package com.sillyclient.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ViewConfiguration
import android.view.Window
import android.webkit.WebView
import com.sillyclient.ui.TavernStatusHint
import com.sillyclient.ui.TopScrimBar

/**
 * 变色龙顶栏取色与触控协调器
 * 1. JS 只通知样式变化，最终颜色从 WebView 已合成像素读取；
 * 2. PixelCopy 单飞并合并最新请求，迟到结果不能跨页面/实例写入；
 * 3. 内存与 SharedPreferences 瞬时对齐，消除进入酒馆首帧色差；
 * 4. 触控手势防抖：滑动期间静默 0 采样，轻触抬手触发光波与平滑取色。
 */
class ChameleonController(
    private val context: Context,
    private val window: Window,
    private val handler: Handler,
    private val topScrimBar: TopScrimBar,
    private var statusHint: TavernStatusHint? = null,
    private val getFixedStatusBarPx: () -> Int,
    private val isWebViewVisible: () -> Boolean,
    private val isSampleDocumentCommitted: () -> Boolean,
    private val isPullToRefreshEnabled: () -> Boolean
) {
    private var lastAppliedColor: Int? = null
    private var currentInstanceId: String? = null
    private val oneShotSampleGate = ChameleonSampleGate()
    private var sampleGeneration = 0L
    private var nextPixelCopyId = 0L
    private var pixelCopyInFlight: PixelCopyFlight? = null
    private var pixelCopyPending: PixelCopyRequest? = null

    private data class SampleContext(
        val generation: Long,
        val instanceId: String?,
        val webView: WebView
    )

    private data class PixelCopyRequest(
        val context: SampleContext,
        val fallbackColor: Int?,
        val onResult: (Int?) -> Unit
    )

    private data class PixelCopyFlight(
        val id: Long,
        val request: PixelCopyRequest,
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val pixels: IntArray
    )

    // 沉浸式与低占用渲染配置
    var isColorExtractionEnabled: Boolean = true
    var colorExtractionMode: String = "realtime" // "realtime" | "once"
    var isClickEffectEnabled: Boolean = true

    private val touchGesture = ChameleonTouchGesture(
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    )
    private var touchGeneration = 0L

    fun setStatusHint(hint: TavernStatusHint) {
        statusHint = hint
    }

    fun setInstanceId(instanceId: String?) {
        if (currentInstanceId == instanceId) return
        currentInstanceId = instanceId
        invalidateSamples()
    }

    fun updateSettings(colorEnabled: Boolean, mode: String, clickEffect: Boolean) {
        val normalizedMode = if (mode == "once") "once" else "realtime"
        val samplingChanged = isColorExtractionEnabled != colorEnabled ||
            colorExtractionMode != normalizedMode
        isColorExtractionEnabled = colorEnabled
        colorExtractionMode = normalizedMode
        isClickEffectEnabled = clickEffect
        if (samplingChanged) invalidateSamples()
        if (!colorEnabled) {
            topScrimBar.setColorInstant(0xFF1E1E1E.toInt())
        }
    }

    fun reset() {
        invalidateSamples()
        lastAppliedColor = null
    }

    private fun invalidateSamples() {
        sampleGeneration++
        pixelCopyPending?.let { runCatching { it.onResult(null) } }
        pixelCopyPending = null
        touchGesture.cancel()
        touchGeneration++
        oneShotSampleGate.reset()
    }

    private fun isCurrent(context: SampleContext): Boolean =
        context.generation == sampleGeneration &&
            context.instanceId == currentInstanceId &&
            isColorExtractionEnabled &&
            oneShotSampleGate.canSample(colorExtractionMode) &&
            isSampleDocumentCommitted() &&
            isWebViewVisible() &&
            context.webView.isShown

    fun getSavedColor(instanceId: String?): Int? {
        val id = instanceId?.takeIf { it.isNotBlank() } ?: currentInstanceId ?: "default"
        val sp = context.getSharedPreferences("sc_instance_colors", Context.MODE_PRIVATE)
        val color = sp.getInt("top_color_$id", 0)
        return if (color != 0) color else null
    }

    fun saveColor(instanceId: String?, color: Int) {
        val id = instanceId?.takeIf { it.isNotBlank() } ?: currentInstanceId ?: "default"
        val sp = context.getSharedPreferences("sc_instance_colors", Context.MODE_PRIVATE)
        sp.edit().putInt("top_color_$id", color).apply()
    }

    fun applyColor(color: Int, instant: Boolean = false) {
        if (color == 0) return
        saveColor(currentInstanceId, color)
        if (!instant && lastAppliedColor == color) {
            statusHint?.onColorChanged(color)
            return
        }
        lastAppliedColor = color
        if (instant) {
            topScrimBar.setColorInstant(color)
        } else {
            topScrimBar.setColor(color)
        }
        statusHint?.onColorChanged(color)
    }

    fun sampleTopColor(webView: WebView, onResult: (Int?) -> Unit) {
        sampleTopColor(webView, fallbackColor = null, onResult = onResult)
    }

    fun sampleTopColor(webView: WebView, fallbackColor: Int?, onResult: (Int?) -> Unit) {
        if (!isColorExtractionEnabled) {
            onResult(null)
            return
        }
        if (!oneShotSampleGate.canSample(colorExtractionMode)) {
            onResult(null)
            return
        }
        if (!isSampleDocumentCommitted()) {
            onResult(null)
            return
        }
        if (!isWebViewVisible() || !webView.isShown) {
            onResult(null)
            return
        }
        enqueuePixelCopy(
            PixelCopyRequest(
                context = SampleContext(sampleGeneration, currentInstanceId, webView),
                fallbackColor = fallbackColor?.takeIf { it != 0 },
                onResult = onResult
            )
        )
    }

    private fun enqueuePixelCopy(request: PixelCopyRequest) {
        if (!isCurrent(request.context)) {
            request.onResult(null)
            return
        }
        if (pixelCopyInFlight != null) {
            pixelCopyPending?.let { runCatching { it.onResult(null) } }
            pixelCopyPending = request
            return
        }
        startPixelCopy(request)
    }

    private fun startPixelCopy(request: PixelCopyRequest) {
        if (!isCurrent(request.context)) {
            request.onResult(null)
            return
        }
        val webView = request.context.webView
        val w = webView.width
        val webViewHeight = webView.height
        if (w <= 0 || webViewHeight <= 1) {
            deliverSampleResult(request, request.fallbackColor)
            return
        }
        val loc = IntArray(2)
        webView.getLocationInWindow(loc)
        val top = loc[1] + 1
        val stripHeight = 3.coerceAtMost(webViewHeight - 1)
        val srcRect = Rect(loc[0], top, loc[0] + w, top + stripHeight)
        val bitmap = Bitmap.createBitmap(w, stripHeight, Bitmap.Config.ARGB_8888)
        val flight = PixelCopyFlight(
            id = ++nextPixelCopyId,
            request = request,
            bitmap = bitmap,
            width = w,
            height = stripHeight,
            pixels = IntArray(w * stripHeight)
        )
        pixelCopyInFlight = flight
        try {
            PixelCopy.request(window, srcRect, bitmap, { result -> finishPixelCopy(flight, result) }, handler)
        } catch (_: Exception) {
            finishPixelCopy(flight, PixelCopy.ERROR_UNKNOWN)
        }
    }

    private fun finishPixelCopy(flight: PixelCopyFlight, result: Int) {
        var sampledColor: Int? = null
        try {
            if (result == PixelCopy.SUCCESS && isCurrent(flight.request.context)) {
                flight.bitmap.getPixels(
                    flight.pixels, 0, flight.width, 0, 0, flight.width, flight.height
                )
                sampledColor = ChameleonPixelSampler.representativeColor(flight.pixels)
            }
        } catch (_: Exception) {
            sampledColor = null
        } finally {
            if (!flight.bitmap.isRecycled) flight.bitmap.recycle()
            if (pixelCopyInFlight?.id == flight.id) pixelCopyInFlight = null
        }

        val request = flight.request
        deliverSampleResult(
            request,
            sampledColor ?: request.fallbackColor,
            source = if (sampledColor != null) {
                ChameleonSampleGate.Source.PIXEL_COPY
            } else {
                ChameleonSampleGate.Source.CSS_FALLBACK
            }
        )

        val pending = pixelCopyPending
        pixelCopyPending = null
        if (pending != null) enqueuePixelCopy(pending)
    }

    private fun deliverSampleResult(
        request: PixelCopyRequest,
        color: Int?,
        source: ChameleonSampleGate.Source = ChameleonSampleGate.Source.CSS_FALLBACK
    ) {
        val acceptedColor = color?.takeIf { it != 0 && isCurrent(request.context) }
        if (acceptedColor != null) {
            oneShotSampleGate.record(colorExtractionMode, acceptedColor, source)
        }
        runCatching { request.onResult(acceptedColor) }
    }

    private fun scheduleTouchSample(webView: WebView) {
        val generation = sampleGeneration
        val gestureGeneration = touchGeneration
        handler.postDelayed({
            if (generation == sampleGeneration &&
                gestureGeneration == touchGeneration &&
                isSampleDocumentCommitted() &&
                isWebViewVisible() &&
                !touchGesture.isActive &&
                oneShotSampleGate.canSample(colorExtractionMode)
            ) {
                sampleTopColor(webView) { color -> if (color != null) applyColor(color) }
            }
        }, 500)
    }

    fun setupTouchListener(webView: WebView, onReloadRequest: () -> Unit) {
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchGeneration++
                    touchGesture.begin(
                        event.rawX,
                        event.rawY,
                        isPullToRefreshEnabled() && webView.scrollY <= 0
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    touchGesture.move(
                        event.rawX,
                        event.rawY,
                        isPullToRefreshEnabled() && webView.scrollY <= 0
                    )
                }
                MotionEvent.ACTION_UP -> {
                    val density = context.resources.displayMetrics.density
                    when (touchGesture.finish(
                        event.rawX,
                        event.rawY,
                        isPullToRefreshEnabled() && webView.scrollY <= 0,
                        getFixedStatusBarPx() + density * 80f,
                        density * 100f
                    )) {
                        ChameleonTouchGesture.Result.RELOAD -> {
                            onReloadRequest()
                            if (isColorExtractionEnabled && isClickEffectEnabled) {
                                topScrimBar.sweepGloss()
                            }
                        }
                        ChameleonTouchGesture.Result.TAP -> {
                            if (isColorExtractionEnabled && isClickEffectEnabled) {
                                topScrimBar.sweepGloss()
                            }
                            if (isColorExtractionEnabled &&
                                oneShotSampleGate.canSample(colorExtractionMode)
                            ) scheduleTouchSample(webView)
                        }
                        ChameleonTouchGesture.Result.NONE -> Unit
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    touchGesture.cancel()
                    touchGeneration++
                }
            }
            false
        }
    }
}
