package com.adskipper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.support.common.FileUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class YoloDetector {

    private static final String TAG = "YoloDetector";
    private static final String MODEL_ASSET = "best.tflite"; // ← tên file của bạn
    private static final int   INPUT_SIZE    = 640;
    private static final float CONF_THRESHOLD = 0.45f;
    private static final float IOU_THRESHOLD  = 0.45f;

    public static class Detection {
        public RectF  box;
        public float  confidence;
        public int    classId;
        public String label;

        public Detection(RectF box, float confidence, int classId, String label) {
            this.box = box; this.confidence = confidence;
            this.classId = classId; this.label = label;
        }
        public float centerX() { return (box.left + box.right)  / 2f; }
        public float centerY() { return (box.top  + box.bottom) / 2f; }

        @Override public String toString() {
            return String.format("[%s] %.0f%% @ (%.2f,%.2f)-(%.2f,%.2f)",
                    label, confidence*100, box.left, box.top, box.right, box.bottom);
        }
    }

    private Interpreter  tflite;
    private GpuDelegate  gpuDelegate;
    private boolean      initialized = false;
    private int          origW, origH;
    private float        lbScale, lbPadX, lbPadY;

    // Output buffer — YOLOv8 TFLite: [1, 4+nc, 8400]
    private float[][][]  outputBuffer;

    private String[] classNames = {"close_button","x_button","skip_button","ad_close"};

    // ─── Init ─────────────────────────────────────────────────────────
    public boolean init(Context context) {
        try {
            // Load model qua memory-mapped I/O (nhanh nhất, không copy vào RAM)
            MappedByteBuffer modelBuffer =
                    FileUtil.loadMappedFile(context, MODEL_ASSET);

            // Thử GPU delegate trước (nhanh hơn ~2-4× trên thiết bị có GPU)
            Interpreter.Options opts = new Interpreter.Options();
            try {
                gpuDelegate = new GpuDelegate();
                opts.addDelegate(gpuDelegate);
                tflite = new Interpreter(modelBuffer, opts);
                Log.d(TAG, "✅ TFLite loaded với GPU delegate");
            } catch (Exception gpuEx) {
                // GPU delegate không hỗ trợ → fallback CPU
                Log.w(TAG, "GPU delegate thất bại, dùng CPU: " + gpuEx.getMessage());
                if (gpuDelegate != null) { gpuDelegate.close(); gpuDelegate = null; }
                opts = new Interpreter.Options();
                opts.setNumThreads(4);          // 4 CPU threads
                opts.setUseXNNPACK(true);       // XNNPACK tăng tốc CPU
                tflite = new Interpreter(modelBuffer, opts);
                Log.d(TAG, "✅ TFLite loaded với CPU (XNNPACK, 4 threads)");
            }

            // Đọc output shape từ model để cấp phát buffer đúng
            int[] outShape = tflite.getOutputTensor(0).shape();
            // outShape thường là [1, 4+nc, 8400] hoặc [1, 8400, 4+nc]
            outputBuffer = new float[outShape[0]][outShape[1]][outShape[2]];
            Log.d(TAG, "Output shape: [" + outShape[0] + "," + outShape[1] + "," + outShape[2] + "]");

            initialized = true;
            return true;

        } catch (Exception e) {
            Log.e(TAG, "❌ Không load được TFLite model: " + e.getMessage(), e);
            return false;
        }
    }

    // ─── Detect ───────────────────────────────────────────────────────
    public List<Detection> detect(Bitmap bitmap) {
        if (!initialized) return Collections.emptyList();

        origW = bitmap.getWidth();
        origH = bitmap.getHeight();

        // 1. Preprocess → ByteBuffer (float32, NCHW hoặc NHWC tuỳ model)
        Bitmap resized  = letterboxResize(bitmap, INPUT_SIZE);
        ByteBuffer input = bitmapToByteBuffer(resized);
        resized.recycle();

        // 2. Run inference
        tflite.run(input, outputBuffer);

        // 3. Postprocess
        return postprocess(outputBuffer);
    }

    // ─── Preprocess ───────────────────────────────────────────────────

    /** Letterbox: giữ aspect ratio, pad bằng màu xám (114,114,114) */
    private Bitmap letterboxResize(Bitmap src, int size) {
        int   w = src.getWidth(), h = src.getHeight();
        float scale = Math.min((float) size / w, (float) size / h);
        int   nw = Math.round(w * scale), nh = Math.round(h * scale);

        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
        Bitmap out    = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        cv.drawColor(android.graphics.Color.rgb(114, 114, 114));
        int px = (size - nw) / 2, py = (size - nh) / 2;
        cv.drawBitmap(scaled, px, py, null);
        scaled.recycle();

        lbScale = scale; lbPadX = px; lbPadY = py;
        return out;
    }

    /**
     * Bitmap → ByteBuffer float32 NHWC [1,640,640,3], normalize [0,1].
     * YOLOv8 TFLite thường dùng NHWC. Nếu model của bạn dùng NCHW thì đổi
     * thứ tự vòng lặp (xem comment bên dưới).
     */
    private ByteBuffer bitmapToByteBuffer(Bitmap bmp) {
        int   size = INPUT_SIZE;
        // 1 batch × H × W × 3 channels × 4 bytes (float32)
        ByteBuffer buf = ByteBuffer.allocateDirect(1 * size * size * 3 * 4);
        buf.order(ByteOrder.nativeOrder());

        int[] pixels = new int[size * size];
        bmp.getPixels(pixels, 0, size, 0, 0, size, size);

        // NHWC layout (mặc định TFLite)
        for (int px : pixels) {
            buf.putFloat(((px >> 16) & 0xFF) / 255.0f); // R
            buf.putFloat(((px >>  8) & 0xFF) / 255.0f); // G
            buf.putFloat(( px        & 0xFF) / 255.0f); // B
        }

        /* Nếu model cần NCHW, thay vòng lặp trên bằng:
        for (int c = 0; c < 3; c++) {
            int shift = (c == 0) ? 16 : (c == 1) ? 8 : 0;
            for (int px : pixels)
                buf.putFloat(((px >> shift) & 0xFF) / 255.0f);
        }
        */

        buf.rewind();
        return buf;
    }

    // ─── Postprocess ──────────────────────────────────────────────────

    private List<Detection> postprocess(float[][][] raw) {
        int dim1 = raw[0].length;
        int dim2 = raw[0][0].length;

        // Xác định layout: [4+nc, 8400] hoặc [8400, 4+nc]
        // YOLOv8 TFLite xuất [1, 4+nc, 8400] → transposed=true
        boolean transposed = (dim1 < dim2);
        int numAnchors = transposed ? dim2 : dim1;
        int numAttrs   = transposed ? dim1 : dim2;
        int numClasses = numAttrs - 4;
        if (numClasses <= 0) numClasses = 1;

        List<float[]>   boxes   = new ArrayList<>();
        List<Float>     scores  = new ArrayList<>();
        List<Integer>   classes = new ArrayList<>();

        for (int a = 0; a < numAnchors; a++) {
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

            // Unmap từ letterbox coords → normalized [0,1]
            float x1 = clamp01((cx - bw/2 - lbPadX) / (lbScale * origW));
            float y1 = clamp01((cy - bh/2 - lbPadY) / (lbScale * origH));
            float x2 = clamp01((cx + bw/2 - lbPadX) / (lbScale * origW));
            float y2 = clamp01((cy + bh/2 - lbPadY) / (lbScale * origH));

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
            out.add(new Detection(new RectF(b[0],b[1],b[2],b[3]),
                    scores.get(ii), classes.get(ii), lbl));
            for (int j = i+1; j < idx.size(); j++) {
                int jj = idx.get(j);
                if (!sup[jj] && classes.get(ii).equals(classes.get(jj))
                        && iou(b, boxes.get(jj)) > IOU_THRESHOLD)
                    sup[jj] = true;
            }
        }
        return out;
    }

    private float iou(float[] a, float[] b) {
        float ix = Math.max(0, Math.min(a[2],b[2]) - Math.max(a[0],b[0]));
        float iy = Math.max(0, Math.min(a[3],b[3]) - Math.max(a[1],b[1]));
        float inter = ix * iy;
        float ua = (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - inter;
        return ua <= 0 ? 0 : inter / ua;
    }

    private float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    public void setClassNames(String[] names) { this.classNames = names; }

    public void close() {
        if (tflite    != null) tflite.close();
        if (gpuDelegate != null) gpuDelegate.close();
    }
}