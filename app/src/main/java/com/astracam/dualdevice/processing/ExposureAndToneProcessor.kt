package com.astracam.dualdevice.processing

import android.graphics.Bitmap
import kotlin.math.pow

class ExposureAndToneProcessor {

    /**
     * Applies dynamic range tone curve, lifting dark underexposed shadows while
     * compressing high specular highlights to avoid clipping.
     */
    fun applyDynamicToneCurve(
        src: Bitmap,
        shadowLift: Float, // 0.0f (none) to 1.0f (strong)
        highlightCompress: Float // 0.0f (none) to 1.0f (strong)
    ): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // Precompute 256-level non-linear tone mapping LUT
        val lut = IntArray(256)
        for (i in 0..255) {
            val normalized = i / 255.0f

            // Shadow lift using smooth quadratic falloff
            var v = normalized + shadowLift * (1.0f - normalized).pow(2.0f) * normalized

            // Highlight compression via rational knee curve
            if (highlightCompress > 0.0f) {
                val knee = 0.65f
                if (v > knee) {
                    val excess = (v - knee) / (1.0f - knee)
                    v = knee + (1.0f - knee) * (1.0f - (1.0f - excess).pow(1.0f + highlightCompress))
                }
            }

            lut[i] = (v.coerceIn(0.0f, 1.0f) * 255.0f).toInt()
        }

        for (idx in pixels.indices) {
            val p = pixels[idx]
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF

            pixels[idx] = (a shl 24) or (lut[r] shl 16) or (lut[g] shl 8) or lut[b]
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }
}