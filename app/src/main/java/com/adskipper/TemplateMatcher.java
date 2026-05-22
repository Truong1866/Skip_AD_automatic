package com.adskipper;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * TemplateMatcher — so khớp ảnh tham chiếu với màn hình.
 *
 * Độ chính xác 1:1 (full resolution):
 *   - Không scale xuống để giữ chính xác tọa độ click
 *   - Thuật toán NCC (Normalized Cross-Correlation) trên ảnh gốc
 *   - Tọa độ tâm (centerXRatio / centerYRatio) là trung tâm chính xác
 *     của vùng khớp, map thẳng về pixel màn hình thật
 *
 * Strategy tìm kiếm:
 *   1. Coarse scan: bước = max(1, min(tw,th)/4) — quét nhanh toàn màn hình
 *   2. Fine scan:   vùng ±step xung quanh điểm tốt nhất — tinh chỉnh pixel
 *
 * Interval: 2s/lần (thay vì 1s để giảm tải CPU)
 */
public class TemplateMatcher {

    private static final String TAG = "TemplateMatcher";

    /** Ngưỡng NCC để coi là khớp — 0.85 = 85% tương đồng */
    private static final float MATCH_THRESHOLD = 0.70f;

    // ─────────────────────────────────────────────────────────────
    // DATA CLASSES
    // ─────────────────────────────────────────────────────────────

    public static class TemplateEntry {
        public final String name;
        public final Bitmap bitmap;
        public final int    width;
        public final int    height;
        public final int[]  pixels;       // pixel array full-res
        public final float  meanR, meanG, meanB;
        public final double norm;         // pre-computed template norm cho NCC

        public TemplateEntry(String name, Bitmap original) {
            this.name   = name;
            this.bitmap = original;
            this.width  = original.getWidth();
            this.height = original.getHeight();
            this.pixels = new int[width * height];
            original.getPixels(pixels, 0, width, 0, 0, width, height);

            // Pre-compute mean
            double sr = 0, sg = 0, sb = 0;
            for (int px : pixels) {
                sr += Color.red(px);
                sg += Color.green(px);
                sb += Color.blue(px);
            }
            int n = pixels.length;
            this.meanR = (float)(sr / n);
            this.meanG = (float)(sg / n);
            this.meanB = (float)(sb / n);

            // Pre-compute template norm (denominator của NCC)
            double normSum = 0;
            for (int px : pixels) {
                double dr = Color.red(px)   - meanR;
                double dg = Color.green(px) - meanG;
                double db = Color.blue(px)  - meanB;
                normSum += dr*dr + dg*dg + db*db;
            }
            this.norm = Math.sqrt(normSum);
        }
    }

    public static class MatchResult {
        public boolean matched;
        public float   score;           // NCC score 0..1
        public float   centerXRatio;   // tâm X / screenW (0..1)
        public float   centerYRatio;   // tâm Y / screenH (0..1)
        public int     pixelX;         // tâm X tuyệt đối (pixel)
        public int     pixelY;         // tâm Y tuyệt đối (pixel)
        public String  templateName;

        @Override
        public String toString() {
            return String.format("[%s] score=%.1f%% @ pixel(%d,%d) ratio(%.3f,%.3f)",
                templateName, score * 100, pixelX, pixelY, centerXRatio, centerYRatio);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // STATE
    // ─────────────────────────────────────────────────────────────

    private final List<TemplateEntry> templates = new ArrayList<>();

    // Cache screen pixels để tái sử dụng giữa nhiều template trong cùng 1 frame
    private int[]  cachedScreenPixels;
    private int    cachedScreenW, cachedScreenH;
    private Bitmap cachedScreenBitmap; // reference để detect thay đổi

    // ─────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────

    public void addTemplate(String name, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            Log.e(TAG, "addTemplate: bitmap null hoặc đã recycle — bỏ qua");
            return;
        }
        templates.add(new TemplateEntry(name, bitmap));
        Log.d(TAG, "Template added: [" + name + "] " + bitmap.getWidth() + "x" + bitmap.getHeight());
    }

    public void clearTemplates() {
        templates.clear();
        cachedScreenPixels  = null;
        cachedScreenBitmap  = null;
    }

    public int getTemplateCount() { return templates.size(); }

    /**
     * So khớp tất cả template với frame màn hình (full resolution 1:1).
     *
     * @param screen  Bitmap màn hình gốc (không resize)
     * @return        Kết quả khớp tốt nhất; matched=false nếu không đạt ngưỡng
     */
    public MatchResult match(Bitmap screen) {
        MatchResult noMatch = new MatchResult();
        noMatch.matched = false;

        if (templates.isEmpty() || screen == null || screen.isRecycled()) return noMatch;

        int sw = screen.getWidth();
        int sh = screen.getHeight();

        // Load screen pixels (cache theo frame)
        int[] screenPx;
        if (screen != cachedScreenBitmap
                || cachedScreenPixels == null
                || cachedScreenW != sw
                || cachedScreenH != sh) {
            screenPx = new int[sw * sh];
            screen.getPixels(screenPx, 0, sw, 0, 0, sw, sh);
            cachedScreenPixels = screenPx;
            cachedScreenW      = sw;
            cachedScreenH      = sh;
            cachedScreenBitmap = screen;
        } else {
            screenPx = cachedScreenPixels;
        }

        MatchResult best = noMatch;

        for (TemplateEntry tmpl : templates) {
            if (tmpl.width > sw || tmpl.height > sh) {
                Log.w(TAG, "Template [" + tmpl.name + "] lớn hơn màn hình — bỏ qua");
                continue;
            }
            MatchResult r = matchOne(tmpl, screenPx, sw, sh);
            if (r.score > best.score) best = r;
        }

        best.matched = (best.score >= MATCH_THRESHOLD);
        return best;
    }

