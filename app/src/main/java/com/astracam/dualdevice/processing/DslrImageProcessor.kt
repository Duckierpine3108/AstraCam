package com.astracam.dualdevice.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.pow

data class DslrTuningParameters(
    var exposure: Float = 0.0f,       // -50.0 to +50.0
    var shadows: Float = 35.0f,       // 0.0 to 100.0 (lift shadow tones)
    var highlights: Float = 25.0f,    // 0.0 to 100.0 (compress highlights)
    var contrast: Float = 10.0f,      // -50.0 to +50.0
    var clarity: Float = 25.0f,       // 0.0 to 100.0 (micro-contrast)
    var saturation: Float = 15.0f,    // -50.0 to +50.0
    var temperature: Float = 0.0f,    // -50.0 to +50.0 (cool to warm)
    var sharpness: Float = 35.0f      // 0.0 to 100.0
)

class DslrImageProcessor {

    fun processBitmap(source: Bitmap, params: DslrTuningParameters): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val inPixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        source.getPixels(inPixels, 0, width, 0, 0, width, height)

        // 1. Build Smooth Continuous Luminance S-Curve LUT
        val lut = FloatArray(256)
        val expMultiplier = (1.0f + params.exposure / 60.0f).coerceIn(0.4f, 2.2f)
        val shadowLift = (params.shadows / 100.0f).coerceIn(0.0f, 1.0f) * 0.45f
        val highCompress = (params.highlights / 100.0f).coerceIn(0.0f, 1.0f) * 0.40f
        val contrastFactor = (1.0f + params.contrast / 100.0f).coerceIn(0.5f, 1.6f)

        for (i in 0..255) {
            var v = (i / 255.0f) * expMultiplier

            // Quadratic smooth shadow lift
            v += shadowLift * (1.0f - v.coerceIn(0f, 1f)).pow(2.0f) * v

            // Smooth highlight compression
            if (v > 0.55f && highCompress > 0f) {
                val excess = (v - 0.55f) / 0.45f
                v = 0.55f + 0.45f * (1.0f - (1.0f - excess.coerceIn(0f, 1f)).pow(1.0f + highCompress * 2.0f))
            }

            // Smooth sigmoid contrast centered at midpoint
            v = ((v - 0.5f) * contrastFactor + 0.5f).coerceIn(0.0f, 1.0f)
            lut[i] = v * 255.0f
        }

        // Temperature adjustments
        val tempNorm = params.temperature / 100.0f
        val redGain = if (tempNorm > 0) 1.0f + tempNorm * 0.25f else 1.0f
        val blueGain = if (tempNorm < 0) 1.0f - tempNorm * 0.25f else 1.0f

        val satFactor = 1.0f + (params.saturation / 100.0f)
        val sharpStrength = (params.sharpness / 100.0f) * 0.45f
        val clarityStrength = (params.clarity / 100.0f) * 0.35f

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val p = inPixels[idx]
                val a = (p ushr 24) and 0xFF
                var r = (p ushr 16) and 0xFF
                var g = (p ushr 8) and 0xFF
                var b = p and 0xFF

                // Compute exact perceived luminance
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val targetLum = lut[lum.toInt().coerceIn(0, 255)]

                // Proportional Luminance Scaling (preserves true chrominance, zero tearing)
                val lumScale = if (lum > 1e-3f) targetLum / lum else 1.0f
                var fR = (r * lumScale * redGain).coerceIn(0f, 255f)
                var fG = (g * lumScale).coerceIn(0f, 255f)
                var fB = (b * lumScale * blueGain).coerceIn(0f, 255f)

                // Vibrance/Saturation
                val newLum = 0.299f * fR + 0.587f * fG + 0.114f * fB
                fR = (newLum + (fR - newLum) * satFactor).coerceIn(0f, 255f)
                fG = (newLum + (fG - newLum) * satFactor).coerceIn(0f, 255f)
                fB = (newLum + (fB - newLum) * satFactor).coerceIn(0f, 255f)

                // Soft-Cored Luminance Sharpening (zero halo, zero color artifacting)
                if ((sharpStrength > 0.02f || clarityStrength > 0.02f) &&
                    x > 0 && x < width - 1 && y > 0 && y < height - 1
                ) {
                    val lumLeft = getLuminance(inPixels[idx - 1])
                    val lumRight = getLuminance(inPixels[idx + 1])
                    val lumUp = getLuminance(inPixels[idx - width])
                    val lumDown = getLuminance(inPixels[idx + width])
                    val avgNeighborLum = (lumLeft + lumRight + lumUp + lumDown) * 0.25f

                    val lumDiff = lum - avgNeighborLum
                    val absDiff = abs(lumDiff)

                    // Continuous soft coring: noise (<3) is ignored, textures are boosted smoothly
                    if (absDiff > 3.0f) {
                        val smoothWeight = (absDiff - 3.0f) / (absDiff + 12.0f)
                        val totalBoost = (lumDiff * (sharpStrength + clarityStrength) * smoothWeight).coerceIn(-30f, 30f)

                        fR = (fR + totalBoost).coerceIn(0f, 255f)
                        fG = (fG + totalBoost).coerceIn(0f, 255f)
                        fB = (fB + totalBoost).coerceIn(0f, 255f)
                    }
                }

                outPixels[idx] = (a shl 24) or (fR.toInt() shl 16) or (fG.toInt() shl 8) or fB.toInt()
            }
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    private inline fun getLuminance(color: Int): Float {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}