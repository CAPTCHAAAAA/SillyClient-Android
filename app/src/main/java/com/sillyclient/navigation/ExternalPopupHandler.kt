package com.sillyclient.navigation

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class ExternalPopupHandler(
    private val context: Context,
    private val handler: Handler,
    private val openExternal: (String) -> Unit,
    private val onDownload: (String, String?, String?, Long) -> Unit,
    private val container: () -> ViewGroup
) {
    private val pending = mutableMapOf<WebView, Runnable>()

    fun createWindow(resultMsg: Message, isCurrentSession: () -> Boolean): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        if (pending.size >= 4 || !isCurrentSession()) return false
        // This short-lived view resolves window.open URLs without exposing native bridges.
        val popup = WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            isFocusable = false
            isFocusableInTouchMode = false
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        container().addView(popup, ViewGroup.LayoutParams(0, 0))
        val timeout = Runnable { release(popup) }
        pending[popup] = timeout
        handler.postDelayed(timeout, 10_000)
        fun handleTarget(url: String): Boolean {
            if (url == "about:blank" || url.startsWith("blob:", ignoreCase = true)) return false
            if (!pending.containsKey(popup)) return true
            val target = runCatching { ExternalNavigationPolicy.externalUrl(url) }.getOrNull()
            release(popup)
            if (target != null && isCurrentSession()) openExternal(target)
            return true
        }
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request == null || !request.isForMainFrame) return false
                return handleTarget(request.url.toString())
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null) handleTarget(url)
            }
        }
        popup.setDownloadListener { url, _, disposition, mimeType, length ->
            if (pending.containsKey(popup) && isCurrentSession()) onDownload(url, disposition, mimeType, length)
            release(popup)
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                if (window != null) release(window)
            }
        }
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    fun clear() {
        pending.keys.toList().forEach(::release)
    }

    private fun release(view: WebView) {
        val timeout = pending.remove(view) ?: return
        handler.removeCallbacks(timeout)
        view.stopLoading()
        (view.parent as? ViewGroup)?.removeView(view)
        handler.post { view.destroy() }
    }
}
