package com.adskipper;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Template Matching engine - so khớp hình ảnh tham chiếu với màn hình.
 *
 * Thuật toán: Normalized Cross-Correlation (NCC) trên ảnh thu nhỏ 1/4.
 * - Scale cả template và screen về 1/4 để tăng tốc độ
 * - Match result được scale lại về tọa độ đầy đủ
 * - Hỗ trợ multi-template (nhiều ảnh tham chiếu)
 */
public class TemplateMatcher {

    private static final String TAG = "TemplateMatcher";
    public static final float SCALE_FACTOR = 0.25f;   // 1/4 như yêu cầu
    private static final float MATCH_THRESHOLD = 0.75f;

    public static class TemplateEntry {
        public String name;
        public Bitmap fullBitmap;
        public Bitmap scaledBitmap;
        public int[] scaledPixels;
        public float meanR, meanG, meanB;

        public TemplateEntry(String name, Bitmap original) {
            this.name = name;
            this.fullBitmap = original;
            int sw = Math.max(1, (int)(original.getWidth()  * SCALE_FACTOR));
            int sh = Math.max(1, (int)(original.getHeight() * SCALE_FACTOR));
            this.scaledBitmap = Bitmap.createScaledBitmap(original, sw, sh, true);
            this.scaledPixels = new int[sw * sh];
            this.scaledBitmap.getPixels(scaledPixels, 0, sw, 0, 0, sw, sh);
            // Precompute mean
            double sr = 0, sg = 0, sb = 0;
            for (int px : scaledPixels) {
                sr += Color.red(px);
                sg += Color.green(px);
                sb += Color.blue(px);
            }
            int n = scaledPixels.length;
            this.meanR = (float)(sr / n);
            this.meanG = (float)(sg / n);
            this.meanB = (float)(sb / n);
        }
    }

    public static class MatchResult {
        public boolean matched;
        public float score;           // 0..1
        public float centerXRatio;    // tọa độ tâm so với ảnh gốc (0..1)
        public float centerYRatio;
        public String templateName;

        @Override
        public String toString() {
            return String.format("[%s] score=%.2f @ (%.3f, %.3f)",
                templateName, score, centerXRatio, centerYRatio);
        }
    }

    private final List<TemplateEntry> templates = new ArrayList<>();
    private int[] screenPixelsScaled;
    private int scaledScreenW, scaledScreenH;

