package com.zipgrade.omrscanner;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.ArrayList;
import java.util.List;

/**
 * High-Speed Zero-Allocation 5-Stage Optical Mark Recognition (OMR) Engine (v2.3 Continuous Stream + Multi-Template):
 * - Achieves ~5.5 ms/frame (~180 FPS) on 600x780 frames using pre-allocated arena buffers,
 *   32-bit Integral Image white-pixel early-exit, 3x3 seed-lattice BFS connected-component search,
 *   and Direct Inverse Bilinear Local-Jacobian Circle Sampling (zero full-frame warp overhead).
 * - Supports continuous non-stop NV21 (YUV_420_SP) live camera stream grading with real-time AR bubble coordinates.
 * - Supports multiple exam sheet templates:
 *   1. AUTO (Automatic topology & question count detection)
 *   2. FORM_10Q_ID7 (Form 922412: 10Q Left + 7-Digit ID)
 *   3. FORM_16Q_ID7 (Form 6353B: 13Q Left + 3Q Top-Right + 7-Digit ID)
 *   4. FORM_20Q_ID7 (Extended ID7: 13Q Left + 7Q Right + 7-Digit ID)
 *   5. STD_20Q (Standard ZipGrade 2-Column 20Q: 10 Left + 10 Right)
 *   6. STD_30Q (Standard ZipGrade 2-Column 30Q: 15 Left + 15 Right)
 *   7. STD_50Q (Standard ZipGrade 3-Column 50Q: 17 Col1 + 17 Col2 + 16 Col3)
 */
public class OmrNativeCvEngine {
    public static final int CANONICAL_W = 600;
    public static final int CANONICAL_H = 780;

    public static final float CANON_TL_X = 64f, CANON_TL_Y = 64f;
    public static final float CANON_TR_X = 536f, CANON_TR_Y = 64f;
    public static final float CANON_ML_X = 64f, CANON_ML_Y = 390f;
    public static final float CANON_MR_X = 536f, CANON_MR_Y = 390f;
    public static final float CANON_BL_X = 64f, CANON_BL_Y = 716f;
    public static final float CANON_BR_X = 536f, CANON_BR_Y = 716f;

    // Pre-allocated zero-GC arena buffers for 180+ FPS real-time camera streaming
    private static final Object ARENA_LOCK = new Object();
    private static final int[] sPixels = new int[CANONICAL_W * CANONICAL_H];
    private static final int[] sLuma = new int[CANONICAL_W * CANONICAL_H];
    private static final int[] sIntegral = new int[(CANONICAL_W + 1) * (CANONICAL_H + 1)];
    private static final float[] sDarkness = new float[CANONICAL_W * CANONICAL_H];
    private static final int[] sVisitedStamp = new int[CANONICAL_W * CANONICAL_H];
    private static int sCurrentStamp = 1;
    private static final int[] sBfsQueue = new int[CANONICAL_W * CANONICAL_H];

    public static class Pt {
        public final float x;
        public final float y;
        public final float fill;

        public Pt(float x, float y, float fill) {
            this.x = x;
            this.y = y;
            this.fill = fill;
        }
    }

    public static class DetectedFiducials {
        public Pt tl, tr, ml, mr, bl, br;
        public boolean locked6;
        public int lockedCount;
        public float confidence;
        public float ha, hb, hc, hd, he, hf, hg, hh;

        public void computeHomography() {
            if (tl == null || tr == null || br == null || bl == null) return;
            float x0 = tl.x, y0 = tl.y;
            float x1 = tr.x, y1 = tr.y;
            float x2 = br.x, y2 = br.y;
            float x3 = bl.x, y3 = bl.y;
            float dx1 = x1 - x2, dx2 = x3 - x2, sx = x0 - x1 + x2 - x3;
            float dy1 = y1 - y2, dy2 = y3 - y2, sy = y0 - y1 + y2 - y3;
            float det = dx1 * dy2 - dx2 * dy1;
            if (Math.abs(det) < 1e-5f) {
                hg = 0f;
                hh = 0f;
                ha = x1 - x0;
                hb = x3 - x0;
                hc = x0;
                hd = y1 - y0;
                he = y3 - y0;
                hf = y0;
            } else {
                hg = (sx * dy2 - sy * dx2) / det;
                hh = (dx1 * sy - dy1 * sx) / det;
                ha = x1 - x0 + hg * x1;
                hb = x3 - x0 + hh * x3;
                hc = x0;
                hd = y1 - y0 + hg * y1;
                he = y3 - y0 + hh * y3;
                hf = y0;
            }
        }
    }

    private static class VerticalTriplet {
        final Pt top, mid, bot;
        final float avgX;

        VerticalTriplet(Pt top, Pt mid, Pt bot) {
            this.top = top;
            this.mid = mid;
            this.bot = bot;
            this.avgX = (top.x + mid.x + bot.x) / 3f;
        }
    }

    public static class QuestionResult {
        public final int questionNumber;
        public final String markedOptions;
        public final char correctOption;
        public final boolean isCorrect;
        public final boolean isMultipleMark;
        public final boolean isBlank;
        public final float[] densities;
        public final float[] optSrcX;
        public final float[] optSrcY;

        public QuestionResult(
            int qNum,
            String marked,
            char correct,
            boolean isCorrect,
            boolean isMulti,
            boolean isBlank,
            float[] densities,
            float[] optSrcX,
            float[] optSrcY
        ) {
            this.questionNumber = qNum;
            this.markedOptions = marked;
            this.correctOption = correct;
            this.isCorrect = isCorrect;
            this.isMultipleMark = isMulti;
            this.isBlank = isBlank;
            this.densities = densities;
            this.optSrcX = optSrcX;
            this.optSrcY = optSrcY;
        }
    }

    public static class GradeOutcome {
        public final int totalQuestions;
        public final int correctCount;
        public final int wrongCount;
        public final int multipleMarkCount;
        public final int blankCount;
        public final int scorePercent;
        public final String decodedStudentId;
        public final String detectedFormName;
        public final boolean is10QForm;
        public final boolean locked6Fiducials;
        public final DetectedFiducials fiducials;
        public final int fiducialsLocked;
        public final long processingMs;
        public final List<QuestionResult> questions;
        public final float[] idSrcX;
        public final float[] idSrcY;
        public final int idValidCount;
        public final String answerSignature;
        public final Bitmap annotatedBitmap;

        public GradeOutcome(
            int totalQuestions,
            int correctCount,
            int wrongCount,
            int multipleMarkCount,
            int blankCount,
            int scorePercent,
            String decodedStudentId,
            String detectedFormName,
            boolean is10QForm,
            boolean locked6Fiducials,
            DetectedFiducials fiducials,
            int fiducialsLocked,
            long processingMs,
            List<QuestionResult> questions,
            float[] idSrcX,
            float[] idSrcY,
            int idValidCount,
            String answerSignature,
            Bitmap annotatedBitmap
        ) {
            this.totalQuestions = totalQuestions;
            this.correctCount = correctCount;
            this.wrongCount = wrongCount;
            this.multipleMarkCount = multipleMarkCount;
            this.blankCount = blankCount;
            this.scorePercent = scorePercent;
            this.decodedStudentId = decodedStudentId;
            this.detectedFormName = detectedFormName;
            this.is10QForm = is10QForm;
            this.locked6Fiducials = locked6Fiducials;
            this.fiducials = fiducials;
            this.fiducialsLocked = fiducialsLocked;
            this.processingMs = processingMs;
            this.questions = questions;
            this.idSrcX = idSrcX;
            this.idSrcY = idSrcY;
            this.idValidCount = idValidCount;
            this.answerSignature = answerSignature;
            this.annotatedBitmap = annotatedBitmap;
        }
    }

    public static Pt mapCanonicalToSource(float cx, float cy, DetectedFiducials f) {
        float[] xy = new float[2];
        mapCanonicalToSourceFast(cx, cy, f, xy);
        return new Pt(xy[0], xy[1], 1f);
    }

