package com.astracam.dualdevice.processing

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

class ColourCorrectionProcessor {

    /**
     * Applies gray-world chromatic adaptation and subtle vibrance enhancement.
     */
    fun applyColorCorrectionAndVibrance(
        src: Bitmap,
        vibranceBoost: Float = 0.20f // 0.0f to 0.5f
    ): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // Step 1: Compute channel averages for neutral chromatic balance
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        val step = max(1, pixels.size / 5000)
        var sampledCount = 0

        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            totalR += (p ushr 16) and 0xFF
            totalG += (p ushr 8) and 0xFF
            totalB += p and 0xFF
            sampledCount++
            i += step
        }

        val avgR = totalR.toDouble() / sampledCount
        val avgG = totalG.toDouble() / sampledCount
        val avgB = totalB.toDouble() / sampledCount
        val gray = (avgR + avgG + avgB) / 3.0

        // Clamped gain factors to prevent unnatural tinting
        val gainR = (gray / avgR).coerceIn(0.85, 1.15)
        val gainG = (gray / avgG).coerceIn(0.85, 1.15)
        val gainB = (gray / avgB).coerceIn(0.85, 1.15)

        for (idx in pixels.indices) {
            val p = pixels[idx]
            val a = (p ushr 24) and 0xFF
            var r = (((p ushr 16) and 0xFF) * gainR).toInt().coerceIn(0, 255)
            var g = (((p ushr 8) and 0xFF) * gainG).toInt().coerceIn(0, 255)
            var b = ((p and 0xFF) * gainB).toInt().coerceIn(0, 255)

            // Vibrance: selective saturation on less saturated tones
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val saturation = if (maxC == 0) 0.0f else (maxC - minC).toFloat() / maxC

            if (saturation < 0.8f) {
                val factor = 1.0f + vibranceBoost * (1.0f - saturation)
                val lum = (0.299f * r + 0.587f * g + 0.114f * b)
                r = (lum + (r - lum) * factor).toInt().coerceIn(0, 255)
                g = (lum + (g - lum) * factor).toInt().coerceIn(0, 255)
                b = (lum + (b - lum) * factor).toInt().coerceIn(0, 255)
            }

            pixels[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }
}