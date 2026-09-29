package com.sillyclient.render

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast

/**
 * SillyClient 渲染调度引擎宿主管理器
 * 统一管理 JS 调度脚本注入、Native 触感桥接与变色龙事件感知桥接
 */
class RenderEngineManager(
    private val context: Context,
    private val hapticController: HapticController,
    private val onColorChangedListener: (Int) -> Unit
) {
    private var cachedScript: String? = null

    /** 注册暴露给前端 JS 的 Native 桥接接口 */
    fun attachBridges(webView: WebView) {
        webView.addJavascriptInterface(HapticBridge(), "SillyClientHaptic")
        webView.addJavascriptInterface(RenderBridge(), "SillyClientRenderBridge")
    }

    /** 注销 Native 桥接接口 */
    fun detachBridges(webView: WebView) {
        webView.removeJavascriptInterface("SillyClientHaptic")
        webView.removeJavascriptInterface("SillyClientRenderBridge")
    }

    /** 读取并注入 sc-render-engine.js 调度引擎 */
    fun injectEngine(webView: WebView) {
        try {
            val script = cachedScript ?: context.assets.open("scripts/sc-render-engine.js")
                .bufferedReader()
                .use { it.readText() }
                .also { cachedScript = it }

            webView.evaluateJavascript(script, null)
        } catch (e: Exception) {
            android.util.Log.w("SC_RenderEngine", "Failed to inject sc-render-engine.js", e)
        }
    }

    /** 切换 Performance Monitor HUD 并弹出 Toast */
    fun togglePerformanceMonitor(webView: WebView) {
        injectEngine(webView)
        webView.evaluateJavascript("window.__scTogglePerfHud ? window.__scTogglePerfHud() : false;") { res ->
            val active = res == "true"
            val msg = if (active) "⚡ SC Performance Engine 监控已开启" else "性能监控已关闭"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    inner class HapticBridge {
        @JavascriptInterface
        fun trigger(type: String?) {
            hapticController.trigger(type ?: "tick")
        }
    }

    inner class RenderBridge {
        @JavascriptInterface
        fun onColorChanged(color: Int) {
            onColorChangedListener(color)
        }
    }
}
