package com.adskipper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;

public class AdSkipperAccessibilityService extends AccessibilityService {

    private static final String TAG = "A11yService";
    private static AdSkipperAccessibilityService instance;

    /** Ripple overlay — khởi tạo lazy khi cần, null nếu chưa có quyền overlay */
    private ClickRippleOverlay rippleOverlay;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "Accessibility Service connected");
        broadcast("✅ Accessibility Service đã kết nối");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        rippleOverlay = null;
        instance = null;
    }

    public static AdSkipperAccessibilityService getInstance() { return instance; }

    // ─────────────────────────────────────────────────────────────
    // CLICK — với ripple animation
    // ─────────────────────────────────────────────────────────────

    /**
     * Click tại tọa độ pixel tuyệt đối trên màn hình.
     * Hiển thị ripple animation tại điểm click nếu có quyền overlay.
     */
    public void performClick(int x, int y, GestureResultCallback callback) {
        // Hiện ripple TRƯỚC khi gesture để người dùng thấy ngay
        showRipple(x, y);

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, 50);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(stroke).build();

        boolean ok = dispatchGesture(gesture, callback != null ? callback : new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                Log.d(TAG, "Click OK at (" + x + "," + y + ")");
                broadcast("✅ Click tại (" + x + "," + y + ")");
            }
            @Override public void onCancelled(GestureDescription g) {
                Log.w(TAG, "Click cancelled");
            }
        }, null);

        if (!ok) Log.e(TAG, "dispatchGesture failed");
    }

    /** Click tại tọa độ tỉ lệ 0..1 */
    public void performClickRatio(float xRatio, float yRatio) {
        Point size = getScreenSize();
        int px = Math.round(xRatio * size.x);
        int py = Math.round(yRatio * size.y);
        Log.d(TAG, String.format("Click ratio (%.3f,%.3f) → pixel (%d,%d)", xRatio, yRatio, px, py));
        performClick(px, py, null);
    }

    // ─────────────────────────────────────────────────────────────
    // RIPPLE
    // ─────────────────────────────────────────────────────────────

    /**
     * Hiển thị vòng tròn mờ dần tại điểm click.
     * Chỉ chạy nếu có quyền SYSTEM_ALERT_WINDOW.
     * Gọi từ main thread (đảm bảo bằng mainHandler.post).
     */
    private void showRipple(int x, int y) {
        if (!Settings.canDrawOverlays(this)) return;

        mainHandler.post(() -> {
            try {
                if (rippleOverlay == null) {
                    rippleOverlay = new ClickRippleOverlay(this);
                }
                rippleOverlay.show(x, y);
            } catch (Exception e) {
                Log.e(TAG, "Ripple error: " + e.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // SCREEN SIZE
    // ─────────────────────────────────────────────────────────────

    public Point getScreenSize() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Point p = new Point();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = wm.getCurrentWindowMetrics();
            p.x = metrics.getBounds().width();
            p.y = metrics.getBounds().height();
        } else {
            Display d = wm.getDefaultDisplay();
            d.getRealSize(p);
        }
        return p;
    }

    private void broadcast(String msg) {
        Intent i = new Intent(MainActivity.ACTION_LOG);
        i.putExtra(MainActivity.EXTRA_LOG_MSG, msg);
        sendBroadcast(i);
    }
}
