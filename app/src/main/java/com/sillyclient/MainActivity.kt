package com.sillyclient

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import com.sillyclient.render.ChameleonController
import com.sillyclient.render.HapticController
import com.sillyclient.render.RenderEngineManager
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
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
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.graphics.Insets
import androidx.documentfile.provider.DocumentFile
import com.getcapacitor.JSObject
import com.sillyclient.runtime.InstanceDataImport
import com.sillyclient.runtime.CompanionPresetInstaller
import com.sillyclient.runtime.CompanionPresetRequest
import com.sillyclient.runtime.CompanionPresetTransaction
import com.sillyclient.runtime.RuntimePaths
import com.sillyclient.runtime.RuntimeFileUtils
import com.sillyclient.runtime.SourceArchiveCache
import com.sillyclient.runtime.CleanupService
import com.sillyclient.runtime.InstanceRepository
import com.sillyclient.runtime.DependencyBank
import com.sillyclient.runtime.KeepAlive
import com.sillyclient.runtime.InstanceRemoval
import com.sillyclient.runtime.WebpackCacheSeed
import com.sillyclient.runtime.InstanceMaintenance
import com.sillyclient.runtime.InstanceInstaller
import com.sillyclient.runtime.DependencyInstaller
import com.sillyclient.runtime.DependencyArchive
import com.sillyclient.runtime.NativeTreeRemoval
import com.sillyclient.runtime.InstanceRelocation
import com.sillyclient.runtime.InstanceRename
import com.sillyclient.runtime.SourceDownloader
import com.sillyclient.runtime.BundledDependencyArchives
import com.sillyclient.runtime.BundledRuntime
import com.sillyclient.runtime.BundledTavernSource
import com.sillyclient.runtime.LogService
import com.sillyclient.runtime.ManagedFiles
import com.sillyclient.runtime.OperationCoordinator
import com.sillyclient.runtime.ProcessSupervisor
import com.sillyclient.runtime.PreinstalledExtensionInstaller
import com.sillyclient.runtime.PreinstalledExtensionsRequest
import com.sillyclient.runtime.PreinstalledExtensionsTransaction
import com.sillyclient.runtime.RuntimeConfiguration
import com.sillyclient.runtime.TavernReadiness
import com.sillyclient.download.TavernDownloadBridge
import com.sillyclient.download.TavernDownloadFiles
import com.sillyclient.download.TavernDownloadRequest
import com.sillyclient.download.TavernDownloadScript
import com.sillyclient.download.TavernDownloadTerminalEvent
import com.sillyclient.ui.TavernStatusHint
import com.sillyclient.ui.TopScrimBar
import com.sillyclient.navigation.ExternalNavigationPolicy
import com.sillyclient.navigation.ExternalPopupHandler
import com.sillyclient.navigation.TavernPageSession
import com.sillyclient.navigation.TavernResourcePolicy
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import com.sillyclient.runtime.TavernStaticGateway

class MainActivity : BridgeActivity() {

    private val bundledRuntime by lazy {
        BundledRuntime(RuntimePaths.from(this),
            packageManager.getPackageInfo(packageName, 0).let { "${it.versionName}:${it.lastUpdateTime}" },
            diagnostic = ::runtimeDiagnostic,
            openAsset = assets::open)
    }

    private val bundledArchives by lazy {
        val rootPaths = RuntimePaths.from(this)
        BundledDependencyArchives(
            archiveDir = File(rootPaths.tarvenHome, "dependency-archives"),
            openAsset = assets::open,
            listAssets = { dir -> assets.list(dir)?.toSet() ?: emptySet() })
    }

    val bundledTavernSource by lazy {
        BundledTavernSource(openAsset = assets::open, diagnostic = ::runtimeDiagnostic)
    }

    val runtimePaths: RuntimePaths get() = RuntimePaths.from(this)

    private val tavernStaticGateway by lazy {
        TavernStaticGateway { resolveActiveServerDir() }
    }

    private fun resolveActiveServerDir(): File? {
        val paths = RuntimePaths.from(this)
        val id = currentTavernInstanceId?.takeIf { it.isNotBlank() } ?: "default"
        val dir = paths.serverDirFor(id, create = false)
        return if (dir.exists()) dir else null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val topColorPoll: Runnable = Runnable {
        if (isWebViewVisible) {
            sampleTopColor { c ->
                if (c != null) applyTopColor(c)
            }
        }
    }

    // ---- Render & UX Controllers ----
    private lateinit var hapticController: HapticController
    private lateinit var chameleonController: ChameleonController
    private lateinit var renderEngineManager: RenderEngineManager

    // ---- Views ----
    private lateinit var root: FrameLayout
    private lateinit var topScrimBar: TopScrimBar     // 酒馆顶框 scrim 条（渐变+光泽+色波）
    private lateinit var tavernStatusHint: TavernStatusHint
    private lateinit var webViewScreen: FrameLayout
    private lateinit var webView: WebView
    private val tavernPageSession = TavernPageSession()
    private var tavernDocumentGeneration = 0L
    private var tavernDocumentFailed = false
    private var tavernConsoleErrors = 0
    private val externalPopups by lazy {
        ExternalPopupHandler(this, handler, ::openNavigationExternalUrl, ::requestTavernUrlDownload) { root }
    }

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
    @Volatile private var serverReady = false
    @Volatile private var isWebViewVisible = false
    private var statusBarFixedPx = 0  // fixed physical pixels, never changes
    // 启动器支持多实例:目标 URL 与端口由前端实例数据决定,不再硬编码 8000
    @Volatile private var tavernUrl = "http://127.0.0.1:8000/"
    @Volatile private var tavernPort = 8000
    @Volatile private var currentTavernInstanceId: String? = null
        set(value) {
            field = value
            if (::chameleonController.isInitialized) {
                chameleonController.setInstanceId(value)
            }
        }
    private var tavernAuthHost: String? = null
    private var tavernAuthUsername: String? = null
    private var tavernAuthPassword: String? = null
    /** 当前 Node 服务进程(用于终端 stdin 输入)。 */
    @Volatile private var serverProcess: Process? = null
    private val operations = OperationCoordinator()
    private val processSupervisor = ProcessSupervisor(operations)
    private val instanceRepository by lazy {
        val paths = RuntimePaths.from(this)
        InstanceRepository(paths.serversDir, installLocations = paths.installLocations, legacyServersRoot = paths.legacyServersDir)
    }
    private val instanceMaintenance by lazy {
        val paths = RuntimePaths.from(this)
        InstanceMaintenance(
            paths.serversDir,
            isRuntimeBusy = {
                val context = operations.context()
                serverReady || isWebViewVisible || processSupervisor.hasProcesses() ||
                    (context != null && !operations.isCurrent(context)) ||
                    (operations.hasPendingWork() && operations.current() !== context)
            },
            validateStandardDataRoot = { directory ->
                val operation = operations.context()
                    ?: throw IllegalStateException("Missing maintenance operation")
                RuntimeConfiguration(paths, operations, processSupervisor)
                    .validateStandardDataRoot(directory, operation)
            },
            commitMutation = { _, action ->
                val operation = operations.context()
                    ?: throw IllegalStateException("Missing maintenance operation")
                operations.commit(operation, action)
            },
            minimumCacheAgeMillis = 0L,
            installLocations = paths.installLocations
        )
    }
    private val cleanupService by lazy {
        val paths = RuntimePaths.from(this)
        CleanupService(
            paths.serversDir,
            File(paths.bootstrapDir, "covers"),
            listOf(paths.tmpDir, File(cacheDir, "sillyclient-tmp")),
            paths.logsDir,
            { operations.hasPendingWork() || processSupervisor.hasProcesses() || serverReady },
            installLocations = paths.installLocations
        )
    }
    /** 酒馆 WebView 下拉刷新开关（通过 SharedPreferences 持久化）。 */
    private var pullToRefreshEnabled: Boolean
        get() = getSharedPreferences("sc_prefs", Context.MODE_PRIVATE).getBoolean("pull_to_refresh", false)
        set(value) {
            getSharedPreferences("sc_prefs", Context.MODE_PRIVATE).edit().putBoolean("pull_to_refresh", value).apply()
        }
    /** 开发者彩蛋：右上角连续点击 7 次切换 SC Performance HUD */
    private var perfEasterEggCount = 0
    private var lastPerfTapTime = 0L

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
        private const val RETIRED_MODULES_NAME = ".sillyclient-retired-modules"
        private val DEPENDENCY_ARCHIVE_NAME = Regex("^([0-9a-f]{64})-([0-9a-f]{64})[.]tar$")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // ---- Capacitor: register plugin BEFORE super so BridgeActivity picks it up ----
        registerPlugin(TarvenEnvPlugin::class.java)
        super.onCreate(savedInstanceState)
        bundledRuntime.prepareAsync()
        bundledArchives.prepareAsync()
        onBackPressedDispatcher.addCallback(this, fullscreenBackCallback)

        // Crash recovery: finish deleting retained relocation sources whose owning
        // instance is already registered elsewhere. Delayed so it never competes
        // with the startup extraction or an instance launch already in flight.
        Thread {
            runCatching { Thread.sleep(45_000) }
            runCatching {
                InstanceRelocation(RuntimePaths.from(this), operations, processSupervisor)
                    .sweepRetainedSources { runtimeDiagnostic(it) }
            }.onFailure { runtimeDiagnostic("relocat.sweep_failed ${it.message?.take(160)}") }
        }.apply {
            name = "SC-relocation-sweep"
            isDaemon = true
        }.start()

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

        // 硬件加速与高刷新率 (90Hz / 120Hz / 144Hz) 驱动
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        try {
            WebView.setWebContentsDebuggingEnabled(true)
        } catch (_: Exception) {}
        var maxDeviceRefreshRate = 60f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
                val maxRefreshMode = disp?.supportedModes?.maxByOrNull { it.refreshRate }
                if (maxRefreshMode != null && maxRefreshMode.refreshRate > 60f) {
                    maxDeviceRefreshRate = maxRefreshMode.refreshRate
                    val lp = window.attributes
                    lp.preferredDisplayModeId = maxRefreshMode.modeId
                    window.attributes = lp
                    if (Build.VERSION.SDK_INT >= 31) {
                        try {
                            val method = View::class.java.getMethod(
                                "setFrameRate",
                                Float::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType
                            )
                            method.invoke(window.decorView, maxRefreshMode.refreshRate, 0)
                        } catch (_: Throwable) {}
                    }
                    android.util.Log.i(TAG, "Configured high refresh rate mode: ${maxRefreshMode.refreshRate}Hz")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Unable to request high refresh rate mode", e)
            }
        }

