package com.sillyclient.render

internal class ChameleonSampleGate {
    enum class Source { PIXEL_COPY, CSS_FALLBACK }

    private var sampledOnce = false

    fun canSample(mode: String): Boolean = mode != "once" || !sampledOnce

    fun record(mode: String, color: Int?, source: Source) {
        if (source == Source.PIXEL_COPY && mode == "once" && color != null && color != 0) {
            sampledOnce = true
        }
    }

    fun reset() {
        sampledOnce = false
    }
}
