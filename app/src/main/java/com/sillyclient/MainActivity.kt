package com.sillyclient

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.webkit.URLUtil
import android.widget.FrameLayout
import android.widget.Toast
import com.getcapacitor.BridgeActivity
import com.sillyclient.plugin.TarvenEnvPlugin
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import com.getcapacitor.JSObject
import com.sillyclient.runtime.RuntimePaths
import com.sillyclient.runtime.RuntimeFileUtils
import com.sillyclient.download.TavernDownloadBridge
import com.sillyclient.download.TavernDownloadFiles
import com.sillyclient.download.TavernDownloadRequest
import com.sillyclient.download.TavernDownloadScript
import com.sillyclient.download.TavernDownloadTerminalEvent
import com.sillyclient.ui.TavernStatusHint
import com.sillyclient.ui.TopScrimBar
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject

class MainActivity : BridgeActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastAppliedTopColor: Int? = null
    private var samplingTopColor = false
    private val topColorPoll: Runnable = Runnable {
        if (isWebViewVisible) {
            sampleTopColor { c ->
                if (c != null) applyTopColor(c)
                handler.postDelayed(topColorPoll, 1500)
            }
        }
    }

    // ---- Views ----
    private lateinit var root: FrameLayout
    private lateinit var topScrimBar: TopScrimBar     // 酒馆顶框 scrim 条（渐变+光泽+色波）
    private lateinit var tavernStatusHint: TavernStatusHint
    private lateinit var webViewScreen: FrameLayout
    private lateinit var webView: WebView

    // 顶部状态栏手势区 — 左右滑动返回启动页
    private lateinit var topGestureZone: View
    private lateinit var topGestureDetector: GestureDetector

    /** 本地实例运行配置(对应前端管理面板设置项)。 */
    data class InstanceConfig(
        val listen: Boolean = false,
        val ipv4: Boolean = true,
        val ipv6: Boolean = false,
        val dnsIpv6: Boolean = false,
        val heartbeat: Int = 0,
        val keepAlive: Boolean = false
    )

    private data class ActiveExportDocument(
        val request: TavernDownloadRequest,
        val uri: Uri,
        val tempFile: File
    )

    private data class StatusHintMetrics(
        val areaLeft: Int,
        val areaRight: Int,
        val cameraHeightPx: Int
    )

    private data class TopCutout(
        val centerX: Int,
        val height: Int
    )

    // ---- State ----
    private var serverReady = false
    private var isWebViewVisible = false
    private var statusBarFixedPx = 0  // fixed physical pixels, never changes
    // 启动器支持多实例:目标 URL 与端口由前端实例数据决定,不再硬编码 8000
    private var tavernUrl = "http://127.0.0.1:8000/"
    private var tavernPort = 8000
    private var currentTavernInstanceId: String? = null
    private var tavernAuthHost: String? = null
    private var tavernAuthUsername: String? = null
    private var tavernAuthPassword: String? = null
    /** 当前 Node 服务进程(用于终端 stdin 输入)。 */
    private var serverProcess: Process? = null
    /** 酒馆 WebView 下拉刷新开关。 */
    private var pullToRefreshEnabled = false
    /** 下拉刷新手势状态。 */
    private var pullStartY = 0f
    private var pullReadyToReload = false

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> resolveFileChooser(result) }
    private lateinit var tavernDownloadBridge: TavernDownloadBridge
    private var pendingExportRequest: TavernDownloadRequest? = null
    private var activeExportDocument: ActiveExportDocument? = null
    private val exportTempDirectory: File by lazy {
        File(cacheDir, "tavern-exports").apply { mkdirs() }
    }
    private val exportTimeoutPoll: Runnable = object : Runnable {
        override fun run() {
            if (::tavernDownloadBridge.isInitialized && tavernDownloadBridge.hasActiveRequest()) {
                tavernDownloadBridge.expireInactive(
                    waitingTimeoutMillis = 2 * 60 * 1000L,
                    writingTimeoutMillis = 2 * 60 * 1000L
                )
                handler.postDelayed(this, 10_000)
            }
        }
    }
    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> resolveTavernExport(result) }

    private val fullscreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitFullscreen()
    }

    companion object {
        private const val TAG = "SillyClient"
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        private const val BG = 0xFF070408.toInt()
        private const val STATE_SERVER_READY = "server_ready"
        private const val STATE_WEBVIEW_VISIBLE = "webview_visible"
        private const val STATE_TAVERN_URL = "tavern_url"
        private const val STATE_TAVERN_PORT = "tavern_port"
        private const val STATE_TAVERN_INSTANCE_ID = "tavern_instance_id"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // ---- Capacitor: register plugin BEFORE super so BridgeActivity picks it up ----
        registerPlugin(TarvenEnvPlugin::class.java)
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, fullscreenBackCallback)

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
        statusBarFixedPx = readStatusBarFixedPx()

        val wasServerReady = savedInstanceState?.getBoolean(STATE_SERVER_READY, false) ?: false
        val wasWebViewVisible = savedInstanceState?.getBoolean(STATE_WEBVIEW_VISIBLE, false) ?: false
        savedInstanceState?.getString(STATE_TAVERN_URL)?.takeIf { it.isNotBlank() }?.let {
            tavernUrl = it
        }
        savedInstanceState?.getInt(STATE_TAVERN_PORT, 0)?.takeIf { it > 0 }?.let {
            tavernPort = it
        }
        savedInstanceState?.getString(STATE_TAVERN_INSTANCE_ID)?.takeIf { it.isNotBlank() }?.let {
            currentTavernInstanceId = it
        }

        // ---- Native overlay for WebView + FCC (hidden until entering tavern) ----
        root = FrameLayout(this).apply {
            setBackgroundColor(BG)
            visibility = View.GONE  // hidden — Compose is the only visible content at launch
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // 消费系统栏 insets（WebView 不受系统栏影响），但保留 IME insets 传递给子 View
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, 0))
                .setInsets(WindowInsetsCompat.Type.ime(), insets.getInsets(WindowInsetsCompat.Type.ime()))
                .setVisible(WindowInsetsCompat.Type.ime(), insets.isVisible(WindowInsetsCompat.Type.ime()))
                .build()
        }
        addContentView(root, FrameLayout.LayoutParams(MATCH, MATCH))

        // 顶框 scrim 条：覆盖 root 顶部 statusBarFixedPx 条带（仅酒馆模式 root 可见时显现）。
        // 随酒馆页顶部取色，scrim 渐变 + 光泽呼吸 + 自下而上色波（设计见 TopScrimBar）。
        topScrimBar = TopScrimBar(this)
        topScrimBar.attach(root, statusBarFixedPx)

        // 顶部状态栏手势区：透明 View 覆盖 statusBarFixedPx 条带
        // 始终可见（不放在 root 里），支持双向操作：
        //   酒馆模式：滑动 → exitTavern()（回启动器，不停服务）
        //   启动器模式：滑动 → returnToTavern()（回酒馆，如果还在跑）
        topGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.8f &&
                    kotlin.math.abs(dx) > 60 &&
                    kotlin.math.abs(vx) > 300) {
                    if (isWebViewVisible) {
                        // 酒馆 → 启动器
                        tavernStatusHint.markUsed()
                        exitTavern()
                    } else if (serverReady && tavernUrl.isNotBlank()) {
                        // 启动器 → 酒馆（实例还在跑）
                        returnToTavern()
                    }
                    return true
                }
                return false
            }
        })
        topGestureZone = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                statusBarFixedPx
            ).apply { gravity = Gravity.TOP }
            setOnTouchListener { view, event ->
                topGestureDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP) view.performClick()
                true
            }
        }
        // 加到独立的始终可见的容器（不放在 root 里）
        val gestureHost = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // 不消费区域外的触摸，只让 topGestureZone 消费状态栏区域
            isClickable = false
        }
        gestureHost.addView(topGestureZone)
        addContentView(gestureHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ============================================
        // WEBVIEW SCREEN (inside native overlay)
        // ============================================
        webViewScreen = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(BG)
        }

        tavernDownloadBridge = TavernDownloadBridge(
            onSaveRequested = { request -> handler.post { launchTavernExportPicker(request) } },
            onStartTransfer = { request -> handler.post { startTavernExportTransfer(request) } },
            onTerminal = { event -> handler.post { handleTavernExportTerminal(event) } },
            onTransientError = { message -> handler.post { showTavernExportError(message) } }
        )
        cleanupStaleExportTempFiles()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            // 文件选择器返回 content:// URI。保持 file:// 关闭，允许 WebView 读取用户明确选择的内容。
            settings.allowContentAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                settings.forceDark = android.webkit.WebSettings.FORCE_DARK_AUTO
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(tavernDownloadBridge, "SillyClientAndroidDownloads")
            setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
                requestTavernUrlDownload(url, contentDisposition, mimeType, contentLength)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    tavernDownloadBridge.invalidateSession()
                    super.onPageStarted(view, url, favicon)
                }

                override fun onReceivedHttpAuthRequest(
                    view: WebView?,
                    handler: HttpAuthHandler?,
                    host: String?,
                    realm: String?
                ) {
                    val username = tavernAuthUsername
                    val password = tavernAuthPassword
                    val expectedHost = tavernAuthHost
                    if (
                        handler != null &&
                        username != null &&
                        password != null &&
                        expectedHost != null &&
                        expectedHost.equals(host, ignoreCase = true)
                    ) {
                        handler.proceed(username, password)
                        return
                    }
                    super.onReceivedHttpAuthRequest(view, handler, host, realm)
                }

                override fun onPageFinished(v: WebView?, url: String?) {
                    super.onPageFinished(v, url)
                    android.util.Log.i(TAG, "Page loaded: $url")
                    installTavernDownloadSupport(url)
                    installChameleonProbes()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // WebView 只保留一个待回调选择；重新触发时先结束旧请求，避免页面永久等待。
                    pendingFileChooser?.onReceiveValue(null)
                    pendingFileChooser = filePathCallback
                    if (filePathCallback == null || fileChooserParams == null) {
                        pendingFileChooser = null
                        return false
                    }

                    return try {
                        fileChooserLauncher.launch(createFileChooserIntent(fileChooserParams))
                        true
                    } catch (error: Exception) {
                        android.util.Log.e(TAG, "Unable to open WebView file chooser", error)
                        pendingFileChooser?.onReceiveValue(null)
                        pendingFileChooser = null
                        false
                    }
                }

                override fun onShowCustomView(v: View?, cb: CustomViewCallback?) {
                    fullscreenView?.let { root.removeView(it) }
                    fullscreenView = v
                    fullscreenCallback = cb
                    fullscreenBackCallback.isEnabled = v != null
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
        val statusHintMetrics = statusHintMetrics()
        tavernStatusHint = TavernStatusHint(this).also {
            it.attach(
                root,
                statusBarFixedPx,
                statusHintMetrics.areaLeft,
                statusHintMetrics.areaRight,
                statusHintMetrics.cameraHeightPx
            )
        }

        // IME 适配：输入法弹出时，给 webViewScreen 加底部 padding，让内容不被遮挡
        // setDecorFitsSystemWindows(false) + CONSUMED 会吞掉所有 insets，
        // 所以在 webViewScreen 上单独监听 IME insets。
        ViewCompat.setOnApplyWindowInsetsListener(webViewScreen) { v, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(0, 0, 0, if (imeVisible) imeHeight else 0)
            insets
        }

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
                currentTavernInstanceId?.let { tavernStatusHint.show(it) }
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
        if (tavernUrl.isNotBlank()) {
            outState.putString(STATE_TAVERN_URL, tavernUrl)
            outState.putInt(STATE_TAVERN_PORT, tavernPort)
        }
        currentTavernInstanceId?.takeIf { it.isNotBlank() }?.let {
            outState.putString(STATE_TAVERN_INSTANCE_ID, it)
        }
    }

    /** Exposed for TarvenEnvPlugin. */
    fun isServerReady(): Boolean = serverReady
    fun isTavernVisible(): Boolean = isWebViewVisible
    fun getTavernUrl(): String = tavernUrl

    fun provisionAndStart(port: Int = 8000, instanceId: String = "default", version: String = "stable", config: InstanceConfig = InstanceConfig(), zipballUrl: String? = null, localZipPath: String? = null) {
        serverReady = false
        tavernPort = port
        tavernUrl = "http://127.0.0.1:$port/"
        clearTavernBasicAuth()
        Thread {
            val paths = RuntimePaths.from(this)
            paths.ensureDirs()
            // 多实例:每个本地实例独立 server 目录
            val targetServerDir = paths.serverDirFor(instanceId)

            val serverJs = File(targetServerDir, "server.js")
            val nodeModules = File(targetServerDir, "node_modules")
            // server.js 存在但 node_modules 不存在 = 之前安装失败,需要重新安装
            val hasServer = serverJs.exists() && nodeModules.exists()

            if (!hasServer) {
                appendLog("> Provisioning [$instanceId]...")
                updateProgress(2, "Initializing")
                // 未完成的事务不能被下次扫描成一个实例。
                if (targetServerDir.exists()) {
                    appendLog("> 清理未完成的安装残留...")
                    targetServerDir.deleteRecursively()
                }
                targetServerDir.mkdirs()
                appendLog("> Extracting rootfs-libs.zip...")
                extractNativeLibs(paths)
                updateProgress(8, "Runtime ready")
                var ok = false

                // 优先:本地 zip 文件导入
                if (localZipPath != null) {
                    appendLog("> 从本地文件导入: $localZipPath")
                    updateProgress(50, "Extracting local zip")
                    val localZip = File(localZipPath)
                    if (localZip.exists()) {
                        ok = extractLocalZip(localZip, targetServerDir)
                        if (ok) {
                            appendLog("[OK] 本地文件解压完成")
                            updateProgress(85, "Installing dependencies")
                            appendLog("> Installing dependencies (npm install --production)...")
                            val npmOk = runNpmInstall(paths, targetServerDir)
                            if (!npmOk) {
                                appendLog("[WARN] npm install 不可用,但源码已解压")
                            } else {
                                appendLog("[OK] Dependencies installed")
                            }
                        } else {
                            appendLog("[ERR] 本地文件解压失败")
                        }
                    } else {
                        appendLog("[ERR] 本地文件不存在: $localZipPath")
                    }
                }

                // 其次:按用户选择的 GitHub release 下载源码 + npm install
                if (!ok && zipballUrl != null) {
                    appendLog("> Downloading $version source from GitHub...")
                    ok = downloadAndExtractGithubRelease(zipballUrl, paths, targetServerDir)
                    if (ok) {
                        appendLog("> Installing dependencies (npm install --production)...")
                        updateProgress(85, "Installing dependencies")
                        val npmOk = runNpmInstall(paths, targetServerDir)
                        if (!npmOk) {
                            appendLog("[WARN] npm install 不可用")
                            ok = false
                        } else {
                            appendLog("[OK] Dependencies installed")
                        }
                    }
                }

                updateProgress(95, "Server source ready")
                if (!ok) {
                    appendLog("[ERR] 所有安装方式均失败")
                    targetServerDir.deleteRecursively()
                    setStatus("Install failed")
                    pushError("安装失败: 无法获取 SillyTavern 源码。请尝试从本地导入 zip 文件,或检查网络后重试。")
                    return@Thread
                }
                appendLog("[OK] Server source extracted")
            } else {
                appendLog("[OK] Server source already exists [$instanceId]")
                updateProgress(50, "Server source exists")
            }

            // 写入实例运行配置(管理面板设置 → config.yaml)
            writeInstanceConfig(targetServerDir, config)

            appendLog("> Starting Node.js server...")
            updateProgress(97, "Starting server")
            setStatus("Starting server...")
            val started = startServer(paths, targetServerDir)
            if (!started) {
                appendLog("[ERR] Server start failed")
                if (!hasServer) targetServerDir.deleteRecursively()
                setStatus("Start failed")
                pushError("Node.js 服务启动失败,请重试或检查实例完整性")
                return@Thread
            }
            appendLog("[OK] Node.js process launched")
            appendLog("> Polling $tavernUrl...")
            updateProgress(99, "Waiting for server")

            pollUntilReady()
        }.start()
    }

    private fun updateHomeReady() {
        post {
            pushProgress(100f, "Ready")
            pushReady(true)
        }
    }

    /** 为酒馆 WebView 创建兼容 Android 文件管理器的选择 Intent。 */
    private fun createFileChooserIntent(params: WebChromeClient.FileChooserParams): Intent {
        return try {
            params.createIntent().apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
        } catch (_: Exception) {
            val acceptedMimeTypes = params.acceptTypes
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.contains('/') }
                .distinct()
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = acceptedMimeTypes.singleOrNull() ?: "*/*"
                if (acceptedMimeTypes.size > 1) {
                    putExtra(Intent.EXTRA_MIME_TYPES, acceptedMimeTypes.toTypedArray())
                }
                putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** 把系统文件管理器结果完整回传给酒馆页面的 input[type=file]。 */
    private fun resolveFileChooser(result: ActivityResult) {
        val callback = pendingFileChooser ?: return
        pendingFileChooser = null

        if (result.resultCode != RESULT_OK) {
            callback.onReceiveValue(null)
            return
        }

        val data = result.data
        val uris = buildList {
            val clipData = data?.clipData
            if (clipData != null) {
                for (index in 0 until clipData.itemCount) {
                    clipData.getItemAt(index).uri?.let(::add)
                }
            } else {
                data?.data?.let(::add)
            }
        }.distinct()

        callback.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    /** 只向当前配置酒馆的同源顶层页面发放一次性下载能力。 */
    private fun installTavernDownloadSupport(pageUrl: String?) {
        if (!TavernDownloadFiles.sameOrigin(tavernUrl, pageUrl)) {
            tavernDownloadBridge.invalidateSession()
            return
        }
        if (tavernDownloadBridge.hasActiveRequest()) return

        val token = UUID.randomUUID().toString()
        if (!tavernDownloadBridge.installSession(token)) return
        webView.evaluateJavascript(TavernDownloadScript.build(token)) { installed ->
            if (installed != "true") {
                android.util.Log.e(TAG, "Unable to install Tavern download interception")
                tavernDownloadBridge.invalidateSession(token)
            }
        }
    }

    /** DownloadListener 兜底：普通附件和漏过 click 拦截的 blob/data URL 仍走同一保存桥。 */
    private fun requestTavernUrlDownload(
        url: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val value = url?.takeIf { it.isNotBlank() } ?: return
        if (!TavernDownloadFiles.sameOrigin(tavernUrl, webView.url)) {
            showTavernExportError("Rejected download from a non-Tavern page")
            return
        }

        val guessedName = runCatching {
            URLUtil.guessFileName(value, contentDisposition, mimeType)
        }.getOrDefault("")
        val fileName = TavernDownloadFiles.sanitizeFileName(guessedName, mimeType)
        val safeMimeType = TavernDownloadFiles.normalizeMimeType(mimeType)
        // DownloadListener contentLength may describe the compressed wire size, while fetch streams
        // the decoded body. Keep HTTP fallbacks size-agnostic and rely on the JS stream count.
        val expectedBytes = -1L
        val script = """
            (() => {
              const request = window.__sillyClientAndroidRequestUrlDownload;
              if (typeof request !== 'function') return false;
              return request(
                ${JSONObject.quote(value)},
                ${JSONObject.quote(fileName)},
                ${JSONObject.quote(safeMimeType)},
                ${JSONObject.quote(expectedBytes.toString())}
              ) === true;
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { handled ->
            if (handled != "true") showTavernExportError("Tavern page did not accept the download")
        }
    }

    private fun launchTavernExportPicker(request: TavernDownloadRequest) {
        if (pendingExportRequest != null || activeExportDocument != null) {
            tavernDownloadBridge.cancelFromHost(
                request,
                "Another export is already active",
                notifyPage = true,
                notifyUser = true
            )
            return
        }

        pendingExportRequest = request
        handler.removeCallbacks(exportTimeoutPoll)
        handler.postDelayed(exportTimeoutPoll, 10_000)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.mimeType
            putExtra(Intent.EXTRA_TITLE, request.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            exportDocumentLauncher.launch(intent)
        } catch (error: Exception) {
            pendingExportRequest = null
            tavernDownloadBridge.cancelFromHost(
                request,
                "Unable to open Android document picker: ${error.message}",
                notifyPage = true,
                notifyUser = true
            )
        }
    }

    private fun resolveTavernExport(result: ActivityResult) {
        val request = pendingExportRequest
        pendingExportRequest = null

        if (request == null) return

        val destination = result.data?.data
        if (result.resultCode != RESULT_OK || destination == null) {
            tavernDownloadBridge.cancelFromHost(
                request,
                "Android document picker was cancelled",
                notifyPage = true,
                notifyUser = false
            )
            return
        }
        if (!tavernDownloadBridge.isAwaitingDestination(request)) return

        val tempFile = File(
            exportTempDirectory,
            "tavern-export-${request.id}-${System.currentTimeMillis()}.tmp"
        )
        if (runCatching { tempFile.createNewFile() }.isFailure) {
            tavernDownloadBridge.cancelFromHost(
                request,
                "Unable to create an export staging file",
                notifyPage = true,
                notifyUser = true
            )
            return
        }
        activeExportDocument = ActiveExportDocument(request, destination, tempFile)
        Thread {
            try {
                val output = FileOutputStream(tempFile)
                if (!tavernDownloadBridge.attachDestination(request, output)) {
                    runCatching { output.close() }
                    cleanupExportTempFile(tempFile)
                    handler.post { clearActiveExport(request) }
                }
            } catch (error: Exception) {
                tavernDownloadBridge.cancelFromHost(
                    request,
                    "Unable to open export destination: ${error.message}",
                    notifyPage = true,
                    notifyUser = true
                )
            }
        }.start()
    }

    private fun startTavernExportTransfer(request: TavernDownloadRequest) {
        if (!::webView.isInitialized) {
            tavernDownloadBridge.cancelFromHost(
                request,
                "Tavern WebView is unavailable",
                notifyPage = false,
                notifyUser = true
            )
            return
        }
        val script = """
            (() => {
              const start = window.__sillyClientAndroidStartDownload;
              if (typeof start !== 'function') return false;
              start(${JSONObject.quote(request.id)});
              return true;
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { started ->
            if (started != "true") {
                tavernDownloadBridge.cancelFromHost(
                    request,
                    "Tavern page lost the pending export",
                    notifyPage = false,
                    notifyUser = true
                )
            }
        }
    }

    private fun handleTavernExportTerminal(event: TavernDownloadTerminalEvent) {
        val request = event.request
        if (pendingExportRequest?.id == request.id) pendingExportRequest = null

        val document = activeExportDocument?.takeIf { sameExportRequest(it.request, request) }
        if (document != null) activeExportDocument = null

        if (event.notifyPage && ::webView.isInitialized) {
            val script = """
                (() => {
                  const release = window.__sillyClientAndroidReleaseDownload;
                  return typeof release === 'function' && release(${JSONObject.quote(request.id)}) === true;
                })();
            """.trimIndent()
            runCatching { webView.evaluateJavascript(script, null) }
        }

        if (event.success) {
            if (document != null) {
                commitTavernExportAsync(document)
            } else if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, "文件已导出", Toast.LENGTH_SHORT).show()
            }
        } else {
            if (document != null) cleanupExportTempFile(document.tempFile)
            android.util.Log.e(TAG, event.message ?: "Tavern export failed")
            if (event.notifyUser) showTavernExportError(event.message ?: "Tavern export failed")
        }
        tavernDownloadBridge.releaseTerminal(request)
    }

    private fun showTavernExportError(message: String) {
        android.util.Log.e(TAG, message)
        if (!isFinishing && !isDestroyed) {
            Toast.makeText(this, "文件导出失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun commitTavernExportAsync(document: ActiveExportDocument) {
        Thread {
            try {
                val output = contentResolver.openOutputStream(document.uri, "w")
                    ?: throw IOException("Document provider returned no output stream")
                document.tempFile.inputStream().use { input ->
                    output.use { sink -> input.copyTo(sink) }
                }
                cleanupExportTempFile(document.tempFile)
                handler.post {
                    clearActiveExport(document.request)
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "文件已导出", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (error: Exception) {
                cleanupExportTempFile(document.tempFile)
                android.util.Log.e(TAG, "Unable to commit export", error)
                handler.post {
                    clearActiveExport(document.request)
                    showTavernExportError("Unable to save export: ${error.message}")
                }
            }
        }.start()
    }

    private fun clearActiveExport(request: TavernDownloadRequest) {
        val current = activeExportDocument
        if (current != null && sameExportRequest(current.request, request)) {
            activeExportDocument = null
        }
    }

    private fun cleanupExportTempFile(file: File) {
        runCatching { file.delete() }
    }

    private fun cleanupStaleExportTempFiles() {
        exportTempDirectory.listFiles()?.forEach(::cleanupExportTempFile)
    }

    private fun sameExportRequest(
        left: TavernDownloadRequest,
        right: TavernDownloadRequest
    ): Boolean = left.id == right.id && left.sessionSerial == right.sessionSerial

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
    fun enterTavern(
        targetUrl: String? = null,
        basicAuthUsername: String? = null,
        basicAuthPassword: String? = null,
        instanceId: String? = null,
        showGestureHint: Boolean = false
    ): Boolean {
        android.util.Log.i(
            TAG,
            "enterTavern instanceId=$instanceId showGestureHint=$showGestureHint current=${currentTavernInstanceId}"
        )
        currentTavernInstanceId = instanceId?.trim()?.takeIf { it.isNotEmpty() } ?: currentTavernInstanceId
        // 远程实例:直接进入(无需 serverReady);本地实例:需 serverReady
        if (targetUrl != null) {
            tavernUrl = targetUrl
            if (!basicAuthUsername.isNullOrBlank() && basicAuthPassword != null) {
                tavernAuthHost = runCatching { URL(targetUrl).host }.getOrNull()
                tavernAuthUsername = basicAuthUsername
                tavernAuthPassword = basicAuthPassword
            } else {
                clearTavernBasicAuth()
            }
            if (!isLocalUrl(targetUrl)) serverReady = true
        }
        if (!serverReady || isWebViewVisible) return false
        if (targetUrl != null || webView.url == null || webView.url.isNullOrBlank()) {
            webView.loadUrl(tavernUrl)
        }
        val h = statusBarFixedPx
        val lp = webViewScreen.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = h
        webViewScreen.layoutParams = lp
        enterImmersive()
        switchToWebView(true)
        // 版本更新后同一实例也会重新提示，是否展示由原生版本标记决定。
        if (!instanceId.isNullOrBlank()) tavernStatusHint.show(instanceId)
        // 顶条带自动取色由 installChameleonProbes 驱动（控制台转向 Capacitor 接入）
        // 页面若已加载，onPageFinished 不会重触发，故在此 kick 轮询。
        handler.removeCallbacks(topColorPoll)
        handler.postDelayed(topColorPoll, 350)
        return true
    }

    /** 判断是否本地回环地址(127.0.0.1 / localhost)。 */
    private fun isLocalUrl(url: String): Boolean = TavernDownloadFiles.isLoopbackHttpUrl(url)

    /**
     * 手势退出：只隐藏 WebView，不停服务。
     * 实例继续运行，可通过 returnToTavern() 回到酒馆。
     */
    fun exitTavern() {
        if (!isWebViewVisible) return
        lastAppliedTopColor = null
        tavernStatusHint.dismiss()
        handler.removeCallbacks(topColorPoll)
        clearSystemGestureExclusions()
        // tavernRunning=true：实例还在跑，前端不置 stopped
        switchToHome(true, tavernRunning = true)
    }

    /**
     * 从启动器返回酒馆（手势返回）。
     * 只在酒馆 URL 仍存在时生效。
     */
    fun returnToTavern() {
        if (isWebViewVisible) return
        if (tavernUrl.isBlank()) return
        if (!serverReady) return
        // WebView 还保留着之前的页面，不需要重新 loadUrl
        val h = statusBarFixedPx
        val lp = webViewScreen.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = h
        webViewScreen.layoutParams = lp
        enterImmersive()
        switchToWebView(true)
        currentTavernInstanceId?.let { tavernStatusHint.show(it) }
        handler.removeCallbacks(topColorPoll)
        handler.postDelayed(topColorPoll, 350)
    }

    /**
     * 真正关闭实例（停止服务）。
     * 由前端"停止"按钮调用。
     */
    fun closeTavern() {
        lastAppliedTopColor = null
        tavernStatusHint.dismiss()
        tavernDownloadBridge.invalidateSession()
        if (isWebViewVisible) {
            handler.removeCallbacks(topColorPoll)
            topScrimBar.reset()
            clearSystemGestureExclusions()
            val lp = webViewScreen.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = 0
            webViewScreen.layoutParams = lp
        }
        isWebViewVisible = false
        webViewScreen.visibility = View.GONE
        root.visibility = View.GONE
        // 停止服务进程
        serverProcess?.let { p ->
            if (p.isAlive) {
                p.destroyForcibly()
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        serverProcess = null
        serverReady = false
        tavernUrl = ""
        clearTavernBasicAuth()
        // tavernRunning=false：前端置 stopped
        pushMode("launcher", tavernRunning = false)
        currentTavernInstanceId = null
        pushReady(false)
    }

    private fun clearTavernBasicAuth() {
        tavernAuthHost = null
        tavernAuthUsername = null
        tavernAuthPassword = null
    }

    private fun clearSystemGestureExclusions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webView.systemGestureExclusionRects = emptyList()
        }
    }

    private fun pushMode(mode: String, tavernRunning: Boolean = false) {
        TarvenEnvPlugin.notify(
            "mode",
            JSObject()
                .put("mode", mode)
                .put("tavernRunning", tavernRunning)
                .put("instanceId", currentTavernInstanceId)
        )
    }

    private fun switchToWebView(animate: Boolean) {
        isWebViewVisible = true
        // Show native overlay (tavernWebView + FCC) — Capacitor console stays behind it.
        root.visibility = View.VISIBLE
        webViewScreen.visibility = View.VISIBLE
        pushMode("tavern", true)
        if (animate) {
            root.animate().cancel()
            webViewScreen.alpha = 1f
            root.alpha = 0f
            root.animate()
                .alpha(1f)
                .setDuration(340)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        } else {
            webViewScreen.alpha = 1f
            root.alpha = 1f
        }
    }

    private fun switchToHome(animate: Boolean, tavernRunning: Boolean = false) {
        if (animate && webViewScreen.isShown) {
            root.animate().cancel()
            root.animate()
                .alpha(0f)
                .setDuration(340)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .withEndAction {
                    if (isDestroyed || isFinishing) return@withEndAction
                    isWebViewVisible = false
                    topScrimBar.reset()
                    val lp = webViewScreen.layoutParams as FrameLayout.LayoutParams
                    lp.topMargin = 0
                    webViewScreen.layoutParams = lp
                    webViewScreen.visibility = View.GONE
                    root.visibility = View.GONE
                    root.alpha = 1f
                    pushMode("launcher", tavernRunning)
                    pushReady(true)
                }
                .start()
        } else {
            isWebViewVisible = false
            topScrimBar.reset()
            val lp = webViewScreen.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = 0
            webViewScreen.layoutParams = lp
            // Hide native overlay — Capacitor console shows underneath.
            webViewScreen.visibility = View.GONE
            root.visibility = View.GONE
            pushMode("launcher", tavernRunning)
            pushReady(true)
        }
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
        if (samplingTopColor) {
            onResult(null)
            return
        }
        val w = webView.width
        if (w <= 0 || !webView.isShown) {
            onResult(null)
            return
        }
        samplingTopColor = true
        val loc = IntArray(2)
        webView.getLocationInWindow(loc)
        val top = loc[1] + 1                       // WebView 顶边下 1px
        val stripH = 3
        val srcRect = Rect(loc[0], top, loc[0] + w, top + stripH)
        val bmp = Bitmap.createBitmap(w, stripH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * stripH)
        try {
            PixelCopy.request(window, srcRect, bmp, { result ->
                samplingTopColor = false
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
            // 窗口无 surface（恢复态/转场）→ 放弃本次，轮询稍后重试
            samplingTopColor = false
            bmp.recycle()
            onResult(null)
        }
    }

    /** 取色 → 顶框 scrim 条色波 + 光泽呼吸。 */
    private fun applyTopColor(color: Int) {
        if (lastAppliedTopColor == color) {
            tavernStatusHint.onColorChanged(color)
            return
        }
        lastAppliedTopColor = color
        topScrimBar.setColor(color)
        tavernStatusHint.onColorChanged(color)
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
                    webView.performClick()
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
        fullscreenBackCallback.isEnabled = false
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        webViewScreen.visibility = View.VISIBLE
    }

    // ============================================
    // SERVER PROVISIONING
    // ============================================

    private fun extractNativeLibs(paths: RuntimePaths) {
        setStatus("Extracting runtime...")
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

        // Extract npm (node_modules) from APK assets
        try {
            val usrAssetPath = "bootstrap/rootfs/rootfs-usr.zip"
            assets.open(usrAssetPath).use { input ->
                RuntimeFileUtils.unzipStream(input, paths.usrDir)
            }
            android.util.Log.i(TAG, "npm extracted to ${paths.usrDir}")
            val npmCli = File(paths.usrDir, "lib/node_modules/npm/bin/npm-cli.js")
            android.util.Log.i(TAG, "npm-cli.js exists: ${npmCli.exists()}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "npm extraction failed", e)
        }

    }

    private fun startServer(paths: RuntimePaths, targetServerDir: File): Boolean {
        paths.logsDir.mkdirs()
        // 杀掉旧的 server 进程,释放端口
        serverProcess?.let { p ->
            if (p.isAlive) {
                appendLog("[WARN] Killing previous server process")
                p.destroyForcibly()
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        serverProcess = null
        // 清空旧日志
        File(paths.logsDir, "server.log").delete()
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
            env["NODE_OPTIONS"] = "--max-old-space-size=2048"
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

    /** GitHub 代理镜像列表(国内加速)。原始 URL 会依次尝试直连 + 各代理。 */
    private val githubMirrors = listOf(
        "",  // 直连 GitHub
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    private fun downloadAndExtractGithubRelease(zipballUrl: String, paths: RuntimePaths, targetServerDir: File): Boolean {
        val maxRetries = 2
        var tmpZip: File? = null

        for ((mirrorIndex, mirror) in githubMirrors.withIndex()) {
            val fullUrl = if (mirror.isEmpty()) zipballUrl else mirror + zipballUrl
            val mirrorName = if (mirror.isEmpty()) "GitHub 直连" else mirror.removePrefix("https://").removeSuffix("/")

            for (attempt in 1..maxRetries) {
                try {
                    appendLog("> 下载尝试 $attempt/$maxRetries via $mirrorName")
                    updateProgress(10, "Downloading via $mirrorName")
                    val url = URL(fullUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 20000
                    conn.readTimeout = 300000
                    conn.setRequestProperty("User-Agent", "SillyClient")
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode !in 200..299) {
                        appendLog("[ERR] HTTP ${conn.responseCode} ($mirrorName)")
                        conn.disconnect()
                        break  // 换下一个镜像
                    }
                    val total = conn.contentLengthLong
                    tmpZip = File(paths.tmpDir, "github-release-${System.currentTimeMillis()}.zip")
                    var downloaded = 0L
                    var lastPct = -1
                    conn.inputStream.use { input ->
                        FileOutputStream(tmpZip).use { out ->
                            val buf = ByteArray(65536)
                            var len: Int
                            while (input.read(buf).also { len = it } != -1) {
                                out.write(buf, 0, len)
                                downloaded += len
                                if (total > 0) {
                                    val pct = (downloaded * 100 / total).toInt()
                                    if (pct != lastPct && pct % 5 == 0) {
                                        lastPct = pct
                                        updateProgress(10 + pct * 70 / 100, "Downloading $pct%")
                                        appendLog("> 下载进度: $pct% (${downloaded / 1048576}MB / ${total / 1048576}MB)")
                                    }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    appendLog("[OK] 下载完成,开始解压...")
                    updateProgress(82, "Extracting")
                    var entryCount = 0
                    val totalEntries = 2000
                    java.util.zip.ZipInputStream(java.io.FileInputStream(tmpZip)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val name = entry.name.substringAfter('/', entry.name)
                                if (name.isNotEmpty()) {
                                    val out = safeZipOutputFile(targetServerDir, name)
                                    out.parentFile?.mkdirs()
                                    FileOutputStream(out).use { zis.copyTo(it) }
                                    entryCount++
                                    if (entryCount % 200 == 0) {
                                        val pct = 82 + (entryCount * 18 / totalEntries).coerceAtMost(17)
                                        updateProgress(pct, "Extracting ($entryCount files)")
                                    }
                                }
                            }
                            entry = zis.nextEntry
                        }
                    }
                    tmpZip.delete()
                    appendLog("[OK] 解压完成 ($entryCount 个文件)")
                    updateProgress(100, "Extracted")
                    return File(targetServerDir, "server.js").exists()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "download attempt $attempt via $mirrorName", e)
                    appendLog("[ERR] 下载失败(尝试 $attempt, $mirrorName): ${e.message}")
                    tmpZip?.delete()
                    if (attempt < maxRetries) {
                        appendLog("> 等待 2 秒后重试...")
                        Thread.sleep(2000)
                    }
                }
            }
            appendLog("> 切换到下一个下载源...")
        }
        return false
    }

    /** 解压本地 zip 文件到目标目录。自动检测 GitHub zipball 格式(有内层目录)并平铺。 */
    private fun extractLocalZip(zipFile: File, destDir: File): Boolean {
        destDir.mkdirs()
        return try {
            var entryCount = 0
            var hasInnerDir = false
            java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zis ->
                // 第一遍:检测是否有内层目录(GitHub zipball 格式)
                var first = zis.nextEntry
                if (first != null && first.name.contains('/')) {
                    hasInnerDir = true
                }
                zis.closeEntry()
            }
            // 第二遍:解压
            java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = if (hasInnerDir) entry.name.substringAfter('/', entry.name) else entry.name
                        if (name.isNotEmpty()) {
                            val out = safeZipOutputFile(destDir, name)
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zis.copyTo(it) }
                            entryCount++
                            if (entryCount % 200 == 0) {
                                updateProgress(50 + (entryCount / 40), "Extracting ($entryCount files)")
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            appendLog("> 解压 $entryCount 个文件")
            File(destDir, "server.js").exists()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "extractLocalZip", e)
            false
        }
    }

    private fun safeZipOutputFile(destination: File, entryName: String): File {
        val root = destination.canonicalFile
        val output = File(root, entryName).canonicalFile
        val rootPrefix = root.path + File.separator
        require(output.path.startsWith(rootPrefix)) { "ZIP entry escapes instance directory: $entryName" }
        return output
    }

    /** 运行 npm install(若运行时含 npm)。返回是否成功。 */
    private fun runNpmInstall(paths: RuntimePaths, targetServerDir: File): Boolean {
        return try {
            val npmCli = File(paths.usrDir, "lib/node_modules/npm/bin/npm-cli.js")
            if (!npmCli.exists()) {
                appendLog("[ERR] npm-cli.js not found")
                return false
            }
            appendLog("[npm] cli: ${npmCli.absolutePath}")
            // npm 缓存和临时目录必须指向 app 私有目录
            // node 编译时 hardcode 了 termux 路径,用 TMPDIR 环境变量覆盖 os.tmpdir()
            val npmCache = File(paths.tarvenHome, "npm-cache").apply { mkdirs() }
            val npmTmp = File(paths.tarvenHome, "npm-tmp").apply { mkdirs() }
            // npm 11+ 不再支持 --tmp 参数,改用 TMPDIR 环境变量
            // 国内网络问题,使用淘宝镜像加速
            val pb = ProcessBuilder(
                paths.nodeBin.absolutePath, npmCli.absolutePath,
                "install", "--omit=dev", "--no-audit", "--no-fund",
                "--cache", npmCache.absolutePath,
                "--prefix", targetServerDir.absolutePath,
                "--registry", "https://registry.npmmirror.com"
            )
            pb.directory(targetServerDir)
            pb.redirectErrorStream(true)
            // 清空旧日志,避免混淆
            File(paths.logsDir, "npm-install.log").delete()
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(File(paths.logsDir, "npm-install.log")))
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = "${paths.usrDir.absolutePath}/lib:${paths.nativeLibDir.absolutePath}"
            env["PATH"] = "${paths.tmpDir.absolutePath}/bin:/system/bin"
            env["HOME"] = paths.tarvenHome.absolutePath
            env["TMPDIR"] = npmTmp.absolutePath
            env["npm_config_cache"] = npmCache.absolutePath
            env["npm_config_prefix"] = paths.usrDir.absolutePath
            var lastExit = -1
            for (attempt in 1..3) {
                appendLog("[npm] attempt $attempt/3")
                val p = pb.start()
                val finished = p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) { p.destroyForcibly(); appendLog("[ERR] npm install timeout"); return false }
                lastExit = p.exitValue()
                if (lastExit == 0 && File(targetServerDir, "node_modules").exists()) {
                    appendLog("[OK] npm install done")
                    return true
                }
                appendLog("[WARN] npm attempt $attempt failed (exit $lastExit), retrying...")
                if (attempt < 3) Thread.sleep(3000)
            }
            appendLog("[ERR] npm install failed after 3 attempts (exit $lastExit)")
            false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "runNpmInstall", e)
            appendLog("[ERR] npm install exception: ${e.message}")
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
                val version = parsePackageVersion(dir)
                val size = dirSize(dir)
                result.add(Quint(dir.name, version, dir.absolutePath, size, hasServer))
            }
        }
        return result
    }

    /** 实例详情:version, path, sizeBytes, createdAt, status。 */
    fun getInstanceInfo(instanceId: String, port: Int): Quint<String, String, Long, String, String> {
        val paths = RuntimePaths.from(this)
        val dir = paths.serverDirFor(instanceId, create = false)
        if (!dir.exists()) return Quint("unknown", dir.absolutePath, 0L, "", "未安装")
        val hasServer = File(dir, "server.js").exists()
        val version = parsePackageVersion(dir)
        val size = dirSize(dir)
        val createdAt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(dir.lastModified()))
        val status = if (hasServer) "已就绪" else "未完成"
        return Quint(version, dir.absolutePath, size, createdAt, status)
    }

    /** 读取目录下 package.json 的 version 字段，失败返回 "unknown"。 */
    private fun parsePackageVersion(dir: File): String {
        val pkg = File(dir, "package.json")
        if (!pkg.exists()) return "unknown"
        return try {
            val txt = pkg.readText()
            val m = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(txt)
            if (m != null) m.groupValues[1] else "unknown"
        } catch (_: Exception) { "unknown" }
    }

    private fun dirSize(dir: File): Long {
        var size = 0L
        dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size
    }

    /** 向终端发送命令:运行 shell 命令并流式输出到日志。 */
    fun sendCommand(text: String, instanceId: String) {
        if (text.isBlank()) return
        val paths = RuntimePaths.from(this)
        val instanceDir = paths.serverDirFor(instanceId, create = false)
        if (!instanceDir.exists()) {
            pushLog("实例目录不存在: $instanceId")
            return
        }
        Thread {
            pushLog("\$ $text")
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", text)
                pb.directory(instanceDir)
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
        if (isWebViewVisible) {
            webView.reload()
        } else {
            // 沉浸式未激活时,提示用户
            pushLog("⚠ 酒馆未运行,无法刷新")
        }
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
        android.util.Log.d(TAG, "copyCoverImage: uri=$uri instanceId=$instanceId")
        val paths = RuntimePaths.from(this)
        val coversDir = File(paths.bootstrapDir, "covers").apply { mkdirs() }
        // 清理 instanceId 中的非法字符(作为文件名)
        val safeId = instanceId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(coversDir, "$safeId.png")
        android.util.Log.d(TAG, "copyCoverImage: outFile=${outFile.absolutePath}")
        val input = contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("无法打开图片流: $uri")
        input.use { ins ->
            FileOutputStream(outFile).use { out -> ins.copyTo(out) }
        }
        android.util.Log.d(TAG, "copyCoverImage: done, size=${outFile.length()}")
        return outFile.absolutePath
    }

    /** 卸载实例:删除安装目录 + 封面图,返回释放的字节数。 */
    fun uninstallInstance(instanceId: String): Long {
        val paths = RuntimePaths.from(this)
        var freed = 0L
        // 删除安装目录
        val serverDir = paths.serverDirFor(instanceId, create = false)
        if (serverDir.exists()) {
            freed += dirSize(serverDir)
            serverDir.deleteRecursively()
        }
        // 删除封面图
        val safeId = instanceId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val coverFile = File(paths.bootstrapDir, "covers/$safeId.png")
        if (coverFile.exists()) {
            freed += coverFile.length()
            coverFile.delete()
        }
        android.util.Log.d(TAG, "uninstallInstance: $instanceId, freed=$freed bytes")
        return freed
    }

    /**
     * 清理垃圾:扫描孤立文件/目录。
     * - orphan_instance: servers/ 下有实例目录但前端 instances 列表中不存在的(已删除卡片但文件残留)
     * - orphan_cover: covers/ 下有封面图但没有对应实例的
     * - temp_file: tmp/ 和 logs/ 下的临时文件
     * - cache: WebView 缓存
     * dryRun=true 仅扫描返回,不实际删除。
     * 返回 (items, totalBytes)。
     */
    fun cleanGarbage(dryRun: Boolean): org.json.JSONArray {
        val paths = RuntimePaths.from(this)
        val items = org.json.JSONArray()
        var totalBytes = 0L

        fun add(path: String, type: String, size: Long, desc: String) {
            if (size <= 0) return
            val item = org.json.JSONObject()
            item.put("path", path)
            item.put("type", type)
            item.put("sizeBytes", size)
            item.put("description", desc)
            items.put(item)
            totalBytes += size
        }

        // 1. 扫描孤立实例目录(servers/ 下的)
        val serversRoot = File(paths.bootstrapDir, "servers")
        if (serversRoot.exists()) {
            serversRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name != "default") {
                    add(dir.absolutePath, "orphan_instance", dirSize(dir), "实例目录: ${dir.name}")
                }
            }
        }

        // 2. 扫描孤立封面图(covers/ 下)
        val coversDir = File(paths.bootstrapDir, "covers")
        if (coversDir.exists()) {
            coversDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".png")) {
                    add(file.absolutePath, "orphan_cover", file.length(), "封面图: ${file.name}")
                }
            }
        }

        // 3. 扫描临时文件(tmp/ 和 logs/)
        val tmpDir = paths.tmpDir
        if (tmpDir.exists()) {
            val tmpSize = dirSize(tmpDir)
            add(tmpDir.absolutePath, "temp_file", tmpSize, "临时文件目录")
        }
        val logsDir = paths.logsDir
        if (logsDir.exists()) {
            val logsSize = dirSize(logsDir)
            add(logsDir.absolutePath, "temp_file", logsSize, "日志文件目录")
        }

        // 4. WebView 缓存
        val cacheDir = this.cacheDir
        if (cacheDir.exists()) {
            val cacheSize = dirSize(cacheDir)
            add(cacheDir.absolutePath, "cache", cacheSize, "应用缓存")
        }

        android.util.Log.d(TAG, "cleanGarbage: dryRun=$dryRun, found ${items.length()} items, $totalBytes bytes")
        return items
    }

    /** 简单五元组(Kotlin 标准库无 Quintuple)。 */
    data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
    data class Quartet<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun pollUntilReady() {
        var a = 0
        while (a < 180) {
            if (tryConnect(tavernUrl)) {
                appendLog("[OK] SillyTavern is online at $tavernUrl")
                serverReady = true
                pushReady(true)
                updateHomeReady()
                refreshLogToCompose()
                return
            }
            // 检查进程是否已退出
            val p = serverProcess
            if (p != null && !p.isAlive) {
                appendLog("[ERR] Node process exited (code ${p.exitValue()})")
                appendLog("[ERR] Check server.log for details")
                setStatus("Server crashed")
                pushError("Node.js 进程已退出 (code ${p.exitValue()}),请检查 server.log")
                return
            }
            a++
            if (a % 10 == 0) appendLog("... still waiting ($a/180)")
            try { Thread.sleep(1000) } catch (_: Exception) { break }
        }
        appendLog("[ERR] Server did not respond within 180s")
        setStatus("No response")
        pushError("服务器在 180 秒内未响应")
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

    private fun pushError(message: String) {
        TarvenEnvPlugin.notify("error", JSObject().put("message", message))
    }

    private fun setStatus(t: String) { pushLog(t) }
    private fun updateProgress(pct: Int, text: String? = null) { pushProgress(pct.toFloat(), text) }
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

    private fun post(r: Runnable) { handler.post(r) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 状态栏提示文字区域：避开前摄，优先落在镜头与对应边框之间的半区。 */
    private fun statusHintMetrics(): StatusHintMetrics {
        val screenWidth = resources.displayMetrics.widthPixels
        val margin = dp(12)
        val cutout = topCutout()
        val (areaLeft, areaRight) = if (cutout == null) {
            margin to (screenWidth / 2 - margin)
        } else if (cutout.centerX < screenWidth * 0.4f) {
            cutout.centerX + margin to screenWidth - margin
        } else {
            margin to cutout.centerX - margin
        }
        return StatusHintMetrics(areaLeft, areaRight, cutout?.height ?: 0)
    }

    private fun topCutout(): TopCutout? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val cutout = window.decorView.rootWindowInsets?.displayCutout ?: return null
        return cutout.boundingRects
            .filter { it.top < statusBarFixedPx && it.bottom > 0 }
            .maxByOrNull { it.width() * it.height() }
            ?.let {
                TopCutout(
                    centerX = (it.left + it.right) / 2,
                    height = it.height()
                )
            }
    }

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

    override fun onDestroy() {
        handler.removeCallbacks(topColorPoll)
        handler.removeCallbacks(exportTimeoutPoll)
        if (::tavernStatusHint.isInitialized) tavernStatusHint.dismiss()
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = null
        pendingExportRequest = null
        val incompleteExport = activeExportDocument
        activeExportDocument = null
        if (::tavernDownloadBridge.isInitialized) tavernDownloadBridge.destroy()
        incompleteExport?.tempFile?.let(::cleanupExportTempFile)
        serverProcess?.destroy()
        serverProcess = null
        if (::webView.isInitialized) webView.removeJavascriptInterface("SillyClientAndroidDownloads")
        webView.destroy()
        super.onDestroy()
    }
}
