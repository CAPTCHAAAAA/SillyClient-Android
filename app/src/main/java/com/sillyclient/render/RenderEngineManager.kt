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
    private val onColorChangedListener: (Int, Long) -> Unit
) {
    private var cachedScript: String? = null
    private var settings = RenderSettings.create()
    @Volatile private var documentToken = 0L

    fun setDocumentToken(token: Long) {
        documentToken = token
    }

    fun isCurrentDocument(token: Long): Boolean = documentToken == token

    fun setSettings(settings: RenderSettings) {
        this.settings = settings
    }

    fun applySettingsToWebView(webView: WebView) {
        webView.post {
            try {
                webView.evaluateJavascript(settings.updateScript(), null)
            } catch (e: Throwable) {
                android.util.Log.w("SC_RenderEngine", "Failed to apply render settings to webview", e)
            }
        }
    }

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
        val token = documentToken
        try {
            val script = cachedScript ?: context.assets.open("scripts/sc-render-engine.js")
                .bufferedReader()
                .use { it.readText() }
                .also { cachedScript = it }

            webView.post {
                try {
                    if (!isCurrentDocument(token)) return@post
                    // Seed before the first script instruction; a saved disabled
                    // preset must never install and then remove its resources.
                    webView.evaluateJavascript(
                        "window.__scRenderDocumentToken = $token;\n" +
                            settings.initializationScript() + "\n" + script + "\n" + settings.updateScript(),
                        null
                    )
                } catch (e: Throwable) {
                    android.util.Log.w("SC_RenderEngine", "Failed to evaluate script in injectEngine", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SC_RenderEngine", "Failed to inject sc-render-engine.js", e)
        }
    }

    /** 切换 Performance Monitor HUD 并弹出 Toast */
    fun togglePerformanceMonitor(webView: WebView) {
        injectEngine(webView)
        webView.post {
            try {
                webView.evaluateJavascript("window.__scTogglePerfHud ? window.__scTogglePerfHud() : false;") { res ->
                    val active = res == "true"
                    val msg = if (active) "⚡ SC Performance Engine 监控已开启" else "性能监控已关闭"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                android.util.Log.w("SC_RenderEngine", "Failed to toggle perf HUD", e)
            }
        }
    }

    /** 强制前端变色龙引擎重新采样并上报 (退出重进或切回前台时唤醒) */
    fun forceChameleonSample(webView: WebView) {
        val token = documentToken
        webView.post {
            try {
                if (!isCurrentDocument(token)) return@post
                if (!settings.colorEnabled) return@post
                webView.evaluateJavascript(
                    "(function(){ try { if (window.__scChameleonEngine) { window.__scChameleonEngine.lastReportedColor = null; window.__scChameleonEngine.sampleAndReport(true); } } catch(_) {} })();",
                    null
                )
            } catch (e: Throwable) {
                android.util.Log.w("SC_RenderEngine", "Failed to force chameleon sample", e)
            }
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
        fun onColorChanged(color: Int, token: Long) {
            if (!isCurrentDocument(token)) return
            onColorChangedListener(color, token)
        }
    }
}