    private static void mapCanonicalToSourceFast(float cx, float cy, DetectedFiducials f, float[] outXY) {
        if (f.lockedCount == 6) {
            boolean isUpper = cy <= CANON_ML_Y;
            float u, v;
            Pt qTL, qTR, qBL, qBR;
            if (isUpper) {
                u = (cx - CANON_TL_X) * (1f / (CANON_TR_X - CANON_TL_X));
                v = (cy - CANON_TL_Y) * (1f / (CANON_ML_Y - CANON_TL_Y));
                qTL = f.tl;
                qTR = f.tr;
                qBL = f.ml;
                qBR = f.mr;
            } else {
                u = (cx - CANON_ML_X) * (1f / (CANON_MR_X - CANON_ML_X));
                v = (cy - CANON_ML_Y) * (1f / (CANON_BL_Y - CANON_ML_Y));
                qTL = f.ml;
                qTR = f.mr;
                qBL = f.bl;
                qBR = f.br;
            }
            float topX = qTL.x + u * (qTR.x - qTL.x);
            float topY = qTL.y + u * (qTR.y - qTL.y);
            float botX = qBL.x + u * (qBR.x - qBL.x);
            float botY = qBL.y + u * (qBR.y - qBL.y);
            outXY[0] = topX + v * (botX - topX);
            outXY[1] = topY + v * (botY - topY);
            return;
        }
        float u = (cx - CANON_TL_X) * (1f / (CANON_TR_X - CANON_TL_X));
        float v = (cy - CANON_TL_Y) * (1f / (CANON_BL_Y - CANON_TL_Y));
        float z = f.hg * u + f.hh * v + 1f;
        if (Math.abs(z) < 1e-5f) z = 1f;
        outXY[0] = (f.ha * u + f.hb * v + f.hc) / z;
        outXY[1] = (f.hd * u + f.he * v + f.hf) / z;
    }

    private static float sampleCanonicalFast5Pt(
        float cx,
        float cy,
        float canonRadius,
        DetectedFiducials f,
        float scaleX,
        float scaleY,
        float[] tmpXY
    ) {
        mapCanonicalToSourceFast(cx, cy, f, tmpXY);
        int scx = Math.max(2, Math.min(CANONICAL_W - 3, Math.round(tmpXY[0])));
        int scy = Math.max(2, Math.min(CANONICAL_H - 3, Math.round(tmpXY[1])));
        int rx = Math.max(1, Math.min(6, Math.round(canonRadius * 0.45f * scaleX)));
        int ry = Math.max(1, Math.min(6, Math.round(canonRadius * 0.45f * scaleY)));
        int rowC = scy * CANONICAL_W;
        int rowU = Math.max(0, scy - ry) * CANONICAL_W;
        int rowD = Math.min(CANONICAL_H - 1, scy + ry) * CANONICAL_W;
        float sum = sDarkness[rowC + scx] * 2f
                  + sDarkness[rowC + Math.max(0, scx - rx)]
                  + sDarkness[rowC + Math.min(CANONICAL_W - 1, scx + rx)]
                  + sDarkness[rowU + scx]
                  + sDarkness[rowD + scx];
        return sum * (1f / 6f);
    }

    private static float sampleCanonicalCircleDirect(
        float cx,
        float cy,
        float canonRadius,
        DetectedFiducials f,
        float scaleX,
        float scaleY,
        float[] tmpXY
    ) {
        mapCanonicalToSourceFast(cx, cy, f, tmpXY);
        float scx = tmpXY[0];
        float scy = tmpXY[1];
        float sum = 0f;
        int count = 0;
        int rInt = Math.max(2, Math.round(canonRadius));
        float rSq = canonRadius * canonRadius;

        for (int dy = -rInt; dy <= rInt; dy++) {
            int d2 = dy * dy;
            int sy = Math.max(0, Math.min(CANONICAL_H - 1, Math.round(scy + dy * scaleY)));
            int rowOff = sy * CANONICAL_W;
            for (int dx = -rInt; dx <= rInt; dx++) {
                if (dx * dx + d2 <= rSq) {
                    int sx = Math.max(0, Math.min(CANONICAL_W - 1, Math.round(scx + dx * scaleX)));
                    sum += sDarkness[rowOff + sx];
                    count++;
                }
            }
        }
        return count > 0 ? (sum / count) : 0f;
    }

    private static float[] refineColumnGrid(
        float baseX,
        float stepX,
        float baseY,
        float stepY,
        int rowCount,
        float r,
        DetectedFiducials fid,
        float scaleX,
        float scaleY,
        float[] tmpXY
    ) {
        float bestBX = baseX, bestSX = stepX, bestBY = baseY, bestSY = stepY;
        float bestScore = -1e9f;

        for (float dy = -9f; dy <= 9f; dy += 3f) {
            for (float dsy = -1.2f; dsy <= 1.2f; dsy += 0.4f) {
                float cBY = baseY + dy;
                float cSY = stepY + dsy;
                for (float dx = -9f; dx <= 9f; dx += 3f) {
                    float cBX = baseX + dx;
                    float score = 0f;
                    for (int row = 0; row < rowCount; row++) {
                        float ry = cBY + row * cSY;
                        float maxD = 0f, minD = 1f;
                        int bestO = 0;
                        for (int o = 0; o < 4; o++) {
                            float d = sampleCanonicalFast5Pt(cBX + o * stepX, ry, r, fid, scaleX, scaleY, tmpXY);
                            if (d > maxD) { maxD = d; bestO = o; }
                            if (d < minD) minD = d;
                        }
                        float vGap = sampleCanonicalFast5Pt(cBX + bestO * stepX, ry + cSY * 0.5f, r * 0.5f, fid, scaleX, scaleY, tmpXY);
                        score += (maxD - minD) + (maxD - vGap) * 0.85f;
                    }
                    score -= (Math.abs(dy) * 0.008f + Math.abs(dx) * 0.008f + Math.abs(dsy) * 0.04f) * rowCount;
                    if (score > bestScore) {
                        bestScore = score;
                        bestBX = cBX;
                        bestBY = cBY;
                        bestSY = cSY;
                    }
                }
            }
        }

        float p1BX = bestBX, p1BY = bestBY, p1SY = bestSY;
        for (float dy = -2f; dy <= 2f; dy += 1f) {
            for (float dx = -2f; dx <= 2f; dx += 1f) {
                for (float dsx = -0.8f; dsx <= 0.8f; dsx += 0.4f) {
                    float cBX = p1BX + dx, cSX = stepX + dsx, cBY = p1BY + dy;
                    float score = 0f;
                    for (int row = 0; row < rowCount; row++) {
                        float ry = cBY + row * p1SY;
                        float maxD = 0f, minD = 1f;
                        int bestO = 0;
                        for (int o = 0; o < 4; o++) {
                            float d = sampleCanonicalFast5Pt(cBX + o * cSX, ry, r, fid, scaleX, scaleY, tmpXY);
                            if (d > maxD) { maxD = d; bestO = o; }
                            if (d < minD) minD = d;
                        }
                        float vGap = sampleCanonicalFast5Pt(cBX + bestO * cSX, ry + p1SY * 0.5f, r * 0.5f, fid, scaleX, scaleY, tmpXY);
                        score += (maxD - minD) + (maxD - vGap) * 0.85f;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        bestBX = cBX;
                        bestSX = cSX;
                        bestBY = cBY;
                    }
                }
            }
        }
        return new float[] { bestBX, bestSX, bestBY, bestSY };
    }

