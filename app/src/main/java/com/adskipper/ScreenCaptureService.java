package com.adskipper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import android.view.WindowMetrics;
import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core service:
 * - Chụp màn hình 1Hz (1 lần/giây, full resolution)
 * - Chạy YOLO on-device → tìm nút X quảng cáo
 * - Chạy Template Matching 1/4 resolution → tìm hình ảnh tham chiếu
 * - Khi phát hiện → đợi 1 giây → thực hiện click
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCapture";
    private static final String CHANNEL_ID = "AdSkipperCh";
    private static final int NOTIF_ID = 1001;

    // Cố định 1Hz như yêu cầu
    private static final long SCAN_INTERVAL_MS = 1000L;
    // Delay 1s trước khi click sau khi phát hiện
    private static final long ACTION_DELAY_MS = 1000L;

    // Static state từ MainActivity
    private static int sResultCode = 0;
    private static Intent sResultData = null;
    private static boolean sShowOverlay = true;

    public static void setProjectionData(int code, Intent data) {
        sResultCode = code;
        sResultData = data;
    }

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenW, screenH, screenDpi;

    private Handler mainHandler;
    private ExecutorService inferenceThread;  // Single thread cho YOLO
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicBoolean pendingAction = new AtomicBoolean(false);
    private volatile boolean running = false;

    // AI engines
    private YoloDetector yoloDetector;
    private TemplateMatcher templateMatcher;

    // Pending click info
    private volatile float pendingX, pendingY;
    private volatile String pendingSource;

    // Scan loop
    private final Runnable scanLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            captureAndProcess();
            mainHandler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        inferenceThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AdSkipper-Inference");
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sShowOverlay = intent != null && intent.getBooleanExtra("showOverlay", true);
        startForeground(NOTIF_ID, buildNotif("Đang khởi động..."));

        if (sResultCode == 0 || sResultData == null) {
            log("❌ Thiếu quyền MediaProjection"); stopSelf(); return START_NOT_STICKY;
        }

        // Init AI engines
        initAI();
        initCapture();

        running = true;
        mainHandler.postDelayed(scanLoop, 500);
        log("🟢 Dịch vụ bắt đầu — quét 1Hz, click sau 1s");
        updateNotif("Đang quét 1Hz...");
        return START_STICKY;
    }

    private void initAI() {
        // YOLO on-device
        yoloDetector = new YoloDetector();
        boolean yoloOk = yoloDetector.init(this);
        if (yoloOk) {
            log("✅ YOLO model loaded (on-device)");
        } else {
            log("⚠️ Không load được YOLO model → dùng Claude API fallback");
            yoloDetector = null;
        }

        // Template matcher
        templateMatcher = new TemplateMatcher();
        log("✅ Template matcher sẵn sàng (" + templateMatcher.getTemplateCount() + " templates)");
    }

    private void initCapture() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = wm.getCurrentWindowMetrics();
            screenW = metrics.getBounds().width();
            screenH = metrics.getBounds().height();
            screenDpi = getResources().getDisplayMetrics().densityDpi;
        } else {
            dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
            screenDpi = dm.densityDpi;
        }
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        screenDpi = dm.densityDpi;

        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(sResultCode, new Intent(sResultData));

        // ImageReader với full resolution (không giảm chất lượng đầu vào)
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AdSkipperVD", screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(), null, null
        );
        Log.d(TAG, "Capture init: " + screenW + "x" + screenH + " @" + screenDpi + "dpi");
    }

    // ─── Capture + Process ─────────────────────────────────────────────

    private void captureAndProcess() {
        if (isProcessing.getAndSet(true)) return; // skip if previous frame still running

        Bitmap frame = captureFrame();
        if (frame == null) { isProcessing.set(false); return; }

        final Bitmap finalFrame = frame;
        inferenceThread.submit(() -> {
            try {
                processFrame(finalFrame);
            } finally {
                finalFrame.recycle();
                isProcessing.set(false);
            }
        });
    }

    private Bitmap captureFrame() {
        try {
            Image img = imageReader.acquireLatestImage();
            if (img == null) return null;

            Image.Plane[] planes = img.getPlanes();
            ByteBuffer buf = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride   = planes[0].getRowStride();
            int rowPad = rowStride - pixelStride * screenW;

            Bitmap bmp = Bitmap.createBitmap(
                screenW + rowPad / pixelStride, screenH, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            img.close();

            if (rowPad > 0) {
                Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, screenW, screenH);
                bmp.recycle();
                return cropped;
            }
            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "Capture error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Xử lý frame:
     * 1. YOLO on-device (ưu tiên)
     * 2. Template matching
     * 3. Claude API fallback (nếu có key và cả 2 trên fail)
     */
    private void processFrame(Bitmap frame) {
        if (pendingAction.get()) return; // đang chờ click, bỏ qua frame này

        // ── 1. YOLO on-device ──────────────────────────────────────────
        if (yoloDetector != null) {
            List<YoloDetector.Detection> detections = yoloDetector.detect(frame);
            if (!detections.isEmpty()) {
                YoloDetector.Detection best = detections.get(0);
                for (YoloDetector.Detection d : detections) {
                    if (d.confidence > best.confidence) best = d;
                }
                log("🎯 YOLO: " + best);
                scheduleClick(best.centerX(), best.centerY(), "YOLO:" + best.label, best.confidence);
                return;
            }
        }

        // ── 2. Template matching ───────────────────────────────────────
        if (templateMatcher.getTemplateCount() > 0) {
            TemplateMatcher.MatchResult match = templateMatcher.match(frame);
            if (match.matched) {
                log(String.format("🖼️ Template match: %s (%.0f%%)", match.templateName, match.score * 100));
                scheduleClick(match.centerXRatio, match.centerYRatio, "TPL:" + match.templateName, match.score);
                return;
            }
        }

        // ── 3. Claude API fallback ─────────────────────────────────────
        if (ClaudeVisionClient.hasApiKey()) {
            ClaudeVisionClient.AdDetectionResult r = ClaudeVisionClient.analyzeScreenshot(frame);
            if (r.hasCloseButton && r.confidence >= 0.70f) {
                log("☁️ Claude: " + r);
                scheduleClick(r.x, r.y, "Claude", r.confidence);
            }
        }
    }

    /**
     * Lên lịch click sau 1 giây từ khi phát hiện
     */
    private void scheduleClick(float x, float y, String source, float confidence) {
        if (pendingAction.getAndSet(true)) return;

        pendingX = x;
        pendingY = y;
        pendingSource = source;

        log(String.format("⏱️ Phát hiện [%s] conf=%.0f%% → click sau 1s tại (%.3f, %.3f)",
            source, confidence * 100, x, y));
        updateNotif("Phát hiện! Click sau 1s...");

        mainHandler.postDelayed(() -> {
            AdSkipperAccessibilityService a11y = AdSkipperAccessibilityService.getInstance();
            if (a11y != null) {
                a11y.performClickRatio(pendingX, pendingY);
                log("👆 Đã click [" + pendingSource + "] tại (" +
                    String.format("%.3f, %.3f)", pendingX, pendingY));
                updateNotif("✅ Đã đóng quảng cáo!");
            } else {
                log("⚠️ Accessibility Service chưa kết nối");
            }
            pendingAction.set(false);

            // Sau click, đợi thêm 1s trước khi quét lại
            mainHandler.postDelayed(() -> updateNotif("Đang quét 1Hz..."), 1000);
        }, ACTION_DELAY_MS);
    }

    // ─── Notification ──────────────────────────────────────────────────
    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "AdSkipper", NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotif(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ AdSkipper AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentIntent(pi).setOngoing(true).setSilent(true).build();
    }

    private void updateNotif(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotif(text));
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        Intent i = new Intent(MainActivity.ACTION_LOG);
        i.putExtra(MainActivity.EXTRA_LOG_MSG, msg);
        sendBroadcast(i);
    }

    // ─── Template management (called from MainActivity) ────────────────
    public void addTemplate(String name, Bitmap bmp) {
        if (templateMatcher != null) {
            templateMatcher.addTemplate(name, bmp);
            log("📷 Đã thêm template: " + name);
        }
    }

    public void clearTemplates() {
        if (templateMatcher != null) {
            templateMatcher.clearTemplates();
            log("🗑️ Đã xóa tất cả template");
        }
    }

    public static ScreenCaptureService instance;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        mainHandler.removeCallbacks(scanLoop);
        inferenceThread.shutdown();
        if (yoloDetector != null) yoloDetector.close();
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();
        log("🔴 Dịch vụ đã dừng");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // Allow MainActivity to reference this service
    public static ScreenCaptureService getInstance() { return instance; }
}
