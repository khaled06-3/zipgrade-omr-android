package com.zipgrade.omrscanner

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin port of our 5-Stage Optical Mark Recognition (OMR) Computer Vision Pipeline:
 * 1. Integral Image Local Adaptive Thresholding (windowRadius = 28)
 * 2. Global Connected-Component 6-Fiducial Square Detector (TL, TR, ML, MR, BL, BR)
 * 3. Split Upper/Lower Dual-Quad Perspective Rectification to Canonical 600x780
 * 4. 7-Digit Student ID Column Decoder (Forms 922412 & 6353B)
 * 5. Dynamic Question Count (1..30 Questions, 4 or 5 Options) Circular ROI Integrator
 */
object OmrNativeCvEngine {
    const val CANONICAL_W = 600
    const val CANONICAL_H = 780

    val DEFAULT_MASTER_KEY: CharArray = charArrayOf('A', 'B', 'B', 'C', 'D', 'D', 'D', 'D', 'C', 'C', 'C', 'B', 'B', 'D', 'D', 'A', 'A', 'B', 'C', 'D', 'A', 'B', 'C', 'D', 'A', 'B', 'C', 'D', 'A', 'B')
    var questionCountOverride: Int = 16 // -1 = Auto-Detect (10Q, 16Q, 20Q..30Q)
    var optionsCountOverride: Int = undefined // -1 = Auto-Detect (4 or 5)

    data class Point2D(val x: Double, val y: Double)
    data class FiducialSet(
        val tl: Point2D, val tr: Point2D,
        val ml: Point2D, val mr: Point2D,
        val bl: Point2D, val br: Point2D,
        val lockConfidence: Double
    )

    data class QuestionGrade(
        val questionNumber: Int,
        val markedOptions: List<Char>,
        val correctOption: Char,
        val isCorrect: Boolean,
        val isMultipleMark: Boolean
    )

    data class ScanResult(
        val locked: Boolean,
        val lockConfidence: Double,
        val decodedStudentId: String,
        val totalQuestions: Int,
        val correctCount: Int,
        val scorePercent: Double,
        val questions: List<QuestionGrade>
    )

    /**
     * Stage 1: Integral-Image Adaptive Thresholding
     */
    fun computeAdaptiveDarkness(luma: ByteArray, width: Int, height: Int, radius: Int = 28): FloatArray {
        val integral = LongArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                val v = luma[y * width + x].toInt() and 0xFF
                rowSum += v
                integral[(y + 1) * (width + 1) + (x + 1)] =
                    integral[y * (width + 1) + (x + 1)] + rowSum
            }
        }
        val darkness = FloatArray(width * height)
        for (y in 0 until height) {
            val y0 = max(0, y - radius)
            val y1 = min(height - 1, y + radius)
            for (x in 0 until width) {
                val x0 = max(0, x - radius)
                val x1 = min(width - 1, x + radius)
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * (width + 1) + (x1 + 1)] -
                        integral[y0 * (width + 1) + (x1 + 1)] -
                        integral[(y1 + 1) * (width + 1) + x0] +
                        integral[y0 * (width + 1) + x0]
                val localMean = sum.toFloat() / area.toFloat()
                val pixel = (luma[y * width + x].toInt() and 0xFF).toFloat()
                val diff = localMean - pixel
                darkness[y * width + x] = if (diff <= 8f) 0f else min(1f, (diff - 8f) / max(32f, localMean * 0.55f))
            }
        }
        return darkness
    }

    /**
     * Stage 3: Dual-Quad Bilinear / Homography Inverse Mapping
     */
    fun sampleDualQuad(
        srcDarkness: FloatArray,
        fid: FiducialSet,
        cx: Double,
        cy: Double
    ): Float {
        val isUpper = cy <= 390.0
        val u = ((cx - 64.0) / (536.0 - 64.0)).coerceIn(0.0, 1.0)
        val v = if (isUpper) {
            ((cy - 64.0) / (390.0 - 64.0)).coerceIn(0.0, 1.0)
        } else {
            ((cy - 390.0) / (716.0 - 390.0)).coerceIn(0.0, 1.0)
        }
        val qTL = if (isUpper) fid.tl else fid.ml
        val qTR = if (isUpper) fid.tr else fid.mr
        val qBL = if (isUpper) fid.ml else fid.bl
        val qBR = if (isUpper) fid.mr else fid.br

        val sx = (1 - u) * (1 - v) * qTL.x + u * (1 - v) * qTR.x + (1 - u) * v * qBL.x + u * v * qBR.x
        val sy = (1 - u) * (1 - v) * qTL.y + u * (1 - v) * qTR.y + (1 - u) * v * qBL.y + u * v * qBR.y
        val ix = sx.roundToInt().coerceIn(0, CANONICAL_W - 1)
        val iy = sy.roundToInt().coerceIn(0, CANONICAL_H - 1)
        return srcDarkness[iy * CANONICAL_W + ix]
    }
}