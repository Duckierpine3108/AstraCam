package com.astracam.dualdevice.processing

import android.graphics.Bitmap
import kotlin.math.abs

class DetailEnhancementProcessor {

    /**
     * Thresholded unsharp masking to enhance DSLR-like micro-contrast and fine detail
     * without boosting background noise or creating white ringing halos.
     */
    fun applyEdgeAwareDetailSharpening(
        src: Bitmap,
        strength: Float = 0.45f,
        noiseThreshold: Int = 4,
        haloClamp: Int = 40
    ): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val inPixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(inPixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val center = inPixels[idx]
                val a = (center ushr 24) and 0xFF
                val r = (center ushr 16) and 0xFF
                val g = (center ushr 8) and 0xFF
                val b = center and 0xFF

                // Fast 3x3 local mean for unsharp base
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0

                for (dy in -1..1) {
                    val ny = (y + dy).coerceIn(0, height - 1)
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val p = inPixels[ny * width + nx]
                        sumR += (p ushr 16) and 0xFF
                        sumG += (p ushr 8) and 0xFF
                        sumB += p and 0xFF
                        count++
                    }
                }

                val avgR = sumR / count
                val avgG = sumG / count
                val avgB = sumB / count

                val diffR = r - avgR
                val diffG = g - avgG
                val diffB = b - avgB

                val newR = if (abs(diffR) > noiseThreshold) {
                    val boost = (diffR * strength).toInt().coerceIn(-haloClamp, haloClamp)
                    (r + boost).coerceIn(0, 255)
                } else r

                val newG = if (abs(diffG) > noiseThreshold) {
                    val boost = (diffG * strength).toInt().coerceIn(-haloClamp, haloClamp)
                    (g + boost).coerceIn(0, 255)
                } else g

                val newB = if (abs(diffB) > noiseThreshold) {
                    val boost = (diffB * strength).toInt().coerceIn(-haloClamp, haloClamp)
                    (b + boost).coerceIn(0, 255)
                } else b

                outPixels[idx] = (a shl 24) or (newR shl 16) or (newG shl 8) or newB
            }
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }
}