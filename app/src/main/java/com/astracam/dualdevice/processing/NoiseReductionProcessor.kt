package com.astracam.dualdevice.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp

class NoiseReductionProcessor {

    /**
     * Edge-preserving bilateral filter on luminance channel.
     * Smooths high-ISO sensor noise in uniform regions while preserving true edges.
     */
    fun applyEdgePreservingDenoise(src: Bitmap, spatialSigma: Float = 2.0f, rangeSigma: Float = 25.0f): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val inPixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(inPixels, 0, width, 0, 0, width, height)

        val radius = 2
        val twoSigmaSpatialSq = 2.0f * spatialSigma * spatialSigma
        val twoSigmaRangeSq = 2.0f * rangeSigma * rangeSigma

        for (y in 0 until height) {
            for (x in 0 until width) {
                val centerIdx = y * width + x
                val centerPixel = inPixels[centerIdx]
                val cr = (centerPixel ushr 16) and 0xFF
                val cg = (centerPixel ushr 8) and 0xFF
                val cb = centerPixel and 0xFF
                val cLum = (0.299f * cr + 0.587f * cg + 0.114f * cb)

                var sumR = 0.0f
                var sumG = 0.0f
                var sumB = 0.0f
                var sumWeight = 0.0f

                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, height - 1)
                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val nPixel = inPixels[ny * width + nx]
                        val nr = (nPixel ushr 16) and 0xFF
                        val ng = (nPixel ushr 8) and 0xFF
                        val nb = nPixel and 0xFF
                        val nLum = (0.299f * nr + 0.587f * ng + 0.114f * nb)

                        val distSpatialSq = (dx * dx + dy * dy).toFloat()
                        val diffLum = cLum - nLum
                        val distRangeSq = diffLum * diffLum

                        val weight = exp(-distSpatialSq / twoSigmaSpatialSq - distRangeSq / twoSigmaRangeSq)
                        sumR += nr * weight
                        sumG += ng * weight
                        sumB += nb * weight
                        sumWeight += weight
                    }
                }

                val finalR = (sumR / sumWeight).toInt().coerceIn(0, 255)
                val finalG = (sumG / sumWeight).toInt().coerceIn(0, 255)
                val finalB = (sumB / sumWeight).toInt().coerceIn(0, 255)
                val a = (centerPixel ushr 24) and 0xFF

                outPixels[centerIdx] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }
}