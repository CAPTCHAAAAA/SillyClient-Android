package com.sillyclient.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChameleonSampleGateTest {
    @Test
    fun cachedColorDoesNotConsumeTheOneShotSample() {
        val gate = ChameleonSampleGate()

        gate.record("once", null, ChameleonSampleGate.Source.CSS_FALLBACK)

        assertTrue(gate.canSample("once"))
    }

    @Test
    fun cssFallbackColorDoesNotConsumeTheOneShotSample() {
        val gate = ChameleonSampleGate()

        gate.record("once", 0xFF112233.toInt(), ChameleonSampleGate.Source.CSS_FALLBACK)

        assertTrue(gate.canSample("once"))
    }

    @Test
    fun onlyAnAcceptedOneShotColorConsumesTheSample() {
        val gate = ChameleonSampleGate()

        gate.record("realtime", 0xFF112233.toInt(), ChameleonSampleGate.Source.PIXEL_COPY)
        assertTrue(gate.canSample("once"))

        gate.record("once", 0, ChameleonSampleGate.Source.PIXEL_COPY)
        assertTrue(gate.canSample("once"))

        gate.record("once", 0xFF112233.toInt(), ChameleonSampleGate.Source.PIXEL_COPY)
        assertFalse(gate.canSample("once"))
        assertTrue(gate.canSample("realtime"))
    }

    @Test
    fun invalidationAllowsOneShotSamplingAgain() {
        val gate = ChameleonSampleGate()
        gate.record("once", 0xFF112233.toInt(), ChameleonSampleGate.Source.PIXEL_COPY)

        gate.reset()

        assertTrue(gate.canSample("once"))
    }
}
