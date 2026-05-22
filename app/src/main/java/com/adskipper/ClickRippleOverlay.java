package com.adskipper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

/**
 * ClickRippleOverlay — hiển thị hiệu ứng vòng tròn mờ dần tại điểm được click.
 *
 * Cách dùng:
 *   ClickRippleOverlay ripple = new ClickRippleOverlay(context);
 *   ripple.show(pixelX, pixelY);   // gọi từ main thread
 *
 * Hiệu ứng 3 lớp:
 *   - rippleDot:   chấm trung tâm, fade out ngay sau 150ms
 *   - rippleInner: vòng trong, scale 1→1.8 + fade out trong 400ms
 *   - rippleOuter: vòng ngoài, scale 1→2.5 + fade out trong 600ms
 *
 * View tự remove khỏi WindowManager sau khi animation kết thúc.
 * An toàn khi gọi liên tiếp nhiều lần (mỗi call tạo view riêng).
 */
public class ClickRippleOverlay {

    private static final String TAG = "ClickRipple";

    /** Kích thước của container ripple (dp) — phải khớp với layout_click_ripple.xml */
    private static final int RIPPLE_SIZE_DP = 80;

    /** Tổng thời gian animation (ms) — view bị remove sau thời gian này */
    private static final long ANIM_DURATION_MS = 650L;

    private final Context       context;
    private final WindowManager wm;
    private final Handler       handler;

    public ClickRippleOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.wm      = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Hiển thị ripple animation tại vị trí pixel tuyệt đối trên màn hình.
     * Gọi từ main thread.
     *
     * @param pixelX  Tọa độ X tâm (pixel màn hình thật)
     * @param pixelY  Tọa độ Y tâm (pixel màn hình thật)
     */
    public void show(int pixelX, int pixelY) {
        try {
            View rippleView = LayoutInflater.from(context)
                    .inflate(R.layout.layout_click_ripple, null);

            int sizePx = dpToPx(RIPPLE_SIZE_DP);

            // Đặt view sao cho tâm ripple = (pixelX, pixelY)
            WindowManager.LayoutParams params = buildParams(sizePx);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = pixelX - sizePx / 2;
            params.y = pixelY - sizePx / 2;

            wm.addView(rippleView, params);

            startRippleAnimation(rippleView);

            // Fallback: đảm bảo view luôn bị remove dù animation bị cancel
            handler.postDelayed(() -> safeRemove(rippleView), ANIM_DURATION_MS + 200);

        } catch (Exception e) {
            Log.e(TAG, "show() error: " + e.getMessage());
        }
    }

    /**
     * Hiển thị ripple từ tọa độ tỉ lệ 0..1.
     *
     * @param xRatio  0.0 = trái, 1.0 = phải
     * @param yRatio  0.0 = trên, 1.0 = dưới
     * @param screenW Chiều rộng màn hình (pixel)
     * @param screenH Chiều cao màn hình (pixel)
     */
    public void showRatio(float xRatio, float yRatio, int screenW, int screenH) {
        int px = Math.round(xRatio * screenW);
        int py = Math.round(yRatio * screenH);
        show(px, py);
    }

    // ─────────────────────────────────────────────────────────────
    // ANIMATION
    // ─────────────────────────────────────────────────────────────

    private void startRippleAnimation(View root) {
        View outer = root.findViewById(R.id.rippleOuter);
        View inner = root.findViewById(R.id.rippleInner);
        View dot   = root.findViewById(R.id.rippleDot);

        // Bắt đầu từ trạng thái ẩn
        outer.setAlpha(0.9f); outer.setScaleX(0.3f); outer.setScaleY(0.3f);
        inner.setAlpha(0.9f); inner.setScaleX(0.3f); inner.setScaleY(0.3f);
        dot.setAlpha(1f);     dot.setScaleX(0.5f);   dot.setScaleY(0.5f);

        // ── Dot: xuất hiện nhanh rồi fade out ──
        AnimatorSet dotAnim = new AnimatorSet();
        dotAnim.playTogether(
            ObjectAnimator.ofFloat(dot, "scaleX", 0.5f, 1.2f, 1f),
            ObjectAnimator.ofFloat(dot, "scaleY", 0.5f, 1.2f, 1f),
            ObjectAnimator.ofFloat(dot, "alpha",  1f, 1f, 0f)
        );
        dotAnim.setDuration(350);
        dotAnim.setInterpolator(new DecelerateInterpolator());

        // ── Inner: scale lên + fade out ──
        AnimatorSet innerAnim = new AnimatorSet();
        innerAnim.playTogether(
            ObjectAnimator.ofFloat(inner, "scaleX", 0.3f, 1.8f),
            ObjectAnimator.ofFloat(inner, "scaleY", 0.3f, 1.8f),
            ObjectAnimator.ofFloat(inner, "alpha",  0.85f, 0f)
        );
        innerAnim.setDuration(450);
        innerAnim.setInterpolator(new AccelerateInterpolator(0.8f));

        // ── Outer: scale lớn hơn + fade out chậm hơn ──
        AnimatorSet outerAnim = new AnimatorSet();
        outerAnim.playTogether(
            ObjectAnimator.ofFloat(outer, "scaleX", 0.3f, 2.5f),
            ObjectAnimator.ofFloat(outer, "scaleY", 0.3f, 2.5f),
            ObjectAnimator.ofFloat(outer, "alpha",  0.7f, 0f)
        );
        outerAnim.setDuration(ANIM_DURATION_MS);
        outerAnim.setInterpolator(new AccelerateInterpolator(0.6f));

        // Chạy cả 3 song song, remove view khi outer kết thúc
        outerAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                safeRemove(root);
            }
        });

        dotAnim.start();
        innerAnim.start();
        outerAnim.start();
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    private WindowManager.LayoutParams buildParams(int sizePx) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            sizePx, sizePx, type,
            // NOT_FOCUSABLE + NOT_TOUCHABLE: không chặn touch của người dùng
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        return p;
    }

    private void safeRemove(View v) {
        try {
            if (v != null && v.isAttachedToWindow()) {
                wm.removeView(v);
            }
        } catch (Exception ignored) {}
    }

    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
