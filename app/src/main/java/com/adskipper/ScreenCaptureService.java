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
 * Pipeline (dual-interval):
 *   Template Matching: 1s/lần (nhanh, on-device)
 *   YOLO inference:    3s/lần (nặng hơn, on-device)
 *   Claude API:        fallback khi YOLO+Template fail
 *
 *   Phát hiện → đợi 1s → click qua AccessibilityService
 *   Thông báo kết quả lên FloatingBubble
 */
public class ScreenCaptureService extends Service {

    private static final String TAG        = "ScreenCapture";
    private static final String CHANNEL_ID = "AdSkipperCh";
    private static final int    NOTIF_ID   = 1001;

    // Dual scan intervals
    private static final long TEMPLATE_INTERVAL_MS = 2000L;  // 2s/lần cho template (full-res NCC)
    private static final long YOLO_INTERVAL_MS      = 3000L;  // 3s/lần cho YOLO
    private static final long ACTION_DELAY_MS        = 1000L;  // 1s trước khi click

    // Kênh notification riêng để alert có thể có âm thanh/vibrate
    private static final String ALERT_CHANNEL_ID = "AdSkipperAlert";
    private static final int    ALERT_NOTIF_ID   = 1002;

    // ── Static projection data ─────────────────────────────────────
    private static int     sResultCode = 0;
    private static Intent  sResultData = null;

    public static void setProjectionData(int code, Intent data) {
        sResultCode = code;
        sResultData = new Intent(data); // copy để tránh bị recycle
    }

    // ── Singleton ─────────────────────────────────────────────────
    private static ScreenCaptureService instance;
    public  static ScreenCaptureService getInstance() { return instance; }

    // ── MediaProjection ───────────────────────────────────────────
    private MediaProjection  mediaProjection;
    private VirtualDisplay   virtualDisplay;
    private ImageReader      imageReader;
    private int screenW, screenH, screenDpi;

    // ── Threading ─────────────────────────────────────────────────
    private Handler         mainHandler;
    private ExecutorService inferenceThread;
    private final AtomicBoolean isProcessingTemplate = new AtomicBoolean(false);
    private final AtomicBoolean isProcessingYolo     = new AtomicBoolean(false);
    private final AtomicBoolean pendingAction        = new AtomicBoolean(false);

    // ── State ─────────────────────────────────────────────────────
    private volatile boolean running = false;
    private volatile boolean paused  = false;

    private volatile float  pendingX, pendingY;
    private volatile String pendingSource;

    // ── AI engines ────────────────────────────────────────────────
    private YoloDetector    yoloDetector;
    private TemplateMatcher templateMatcher;

    // ── Floating Bubble ───────────────────────────────────────────
    private FloatingBubbleManager bubble;

