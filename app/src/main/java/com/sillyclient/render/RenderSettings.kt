package com.sillyclient.render

import org.json.JSONObject

@ConsistentCopyVisibility
data class RenderSettings private constructor(
    val colorEnabled: Boolean,
    val mode: String,
    val clickEffect: Boolean,
    val performancePreset: Boolean
) {
    fun updated(
        colorEnabled: Boolean? = null,
        mode: String? = null,
        clickEffect: Boolean? = null,
        performancePreset: Boolean? = null
    ): RenderSettings = create(
        colorEnabled ?: this.colorEnabled,
        mode ?: this.mode,
        clickEffect ?: this.clickEffect,
        performancePreset ?: this.performancePreset
    )

    fun initializationScript(): String {
        val settings = JSONObject()
            .put("colorEnabled", colorEnabled)
            .put("mode", mode)
            .put("clickEffect", clickEffect)
            .put("performancePreset", performancePreset)
        return "window.__scInitialRenderSettings = $settings;"
    }

    fun updateScript(): String =
        "if (window.__scUpdateRenderSettings) {" +
            "window.__scUpdateRenderSettings($colorEnabled, '$mode', $clickEffect, $performancePreset); }"

    companion object {
        fun create(
            colorEnabled: Boolean = true,
            mode: String? = "realtime",
            clickEffect: Boolean = true,
            performancePreset: Boolean = true
        ): RenderSettings = RenderSettings(
            colorEnabled,
            if (mode == "once") "once" else "realtime",
            colorEnabled && clickEffect,
            performancePreset
        )
    }
}
