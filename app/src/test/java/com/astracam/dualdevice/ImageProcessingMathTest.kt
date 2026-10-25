package com.astracam.dualdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class ImageProcessingMathTest {

    @Test
    fun testGaussianWeightFormula() {
        val sigma = 0.2f
        val twoSigSq = 2.0f * sigma * sigma
        val center = 0.5f

        // Center value must yield maximum weight of 1.0
        val weightCenter = exp(-((center - 0.5f) * (center - 0.5f)) / twoSigSq)
        assertEquals(1.0f, weightCenter, 0.001f)

        // Extreme values (0.0 or 1.0) must yield significantly smaller weights
        val weightEdge = exp(-((0.0f - 0.5f) * (0.0f - 0.5f)) / twoSigSq)
        assertTrue("Extreme tones must receive lower merge weights", weightEdge < 0.05f)
    }

    @Test
    fun testColorClamping() {
        val rawOverexposed = 295
        val clamped = rawOverexposed.coerceIn(0, 255)
        assertEquals(255, clamped)

        val rawUnderexposed = -18
        val clampedUnder = rawUnderexposed.coerceIn(0, 255)
        assertEquals(0, clampedUnder)
    }
}