package com.astracam.dualdevice.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class HdrMergeProcessor {

    /**
     * GCam-inspired Motion-Aware Exposure Fusion.
     * Aligns shifted frames and rejects moving/shaken pixels using a deghosting mask.
     */
    fun mergeExposureBracket(bitmaps: List<Bitmap>): Bitmap {
        require(bitmaps.isNotEmpty()) { "Cannot merge empty bitmap list" }
        if (bitmaps.size == 1) return bitmaps[0]

        val ref = bitmaps[0] // Reference frame (EV 0 sharpest base)
        val width = ref.width
        val height = ref.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val frameCount = bitmaps.size
        val allPixels = Array(frameCount) { IntArray(width * height) }
        for (i in 0 until frameCount) {
            bitmaps[i].getPixels(allPixels[i], 0, width, 0, 0, width, height)
        }

        // 1. Calculate Hand-Shake Shift Vectors (dx, dy) relative to Reference Frame
        val shiftsX = IntArray(frameCount)
        val shiftsY = IntArray(frameCount)

        for (f in 1 until frameCount) {
            val shift = estimateTranslationShift(allPixels[0], allPixels[f], width, height)
            shiftsX[f] = shift.first
            shiftsY[f] = shift.second
        }

        val outPixels = IntArray(width * height)
        val weights = FloatArray(frameCount)

        // 2. Pixel-by-Pixel Fusion with Motion Rejection
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val refPixel = allPixels[0][idx]
                val refR = (refPixel ushr 16) and 0xFF
                val refG = (refPixel ushr 8) and 0xFF
                val refB = refPixel and 0xFF
                val refLum = 0.299f * refR + 0.587f * refG + 0.114f * refB

                var sumWeight = 0.0f

                for (f in 0 until frameCount) {
                    val sx = (x + shiftsX[f]).coerceIn(0, width - 1)
                    val sy = (y + shiftsY[f]).coerceIn(0, height - 1)
                    val p = allPixels[f][sy * width + sx]

                    val r = ((p ushr 16) and 0xFF) / 255.0f
                    val g = ((p ushr 8) and 0xFF) / 255.0f
                    val b = (p and 0xFF) / 255.0f

                    // Gaussian well-exposedness weight (favors mid-tones)
                    val sigma = 0.22f
                    val twoSigSq = 2.0f * sigma * sigma
                    val wExp = exp(-((r - 0.5f) * (r - 0.5f) + (g - 0.5f) * (g - 0.5f) + (b - 0.5f) * (b - 0.5f)) / twoSigSq)

                    // Deghosting Motion Mask: Reject frame if hand moved or pixel differs heavily from reference
                    val pLum = (0.299f * r + 0.587f * g + 0.114f * b) * 255.0f
                    val lumDiff = abs(pLum - refLum)

                    val wMotion = if (f == 0) 1.0f else {
                        // Smoothly damp moving pixels (threshold at 22 levels of difference)
                        exp(-(lumDiff * lumDiff) / (2.0f * 22.0f * 22.0f))
                    }

                    val finalWeight = (wExp * wMotion).coerceAtLeast(1e-4f)
                    weights[f] = finalWeight
                    sumWeight += finalWeight
                }

                var mergedR = 0.0f
                var mergedG = 0.0f
                var mergedB = 0.0f

                for (f in 0 until frameCount) {
                    val normWeight = weights[f] / sumWeight
                    val sx = (x + shiftsX[f]).coerceIn(0, width - 1)
                    val sy = (y + shiftsY[f]).coerceIn(0, height - 1)
                    val p = allPixels[f][sy * width + sx]

                    mergedR += ((p ushr 16) and 0xFF) * normWeight
                    mergedG += ((p ushr 8) and 0xFF) * normWeight
                    mergedB += (p and 0xFF) * normWeight
                }

                outPixels[idx] = (0xFF shl 24) or
                        (mergedR.toInt().coerceIn(0, 255) shl 16) or
                        (mergedG.toInt().coerceIn(0, 255) shl 8) or
                        mergedB.toInt().coerceIn(0, 255)
            }
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Fast luminance cross-correlation to estimate global hand movement (dx, dy).
     */
    private fun estimateTranslationShift(
        refPixels: IntArray,
        targetPixels: IntArray,
        w: Int,
        h: Int
    ): Pair<Int, Int> {
        val sampleStep = 8 // Fast downsampling
        val maxShift = 16   // Search radius [-16..16] pixels
        var bestShiftX = 0
        var bestShiftY = 0
        var minSad = Long.MAX_VALUE

        val startY = h / 4
        val endY = (3 * h) / 4
        val startX = w / 4
        val endX = (3 * w) / 4

        for (dy in -maxShift..maxShift step 4) {
            for (dx in -maxShift..maxShift step 4) {
                var currentSad = 0L

                var y = startY
                while (y < endY) {
                    var x = startX
                    while (x < endX) {
                        val refP = refPixels[y * w + x]
                        val tgtP = targetPixels[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]

                        val refLum = (refP ushr 8) and 0xFF
                        val tgtLum = (tgtP ushr 8) and 0xFF
                        currentSad += abs(refLum - tgtLum)

                        x += sampleStep
                    }
                    y += sampleStep
                }

                if (currentSad < minSad) {
                    minSad = currentSad
                    bestShiftX = dx
                    bestShiftY = dy
                }
            }
        }

        return Pair(bestShiftX, bestShiftY)
    }
}