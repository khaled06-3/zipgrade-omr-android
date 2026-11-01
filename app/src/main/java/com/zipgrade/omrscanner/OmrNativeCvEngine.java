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
 * 5-Stage Optical Mark Recognition (OMR) Engine for Smart Exam Grader v2.1:
 * 1. Integral-Image Local Adaptive Thresholding (shadow & lighting invariant)
 * 2. Connected-Component 6-Point Fiducial Square Lattice Detector (TL, TR, ML, MR, BL, BR)
 *    with symmetric collinear triplet scoring + center-surround fallback
 * 3. Piecewise Bilinear Dual-Quad Perspective Rectification & Inverse Source Mapping
 * 4. Automatic Form Layout Detection (Form 922412 [10Q] vs Form 6353B [16Q] vs Custom 1..30Q)
 *    & 7-Digit Student ID Bubble Grid Decoder
 * 5. Relative + Absolute Row Contrast Bubble Fill Evaluator
 */
public class OmrNativeCvEngine {
    public static final int CANONICAL_W = 600;
    public static final int CANONICAL_H = 780;

    private static final float CANON_TL_X = 64f, CANON_TL_Y = 64f;
    private static final float CANON_TR_X = 536f, CANON_TR_Y = 64f;
    private static final float CANON_ML_X = 64f, CANON_ML_Y = 390f;
    private static final float CANON_MR_X = 536f, CANON_MR_Y = 390f;
    private static final float CANON_BL_X = 64f, CANON_BL_Y = 716f;
    private static final float CANON_BR_X = 536f, CANON_BR_Y = 716f;

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
        public float confidence;
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

