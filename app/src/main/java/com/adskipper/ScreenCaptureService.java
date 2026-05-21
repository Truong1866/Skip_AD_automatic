package com.adskipper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import android.provider.Settings;
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
 * ScreenCaptureService — service lõi chạy nền.
 *
 * Pipeline:
 *   1Hz capture → YOLO on-device → Template Matching → Claude API fallback
 *   Phát hiện → đợi 1s → click qua AccessibilityService
 *   Thông báo kết quả lên FloatingBubble
 *
 * Hỗ trợ ACTION_PAUSE / ACTION_RESUME / ACTION_STOP từ FloatingBubbleManager.
 */
public class ScreenCaptureService extends Service {

    private static final String TAG        = "ScreenCapture";
    private static final String CHANNEL_ID = "AdSkipperCh";
    private static final int    NOTIF_ID   = 1001;

    private static final long SCAN_INTERVAL_MS = 1000L;  // 1Hz
    private static final long ACTION_DELAY_MS  = 1000L;  // 1s trước khi click

    // ── Static projection data (set từ MainActivity) ─────────────────
    private static int     sResultCode = 0;
    private static Intent  sResultData = null;

    public static void setProjectionData(int code, Intent data) {
        sResultCode = code;
        sResultData = data;
    }

    // ── Singleton ─────────────────────────────────────────────────────
    private static ScreenCaptureService instance;
    public  static ScreenCaptureService getInstance() { return instance; }

    // ── MediaProjection ───────────────────────────────────────────────
    private MediaProjection  mediaProjection;
    private VirtualDisplay   virtualDisplay;
    private ImageReader      imageReader;
    private int screenW, screenH, screenDpi;

    // ── Threading ─────────────────────────────────────────────────────
    private Handler         mainHandler;
    private ExecutorService inferenceThread;
    private final AtomicBoolean isProcessing  = new AtomicBoolean(false);
    private final AtomicBoolean pendingAction = new AtomicBoolean(false);

    // ── State ─────────────────────────────────────────────────────────
    private volatile boolean running = false;
    private volatile boolean paused  = false;

    private volatile float  pendingX, pendingY;
    private volatile String pendingSource;

    // ── AI engines ────────────────────────────────────────────────────
    private YoloDetector    yoloDetector;
    private TemplateMatcher templateMatcher;

    // ── Floating Bubble ───────────────────────────────────────────────
    private FloatingBubbleManager bubble;