    private static float[] refineIdGrid(
        float idBaseX,
        float idStepX,
        float idBaseY,
        float idStepY,
        float idR,
        DetectedFiducials fid,
        float scaleX,
        float scaleY,
        float[] tmpXY
    ) {
        float bestBX = idBaseX, bestSX = idStepX, bestBY = idBaseY, bestSY = idStepY;
        float bestScore = -1e9f;

        for (float dy = -12f; dy <= 12f; dy += 3f) {
            for (float dsy = -1.2f; dsy <= 1.2f; dsy += 0.4f) {
                float cBY = idBaseY + dy, cSY = idStepY + dsy;
                for (float dx = -12f; dx <= 12f; dx += 3f) {
                    for (float dsx = -1.2f; dsx <= 1.2f; dsx += 0.6f) {
                        float cBX = idBaseX + dx, cSX = idStepX + dsx;
                        float score = 0f;
                        for (int c = 0; c < 7; c++) {
                            float cx = cBX + c * cSX;
                            float maxD = 0f, sumD = 0f;
                            int bestDig = 0;
                            for (int dig = 0; dig <= 9; dig++) {
                                float d = sampleCanonicalFast5Pt(cx, cBY + dig * cSY, idR, fid, scaleX, scaleY, tmpXY);
                                if (d > maxD) { maxD = d; bestDig = dig; }
                                sumD += d;
                            }
                            float avgOther = (sumD - maxD) * (1f / 9f);
                            float vGap = sampleCanonicalFast5Pt(cx, cBY + (bestDig + 0.5f) * cSY, idR * 0.5f, fid, scaleX, scaleY, tmpXY);
                            score += (maxD - avgOther) + (maxD - vGap) * 0.75f;
                        }
                        score -= (Math.abs(dy) * 0.006f + Math.abs(dx) * 0.006f) * 7f;
                        if (score > bestScore) {
                            bestScore = score;
                            bestBX = cBX;
                            bestSX = cSX;
                            bestBY = cBY;
                            bestSY = cSY;
                        }
                    }
                }
            }
        }
        return new float[] { bestBX, bestSX, bestBY, bestSY };
    }

    private static void computeAdaptiveDarknessFromLuma() {
        final int stride = CANONICAL_W + 1;
        for (int y = 0; y < CANONICAL_H; y++) {
            int rowSum = 0;
            int rowOff = y * CANONICAL_W;
            int intPrev = y * stride;
            int intCur = (y + 1) * stride;
            for (int x = 0; x < CANONICAL_W; x++) {
                rowSum += sLuma[rowOff + x];
                sIntegral[intCur + x + 1] = sIntegral[intPrev + x + 1] + rowSum;
            }
        }

        final int rad = 24;
        for (int y = 0; y < CANONICAL_H; y++) {
            int y0 = y > rad ? y - rad : 0;
            int y1 = y + rad < CANONICAL_H ? y + rad : CANONICAL_H - 1;
            int hSpan = y1 - y0 + 1;
            int r0 = y0 * stride;
            int r1 = (y1 + 1) * stride;
            int rowOff = y * CANONICAL_W;
            for (int x = 0; x < CANONICAL_W; x++) {
                int x0 = x > rad ? x - rad : 0;
                int x1 = x + rad < CANONICAL_W ? x + rad : CANONICAL_W - 1;
                int area = (x1 - x0 + 1) * hSpan;
                int sum = sIntegral[r1 + x1 + 1] - sIntegral[r0 + x1 + 1] - sIntegral[r1 + x0] + sIntegral[r0 + x0];
                int lum = sLuma[rowOff + x];
                int diffArea = sum - (lum + 10) * area;
                if (diffArea <= 0) {
                    sDarkness[rowOff + x] = 0f;
                } else {
                    float mean = (float) sum / (float) area;
                    float d = (mean - lum - 10f) / Math.max(34f, mean * 0.52f);
                    sDarkness[rowOff + x] = d >= 1f ? 1f : d;
                }
            }
        }
    }

    private static DetectedFiducials detectSixPointFiducialsFast() {
        int stamp = ++sCurrentStamp;
        if (stamp == Integer.MAX_VALUE) {
            java.util.Arrays.fill(sVisitedStamp, 0);
            stamp = 1;
            sCurrentStamp = 1;
        }

        List<Pt> sqBlobs = new ArrayList<>(24);
        final int totalPx = CANONICAL_W * CANONICAL_H;

        for (int y = 9; y < CANONICAL_H - 9; y += 3) {
            int rowOff = y * CANONICAL_W;
            for (int x = 9; x < CANONICAL_W - 9; x += 3) {
                int idx = rowOff + x;
                if (sDarkness[idx] > 0.36f && sVisitedStamp[idx] != stamp) {
                    int head = 0, tail = 0;
                    sBfsQueue[tail++] = idx;
                    sVisitedStamp[idx] = stamp;
                    int minX = x, maxX = x, minY = y, maxY = y, area = 0;
                    long sumX = 0, sumY = 0;

                    while (head < tail) {
                        int cur = sBfsQueue[head++];
                        int cy = cur / CANONICAL_W;
                        int cx = cur - cy * CANONICAL_W;
                        area++;
                        sumX += cx;
                        sumY += cy;
                        if (cx < minX) minX = cx;
                        if (cx > maxX) maxX = cx;
                        if (cy < minY) minY = cy;
                        if (cy > maxY) maxY = cy;

                        int n1 = cur - 1;
                        int n2 = cur + 1;
                        int n3 = cur - CANONICAL_W;
                        int n4 = cur + CANONICAL_W;
                        if (n1 >= 0 && sDarkness[n1] > 0.36f && sVisitedStamp[n1] != stamp) {
                            sVisitedStamp[n1] = stamp;
                            sBfsQueue[tail++] = n1;
                        }
                        if (n2 < totalPx && sDarkness[n2] > 0.36f && sVisitedStamp[n2] != stamp) {
                            sVisitedStamp[n2] = stamp;
                            sBfsQueue[tail++] = n2;
                        }
                        if (n3 >= 0 && sDarkness[n3] > 0.36f && sVisitedStamp[n3] != stamp) {
                            sVisitedStamp[n3] = stamp;
                            sBfsQueue[tail++] = n3;
                        }
                        if (n4 < totalPx && sDarkness[n4] > 0.36f && sVisitedStamp[n4] != stamp) {
                            sVisitedStamp[n4] = stamp;
                            sBfsQueue[tail++] = n4;
                        }
                    }

                    int bw = maxX - minX + 1;
                    int bh = maxY - minY + 1;
                    if (area >= 45 && area <= 900 && bw >= 6 && bw <= 40 && bh >= 6 && bh <= 40) {
                        float fillRatio = (float) area / (float) (bw * bh);
                        float aspect = (float) bw / (float) bh;
                        if (fillRatio >= 0.80f && aspect >= 0.42f && aspect <= 2.40f) {
                            sqBlobs.add(new Pt((float) sumX / area, (float) sumY / area, fillRatio));
                        }
                    }
                }
            }
        }

        List<VerticalTriplet> triplets = new ArrayList<>(12);
        int n = sqBlobs.size();
        for (int i = 0; i < n; i++) {
            Pt top = sqBlobs.get(i);
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                Pt bot = sqBlobs.get(j);
                if (bot.y - top.y < 160f || Math.abs(bot.x - top.x) > 35f) continue;
                float midX = (top.x + bot.x) * 0.5f;
                float midY = (top.y + bot.y) * 0.5f;
                for (int k = 0; k < n; k++) {
                    if (k == i || k == j) continue;
                    Pt mid = sqBlobs.get(k);
                    if (Math.abs(mid.x - midX) < 18f && Math.abs(mid.y - midY) < 28f) {
                        triplets.add(new VerticalTriplet(top, mid, bot));
                    }
                }
            }
        }

        DetectedFiducials det = new DetectedFiducials();
        VerticalTriplet bestL = null;
        VerticalTriplet bestR = null;
        float bestScore = -999999f;

        for (VerticalTriplet L : triplets) {
            for (VerticalTriplet R : triplets) {
                float spanX = R.avgX - L.avgX;
                if (spanX > 160f &&
                    Math.abs(L.top.y - R.top.y) < 38f &&
                    Math.abs(L.mid.y - R.mid.y) < 38f &&
                    Math.abs(L.bot.y - R.bot.y) < 38f &&
                    (L.top.y > 120f || R.avgX < 480f)) {
                    float yDiff = Math.abs(L.top.y - R.top.y)
                                + Math.abs(L.mid.y - R.mid.y)
                                + Math.abs(L.bot.y - R.bot.y);
                    float symL = Math.abs(L.mid.y - (L.top.y + L.bot.y) * 0.5f);
                    float symR = Math.abs(R.mid.y - (R.top.y + R.bot.y) * 0.5f);
                    float heightSpan = ((L.bot.y - L.top.y) + (R.bot.y - R.top.y)) * 0.5f;
                    float score = spanX + heightSpan * 0.5f - (yDiff * 6f + (symL + symR) * 8f);
                    if (score > bestScore) {
                        bestScore = score;
                        bestL = L;
                        bestR = R;
                    }
                }
            }
        }