    // ─────────────────────────────────────────────────────────────
    // CORE MATCHING (full 1:1 resolution)
    // ─────────────────────────────────────────────────────────────

    /**
     * NCC matching với 2 giai đoạn:
     *   Phase 1 — Coarse: bước step lớn, quét toàn màn hình nhanh
     *   Phase 2 — Fine:   bước 1px quanh vùng tốt nhất của Phase 1
     *
     * Trả về centerX/Y là trung tâm CHÍNH XÁC của vùng khớp (pixel).
     */
    private MatchResult matchOne(TemplateEntry tmpl,
                                  int[] screenPx, int sw, int sh) {
        int tw = tmpl.width;
        int th = tmpl.height;

        // Coarse step: khoảng 1/4 kích thước nhỏ nhất của template
        // Tối thiểu 2 để không quá chậm, tối đa 16
        int step = Math.max(2, Math.min(16, Math.min(tw, th) / 4));

        // Phase 1: Coarse scan
        float bestScore = -2f;
        int   bestX     = 0, bestY = 0;

        for (int y = 0; y <= sh - th; y += step) {
            for (int x = 0; x <= sw - tw; x += step) {
                float s = ncc(screenPx, sw, x, y, tmpl);
                if (s > bestScore) { bestScore = s; bestX = x; bestY = y; }
            }
        }

        // Phase 2: Fine scan — 1px trong vùng ±step xung quanh coarse best
        int x0 = Math.max(0,      bestX - step);
        int x1 = Math.min(sw - tw, bestX + step);
        int y0 = Math.max(0,      bestY - step);
        int y1 = Math.min(sh - th, bestY + step);

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float s = ncc(screenPx, sw, x, y, tmpl);
                if (s > bestScore) { bestScore = s; bestX = x; bestY = y; }
            }
        }

        // Tâm của vùng khớp (pixel chính xác)
        int centerPxX = bestX + tw / 2;
        int centerPxY = bestY + th / 2;

        MatchResult r = new MatchResult();
        r.score         = Math.max(0f, Math.min(1f, bestScore));
        r.pixelX        = centerPxX;
        r.pixelY        = centerPxY;
        r.centerXRatio  = (float) centerPxX / sw;
        r.centerYRatio  = (float) centerPxY / sh;
        r.templateName  = tmpl.name;
        r.matched       = (r.score >= MATCH_THRESHOLD);
        return r;
    }

    // ─────────────────────────────────────────────────────────────
    // NCC COMPUTATION
    // ─────────────────────────────────────────────────────────────

    /**
     * Normalized Cross-Correlation tại vị trí (ox, oy) trên màn hình.
     *
     *         Σ (patch_i − μ_patch)(tmpl_i − μ_tmpl)
     * NCC = ─────────────────────────────────────────
     *             σ_patch × σ_tmpl
     *
     * Tính trên cả 3 channel R, G, B đồng thời.
     * Trả về giá trị trong [-1, 1]; 1 = khớp hoàn hảo.
     */
    private float ncc(int[] screen, int sw,
                      int ox, int oy,
                      TemplateEntry tmpl) {
        int tw = tmpl.width;
        int th = tmpl.height;
        int n  = tw * th;

        // ── Tính mean của patch (vùng màn hình tại ox,oy) ──
        double pSumR = 0, pSumG = 0, pSumB = 0;
        for (int y = 0; y < th; y++) {
            int rowBase = (oy + y) * sw + ox;
            for (int x = 0; x < tw; x++) {
                int px = screen[rowBase + x];
                pSumR += (px >> 16) & 0xFF;
                pSumG += (px >>  8) & 0xFF;
                pSumB +=  px        & 0xFF;
            }
        }
        double pMeanR = pSumR / n;
        double pMeanG = pSumG / n;
        double pMeanB = pSumB / n;

        // ── NCC numerator + patch norm ──
        double num = 0, pNorm = 0;
        int[] tPx  = tmpl.pixels;
        float tMR  = tmpl.meanR, tMG = tmpl.meanG, tMB = tmpl.meanB;

        for (int y = 0; y < th; y++) {
            int sRowBase = (oy + y) * sw + ox;
            int tRowBase = y * tw;
            for (int x = 0; x < tw; x++) {
                int sp = screen[sRowBase + x];
                int tp = tPx[tRowBase + x];

                double dr = ((sp >> 16) & 0xFF) - pMeanR;
                double dg = ((sp >>  8) & 0xFF) - pMeanG;
                double db = ( sp        & 0xFF) - pMeanB;
                double tr = ((tp >> 16) & 0xFF) - tMR;
                double tg = ((tp >>  8) & 0xFF) - tMG;
                double tb = ( tp        & 0xFF) - tMB;

                num   += dr*tr + dg*tg + db*tb;
                pNorm += dr*dr + dg*dg + db*db;
            }
        }

        if (pNorm <= 0 || tmpl.norm <= 0) return 0f;
        double nccVal = num / (Math.sqrt(pNorm) * tmpl.norm);
        return (float) Math.max(-1.0, Math.min(1.0, nccVal));
    }
}
