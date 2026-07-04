package com.sillyclient

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.getcapacitor.BridgeActivity
import com.sillyclient.plugin.TarvenEnvPlugin
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.JSObject
import com.sillyclient.runtime.RuntimePaths
import com.sillyclient.runtime.RuntimeFileUtils
import com.sillyclient.runtime.TarvenProcessRunner
import com.sillyclient.ui.HybridUiHost
import com.sillyclient.ui.TopScrimBar
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : BridgeActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val topColorPoll: Runnable = Runnable {
        if (isWebViewVisible) {
            sampleTopColor { c -> if (c != null) applyTopColor(c) }
            handler.postDelayed(topColorPoll, 1500)
        }
    }

    // ---- Views ----
    private lateinit var root: FrameLayout
    private lateinit var topScrimBar: TopScrimBar     // 酒馆顶框 scrim 条（渐变+光泽+色波）
    private lateinit var webViewScreen: FrameLayout
    private lateinit var webView: WebView

    // ---- Hybrid UI host (web dashboard / console / bridge) ----
    private lateinit var hybridHost: HybridUiHost

    /** 本地实例运行配置(对应前端管理面板设置项)。 */
    data class InstanceConfig(
        val listen: Boolean = false,
        val ipv4: Boolean = true,
        val ipv6: Boolean = false,
        val dnsIpv6: Boolean = false,
        val heartbeat: Int = 0,
        val keepAlive: Boolean = false
    )

    // ---- State ----
    private lateinit var runner: TarvenProcessRunner
    private var serverReady = false
    private var isWebViewVisible = false
    private var statusBarFixedPx = 0  // fixed physical pixels, never changes
    // 启动器支持多实例:目标 URL 与端口由前端实例数据决定,不再硬编码 8000
    private var tavernUrl = "http://127.0.0.1:8000/"
    private var tavernPort = 8000
    /** 当前 Node 服务进程(用于终端 stdin 输入)。 */
    private var serverProcess: Process? = null
    /** 酒馆 WebView 下拉刷新开关。 */
    private var pullToRefreshEnabled = false
    /** 下拉刷新手势状态。 */
    private var pullStartY = 0f
    private var pullReadyToReload = false

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    companion object {
        private const val TAG = "SillyClient"
        private const val SERVER_SOURCE_URL =
            "https://github.com/CAPTCHAAAAA/TarvenPlus/releases/download/v0.2/server-source.zip"
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        private const val BG = 0xFF070408.toInt()
        private const val STATE_SERVER_READY = "server_ready"
        private const val STATE_WEBVIEW_VISIBLE = "webview_visible"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // ---- Capacitor: register plugin BEFORE super so BridgeActivity picks it up ----
        registerPlugin(TarvenEnvPlugin::class.java)
        super.onCreate(savedInstanceState)

        // ╔══════════════════════════════════════════════════════════════╗
        // ║  DO NOT CHANGE — Fullscreen immersion foundation.           ║
        // ║  These 4 lines are the result of 2 weeks of trial-and-error ║
        // ║  against MIUI/HyperOS window state machines.                ║
        // ║  - setDecorFitsSystemWindows(false): content behind bars    ║
        // ║  - SHORT_EDGES: tell MIUI "we own the cutout, don't push"  ║
        // ║  - statusBarFixedPx: from hardware DisplayCutout (116px),   ║
        // ║    NEVER from software insets (they lie).                   ║
        // ║  - CONSUMED insets: WebView never sees layout shifts.        ║
        // ╚══════════════════════════════════════════════════════════════╝
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Match window background to Compose BG — eliminates native flash
        window.decorView.setBackgroundColor(BG)
        runner = TarvenProcessRunner()
        statusBarFixedPx = readStatusBarFixedPx()

        val wasServerReady = savedInstanceState?.getBoolean(STATE_SERVER_READY, false) ?: false
        val wasWebViewVisible = savedInstanceState?.getBoolean(STATE_WEBVIEW_VISIBLE, false) ?: false

        // ---- Hybrid web dashboard = primary content ----
        hybridHost = HybridUiHost(this)
        hybridHost.callback = object : HybridUiHost.Callback {
            override fun onEnter() = enterTavern()
            override fun onExit() = exitTavern()
            override fun onDiagnose() = runDiagnostics()
            override fun onCopyLogs(text: String) {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Tarven logs", text))
            }
            override fun onGoBack() { if (webView.canGoBack()) webView.goBack() }
            override fun onGoForward() { if (webView.canGoForward()) webView.goForward() }
            override fun onSetZoom(pct: Int) { webView.settings.textZoom = pct.coerceIn(50, 200) }
            override fun onSetDark(on: Boolean) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    webView.settings.forceDark =
                        if (on) android.webkit.WebSettings.FORCE_DARK_ON else android.webkit.WebSettings.FORCE_DARK_OFF
            }
            override fun onSetJs(on: Boolean) { webView.settings.javaScriptEnabled = on }
            override fun onSetCookies(on: Boolean) { CookieManager.getInstance().setAcceptCookie(on) }
            override fun onSetUa(value: String) { /* TODO: map ua preset → userAgentString */ }
            override fun onRefreshLogs() = refreshLogToCompose()
            override fun onClearLogs() {}
            override fun onExportLogs(text: String) = onCopyLogs(text)
            override fun onOpenExternal(url: String) {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            }
            override fun onRequestState() = pushCurrentStateToWeb()
            override fun onSetTheme(t: String) = hybridHost.setTheme(t)
        }
        hybridHost.onPageReady = { pushCurrentStateToWeb() }
        // ponytail: Capacitor WebView is now primary (loaded by BridgeActivity.load()). 
        // HybridUiHost is kept for provisionAndStart status dispatch (no-ops until wired).
        // setContentView(hybridHost.webView, FrameLayout.LayoutParams(MATCH, MATCH))
        // hybridHost.loadDashboard()

        // ---- Native overlay for WebView + FCC (hidden until entering tavern) ----
        root = FrameLayout(this).apply {
            setBackgroundColor(BG)
            visibility = View.GONE  // hidden — Compose is the only visible content at launch
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            WindowInsetsCompat.CONSUMED
        }
        addContentView(root, FrameLayout.LayoutParams(MATCH, MATCH))

        // 顶框 scrim 条：覆盖 root 顶部 statusBarFixedPx 条带（仅酒馆模式 root 可见时显现）。
        // 随酒馆页顶部取色，scrim 渐变 + 光泽呼吸 + 自下而上色波（设计见 TopScrimBar）。
        topScrimBar = TopScrimBar(this)
        topScrimBar.attach(root, statusBarFixedPx)

        // ============================================
        // WEBVIEW SCREEN (inside native overlay)
        // ============================================
        webViewScreen = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(BG)
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                settings.forceDark = android.webkit.WebSettings.FORCE_DARK_AUTO
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    super.onPageFinished(v, url)
                    android.util.Log.i(TAG, "Page loaded: $url")
                    installChameleonProbes()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(v: View?, cb: CustomViewCallback?) {
                    fullscreenView?.let { root.removeView(it) }
                    fullscreenView = v
                    fullscreenCallback = cb
                    v?.let {
                        root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
                        webViewScreen.visibility = View.GONE
                    }
                }
                override fun onHideCustomView() {
                    exitFullscreen()
                }
            }
        }

        webViewScreen.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(webViewScreen, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- Hybrid UI host owns the web dashboard + console + bridge ----

        // Restore or init —— 启动器语义:不自动 provision,由前端选择实例后通过插件触发。
        if (wasWebViewVisible && wasServerReady) {
            serverReady = true
            // 镜像 enterTavern 的布局：WebView 下移 statusBarFixedPx，露出顶条带
            val h = statusBarFixedPx
            val lp = webViewScreen.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = h
            webViewScreen.layoutParams = lp
            webView.loadUrl(tavernUrl)
            handler.post {
                switchToWebView(false)
                enterImmersive()
            }
            setStatus("Ready")
            pushReady(true)
        } else if (wasServerReady) {
            serverReady = true
            updateHomeReady()
        }
        // else: 首启或服务未就绪 —— 等待前端实例选择后调用 provisionAndStart(port) / enterTavern(url)

        // 启动器与酒馆统一全屏沉浸式 —— 状态栏不遮挡内容
        enterImmersive()
    }

    // ponytail: BridgeActivity.load() now loads assets/public/index.html (Capacitor console).
    // Capacitor WebView is the primary content; native overlay sits on top via addContentView.

    override fun onResume() {
        super.onResume()
        // 启动器与酒馆统一全屏沉浸式 —— 始终隐藏系统栏
        enterImmersive()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SERVER_READY, serverReady)
        outState.putBoolean(STATE_WEBVIEW_VISIBLE, isWebViewVisible)
    }

    /** Exposed for TarvenEnvPlugin. */
    fun isServerReady(): Boolean = serverReady
    fun isTavernVisible(): Boolean = isWebViewVisible
    fun getTavernUrl(): String = tavernUrl

    fun provisionAndStart(port: Int = 8000, instanceId: String = "default", version: String = "stable", config: InstanceConfig = InstanceConfig(), zipballUrl: String? = null, skipIfExists: Boolean = true) {
        tavernPort = port
        tavernUrl = "http://127.0.0.1:$port/"
        Thread {
            val paths = RuntimePaths.from(this)
            paths.ensureDirs()
            // 多实例:每个本地实例独立 server 目录
            val targetServerDir = paths.serverDirFor(instanceId)

            val serverJs = File(targetServerDir, "server.js")
            val hasServer = serverJs.exists()

            if (!hasServer) {
                appendLog("→ Provisioning [$instanceId]...")
                updateProgress(5)
                appendLog("→ Extracting rootfs-libs.zip...")
                extractNativeLibs(paths)
                updateProgress(15)
                // 优先:按用户选择的 GitHub release 下载源码 + npm install
                var ok = false
                if (zipballUrl != null) {
                    appendLog("→ Downloading $version source from GitHub...")
                    ok = downloadAndExtractGithubRelease(zipballUrl, paths, targetServerDir)
                    if (ok) {
                        appendLog("→ Installing dependencies (npm install --production)...")
                        val npmOk = runNpmInstall(paths, targetServerDir)
                        if (!npmOk) {
                            appendLog("⚠ npm install 不可用,回退到预打包 server-source.zip")
                            ok = false
                        }
                    }
                }
                // 回退:预打包 server-source.zip(含 node_modules)
                if (!ok) {
                    appendLog("→ Downloading pre-bundled server-source.zip (136MB)...")
                    ok = downloadAndExtractServer(paths, targetServerDir)
                }
                updateProgress(100)
                if (!ok) {
                    appendLog("✗ Download failed")
                    setStatus("Download failed")
                    return@Thread
                }
                appendLog("✓ Server source extracted")
            } else {
                appendLog("✓ Server source already exists [$instanceId]")
            }

            // 写入实例运行配置(管理面板设置 → config.yaml)
            writeInstanceConfig(targetServerDir, config)

            appendLog("→ Starting Node.js server...")
            setStatus("Starting server...")
            val started = startServer(paths, targetServerDir)
            if (!started) {
                appendLog("✗ Server start failed")
                setStatus("Start failed")
                return@Thread
            }
            appendLog("✓ Node.js process launched")
            appendLog("→ Polling $tavernUrl...")

            pollUntilReady()
        }.start()
    }

    private fun updateHomeReady() {
        post {
            pushProgress(100f, "Ready")
            pushReady(true)
        }
    }

    /**
     * ╔══════════════════════════════════════════════════════════════════╗
     * ║  DO NOT CHANGE the layout strategy.                              ║
     * ║  We manually push WebView down by statusBarHeight so the top    ║
     * ║  band is free for our info bar. This is intentional — we do NOT ║
     * ║  rely on system insets (they change to 0 in immersive and break ║
     * ║  everything on MIUI). The fixed topMargin + consumed insets     ║
     * ║  combo is the only stable approach found for HyperOS.           ║
     * ╚══════════════════════════════════════════════════════════════════╝
     */
    private fun runDiagnostics() {
        appendLog("→ Diagnostics...")
        pushProgress(0f)
        Thread {
            val paths = RuntimePaths.from(this)
            // 1. Node binary
            val nodeOk = paths.nodeBin.exists()
            pushLog(if (nodeOk) "  Node v24.17.0 ready" else "  Node binary missing!")
            pushProgress(30f)
            // 2. Bionic libs
            val libCount = paths.usrLibDir.listFiles()?.size ?: 0
            pushLog(if (libCount > 50) "  $libCount libs loaded" else "  $libCount libs — need rootfs")
            pushProgress(60f)
            // 3. Server source
            val serverJs = File(paths.serverDir, "server.js")
            val serverOk = serverJs.exists() && File(paths.serverDir, "node_modules").isDirectory
            pushLog(if (serverOk) "  Server source ready" else "  Server source missing!")
            pushProgress(80f)
            // 4. Fix if needed
            if (!nodeOk || libCount < 50 || !serverOk) {
                appendLog("→ Issues found — fixing...")
                if (libCount < 50) try {
                    paths.usrDir.mkdirs()
                    assets.open("bootstrap/rootfs/rootfs-libs.zip").use { RuntimeFileUtils.unzipStream(it, paths.usrDir) }
                    val n = paths.usrLibDir.listFiles()?.size ?: 0
                    pushLog("  $n libs (re-extracted)")
                } catch (_: Exception) {
                    pushLog("  Extraction failed")
                }
            }
            // 5. HTTP check
            val httpOk = tryConnect(tavernUrl)
            if (httpOk) {
                pushLog("  $tavernUrl — online")
                pushReady(true)
                pushProgress(100f, "All systems ready")
                serverReady = true
            } else {
                pushProgress(100f, "Check complete — tap ENTER to start")
            }
        }.start()
    }

    fun enterTavern(targetUrl: String? = null) {
        // 远程实例:直接进入(无需 serverReady);本地实例:需 serverReady
        if (targetUrl != null) {
            tavernUrl = targetUrl
            if (!isLocalUrl(targetUrl)) serverReady = true
        }
        if (!serverReady || isWebViewVisible) return
        if (targetUrl != null || webView.url == null || webView.url.isNullOrBlank()) {
            webView.loadUrl(tavernUrl)
        }
        val h = statusBarFixedPx
        val lp = webViewScreen.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = h
        webViewScreen.layoutParams = lp
        enterImmersive()
        switchToWebView(true)
        // 顶条带自动取色由 installChameleonProbes 驱动（控制台转向 Capacitor 接入）
        // 页面若已加载，onPageFinished 不会重触发，故在此 kick 轮询。
        handler.removeCallbacks(topColorPoll)
        handler.postDelayed(topColorPoll, 350)
    }

    /** 判断是否本地回环地址(127.0.0.1 / localhost)。 */
    private fun isLocalUrl(url: String): Boolean =
        url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")

    fun exitTavern() {
        if (!isWebViewVisible) return
        handler.removeCallbacks(topColorPoll)
        topScrimBar.reset()
        // 启动器也保持沉浸式,不显示系统栏
        // Restore system gesture handling
        clearSystemGestureExclusions()
        val lp = webViewScreen.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = 0
        webViewScreen.layoutParams = lp
        switchToHome(true)
    }

    /** Prevent system back gesture from intercepting edge touches inside the WebView. */
    private fun excludeSystemGestures() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        webView.post {
            val w = webView.width; if (w <= 0) return@post
            val h = webView.height; if (h <= 0) return@post
            val band = dp(36) // exclude ~36dp from each vertical edge
            webView.systemGestureExclusionRects = listOf(
                Rect(0, 0, band, h),            // left edge
                Rect(w - band, 0, w, h)         // right edge
            )
        }
    }

    private fun clearSystemGestureExclusions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webView.systemGestureExclusionRects = emptyList()
        }
    }

    private fun pushMode(mode: String) {
        TarvenEnvPlugin.notify("mode", JSObject().put("mode", mode))
    }

    private fun switchToWebView(animate: Boolean) {
        isWebViewVisible = true
        // Show native overlay (tavernWebView + FCC) — Capacitor console stays behind it.
        root.visibility = View.VISIBLE
        webViewScreen.visibility = View.VISIBLE
        pushMode("tavern")
        if (animate) {
            webViewScreen.alpha = 0f
            webViewScreen.animate().alpha(1f).setDuration(220).start()
        } else {
            webViewScreen.alpha = 1f
        }
    }

    private fun switchToHome(animate: Boolean) {
        isWebViewVisible = false
        // Hide native overlay — Capacitor console shows underneath.
        webViewScreen.visibility = View.GONE
        root.visibility = View.GONE
        pushMode("launcher")
        pushReady(true)
    }

    // ╔══════════════════════════════════════════════════════════════════╗
    // ║  DO NOT CHANGE — Immersive hide/show.                           ║
    // ║  API 30+: WindowInsetsController (modern, clean).                ║
    // ║  API 26-29: SYSTEM_UI_FLAG_IMMERSIVE_STICKY (proven fallback).  ║
    // ║  DO NOT mix old and new APIs — Android 15+ has a concurrency    ║
    // ║  bug in ClientWindowFrames when both are active simultaneously. ║
    // ╚══════════════════════════════════════════════════════════════════╝
    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ╔══════════════════════════════════════════════════════════════════╗
    // ║  DO NOT REMOVE — MIUI re-immersive guard.                       ║
    // ║  MIUI forcibly shows system bars after notification shade pull,  ║
    // ║  recents, or screen rotation. This callback re-hides them.      ║
    // ╚══════════════════════════════════════════════════════════════════╝
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 启动器与酒馆统一:有焦点就保持沉浸式(MIUI 会在通知栏/最近任务后强制显示系统栏)
        if (hasFocus) {
            enterImmersive()
        }
    }

    // ╔══════════════════════════════════════════════════════════════════╗
    // ║  DO NOT CHANGE — 顶框自适应取色（PixelCopy → TopScrimBar）。       ║
    // ║  读 WebView 顶部 3px×全宽条带 → 平均非透明像素 → 喂 TopScrimBar。  ║
    // ║  · 条带平均而非单像素：抗抖动、稳主色。                            ║
    // ║  · isShown + try/catch：恢复态/转场无 surface 时跳过，轮询稍后重试。║
    // ║  · 触发分工：周期轮询(1.5s)+touch-up 仅做色波；gloss 白色光波仅点击。║
    // ║  取色层 Android 落地（远端页真实像素无可移植 API）；色数学生见      ║
    // ║  com.sillyclient.ui.TopColor；渲染见 com.sillyclient.ui.TopScrimBar。║
    // ╚══════════════════════════════════════════════════════════════════╝
    private fun sampleTopColor(onResult: (Int?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { onResult(null); return }
        val w = webView.width
        if (w <= 0 || !webView.isShown) { onResult(null); return }  // 未绘制/无 surface 时跳过
        val loc = IntArray(2)
        webView.getLocationInWindow(loc)
        val top = loc[1] + 1                       // WebView 顶边下 1px
        val stripH = 3
        val srcRect = Rect(loc[0], top, loc[0] + w, top + stripH)
        val bmp = Bitmap.createBitmap(w, stripH, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, srcRect, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    var rs = 0; var gs = 0; var bs = 0; var n = 0
                    for (y in 0 until stripH) {
                        for (x in 0 until w) {
                            val p = bmp.getPixel(x, y)
                            if (Color.alpha(p) > 200) {
                                rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p); n++
                            }
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
            // 窗口无 surface（恢复态/转场）→ 放弃本次，轮询稍后重试
            bmp.recycle()
            onResult(null)
        }
    }

    /** 取色 → 顶框 scrim 条色波 + 光泽呼吸。 */
    private fun applyTopColor(color: Int) {
        topScrimBar.setColor(color)
    }

    /** 探针：页面加载后 + 每次 touch-up + 酒馆内 1.5s 周期轮询（单链去重）。 */
    private fun installChameleonProbes() {
        handler.removeCallbacks(topColorPoll)
        handler.postDelayed(topColorPoll, 0)
        webView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pullStartY = event.rawY
                    pullReadyToReload = pullToRefreshEnabled && webView.scrollY == 0
                }
                MotionEvent.ACTION_UP -> {
                    // 下拉刷新:从顶部向下拉超过 120px 时刷新
                    if (pullReadyToReload && (event.rawY - pullStartY) > 120) {
                        webView.reload()
                        pushLog("↓ 下拉刷新酒馆界面")
                    }
                    pullReadyToReload = false
                    topScrimBar.sweepGloss()   // 点击白色光波
                    handler.postDelayed({
                        if (isWebViewVisible) sampleTopColor { c -> if (c != null) applyTopColor(c) }
                    }, 200)
                }
            }
            false
        }
    }

    private fun exitFullscreen() {
        fullscreenView?.let { root.removeView(it) }
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        webViewScreen.visibility = View.VISIBLE
    }

    // ============================================
    // SERVER PROVISIONING
    // ============================================

    private fun extractNativeLibs(paths: RuntimePaths) {
        setStatus("Extracting runtime...")
        val nativeDir = paths.nativeLibDir
        val bootstrapDir = paths.bootstrapDir
        bootstrapDir.mkdirs()

        // Extract Bionic system libraries (libz, libssl, libicu etc.) from APK assets
        try {
            paths.usrDir.mkdirs()
            val assetPath = "bootstrap/rootfs/rootfs-libs.zip"
            assets.open(assetPath).use { input ->
                RuntimeFileUtils.unzipStream(input, paths.usrDir)
            }
            android.util.Log.i(TAG, "Rootfs extracted to ${paths.usrDir}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Rootfs extraction failed", e)
        }

        // Copy native SO files from lib dir to bootstrap for scripts
        val soFiles = listOf(
            "libtarven-sh.so",
            "libtarven-git.so",
            "libtarven-git-remote-http.so",
            "libtarven-curl.so"
        )
        for (so in soFiles) {
            val src = File(nativeDir, so)
            val dst = File(bootstrapDir, so)
            if (src.exists() && !dst.exists()) {
                src.copyTo(dst)
                RuntimeFileUtils.chmodExecutable(dst)
            }
        }
    }

    private fun downloadAndExtractServer(paths: RuntimePaths, targetServerDir: File): Boolean {
        val destZip = File(paths.tarvenHome, "server-source.zip")
        val serverDir = targetServerDir
        serverDir.mkdirs()

        if (!downloadFile(SERVER_SOURCE_URL, destZip)) return false

        setStatus("Extracting server...")
        setStatus("Extracting server...")
        try {
            destZip.inputStream().use { input ->
                RuntimeFileUtils.unzipStream(input, serverDir)
            }
            destZip.delete()
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Extract failed", e)
            setStatus("Extract failed")
            setStatus("Extract failed")
            return false
        }
    }

    private fun downloadFile(urlStr: String, dest: File): Boolean {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "Tarven++/0.4")
            conn.connect()
            if (conn.responseCode != 200) {
                android.util.Log.e(TAG, "Download HTTP ${conn.responseCode}")
                return false
            }
            val total = conn.contentLengthLong
            val input = BufferedInputStream(conn.inputStream)
            val output = FileOutputStream(dest)
            val buf = ByteArray(65536)
            var dl = 0L
            var len: Int
            while (input.read(buf).also { len = it } != -1) {
                output.write(buf, 0, len)
                dl += len
                if (total > 0 && dl % (5 * 1024 * 1024) < buf.size) {
                    val pct = (dl * 100 / total).toInt()
                    updateProgress(15 + (pct * 80 / 100))
                    setStatus("Downloading... $pct%")
                }
            }
            output.close()
            input.close()
            conn.disconnect()
            android.util.Log.i(TAG, "Downloaded: ${dest.length()} bytes")
            true
        } catch (e: Exception) {
            dest.delete()
            android.util.Log.e(TAG, "Download failed", e)
            false
        }
    }

    private fun startServer(paths: RuntimePaths, targetServerDir: File): Boolean {
        paths.logsDir.mkdirs()
        // Ensure local xdg-open from open package is executable
        val localXdgOpen = File(targetServerDir, "node_modules/open/xdg-open")
        if (localXdgOpen.exists()) RuntimeFileUtils.chmodExecutable(localXdgOpen)
        // Create fake xdg-open that exits cleanly — open npm pkg forces system xdg-open on Android
        val fakeXdgDir = File(paths.tmpDir, "bin")
        fakeXdgDir.mkdirs()
        val fakeXdg = File(fakeXdgDir, "xdg-open")
        if (!fakeXdg.exists()) {
            fakeXdg.writeText("#!/system/bin/sh\nexit 0\n")
            RuntimeFileUtils.chmodExecutable(fakeXdg)
        }
        // Patch open package: on Android, use /system/bin/true instead of xdg-open
        val openIndex = File(targetServerDir, "node_modules/open/index.js")
        if (openIndex.exists()) {
            var patched = openIndex.readText()
            patched = patched.replace(
                "platform === 'android' || isBundled || ",
                "isBundled || ")
            // Force command = '/system/bin/true' on Android
            patched = patched.replace(
                "command = useSystemXdgOpen ? 'xdg-open' : localXdgOpenPath;",
                "command = '/system/bin/true';")
            openIndex.writeText(patched)
        }
        try {
            // Launch directly: node server.js (skip npm install — node_modules is pre-bundled)
            val pb = ProcessBuilder(paths.nodeBin.absolutePath, "server.js")
            pb.directory(targetServerDir)
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(File(paths.logsDir, "server.log")))
            val env = pb.environment()
            env["TARVEN_HOME"] = paths.tarvenHome.absolutePath
            env["TARVEN_USR"] = paths.usrDir.absolutePath
            env["TARVEN_SERVER_DIR"] = targetServerDir.absolutePath
            env["TARVEN_NODE"] = paths.nodeBin.absolutePath
            env["TARVEN_NATIVE_LIB_DIR"] = paths.nativeLibDir.absolutePath
            env["TARVEN_TMP"] = paths.tmpDir.absolutePath
            env["TARVEN_BOOTSTRAP"] = paths.bootstrapDir.absolutePath
            env["LD_LIBRARY_PATH"] = "${paths.usrDir.absolutePath}/lib:${paths.nativeLibDir.absolutePath}"
            env["AUTO_LAUNCH"] = "false"
            env["NO_BROWSER"] = "true"
            env["BROWSER"] = "/system/bin/true"
            env["PATH"] = "${paths.tmpDir.absolutePath}/bin:/system/bin:${System.getenv("PATH") ?: ""}"
            env["HOST"] = "127.0.0.1"
            env["PORT"] = tavernPort.toString()
            val p = pb.start()
            serverProcess = p
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /** 把管理面板的实例配置写入 SillyTavern 的 config.yaml(覆盖式)。 */
    private fun writeInstanceConfig(targetServerDir: File, config: InstanceConfig) {
        try {
            val yaml = buildString {
                append("port: ").append(tavernPort).append("\n")
                append("listen: ").append(config.listen).append("\n")
                append("listenAddressIPv4: '127.0.0.1'\n")
                append("protocolIPv4: ").append(config.ipv4).append("\n")
                append("protocolIPv6: ").append(config.ipv6).append("\n")
                append("dnsPreferIPv6: ").append(config.dnsIpv6).append("\n")
                append("enableHeartbeat: ").append(config.heartbeat > 0).append("\n")
                append("heartbeatInterval: ").append(config.heartbeat).append("\n")
                append("enableHttpKeepAlive: ").append(config.keepAlive).append("\n")
                append("whitelistMode: false\n")
                append("securityOverride: true\n")
            }
            File(targetServerDir, "config.yaml").writeText(yaml)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "writeInstanceConfig failed", e)
        }
    }

    /** 下载 GitHub release zipball 并解压到目标目录。 */
    private fun downloadAndExtractGithubRelease(zipballUrl: String, paths: RuntimePaths, targetServerDir: File): Boolean {
        return try {
            val url = java.net.URL(zipballUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 120000
            conn.setRequestProperty("User-Agent", "SillyClient")
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return false
            }
            val tmpZip = File(paths.tmpDir, "github-release-${System.currentTimeMillis()}.zip")
            conn.inputStream.use { input ->
                java.io.FileOutputStream(tmpZip).use { out -> input.copyTo(out) }
            }
            conn.disconnect()
            // GitHub zipball 内层有一层目录(SillyTavern-<sha>/),解压后需要平铺
            unzipFlatten(tmpZip, targetServerDir)
            tmpZip.delete()
            File(targetServerDir, "server.js").exists()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "downloadAndExtractGithubRelease", e)
            false
        }
    }

    /** 解压 zip,跳过内层单层根目录(适应 GitHub zipball 结构)。 */
    private fun unzipFlatten(zipFile: File, destDir: File) {
        destDir.mkdirs()
        java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    // 去掉第一层目录前缀
                    val name = entry.name.substringAfter('/', entry.name)
                    if (name.isNotEmpty()) {
                        val out = File(destDir, name)
                        out.parentFile?.mkdirs()
                        java.io.FileOutputStream(out).use { zis.copyTo(it) }
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    /** 运行 npm install(若运行时含 npm)。返回是否成功。 */
    private fun runNpmInstall(paths: RuntimePaths, targetServerDir: File): Boolean {
        return try {
            // 查找 npm-cli.js(node 自带)
            val npmCli = File(paths.usrDir, "lib/node_modules/npm/bin/npm-cli.js")
            if (!npmCli.exists()) return false
            val pb = ProcessBuilder(paths.nodeBin.absolutePath, npmCli.absolutePath, "install", "--production", "--no-audit", "--no-fund")
            pb.directory(targetServerDir)
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(File(paths.logsDir, "npm-install.log")))
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = "${paths.usrDir.absolutePath}/lib:${paths.nativeLibDir.absolutePath}"
            env["PATH"] = "${paths.tmpDir.absolutePath}/bin:/system/bin"
            val p = pb.start()
            val finished = p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) { p.destroyForcibly(); return false }
            p.exitValue() == 0 && File(targetServerDir, "node_modules").exists()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "runNpmInstall", e)
            false
        }
    }

    /** 扫描本地已存在的酒馆实例。返回五元组:instanceId, version, path, sizeBytes, hasServer。 */
    fun scanInstances(): List<Quint<String, String, String, Long, Boolean>> {
        val paths = RuntimePaths.from(this)
        val serversRoot = File(paths.bootstrapDir, "servers")
        val result = mutableListOf<Quint<String, String, String, Long, Boolean>>()
        if (!serversRoot.exists()) return result
        serversRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val hasServer = File(dir, "server.js").exists()
                val pkg = File(dir, "package.json")
                var version = "unknown"
                if (pkg.exists()) {
                    try {
                        val txt = pkg.readText()
                        val m = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(txt)
                        if (m != null) version = m.groupValues[1]
                    } catch (_: Exception) {}
                }
                val size = dirSize(dir)
                result.add(Quint(dir.name, version, dir.absolutePath, size, hasServer))
            }
        }
        return result
    }

    /** 实例详情:version, path, sizeBytes, createdAt, status。 */
    fun getInstanceInfo(instanceId: String, port: Int): Quint<String, String, Long, String, String> {
        val paths = RuntimePaths.from(this)
        val dir = File(paths.bootstrapDir, "servers/$instanceId")
        if (!dir.exists()) return Quint("unknown", dir.absolutePath, 0L, "", "未安装")
        val hasServer = File(dir, "server.js").exists()
        var version = "unknown"
        val pkg = File(dir, "package.json")
        if (pkg.exists()) {
            try {
                val txt = pkg.readText()
                val m = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(txt)
                if (m != null) version = m.groupValues[1]
            } catch (_: Exception) {}
        }
        val size = dirSize(dir)
        val createdAt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(dir.lastModified()))
        val status = if (hasServer) "已就绪" else "未完成"
        return Quint(version, dir.absolutePath, size, createdAt, status)
    }

    private fun dirSize(dir: File): Long {
        var size = 0L
        dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size
    }

    /** 向终端发送命令:运行 shell 命令并流式输出到日志。 */
    fun sendCommand(text: String) {
        if (text.isBlank()) return
        val paths = RuntimePaths.from(this)
        Thread {
            pushLog("\$ $text")
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", text)
                pb.directory(paths.bootstrapDir)
                pb.redirectErrorStream(true)
                val env = pb.environment()
                env["LD_LIBRARY_PATH"] = "${paths.usrDir.absolutePath}/lib:${paths.nativeLibDir.absolutePath}"
                env["PATH"] = "${paths.tmpDir.absolutePath}/bin:/system/bin:${System.getenv("PATH") ?: ""}"
                env["HOME"] = paths.tarvenHome.absolutePath
                val p = pb.start()
                java.io.BufferedReader(java.io.InputStreamReader(p.inputStream)).useLines { lines ->
                    lines.forEach { pushLog(it) }
                }
                p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                pushLog("命令执行失败: ${e.message}")
            }
        }.start()
    }

    /** 刷新酒馆 WebView。 */
    fun reloadTavern() {
        if (isWebViewVisible) webView.reload()
    }

    /** 清空宿主 WebView 缓存/Cookie/历史。 */
    fun clearWebViewData() {
        webView.clearCache(true)
        webView.clearHistory()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        pushLog("已清空宿主 WebView 缓存/Cookie/历史")
    }

    /** 安全 insets(挖孔/状态栏避让),单位 px。
     *  top = 仅挖孔摄像头高度(非整个状态栏),前端顶栏用此值避让。
     *  若 cutout 尚未就绪(返回 0),fallback 到 statusBarFixedPx 的挖孔部分。
     */
    fun getSafeInsets(): Quartet<Int, Int, Int, Int> {
        var cutoutTop = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cutout = window.decorView.rootWindowInsets?.displayCutout
            if (cutout != null) cutoutTop = cutout.safeInsetTop
        }
        // Fallback: 若运行时 cutout 未就绪,用 onCreate 时测量的 statusBarFixedPx
        if (cutoutTop <= 0) cutoutTop = statusBarFixedPx
        return Quartet(cutoutTop, 0, 0, 0)
    }

    /** 启用/禁用酒馆 WebView 下拉刷新。 */
    fun setPullToRefresh(enabled: Boolean) {
        pullToRefreshEnabled = enabled
    }

    /** 把选中图片复制到 covers/{instanceId}.png,返回可加载的文件路径。 */
    fun copyCoverImage(uri: android.net.Uri, instanceId: String): String {
        val paths = RuntimePaths.from(this)
        val coversDir = File(paths.bootstrapDir, "covers").apply { mkdirs() }
        val outFile = File(coversDir, "$instanceId.png")
        contentResolver.openInputStream(uri).use { input ->
            java.io.FileOutputStream(outFile).use { out -> input?.copyTo(out) }
        }
        // WebView 可直接加载 file:// 路径
        return outFile.absolutePath
    }

    /** 简单五元组(Kotlin 标准库无 Quintuple)。 */
    data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
    data class Quartet<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun pollUntilReady() {
        var a = 0
        while (a < 120) {
            if (tryConnect(tavernUrl)) {
                appendLog("✓ SillyTavern is online at $tavernUrl")
                serverReady = true
                updateHomeReady()
                refreshLogToCompose()
                return
            }
            a++
            if (a % 10 == 0) appendLog("... still waiting ($a/120)")
            try { Thread.sleep(1000) } catch (_: Exception) { break }
        }
        appendLog("✗ Server did not respond within 120s")
        setStatus("No response")
    }

    private fun tryConnect(url: String) = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        c.responseCode in 200..499
    } catch (_: Exception) { false }

    // ============================================
    // HELPERS — push to Capacitor JS via TarvenEnvPlugin.notify
    // ============================================

    private fun pushLog(line: String) {
        TarvenEnvPlugin.notify("log", JSObject().put("message", line))
    }

    private fun pushProgress(pct: Float, text: String? = null) {
        val d = JSObject().put("percent", pct.toInt())
        if (text != null) d.put("stage", text)
        TarvenEnvPlugin.notify("progress", d)
    }

    private fun pushReady(ready: Boolean) {
        val d = JSObject().put("ready", ready)
        if (ready) {
            d.put("url", tavernUrl)
            d.put("port", tavernPort)
        }
        TarvenEnvPlugin.notify("ready", d)
    }

    private fun setStatus(t: String) { pushLog(t) }
    private fun setProgressValue(pct: Int) { pushProgress(pct.toFloat()) }
    private fun updateProgress(pct: Int) { pushProgress(pct.toFloat()) }
    private fun appendLog(line: String) { pushLog(line) }

    /** Read server.log and push its tail to Capacitor JS. */
    private fun refreshLogToCompose() {
        Thread {
            val paths = RuntimePaths.from(this)
            val logFile = File(paths.logsDir, "server.log")
            if (!logFile.exists()) return@Thread
            val lines = logFile.readLines().takeLast(30)
            for (l in lines) pushLog(l)
        }.start()
    }

    /** Push current boot state to Capacitor JS. */
    private fun pushCurrentStateToWeb() {
        if (serverReady) {
            pushProgress(100f, "Ready")
            pushReady(true)
        }
        refreshLogToCompose()
    }

    private fun post(r: Runnable) { handler.post(r) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Hardware radar: read the physical camera cutout height — never lies, never changes.
     * Fallback: system status_bar_height resource → 24dp absolute last-resort.
     */
    // ╔══════════════════════════════════════════════════════════════════╗
    // ║  DO NOT CHANGE this read chain.                                 ║
    // ║  Priority: DisplayCutout (hardware, burned at factory) →         ║
    // ║  status_bar_height resource → 24dp fallback.                     ║
    // ║  NEVER use WindowInsets for status bar height — they report 0   ║
    // ║  when the bar is hidden, breaking all layout calculations.       ║
    // ║  The camera cutout is part of the phone glass. It doesn't care  ║
    // ║  whether Android thinks the status bar is visible.               ║
    // ╚══════════════════════════════════════════════════════════════════╝
    private fun readStatusBarFixedPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cutout = window.decorView.rootWindowInsets?.displayCutout
            if (cutout != null) {
                val h = cutout.safeInsetTop
                if (h > 0) return h
            }
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) return resources.getDimensionPixelSize(id)
        return dp(24)
    }

    override fun onBackPressed() {
        if (fullscreenView != null) exitFullscreen()
        else if (isWebViewVisible) {
            // 酒馆内 back 不退出（退出走启动页 EXIT；控制台转 Capacitor）
        } else super.onBackPressed()
    }


    override fun onDestroy() {
        handler.removeCallbacks(topColorPoll)
        if (serverReady) runner.stop()
        webView.destroy()
        super.onDestroy()
    }
}
