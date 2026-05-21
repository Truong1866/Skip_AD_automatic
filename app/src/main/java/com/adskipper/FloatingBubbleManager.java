package com.adskipper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Floating Bubble — hiển thị bong bóng nổi lên trên mọi app.
 *
 * Hành vi:
 * - Bong bóng tròn nhỏ, kéo thả được, tự snap vào cạnh trái/phải
 * - Nhấn một lần  → mở panel điều khiển (Pause/Resume + trạng thái)
 * - Panel tự đóng sau 4s không tương tác
 * - Pulse animation khi đang chạy, tĩnh khi pause
 * - Hiển thị badge khi vừa phát hiện và click quảng cáo
 */
public class FloatingBubbleManager {

    // Actions gửi đến ScreenCaptureService
    public static final String ACTION_PAUSE  = "com.adskipper.PAUSE";
    public static final String ACTION_RESUME = "com.adskipper.RESUME";
    public static final String ACTION_STOP   = "com.adskipper.STOP";

    private final Context        context;
    private final WindowManager  wm;
    private       View           bubbleView;
    private       View           panelView;
    private       boolean        panelVisible = false;
    private       boolean        isPaused     = false;
    private       boolean        attached     = false;

    private int screenW, screenH;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Panel auto-dismiss
    private final Runnable dismissPanelRunnable = this::hidePanel;

    // Bubble drag state
    private int   initX, initY;
    private float initTouchX, initTouchY;
    private boolean isDragging = false;
    private long   touchDownTime;