    public void addTemplate(String name, Bitmap bitmap) {
        templates.add(new TemplateEntry(name, bitmap));
        Log.d(TAG, "Added template: " + name + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
    }

    public void clearTemplates() {
        for (TemplateEntry t : templates) {
            if (t.scaledBitmap != null && !t.scaledBitmap.isRecycled())
                t.scaledBitmap.recycle();
        }
        templates.clear();
    }

    public int getTemplateCount() { return templates.size(); }

    /**
     * So khớp tất cả template với màn hình.
     * @param screen Ảnh màn hình đầy đủ (gốc, không resize)
     * @return Kết quả khớp tốt nhất, hoặc matched=false
     */
    public MatchResult match(Bitmap screen) {
        if (templates.isEmpty()) {
            MatchResult r = new MatchResult();
            r.matched = false;
            return r;
        }

        // Scale màn hình về 1/4
        int sw = Math.max(1, (int)(screen.getWidth()  * SCALE_FACTOR));
        int sh = Math.max(1, (int)(screen.getHeight() * SCALE_FACTOR));
        Bitmap scaledScreen = Bitmap.createScaledBitmap(screen, sw, sh, true);
        scaledScreenW = sw;
        scaledScreenH = sh;
        screenPixelsScaled = new int[sw * sh];
        scaledScreen.getPixels(screenPixelsScaled, 0, sw, 0, 0, sw, sh);
        scaledScreen.recycle();

        MatchResult best = new MatchResult();
        best.matched = false;
        best.score = 0;

        for (TemplateEntry tmpl : templates) {
            MatchResult r = matchSingle(tmpl, screen.getWidth(), screen.getHeight());
            if (r.score > best.score) {
                best = r;
            }
        }

        best.matched = (best.score >= MATCH_THRESHOLD);
        return best;
    }

    private MatchResult matchSingle(TemplateEntry tmpl, int origW, int origH) {
        int tw = tmpl.scaledBitmap.getWidth();
        int th = tmpl.scaledBitmap.getHeight();
        int sw = scaledScreenW;
        int sh = scaledScreenH;

        if (tw > sw || th > sh) {
            MatchResult r = new MatchResult();
            r.score = 0;
            r.matched = false;
            return r;
        }

        float bestScore = -1;
        int bestX = 0, bestY = 0;

        // Precompute template std
        double tNorm = computeNorm(tmpl.scaledPixels, tmpl.meanR, tmpl.meanG, tmpl.meanB);
        if (tNorm == 0) {
            MatchResult r = new MatchResult();
            r.score = 0;
            return r;
        }

        // Slide template over screen at 1/4 scale
        int stepX = Math.max(1, tw / 8);
        int stepY = Math.max(1, th / 8);

        for (int y = 0; y <= sh - th; y += stepY) {
            for (int x = 0; x <= sw - tw; x += stepX) {
                float score = computeNCC(
                    screenPixelsScaled, sw,
                    tmpl.scaledPixels, tw, th,
                    x, y,
                    tmpl.meanR, tmpl.meanG, tmpl.meanB,
                    tNorm
                );
                if (score > bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestY = y;
                }
            }
        }

        // Refine: fine search around best position
        for (int y = Math.max(0, bestY - stepY); y <= Math.min(sh - th, bestY + stepY); y++) {
            for (int x = Math.max(0, bestX - stepX); x <= Math.min(sw - tw, bestX + stepX); x++) {
                float score = computeNCC(
                    screenPixelsScaled, sw,
                    tmpl.scaledPixels, tw, th,
                    x, y,
                    tmpl.meanR, tmpl.meanG, tmpl.meanB,
                    tNorm
                );
                if (score > bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestY = y;
                }
            }
        }

        // Convert scaled match coords → original image ratio
        // Center of matched region in scaled coords
        float scaledCx = bestX + tw / 2f;
        float scaledCy = bestY + th / 2f;

        MatchResult r = new MatchResult();
        r.score = Math.max(0, Math.min(1, bestScore));
        r.centerXRatio = scaledCx / sw;
        r.centerYRatio = scaledCy / sh;
        r.templateName = tmpl.name;
        r.matched = (r.score >= MATCH_THRESHOLD);
        return r;
    }

    /**
     * Normalized Cross-Correlation for a patch in screenPixels vs template.
     * Uses RGB channels.
     */
    private float computeNCC(
        int[] screen, int screenW,
        int[] tmpl, int tw, int th,
        int ox, int oy,
        float tMeanR, float tMeanG, float tMeanB,
        double tNorm
    ) {
        // Compute patch mean
        double pMeanR = 0, pMeanG = 0, pMeanB = 0;
        int n = tw * th;
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int px = screen[(oy + y) * screenW + (ox + x)];
                pMeanR += Color.red(px);
                pMeanG += Color.green(px);
                pMeanB += Color.blue(px);
            }
        }
        pMeanR /= n; pMeanG /= n; pMeanB /= n;

        // NCC
        double num = 0, pNorm = 0;
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int sp = screen[(oy + y) * screenW + (ox + x)];
                int tp = tmpl[y * tw + x];
                double dr = Color.red(sp)   - pMeanR;
                double dg = Color.green(sp) - pMeanG;
                double db = Color.blue(sp)  - pMeanB;
                double tr = Color.red(tp)   - tMeanR;
                double tg = Color.green(tp) - tMeanG;
                double tb = Color.blue(tp)  - tMeanB;
                num += dr * tr + dg * tg + db * tb;
                pNorm += dr*dr + dg*dg + db*db;
            }
        }
        pNorm = Math.sqrt(pNorm);
        if (pNorm == 0) return 0;
        double ncc = num / (tNorm * pNorm);
        return (float) Math.max(-1, Math.min(1, ncc));
    }

    private double computeNorm(int[] pixels, float mR, float mG, float mB) {
        double sum = 0;
        for (int px : pixels) {
            double dr = Color.red(px)   - mR;
            double dg = Color.green(px) - mG;
            double db = Color.blue(px)  - mB;
            sum += dr*dr + dg*dg + db*db;
        }
        return Math.sqrt(sum);
    }
}
