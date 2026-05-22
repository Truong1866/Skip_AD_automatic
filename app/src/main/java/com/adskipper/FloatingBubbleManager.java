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
import android.util.Log;
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
 * FloatingBubbleManager — hiển thị bong bóng nổi lên trên MỌI app.
 *
 * Yêu cầu permission: SYSTEM_ALERT_WINDOW (đã khai báo trong Manifest).
 * Cần gọi Settings.canDrawOverlays(context) trước khi show().
 *
 * Hành vi:
 *  - Bong bóng tròn, kéo thả, tự snap vào cạnh trái/phải
 *  - Tap ngắn → mở panel điều khiển (Pause/Resume + trạng thái + số lần đóng QC)
 *  - Panel tự đóng sau 4s không tương tác
 *  - Pulse animation khi đang chạy, tĩnh khi pause
 *  - Badge đỏ "!" khi vừa phát hiện & click QC (hiện 2s rồi tắt)
 *  - Click count được cập nhật realtime trên panel
 */
public class FloatingBubbleManager {

    private static final String TAG = "BubbleManager";

    // Actions gửi đến ScreenCaptureService
    public static final String ACTION_PAUSE  = "com.adskipper.PAUSE";
    public static final String ACTION_RESUME = "com.adskipper.RESUME";
    public static final String ACTION_STOP   = "com.adskipper.STOP";

    private final Context       context;
    private final WindowManager wm;

    private View    bubbleView;
    private View    panelView;

    private boolean panelVisible = false;
    private boolean isPaused     = false;
    private boolean attached     = false;

    private int     clickCount   = 0;   // số lần đã đóng QC

    private int screenW, screenH;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Auto-dismiss panel sau 4s
    private final Runnable dismissPanelRunnable = this::hidePanel;

    // Drag state
    private int   initX, initY;
    private float initTouchX, initTouchY;
    private boolean isDragging = false;
    private long    touchDownTime;

    // Cache params để không gọi wm.updateViewLayout khi không cần
    private WindowManager.LayoutParams bubbleParams;

    public FloatingBubbleManager(Context context) {
        this.context = context.getApplicationContext();
        this.wm      = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        refreshScreenSize();
    }

    private void refreshScreenSize() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    // ─────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────

    /** Hiển thị bubble (gọi sau khi đã có SYSTEM_ALERT_WINDOW permission) */
    public void show() {
        if (attached) return;
        refreshScreenSize();
        createBubble();
        attached = true;
        Log.d(TAG, "Bubble shown");
    }

    /** Ẩn và xóa bubble + panel khỏi WindowManager */
    public void hide() {
        if (!attached) return;
        handler.removeCallbacksAndMessages(null);
        hidePanelImmediate();
        if (bubbleView != null) {
            animateOut(bubbleView, () -> safeRemoveView(bubbleView));
            bubbleView = null;
        }
        attached = false;
        Log.d(TAG, "Bubble hidden");
    }

    /**
     * Gọi khi phát hiện + click QC thành công.
     * Tăng counter, hiện badge "!" trên bubble, cập nhật panel nếu đang mở.
     */
    public void onAdClicked() {
        clickCount++;
        handler.post(() -> {
            showBadge();
            updateClickCount(clickCount);
        });
    }

    /** Cập nhật text trạng thái trên panel (ví dụ: "Đang quét 1Hz...") */
    public void updateStatus(String status) {
        if (panelView == null) return;
        handler.post(() -> {
            TextView tv = panelView.findViewById(R.id.panelStatus);
            if (tv != null) tv.setText(status);
        });
    }

    public boolean isPaused()   { return isPaused; }
    public boolean isAttached() { return attached; }
    public int getClickCount()  { return clickCount; }

    // ─────────────────────────────────────────────────────────────────
    // BUBBLE CREATION
    // ─────────────────────────────────────────────────────────────────

    private void createBubble() {
        bubbleView = LayoutInflater.from(context).inflate(R.layout.layout_bubble, null);

        bubbleParams = buildOverlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        // Bắt đầu ở cạnh phải, 1/3 màn hình từ trên
        bubbleParams.x = screenW - dpToPx(70);
        bubbleParams.y = screenH / 3;

        wm.addView(bubbleView, bubbleParams);

        // Bắt đầu pulse animation
        startPulseAnimation(bubbleView.findViewById(R.id.bubbleRing));
        // Hiệu ứng xuất hiện
        animateIn(bubbleView);
        // Gắn touch listener
        setupBubbleTouchListener();
    }