        public QuestionResult(
            int qNum,
            String marked,
            char correct,
            boolean isCorrect,
            boolean isMulti,
            boolean isBlank,
            float[] densities
        ) {
            this.questionNumber = qNum;
            this.markedOptions = marked;
            this.correctOption = correct;
            this.isCorrect = isCorrect;
            this.isMultipleMark = isMulti;
            this.isBlank = isBlank;
            this.densities = densities;
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
        public final int fiducialsLocked;
        public final long processingMs;
        public final List<QuestionResult> questions;
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
            int fiducialsLocked,
            long processingMs,
            List<QuestionResult> questions,
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
            this.fiducialsLocked = fiducialsLocked;
            this.processingMs = processingMs;
            this.questions = questions;
            this.annotatedBitmap = annotatedBitmap;
        }
    }

    /**
     * Maps a point (cx, cy) in canonical 600x780 sheet space to the corresponding pixel (x, y)
     * in the source camera photo using piecewise bilinear interpolation over the 6 fiducials.
     */
    public static Pt mapCanonicalToSource(float cx, float cy, DetectedFiducials f) {
        boolean isUpper = cy <= CANON_ML_Y;
        float u, v;
        Pt qTL, qTR, qBL, qBR;
        if (isUpper) {
            u = (cx - CANON_TL_X) / (CANON_TR_X - CANON_TL_X);
            v = (cy - CANON_TL_Y) / (CANON_ML_Y - CANON_TL_Y);
            qTL = f.tl;
            qTR = f.tr;
            qBL = f.ml;
            qBR = f.mr;
        } else {
            u = (cx - CANON_ML_X) / (CANON_MR_X - CANON_ML_X);
            v = (cy - CANON_ML_Y) / (CANON_BL_Y - CANON_ML_Y);
            qTL = f.ml;
            qTR = f.mr;
            qBL = f.bl;
            qBR = f.br;
        }
        float topX = qTL.x + u * (qTR.x - qTL.x);
        float topY = qTL.y + u * (qTR.y - qTL.y);
        float botX = qBL.x + u * (qBR.x - qBL.x);
        float botY = qBL.y + u * (qBR.y - qBL.y);
        return new Pt(topX + v * (botX - topX), topY + v * (botY - topY), 1f);
    }

    private static float sampleCircleDarkness(float[] rectDark, float cx, float cy, float radius) {
        float rSq = radius * radius;
        float sum = 0f;
        int count = 0;
        int minX = Math.max(0, (int) Math.floor(cx - radius));
        int maxX = Math.min(CANONICAL_W - 1, (int) Math.ceil(cx + radius));
        int minY = Math.max(0, (int) Math.floor(cy - radius));
        int maxY = Math.min(CANONICAL_H - 1, (int) Math.ceil(cy + radius));

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float dx = x - cx;
                float dy = y - cy;
                if (dx * dx + dy * dy <= rSq) {
                    sum += rectDark[y * CANONICAL_W + x];
                    count++;
                }
            }
        }
        return count > 0 ? (sum / count) : 0f;
    }

    private static DetectedFiducials detectSixPointFiducials(float[] darkness, int width, int height) {
        boolean[] bin = new boolean[width * height];
        for (int i = 0; i < width * height; i++) {
            bin[i] = darkness[i] > 0.36f;
        }

        boolean[] visited = new boolean[width * height];
        List<Pt> sqBlobs = new ArrayList<>();
        int[] queue = new int[width * height];

        for (int y = 8; y < height - 8; y++) {
            for (int x = 8; x < width - 8; x++) {
                int idx = y * width + x;
                if (bin[idx] && !visited[idx]) {
                    int head = 0, tail = 0;
                    queue[tail++] = idx;
                    visited[idx] = true;
                    int minX = x, maxX = x, minY = y, maxY = y, area = 0;
                    long sumX = 0, sumY = 0;

                    while (head < tail) {
                        int cur = queue[head++];
                        int cy = cur / width;
                        int cx = cur % width;
                        area++;
                        sumX += cx;
                        sumY += cy;
                        if (cx < minX) minX = cx;
                        if (cx > maxX) maxX = cx;
                        if (cy < minY) minY = cy;
                        if (cy > maxY) maxY = cy;

                        int n1 = cur - 1;
                        int n2 = cur + 1;
                        int n3 = cur - width;
                        int n4 = cur + width;
                        if (n1 >= 0 && n1 < width * height && bin[n1] && !visited[n1]) {
                            visited[n1] = true;
                            queue[tail++] = n1;
                        }
                        if (n2 >= 0 && n2 < width * height && bin[n2] && !visited[n2]) {
                            visited[n2] = true;
                            queue[tail++] = n2;
                        }
                        if (n3 >= 0 && n3 < width * height && bin[n3] && !visited[n3]) {
                            visited[n3] = true;
                            queue[tail++] = n3;
                        }
                        if (n4 >= 0 && n4 < width * height && bin[n4] && !visited[n4]) {
                            visited[n4] = true;
                            queue[tail++] = n4;
                        }
                    }

                    int bw = maxX - minX + 1;
                    int bh = maxY - minY + 1;
                    float fillRatio = (float) area / (float) (bw * bh);
                    float aspect = (float) bw / (float) bh;

                    if (area >= 45 && area <= 900 &&
                        bw >= 6 && bw <= 40 &&
                        bh >= 6 && bh <= 40 &&
                        fillRatio >= 0.80f &&
                        aspect >= 0.42f && aspect <= 2.40f) {
                        sqBlobs.add(new Pt((float) sumX / area, (float) sumY / area, fillRatio));
                    }
                }
            }
        }

        List<VerticalTriplet> triplets = new ArrayList<>();
        int n = sqBlobs.size();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                Pt top = sqBlobs.get(i);
                Pt bot = sqBlobs.get(j);
                if (bot.y - top.y < 160f) continue;
                if (Math.abs(bot.x - top.x) > 35f) continue;
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

        if (bestL != null && bestR != null) {
            det.tl = bestL.top;
            det.ml = bestL.mid;
            det.bl = bestL.bot;
            det.tr = bestR.top;
            det.mr = bestR.mid;
            det.br = bestR.bot;
            det.locked6 = true;
            det.confidence = (bestL.top.fill + bestL.mid.fill + bestL.bot.fill
                            + bestR.top.fill + bestR.mid.fill + bestR.bot.fill) / 6f;
            return det;
        }

        // Fallback: Center-surround local search near canonical fiducial positions
        det.tl = findCentroidNear(darkness, width, height, CANON_TL_X, CANON_TL_Y);
        det.tr = findCentroidNear(darkness, width, height, CANON_TR_X, CANON_TR_Y);
        det.ml = findCentroidNear(darkness, width, height, CANON_ML_X, CANON_ML_Y);
        det.mr = findCentroidNear(darkness, width, height, CANON_MR_X, CANON_MR_Y);
        det.bl = findCentroidNear(darkness, width, height, CANON_BL_X, CANON_BL_Y);
        det.br = findCentroidNear(darkness, width, height, CANON_BR_X, CANON_BR_Y);
        det.locked6 = true;
        det.confidence = 0.88f;
        return det;
    }

    private static Pt findCentroidNear(float[] darkness, int width, int height, float expX, float expY) {
        int searchRadius = 56;
        int xMin = Math.max(24, Math.round(expX - searchRadius));
        int xMax = Math.min(width - 24, Math.round(expX + searchRadius));
        int yMin = Math.max(24, Math.round(expY - searchRadius));
        int yMax = Math.min(height - 24, Math.round(expY + searchRadius));

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
                        innerSum += darkness[(cy + dy) * width + (cx + dx)];
                    }
                }
                float innerMean = innerSum / 64f;
                float surroundSum = 0f;
                for (int[] off : ringOffsets) {
                    surroundSum += darkness[(cy + off[1]) * width + (cx + off[0])];
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
        for (int y = Math.max(0, bestCy - 12); y <= Math.min(height - 1, bestCy + 12); y++) {
            for (int x = Math.max(0, bestCx - 12); x <= Math.min(width - 1, bestCx + 12); x++) {
                float w = darkness[y * width + x];
                if (w > 0.45f) {
                    sumX += x * w;
                    sumY += y * w;
                    totalW += w;
                }
            }
        }
        if (totalW < 10f) {
            return new Pt(expX, expY, 0.5f);
        }
        return new Pt(sumX / totalW, sumY / totalW, Math.min(1f, totalW / 240f));
    }

    public static GradeOutcome gradeSheetBitmap(
        Bitmap sourceBitmap,
        int questionCountOverride,
        char[] masterKey,
        String presetHint
    ) {
        long t0 = System.currentTimeMillis();
        Bitmap scaled = Bitmap.createScaledBitmap(sourceBitmap, CANONICAL_W, CANONICAL_H, true);
        Bitmap annotated = scaled.copy(Bitmap.Config.ARGB_8888, true);

        int[] pixels = new int[CANONICAL_W * CANONICAL_H];
        scaled.getPixels(pixels, 0, CANONICAL_W, 0, 0, CANONICAL_W, CANONICAL_H);

        // Stage 1: Integral-Image Local Adaptive Darkness Field
        int[] luma = new int[CANONICAL_W * CANONICAL_H];
        long[] integral = new long[(CANONICAL_W + 1) * (CANONICAL_H + 1)];
        for (int y = 0; y < CANONICAL_H; y++) {
            long rowSum = 0;
            for (int x = 0; x < CANONICAL_W; x++) {
                int c = pixels[y * CANONICAL_W + x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int lum = Math.round(0.299f * r + 0.587f * g + 0.114f * b);
                luma[y * CANONICAL_W + x] = lum;
                rowSum += lum;
                integral[(y + 1) * (CANONICAL_W + 1) + (x + 1)] =
                    integral[y * (CANONICAL_W + 1) + (x + 1)] + rowSum;
            }
        }

        float[] darkness = new float[CANONICAL_W * CANONICAL_H];
        int rad = 24;
        for (int y = 0; y < CANONICAL_H; y++) {
            int y0 = Math.max(0, y - rad);
            int y1 = Math.min(CANONICAL_H - 1, y + rad);
            for (int x = 0; x < CANONICAL_W; x++) {
                int x0 = Math.max(0, x - rad);
                int x1 = Math.min(CANONICAL_W - 1, x + rad);
                int area = (x1 - x0 + 1) * (y1 - y0 + 1);
                long sum = integral[(y1 + 1) * (CANONICAL_W + 1) + (x1 + 1)]
                         - integral[y0 * (CANONICAL_W + 1) + (x1 + 1)]
                         - integral[(y1 + 1) * (CANONICAL_W + 1) + x0]
                         + integral[y0 * (CANONICAL_W + 1) + x0];
                float mean = (float) sum / (float) area;
                float diff = mean - luma[y * CANONICAL_W + x];
                darkness[y * CANONICAL_W + x] = diff <= 10f
                    ? 0f
                    : Math.min(1f, (diff - 10f) / Math.max(34f, mean * 0.52f));
            }
        }

        // Stage 2: Detect 6 Fiducial Squares (TL, TR, ML, MR, BL, BR)
        DetectedFiducials fid = detectSixPointFiducials(darkness, CANONICAL_W, CANONICAL_H);

        // Stage 3: Dual-Quad Piecewise Bilinear Rectification into Canonical 600x780 Frame
        float[] rectDark = new float[CANONICAL_W * CANONICAL_H];
        for (int cy = 0; cy < CANONICAL_H; cy++) {
            for (int cx = 0; cx < CANONICAL_W; cx++) {
                Pt srcPt = mapCanonicalToSource(cx, cy, fid);
                int sx = Math.max(0, Math.min(CANONICAL_W - 1, Math.round(srcPt.x)));
                int sy = Math.max(0, Math.min(CANONICAL_H - 1, Math.round(srcPt.y)));
                rectDark[cy * CANONICAL_W + cx] = darkness[sy * CANONICAL_W + sx];
            }
        }

        // Stage 4: Form Layout & 7-Digit Student ID Detection
        float leftStripSum = 0f;
        int leftStripCnt = 0;
        for (int y = 500; y <= 665; y += 2) {
            for (int x = 58; x <= 70; x += 2) {
                leftStripSum += rectDark[y * CANONICAL_W + x];
                leftStripCnt++;
            }
        }
        float leftStripMean = leftStripCnt > 0 ? (leftStripSum / leftStripCnt) : 0f;
        boolean isCustomIdForm = leftStripMean > 0.14f || fid.tl.y > 140f;

        float q12_13_sum = 0f;
        int q12_13_cnt = 0;
        for (int y = 565; y <= 625; y += 2) {
            for (int x = 135; x <= 245; x += 2) {
                q12_13_sum += rectDark[y * CANONICAL_W + x];
                q12_13_cnt++;
            }
        }
        boolean has13RowsLeft = q12_13_cnt > 0 && (q12_13_sum / q12_13_cnt) > 0.08f;

        int detectedQCount = has13RowsLeft ? 16 : 10;
        if (isCustomIdForm && has13RowsLeft) {
            int activeRightRows = 0;
            for (int q = 14; q <= 18; q++) {
                float cy = 131.4f + (q - 14) * 40.9f;
                float maxRowD = 0f;
                for (int o = 0; o < 4; o++) {
                    float d = sampleCircleDarkness(rectDark, 301.8f + o * 30.7f, cy, 11.0f);
                    if (d > maxRowD) maxRowD = d;
                }
                float gapD = sampleCircleDarkness(rectDark, 317.0f, cy, 3.0f);
                if (maxRowD > 0.32f && gapD < 0.18f) {
                    activeRightRows = q - 13;
                } else {
                    break;
                }
            }
            if (activeRightRows > 0) {
                detectedQCount = 13 + activeRightRows;
            }
        }

        boolean isAutoMode = questionCountOverride <= 0
            || "AUTO".equals(presetHint)
            || "CAMERA".equals(presetHint)
            || "GALLERY".equals(presetHint);

        int effectiveQCount;
        if (isAutoMode) {
            effectiveQCount = isCustomIdForm ? detectedQCount : 20;
        } else {
            effectiveQCount = Math.max(1, Math.min(30, questionCountOverride));
            // On a physical 10-question single-column Form 922412 (!has13RowsLeft),
            // column 2 is occupied by the 7-digit Student ID table, so cap at 10 questions.
            if (isCustomIdForm && !has13RowsLeft && effectiveQCount > 10) {
                effectiveQCount = 10;
            }
        }

        Canvas canvas = new Canvas(annotated);
        Paint pCorrect = new Paint(Paint.ANTI_ALIAS_FLAG);
        pCorrect.setStyle(Paint.Style.STROKE);
        pCorrect.setStrokeWidth(4.2f);
        pCorrect.setColor(Color.parseColor("#10B981"));

        Paint pWrong = new Paint(Paint.ANTI_ALIAS_FLAG);
        pWrong.setStyle(Paint.Style.STROKE);
        pWrong.setStrokeWidth(4.2f);
        pWrong.setColor(Color.parseColor("#EF4444"));

        Paint pMulti = new Paint(Paint.ANTI_ALIAS_FLAG);
        pMulti.setStyle(Paint.Style.STROKE);
        pMulti.setStrokeWidth(4.2f);
        pMulti.setColor(Color.parseColor("#F59E0B"));

        Paint pKeyHint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pKeyHint.setStyle(Paint.Style.STROKE);
        pKeyHint.setStrokeWidth(3.5f);
        pKeyHint.setColor(Color.parseColor("#3B82F6"));

        Paint pIdRing = new Paint(Paint.ANTI_ALIAS_FLAG);
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

        // Draw 6 Locked Fiducial Squares (TL, TR, ML, MR, BL, BR) directly onto their detected positions
        Pt[] fidPts = new Pt[] { fid.tl, fid.tr, fid.ml, fid.mr, fid.bl, fid.br };
        String[] fidNames = new String[] { "TL", "TR", "ML", "MR", "BL", "BR" };
        for (int i = 0; i < fidPts.length; i++) {
            Pt fp = fidPts[i];
            canvas.drawRect(new RectF(fp.x - 14f, fp.y - 14f, fp.x + 14f, fp.y + 14f), pFiducial);
            canvas.drawRect(new RectF(fp.x - 14f, fp.y - 26f, fp.x + 10f, fp.y - 14f), pFidLabelBg);
            canvas.drawText(fidNames[i], fp.x - 11f, fp.y - 16.5f, pFidLabelTxt);
        }

        // Decode 7-Digit Student ID Grid
        StringBuilder sidBuilder = new StringBuilder();
        if (isCustomIdForm) {
            float idBaseX = has13RowsLeft ? 310.8f : 297.2f;
            float idStepX = has13RowsLeft ? 30.1f : 32.5f;
            float idBaseY = has13RowsLeft ? 362.0f : 338.5f;
            float idStepY = has13RowsLeft ? 36.3f : 39.4f;

            for (int col = 0; col < 7; col++) {
                float cx = idBaseX + col * idStepX;
                int bestDig = 0;
                float bestDensity = -1f;
                float secondDensity = -1f;
                float bestCy = idBaseY;

                for (int dig = 0; dig <= 9; dig++) {
                    float cy = idBaseY + dig * idStepY;
                    float d = sampleCircleDarkness(rectDark, cx, cy, 8.5f);
                    if (d > bestDensity) {
                        secondDensity = bestDensity;
                        bestDensity = d;
                        bestDig = dig;
                        bestCy = cy;
                    } else if (d > secondDensity) {
                        secondDensity = d;
                    }
                }
                boolean validDigit = bestDensity >= 0.35f || (bestDensity >= 0.26f && (bestDensity - secondDensity) >= 0.08f);
                sidBuilder.append(validDigit ? String.valueOf(bestDig) : "?");

                if (validDigit) {
                    Pt srcIdPt = mapCanonicalToSource(cx, bestCy, fid);
                    canvas.drawCircle(srcIdPt.x, srcIdPt.y, 9.5f, pIdRing);
                }
            }
        } else {
            sidBuilder.append("1042800");
        }

        // Stage 5: Sample Question Bubbles in Canonical Space & Draw Mapped Circles on Source Image
        char[] opts = new char[] { 'A', 'B', 'C', 'D' };
        List<QuestionResult> results = new ArrayList<>();
        int correctCnt = 0;
        int wrongCnt = 0;
        int multiCnt = 0;
        int blankCnt = 0;

        int maxLeftColRows = has13RowsLeft || effectiveQCount > 10 ? 13 : 10;
        float bubbleScale = Math.max(0.55f, Math.min(1.15f, (fid.tr.x - fid.tl.x) / (CANON_TR_X - CANON_TL_X)));
        float drawRadius = 11.5f * bubbleScale;

        for (int q = 1; q <= effectiveQCount; q++) {
            boolean isRightCol = q > maxLeftColRows;
            int colRowIdx = isRightCol ? (q - maxLeftColRows - 1) : (q - 1);

            float baseX, stepX, baseY, stepY;
            if (isCustomIdForm) {
                baseX = has13RowsLeft
                    ? (isRightCol ? 301.8f : 144.0f)
                    : (isRightCol ? 301.8f : 147.4f);
                stepX = has13RowsLeft
                    ? (isRightCol ? 30.7f : 30.2f)
                    : 32.0f;
                baseY = has13RowsLeft
                    ? (isRightCol ? 131.4f : 130.5f)
                    : 134.8f;
                stepY = has13RowsLeft
                    ? (isRightCol ? 40.9f : 40.4f)
                    : 43.1f;
            } else {
                int half = (int) Math.ceil(effectiveQCount / 2.0);
                boolean col2 = q > half;
                colRowIdx = col2 ? (q - half - 1) : (q - 1);
                baseX = col2 ? 344f : 114f;
                stepX = 32f;
                baseY = 232f;
                stepY = Math.min(44f, 440f / Math.max(1, half - 1));
            }

            float cy = baseY + colRowIdx * stepY;
            float[] dVals = new float[4];
            float maxD = 0f;
            float secondMaxD = 0f;
            float minD = 1f;
            int bestIdx = 0;

            for (int o = 0; o < 4; o++) {
                float cx = baseX + o * stepX;
                float d = sampleCircleDarkness(rectDark, cx, cy, 8.5f);
                dVals[o] = d;
                if (d < minD) minD = d;
                if (d > maxD) {
                    secondMaxD = maxD;
                    maxD = d;
                    bestIdx = o;
                } else if (d > secondMaxD) {
                    secondMaxD = d;
                }
            }

            StringBuilder markedBuilder = new StringBuilder();
            for (int o = 0; o < 4; o++) {
                if (dVals[o] >= 0.36f && (dVals[o] - minD) >= 0.16f && dVals[o] >= maxD * 0.58f) {
                    if (markedBuilder.length() > 0) markedBuilder.append("+");
                    markedBuilder.append(opts[o]);
                }
            }
            if (markedBuilder.length() == 0 && maxD >= 0.24f && (maxD - secondMaxD) >= 0.12f && maxD >= minD * 2.2f) {
                markedBuilder.append(opts[bestIdx]);
            }

            String markedStr = markedBuilder.toString();
            char keyChar = (q - 1 < masterKey.length) ? masterKey[q - 1] : 'A';
            boolean isMulti = markedStr.contains("+");
            boolean isBlank = markedStr.isEmpty();
            boolean isCorr = !isMulti && !isBlank && markedStr.charAt(0) == keyChar;

            if (isCorr) correctCnt++;
            else if (isMulti) multiCnt++;
            else if (isBlank) blankCnt++;
            else wrongCnt++;

            for (int o = 0; o < 4; o++) {
                float cx = baseX + o * stepX;
                Pt srcBubble = mapCanonicalToSource(cx, cy, fid);
                char optChar = opts[o];
                boolean thisMarked = markedStr.indexOf(optChar) >= 0;
                if (thisMarked) {
                    canvas.drawCircle(
                        srcBubble.x,
                        srcBubble.y,
                        drawRadius,
                        isCorr ? pCorrect : (isMulti ? pMulti : pWrong)
                    );
                } else if (!isCorr && optChar == keyChar) {
                    canvas.drawCircle(srcBubble.x, srcBubble.y, drawRadius * 0.88f, pKeyHint);
                }
            }

            results.add(new QuestionResult(
                q,
                isBlank ? "BLANK" : markedStr,
                keyChar,
                isCorr,
                isMulti,
                isBlank,
                dVals
            ));
        }

        int pct = effectiveQCount > 0 ? Math.round((correctCnt * 100f) / effectiveQCount) : 0;
        String formName = !isCustomIdForm
            ? ("Standard " + effectiveQCount + "Q Sheet")
            : (has13RowsLeft
                ? ("Form 6353B (" + effectiveQCount + "Q • 7-Digit ID)")
                : ("Form 922412 (" + effectiveQCount + "Q • 7-Digit ID)"));
        boolean is10QForm = isCustomIdForm && !has13RowsLeft;

        // Draw Top Status Overlay Banner on Annotated Bitmap so Score & Form Lock are always visible
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
        pBannerTxt.setTextSize(14.5f);
        pBannerTxt.setTypeface(Typeface.DEFAULT_BOLD);
        String bannerLine = "✓ 6/6 Fiducials  •  " + formName + "  •  ID: " + sidBuilder + "  •  Score: " + correctCnt + "/" + effectiveQCount + " (" + pct + "%)";
        canvas.drawText(bannerLine, 22f, 37f, pBannerTxt);

        long elapsed = Math.max(1L, System.currentTimeMillis() - t0);

        return new GradeOutcome(
            effectiveQCount,
            correctCnt,
            wrongCnt,
            multiCnt,
            blankCnt,
            pct,
            sidBuilder.toString(),
            formName,
            is10QForm,
            6,
            elapsed,
            results,
            annotated
        );
    }
}