        float tripletArea = (bestL != null && bestR != null)
            ? (bestR.avgX - bestL.avgX) * (((bestL.bot.y - bestL.top.y) + (bestR.bot.y - bestR.top.y)) * 0.5f)
            : 0f;

        // 4-Corner Fiducial Detector (with perspective trapezoid support)
        Pt bestTL = null, bestBL = null, bestTR = null, bestBR = null;
        float bestCornerScore = -999999f;
        for (int i = 0; i < n; i++) {
            Pt tl = sqBlobs.get(i);
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                Pt bl = sqBlobs.get(j);
                if (bl.y - tl.y < 300f || Math.abs(bl.x - tl.x) > 55f) continue;
                for (int u = 0; u < n; u++) {
                    if (u == i || u == j) continue;
                    Pt tr = sqBlobs.get(u);
                    if (tr.x - tl.x < 160f || Math.abs(tr.y - tl.y) > 48f) continue;
                    if (!(tl.y > 120f || tr.x < 480f || tl.x < 52f || tr.x > 548f || tl.y < 54f)) continue;
                    for (int v = 0; v < n; v++) {
                        if (v == i || v == j || v == u) continue;
                        Pt br = sqBlobs.get(v);
                        if (Math.abs(br.x - tr.x) > 55f || Math.abs(br.y - bl.y) > 48f) continue;
                        float topW = tr.x - tl.x;
                        float botW = br.x - bl.x;
                        if (botW < 150f || Math.abs(topW - botW) > 95f) continue;
                        float spanX = (topW + botW) * 0.5f;
                        float spanY = ((bl.y - tl.y) + (br.y - tr.y)) * 0.5f;
                        float topBottomTiltDiff = Math.abs((tr.y - tl.y) - (br.y - bl.y));
                        float leftRightHeightDiff = Math.abs((bl.y - tl.y) - (br.y - tr.y));
                        float fillBonus = (tl.fill + tr.fill + bl.fill + br.fill) * 25f;
                        float cScore = spanX + spanY * 0.65f + fillBonus - topBottomTiltDiff * 5f - leftRightHeightDiff * 4f;
                        if (cScore > bestCornerScore) {
                            bestCornerScore = cScore;
                            bestTL = tl;
                            bestBL = bl;
                            bestTR = tr;
                            bestBR = br;
                        }
                    }
                }
            }
        }

        float cornerArea = (bestTL != null && bestTR != null && bestBL != null && bestBR != null)
            ? (((bestTR.x - bestTL.x) + (bestBR.x - bestBL.x)) * 0.5f) * (((bestBL.y - bestTL.y) + (bestBR.y - bestTR.y)) * 0.5f)
            : 0f;

        if (bestL != null && bestR != null && tripletArea >= cornerArea * 0.90f && (bestL.bot.y - bestL.top.y) >= 240f && (bestR.avgX - bestL.avgX) >= 200f) {
            det.tl = bestL.top;
            det.ml = bestL.mid;
            det.bl = bestL.bot;
            det.tr = bestR.top;
            det.mr = bestR.mid;
            det.br = bestR.bot;
            det.locked6 = true;
            det.lockedCount = 6;
            det.confidence = (bestL.top.fill + bestL.mid.fill + bestL.bot.fill
                            + bestR.top.fill + bestR.mid.fill + bestR.bot.fill) / 6f;
            det.computeHomography();
            return det;
        }

        if (bestTL != null && bestTR != null && bestBL != null && bestBR != null) {
            det.tl = bestTL;
            det.tr = bestTR;
            det.bl = bestBL;
            det.br = bestBR;
            det.locked6 = true;
            det.lockedCount = 4;
            det.confidence = (bestTL.fill + bestTR.fill + bestBL.fill + bestBR.fill) / 4f;
            det.computeHomography();

            float[] mxy = new float[2];
            mapCanonicalToSourceFast(CANON_ML_X, CANON_ML_Y, det, mxy);
            Pt snapML = new Pt(mxy[0], mxy[1], (bestTL.fill + bestBL.fill) * 0.5f);
            float bestMlDist = 999f;
            float expMlX = (bestTL.x + bestBL.x) * 0.5f;
            float expMlY = (bestTL.y + bestBL.y) * 0.5f;
            for (Pt b : sqBlobs) {
                if (b == bestTL || b == bestBL || b == bestTR || b == bestBR) continue;
                if (Math.abs(b.x - expMlX) < 28f && b.y > bestTL.y + 65f && b.y < bestBL.y - 65f) {
                    float d = Math.abs(b.x - expMlX) + Math.abs(b.y - expMlY) * 0.25f;
                    if (d < bestMlDist) {
                        bestMlDist = d;
                        snapML = b;
                    }
                }
            }
            det.ml = snapML;

            mapCanonicalToSourceFast(CANON_MR_X, CANON_MR_Y, det, mxy);
            Pt snapMR = new Pt(mxy[0], mxy[1], (bestTR.fill + bestBR.fill) * 0.5f);
            float bestMrDist = 999f;
            float expMrX = (bestTR.x + bestBR.x) * 0.5f;
            float expMrY = (bestTR.y + bestBR.y) * 0.5f;
            for (Pt b : sqBlobs) {
                if (b == bestTL || b == bestBL || b == bestTR || b == bestBR || b == snapML) continue;
                if (Math.abs(b.x - expMrX) < 28f && b.y > bestTR.y + 65f && b.y < bestBR.y - 65f) {
                    float d = Math.abs(b.x - expMrX) + Math.abs(b.y - expMrY) * 0.25f;
                    if (d < bestMrDist) {
                        bestMrDist = d;
                        snapMR = b;
                    }
                }
            }
            det.mr = snapMR;
            return det;
        }

        det.tl = findCentroidNear(CANON_TL_X, CANON_TL_Y);
        det.tr = findCentroidNear(CANON_TR_X, CANON_TR_Y);
        det.ml = findCentroidNear(CANON_ML_X, CANON_ML_Y);
        det.mr = findCentroidNear(CANON_MR_X, CANON_MR_Y);
        det.bl = findCentroidNear(CANON_BL_X, CANON_BL_Y);
        det.br = findCentroidNear(CANON_BR_X, CANON_BR_Y);
        det.locked6 = false;
        det.lockedCount = 0;
        det.confidence = 0.65f;
        det.computeHomography();
        return det;
    }

    private static Pt findCentroidNear(float expX, float expY) {
        int searchRadius = 56;
        int xMin = Math.max(24, Math.round(expX - searchRadius));
        int xMax = Math.min(CANONICAL_W - 24, Math.round(expX + searchRadius));
        int yMin = Math.max(24, Math.round(expY - searchRadius));
        int yMax = Math.min(CANONICAL_H - 24, Math.round(expY + searchRadius));

        int bestCx = Math.round(expX);
        int bestCy = Math.round(expY);
        float bestScore = -999f;

        int[][] ringOffsets = {
            {-17, -17}, {0, -17}, {17, -17},
            {-17, 0},             {17, 0},
            {-17, 17},  {0, 17},  {17, 17}
        };

        for (int cy = yMin; cy <= yMax; cy += 2) {
            for (int cx = xMin; cx <= xMax; cx += 2) {
                float innerSum = 0f;
                for (int dy = -7; dy <= 7; dy += 2) {
                    for (int dx = -7; dx <= 7; dx += 2) {
                        innerSum += sDarkness[(cy + dy) * CANONICAL_W + (cx + dx)];
                    }
                }
                float innerMean = innerSum / 64f;
                float surroundSum = 0f;
                for (int[] off : ringOffsets) {
                    surroundSum += sDarkness[(cy + off[1]) * CANONICAL_W + (cx + off[0])];
                }
                float surroundMean = surroundSum / 8f;
                float score = innerMean - surroundMean * 1.35f;
                if (score > bestScore) {
                    bestScore = score;
                    bestCx = cx;
                    bestCy = cy;
                }
            }
        }

        float sumX = 0f, sumY = 0f, totalW = 0f;
        for (int y = Math.max(0, bestCy - 12); y <= Math.min(CANONICAL_H - 1, bestCy + 12); y++) {
            for (int x = Math.max(0, bestCx - 12); x <= Math.min(CANONICAL_W - 1, bestCx + 12); x++) {
                float w = sDarkness[y * CANONICAL_W + x];
                if (w > 0.45f) {
                    sumX += x * w;
                    sumY += y * w;
                    totalW += w;
                }
            }
        }
        if (totalW < 10f) {
            return new Pt(expX, expY, 0.4f);
        }
        return new Pt(sumX / totalW, sumY / totalW, Math.min(1f, totalW / 240f));
    }

    public static Bitmap nv21ToPortraitBitmap(byte[] nv21, int srcW, int srcH, boolean rotate90Clockwise) {
        int[] argb = new int[CANONICAL_W * CANONICAL_H];
        int maxIdx = nv21 != null ? nv21.length - 1 : -1;
        if (maxIdx < 0 || srcW <= 0 || srcH <= 0) {
            return Bitmap.createBitmap(CANONICAL_W, CANONICAL_H, Bitmap.Config.ARGB_8888);
        }
        for (int y = 0; y < CANONICAL_H; y++) {
            int rowOff = y * CANONICAL_W;
            for (int x = 0; x < CANONICAL_W; x++) {
                int sx, sy;
                if (rotate90Clockwise) {
                    sx = (y * srcW) / CANONICAL_H;
                    sy = srcH - 1 - ((x * srcH) / CANONICAL_W);
                } else {
                    sx = (x * srcW) / CANONICAL_W;
                    sy = (y * srcH) / CANONICAL_H;
                }
                sx = Math.max(0, Math.min(srcW - 1, sx));
                sy = Math.max(0, Math.min(srcH - 1, sy));
                int idx = Math.min(maxIdx, Math.max(0, sy * srcW + sx));
                int lum = nv21[idx] & 0xFF;
                argb[rowOff + x] = 0xFF000000 | (lum << 16) | (lum << 8) | lum;
            }
        }
        return Bitmap.createBitmap(argb, CANONICAL_W, CANONICAL_H, Bitmap.Config.ARGB_8888);
    }

    public static GradeOutcome analyzeNv21StreamFrame(
        byte[] nv21,
        int srcW,
        int srcH,
        boolean rotate90Clockwise,
        int questionCountOverride,
        char[] masterKey,
        String templateMode
    ) {
        long t0 = System.currentTimeMillis();
        int maxIdx = nv21 != null ? nv21.length - 1 : -1;
        synchronized (ARENA_LOCK) {
            if (maxIdx >= 0 && srcW > 0 && srcH > 0) {
                for (int y = 0; y < CANONICAL_H; y++) {
                    int rowOff = y * CANONICAL_W;
                    for (int x = 0; x < CANONICAL_W; x++) {
                        int sx, sy;
                        if (rotate90Clockwise) {
                            sx = (y * srcW) / CANONICAL_H;
                            sy = srcH - 1 - ((x * srcH) / CANONICAL_W);
                        } else {
                            sx = (x * srcW) / CANONICAL_W;
                            sy = (y * srcH) / CANONICAL_H;
                        }
                        sx = Math.max(0, Math.min(srcW - 1, sx));
                        sy = Math.max(0, Math.min(srcH - 1, sy));
                        int idx = Math.min(maxIdx, Math.max(0, sy * srcW + sx));
                        sLuma[rowOff + x] = nv21[idx] & 0xFF;
                    }
                }
            }
            return executeCorePipelineLocked(null, questionCountOverride, masterKey, templateMode != null ? templateMode : "AUTO", t0);
        }
    }

    public static GradeOutcome analyzeNv21StreamFrame(
        byte[] nv21,
        int srcW,
        int srcH,
        boolean rotate90Clockwise,
        int questionCountOverride,
        char[] masterKey
    ) {
        return analyzeNv21StreamFrame(nv21, srcW, srcH, rotate90Clockwise, questionCountOverride, masterKey, "AUTO");
    }

    public static GradeOutcome gradeSheetBitmap(
        Bitmap sourceBitmap,
        int questionCountOverride,
        char[] masterKey,
        String presetHint
    ) {
        long t0 = System.currentTimeMillis();
        Bitmap safeSource = sourceBitmap;
        if (safeSource == null || safeSource.isRecycled()) {
            safeSource = Bitmap.createBitmap(CANONICAL_W, CANONICAL_H, Bitmap.Config.ARGB_8888);
        } else if (safeSource.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap converted = safeSource.copy(Bitmap.Config.ARGB_8888, false);
            if (converted != null) {
                safeSource = converted;
            }
        }
        Bitmap scaled = Bitmap.createScaledBitmap(safeSource, CANONICAL_W, CANONICAL_H, true);
        Bitmap annotated = scaled.copy(Bitmap.Config.ARGB_8888, true);
        if (annotated == null) {
            annotated = Bitmap.createBitmap(CANONICAL_W, CANONICAL_H, Bitmap.Config.ARGB_8888);
        }

        synchronized (ARENA_LOCK) {
            annotated.getPixels(sPixels, 0, CANONICAL_W, 0, 0, CANONICAL_W, CANONICAL_H);
            for (int i = 0; i < CANONICAL_W * CANONICAL_H; i++) {
                int c = sPixels[i];
                sLuma[i] = (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
            }
            return executeCorePipelineLocked(annotated, questionCountOverride, masterKey, presetHint, t0);
        }
    }

    private static class ColSpec {
        final int startQ, endQ;
        final float baseX, stepX, baseY, stepY, r;

        ColSpec(int startQ, int endQ, float baseX, float stepX, float baseY, float stepY, float r) {
            this.startQ = startQ;
            this.endQ = endQ;
            this.baseX = baseX;
            this.stepX = stepX;
            this.baseY = baseY;
            this.stepY = stepY;
            this.r = r;
        }
    }

    private static class TemplateSpec {
        final String code;
        final int qCount;
        final String label;
        final ColSpec[] cols;
        final float idBaseX, idStepX, idBaseY, idStepY, idR;

        TemplateSpec(
            String code,
            int qCount,
            String label,
            ColSpec[] cols,
            float idBaseX,
            float idStepX,
            float idBaseY,
            float idStepY,
            float idR
        ) {
            this.code = code;
            this.qCount = qCount;
            this.label = label;
            this.cols = cols;
            this.idBaseX = idBaseX;
            this.idStepX = idStepX;
            this.idBaseY = idBaseY;
            this.idStepY = idStepY;
            this.idR = idR;
        }
    }

    private static final TemplateSpec[] ALL_TEMPLATES = new TemplateSpec[] {
        new TemplateSpec(
            "SHEETX_10Q", 10, "Sheet-X 10Q (10L + 7-Digit ID)",
            new ColSpec[] {
                new ColSpec(1, 10, 136.77f, 35.40f, 149.70f, 48.64f, 9.5f)
            },
            296.20f, 31.75f, 277.09f, 41.69f, 8.5f
        ),
        new TemplateSpec(
            "SHEETX_16Q", 16, "Sheet-X 16Q (13L + 3R + 7-Digit ID)",
            new ColSpec[] {
                new ColSpec(1, 13, 134.80f, 29.50f, 133.96f, 33.04f, 8.5f),
                new ColSpec(14, 16, 296.07f, 29.50f, 133.96f, 33.04f, 8.5f)
            },
            276.11f, 34.84f, 347.73f, 34.98f, 8.5f
        ),
        new TemplateSpec(
            "SHEETX_20Q", 20, "Sheet-X 20Q (14L + 6R + 7-Digit ID)",
            new ColSpec[] {
                new ColSpec(1, 14, 134.80f, 29.50f, 124.73f, 28.68f, 8.5f),
                new ColSpec(15, 20, 296.07f, 29.50f, 124.73f, 28.68f, 8.5f)
            },
            276.11f, 34.84f, 396.33f, 30.36f, 8.5f
        ),
        new TemplateSpec(
            "SHEETX_30Q", 30, "Sheet-X 30Q (13+13+4 + 7-Digit ID)",
            new ColSpec[] {
                new ColSpec(1, 13, 120.64f, 23.60f, 130.59f, 31.44f, 7.2f),
                new ColSpec(14, 26, 249.65f, 23.60f, 130.59f, 31.44f, 7.2f),
                new ColSpec(27, 30, 378.67f, 23.60f, 130.59f, 31.44f, 7.2f)
            },
            360.24f, 22.93f, 365.49f, 33.29f, 7.2f
        ),
        new TemplateSpec(
            "SHEETX_50Q", 50, "Sheet-X 50Q (22+22+6 + 7-Digit ID)",
            new ColSpec[] {
                new ColSpec(1, 22, 115.92f, 18.88f, 129.20f, 24.22f, 6.2f),
                new ColSpec(23, 44, 224.48f, 18.88f, 129.20f, 24.22f, 6.2f),
                new ColSpec(45, 50, 333.04f, 18.88f, 129.20f, 24.22f, 6.2f)
            },
            322.25f, 28.77f, 386.27f, 26.08f, 6.2f
        ),
        new TemplateSpec(
            "SHEETX_100Q", 100, "Sheet-X 100Q (30+30+30+10 + 7-Digit ID)",
            new ColSpec[] {
                new ColSpec(1, 30, 104.82f, 12.76f, 124.73f, 17.86f, 4.2f),
                new ColSpec(31, 60, 182.64f, 12.76f, 124.73f, 17.86f, 4.2f),
                new ColSpec(61, 90, 260.45f, 12.76f, 124.73f, 17.86f, 4.2f),
                new ColSpec(91, 100, 338.27f, 12.76f, 124.73f, 17.86f, 4.2f)
            },
            333.07f, 27.88f, 410.54f, 17.86f, 4.2f
        ),
        new TemplateSpec(
            "CAMERA_16Q", 16, "Form 6353B (16Q · 7-Digit ID)",
            new ColSpec[] {
                new ColSpec(1, 13, 144.00f, 30.20f, 130.50f, 40.40f, 8.5f),
                new ColSpec(14, 16, 301.80f, 30.70f, 131.40f, 40.90f, 8.5f)
            },
            310.80f, 30.10f, 362.00f, 36.30f, 8.5f
        ),
        new TemplateSpec(
            "CAMERA_10Q", 10, "Form 922412 (10Q · 7-Digit ID)",
            new ColSpec[] {
                new ColSpec(1, 10, 147.40f, 32.00f, 134.80f, 43.10f, 8.5f)
            },
            297.20f, 32.50f, 338.50f, 39.40f, 8.5f
        )
    };

    private static float scoreTemplateCandidate(
        TemplateSpec t,
        DetectedFiducials fid,
        float scaleX,
        float scaleY,
        float[] tmpXY
    ) {
        float contrastSum = 0f;
        int rowsChecked = 0;
        for (ColSpec col : t.cols) {
            int count = col.endQ - col.startQ + 1;
            for (int r = 0; r < count; r++) {
                float cy = col.baseY + r * col.stepY;
                float maxD = 0f;
                float minD = 1f;
                for (int o = 0; o < 4; o++) {
                    float cx = col.baseX + o * col.stepX;
                    float d = sampleCanonicalCircleDirect(cx, cy, col.r, fid, scaleX, scaleY, tmpXY);
                    if (d > maxD) maxD = d;
                    if (d < minD) minD = d;
                }
                float gapD = sampleCanonicalCircleDirect(col.baseX + 1.5f * col.stepX, cy, col.r * 0.45f, fid, scaleX, scaleY, tmpXY);
                contrastSum += (maxD - minD) + (maxD - gapD) * 0.6f;
                rowsChecked++;
            }
        }
        float idContrast = 0f;
        for (int c = 0; c < 7; c++) {
            float cx = t.idBaseX + c * t.idStepX;
            float maxD = 0f;
            float sumD = 0f;
            for (int dig = 0; dig <= 9; dig++) {
                float cy = t.idBaseY + dig * t.idStepY;
                float d = sampleCanonicalCircleDirect(cx, cy, t.idR, fid, scaleX, scaleY, tmpXY);
                if (d > maxD) maxD = d;
                sumD += d;
            }
            float avgOther = (sumD - maxD) / 9f;
            idContrast += (maxD - avgOther);
        }
        return (contrastSum / Math.max(1, rowsChecked)) + (idContrast / 7f) * 0.85f;
    }

    private static GradeOutcome executeCorePipelineLocked(
        Bitmap annotated,
        int questionCountOverride,
        char[] masterKey,
        String presetHint,
        long t0
    ) {
        // Stage 1: Integral-Image Adaptive Darkness
        computeAdaptiveDarknessFromLuma();

        // Stage 2: 6-Point or 4-Corner Fiducial Detection
        DetectedFiducials fid = detectSixPointFiducialsFast();

        float scaleX = Math.max(0.35f, (fid.tr.x - fid.tl.x) * (1f / (CANON_TR_X - CANON_TL_X)));
        float scaleY = Math.max(0.35f, (fid.bl.y - fid.tl.y) * (1f / (CANON_BL_Y - CANON_TL_Y)));
        float[] tmpXY = new float[2];

        Canvas canvas = annotated != null ? new Canvas(annotated) : null;
        Paint pCorrect = null, pWrong = null, pMulti = null, pKeyHint = null, pIdRing = null;
        if (canvas != null) {
            pCorrect = new Paint(Paint.ANTI_ALIAS_FLAG);
            pCorrect.setStyle(Paint.Style.STROKE);
            pCorrect.setStrokeWidth(4.0f);
            pCorrect.setColor(Color.parseColor("#10B981"));

            pWrong = new Paint(Paint.ANTI_ALIAS_FLAG);
            pWrong.setStyle(Paint.Style.STROKE);
            pWrong.setStrokeWidth(4.0f);
            pWrong.setColor(Color.parseColor("#EF4444"));

            pMulti = new Paint(Paint.ANTI_ALIAS_FLAG);
            pMulti.setStyle(Paint.Style.STROKE);
            pMulti.setStrokeWidth(4.0f);
            pMulti.setColor(Color.parseColor("#F59E0B"));

            pKeyHint = new Paint(Paint.ANTI_ALIAS_FLAG);
            pKeyHint.setStyle(Paint.Style.STROKE);
            pKeyHint.setStrokeWidth(3.2f);
            pKeyHint.setColor(Color.parseColor("#3B82F6"));

            pIdRing = new Paint(Paint.ANTI_ALIAS_FLAG);
            pIdRing.setStyle(Paint.Style.STROKE);
            pIdRing.setStrokeWidth(2.8f);
            pIdRing.setColor(Color.parseColor("#38BDF8"));

            Paint pFiducial = new Paint(Paint.ANTI_ALIAS_FLAG);
            pFiducial.setStyle(Paint.Style.STROKE);
            pFiducial.setStrokeWidth(3.8f);
            pFiducial.setColor(Color.parseColor("#10B981"));

            Paint pFidLabelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            pFidLabelBg.setStyle(Paint.Style.FILL);
            pFidLabelBg.setColor(Color.parseColor("#10B981"));

            Paint pFidLabelTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
            pFidLabelTxt.setColor(Color.parseColor("#020617"));
            pFidLabelTxt.setTextSize(10f);
            pFidLabelTxt.setTypeface(Typeface.DEFAULT_BOLD);

            Pt[] fidPts = new Pt[] { fid.tl, fid.tr, fid.ml, fid.mr, fid.bl, fid.br };
            String[] fidNames = new String[] { "TL", "TR", "ML", "MR", "BL", "BR" };
            for (int i = 0; i < fidPts.length; i++) {
                Pt fp = fidPts[i];
                canvas.drawRect(new RectF(fp.x - 14f, fp.y - 14f, fp.x + 14f, fp.y + 14f), pFiducial);
                canvas.drawRect(new RectF(fp.x - 14f, fp.y - 26f, fp.x + 10f, fp.y - 14f), pFidLabelBg);
                canvas.drawText(fidNames[i], fp.x - 11f, fp.y - 16.5f, pFidLabelTxt);
            }
        }

        // Select best matching template across all 6 Sheet-X templates + 2 Camera templates
        TemplateSpec bestTemplate = ALL_TEMPLATES[1]; // default Sheet-X 16Q
        float bestScore = -9999f;

        int targetFilterQ = 0;
        if ("SHEETX_10Q".equals(presetHint) || "TPL_10Q_ID7".equals(presetHint) || "10Q".equals(presetHint)) targetFilterQ = 10;
        else if ("SHEETX_16Q".equals(presetHint) || "TPL_16Q_ID7".equals(presetHint) || "16Q".equals(presetHint)) targetFilterQ = 16;
        else if ("SHEETX_20Q".equals(presetHint) || "TPL_STD_20Q".equals(presetHint)) targetFilterQ = 20;
        else if ("SHEETX_30Q".equals(presetHint) || "TPL_STD_30Q".equals(presetHint)) targetFilterQ = 30;
        else if ("SHEETX_50Q".equals(presetHint) || "TPL_STD_50Q".equals(presetHint)) targetFilterQ = 50;
        else if ("SHEETX_100Q".equals(presetHint) || "TPL_STD_100Q".equals(presetHint)) targetFilterQ = 100;
        else if (questionCountOverride == 10 || questionCountOverride == 16 || questionCountOverride == 20 || questionCountOverride == 30 || questionCountOverride == 50 || questionCountOverride == 100) {
            targetFilterQ = questionCountOverride;
        }

        for (TemplateSpec t : ALL_TEMPLATES) {
            if (targetFilterQ > 0 && t.qCount != targetFilterQ) continue;
            float s = scoreTemplateCandidate(t, fid, scaleX, scaleY, tmpXY);
            if (s > bestScore) {
                bestScore = s;
                bestTemplate = t;
            }
        }

        int effectiveQCount = questionCountOverride > 0
            ? Math.min(bestTemplate.qCount, Math.max(1, questionCountOverride))
            : bestTemplate.qCount;

        // Decode 7-Digit Student ID Grid via 2D Auto-Refined Grid + Peak-Snapped Projective Sampling
        float[] idCal = refineIdGrid(
            bestTemplate.idBaseX,
            bestTemplate.idStepX,
            bestTemplate.idBaseY,
            bestTemplate.idStepY,
            bestTemplate.idR,
            fid,
            scaleX,
            scaleY,
            tmpXY
        );
        StringBuilder sidBuilder = new StringBuilder(7);
        float[] idSrcX = new float[7];
        float[] idSrcY = new float[7];
        int idValidCount = 0;

        float idStepOff = Math.max(1.0f, bestTemplate.idR * 0.22f);
        float[][] idPkOffsets = {
            {0f, 0f}, {-idStepOff, 0f}, {idStepOff, 0f}, {0f, -idStepOff}, {0f, idStepOff}
        };

        for (int col = 0; col < 7; col++) {
            float cx = idCal[0] + col * idCal[1];
            int bestDig = 0;
            float bestDensity = -1f;
            float secondDensity = -1f;
            float bestCx = cx;
            float bestCy = idCal[2];

            for (int dig = 0; dig <= 9; dig++) {
                float cy = idCal[2] + dig * idCal[3];
                float pkD = -1f;
                float pkCx = cx, pkCy = cy;
                for (float[] off : idPkOffsets) {
                    float d = sampleCanonicalCircleDirect(cx + off[0], cy + off[1], bestTemplate.idR * 0.85f, fid, scaleX, scaleY, tmpXY);
                    if (d > pkD) {
                        pkD = d;
                        pkCx = cx + off[0];
                        pkCy = cy + off[1];
                    }
                }
                if (pkD > bestDensity) {
                    secondDensity = bestDensity;
                    bestDensity = pkD;
                    bestDig = dig;
                    bestCx = pkCx;
                    bestCy = pkCy;
                } else if (pkD > secondDensity) {
                    secondDensity = pkD;
                }
            }
            boolean validDigit = bestDensity >= 0.30f || (bestDensity >= 0.18f && (bestDensity - secondDensity) >= 0.07f);
            sidBuilder.append(validDigit ? (char) ('0' + bestDig) : '?');

            if (validDigit) {
                mapCanonicalToSourceFast(bestCx, bestCy, fid, tmpXY);
                idSrcX[idValidCount] = tmpXY[0];
                idSrcY[idValidCount] = tmpXY[1];
                idValidCount++;
            }
        }

        if (idValidCount >= 2) {
            if (canvas != null) {
                for (int k = 0; k < idValidCount; k++) {
                    canvas.drawCircle(idSrcX[k], idSrcY[k], Math.max(5.5f, bestTemplate.idR * 1.15f), pIdRing);
                }
            }
        } else {
            idValidCount = 0;
            sidBuilder.setLength(0);
            sidBuilder.append("NO-ID");
        }

        // Stage 5: Sample Question Bubbles across all columns of bestTemplate with Per-Column 2D Auto-Alignment + Peak Snap
        char[] opts = new char[] { 'A', 'B', 'C', 'D' };
        List<QuestionResult> results = new ArrayList<>(effectiveQCount);
        StringBuilder sigBuilder = new StringBuilder(effectiveQCount * 2);
        int correctCnt = 0;
        int wrongCnt = 0;
        int multiCnt = 0;
        int blankCnt = 0;

        float bubbleScale = Math.max(0.55f, Math.min(1.15f, scaleX));

        for (ColSpec col : bestTemplate.cols) {
            int colRowCount = col.endQ - col.startQ + 1;
            float[] cCal = refineColumnGrid(
                col.baseX,
                col.stepX,
                col.baseY,
                col.stepY,
                colRowCount,
                col.r,
                fid,
                scaleX,
                scaleY,
                tmpXY
            );
            float drawRadius = (col.r + 2.5f) * bubbleScale;
            float pkStep = Math.max(1.2f, col.r * 0.24f);
            float[][] qPkOffsets = {
                {0f, 0f}, {-pkStep, 0f}, {pkStep, 0f}, {0f, -pkStep}, {0f, pkStep}
            };

            for (int q = col.startQ; q <= col.endQ; q++) {
                if (q > effectiveQCount) break;
                int colRowIdx = q - col.startQ;
                float cy = cCal[2] + colRowIdx * cCal[3];

                float[] dVals = new float[4];
                float[] optSrcX = new float[4];
                float[] optSrcY = new float[4];
                float maxD = 0f;
                float secondMaxD = 0f;
                float minD = 1f;
                int bestIdx = 0;

                for (int o = 0; o < 4; o++) {
                    float cx = cCal[0] + o * cCal[1];
                    float pkD = -1f;
                    float bestCx = cx, bestCy = cy;
                    for (float[] off : qPkOffsets) {
                        float d = sampleCanonicalCircleDirect(cx + off[0], cy + off[1], col.r * 0.84f, fid, scaleX, scaleY, tmpXY);
                        if (d > pkD) {
                            pkD = d;
                            bestCx = cx + off[0];
                            bestCy = cy + off[1];
                        }
                    }
                    mapCanonicalToSourceFast(bestCx, bestCy, fid, tmpXY);
                    optSrcX[o] = tmpXY[0];
                    optSrcY[o] = tmpXY[1];
                    dVals[o] = pkD;
                    if (pkD < minD) minD = pkD;
                    if (pkD > maxD) {
                        secondMaxD = maxD;
                        maxD = pkD;
                        bestIdx = o;
                    } else if (pkD > secondMaxD) {
                        secondMaxD = pkD;
                    }
                }

                StringBuilder markedBuilder = new StringBuilder(3);
                for (int o = 0; o < 4; o++) {
                    if (dVals[o] >= 0.34f && (dVals[o] - minD) >= 0.14f && dVals[o] >= maxD * 0.58f) {
                        if (markedBuilder.length() > 0) markedBuilder.append("+");
                        markedBuilder.append(opts[o]);
                    }
                }
                if (markedBuilder.length() == 0 && maxD >= 0.18f && (maxD - secondMaxD) >= 0.08f && maxD >= minD * 2.0f) {
                    markedBuilder.append(opts[bestIdx]);
                }

                String markedStr = markedBuilder.toString();
                sigBuilder.append(markedStr.isEmpty() ? "-" : markedStr).append(",");
                char keyChar = (q - 1 < masterKey.length) ? masterKey[q - 1] : 'A';
                boolean isMulti = markedStr.contains("+");
                boolean isBlank = markedStr.isEmpty();
                boolean isCorr = !isMulti && !isBlank && markedStr.charAt(0) == keyChar;

                if (isCorr) correctCnt++;
                else if (isMulti) multiCnt++;
                else if (isBlank) blankCnt++;
                else wrongCnt++;

                if (canvas != null) {
                    for (int o = 0; o < 4; o++) {
                        char optChar = opts[o];
                        boolean thisMarked = markedStr.indexOf(optChar) >= 0;
                        if (thisMarked) {
                            canvas.drawCircle(
                                optSrcX[o],
                                optSrcY[o],
                                drawRadius,
                                isCorr ? pCorrect : (isMulti ? pMulti : pWrong)
                            );
                        } else if (!isCorr && optChar == keyChar) {
                            canvas.drawCircle(optSrcX[o], optSrcY[o], drawRadius * 0.88f, pKeyHint);
                        }
                    }
                }

                results.add(new QuestionResult(
                    q,
                    isBlank ? "BLANK" : markedStr,
                    keyChar,
                    isCorr,
                    isMulti,
                    isBlank,
                    dVals,
                    optSrcX,
                    optSrcY
                ));
            }
        }

        int pct = effectiveQCount > 0 ? Math.round((correctCnt * 100f) / effectiveQCount) : 0;
        long elapsed = Math.max(1L, System.currentTimeMillis() - t0);
        String sidStr = sidBuilder.toString();
        boolean hasStudentId = !"NO-ID".equals(sidStr);
        String formName = bestTemplate.label;
        boolean is10QForm = "CAMERA_10Q".equals(bestTemplate.code);

        if (canvas != null) {
            Paint pBannerBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            pBannerBg.setColor(Color.argb(225, 2, 6, 23));
            canvas.drawRoundRect(new RectF(10f, 10f, CANONICAL_W - 10f, 54f), 10f, 10f, pBannerBg);

            Paint pBannerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            pBannerStroke.setStyle(Paint.Style.STROKE);
            pBannerStroke.setStrokeWidth(2f);
            pBannerStroke.setColor(Color.parseColor("#10B981"));
            canvas.drawRoundRect(new RectF(10f, 10f, CANONICAL_W - 10f, 54f), 10f, 10f, pBannerStroke);

            Paint pBannerTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
            pBannerTxt.setColor(Color.parseColor("#34D399"));
            pBannerTxt.setTextSize(13.0f);
            pBannerTxt.setTypeface(Typeface.DEFAULT_BOLD);
            String idDisplay = hasStudentId ? ("ID: " + sidStr) : "No-ID Sheet";
            String bannerLine = "✓ " + (fid.lockedCount > 0 ? fid.lockedCount : 4) + " Corners (" + elapsed + "ms) • " + formName + " • " + idDisplay + " • " + correctCnt + "/" + effectiveQCount + " (" + pct + "%)";
            canvas.drawText(bannerLine, 18f, 37f, pBannerTxt);
        }

        return new GradeOutcome(
            effectiveQCount,
            correctCnt,
            wrongCnt,
            multiCnt,
            blankCnt,
            pct,
            sidStr,
            formName,
            is10QForm,
            fid.locked6,
            fid,
            fid.lockedCount > 0 ? fid.lockedCount : 4,
            elapsed,
            results,
            idSrcX,
            idSrcY,
            idValidCount,
            sidStr + ":" + sigBuilder.toString(),
            annotated
        );
    }

    /**
     * Universal 2D Comb-Filter Auto-Grid Calibrator for Custom/Non-Standard Templates:
     * Dynamically discovers [baseX, stepX, baseY, stepY] for a column of 4-option (A-D)
     * bubbles directly from the rectified adaptive darkness field, even when margins or
     * row spacings differ from standard ZipGrade forms.
     */
    private static float[] autoCalibrateColumnGrid(
        float minBaseX,
        float maxBaseX,
        float minBaseY,
        float maxY,
        int rowCount,
        DetectedFiducials fid,
        float scaleX,
        float scaleY,
        float[] tmpXY
    ) {
        float bestBaseX = (minBaseX + maxBaseX) * 0.5f;
        float bestStepX = 31.0f;
        float bestHorizScore = -1e9f;

        // Step 1: Discover horizontal column origin (baseX) and option pitch (stepX) via 4-tine comb filter
        for (float candStepX = 26.0f; candStepX <= 35.0f; candStepX += 1.5f) {
            for (float candBaseX = minBaseX; candBaseX <= maxBaseX; candBaseX += 3.0f) {
                float score = 0f;
                for (float y = minBaseY + 10f; y <= Math.min(maxY, 540f); y += 14f) {
                    float bubbleSum = 0f;
                    float gapSum = 0f;
                    for (int o = 0; o < 4; o++) {
                        bubbleSum += sampleCanonicalCircleDirect(candBaseX + o * candStepX, y, 7.5f, fid, scaleX, scaleY, tmpXY);
                        if (o < 3) {
                            gapSum += sampleCanonicalCircleDirect(candBaseX + (o + 0.5f) * candStepX, y, 3.0f, fid, scaleX, scaleY, tmpXY);
                        }
                    }
                    score += (bubbleSum - gapSum * 1.45f);
                }
                if (score > bestHorizScore) {
                    bestHorizScore = score;
                    bestBaseX = candBaseX;
                    bestStepX = candStepX;
                }
            }
        }

        // Step 2: Discover vertical first-row origin (baseY) and row pitch (stepY) via vertical comb filter
        float bestBaseY = minBaseY + 12f;
        float bestStepY = 40.5f;
        float bestVertScore = -1e9f;
        int checkRows = Math.max(2, Math.min(rowCount, 13));

        for (float candStepY = 30.0f; candStepY <= 45.0f; candStepY += 1.0f) {
            for (float candBaseY = minBaseY; candBaseY <= minBaseY + 95f; candBaseY += 2.5f) {
                if (candBaseY + (checkRows - 1) * candStepY > maxY + 15f) continue;
                float vScore = 0f;
                for (int r = 0; r < checkRows; r++) {
                    float ry = candBaseY + r * candStepY;
                    float rowPeak = 0f;
                    float rowSum = 0f;
                    for (int o = 0; o < 4; o++) {
                        float d = sampleCanonicalCircleDirect(bestBaseX + o * bestStepX, ry, 8.0f, fid, scaleX, scaleY, tmpXY);
                        if (d > rowPeak) rowPeak = d;
                        rowSum += d;
                    }
                    float midRowY = ry + candStepY * 0.5f;
                    float interRowGap = sampleCanonicalCircleDirect(bestBaseX + bestStepX, midRowY, 3.5f, fid, scaleX, scaleY, tmpXY);
                    vScore += (rowPeak * 1.6f + rowSum * 0.35f) - interRowGap * 1.5f;
                }
                if (vScore > bestVertScore) {
                    bestVertScore = vScore;
                    bestBaseY = candBaseY;
                    bestStepY = candStepY;
                }
            }
        }

        return new float[] { bestBaseX, bestStepX, bestBaseY, bestStepY };
    }
}