    // ── Scan loop ─────────────────────────────────────────────────────
    private final Runnable scanLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!paused) captureAndProcess();
            mainHandler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    // ─────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        instance     = this;
        mainHandler  = new Handler(Looper.getMainLooper());
        inferenceThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AdSkipper-Inference");
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // ── Handle actions từ FloatingBubble ─────────────────────────
        if (intent != null) {
            String action = intent.getAction();
            if (FloatingBubbleManager.ACTION_PAUSE.equals(action)) {
                paused = true;
                log("⏸ Tạm dừng quét");
                updateNotif("⏸ Đã tạm dừng");
                if (bubble != null) bubble.updateStatus("⏸ Đang tạm dừng");
                return START_STICKY;
            }
            if (FloatingBubbleManager.ACTION_RESUME.equals(action)) {
                paused = false;
                log("▶️ Tiếp tục quét");
                updateNotif("🟢 Đang quét 1Hz...");
                if (bubble != null) bubble.updateStatus("🟢 Đang quét 1Hz...");
                return START_STICKY;
            }
            if (FloatingBubbleManager.ACTION_STOP.equals(action)) {
                log("⏹ Nhận lệnh dừng từ bubble");
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        // ── Khởi động lần đầu ────────────────────────────────────────
        startForeground(NOTIF_ID, buildNotif("Đang khởi động..."));

        if (sResultCode == 0 || sResultData == null) {
            log("❌ Thiếu quyền MediaProjection");
            stopSelf();
            return START_NOT_STICKY;
        }

        initAI();
        initCapture();
        initBubble();

        running = true;
        mainHandler.postDelayed(scanLoop, 800);
        log("🟢 Bắt đầu — 1Hz scan, click sau 1s");
        updateNotif("🟢 Đang quét 1Hz...");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        mainHandler.removeCallbacksAndMessages(null);
        inferenceThread.shutdown();

        // Dừng và xóa bubble
        if (bubble != null) {
            bubble.hide();
            bubble = null;
        }

        // Giải phóng capture resources
        if (yoloDetector   != null) yoloDetector.close();
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader    != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();

        log("🔴 Dịch vụ đã dừng");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ─────────────────────────────────────────────────────────────────
    // INIT
    // ─────────────────────────────────────────────────────────────────

    private void initAI() {
        yoloDetector = new YoloDetector();
        boolean ok = yoloDetector.init(this);
        if (ok) {
            log("✅ YOLO model loaded (on-device)");
        } else {
            log("⚠️ Không load được YOLO → dùng Template/Claude fallback");
            yoloDetector = null;
        }

        templateMatcher = new TemplateMatcher();
        log("✅ Template matcher sẵn sàng (" + templateMatcher.getTemplateCount() + " templates)");
    }

    private void initCapture() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = wm.getCurrentWindowMetrics();
            screenW   = metrics.getBounds().width();
            screenH   = metrics.getBounds().height();
            screenDpi = getResources().getDisplayMetrics().densityDpi;
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW   = dm.widthPixels;
            screenH   = dm.heightPixels;
            screenDpi = dm.densityDpi;
        }

        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(sResultCode, new Intent(sResultData));

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AdSkipperVD", screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(), null, null
        );
        Log.d(TAG, "Capture init: " + screenW + "x" + screenH + " @" + screenDpi);
    }

    /**
     * Khởi tạo FloatingBubble — chỉ khi có SYSTEM_ALERT_WINDOW permission.
     */
    private void initBubble() {
        if (!Settings.canDrawOverlays(this)) {
            log("⚠️ Chưa có quyền SYSTEM_ALERT_WINDOW — bỏ qua bubble");
            return;
        }
        mainHandler.post(() -> {
            bubble = new FloatingBubbleManager(this);
            bubble.show();
            log("🫧 Floating bubble đã hiển thị");
        });
    }

    // ─────────────────────────────────────────────────────────────────
    // CAPTURE + PROCESS
    // ─────────────────────────────────────────────────────────────────

    private void captureAndProcess() {
        if (isProcessing.getAndSet(true)) return;

        Bitmap frame = captureFrame();
        if (frame == null) { isProcessing.set(false); return; }

        inferenceThread.submit(() -> {
            try {
                processFrame(frame);
            } finally {
                frame.recycle();
                isProcessing.set(false);
            }
        });
    }

    private Bitmap captureFrame() {
        try {
            Image img = imageReader.acquireLatestImage();
            if (img == null) return null;

            Image.Plane[] planes  = img.getPlanes();
            ByteBuffer    buf     = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride   = planes[0].getRowStride();
            int rowPad      = rowStride - pixelStride * screenW;

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
     * Xử lý frame theo thứ tự ưu tiên:
     *   1. YOLO on-device
     *   2. Template matching
     *   3. Claude API fallback
     */
    private void processFrame(Bitmap frame) {
        if (pendingAction.get()) return;

        // 1. YOLO
        if (yoloDetector != null) {
            List<YoloDetector.Detection> detections = yoloDetector.detect(frame);
            if (!detections.isEmpty()) {
                YoloDetector.Detection best = detections.get(0);
                for (YoloDetector.Detection d : detections)
                    if (d.confidence > best.confidence) best = d;
                log("🎯 YOLO: " + best);
                scheduleClick(best.centerX(), best.centerY(), "YOLO:" + best.label, best.confidence);
                return;
            }
        }

        // 2. Template matching
        if (templateMatcher != null && templateMatcher.getTemplateCount() > 0) {
            TemplateMatcher.MatchResult match = templateMatcher.match(frame);
            if (match.matched) {
                log(String.format("🖼️ Template: %s (%.0f%%)", match.templateName, match.score * 100));
                scheduleClick(match.centerXRatio, match.centerYRatio,
                    "TPL:" + match.templateName, match.score);
                return;
            }
        }

        // 3. Claude API fallback
        if (ClaudeVisionClient.hasApiKey()) {
            ClaudeVisionClient.AdDetectionResult r = ClaudeVisionClient.analyzeScreenshot(frame);
            if (r.hasCloseButton && r.confidence >= 0.70f) {
                log("☁️ Claude: " + r);
                scheduleClick(r.x, r.y, "Claude", r.confidence);
            }
        }
    }

    /**
     * Lên lịch click sau ACTION_DELAY_MS (1 giây) từ khi phát hiện.
     * Thông báo bubble ngay lập tức (badge).
     */
    private void scheduleClick(float x, float y, String source, float confidence) {
        if (pendingAction.getAndSet(true)) return;

        pendingX      = x;
        pendingY      = y;
        pendingSource = source;

        log(String.format("⏱️ [%s] conf=%.0f%% → click sau 1s @ (%.3f,%.3f)",
            source, confidence * 100, x, y));
        updateNotif("Phát hiện! Click sau 1s...");

        // Hiện badge ngay trên bubble
        if (bubble != null) mainHandler.post(() -> bubble.onAdClicked());

        mainHandler.postDelayed(() -> {
            AdSkipperAccessibilityService a11y = AdSkipperAccessibilityService.getInstance();
            if (a11y != null) {
                a11y.performClickRatio(pendingX, pendingY);
                log("👆 Click [" + pendingSource + "] @ ("
                    + String.format("%.3f,%.3f", pendingX, pendingY) + ")");
                updateNotif("✅ Đã đóng QC #" +
                    (bubble != null ? bubble.getClickCount() : "?"));
            } else {
                log("⚠️ Accessibility Service chưa kết nối");
                updateNotif("⚠️ Cần bật Accessibility Service");
            }

            pendingAction.set(false);
            mainHandler.postDelayed(() -> {
                if (running && !paused)
                    updateNotif("🟢 Đang quét 1Hz...");
            }, 1500);

        }, ACTION_DELAY_MS);
    }

    // ─────────────────────────────────────────────────────────────────
    // TEMPLATE MANAGEMENT (gọi từ MainActivity)
    // ─────────────────────────────────────────────────────────────────

    public void addTemplate(String name, Bitmap bmp) {
        if (templateMatcher != null) {
            templateMatcher.addTemplate(name, bmp);
            log("📷 Template thêm: " + name);
        }
    }

    public void clearTemplates() {
        if (templateMatcher != null) {
            templateMatcher.clearTemplates();
            log("🗑️ Đã xóa tất cả template");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // NOTIFICATION
    // ─────────────────────────────────────────────────────────────────

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "AdSkipper", NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotif(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ AdSkipper AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }

    private void updateNotif(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotif(text));
    }

    // ─────────────────────────────────────────────────────────────────
    // LOGGING
    // ─────────────────────────────────────────────────────────────────

    private void log(String msg) {
        Log.d(TAG, msg);
        Intent i = new Intent(MainActivity.ACTION_LOG);
        i.putExtra(MainActivity.EXTRA_LOG_MSG, msg);
        sendBroadcast(i);
    }
}
