package com.sillyclient.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChameleonPixelSamplerTest {
    @Test
    fun sparseBrightIconsDoNotPullTheRepresentativeColorAwayFromTheBar() {
        val barColor = 0xFF68142F.toInt()
        val pixels = IntArray(1000) { barColor }
        for (index in 0 until 80) pixels[index] = 0xFFFFFFFF.toInt()

        assertEquals(barColor, ChameleonPixelSampler.representativeColor(pixels))
    }

    @Test
    fun horizontalGradientSelectsTheMiddleObservedColor() {
        val pixels = IntArray(101) { index ->
            (0xFF shl 24) or (index shl 16) or (100 shl 8) or 50
        }

        assertEquals(0xFF326432.toInt(), ChameleonPixelSampler.representativeColor(pixels))
    }

    @Test
    fun transparentPixelsAreExcluded() {
        assertEquals(
            0xFF456789.toInt(),
            ChameleonPixelSampler.representativeColor(
                intArrayOf(0x00112233, 0x00112233, 0xFF456789.toInt())
            )
        )
    }

    @Test
    fun fullyTransparentPixelsHaveNoRepresentativeColor() {
        assertNull(
            ChameleonPixelSampler.representativeColor(
                intArrayOf(0x00112233, 0x00112233)
            )
        )
    }
}
