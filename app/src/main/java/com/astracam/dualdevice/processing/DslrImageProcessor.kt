package com.astracam.dualdevice.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class DslrTuningParameters(
    var exposure: Float = 0.0f,       // -50.0 to +50.0
    var shadows: Float = 35.0f,       // 0.0 to 100.0 (lift shadow tones)
    var highlights: Float = 25.0f,    // 0.0 to 100.0 (compress highlights)
    var contrast: Float = 10.0f,      // -50.0 to +50.0
    var clarity: Float = 25.0f,       // 0.0 to 100.0 (micro-contrast)
    var saturation: Float = 15.0f,    // -50.0 to +50.0
    var temperature: Float = 0.0f,    // -50.0 to +50.0 (cool to warm)
    var sharpness: Float = 40.0f      // 0.0 to 100.0
)

class DslrImageProcessor {

    fun processBitmap(source: Bitmap, params: DslrTuningParameters): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val inPixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        source.getPixels(inPixels, 0, width, 0, 0, width, height)

        // 1. Build Precomputed 256-Tone S-Curve Mapping LUT
        val lut = IntArray(256)
        val expMultiplier = (1.0f + params.exposure / 50.0f).coerceIn(0.5f, 2.0f)
        val shadowLift = (params.shadows / 100.0f).coerceIn(0.0f, 1.0f)
        val highCompress = (params.highlights / 100.0f).coerceIn(0.0f, 1.0f)
        val contrastFactor = (1.0f + params.contrast / 100.0f).coerceIn(0.5f, 1.8f)

        for (i in 0..255) {
            var v = (i / 255.0f) * expMultiplier

            // Non-linear shadow boost
            v += shadowLift * (1.0f - v.coerceIn(0.0f, 1.0f)).pow(2.0f) * v

            // Highlight recovery curve
            if (v > 0.60f && highCompress > 0f) {
                val excess = (v - 0.60f) / 0.40f
                v = 0.60f + 0.40f * (1.0f - (1.0f - excess.coerceIn(0.0f, 1.0f)).pow(1.0f + highCompress * 1.5f))
            }

            // DSLR Contrast Sigmoid
            v = ((v - 0.5f) * contrastFactor + 0.5f).coerceIn(0.0f, 1.0f)
            lut[i] = (v * 255.0f).toInt().coerceIn(0, 255)
        }

        // 2. Temperature Multipliers
        val tempNorm = params.temperature / 100.0f
        val redGain = if (tempNorm > 0) 1.0f + tempNorm * 0.35f else 1.0f
        val blueGain = if (tempNorm < 0) 1.0f - tempNorm * 0.35f else 1.0f

        // 3. Clarity & Tone Pass
        val clarityRadius = 1
        val clarityStrength = (params.clarity / 100.0f) * 0.35f
        val satFactor = 1.0f + (params.saturation / 100.0f)
        val sharpStrength = (params.sharpness / 100.0f) * 0.60f

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val p = inPixels[idx]
                val a = (p ushr 24) and 0xFF
                var r = (p ushr 16) and 0xFF
                var g = (p ushr 8) and 0xFF
                var b = p and 0xFF

                // Apply tone curve
                r = lut[r]
                g = lut[g]
                b = lut[b]

                // Temperature
                r = (r * redGain).toInt().coerceIn(0, 255)
                b = (b * blueGain).toInt().coerceIn(0, 255)

                // Saturation
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val lum = (0.299f * r + 0.587f * g + 0.114f * b)
                if (maxC > 0) {
                    r = (lum + (r - lum) * satFactor).toInt().coerceIn(0, 255)
                    g = (lum + (g - lum) * satFactor).toInt().coerceIn(0, 255)
                    b = (lum + (b - lum) * satFactor).toInt().coerceIn(0, 255)
                }

                // Clarity / Unsharp Sharpening
                if (sharpStrength > 0.05f && x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                    val pLeft = inPixels[idx - 1]
                    val pRight = inPixels[idx + 1]
                    val pUp = inPixels[idx - width]
                    val pDown = inPixels[idx + width]

                    val avgR = ((pLeft ushr 16 and 0xFF) + (pRight ushr 16 and 0xFF) +
                            (pUp ushr 16 and 0xFF) + (pDown ushr 16 and 0xFF)) shr 2
                    val avgG = ((pLeft ushr 8 and 0xFF) + (pRight ushr 8 and 0xFF) +
                            (pUp ushr 8 and 0xFF) + (pDown ushr 8 and 0xFF)) shr 2
                    val avgB = ((pLeft and 0xFF) + (pRight and 0xFF) +
                            (pUp and 0xFF) + (pDown and 0xFF)) shr 2

                    val diffR = r - avgR
                    val diffG = g - avgG
                    val diffB = b - avgB

                    if (abs(diffR) in 4..50) r = (r + diffR * sharpStrength).toInt().coerceIn(0, 255)
                    if (abs(diffG) in 4..50) g = (g + diffG * sharpStrength).toInt().coerceIn(0, 255)
                    if (abs(diffB) in 4..50) b = (b + diffB * sharpStrength).toInt().coerceIn(0, 255)
                }

                outPixels[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }
}