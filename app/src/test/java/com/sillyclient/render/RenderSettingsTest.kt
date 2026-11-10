package com.sillyclient.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class RenderSettingsTest {
    @Test
    fun defaultSettingsPreserveAllVisuals() {
        val settings = RenderSettings.create()
        assertTrue(settings.colorEnabled)
        assertEquals("realtime", settings.mode)
        assertTrue(settings.clickEffect)
        assertTrue(settings.performancePreset)
    }

    @Test
    fun closingColorExtractionForcesClickEffectOff() {
        val settings = RenderSettings.create(false, "realtime", true)
        assertFalse(settings.colorEnabled)
        assertFalse(settings.clickEffect)
        assertTrue(settings.performancePreset)
    }

    @Test
    fun onceModeIsSanitizedCorrectly() {
        val once = RenderSettings.create(true, "once", true)
        assertEquals("once", once.mode)
        assertTrue(once.clickEffect)

        val invalid = RenderSettings.create(true, "unknown_mode", true)
        assertEquals("realtime", invalid.mode)
    }

    @Test
    fun lowOverheadModeDisablesEverythingCleanly() {
        val lowOverhead = RenderSettings.create(false, "once", false)
        assertFalse(lowOverhead.colorEnabled)
        assertEquals("once", lowOverhead.mode)
        assertFalse(lowOverhead.clickEffect)
    }

    @Test
    fun presetCanBeDisabledWithoutChangingChameleon() {
        val previous = RenderSettings.create(true, "once", true)
        val next = previous.updated(performancePreset = false)
        assertEquals(previous.colorEnabled, next.colorEnabled)
        assertEquals(previous.mode, next.mode)
        assertEquals(previous.clickEffect, next.clickEffect)
        assertFalse(next.performancePreset)
    }

    @Test
    fun legacyThreeFieldUpdatePreservesDisabledPreset() {
        val previous = RenderSettings.create(performancePreset = false)
        val next = previous.updated(false, "once", true)
        assertFalse(next.performancePreset)
        assertFalse(next.colorEnabled)
        assertFalse(next.clickEffect)
        assertEquals("once", next.mode)
    }

    @Test
    fun omittedUpdatePreservesEverySavedSetting() {
        val previous = RenderSettings.create(false, "once", false, false)
        assertEquals(previous, previous.updated())
        assertEquals(previous, previous.updated(performancePreset = false))
    }

    @Test
    fun enablingPresetDoesNotEnableColorOrClickEffects() {
        val next = RenderSettings.create(false, "once", false, false)
            .updated(performancePreset = true)
        assertTrue(next.performancePreset)
        assertFalse(next.colorEnabled)
        assertFalse(next.clickEffect)
        assertEquals("once", next.mode)
    }

    @Test
    fun initializationCarriesAllSavedSettingsBeforeTheEngineRuns() {
        val settings = RenderSettings.create(true, "once", false, false)
        val script = settings.initializationScript()
        assertTrue(script.startsWith("window.__scInitialRenderSettings = "))
        val value = JSONObject(script.substringAfter(" = ").removeSuffix(";"))
        assertTrue(value.getBoolean("colorEnabled"))
        assertEquals("once", value.getString("mode"))
        assertFalse(value.getBoolean("clickEffect"))
        assertFalse(value.getBoolean("performancePreset"))
    }

    @Test
    fun liveUpdateUsesSanitizedArgumentsAndExplicitFalse() {
        val settings = RenderSettings.create(mode = "';throw new Error();", performancePreset = false)
        assertEquals(
            "if (window.__scUpdateRenderSettings) {" +
                "window.__scUpdateRenderSettings(true, 'realtime', true, false); }",
            settings.updateScript()
        )
    }
}
