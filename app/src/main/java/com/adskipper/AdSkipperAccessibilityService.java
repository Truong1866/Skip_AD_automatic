package com.adskipper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;

public class AdSkipperAccessibilityService extends AccessibilityService {

    private static final String TAG = "A11yService";
    private static AdSkipperAccessibilityService instance;

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
        instance = null;
    }

    public static AdSkipperAccessibilityService getInstance() { return instance; }

    /**
     * Click tại tọa độ pixel tuyệt đối trên màn hình
     */
    public void performClick(int x, int y, GestureResultCallback callback) {
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
