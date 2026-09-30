package com.sillyclient.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
 * 1. 优先调用 JS ChameleonEngine 秒级直读计算色，0 开销 0 GPU 管线中断；
 * 2. 跨域或首帧异常时自动启用 PixelCopy 零抖动位图采样兜底；
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
    private val isPullToRefreshEnabled: () -> Boolean
) {
    private var lastAppliedColor: Int? = null
    private var isSamplingPixelCopy = false
    private var currentInstanceId: String? = null

    // 触控手势状态
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var pullStartY = 0f
    private var pullReadyToReload = false
    private var isTouchScrolling = false

    fun setStatusHint(hint: TavernStatusHint) {
        statusHint = hint
    }

    fun setInstanceId(instanceId: String?) {
        currentInstanceId = instanceId
    }

    fun reset() {
        lastAppliedColor = null
        isSamplingPixelCopy = false
        isTouchScrolling = false
    }

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
        if (!isWebViewVisible() || !webView.isShown) {
            onResult(null)
            return
        }
        // 引擎 A: 直调前端 ChameleonEngine 缓存与计算，0 开销 0 卡顿
        val js = "(function(){ try { return window.__scChameleonEngine ? window.__scChameleonEngine.computeTopColor() : null; } catch(_) { return null; } })()"
        webView.evaluateJavascript(js) { res ->
            val color = res?.trim('"', ' ', '\'')?.toIntOrNull()
            if (color != null && color != 0) {
                onResult(color)
            } else if (webView.isShown && webView.width > 0) {
                // 引擎 B: PixelCopy 兜底
                sampleTopColorPixelCopy(webView, onResult)
            } else {
                onResult(null)
            }
        }
    }

    private fun sampleTopColorPixelCopy(webView: WebView, onResult: (Int?) -> Unit) {
        if (isSamplingPixelCopy) {
            onResult(null)
            return
        }
        val w = webView.width
        if (w <= 0 || !webView.isShown) {
            onResult(null)
            return
        }
        isSamplingPixelCopy = true
        val loc = IntArray(2)
        webView.getLocationInWindow(loc)
        val top = loc[1] + 1
        val stripH = 3
        val srcRect = Rect(loc[0], top, loc[0] + w, top + stripH)
        val bmp = Bitmap.createBitmap(w, stripH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * stripH)
        try {
            PixelCopy.request(window, srcRect, bmp, { result ->
                isSamplingPixelCopy = false
                if (result == PixelCopy.SUCCESS) {
                    var rs = 0; var gs = 0; var bs = 0; var n = 0
                    bmp.getPixels(pixels, 0, w, 0, 0, w, stripH)
                    for (p in pixels) {
                        if (Color.alpha(p) > 200) {
                            rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p); n++
                        }
                    }
                    bmp.recycle()
                    if (n > 0) {
                        val avg = (0xFF shl 24) or ((rs / n and 0xFF) shl 16) or
                                ((gs / n and 0xFF) shl 8) or (bs / n and 0xFF)
                        onResult(avg)
                    } else onResult(null)
                } else {
                    bmp.recycle()
                    onResult(null)
                }
            }, handler)
        } catch (_: Exception) {
            isSamplingPixelCopy = false
            bmp.recycle()
            onResult(null)
        }
    }

    fun setupTouchListener(webView: WebView, onReloadRequest: () -> Unit) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    pullStartY = event.rawY
                    pullReadyToReload = isPullToRefreshEnabled() && webView.scrollY == 0
                    isTouchScrolling = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.rawX - touchDownX)
                    val dy = kotlin.math.abs(event.rawY - touchDownY)
                    if (dx > touchSlop || dy > touchSlop) {
                        isTouchScrolling = true
                        if (dx > dy * 0.8f || event.rawY < pullStartY) {
                            pullReadyToReload = false
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val isTopEdge = pullStartY <= (getFixedStatusBarPx() + 180)
                    if (pullReadyToReload && isTopEdge && (event.rawY - pullStartY) > 240) {
                        onReloadRequest()
                        topScrimBar.sweepGloss()
                    } else if (!isTouchScrolling) {
                        topScrimBar.sweepGloss()
                        handler.postDelayed({
                            if (isWebViewVisible() && !isTouchScrolling) {
                                sampleTopColor(webView) { c -> if (c != null) applyColor(c) }
                            }
                        }, 500)
                    }
                    pullReadyToReload = false
                    isTouchScrolling = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    pullReadyToReload = false
                    isTouchScrolling = false
                }
            }
            false
        }
    }
}
