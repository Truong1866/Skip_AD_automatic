package com.adskipper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class YoloDetector {

    private static final String TAG            = "YoloDetector";
    private static final String MODEL_ASSET    = "best.tflite";
    private static final int    INPUT_SIZE     = 640;
    private static final float  CONF_THRESHOLD = 0.45f;
    private static final float  IOU_THRESHOLD  = 0.45f;

    public static class Detection {
        public RectF  box;           // góc trên-trái / dưới-phải, ratio 0..1 so với ảnh gốc
        public float  confidence;
        public int    classId;
        public String label;

        public Detection(RectF box, float confidence, int classId, String label) {
            this.box = box;
            this.confidence = confidence;
            this.classId = classId;
            this.label = label;
        }

        /**
         * Tâm X của bounding box, ratio 0..1 so với chiều rộng ảnh gốc.
         * Đây là điểm click mục tiêu.
         */
        public float centerX() { return (box.left + box.right)  / 2f; }

        /**
         * Tâm Y của bounding box, ratio 0..1 so với chiều cao ảnh gốc.
         * Đây là điểm click mục tiêu.
         */
        public float centerY() { return (box.top  + box.bottom) / 2f; }

        @Override public String toString() {
            return String.format("[%s] %.0f%% center=(%.3f,%.3f) box=(%.3f,%.3f)-(%.3f,%.3f)",
                    label, confidence * 100,
                    centerX(), centerY(),
                    box.left, box.top, box.right, box.bottom);
        }
    }

    private Interpreter tflite;
    private boolean     initialized = false;

    // Letterbox params — được set trong letterboxResize(), dùng lại trong postprocess()
    private int   origW, origH;
    private float lbScale;   // scale dùng để resize ảnh gốc vào INPUT_SIZE
    private float lbPadX;    // padding pixel (horizontal) trong không gian 640×640
    private float lbPadY;    // padding pixel (vertical)   trong không gian 640×640

    // Output buffer — YOLOv8 TFLite: [1, 4+nc, 8400]
    private float[][][] outputBuffer;

    private String[] classNames = {"close_button", "x_button", "skip_button", "ad_close"};

    // ─── Init ────────────────────────────────────────────────────────────────

    public boolean init(Context context) {
        try {
            MappedByteBuffer modelBuffer = FileUtil.loadMappedFile(context, MODEL_ASSET);

            Interpreter.Options opts = new Interpreter.Options();
            opts.setNumThreads(4);
            opts.setUseXNNPACK(true);
            tflite = new Interpreter(modelBuffer, opts);
            Log.d(TAG, "✅ TFLite loaded — CPU, XNNPACK, 4 threads");

            int[] outShape = tflite.getOutputTensor(0).shape();
            outputBuffer = new float[outShape[0]][outShape[1]][outShape[2]];
            Log.d(TAG, "Output shape: [" + outShape[0] + "," + outShape[1] + "," + outShape[2] + "]");

            initialized = true;
            return true;

        } catch (Exception e) {
            Log.e(TAG, "❌ Không load được TFLite model: " + e.getMessage(), e);
            return false;
        }
    }

    // ─── Detect ──────────────────────────────────────────────────────────────

    public List<Detection> detect(Bitmap bitmap) {
        if (!initialized) return Collections.emptyList();

        origW = bitmap.getWidth();
        origH = bitmap.getHeight();

        Bitmap resized = letterboxResize(bitmap, INPUT_SIZE);
        ByteBuffer input = bitmapToByteBuffer(resized);
        resized.recycle();

        tflite.run(input, outputBuffer);

        return postprocess(outputBuffer);
    }

    // ─── Preprocess ──────────────────────────────────────────────────────────

    private Bitmap letterboxResize(Bitmap src, int size) {
        int   w = src.getWidth(), h = src.getHeight();
        // scale sao cho cạnh dài nhất khớp INPUT_SIZE
        lbScale = Math.min((float) size / w, (float) size / h);
        int nw = Math.round(w * lbScale);
        int nh = Math.round(h * lbScale);

        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
        Bitmap out    = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        cv.drawColor(android.graphics.Color.rgb(114, 114, 114)); // letterbox color

        // padding để căn giữa ảnh trong khung 640×640
        lbPadX = (size - nw) / 2f;
        lbPadY = (size - nh) / 2f;
        cv.drawBitmap(scaled, lbPadX, lbPadY, null);
        scaled.recycle();

        return out;
    }

    private ByteBuffer bitmapToByteBuffer(Bitmap bmp) {
        int size = INPUT_SIZE;
        ByteBuffer buf = ByteBuffer.allocateDirect(1 * size * size * 3 * 4);
        buf.order(ByteOrder.nativeOrder());

        int[] pixels = new int[size * size];
        bmp.getPixels(pixels, 0, size, 0, 0, size, size);

        for (int px : pixels) {
            buf.putFloat(((px >> 16) & 0xFF) / 255.0f); // R
            buf.putFloat(((px >>  8) & 0xFF) / 255.0f); // G
            buf.putFloat(( px        & 0xFF) / 255.0f); // B
        }

        buf.rewind();
        return buf;
    }

    // ─── Postprocess ─────────────────────────────────────────────────────────

    /**
     * Chuyển đổi output model → tọa độ ratio 0..1 so với ảnh gốc.
     *
     * Model output cx,cy,bw,bh là pixel trong không gian INPUT_SIZE (640×640)
     * đã letterbox. Để ra ảnh gốc:
     *
     *   pixel_goc_x = (cx_lb - lbPadX) / lbScale
     *   ratio_x     = pixel_goc_x / origW
     *               = (cx_lb - lbPadX) / lbScale / origW
     *
     * Lưu ý: (cx - lbPadX) / (lbScale * origW) ≠ (cx - lbPadX) / lbScale / origW
     * khi lbPadX != 0 vì thứ tự chia khác nhau — đây là lỗi trong code cũ.
     */
    private List<Detection> postprocess(float[][][] raw) {
        int dim1 = raw[0].length;
        int dim2 = raw[0][0].length;

        // YOLOv8: output shape có thể là [1, 4+nc, 8400] (transposed)
        //         hoặc [1, 8400, 4+nc] (không transposed)
        boolean transposed = (dim1 < dim2); // dim1 = 4+nc nếu transposed
        int numAnchors = transposed ? dim2 : dim1;
        int numAttrs   = transposed ? dim1 : dim2;
        int numClasses = numAttrs - 4;
        if (numClasses <= 0) numClasses = 1;

        List<float[]>  boxes   = new ArrayList<>();
        List<Float>    scores  = new ArrayList<>();
        List<Integer>  classes = new ArrayList<>();

        for (int a = 0; a < numAnchors; a++) {
            // cx, cy, bw, bh — tọa độ pixel trong không gian 640×640 letterbox
            float cx, cy, bw, bh, maxScore = 0;
            int   bestClass = 0;

            if (transposed) {
                cx = raw[0][0][a]; cy = raw[0][1][a];
                bw = raw[0][2][a]; bh = raw[0][3][a];
                for (int c = 0; c < numClasses; c++) {
                    float s = raw[0][4 + c][a];
                    if (s > maxScore) { maxScore = s; bestClass = c; }
                }
            } else {
                cx = raw[0][a][0]; cy = raw[0][a][1];
                bw = raw[0][a][2]; bh = raw[0][a][3];
                for (int c = 0; c < numClasses; c++) {
                    float s = raw[0][a][4 + c];
                    if (s > maxScore) { maxScore = s; bestClass = c; }
                }
            }

            if (maxScore < CONF_THRESHOLD) continue;

            // ── Chuyển từ không gian letterbox 640×640 → ratio ảnh gốc ──
            //
            // Bước 1: bỏ letterbox padding → pixel trong ảnh đã scale
            //   x_scaled = cx - lbPadX  (đơn vị: pixel trong ảnh scaled nw×nh)
            //
            // Bước 2: bỏ scale → pixel trong ảnh gốc
            //   x_orig = x_scaled / lbScale
            //
            // Bước 3: normalize → ratio
            //   x_ratio = x_orig / origW
            //           = (cx - lbPadX) / lbScale / origW
            //
            // Code cũ viết: (cx - lbPadX) / (lbScale * origW)
            // => chia lbPadX theo cả lbScale*origW thay vì chỉ lbScale → SAI khi padding != 0

            float x1 = clamp01( (cx - bw / 2f - lbPadX) / lbScale / origW );
            float y1 = clamp01( (cy - bh / 2f - lbPadY) / lbScale / origH );
            float x2 = clamp01( (cx + bw / 2f - lbPadX) / lbScale / origW );
            float y2 = clamp01( (cy + bh / 2f - lbPadY) / lbScale / origH );

            if (x2 <= x1 || y2 <= y1) continue; // degenerate box

            boxes.add(new float[]{x1, y1, x2, y2});
            scores.add(maxScore);
            classes.add(bestClass);
        }

        return applyNMS(boxes, scores, classes);
    }

    private List<Detection> applyNMS(List<float[]> boxes, List<Float> scores,
                                      List<Integer> classes) {
        List<Detection> out = new ArrayList<>();
        boolean[] sup = new boolean[boxes.size()];
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) idx.add(i);
        idx.sort((a, b) -> Float.compare(scores.get(b), scores.get(a)));

        for (int i = 0; i < idx.size(); i++) {
            int ii = idx.get(i);
            if (sup[ii]) continue;
            float[] b  = boxes.get(ii);
            String lbl = (classes.get(ii) < classNames.length)
                    ? classNames[classes.get(ii)] : "cls_" + classes.get(ii);
            out.add(new Detection(
                    new RectF(b[0], b[1], b[2], b[3]),
                    scores.get(ii), classes.get(ii), lbl));
            for (int j = i + 1; j < idx.size(); j++) {
                int jj = idx.get(j);
                if (!sup[jj] && classes.get(ii).equals(classes.get(jj))
                        && iou(b, boxes.get(jj)) > IOU_THRESHOLD)
                    sup[jj] = true;
            }
        }
        return out;
    }

    private float iou(float[] a, float[] b) {
        float ix = Math.max(0, Math.min(a[2], b[2]) - Math.max(a[0], b[0]));
        float iy = Math.max(0, Math.min(a[3], b[3]) - Math.max(a[1], b[1]));
        float inter = ix * iy;
        float ua = (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - inter;
        return ua <= 0 ? 0 : inter / ua;
    }

    private float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    public void setClassNames(String[] names) { this.classNames = names; }

    public void close() {
        if (tflite != null) tflite.close();
    }
}
