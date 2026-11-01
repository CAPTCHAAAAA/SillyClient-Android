package com.sillyclient.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderSettingsTest {

    data class TestRenderSettings(
        val immersiveColor: Boolean,
        val colorExtractionMode: String,
        val clickEffect: Boolean
    ) {
        companion object {
            fun sanitize(immersiveColor: Boolean, mode: String?, clickEffect: Boolean): TestRenderSettings {
                val safeMode = if (mode == "once") "once" else "realtime"
                val safeClick = if (immersiveColor) clickEffect else false
                return TestRenderSettings(immersiveColor, safeMode, safeClick)
            }
        }
    }

    @Test
    fun defaultSettingsPreserveAllVisuals() {
        val settings = TestRenderSettings.sanitize(true, "realtime", true)
        assertTrue(settings.immersiveColor)
        assertEquals("realtime", settings.colorExtractionMode)
        assertTrue(settings.clickEffect)
    }

    @Test
    fun closingColorExtractionForcesClickEffectOff() {
        val settings = TestRenderSettings.sanitize(false, "realtime", true)
        assertFalse(settings.immersiveColor)
        assertFalse(settings.clickEffect)
    }

    @Test
    fun onceModeIsSanitizedCorrectly() {
        val once = TestRenderSettings.sanitize(true, "once", true)
        assertEquals("once", once.colorExtractionMode)
        assertTrue(once.clickEffect)

        val invalid = TestRenderSettings.sanitize(true, "unknown_mode", true)
        assertEquals("realtime", invalid.colorExtractionMode)
    }

    @Test
    fun lowOverheadModeDisablesEverythingCleanly() {
        val lowOverhead = TestRenderSettings.sanitize(false, "once", false)
        assertFalse(lowOverhead.immersiveColor)
        assertEquals("once", lowOverhead.colorExtractionMode)
        assertFalse(lowOverhead.clickEffect)
    }
}
