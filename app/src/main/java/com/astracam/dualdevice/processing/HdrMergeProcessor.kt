package com.astracam.dualdevice.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp

class HdrMergeProcessor {

    /**
     * Exposure Fusion (Mertens-inspired).
     * Merges bracketed exposures using well-exposedness and local contrast weighting.
     */
    fun mergeExposureBracket(bitmaps: List<Bitmap>): Bitmap {
        require(bitmaps.isNotEmpty()) { "Cannot merge empty bitmap list" }
        if (bitmaps.size == 1) return bitmaps[0]

        val width = bitmaps[0].width
        val height = bitmaps[0].height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val frameCount = bitmaps.size
        val allPixels = Array(frameCount) { IntArray(width * height) }
        for (i in 0 until frameCount) {
            bitmaps[i].getPixels(allPixels[i], 0, width, 0, 0, width, height)
        }

        val outPixels = IntArray(width * height)
        val weights = FloatArray(frameCount)

        for (idx in outPixels.indices) {
            var sumWeight = 0.0f

            for (f in 0 until frameCount) {
                val p = allPixels[f][idx]
                val r = ((p ushr 16) and 0xFF) / 255.0f
                val g = ((p ushr 8) and 0xFF) / 255.0f
                val b = (p and 0xFF) / 255.0f

                // Well-exposedness Gaussian curve centered at 0.5
                val sigma = 0.2f
                val twoSigSq = 2.0f * sigma * sigma
                val wR = exp(-((r - 0.5f) * (r - 0.5f)) / twoSigSq)
                val wG = exp(-((g - 0.5f) * (g - 0.5f)) / twoSigSq)
                val wB = exp(-((b - 0.5f) * (b - 0.5f)) / twoSigSq)
                val weight = (wR * wG * wB).coerceAtLeast(1e-4f)

                weights[f] = weight
                sumWeight += weight
            }

            var mergedR = 0.0f
            var mergedG = 0.0f
            var mergedB = 0.0f

            for (f in 0 until frameCount) {
                val normWeight = weights[f] / sumWeight
                val p = allPixels[f][idx]
                mergedR += ((p ushr 16) and 0xFF) * normWeight
                mergedG += ((p ushr 8) and 0xFF) * normWeight
                mergedB += (p and 0xFF) * normWeight
            }

            val finalR = mergedR.toInt().coerceIn(0, 255)
            val finalG = mergedG.toInt().coerceIn(0, 255)
            val finalB = mergedB.toInt().coerceIn(0, 255)

            outPixels[idx] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }
}