    public FloatingBubbleManager(Context context) {
        this.context = context;
        this.wm      = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    // ── Public API ────────────────────────────────────────────────────

    public void show() {
        if (attached) return;
        createBubble();
        attached = true;
    }

    public void hide() {
        if (!attached) return;
        handler.removeCallbacks(dismissPanelRunnable);
        hidePanelImmediate();
        if (bubbleView != null) {
            animateOut(bubbleView, () -> {
                try { wm.removeView(bubbleView); } catch (Exception ignored) {}
                bubbleView = null;
            });
        }
        attached = false;
    }

    /** Gọi khi vừa phát hiện quảng cáo — hiện badge ⚡ */
    public void showDetectedBadge() {
        if (bubbleView == null) return;
        handler.post(() -> {
            View badge = bubbleView.findViewById(R.id.bubbleBadge);
            if (badge != null) {
                badge.setVisibility(View.VISIBLE);
                handler.postDelayed(() -> {
                    if (badge != null) badge.setVisibility(View.GONE);
                }, 2000);
            }
        });
    }

    /** Cập nhật số lần đã click quảng cáo trên panel */
    public void updateClickCount(int count) {
        if (panelView == null) return;
        handler.post(() -> {
            TextView tv = panelView.findViewById(R.id.panelClickCount);
            if (tv != null) tv.setText("Đã đóng: " + count + " quảng cáo");
        });
    }

    /** Cập nhật trạng thái đang quét */
    public void updateStatus(String status) {
        if (panelView == null) return;
        handler.post(() -> {
            TextView tv = panelView.findViewById(R.id.panelStatus);
            if (tv != null) tv.setText(status);
        });
    }

    public boolean isPaused() { return isPaused; }

    // ── Bubble creation ───────────────────────────────────────────────

    private void createBubble() {
        bubbleView = LayoutInflater.from(context).inflate(R.layout.layout_bubble, null);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = screenW - dpToPx(72);  // Bắt đầu ở cạnh phải
        params.y = screenH / 3;

        wm.addView(bubbleView, params);
        startPulseAnimation(bubbleView.findViewById(R.id.bubbleRing));
        animateIn(bubbleView);
        setupBubbleTouchListener(params);
    }

    private void setupBubbleTouchListener(WindowManager.LayoutParams params) {
        bubbleView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isDragging    = false;
                    touchDownTime = System.currentTimeMillis();
                    initX         = params.x;
                    initY         = params.y;
                    initTouchX    = event.getRawX();
                    initTouchY    = event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initTouchX;
                    float dy = event.getRawY() - initTouchY;
                    if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                        isDragging = true;
                        if (panelVisible) hidePanelImmediate();
                    }
                    if (isDragging) {
                        params.x = (int) (initX + dx);
                        params.y = (int) (initY + dy);
                        // Clamp trong màn hình
                        int bubbleSize = dpToPx(56);
                        params.x = Math.max(0, Math.min(screenW - bubbleSize, params.x));
                        params.y = Math.max(0, Math.min(screenH - bubbleSize * 2, params.y));
                        try { wm.updateViewLayout(bubbleView, params); } catch (Exception ignored) {}
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (isDragging) {
                        // Snap vào cạnh gần nhất
                        snapToEdge(params);
                    } else {
                        // Tap ngắn → toggle panel
                        long duration = System.currentTimeMillis() - touchDownTime;
                        if (duration < 300) togglePanel(params);
                    }
                    isDragging = false;
                    return true;
            }
            return false;
        });
    }

    // ── Snap to edge ──────────────────────────────────────────────────

    private void snapToEdge(WindowManager.LayoutParams params) {
        int bubbleSize = dpToPx(56);
        int targetX = (params.x + bubbleSize / 2 < screenW / 2) ? 0 : screenW - bubbleSize;
        ValueAnimator anim = ValueAnimator.ofInt(params.x, targetX);
        anim.setDuration(250);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            params.x = (int) a.getAnimatedValue();
            try { wm.updateViewLayout(bubbleView, params); } catch (Exception ignored) {}
        });
        anim.start();
    }

    // ── Panel ─────────────────────────────────────────────────────────

    private void togglePanel(WindowManager.LayoutParams bubbleParams) {
        if (panelVisible) { hidePanel(); return; }

        handler.removeCallbacks(dismissPanelRunnable);
        panelView = LayoutInflater.from(context).inflate(R.layout.layout_bubble_panel, null);

        // Posisi panel di sebelah bubble
        int bubbleSize = dpToPx(56);
        int panelW     = dpToPx(200);
        int panelH     = dpToPx(140);

        int panelX = (bubbleParams.x < screenW / 2)
            ? bubbleParams.x + bubbleSize + dpToPx(8)
            : bubbleParams.x - panelW - dpToPx(8);
        int panelY = Math.min(bubbleParams.y, screenH - panelH - dpToPx(16));

        WindowManager.LayoutParams panelParams = new WindowManager.LayoutParams(
            dpToPx(200),
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = panelX;
        panelParams.y = panelY;

        wm.addView(panelView, panelParams);
        panelVisible = true;
        setupPanelButtons();
        animateIn(panelView);

        // Tự đóng sau 4s
        handler.postDelayed(dismissPanelRunnable, 4000);
    }

    private void setupPanelButtons() {
        if (panelView == null) return;

        // Nút Pause / Resume
        View btnPauseResume = panelView.findViewById(R.id.btnPauseResume);
        TextView btnText    = panelView.findViewById(R.id.btnPauseResumeText);
        ImageView btnIcon   = panelView.findViewById(R.id.btnPauseResumeIcon);

        updatePauseResumeButton(btnText, btnIcon);

        btnPauseResume.setOnClickListener(v -> {
            handler.removeCallbacks(dismissPanelRunnable);
            isPaused = !isPaused;
            updatePauseResumeButton(btnText, btnIcon);

            // Gửi intent tới service
            Intent intent = new Intent(context, ScreenCaptureService.class);
            intent.setAction(isPaused ? ACTION_PAUSE : ACTION_RESUME);
            context.startService(intent);

            // Cập nhật bubble icon
            updateBubbleState();

            // Đóng panel sau 1.5s
            handler.postDelayed(dismissPanelRunnable, 1500);
        });

        // Nút Stop
        View btnStop = panelView.findViewById(R.id.btnStop);
        btnStop.setOnClickListener(v -> {
            hidePanelImmediate();
            Intent intent = new Intent(context, ScreenCaptureService.class);
            intent.setAction(ACTION_STOP);
            context.startService(intent);
        });

        // Nút đóng panel (X nhỏ)
        View btnClose = panelView.findViewById(R.id.btnClosePanel);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> hidePanel());
        }

        // Reset auto-dismiss mỗi khi touch panel
        panelView.setOnTouchListener((v, e) -> {
            handler.removeCallbacks(dismissPanelRunnable);
            handler.postDelayed(dismissPanelRunnable, 4000);
            return false;
        });
    }

    private void updatePauseResumeButton(TextView text, ImageView icon) {
        if (text == null || icon == null) return;
        if (isPaused) {
            text.setText("Tiếp tục");
            icon.setImageResource(android.R.drawable.ic_media_play);
            icon.setColorFilter(0xFF44FF88);
        } else {
            text.setText("Tạm dừng");
            icon.setImageResource(android.R.drawable.ic_media_pause);
            icon.setColorFilter(0xFFFFAA00);
        }
    }

    private void updateBubbleState() {
        if (bubbleView == null) return;
        View ring = bubbleView.findViewById(R.id.bubbleRing);
        ImageView icon = bubbleView.findViewById(R.id.bubbleIcon);
        if (isPaused) {
            ring.clearAnimation();
            ring.setAlpha(0.3f);
            if (icon != null) icon.setColorFilter(0xFF888899);
        } else {
            ring.setAlpha(1f);
            if (icon != null) icon.setColorFilter(0xFF00E5FF);
            startPulseAnimation(ring);
        }
    }

    private void hidePanel() {
        if (!panelVisible || panelView == null) return;
        animateOut(panelView, () -> {
            try { wm.removeView(panelView); } catch (Exception ignored) {}
            panelView    = null;
            panelVisible = false;
        });
    }

    private void hidePanelImmediate() {
        if (panelView != null) {
            try { wm.removeView(panelView); } catch (Exception ignored) {}
            panelView    = null;
            panelVisible = false;
        }
    }

    // ── Animations ────────────────────────────────────────────────────

    private void startPulseAnimation(View ring) {
        if (ring == null) return;
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(ring, "scaleX", 1f, 1.25f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(ring, "scaleY", 1f, 1.25f, 1f);
        ObjectAnimator alpha  = ObjectAnimator.ofFloat(ring, "alpha", 0.8f, 0.2f, 0.8f);

        for (ObjectAnimator a : new ObjectAnimator[]{scaleX, scaleY, alpha}) {
            a.setDuration(1800);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new DecelerateInterpolator());
            a.start();
        }
    }

    private void animateIn(View v) {
        v.setScaleX(0.3f); v.setScaleY(0.3f); v.setAlpha(0f);
        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(280)
            .setInterpolator(new OvershootInterpolator(1.8f))
            .start();
    }

    private void animateOut(View v, Runnable onEnd) {
        v.animate().scaleX(0.2f).scaleY(0.2f).alpha(0f)
            .setDuration(200)
            .setInterpolator(new DecelerateInterpolator())
            .setListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) { if (onEnd != null) onEnd.run(); }
            }).start();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
