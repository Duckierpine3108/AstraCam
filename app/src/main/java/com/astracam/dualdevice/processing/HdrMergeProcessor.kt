package com.astracam.dualdevice.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

class HdrMergeProcessor {

    /**
     * GCam / AGC-inspired Multi-Scale Optical Alignment & Motion Deghosting.
     * Prevents hand-shake ghosting and falls back to synthetic HDR if shake is extreme.
     */
    fun mergeExposureBracket(bitmaps: List<Bitmap>): Bitmap {
        require(bitmaps.isNotEmpty()) { "Cannot merge empty bitmap list" }
        if (bitmaps.size == 1) return bitmaps[0]

        val ref = bitmaps[0]
        val width = ref.width
        val height = ref.height

        val frameCount = bitmaps.size
        val allPixels = Array(frameCount) { IntArray(width * height) }
        for (i in 0 until frameCount) {
            bitmaps[i].getPixels(allPixels[i], 0, width, 0, 0, width, height)
        }

        val shiftsX = IntArray(frameCount)
        val shiftsY = IntArray(frameCount)
        var maxObservedShake = 0

        for (f in 1 until frameCount) {
            val (dx, dy, error) = estimateHierarchicalShift(allPixels[0], allPixels[f], width, height)
            shiftsX[f] = dx
            shiftsY[f] = dy
            if (error > maxObservedShake) maxObservedShake = error
        }

        // Automatic fallback to single-frame synthetic HDR if shake is too severe
        if (maxObservedShake > 45) {
            return generateSyntheticSingleFrameHdr(ref)
        }

        val outPixels = IntArray(width * height)
        val weights = FloatArray(frameCount)

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

                    val sigma = 0.22f
                    val twoSigSq = 2.0f * sigma * sigma
                    val wExp = exp(-((r - 0.5f) * (r - 0.5f) + (g - 0.5f) * (g - 0.5f) + (b - 0.5f) * (b - 0.5f)) / twoSigSq)

                    val pLum = (0.299f * r + 0.587f * g + 0.114f * b) * 255.0f
                    val lumDiff = abs(pLum - refLum)

                    val wMotion = if (f == 0) 1.0f else {
                        exp(-(lumDiff * lumDiff) / (2.0f * 18.0f * 18.0f))
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

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun estimateHierarchicalShift(
        refPixels: IntArray,
        targetPixels: IntArray,
        w: Int,
        h: Int
    ): Triple<Int, Int, Int> {
        val coarseStep = 16
        val coarseMaxShift = 48
        var bestX = 0
        var bestY = 0
        var minSad = Long.MAX_VALUE

        val startY = h / 4
        val endY = (3 * h) / 4
        val startX = w / 4
        val endX = (3 * w) / 4

        // Pass 1: Coarse search
        var dy = -coarseMaxShift
        while (dy <= coarseMaxShift) {
            var dx = -coarseMaxShift
            while (dx <= coarseMaxShift) {
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
                        x += coarseStep
                    }
                    y += coarseStep
                }
                if (currentSad < minSad) {
                    minSad = currentSad
                    bestX = dx
                    bestY = dy
                }
                dx += 8
            }
            dy += 8
        }

        // Pass 2: Fine refinement
        val fineRadius = 4
        for (fdy in (bestY - fineRadius)..(bestY + fineRadius) step 2) {
            for (fdx in (bestX - fineRadius)..(bestX + fineRadius) step 2) {
                var currentSad = 0L
                var y = startY
                while (y < endY) {
                    var x = startX
                    while (x < endX) {
                        val refP = refPixels[y * w + x]
                        val tgtP = targetPixels[(y + fdy).coerceIn(0, h - 1) * w + (x + fdx).coerceIn(0, w - 1)]
                        val refLum = (refP ushr 8) and 0xFF
                        val tgtLum = (tgtP ushr 8) and 0xFF
                        currentSad += abs(refLum - tgtLum)
                        x += coarseStep
                    }
                    y += coarseStep
                }
                if (currentSad < minSad) {
                    minSad = currentSad
                    bestX = fdx
                    bestY = fdy
                }
            }
        }

        val sampleCount = max(1, ((endY - startY) * (endX - startX)) / (coarseStep * coarseStep))
        val normalizedError = (minSad / sampleCount).toInt()
        return Triple(bestX, bestY, normalizedError)
    }

    private fun generateSyntheticSingleFrameHdr(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            var r = (p ushr 16) and 0xFF
            var g = (p ushr 8) and 0xFF
            var b = p and 0xFF

            val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            val boostedLum = lum + 0.35f * (1.0f - lum).pow(2.0f) * lum
            val scale = if (lum > 1e-3f) (boostedLum / lum).coerceIn(0.6f, 1.8f) else 1.0f

            r = (r * scale).toInt().coerceIn(0, 255)
            g = (g * scale).toInt().coerceIn(0, 255)
            b = (b * scale).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}