        // 启动器 WebView (Capacitor Bridge) 硬件加速与高刷锁定
        try {
            bridge?.webView?.let { lwv ->
                lwv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                lwv.overScrollMode = View.OVER_SCROLL_NEVER
                lwv.isVerticalScrollBarEnabled = false
                lwv.isHorizontalScrollBarEnabled = false
                if (Build.VERSION.SDK_INT >= 31 && maxDeviceRefreshRate > 60f) {
                    try {
                        val method = View::class.java.getMethod(
                            "setFrameRate",
                            Float::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                        method.invoke(lwv, maxDeviceRefreshRate, 0)
                    } catch (_: Throwable) {}
                }
                @Suppress("DEPRECATION")
                lwv.settings.apply {
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        offscreenPreRaster = false
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Unable to configure launcher webView", e)
        }

        statusBarFixedPx = readStatusBarFixedPx()

        // iOS 同款 Full Bleed 架构：开启沉浸式透明导航栏，消除死黑条，让毛玻璃背景 100% 满版贴底
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

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

        hapticController = HapticController(this)
        chameleonController = ChameleonController(
            context = this,
            window = window,
            handler = handler,
            topScrimBar = topScrimBar,
            getFixedStatusBarPx = { statusBarFixedPx },
            isWebViewVisible = { isWebViewVisible },
            isPullToRefreshEnabled = { pullToRefreshEnabled }
        )
        chameleonController.setInstanceId(currentTavernInstanceId)
        renderEngineManager = RenderEngineManager(
            context = this,
            hapticController = hapticController,
            onColorChangedListener = { color ->
                runOnUiThread {
                    if (isWebViewVisible) {
                        applyTopColor(color)
                    }
                }
            }
        )

        // 顶部状态栏手势区：透明 View 覆盖 statusBarFixedPx 条带
        // 始终可见（不放在 root 里），支持双向操作：
        //   酒馆模式：滑动 → exitTavern()（回启动器，不停服务）
        //   启动器模式：滑动 → returnToTavern()（回酒馆，如果还在跑）
        topGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 点击顶部状态栏/变色龙区域：立即触发硬件加速白色光波！
                topScrimBar.sweepGloss()
                if (isWebViewVisible) {
                    sampleTopColor { c -> if (c != null) applyTopColor(c) }
                }

                // 开发者模式彩蛋：右上角变色龙区域连续点击 7 次切换 SC Performance HUD
                val screenWidth = resources.displayMetrics.widthPixels
                if (e.x > screenWidth * 0.60f) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastPerfTapTime > 1800) {
                        perfEasterEggCount = 0
                    }
                    lastPerfTapTime = now
                    perfEasterEggCount++
                    if (perfEasterEggCount in 1..6) {
                        triggerHaptic("tick")
                    }
                    if (perfEasterEggCount in 4..6) {
                        val remaining = 7 - perfEasterEggCount
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "再点击 ${remaining} 次开启性能监控",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else if (perfEasterEggCount >= 7) {
                        perfEasterEggCount = 0
                        triggerHaptic("click")
                        if (isWebViewVisible) {
                            togglePerformanceMonitor()
                        } else {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "⚡ 性能监控引擎已激活，进入酒馆时将自动常驻",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                return true
            }

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
                if (event.action == MotionEvent.ACTION_UP) {
                    view.performClick()
                }
                topGestureDetector.onTouchEvent(event)
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
            setLayerType(View.LAYER_TYPE_NONE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
            overScrollMode = View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            settings.javaScriptEnabled = true
            settings.setSupportMultipleWindows(true)
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION")
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            // 文件选择器返回 content:// URI。保持 file:// 关闭，允许 WebView 读取用户明确选择的内容。
            settings.allowContentAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setNeedInitialFocus(false)
            settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL
            settings.textZoom = 100
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                settings.offscreenPreRaster = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(tavernDownloadBridge, "SillyClientAndroidDownloads")
            renderEngineManager.attachBridges(this)
            setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
                requestTavernUrlDownload(url, contentDisposition, mimeType, contentLength)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request == null) return false
                    val url = request.url.toString()
                    return when (ExternalNavigationPolicy.navigation(tavernUrl, url, request.isForMainFrame)) {
                        ExternalNavigationPolicy.Decision.INTERNAL -> false
                        ExternalNavigationPolicy.Decision.EXTERNAL -> {
                            openNavigationExternalUrl(url)
                            true
                        }
                        ExternalNavigationPolicy.Decision.BLOCK -> true
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    tavernDownloadBridge.invalidateSession()
                    tavernDocumentGeneration++
                    tavernDocumentFailed = false
                    tavernConsoleErrors = 0
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    android.util.Log.i(TAG, "Tavern main frame committed")
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    android.util.Log.e(TAG, "Tavern resource error url=${request?.url} isMain=${request?.isForMainFrame} code=${error?.errorCode} desc=${error?.description}")
                    if (request?.isForMainFrame == true) reportTavernDocumentFailure("network", error?.errorCode)
                }

                override fun onReceivedHttpError(
                    view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, response)
                    android.util.Log.e(TAG, "Tavern HTTP error url=${request?.url} isMain=${request?.isForMainFrame} status=${response?.statusCode}")
                    if (request?.isForMainFrame == true) reportTavernDocumentFailure("http", response?.statusCode)
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

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request != null && TavernResourcePolicy.allows(
                        tavernUrl, request.url.toString(), request.method, request.isForMainFrame,
                        serverReady && serverProcess?.isAlive == true
                    )) tavernStaticGateway.shouldInterceptRequest(request)?.let { return it }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(v: WebView?, url: String?) {
                    super.onPageFinished(v, url)
                    android.util.Log.i(TAG, "Tavern load finished; not a render-success signal")
                    installTavernDownloadSupport(url)
                    installChameleonProbes()
                    injectRenderEngine()
                    injectMobileLayoutOptimizations()
                    recordTavernDocumentDiagnostics(url)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    if (message != null) {
                        val level = message.messageLevel()
                        val text = "${message.message()} [${message.sourceId()}:${message.lineNumber()}]"
                        when (level) {
                            ConsoleMessage.MessageLevel.ERROR -> android.util.Log.e(TAG, "Tavern JS error: $text")
                            ConsoleMessage.MessageLevel.WARNING -> android.util.Log.w(TAG, "Tavern JS warn: $text")
                            else -> android.util.Log.d(TAG, "Tavern JS console: $text")
                        }
                    }
                    return super.onConsoleMessage(message)
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    if (resultMsg == null || !isUserGesture) return false
                    val instanceId = currentTavernInstanceId
                    val instanceUrl = tavernUrl
                    return externalPopups.createWindow(resultMsg) {
                        !isFinishing && !isDestroyed && isWebViewVisible &&
                            currentTavernInstanceId == instanceId && tavernUrl == instanceUrl
                    }
                }

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
        chameleonController.setStatusHint(tavernStatusHint)

        // IME 零重排零延迟适配（TT 同款架构）：
        // 废除 webViewScreen.setPadding(...)，绝不改变 WebView 物理高宽，彻底消灭 Viewport Resize 全局重排！
        // 原生直接将软键盘高度转换为 CSS 像素，驱动 GPU 硬件加速的局部 translate3d 位移。
        ViewCompat.setWindowInsetsAnimationCallback(
            webViewScreen,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {}

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                        val rootInsets = ViewCompat.getRootWindowInsets(webViewScreen)
                        val navHeight = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
                        val targetHeight = bounds.upperBound.bottom
                        val imeVisible = rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) ?: (targetHeight > 0)
                        dispatchImeOffset(if (imeVisible) targetHeight else 0, navHeight)
                    }
                    return super.onStart(animation, bounds)
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    val imeType = WindowInsetsCompat.Type.ime()
                    val navHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    val imeHeight = insets.getInsets(imeType).bottom
                    val isImeVisible = insets.isVisible(imeType)
                    dispatchImeOffset(if (isImeVisible) imeHeight else 0, navHeight)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                        val rootInsets = ViewCompat.getRootWindowInsets(webViewScreen)
                        val navHeight = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
                        val imeVisible = rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
                        val imeHeight = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                        dispatchImeOffset(if (imeVisible) imeHeight else 0, navHeight)
                    }
                }
            }
        )

        ViewCompat.setOnApplyWindowInsetsListener(webViewScreen) { _, insets ->
            val navHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            dispatchImeOffset(if (imeVisible) imeHeight else 0, navHeight)
            insets
        }

        // ---- Hybrid UI host owns the web dashboard + console + bridge ----

        // Restore or init —— 启动器语义:不自动 provision,由前端选择实例后通过插件触发。
        val canRestoreReady = wasServerReady && !isLocalUrl(tavernUrl)
        if (wasWebViewVisible && canRestoreReady) {
            serverReady = true
            // 镜像 enterTavern 的布局：WebView 下移 statusBarFixedPx，露出顶条带
            val h = statusBarFixedPx
            val lp = webViewScreen.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = h
            webViewScreen.layoutParams = lp
            tavernPageSession.ensureLoaded(currentTavernInstanceId, tavernUrl, webView.url, loadUrl = webView::loadUrl)
            handler.post {
                switchToWebView(false)
                enterImmersive()
                currentTavernInstanceId?.let { tavernStatusHint.show(it) }
            }
            setStatus("Ready")
            pushReady(true)
        } else if (canRestoreReady) {
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
        // 系统前台切回心跳自愈探针
        resumeHeartbeatHeal()
        if (isWebViewVisible && ::webView.isInitialized) {
            renderEngineManager.forceChameleonSample(webView)
        }
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
    fun getRunningInstanceId(): String? = currentTavernInstanceId.takeIf { serverReady }
    fun getRunningOperationId(): String? = operations.current()?.takeIf {
        serverReady && it.instanceId == currentTavernInstanceId && serverProcess?.isAlive == true
    }?.operationId

    fun provisionAndStart(
        port: Int = 8000,
        instanceId: String = "default",
        version: String = "stable",
        config: InstanceConfig = InstanceConfig(),
        zipballUrl: String? = null,
        localZipPath: String? = null,
        companionPreset: CompanionPresetRequest? = null,
        operationId: String? = null,
        preinstall: PreinstalledExtensionsRequest? = null,
        installPath: String? = null,
        installPathMode: String = "exact",
        instanceName: String? = null
    ) {
        require(port in 1..65535) { "Invalid instance port" }
        require(config.ipv4 || config.ipv6) { "请至少启用 IPv4 或 IPv6" }
        require(config.heartbeat >= 0) { "Invalid heartbeat interval" }
        val paths = RuntimePaths.from(this)
        val id = RuntimePaths.normalizeInstanceId(instanceId)
        val targetServerDir = paths.launchDirectoryFor(id, installPath, installPathMode, instanceName)
        val operation = operations.begin(id, operationId)
        val previousStop = processSupervisor.stopAllAsync()
        cleanupService.invalidate()
        serverProcess = null
        serverReady = false
        tavernPort = port
        tavernUrl = "http://${if (config.ipv4) "127.0.0.1" else "[::1]"}:$port/"
        currentTavernInstanceId = id
        clearTavernBasicAuth()
        KeepAlive.acquire(this)
        operations.execute(operation) {
            val started = System.nanoTime()
            val storage = if (targetServerDir.absoluteFile.toPath().startsWith(paths.appFilesDir.absoluteFile.toPath()))
                "private" else "external"
            runtimeDiagnostic("provision.begin instance=$id storage=$storage")
            var presetTransaction: CompanionPresetTransaction? = null
            var extensionsTransaction: PreinstalledExtensionsTransaction? = null
            var launched: Process? = null
            fun rollbackPresets() {
                runCatching { presetTransaction?.rollback() }.onFailure { appendLog("[WARN] 主题预设回滚失败，已有文件保留") }
                runCatching { extensionsTransaction?.rollback() }.onFailure { appendLog("[WARN] 扩展回滚未完成，已有文件保留") }
            }
            try {
                previousStop.get(5, TimeUnit.SECONDS)
                operations.ensureCurrent(operation)
                paths.ensureDirs()
                ensureRuntimeReady()
                instanceInstaller(paths).prepare(
                        targetServerDir,
                        ensureActive = { operations.ensureCurrent(operation) },
                        extract = { directory ->
                            appendLog("> Provisioning [$instanceId]...")
                            updateProgress(2, "Initializing")
                            if (localZipPath != null) {
                                updateProgress(50, "Extracting local zip")
                                extractLocalZip(File(localZipPath), directory)
                            } else if (bundledTavernSource.matchesRequestedVersion(version, zipballUrl)) {
                                updateProgress(50, "Extracting bundled source")
                                appendLog("> 使用内置源码包，无需下载...")
                                extractBundledRelease(paths, directory)
                            } else {
                                val sourceUrl = zipballUrl ?: if (version in setOf("stable", "release")) {
                                    com.sillyclient.runtime.TavernReleaseCatalog.STABLE_BRANCH_URL
                                } else error("所选版本没有可用下载地址，请刷新版本列表或选择本地 ZIP")
                                appendLog("> Downloading $version source from GitHub...")
                                downloadAndExtractGithubRelease(sourceUrl, paths, directory)
                            }
                        },
                        installDependencies = { directory ->
                            if (localZipPath == null &&
                                bundledTavernSource.matchesRequestedVersion(version, zipballUrl)) {
                                // Bundled source: one shared tree serves every instance
                                // from <root>/node_modules via standard Node resolution.
                                updateProgress(85, "正在准备共享依赖组件（仅首次）")
                                ensureDependencyBank(paths, { operations.ensureCurrent(operation) },
                                    manifestDirectory = directory)
                                true
                            } else {
                                updateProgress(85, "正在准备实例依赖")
                                runNpmInstall(paths, directory) { restored ->
                                    updateProgress(85, "正在恢复依赖组件 · $restored")
                                }
                            }
                        },
                        commit = { action -> operations.commit(operation, action) },
                        instanceId = id
                    )
                instanceRepository.invalidate(targetServerDir)
                val runtimeConfiguration = RuntimeConfiguration(paths, operations, processSupervisor)
                if (companionPreset != null || preinstall?.extensionIds?.isNotEmpty() == true) {
                    runtimeConfiguration.validateStandardDataRoot(targetServerDir, operation)
                }
                if (preinstall != null) {
                    updateProgress(92, "Installing selected extensions")
                    extensionsTransaction = PreinstalledExtensionInstaller.install(
                        this, targetServerDir, preinstall, operations, operation, ::appendLog
                    )
                }
                if (companionPreset != null) {
                    updateProgress(94, "Applying theme preset")
                    operations.ensureCurrent(operation)
                    presetTransaction = CompanionPresetInstaller.install(this, targetServerDir, companionPreset)
                    operations.ensureCurrent(operation)
                    appendLog("[OK] SC Bordeaux 主题预设已就绪")
                }
                updateProgress(97, "Starting server")
                ensureBankForLaunch(paths, targetServerDir, operation)
                bundledDependencyArchive(paths) { operations.ensureCurrent(operation) }?.let { (key, _) ->
                    if (webpackCacheSeed(paths).seedInstance(targetServerDir, key) { operations.ensureCurrent(operation) }) {
                        runtimeDiagnostic("webpack.seeded key=${key.take(12)}")
                    }
                }
                launched = startServer(paths, targetServerDir, port, config, operation)
                check(launched != null) { "Node.js 服务启动失败，请检查实例完整性" }
                appendLog("[OK] Node.js process launched")
                updateProgress(99, "Waiting for server")
                if (pollUntilReady(tavernUrl, launched, operation)) {
                    // The first successful start compiled the frontend libraries;
                    // harvest that cache so later instances start warm.
                    bundledDependencyArchive(paths) { operations.ensureCurrent(operation) }?.let { (key, _) ->
                        webpackCacheSeed(paths).harvestInBackground(targetServerDir, key)
                    }
                    operations.commit(operation) {
                        presetTransaction?.commit()
                        extensionsTransaction?.commit()
                    }
                    // Archive after the server is ready so the heavy tree copy cannot
                    // compete with Node's cold-start dependency reads.
                    archiveDependenciesInBackground(paths, targetServerDir)

                } else {
                    runtimeDiagnostic("server.not_ready alive=${launched?.isAlive == true}")
                    rollbackPresets()
                    launched?.let(processSupervisor::stopAsync)
                    operations.commit(operation) { if (serverProcess === launched) serverProcess = null }
                }
            } catch (_: CancellationException) {
                rollbackPresets()
                launched?.let(processSupervisor::stopAsync)
            } catch (_: InterruptedException) {
                rollbackPresets()
                launched?.let(processSupervisor::stopAsync)
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                rollbackPresets()
                launched?.let(processSupervisor::stopAsync)
                runtimeDiagnostic("provision.failed instance=$id type=${error.javaClass.simpleName} msg=${error.message?.take(200)}")
                if (operations.isCurrent(operation)) {
                    operations.commit(operation) { serverReady = false; if (serverProcess === launched) serverProcess = null }
                    pushError(error.message ?: "安装失败")
                }
            } finally {
                runtimeDiagnostic("provision.end instance=$id elapsedMs=${(System.nanoTime() - started) / 1_000_000} ready=$serverReady")
                KeepAlive.release(this)
            }
        }
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
        targetUrl?.let { ExternalNavigationPolicy.externalUrl(it) }
        if (targetUrl != null && instanceId.isNullOrBlank()) {
            openExternalUrl(targetUrl)
            return true
        }
        android.util.Log.i(
            TAG,
            "enterTavern instanceId=$instanceId showGestureHint=$showGestureHint current=${currentTavernInstanceId}"
        )
        if (isWebViewVisible) return false
        val requestedInstanceId = instanceId?.trim()?.takeIf { it.isNotEmpty() } ?: currentTavernInstanceId
        val operation = operations.current()
        val changesTarget = (requestedInstanceId != null && requestedInstanceId != currentTavernInstanceId) ||
            (targetUrl != null && targetUrl != tavernUrl)
        if (changesTarget && (operations.hasPendingWork() || serverProcess?.isAlive == true)) {
            throw IllegalStateException("请先停止当前实例或等待当前任务结束后再切换")
        }
        val willBeReady = serverReady || (targetUrl != null && !isLocalUrl(targetUrl))
        if (!willBeReady) return false
        if (changesTarget && operation != null) operations.cancel(operation.instanceId, operation.operationId)
        currentTavernInstanceId = requestedInstanceId
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
        tavernPageSession.ensureLoaded(
            currentTavernInstanceId, tavernUrl, webView.url,
            forceReload = targetUrl != null, loadUrl = webView::loadUrl
        )
        val h = statusBarFixedPx
        val lp = webViewScreen.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = h
        webViewScreen.layoutParams = lp
        enterImmersive()
        switchToWebView(true)
        // 版本更新后同一实例也会重新提示，是否展示由原生版本标记决定。
        if (!instanceId.isNullOrBlank()) tavernStatusHint.show(instanceId)
        // 顶条带自动取色：第 0 毫秒先以缓存色瞬时对齐，随后首帧探针校准与事件驱动
        val cachedColor = getSavedTopColor(instanceId)
        if (cachedColor != null) {
            applyTopColor(cachedColor, instant = true)
        }
        handler.removeCallbacks(topColorPoll)
        handler.postDelayed({ triggerTopColorSample() }, 360)
        injectRenderEngine()
        renderEngineManager.forceChameleonSample(webView)
        return true
    }

    fun openExternalUrl(url: String) {
        val target = ExternalNavigationPolicy.externalUrl(url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        }
        startActivity(intent)
    }

    private fun openNavigationExternalUrl(url: String) {
        try {
            openExternalUrl(url)
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开系统浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    /** 判断是否本地回环地址(127.0.0.1 / localhost)。 */
    private fun isLocalUrl(url: String): Boolean = TavernDownloadFiles.isLoopbackHttpUrl(url)

    /**
     * 手势退出：只隐藏 WebView，不停服务。
     * 实例继续运行，可通过 returnToTavern() 回到酒馆。
     */
    fun exitTavern() {
        if (!isWebViewVisible) return
        isWebViewVisible = false
        chameleonController.reset()
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
    fun returnToTavern(): Boolean {
        if (isWebViewVisible) return true
        if (tavernUrl.isBlank() || !serverReady) return false
        return enterTavern(instanceId = currentTavernInstanceId)
    }

    /**
     * 真正关闭实例（停止服务）。
     * 由前端"停止"按钮调用。
     */
    fun closeTavern(instanceId: String? = null, operationId: String? = null) {
        val operation = operations.current()
        if (operation == null) {
            if (operationId != null || (instanceId != null && instanceId != currentTavernInstanceId)) return
        } else if (!operations.cancel(instanceId, operationId)) {
            return
        }
        cleanupService.invalidate()
        processSupervisor.stopAllAsync()
        chameleonController.reset()
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
        serverProcess = null
        serverReady = false
        tavernUrl = ""
        tavernPageSession.reset()
        clearTavernBasicAuth()
        // tavernRunning=false：前端置 stopped
        bridge?.webView?.apply {
            visibility = View.VISIBLE
            onResume()
        }
        pushMode("launcher", tavernRunning = false, operation = operation, allowCancelled = true)
        pushReady(false, operation, allowCancelled = true)
        currentTavernInstanceId = null
    }

    private fun clearTavernBasicAuth() {
        tavernAuthHost = null
        tavernAuthUsername = null
        tavernAuthPassword = null
    }

    private fun reportTavernDocumentFailure(kind: String, code: Int?) {
        android.util.Log.e(TAG, "Tavern main document failure kind=$kind code=$code")
        if (tavernDocumentFailed) return
        tavernDocumentFailed = true
        tavernPageSession.reset()
        pushLog("[ERR] 酒馆主页面加载失败 ($kind ${code ?: "unknown"})，请返回启动器查看日志")
        if (isWebViewVisible) Toast.makeText(this, "酒馆页面加载失败：$kind ${code ?: ""}", Toast.LENGTH_LONG).show()
    }

    private fun recordTavernDocumentDiagnostics(pageUrl: String?) {
        if (!TavernDownloadFiles.sameOrigin(tavernUrl, pageUrl)) return
        val generation = tavernDocumentGeneration
        handler.postDelayed({
            if (isDestroyed || !isWebViewVisible || generation != tavernDocumentGeneration) return@postDelayed
            // Counts and structural flags only; never inspect page text, forms, cookies or URLs.
            webView.evaluateJavascript("""
                (() => JSON.stringify({
                  ready: document.readyState,
                  nodes: document.getElementsByTagName('*').length,
                  scripts: document.scripts.length,
                  topBar: !!document.getElementById('top-bar'),
                  input: !!document.getElementById('send_textarea'),
                  splash: !!document.querySelector('.splash-screen'),
                  width: document.documentElement.clientWidth,
                  height: document.documentElement.clientHeight
                }))();
            """.trimIndent()) { result ->
                if (generation != tavernDocumentGeneration || !isWebViewVisible) return@evaluateJavascript
                val data = runCatching { JSONObject(org.json.JSONArray("[$result]").getString(0)) }.getOrNull()
                if (data != null) android.util.Log.i(TAG, "Tavern structure: $data")
            }
        }, 1_200)
    }

    private fun clearSystemGestureExclusions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webView.systemGestureExclusionRects = emptyList()
        }
    }

    private fun pushMode(
        mode: String,
        tavernRunning: Boolean = false,
        operation: OperationCoordinator.Operation? = runtimeEventContext(),
        allowCancelled: Boolean = false
    ) {
        notifyRuntime(
            "mode",
            JSObject()
                .put("mode", mode)
                .put("tavernRunning", tavernRunning)
                .put("instanceId", currentTavernInstanceId),
            operation,
            allowCancelled
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
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withLayer()
                .withEndAction {
                    if (isDestroyed || isFinishing) return@withEndAction
                    if (isWebViewVisible) {
                        bridge?.webView?.apply {
                            onPause()
                            visibility = View.GONE
                        }
                    }
                }
                .start()
        } else {
            webViewScreen.alpha = 1f
            root.alpha = 1f
            bridge?.webView?.apply {
                onPause()
                visibility = View.GONE
            }
        }
    }

    private fun switchToHome(animate: Boolean, tavernRunning: Boolean = false) {
        isWebViewVisible = false
        // 唤醒底座 Capacitor 控制台
        bridge?.webView?.apply {
            visibility = View.VISIBLE
            onResume()
        }
        if (animate && webViewScreen.isShown) {
            root.animate().cancel()
            root.animate()
                .alpha(0f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withLayer()
                .withEndAction {
                    if (isDestroyed || isFinishing) return@withEndAction
                    if (isWebViewVisible) return@withEndAction
                    chameleonController.reset()
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
            chameleonController.reset()
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
                // 仅隐藏顶栏状态栏，保留底部手势导航栏（小黑条），让输入底栏自然避让曲面屏圆角
                controller.hide(WindowInsets.Type.statusBars())
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_FULLSCREEN
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

    // ============================================
    // RENDER ENGINE & CHAMELEON DELEGATION
    // ============================================

    /** 触发单次极速取色（进入瞬间或关键节点主动调用） */
    private fun triggerTopColorSample() {
        if (!isWebViewVisible || !::webView.isInitialized) return
        chameleonController.sampleTopColor(webView) { c ->
            if (c != null) applyTopColor(c)
        }
    }

    private fun sampleTopColor(onResult: (Int?) -> Unit) {
        if (!isWebViewVisible || !::webView.isInitialized) {
            onResult(null)
            return
        }
        chameleonController.sampleTopColor(webView, onResult)
    }

    private fun getSavedTopColor(instanceId: String?): Int? =
        chameleonController.getSavedColor(instanceId)

    /** 取色 → 顶框 scrim 条色波 + 光泽呼吸；instant=true 时 0 毫秒瞬时设定，消除进入色差。 */
    private fun applyTopColor(color: Int, instant: Boolean = false) {
        chameleonController.applyColor(color, instant)
    }

    /** 探针：页面加载后驱动取色；触控原汁原味响应轻触点击光波与下拉刷新，滑动过程零打扰。 */
    private fun installChameleonProbes() {
        handler.removeCallbacks(topColorPoll)
        handler.postDelayed(topColorPoll, 300)
        chameleonController.setupTouchListener(webView) {
            webView.reload()
            topScrimBar.sweepGloss()
            pushLog("↓ 下拉刷新酒馆界面")
        }
    }

    /** 注入 SC 渲染调度引擎（含运行时底座补丁、变色龙感知、锁步合批与图层爆炸治理） */
    private fun injectRenderEngine() {
        if (!::webView.isInitialized) return
        // The engine belongs to a committed same-origin document: an eval racing
        // a pending navigation lands on about:blank, where WebView denies
        // localStorage and the engine's own init would die before installing.
        if (!TavernDownloadFiles.sameOrigin(tavernUrl, webView.url)) return
        renderEngineManager.injectEngine(webView)
    }

    private fun togglePerformanceMonitor() {
        if (!::webView.isInitialized) return
        renderEngineManager.togglePerformanceMonitor(webView)
    }

    /** 触感引擎：驱动设备线性马达输出微米级触觉反馈 (Tick / Click / Heavy) */
    fun triggerHaptic(type: String = "tick") {
        hapticController.trigger(type)
    }

    /** 系统前台切回心跳自愈探针：防系统省电杀死 socket 导致白屏断连 */
    private fun resumeHeartbeatHeal() {
        if (!isWebViewVisible || !serverReady || tavernUrl.isBlank()) return
        Thread {
            try {
                val conn = URL(tavernUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "HEAD"
                val code = conn.responseCode
                conn.disconnect()
                android.util.Log.d(TAG, "Heartbeat probe HTTP $code")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Heartbeat probe non-fatal hiccup: ${e.message}")
            }
        }.start()
    }

    /**
     * Always-on mobile layout patches (keyboard-fitting input bar and the
     * bottom padding), independent of whether the optional companion theme is
     * enabled. These rules used to ride inside that theme's CSS; making them
     * part of the page itself keeps the behaviour on every instance.
     */
    private fun injectMobileLayoutOptimizations() {
        if (!::webView.isInitialized) return
        val script = """
            (function() {
                try {
                    if (document.getElementById('sc-mobile-layout')) return;
                    const style = document.createElement('style');
                    style.id = 'sc-mobile-layout';
                    style.textContent = ':root { --sc-nav-bottom: 18px; }' +
                        '#form_sheld { padding-bottom: 0 !important; }' +
                        '#send_form { padding-bottom: max(12px, var(--sc-nav-bottom, 14px)) !important;' +
                        ' box-sizing: border-box !important;' +
                        ' transition: padding-bottom 120ms cubic-bezier(0.12, 0.98, 0.24, 1) !important; }' +
                        '#chat { padding-bottom: calc(var(--bottomFormBlockSize, 60px) + var(--sc-nav-bottom, 14px) + 6px) !important; }';
                    document.head.appendChild(style);
                } catch(_) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
        // Re-apply the current insets so the fresh stylesheet starts from the
        // real keyboard/navigation state instead of the 18px default.
        val insets = ViewCompat.getRootWindowInsets(webViewScreen)
        val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        val imeHeight = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val navHeight = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        dispatchImeOffset(if (imeVisible) imeHeight else 0, navHeight)
    }

    private fun dispatchImeOffset(imeHeightPx: Int, navHeightPx: Int) {
        if (!::webView.isInitialized) return
        val density = resources.displayMetrics.density
        val navCssPx = if (density > 0) navHeightPx / density else navHeightPx.toFloat()
        val script = """
            (function() {
                try {
                    // 软键盘弹起时底栏无缝贴合键盘；收起时留出小黑条与曲面屏安全区（默认至少 18px）
                    const isIme = ${imeHeightPx} > 0;
                    const nav = isIme ? 0 : Math.max(${navCssPx}, 18);
                    document.documentElement.style.setProperty('--sc-nav-bottom', nav + 'px');
                } catch(_) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
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

    private fun ensureRuntimeReady() {
        bundledRuntime.awaitReady(::ensureOperationActive)
    }

    private fun startServer(
        paths: RuntimePaths,
        targetServerDir: File,
        port: Int,
        config: InstanceConfig,
        operation: OperationCoordinator.Operation
    ): Process? {
        operations.ensureCurrent(operation)
        paths.logsDir.mkdirs()
        LogService.rotate(File(paths.logsDir, "server.log"))
        try {
            val pb = RuntimeConfiguration(paths, operations, processSupervisor)
                .serverBuilder(targetServerDir, instanceConfigValues(config, port))
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
            env["PATH"] = "${paths.tarvenHome.absolutePath}/bin:/system/bin"
            env["HOME"] = paths.tarvenHome.absolutePath
            env["TMPDIR"] = paths.tmpDir.absolutePath
            env["HOST"] = "127.0.0.1"
            env["PORT"] = port.toString()
            env["NODE_OPTIONS"] = "--max-old-space-size=2048"
            // V8 bytecode cache: repeat launches skip re-parsing thousands of CJS
            // modules, which dominates SillyTavern cold-start time on Android.
            env["NODE_COMPILE_CACHE"] = File(paths.tarvenHome, "node-compile-cache").apply { mkdirs() }.absolutePath
            // 实例自持：模块全部由实例目录内的 node_modules 解析，无需任何桥接。
            return operations.commit(operation) {
                processSupervisor.track(pb.start(), operation.instanceId, operation).also { process ->
                    serverProcess = process
                    processSupervisor.watch(process, onLine = { line ->
                        if (operations.isCurrent(operation)) {
                            LogService.append(File(paths.logsDir, "server.log"), line)
                            pushLog(line, operation)
                        }
                    }, onExit = { code ->
                        if (operations.isCurrent(operation) && serverProcess === process && serverReady) {
                            runCatching {
                                operations.commit(operation) {
                                    if (serverProcess === process) {
                                        serverProcess = null
                                        serverReady = false
                                        pushReady(false, operation)
                                        notifyRuntime("error", JSObject().put("message", "Node.js 进程已退出 (code $code)"), operation)
                                    }
                                }
                            }
                        }
                    })
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Exception) {
            runtimeDiagnostic("server.launch_failed ${error.javaClass.simpleName} ${error.message?.take(240)}")
            return null
        }
    }

    private fun instanceConfigValues(config: InstanceConfig, port: Int): JSONObject = JSONObject()
        .put("port", port)
        .put("listen", config.listen)
        .put("listenAddress.ipv4", if (config.listen) "0.0.0.0" else "127.0.0.1")
        .put("listenAddress.ipv6", if (config.listen) "::" else "::1")
        .put("protocol.ipv4", config.ipv4)
        .put("protocol.ipv6", config.ipv6)
        .put("dnsPreferIPv6", config.dnsIpv6)
        .put("heartbeatInterval", config.heartbeat)
        .put("enableKeepAlive", config.keepAlive)
        .put("browserLaunch.enabled", false)
        .put("checkForUpdates", false)

    private fun downloadAndExtractGithubRelease(zipballUrl: String, paths: RuntimePaths, targetServerDir: File): Boolean {
        val operation = operations.context() ?: error("Missing download operation")
        val sourceCache = SourceArchiveCache(File(paths.tarvenHome, "source-archives"))
        val urlKey = SourceArchiveCache.urlKey(zipballUrl)
        val cached = sourceCache.cachedArchive(urlKey)
        runtimeDiagnostic("source.cache.${if (cached != null) "hit" else "miss"} key=${urlKey.take(12)}")
        var stored = false
        val archive: File = cached ?: run {
            appendLog("[提示] 若下载缓慢或失败，可改用本地 ZIP 导入，或开启科学上网后重试")
            val downloaded = SourceDownloader().downloadSillyTavern(
                zipballUrl, paths.tmpDir, operations, operation, ::appendLog
            ) { progress ->
                val percent = progress.totalBytes?.takeIf { it > 0 }?.let {
                    (progress.downloadedBytes * 100 / it).toInt().coerceIn(0, 100)
                }
                updateProgress(10 + (percent ?: 0) * 65 / 100,
                    "Downloading via ${progress.source}: ${progress.downloadedBytes / 1048576} MiB")
            }
            val sealed = sourceCache.store(urlKey, downloaded)
            stored = sealed != null
            if (stored) {
                runtimeDiagnostic("source.cache.stored key=${urlKey.take(12)} bytes=${downloaded.length()}")
            }
            sealed ?: downloaded
        }
        return try {
            if (cached != null) updateProgress(74, "Source archive cache hit")
            updateProgress(78, "Extracting source")
            extractLocalZip(archive, targetServerDir)
        } finally {
            if (cached == null && !stored && !archive.delete() && archive.exists()) {
                appendLog("[WARN] Download archive retained for later cleanup")
            }
        }
    }
    /** Streams the SillyTavern zipball shipped inside the APK to the staging directory. */
    private fun extractBundledRelease(paths: RuntimePaths, destDir: File): Boolean {
        check(bundledTavernSource.version != null) { "Bundled source archive is unavailable" }
        val name = BundledTavernSource.ASSET_PATH
        val tmpDir = paths.tmpDir.apply { check(isDirectory || mkdirs()) { "Cannot prepare the staging directory" } }
        val temp = File.createTempFile("bundled-release-", ".zip", tmpDir)
        return try {
            assets.open(name).use { input ->
                FileOutputStream(temp).use { output -> copyWhileActive(input, output) }
            }
            // Preferred path: cached ustar of the bundled source, extracted by
            // parallel tar groups (a few hundred ms) instead of in-process
            // per-file writes. Falls back to the in-process extractor, which
            // requires a clean target — partial tar output is wiped first.
            val tar = com.sillyclient.runtime.SourceTarCache.tarFor(
                temp, File(paths.tarvenHome, "source-archives"))
            val extractor = shardExtractor(paths)
            if (tar != null && extractor.available() &&
                extractor.extract(tar, destDir, ::ensureOperationActive)) {
                runtimeDiagnostic("source.tar extracted")
                return true
            }
            destDir.listFiles()?.forEach { partial -> runCatching { partial.deleteRecursively() } }
            extractLocalZip(temp, destDir)
        } finally {
            if (!temp.delete() && temp.exists()) appendLog("[WARN] Bundled source cache retained for later cleanup")
        }
    }

    /** 解压本地 zip 文件到目标目录。自动检测 GitHub zipball 格式(有内层单一包裹目录)并平铺。 */
    private fun extractLocalZip(zipFile: File, destDir: File): Boolean {
        destDir.mkdirs()
        return try {
            val entryCount = com.sillyclient.runtime.SourceArchive.extract(
                zipFile, destDir, ::ensureOperationActive,
                // Imported packages belong to this instance, including extras
                // not represented by a cached dependency archive.
                onProgress = { count ->
                    if (count % 200 == 0) updateProgress(78 + (count / 1000).coerceAtMost(6), "Extracting ($count files)")
                })
            appendLog("> Extracted $entryCount files")

            val serverJs = File(destDir, "server.js")
            if (!serverJs.exists()) {
                val paths = RuntimePaths.from(this)
                val baseInstance = paths.serverDirFor("default", create = false)
                if (baseInstance.exists() && File(baseInstance, "server.js").exists()) {
                    appendLog("> 未发现 server.js，正在匹配基础酒馆运行底座...")
                    copyBaseRuntimeExcludingData(baseInstance, destDir)
                }
            }
            File(destDir, "server.js").exists()
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            android.util.Log.e(TAG, "extractLocalZip", e)
            appendLog("[ERR] Source extraction failed: ${e.message}")
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

    private fun ensureOperationActive() {
        operations.context()?.let(operations::ensureCurrent)
        if (Thread.currentThread().isInterrupted) throw CancellationException("Operation cancelled")
    }

    private fun copyWhileActive(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(65_536)
        var count: Int
        while (input.read(buffer).also { count = it } >= 0) {
            ensureOperationActive()
            output.write(buffer, 0, count)
        }
    }

    private fun runNpmInstall(paths: RuntimePaths, targetServerDir: File, onRestoreProgress: (Int) -> Unit = {}): Boolean {
        val operation = operations.context() ?: error("Missing dependency installation operation")
        // The APK ships dependency archives for the bundled release; make sure they
        // are materialized before the archive lookup so the common path needs no network.
        try {
            bundledArchives.awaitReady { operations.ensureCurrent(operation) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) { /* Bundled archives are best-effort; npm remains the fallback. */ }
        // npm-cli.js lives inside the shared runtime; a re-extraction window after
        // an app update would otherwise surface as "Bundled npm is unavailable".
        try {
            bundledRuntime.awaitReady { operations.ensureCurrent(operation) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) { /* Only npm paths need the runtime; let them report their own error. */ }
        // 实例自持：依赖一律落进实例目录，目录即完整可运行沙盒；
        // 归档仅作为安装加速缓存，缺失或损坏时回退 npm（同 Windows 语义）。
        val recoveryDirectory = File(targetServerDir, ".sillyclient-dependency-restore")
        if (!File(targetServerDir, InstanceInstaller.DEPENDENCY_MARKER).exists() && !recoveryDirectory.exists() &&
            DependencyInstaller.hasRequiredPackages(targetServerDir)) return true
        val archive = dependencyArchive(paths)
        val lockKey = archive.lockKey(File(targetServerDir, "package-lock.json"))
        val lockMismatch = DependencyInstaller.lockManifestMismatch(targetServerDir)
        if (lockMismatch || lockKey == null) {
            check(!recoveryDirectory.exists()) {
                "依赖恢复所需的安装清单或锁文件已改变，恢复目录已保留，请检查实例文件"
            }
            runtimeDiagnostic("deps.archive.skipped reason=${if (lockMismatch) "manifest_lock_mismatch" else "missing_lock_key"}")
        } else if (restoreDependencies(archive, lockKey, targetServerDir, onRestoreProgress)) return true
        runtimeDiagnostic("deps.archive.miss instance=${operation.instanceId} key=${lockKey?.take(12) ?: "none"}")
        updateProgress(85, "正在安装依赖，请查看控制台进度")
        return DependencyInstaller(paths, operations, processSupervisor, ::appendLog, ::runtimeDiagnostic)
            .install(targetServerDir, operation)
    }

    /**
     * Complete with the instance's own node_modules or with the shared bank at
     * `<root>/node_modules` covering the instance's lock file.
     */
    private fun instanceDependenciesComplete(directory: File): Boolean =
        DependencyInstaller.hasRequiredPackages(directory) || bankCovers(directory)

    private fun bankCovers(instanceDirectory: File): Boolean {
        val paths = RuntimePaths.from(this)
        val lockKey = DependencyArchive(File(paths.tarvenHome, "dependency-archives"))
            .lockKey(File(instanceDirectory, "package-lock.json"))
        return DependencyBank.covers(instanceDirectory, lockKey)
    }

    /**
     * Materialize/refresh the shared dependency bank at `<root>/node_modules`
     * from the bundled archive. Extraction goes directly into the bank — a
     * populated tree must never be renamed on shared storage (MediaProvider
     * re-indexes every descendant), and a stale bank is cleared first so a
     * half-swapped version can never survive.
     */
    private fun ensureDependencyBank(paths: RuntimePaths, ensureActive: () -> Unit,
                                     manifestDirectory: File? = null) {
        // Node resolves packages by walking up from the instance directory: from
        // `<parent>/<name>` the first candidate is `<parent>/node_modules`, and
        // during creation the staging directory shares that same parent. The
        // bank must sit exactly there — bankDirectory(instanceOrStaging)
        // answers `<parent>/node_modules` for both.
        val bank = DependencyBank.bankDirectory(manifestDirectory ?: paths.serversDir)
        // The earlier private layout (files/node_modules) is one level off and
        // must not linger as dead weight.
        val legacyBank = File(paths.appFilesDir, "node_modules")
        if (legacyBank != bank && legacyBank.isDirectory) {
            runCatching { ManagedFiles.deleteDirectory(legacyBank, paths.appFilesDir) }
        }
        val (key, archive) = bundledDependencyArchive(paths, ensureActive)
            ?: error("缺少内置依赖归档，无法准备共享依赖，请检查安装包完整性")
        val manifest = manifestDirectory
        if (DependencyBank.read(bank)?.key == key &&
            (manifest == null || DependencyInstaller.hasRequiredPackages(manifest, bank))) {
            runtimeDiagnostic("bank.hit key=${key.take(12)}")
            return
        }
        if (bank.exists()) {
            // The bank sits one level above the instances root; its own parent
            // is the managed scope, and deletion runs through the child rm so
            // tens of thousands of files never serialize the app process.
            runtimeDiagnostic("bank.reset")
            val bankParent = requireNotNull(bank.parentFile)
            NativeTreeRemoval(processSupervisor).remove(
                listOf(bank), bankParent, "dependencies", operations.context(), ensureActive)
            check(!bank.exists()) { "无法清理旧的共享依赖目录，请重试" }
        }
        val extractor = shardExtractor(paths)
        val extractorProgress: (Int) -> Unit = { count ->
            updateProgress(85, "正在准备共享依赖组件 · $count")
        }
        val extracted = extractor.available() && extractor.extract(archive, bank, ensureActive, extractorProgress)
        if (!extracted) {
            // No platform tar (or child extraction failed): fall back to the
            // in-process transaction, which publishes through a same-parent
            // rename. The completeness check needs the manifest that defines
            // the dependency set — the instance being created or launched.
            // Without one (idle prewarm) the fallback is skipped and the work
            // is retried on the next opportunity.
            check(manifestDirectory != null) { "共享依赖准备失败，请重试" }
            bank.deleteRecursively()
            check(dependencyArchive(paths).restore(key,
                manifestDirectory?.parentFile ?: paths.serversDir, replaceIncomplete = true,
                skipExecutableLinks = true,
                validateModules = { modules -> DependencyInstaller.hasRequiredPackages(manifest, modules) },
                ensureActive = ensureActive)) { "共享依赖准备失败，请重试" }
        }
        // The bank holds node_modules only; verify against the instance manifest
        // when one is available (prewarm has none — the tar path already
        // verified every top-level package exists).
        check(manifest == null || DependencyInstaller.hasRequiredPackages(manifest, bank)) {
            "共享依赖校验失败，请重试"
        }
        DependencyBank.write(bank, key)
        runtimeDiagnostic("bank.ready key=${key.take(12)}")
    }

    /** Self-heal hook: a launch that needs the bank rebuilds it before the server starts. */
    private fun ensureBankForLaunch(paths: RuntimePaths, target: File, operation: OperationCoordinator.Operation) {
        if (DependencyInstaller.hasRequiredPackages(target)) return
        if (File(target, InstanceInstaller.DEPENDENCY_MARKER).exists()) return
        val extract = { operations.ensureCurrent(operation) }
        val lockKey = DependencyArchive(File(paths.tarvenHome, "dependency-archives"))
            .lockKey(File(target, "package-lock.json")) ?: return
        if (DependencyBank.covers(target, lockKey)) return
        // The bank can only serve the lock the bundled archive was built from;
        // anything else keeps the npm flow as its owner.
        val bundled = bundledDependencyArchive(paths, extract) ?: return
        if (bundled.first != lockKey) return
        updateProgress(96, "正在准备共享依赖组件（仅首次）")
        ensureDependencyBank(paths, extract, manifestDirectory = target)
    }

    /** (lockKey, archiveFile) of the bundled dependency archive, materialized on demand. */
    private fun bundledDependencyArchive(paths: RuntimePaths, ensureActive: () -> Unit): Pair<String, File>? {
        bundledArchives.awaitReady(ensureActive)
        val archive = File(paths.tarvenHome, "dependency-archives")
            .listFiles { file -> file.isFile && DEPENDENCY_ARCHIVE_NAME.matches(file.name) }
            ?.maxByOrNull { it.lastModified() } ?: return null
        val key = DEPENDENCY_ARCHIVE_NAME.find(archive.name)?.groupValues?.get(1) ?: return null
        return key to archive
    }

    private fun dependencyArchive(paths: RuntimePaths) = DependencyArchive(
        File(paths.tarvenHome, "dependency-archives"),
        childExtract = { archive, target, ensureActive, onFiles ->
            shardExtractor(paths).let { extractor ->
                extractor.available() && extractor.extract(archive, target, ensureActive, onFiles)
            }
        },
        removeStaging = { staging, owner, verify ->
            val operation = operations.context() ?: error("Missing dependency recovery operation")
            com.sillyclient.runtime.InstanceRemoval.remove(
                staging, owner, verifyIdentity = verify, ensureActive = ::ensureOperationActive,
                removeChildren = { children ->
                    NativeTreeRemoval(processSupervisor).remove(children, staging, operation.instanceId, operation,
                        ::ensureOperationActive, onProgress = {
                            runtimeDiagnostic("deps.cleanup $it")
                            updateProgress(85, "正在清理旧依赖 · ${it.removedEntries}")
                        })
                },
                commit = { action -> operations.commit(operation, action) }, unregister = {}
            )
        })

    private fun shardExtractor(paths: RuntimePaths) =
        com.sillyclient.runtime.TarGroupExtractor(paths, operations, processSupervisor)

    private fun webpackCacheSeed(paths: RuntimePaths) =
        WebpackCacheSeed(paths)

    /** Only a cache miss falls back to npm; filesystem failures preserve both copies. */
    private fun restoreDependencies(
        archive: DependencyArchive,
        lockKey: String,
        directory: File,
        onFileRestored: (Int) -> Unit = {}
    ): Boolean {
        val started = System.nanoTime()
        var lastProgress = 0L
        return try {
            runtimeDiagnostic("deps.archive.check key=${lockKey.take(12)}")
            archive.restore(lockKey, directory, onFileRestored = { restored ->
                val now = System.nanoTime()
                if (now - lastProgress >= 500_000_000L) {
                    lastProgress = now
                    onFileRestored(restored)
                    runtimeDiagnostic("deps.archive.progress files=$restored elapsed_ms=${(now - started) / 1_000_000}")
                }
            }, replaceIncomplete = true, skipExecutableLinks = true,
                validateModules = { DependencyInstaller.hasRequiredPackages(directory, it) },
                ensureActive = ::ensureOperationActive) &&
                DependencyInstaller.hasRequiredPackages(directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runtimeDiagnostic("deps.archive.failed type=${error.javaClass.simpleName} msg=${error.message?.take(160)}")
            throw IllegalStateException("本地依赖恢复失败，原有文件已保留：${error.message}", error)
        }.also { restored ->
            if (restored) {
                appendLog("[OK] 依赖归档命中，已跳过网络安装")
                runtimeDiagnostic("deps.archive.restored key=${lockKey.take(12)} " +
                    "elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
            }
        }
    }

    /** Archives a published instance's dependencies for the next same-lock install. */
    private fun archiveDependenciesInBackground(paths: RuntimePaths, instanceDirectory: File) {
        val archive = dependencyArchive(paths)
        val lockKey = archive.lockKey(File(instanceDirectory, "package-lock.json")) ?: return
        if (File(instanceDirectory, InstanceInstaller.DEPENDENCY_MARKER).exists() ||
            !DependencyInstaller.hasRequiredPackages(instanceDirectory)) return
        Thread {
            val started = System.nanoTime()
            try {
                if (archive.archive(instanceDirectory, lockKey)) {
                    runtimeDiagnostic("deps.archive.stored key=${lockKey.take(12)} " +
                        "elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
                }
            } catch (error: Exception) {
                runtimeDiagnostic("deps.archive.store_failed ${error.message?.take(160)}")
            }
        }.apply {
            name = "SC-dependency-archive"
            isDaemon = true
        }.start()
    }

    private fun runtimeDiagnostic(message: String) {
        android.util.Log.i(TAG, "runtime ${message.take(512)}")
        com.sillyclient.runtime.Diag.append(RuntimePaths.from(this).tarvenHome, "runtime ${message.take(512)}")
    }

    private fun instanceInstaller(paths: RuntimePaths) = InstanceInstaller(
        paths.serversDir, paths.installLocations, ::instanceDependenciesComplete,
        removeStaging = { directory, root, marker, verifyIdentity ->
            val operation = operations.context() ?: error("Missing installation cleanup operation")
            val interrupted = Thread.interrupted()
            try {
                check(!processSupervisor.hasProcesses(operation.instanceId)) {
                    "Installation process has not exited; incomplete files were preserved"
                }
                runtimeDiagnostic("install.rollback.begin")
                updateProgress(85, "Cleaning up incomplete installation")
                com.sillyclient.runtime.InstanceRemoval.remove(
                    directory, root, verifyIdentity = verifyIdentity, ensureActive = {},
                    removeChildren = { children ->
                        NativeTreeRemoval(processSupervisor, identityFileName = marker.name).remove(
                            children, directory, operation.instanceId, null, {},
                            onProgress = { runtimeDiagnostic("install.rollback $it") }
                        )
                    },
                    commit = { it() }, unregister = {}, identityFileName = marker.name
                )
                true
            } finally {
                runtimeDiagnostic("install.rollback.end removed=${!directory.exists()}")
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    )

    /** 扫描本地已存在的酒馆实例。返回强类型实例摘要:instanceId, version, path, sizeBytes, hasServer。 */
    fun scanInstances(): List<InstanceSummary> {
        // Console refreshes double as the retry point for interrupted removals:
        // marked or renamed remnants are hidden from the scan and reclaimed here.
        // Never start a purge while a user operation is running: both compete for
        // the same emulated-storage queue, which can stall the foreground work.
        if (operations.current() == null &&
            com.sillyclient.storage.InstanceStorageAccess.isGranted(this)) {
            runCatching { sweepRemovalRemnants(RuntimePaths.from(this).serversDir, "maintenance") }
        }
        return instanceRepository.scan().map { info ->
            InstanceSummary(info.instanceId, info.version, info.path, info.sizeBytes, info.hasServer)
        }
    }

    /** 实例详情:version, path, sizeBytes, createdAt, status。 */
    fun getInstanceInfo(instanceId: String, port: Int, installPath: String? = null): InstanceDetails {
        val paths = RuntimePaths.from(this)
        val dir = paths.serverDirFor(instanceId, installPath, create = false)
        val info = instanceRepository.info(dir)
        return InstanceDetails(info.version, info.path, info.sizeBytes, info.createdAt, info.status)
    }

    fun checkLegacyInstances(): List<InstanceRelocation.LegacyInstance> =
        InstanceRelocation(RuntimePaths.from(this), operations, processSupervisor).legacyInstances()

    fun relocateInstance(instanceId: String, targetPath: String? = null, installPath: String? = null,
                         operationId: String? = null): InstanceRelocation.Result =
        runInstanceMaintenance(instanceId, operationId) { id ->
            bundledRuntime.awaitReady(::ensureOperationActive)
            val operation = operations.context() ?: error("Missing relocation operation")
            val result = InstanceRelocation(RuntimePaths.from(this), operations, processSupervisor,
                onProgress = transferProgressForwarder(id))
                .relocate(id, targetPath, installPath, operation = operation)
            instanceRepository.invalidate(File(result.oldPath))
            instanceRepository.invalidate(File(result.newPath))
            cleanupService.invalidate()
            result
        }

    fun renameInstance(instanceId: String, newName: String, installPath: String? = null,
                       operationId: String? = null): InstanceRename.Result =
        runInstanceMaintenance(instanceId, operationId) { id ->
            bundledRuntime.awaitReady(::ensureOperationActive)
            val paths = RuntimePaths.from(this)
            val operation = operations.context() ?: error("Missing rename operation")
            val result = InstanceRename(paths, InstanceRelocation(paths, operations, processSupervisor))
                .rename(id, newName, installPath, operation)
            paths.instanceLock.renamePassword(id, result.newId)
            instanceRepository.invalidate(File(result.oldPath))
            instanceRepository.invalidate(File(result.newPath))
            cleanupService.invalidate()
            result
        }

    /** 向终端发送命令:运行 shell 命令并流式输出到日志。 */
    fun sendCommand(text: String, instanceId: String) {
        if (text.isBlank()) return
        val paths = RuntimePaths.from(this)
        val id = RuntimePaths.normalizeInstanceId(instanceId)
        val instanceDir = paths.serverDirFor(instanceId, create = false)
        val operation = operations.current()?.takeIf { it.instanceId == id }
        val generation = processSupervisor.generation()
        if (!instanceDir.exists()) {
            pushCommandLog("实例目录不存在: $instanceId", operation, instanceId, generation)
            return
        }
        Thread {
            pushCommandLog("\$ $text", operation, instanceId, generation)
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", text)
                pb.directory(instanceDir)
                pb.redirectErrorStream(true)
                val env = pb.environment()
                env["LD_LIBRARY_PATH"] = "${paths.usrDir.absolutePath}/lib:${paths.nativeLibDir.absolutePath}"
                env["PATH"] = "${paths.tmpDir.absolutePath}/bin:/system/bin:${System.getenv("PATH") ?: ""}"
                env["HOME"] = paths.tarvenHome.absolutePath
                val p = processSupervisor.launch(pb, id, operation, generation)
                val result = processSupervisor.waitFor(p, 30_000) { pushCommandLog(it, operation, instanceId, generation) }
                if (result.timedOut) pushCommandLog("命令执行超时，已终止进程", operation, instanceId, generation)
            } catch (_: CancellationException) {
                // Closing or switching an instance cancels queued commands too.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                pushCommandLog("命令执行失败: ${e.message}", operation, instanceId, generation)
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
    fun getSafeInsets(): InsetsRect {
        var cutoutTop = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cutout = window.decorView.rootWindowInsets?.displayCutout
            if (cutout != null) cutoutTop = cutout.safeInsetTop
        }
        // Fallback: 若运行时 cutout 未就绪,用 onCreate 时测量的 statusBarFixedPx
        if (cutoutTop <= 0) cutoutTop = statusBarFixedPx
        return InsetsRect(cutoutTop, 0, 0, 0)
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
        require(ManagedFiles.isWithin(outFile, coversDir)) { "Cover path escapes the managed root" }
        android.util.Log.d(TAG, "copyCoverImage: outFile=${outFile.absolutePath}")
        val input = contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("无法打开图片流: $uri")
        input.use { ins ->
            FileOutputStream(outFile).use { out -> ins.copyTo(out) }
        }
        cleanupService.invalidate()
        android.util.Log.d(TAG, "copyCoverImage: done, size=${outFile.length()}")
        return outFile.absolutePath
    }

    /** 卸载实例:删除安装目录 + 封面图,返回释放的字节数。 */
    fun uninstallInstance(instanceId: String, installPath: String? = null, operationId: String? = null): Long {
        val paths = RuntimePaths.from(this)
        val id = RuntimePaths.normalizeInstanceId(instanceId)
        val targetDir = paths.serverDirFor(id, installPath, create = false)
        check(!serverReady || currentTavernInstanceId == id) { "请先停止正在运行的其他实例后再删除" }
        check(!operations.hasPendingWork() || operations.current()?.instanceId == id) {
            "请先等待其他实例任务完成"
        }
        val operation = operations.begin(id, operationId?.takeIf { it.isNotBlank() })
        cleanupService.invalidate()
        val stopped = processSupervisor.stopAllAsync(id)
        return try {
            operations.run(operation) {
                try {
                    stopped.get(3, TimeUnit.SECONDS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("Operation cancelled")
                } catch (error: Exception) {
                    runtimeDiagnostic("removal.stop_failed type=${error.javaClass.simpleName} " +
                        "alive=${processSupervisor.hasProcesses(id)}")
                    throw IllegalStateException("实例后台进程未能停止，删除已中止；请稍后重试", error)
                }
                runtimeDiagnostic("removal.stopped")
                operations.ensureCurrent(operation)
                val freed = uninstallInstanceFiles(id, targetDir)
                runOnUiThread {
                    if (operations.current() === operation) closeTavern(id, operation.operationId)
                    else if (operations.current() == null && currentTavernInstanceId == id) closeTavern(id)
                }
                freed
            }
        } catch (error: Exception) {
            runtimeDiagnostic("removal.failed type=${error.javaClass.simpleName}")
            throw error
        } finally {
            operations.finish(operation)
        }
    }

    /**
     * Package an instance directory into a ZIP under Download/SillyClient-导出
     * so the user always has a file-manager-visible copy on demand, whether the
     * instance itself lives in the managed area or a custom directory.
     */
    /**
     * 把完整压缩包里的用户数据无损导入到已有实例：只覆盖用户数据
     * （data/、第三方扩展、plugins/，可选的 secrets.json/config.yaml），
     * 依赖与程序文件（node_modules、package.json、其余 public/、构建缓存）永不写入。
     * 实例必须处于停止状态；逐文件"临时文件 + 原子替换"，取消不会留下半个文件。
     */
    fun importInstanceData(
        instanceId: String,
        installPath: String?,
        archivePath: String,
        includeOptional: Boolean,
        operationId: String? = null
    ): Triple<Int, Long, Int> {
        val archive = File(archivePath)
        require(archive.isFile) { "压缩包不存在，请重新选择" }
        val paths = RuntimePaths.from(this)
        return runInstanceMaintenance(instanceId, operationId) { id ->
            val directory = paths.serverDirFor(id, installPath, create = false)
            require(directory.isDirectory && File(directory, "server.js").isFile) {
                "实例目录不存在或尚未安装"
            }
            pushLog("> 正在从备份导入用户数据…")
            val outcome = InstanceDataImport.import(
                archive = archive,
                instanceDir = directory,
                includeOptional = includeOptional,
                ensureActive = { ensureOperationActive() },
                onProgress = { count, bytes ->
                    if (count % 200 == 0) pushLog("正在导入数据 · $count 项 · ${bytes / 1024 / 1024} MB")
                }
            )
            pushLog("[OK] 数据导入完成：${outcome.imported} 项（忽略依赖与程序文件 ${outcome.skippedEntries} 项）")
            runtimeDiagnostic("import.done imported=${outcome.imported} skipped=${outcome.skippedEntries} bytes=${outcome.bytes}")
            Triple(outcome.imported, outcome.bytes, outcome.skippedEntries)
        }
    }

    fun exportInstance(instanceId: String, installPath: String? = null): Pair<String, Long> {
        val paths = RuntimePaths.from(this)
        val id = RuntimePaths.normalizeInstanceId(instanceId)
        val directory = paths.serverDirFor(id, installPath, create = false)
        require(directory.isDirectory && File(directory, "server.js").isFile) { "实例目录不存在或尚未安装" }
        val targetDir = File(android.os.Environment.getExternalStorageDirectory(), "Download/SillyClient-导出").apply { mkdirs() }
        require(targetDir.isDirectory) { "无法创建导出目录，请检查存储权限" }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val zip = File(targetDir, "${directory.name}-$stamp.zip")
        var entries = 0
        var bytes = 0L
        val rootPath = directory.toPath()
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(zip), 256 * 1024)).use { output ->
            directory.walkTopDown().forEach { file ->
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("export cancelled")
                if (java.nio.file.Files.isSymbolicLink(file.toPath())) return@forEach
                val relative = rootPath.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                if (relative.isEmpty()) return@forEach
                if (file.isDirectory) {
                    output.putNextEntry(java.util.zip.ZipEntry("$relative/"))
                    output.closeEntry()
                } else if (file.isFile) {
                    output.putNextEntry(java.util.zip.ZipEntry(relative))
                    file.inputStream().use { it.copyTo(output, 256 * 1024) }
                    output.closeEntry()
                    entries++
                    bytes += file.length()
                    if (entries % 200 == 0) pushLog("正在打包实例 · $entries 项")
                }
            }
        }
        runtimeDiagnostic("export.done entries=$entries bytes=$bytes path=${zip.name}")
        return zip.absolutePath to bytes
    }

    private fun uninstallInstanceFiles(instanceId: String, targetDir: File): Long {
        val paths = RuntimePaths.from(this)
        val operation = operations.context() ?: error("Missing removal operation")
        runtimeDiagnostic("removal.begin")
        val parent = requireNotNull(targetDir.parentFile)
        paths.installLocations.allowedRootFor(targetDir)
        // Committing the removal unregisters the instance; without storage
        // access the physical delete would then fail and leave an orphaned
        // directory the console no longer knows about. Refuse before that.
        check(com.sillyclient.storage.InstanceStorageAccess.isGranted(this)) {
            com.sillyclient.storage.InstanceStorageAccess.DENIED_MESSAGE
        }
        // Remnants of earlier interrupted removals in this root are reclaimed
        // alongside; marked directories from any root are hidden by scanners.
        sweepRemovalRemnants(parent, instanceId)
        // The visible removal is one marker write plus one registry update: the
        // instance disappears from the console immediately and the physical
        // delete streams in the background. A directory rename is deliberately
        // NOT used: on emulated storage it makes MediaProvider re-index every
        // descendant, which is slower than the delete itself.
        operations.commit(operation) {
            val marker = File(targetDir, InstanceRemoval.REMOVAL_MARKER)
            require(ManagedFiles.isWithin(marker, targetDir)) { "Invalid removal marker path" }
            marker.writeText(InstanceRemoval.MARKER_CONTENT)
            paths.installLocations.unregisterAfterDelete(instanceId, targetDir)
        }
        runtimeDiagnostic("removal.disappeared")
        KeepAlive.acquire(this)
        Thread {
            try {
                NativeTreeRemoval(processSupervisor).remove(listOf(targetDir), parent, instanceId)
                runtimeDiagnostic("removal.purged")
            } catch (error: Exception) {
                runtimeDiagnostic("removal.purge_failed type=${error.javaClass.simpleName} msg=${error.message?.take(160)}")
            } finally {
                runOnUiThread { KeepAlive.release(applicationContext) }
            }
        }.apply {
            name = "SC-removal-purge"
            isDaemon = true
        }.start()
        instanceRepository.invalidate(targetDir)
        val safeId = instanceId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val coverFile = File(paths.bootstrapDir, "covers/$safeId.png")
        if (coverFile.isFile && ManagedFiles.isWithin(coverFile, paths.bootstrapDir)) {
            if (!coverFile.delete()) appendLog("[WARN] 实例已删除，封面缓存暂未移除")
        }
        paths.instanceLock.removePassword(instanceId)
        runtimeDiagnostic("removal.complete")
        // Unknown byte count avoids a second full traversal of the dependency tree.
        return 0L
    }

    /**
     * Background-reclaim deletion remnants under [parent]: directories marked
     * with [InstanceRemoval.REMOVAL_MARKER] (interrupted background purge) and
     * `.name.sillyclient-removing-<uuid>` renames left by older builds.
     */
    fun sweepRemovalRemnants(parent: File, instanceId: String) {
        val remnants = parent.listFiles { file ->
            file.isDirectory && (InstanceRemoval.RENAME_PATTERN.matches(file.name) ||
                File(file, InstanceRemoval.REMOVAL_MARKER).isFile)
        }?.takeIf { it.isNotEmpty() } ?: return
        KeepAlive.acquire(this)
        Thread {
            try {
                for (remnant in remnants) {
                    try {
                        NativeTreeRemoval(processSupervisor).remove(listOf(remnant), parent, instanceId)
                        runtimeDiagnostic("removal.stale_purged")
                    } catch (error: Exception) {
                        runtimeDiagnostic("removal.stale_purge_failed type=${error.javaClass.simpleName} msg=${error.message?.take(160)}")
                    }
                }
            } finally {
                runOnUiThread { KeepAlive.release(applicationContext) }
            }
        }.apply {
            name = "SC-removal-stale-purge"
            isDaemon = true
        }.start()
    }

    /** Plan only disposable, inactive files; instance directories are never garbage. */
    fun cleanGarbage(
        @Suppress("UNUSED_PARAMETER") dryRun: Boolean,
        activeInstanceIds: List<String>? = null,
        activeCoverPaths: List<String>? = null
    ): org.json.JSONArray {
        val items = org.json.JSONArray()
        cleanupService.scan(activeInstanceIds, activeCoverPaths).forEach { item ->
            items.put(JSONObject()
                .put("path", item.path)
                .put("type", item.type)
                .put("sizeBytes", item.sizeBytes)
                .put("description", item.description)
                .put("token", item.token))
        }
        return items
    }

    fun deleteGarbageItem(path: String, token: String?): Long = cleanupService.delete(path, token)

    fun scanInstanceMaintenance(instanceId: String, installPath: String? = null): InstanceMaintenance.Scan =
        runInstanceMaintenance(instanceId) { instanceMaintenance.scan(it, installPath) }

    fun applyInstanceMaintenance(
        instanceId: String,
        scanId: String,
        selected: List<InstanceMaintenance.Selection>,
        installPath: String? = null
    ): InstanceMaintenance.Applied = runInstanceMaintenance(instanceId) {
        instanceMaintenance.apply(it, scanId, selected, installPath).also {
            instanceRepository.invalidate(RuntimePaths.from(this).serverDirFor(instanceId, installPath, create = false))
        }
    }

    fun listInstanceMaintenanceRecovery(instanceId: String, installPath: String? = null): InstanceMaintenance.RecoveryList =
        runInstanceMaintenance(instanceId) { instanceMaintenance.listRecovery(it, installPath) }

    fun restoreInstanceMaintenance(instanceId: String, recoveryId: String, token: String?, installPath: String? = null): InstanceMaintenance.Restored =
        runInstanceMaintenance(instanceId) {
            instanceMaintenance.restore(it, recoveryId, token, installPath).also {
                if (it.success) instanceRepository.invalidate(RuntimePaths.from(this).serverDirFor(instanceId, installPath, create = false))
            }
        }

    private fun <T> runInstanceMaintenance(
        instanceId: String,
        operationId: String? = null,
        action: (String) -> T
    ): T {
        val id = RuntimePaths.normalizeInstanceId(instanceId)
        require(id == instanceId) { "实例标识无效，请返回控制台刷新实例列表后重试" }
        // 运行态互斥：酒馆进程存活或前台可见时禁止任何实例目录维护。
        check(!serverReady && !isWebViewVisible) { "请先退出酒馆页面并停止实例，再执行此操作" }
        // 仅本实例自身的进程会阻塞维护；其他实例的后台进程与本实例目录无关。
        check(!processSupervisor.hasProcesses(id)) { "实例相关进程尚未完全退出，请稍候重试" }
        // 其他实例的挂起任务（例如仍在进行的安装）不被本实例维护静默取消。
        val pending = operations.current()
        if (pending != null && pending.instanceId != id) {
            check(!operations.hasPendingWork()) { "实例「${pending.instanceId}」仍有任务进行中，请等待完成或取消后再试" }
        }
        val operation = operations.begin(id, operationId?.takeIf { it.isNotBlank() })
        KeepAlive.acquire(this)
        return try {
            operations.run(operation) { action(id) }
        } finally {
            operations.finish(operation)
            KeepAlive.release(this)
        }
    }

    /** 强类型实例及环境数据结构，取代历史未命名的通用元组。 */
    data class InstanceSummary(
        val instanceId: String,
        val version: String,
        val path: String,
        val sizeBytes: Long,
        val hasServer: Boolean
    )
    data class InstanceDetails(
        val version: String,
        val path: String,
        val sizeBytes: Long,
        val createdAt: String,
        val status: String
    )
    data class InsetsRect(
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int
    )

    private fun pollUntilReady(
        url: String,
        process: Process,
        operation: OperationCoordinator.Operation
    ): Boolean {
        val started = System.nanoTime()
        val deadline = started + TimeUnit.SECONDS.toNanos(60)
        var nextLog = started + TimeUnit.SECONDS.toNanos(5)
        val readiness = TavernReadiness(timeoutMillis = 300)
        while (System.nanoTime() < deadline) {
            operations.ensureCurrent(operation)
            if (process.isAlive) {
                val result = readiness.probe(url)
                if (result.terminal) {
                    pushError("本地酒馆拒绝访问 (HTTP ${result.status})，请检查实例认证和白名单配置")
                    return false
                }
                if (result.ready) {
                    operations.ensureCurrent(operation)
                    operations.commit(operation) {
                        check(serverProcess === process && process.isAlive) { "Server process changed" }
                        serverReady = true
                    }
                    appendLog("[OK] SillyTavern is online at $url")
                    pushProgress(100f, "Ready")
                    pushReady(true, operation)
                    refreshLogToCompose(operation)
                    return true
                }
            }
            operations.ensureCurrent(operation)
            if (!process.isAlive) {
                appendLog("[ERR] Node process exited (code ${process.exitValue()})")
                appendLog("[ERR] Check server.log for details")
                setStatus("Server crashed")
                pushError("Node.js 进程已退出 (code ${process.exitValue()}),请检查 server.log")
                return false
            }
            val now = System.nanoTime()
            if (now >= nextLog) {
                appendLog("... waiting for server (${TimeUnit.NANOSECONDS.toSeconds(now - started)}s)")
                nextLog = now + TimeUnit.SECONDS.toNanos(5)
            }
            Thread.sleep(200)
        }
        appendLog("[ERR] Backend not ready after timeout")
        setStatus("No response")
        pushError("酒馆启动超时，请检查实例日志")
        return false
    }

    // ============================================
    // HELPERS — push to Capacitor JS via TarvenEnvPlugin.notify
    // ============================================

    private fun notifyRuntime(
        event: String,
        data: JSObject,
        operation: OperationCoordinator.Operation? = runtimeEventContext(),
        allowCancelled: Boolean = false
    ) {
        operation?.let {
            data.put("instanceId", it.instanceId)
            data.put("operationId", it.operationId)
        }
        if (operation != null && !allowCancelled && !operations.isCurrent(operation)) return
        TarvenEnvPlugin.notify(event, data) {
            !isDestroyed && (operation == null || allowCancelled || operations.isCurrent(operation))
        }
    }

    private fun pushLog(
        line: String,
        operation: OperationCoordinator.Operation? = runtimeEventContext(),
        instanceId: String? = null
    ) {
        val data = JSObject().put("message", line.take(16_384))
        instanceId?.let { data.put("instanceId", it) }
        notifyRuntime("log", data, operation)
    }

    private fun pushCommandLog(
        line: String, operation: OperationCoordinator.Operation?, instanceId: String, generation: Long
    ) {
        val data = JSObject().put("message", line.take(16_384)).put("instanceId", instanceId).put("source", "command")
        operation?.let { data.put("operationId", it.operationId) }
        TarvenEnvPlugin.notify("log", data) {
            !isDestroyed && processSupervisor.generation() == generation &&
                (operation == null || operations.isCurrent(operation))
        }
    }

    private fun pushProgress(pct: Float, text: String? = null) {
        val d = JSObject().put("percent", pct.toInt())
        if (text != null) d.put("stage", text)
        notifyRuntime("progress", d)
    }

    /**
     * Maintenance operations run outside the launch pipeline, so their progress
     * is emitted without an operation scope: the WebView coordinator delivers
     * such events through its legacy channel while no cancellation is active.
     */
    private fun pushMaintenanceProgress(instanceId: String, percent: Int, text: String) {
        val data = JSObject().put("percent", percent).put("stage", text).put("instanceId", instanceId)
        TarvenEnvPlugin.notify("progress", data) { !isDestroyed }
    }

    /** Forwards raw SC_TRANSFER lines from the copy script as user-visible progress. */
    private fun transferProgressForwarder(instanceId: String): (String) -> Unit {
        var lastPercent = -1
        return { line ->
            val json = runCatching { JSONObject(line.removePrefix("SC_TRANSFER").trim()) }.getOrNull()
            if (json != null) {
                val stage = json.optString("stage", "copy")
                val files = json.optLong("files", 0)
                val bytes = json.optLong("bytes", 0)
                val totalFiles = json.optLong("totalFiles", 0)
                val totalBytes = json.optLong("totalBytes", 0)
                val percent = when {
                    stage == "copy" && totalBytes > 0 -> ((bytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                    stage == "copy" && totalFiles > 0 -> ((files * 100) / totalFiles).toInt().coerceIn(0, 100)
                    stage == "verify" && totalFiles > 0 -> ((files * 100) / totalFiles).toInt().coerceIn(0, 100)
                    else -> 0
                }
                if (stage == "scan" || percent != lastPercent) {
                    lastPercent = percent
                    val text = when (stage) {
                        "scan" -> "实例迁移 · 正在扫描 ${files} 项"
                        "copy" -> "实例迁移 · 已复制 ${files}/${totalFiles} 个文件"
                        "verify" -> "实例迁移 · 校验 ${files}/${totalFiles} 项"
                        else -> "实例迁移"
                    }
                    pushMaintenanceProgress(instanceId, percent, text)
                }
            }
        }
    }

    private fun pushReady(
        ready: Boolean,
        operation: OperationCoordinator.Operation? = runtimeEventContext(),
        allowCancelled: Boolean = false
    ) {
        val d = JSObject().put("ready", ready)
        if (ready) {
            d.put("url", tavernUrl)
            d.put("port", tavernPort)
        }
        notifyRuntime("ready", d, operation, allowCancelled)
    }

    private fun pushError(message: String) {
        notifyRuntime("error", JSObject().put("message", message))
    }

    private fun setStatus(t: String) { pushLog(t) }
    private fun updateProgress(pct: Int, text: String? = null) { pushProgress(pct.toFloat(), text) }
    private fun appendLog(line: String) {
        com.sillyclient.runtime.Diag.append(RuntimePaths.from(this).tarvenHome, line)
        pushLog(line)
    }

    private fun runtimeEventContext(): OperationCoordinator.Operation? =
        operations.context() ?: operations.current()?.takeIf { it.instanceId == currentTavernInstanceId }

    /** Read server.log and push its tail to Capacitor JS. */
    private fun refreshLogToCompose(operation: OperationCoordinator.Operation? = operations.current()) {
        Thread {
            val paths = RuntimePaths.from(this)
            val logFile = File(paths.logsDir, "server.log")
            if (!logFile.exists()) return@Thread
            val lines = runCatching { LogService.tail(logFile) }.getOrDefault(emptyList())
            for (line in lines) pushLog(line, operation)
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


    private fun copyBaseRuntimeExcludingData(srcDir: File, destDir: File) {
        val entries = srcDir.listFiles() ?: return
        for (entry in entries) {
            ensureOperationActive()
            val name = entry.name
            if (name in setOf("data", ".git", "node_modules", "config.yaml", ".sc-identity", InstanceInstaller.DEPENDENCY_MARKER) ||
                name.startsWith(InstanceInstaller.STAGING_PREFIX)) continue
            val target = File(destDir, name)
            if (entry.isDirectory) {
                copyRuntimeTree(entry, target)
            } else if (!target.exists()) {
                require(ManagedFiles.isUnlinked(entry)) { "Linked base runtime files are not supported" }
                entry.inputStream().use { input -> target.outputStream().use { copyWhileActive(input, it) } }
            }
        }
    }

    private fun copyRuntimeTree(source: File, destination: File) {
        source.walkTopDown().onEnter {
            ensureOperationActive()
            require(ManagedFiles.isUnlinked(it)) { "Linked runtime directories are not supported" }
            true
        }.forEach { file ->
            ensureOperationActive()
            require(ManagedFiles.isUnlinked(file)) { "Linked runtime files are not supported" }
            val target = File(destination, file.relativeTo(source).path)
            if (file.isDirectory) target.mkdirs()
            else if (!target.exists()) {
                target.parentFile?.mkdirs()
                file.inputStream().use { input -> target.outputStream().use { copyWhileActive(input, it) } }
            }
        }
    }


    override fun onDestroy() {
        externalPopups.clear()
        // bundledRuntime 是应用级单例（类文档约定 per-app），其 worker 由
        // Activity 生命周期关闭会在同进程复用时毒化后续全部启动，故不在此关闭。
        operations.close()
        processSupervisor.close()
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
        serverProcess = null
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("SillyClientAndroidDownloads")
            if (::renderEngineManager.isInitialized) {
                renderEngineManager.detachBridges(webView)
            }
        }
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