    // ── Template scan loop (2s) ───────────────────────────────────
    private final Runnable templateScanLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!paused) captureAndProcessTemplate();
            mainHandler.postDelayed(this, TEMPLATE_INTERVAL_MS);
        }
    };

    // ── YOLO scan loop (3s) ───────────────────────────────────────
    private final Runnable yoloScanLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!paused) captureAndProcessYolo();
            mainHandler.postDelayed(this, YOLO_INTERVAL_MS);
        }
    };

    // ─────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        instance     = this;
        mainHandler  = new Handler(Looper.getMainLooper());
        inferenceThread = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "AdSkipper-Inference");
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // ── Handle actions từ FloatingBubble ──────────────────────
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
                updateNotif("🟢 Template:2s | YOLO:3s");
                if (bubble != null) bubble.updateStatus("🟢 Template:2s | YOLO:3s");
                return START_STICKY;
            }
            if (FloatingBubbleManager.ACTION_STOP.equals(action)) {
                log("⏹ Nhận lệnh dừng từ bubble");
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        // ── Khởi động lần đầu ────────────────────────────────────
        startForeground(NOTIF_ID, buildNotif("Đang khởi động..."));

        if (sResultCode == 0 || sResultData == null) {
            log("❌ Thiếu quyền MediaProjection");
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean captureOk = initCapture();
        if (!captureOk) {
            log("❌ Không khởi tạo được screen capture");
            stopSelf();
            return START_NOT_STICKY;
        }

        initAI();
        initBubble();

        running = true;

        // Chạy 2 loop song song với delay khởi động khác nhau
        mainHandler.postDelayed(templateScanLoop, 1000L);  // Template bắt đầu sau 1s
        mainHandler.postDelayed(yoloScanLoop,     2000L);  // YOLO bắt đầu sau 2s

        log("🟢 Bắt đầu — Template:2s, YOLO:3s, click sau 1s");
        updateNotif("🟢 Template:2s | YOLO:3s");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        mainHandler.removeCallbacksAndMessages(null);
        inferenceThread.shutdown();

        if (bubble != null) { bubble.hide(); bubble = null; }
        if (yoloDetector   != null) yoloDetector.close();
        if (virtualDisplay  != null) virtualDisplay.release();
        if (imageReader     != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();

        log("🔴 Dịch vụ đã dừng");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ─────────────────────────────────────────────────────────────
    // INIT
    // ─────────────────────────────────────────────────────────────

    private void initAI() {
        // Khởi tạo YOLO
        yoloDetector = new YoloDetector();
        boolean yoloOk = yoloDetector.init(this);
        if (yoloOk) {
            log("✅ YOLO model loaded (on-device, 3s/lần)");
            // Broadcast YOLO status về MainActivity
            Intent i = new Intent(MainActivity.ACTION_YOLO_STATUS);
            i.putExtra(MainActivity.EXTRA_YOLO_OK, true);
            sendBroadcast(i);
        } else {
            log("⚠️ Không load được YOLO model (kiểm tra file best.tflite trong assets)");
            yoloDetector = null;
            Intent i = new Intent(MainActivity.ACTION_YOLO_STATUS);
            i.putExtra(MainActivity.EXTRA_YOLO_OK, false);
            sendBroadcast(i);
        }

        // Khởi tạo Template Matcher
        templateMatcher = new TemplateMatcher();
        log("✅ Template matcher sẵn sàng (" + templateMatcher.getTemplateCount() + " templates, 1s/lần)");
    }

    private boolean initCapture() {
        try {
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

            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection null — token expired?");
                return false;
            }

            imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "AdSkipperVD", screenW, screenH, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null
            );
            Log.d(TAG, "Capture init OK: " + screenW + "x" + screenH + " @" + screenDpi);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "initCapture failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Khởi tạo FloatingBubble — post lên main thread, retry nếu cần.
     */
    private void initBubble() {
        if (!Settings.canDrawOverlays(this)) {
            log("⚠️ Chưa có quyền SYSTEM_ALERT_WINDOW — bubble không hiển thị");
            return;
        }
        mainHandler.post(() -> {
            try {
                bubble = new FloatingBubbleManager(this);
                bubble.show();
                log("🫧 Floating bubble đã hiển thị");
            } catch (Exception e) {
                Log.e(TAG, "Bubble init failed: " + e.getMessage(), e);
                log("⚠️ Lỗi khởi tạo bubble: " + e.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // CAPTURE
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    // TEMPLATE SCAN (1s interval)
    // ─────────────────────────────────────────────────────────────

    private void captureAndProcessTemplate() {
        if (pendingAction.get()) return;
        if (isProcessingTemplate.getAndSet(true)) return;

        Bitmap frame = captureFrame();
        if (frame == null) { isProcessingTemplate.set(false); return; }

        inferenceThread.submit(() -> {
            try {
                processTemplate(frame);
            } finally {
                frame.recycle();
                isProcessingTemplate.set(false);
            }
        });
    }

    private void processTemplate(Bitmap frame) {
        if (pendingAction.get()) return;

        // Template matching (full-res NCC)
        if (templateMatcher != null && templateMatcher.getTemplateCount() > 0) {
            TemplateMatcher.MatchResult match = templateMatcher.match(frame);
            if (match.matched) {
                log(String.format("🖼️ Template [%s] %.0f%% — tâm pixel (%d,%d) ratio(%.3f,%.3f)",
                    match.templateName, match.score * 100,
                    match.pixelX, match.pixelY,
                    match.centerXRatio, match.centerYRatio));
                scheduleClick(match.centerXRatio, match.centerYRatio,
                    "Template:" + match.templateName, match.score);
                return;
            }
        }

        // Claude API fallback (chỉ khi không có YOLO và Template fail)
        if (yoloDetector == null && ClaudeVisionClient.hasApiKey()) {
            ClaudeVisionClient.AdDetectionResult r = ClaudeVisionClient.analyzeScreenshot(frame);
            if (r.hasCloseButton && r.confidence >= 0.70f) {
                log("☁️ Claude: " + r);
                scheduleClick(r.x, r.y, "Claude", r.confidence);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // YOLO SCAN (3s interval)
    // ─────────────────────────────────────────────────────────────

    private void captureAndProcessYolo() {
        if (yoloDetector == null) return;
        if (pendingAction.get()) return;
        if (isProcessingYolo.getAndSet(true)) return;

        Bitmap frame = captureFrame();
        if (frame == null) { isProcessingYolo.set(false); return; }

        inferenceThread.submit(() -> {
            try {
                processYolo(frame);
            } finally {
                frame.recycle();
                isProcessingYolo.set(false);
            }
        });
    }

    private void processYolo(Bitmap frame) {
        if (pendingAction.get()) return;
        if (yoloDetector == null) return;

        List<YoloDetector.Detection> detections = yoloDetector.detect(frame);
        if (!detections.isEmpty()) {
            YoloDetector.Detection best = detections.get(0);
            for (YoloDetector.Detection d : detections)
                if (d.confidence > best.confidence) best = d;
            log("🎯 YOLO: " + best);
            scheduleClick(best.centerX(), best.centerY(), "YOLO:" + best.label, best.confidence);
            return;
        }

        // Claude API fallback sau YOLO
        if (ClaudeVisionClient.hasApiKey()) {
            ClaudeVisionClient.AdDetectionResult r = ClaudeVisionClient.analyzeScreenshot(frame);
            if (r.hasCloseButton && r.confidence >= 0.70f) {
                log("☁️ Claude: " + r);
                scheduleClick(r.x, r.y, "Claude", r.confidence);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // SCHEDULE CLICK
    // ─────────────────────────────────────────────────────────────

    private void scheduleClick(float x, float y, String source, float confidence) {
        if (pendingAction.getAndSet(true)) return;

        pendingX      = x;
        pendingY      = y;
        pendingSource = source;

        log(String.format("⏱️ [%s] conf=%.0f%% → click sau 1s @ (%.3f,%.3f)",
            source, confidence * 100, x, y));
        updateNotif("🔍 Phát hiện QC! Đang click...");

        if (bubble != null) mainHandler.post(() -> bubble.onAdClicked());

        mainHandler.postDelayed(() -> {
            AdSkipperAccessibilityService a11y = AdSkipperAccessibilityService.getInstance();
            if (a11y != null) {
                a11y.performClickRatio(pendingX, pendingY);

                int count = (bubble != null) ? bubble.getClickCount() : -1;
                String countStr = (count >= 0) ? String.valueOf(count) : "?";

                log("👆 Click [" + pendingSource + "] @ ("
                    + String.format("%.3f,%.3f", pendingX, pendingY) + ")"
                    + " — Tổng: #" + countStr);
                updateNotif("✅ Đã đóng QC #" + countStr);

                // ── Gửi notification popup riêng ──────────────────────
                sendDetectionAlert(pendingSource, confidence, count >= 0 ? count : 0);

            } else {
                log("⚠️ Accessibility Service chưa kết nối");
                updateNotif("⚠️ Cần bật Accessibility Service");
            }

            pendingAction.set(false);
            mainHandler.postDelayed(() -> {
                if (running && !paused)
                    updateNotif("🟢 Template:2s | YOLO:3s");
            }, 1500);

        }, ACTION_DELAY_MS);
    }

    // ─────────────────────────────────────────────────────────────
    // TEMPLATE MANAGEMENT
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    // NOTIFICATION
    // ─────────────────────────────────────────────────────────────

    private void createChannel() {
        // Channel thường (ongoing, im lặng)
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "AdSkipper — Trạng thái", NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        ch.enableVibration(false);

        // Channel cảnh báo khi phát hiện QC (có âm thanh + vibrate)
        NotificationChannel alertCh = new NotificationChannel(
            ALERT_CHANNEL_ID, "AdSkipper — Phát hiện QC", NotificationManager.IMPORTANCE_HIGH);
        alertCh.setDescription("Thông báo khi phát hiện quảng cáo và click đóng");
        alertCh.enableVibration(true);
        alertCh.setVibrationPattern(new long[]{0, 150, 80, 150});
        // Cho phép âm thanh mặc định của hệ thống
        alertCh.setSound(
            android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
            new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        );

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(ch);
        nm.createNotificationChannel(alertCh);
    }

    /**
     * Gửi thông báo popup riêng khi phát hiện + đóng QC thành công.
     * Dùng channel ALERT (IMPORTANCE_HIGH) để hiện heads-up notification.
     *
     * @param source      Nguồn phát hiện: "Template:tên", "YOLO:label", "Claude"
     * @param confidence  Độ chính xác 0..1
     * @param clickCount  Tổng số lần đã đóng QC
     */
    private void sendDetectionAlert(String source, float confidence, int clickCount) {
        try {
            PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            String title = "⚡ AdSkipper — Đã đóng quảng cáo #" + clickCount;
            String body  = String.format("Nguồn: %s · Độ chính xác: %.0f%%", source, confidence * 100);

            Notification alertNotif = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .build();

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                // Dùng ID động để không override nhau (tối đa lưu 10 notif)
                int alertId = ALERT_NOTIF_ID + (clickCount % 10);
                nm.notify(alertId, alertNotif);
            }
        } catch (Exception e) {
            Log.e(TAG, "sendDetectionAlert error: " + e.getMessage());
        }
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

    // ─────────────────────────────────────────────────────────────
    // LOGGING
    // ─────────────────────────────────────────────────────────────

    private void log(String msg) {
        Log.d(TAG, msg);
        Intent i = new Intent(MainActivity.ACTION_LOG);
        i.putExtra(MainActivity.EXTRA_LOG_MSG, msg);
        sendBroadcast(i);
    }
}