    /**
     * Xây dựng LayoutParams phù hợp cho overlay window.
     * FLAG_NOT_FOCUSABLE: không lấy focus → không block keyboard/back của app khác
     * FLAG_WATCH_OUTSIDE_TOUCH: nhận touch ngoài bounds → để dismiss panel khi tap ra ngoài
     * FLAG_LAYOUT_IN_SCREEN: vẽ trong toàn màn hình kể cả status bar
     */
    private WindowManager.LayoutParams buildOverlayParams(int w, int h) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        return new WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        );
    }

    // ─────────────────────────────────────────────────────────────────
    // TOUCH / DRAG / SNAP
    // ─────────────────────────────────────────────────────────────────

    private void setupBubbleTouchListener() {
        bubbleView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isDragging    = false;
                    touchDownTime = System.currentTimeMillis();
                    initX         = bubbleParams.x;
                    initY         = bubbleParams.y;
                    initTouchX    = event.getRawX();
                    initTouchY    = event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - initTouchX;
                    float dy = event.getRawY() - initTouchY;
                    if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isDragging = true;
                        if (panelVisible) hidePanelImmediate();
                    }
                    if (isDragging) {
                        int bSize = dpToPx(64);
                        bubbleParams.x = clampInt((int)(initX + dx), 0, screenW - bSize);
                        bubbleParams.y = clampInt((int)(initY + dy), 0, screenH - bSize * 2);
                        safeUpdateLayout(bubbleView, bubbleParams);
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    if (isDragging) {
                        snapToEdge();
                    } else {
                        long dur = System.currentTimeMillis() - touchDownTime;
                        if (dur < 350) togglePanel();
                    }
                    isDragging = false;
                    return true;
            }
            return false;
        });
    }

    private void snapToEdge() {
        if (bubbleView == null) return;
        int bSize = dpToPx(64);
        int targetX = (bubbleParams.x + bSize / 2 < screenW / 2) ? 0 : screenW - bSize;
        ValueAnimator anim = ValueAnimator.ofInt(bubbleParams.x, targetX);
        anim.setDuration(280);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            bubbleParams.x = (int) a.getAnimatedValue();
            safeUpdateLayout(bubbleView, bubbleParams);
        });
        anim.start();
    }

    // ─────────────────────────────────────────────────────────────────
    // PANEL
    // ─────────────────────────────────────────────────────────────────

    private void togglePanel() {
        if (panelVisible) { hidePanel(); return; }
        showPanel();
    }

    private void showPanel() {
        handler.removeCallbacks(dismissPanelRunnable);
        panelView = LayoutInflater.from(context).inflate(R.layout.layout_bubble_panel, null);

        int bSize  = dpToPx(64);
        int panelW = dpToPx(210);
        int panelH = dpToPx(200); // estimate

        // Vị trí panel: bên cạnh bubble, không vượt ra ngoài màn hình
        int panelX = (bubbleParams.x < screenW / 2)
            ? bubbleParams.x + bSize + dpToPx(8)
            : bubbleParams.x - panelW - dpToPx(8);
        int panelY = clampInt(bubbleParams.y, 0, screenH - panelH - dpToPx(20));
        panelX = clampInt(panelX, 0, screenW - panelW);

        WindowManager.LayoutParams pp = buildOverlayParams(dpToPx(210),
            WindowManager.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.TOP | Gravity.START;
        pp.x = panelX;
        pp.y = panelY;

        wm.addView(panelView, pp);
        panelVisible = true;

        // Khởi tạo nội dung panel
        setupPanelContent();
        animateIn(panelView);

        // Auto-dismiss sau 4s
        handler.postDelayed(dismissPanelRunnable, 4000);

        // Reset dismiss timer khi touch panel
        panelView.setOnTouchListener((v, e) -> {
            handler.removeCallbacks(dismissPanelRunnable);
            handler.postDelayed(dismissPanelRunnable, 4000);
            return false;
        });
    }

    private void setupPanelContent() {
        if (panelView == null) return;

        // Cập nhật click count ngay lập tức
        TextView tvCount = panelView.findViewById(R.id.panelClickCount);
        if (tvCount != null) tvCount.setText("Đã đóng: " + clickCount + " quảng cáo");

        TextView tvStatus = panelView.findViewById(R.id.panelStatus);
        if (tvStatus != null)
            tvStatus.setText(isPaused ? "⏸ Đang tạm dừng" : "🟢 Đang quét 1Hz...");

        // Nút Pause/Resume
        View btnPR     = panelView.findViewById(R.id.btnPauseResume);
        TextView btnTxt = panelView.findViewById(R.id.btnPauseResumeText);
        ImageView btnIco = panelView.findViewById(R.id.btnPauseResumeIcon);
        updatePauseResumeUI(btnTxt, btnIco);

        btnPR.setOnClickListener(v -> {
            handler.removeCallbacks(dismissPanelRunnable);
            isPaused = !isPaused;
            updatePauseResumeUI(btnTxt, btnIco);
            updateBubbleState();

            // Gửi intent đến ScreenCaptureService
            sendServiceAction(isPaused ? ACTION_PAUSE : ACTION_RESUME);

            // Cập nhật status text
            if (tvStatus != null)
                tvStatus.setText(isPaused ? "⏸ Đang tạm dừng" : "🟢 Đang quét 1Hz...");

            // Đóng panel sau 1.5s
            handler.postDelayed(dismissPanelRunnable, 1500);
        });

        // Nút Stop
        View btnStop = panelView.findViewById(R.id.btnStop);
        btnStop.setOnClickListener(v -> {
            hidePanelImmediate();
            sendServiceAction(ACTION_STOP);
        });

        // Nút đóng (X nhỏ)
        View btnClose = panelView.findViewById(R.id.btnClosePanel);
        if (btnClose != null) btnClose.setOnClickListener(v -> hidePanel());
    }

    private void updatePauseResumeUI(TextView text, ImageView icon) {
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

    private void hidePanel() {
        if (!panelVisible || panelView == null) return;
        handler.removeCallbacks(dismissPanelRunnable);
        View v = panelView;
        panelView    = null;
        panelVisible = false;
        animateOut(v, () -> safeRemoveView(v));
    }

    private void hidePanelImmediate() {
        handler.removeCallbacks(dismissPanelRunnable);
        if (panelView != null) {
            safeRemoveView(panelView);
            panelView    = null;
            panelVisible = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // BUBBLE STATE
    // ─────────────────────────────────────────────────────────────────

    private void updateBubbleState() {
        if (bubbleView == null) return;
        View ring = bubbleView.findViewById(R.id.bubbleRing);
        // bubbleIcon giờ là icon app — không dùng colorFilter
        if (isPaused) {
            if (ring != null) {
                ring.animate().cancel();
                ring.setScaleX(1f); ring.setScaleY(1f);
                ring.setAlpha(0.25f);
            }
            // Làm mờ icon app khi pause bằng alpha thay vì colorFilter
            View icon = bubbleView.findViewById(R.id.bubbleIcon);
            if (icon != null) icon.setAlpha(0.45f);
        } else {
            if (ring != null) {
                ring.setAlpha(1f);
                startPulseAnimation(ring);
            }
            View icon = bubbleView.findViewById(R.id.bubbleIcon);
            if (icon != null) icon.setAlpha(1f);
        }
    }

    /** Hiện badge "!" trong 2 giây */
    private void showBadge() {
        if (bubbleView == null) return;
        View badge = bubbleView.findViewById(R.id.bubbleBadge);
        if (badge == null) return;
        badge.setVisibility(View.VISIBLE);
        handler.removeCallbacksAndMessages(badge); // xóa delay cũ nếu có
        handler.postDelayed(() -> {
            if (badge != null) badge.setVisibility(View.GONE);
        }, 2000);
    }

    /** Chỉ cập nhật click count trên panel (không hiện badge) */
    private void updateClickCount(int count) {
        if (panelView == null) return;
        TextView tv = panelView.findViewById(R.id.panelClickCount);
        if (tv != null) tv.setText("Đã đóng: " + count + " quảng cáo");
    }

    // ─────────────────────────────────────────────────────────────────
    // ANIMATIONS
    // ─────────────────────────────────────────────────────────────────

    private void startPulseAnimation(View ring) {
        if (ring == null) return;
        ring.animate().cancel();

        // Scale pulse
        ObjectAnimator sx    = ObjectAnimator.ofFloat(ring, "scaleX", 1f, 1.30f, 1f);
        ObjectAnimator sy    = ObjectAnimator.ofFloat(ring, "scaleY", 1f, 1.30f, 1f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(ring, "alpha",  0.9f, 0.15f, 0.9f);
        for (ObjectAnimator a : new ObjectAnimator[]{sx, sy, alpha}) {
            a.setDuration(2000);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new DecelerateInterpolator());
            a.start();
        }
    }

    private void animateIn(View v) {
        v.setScaleX(0.2f); v.setScaleY(0.2f); v.setAlpha(0f);
        v.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(300)
            .setInterpolator(new OvershootInterpolator(2f))
            .start();
    }

    private void animateOut(View v, Runnable onEnd) {
        v.animate()
            .scaleX(0.1f).scaleY(0.1f).alpha(0f)
            .setDuration(200)
            .setInterpolator(new DecelerateInterpolator())
            .setListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    v.animate().setListener(null); // clean up
                    if (onEnd != null) onEnd.run();
                }
            }).start();
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    private void sendServiceAction(String action) {
        try {
            Intent intent = new Intent(context, ScreenCaptureService.class);
            intent.setAction(action);
            context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "sendServiceAction failed: " + e.getMessage());
        }
    }

    private void safeRemoveView(View v) {
        try { if (v != null) wm.removeView(v); } catch (Exception ignored) {}
    }

    private void safeUpdateLayout(View v, WindowManager.LayoutParams p) {
        try { if (v != null && v.isAttachedToWindow()) wm.updateViewLayout(v, p); }
        catch (Exception ignored) {}
    }

    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static int clampInt(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
