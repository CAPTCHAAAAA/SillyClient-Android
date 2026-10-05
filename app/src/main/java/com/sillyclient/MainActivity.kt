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
import com.sillyclient.runtime.CompanionPresetInstaller
import com.sillyclient.runtime.CompanionPresetRequest
import com.sillyclient.runtime.CompanionPresetTransaction
import com.sillyclient.runtime.RuntimePaths
import com.sillyclient.runtime.RuntimeFileUtils
import com.sillyclient.runtime.SourceArchiveCache
import com.sillyclient.runtime.CleanupService
import com.sillyclient.runtime.InstanceRepository
import com.sillyclient.runtime.InstanceMaintenance
import com.sillyclient.runtime.InstanceInstaller
import com.sillyclient.runtime.DependencyInstaller
import com.sillyclient.runtime.DependencyArchive
import com.sillyclient.runtime.DependencyTrees
import com.sillyclient.runtime.FrontendBundlePrebuild
import com.sillyclient.runtime.NativeTreeRemoval
import com.sillyclient.runtime.InstanceRelocation
import com.sillyclient.runtime.InstanceRename
import com.sillyclient.runtime.SourceDownloader
import com.sillyclient.runtime.BundledDependencyArchives
import com.sillyclient.runtime.BundledRuntime
import com.sillyclient.runtime.LogService
import com.sillyclient.runtime.MigrationPolicy
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

    private val bundledReleaseAsset: String? by lazy {
        "bundled/sillytavern-release.zip"
            .takeIf { assets.list("bundled")?.contains("sillytavern-release.zip") == true }
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
                extractNativeLibs(paths)
                    instanceInstaller(paths).prepare(
                        targetServerDir,
                        ensureActive = { operations.ensureCurrent(operation) },
                        extract = { directory ->
                            appendLog("> Provisioning [$instanceId]...")
                            updateProgress(2, "Initializing")
                            if (localZipPath != null) {
                                updateProgress(50, "Extracting local zip")
                                extractLocalZip(File(localZipPath), directory)
                            } else if (version in setOf("stable", "release") && bundledReleaseAsset != null) {
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
                            updateProgress(85, "Installing dependencies")
                            runNpmInstall(paths, directory)
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
                updateProgress(96, "Preparing frontend")
                prebuildFrontendBundle(paths, targetServerDir, operation)
                updateProgress(97, "Starting server")
                launched = startServer(paths, targetServerDir, port, config, operation)
                check(launched != null) { "Node.js 服务启动失败，请检查实例完整性" }
                appendLog("[OK] Node.js process launched")
                updateProgress(99, "Waiting for server")
                if (pollUntilReady(tavernUrl, launched, operation)) {
                    operations.commit(operation) {
                        presetTransaction?.commit()
                        extensionsTransaction?.commit()
                    }
                    // Archive after the server is ready so the heavy tree copy cannot
                    // compete with Node's cold-start dependency reads.
                    archiveDependenciesInBackground(paths, targetServerDir)
                    // Build the shared tree from legacy local dependencies, then retire them.
                    promoteLocalDependenciesInBackground(paths, targetServerDir)
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

    private fun extractNativeLibs(@Suppress("UNUSED_PARAMETER") paths: RuntimePaths) {
        bundledRuntime.awaitReady(::ensureOperationActive)
    }

    /**
     * Tree-backed instances resolve server modules through the shared dependency
     * tree, but SillyTavern's own webpack build at startup cannot see that tree,
     * leaving /lib.js missing and the WebView grey. The prebuild compiles the
     * bundle through the tree before the server starts; a local node_modules
     * instance simply keeps building on its own.
     */
    private fun prebuildFrontendBundle(paths: RuntimePaths, instanceDirectory: File, operation: OperationCoordinator.Operation) {
        val treeModules = dependencyArchive(paths)
            .lockKey(File(instanceDirectory, "package-lock.json"))
            ?.let { dependencyTrees(paths).modulesFor(it) } ?: return
        val ready = FrontendBundlePrebuild(paths, operations, processSupervisor, ::appendLog, ::runtimeDiagnostic)
            .ensure(instanceDirectory, treeModules, operation, writeEsmResolutionBridge(paths))
        if (!ready) {
            throw IllegalStateException("前端资源预构建失败，无法提供页面脚本；请重试启动，若持续失败请查看 prebuild.log")
        }
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
            // The bridge is inert without SILLYCLIENT_NODE_MODULES, so it loads for
            // local-node_modules instances too and simply changes nothing there.
            val bridge = writeEsmResolutionBridge(paths)
            env["NODE_OPTIONS"] = "--max-old-space-size=2048 --import $bridge"
            // V8 bytecode cache: repeat launches skip re-parsing thousands of CJS
            // modules, which dominates SillyTavern cold-start time on Android.
            env["NODE_COMPILE_CACHE"] = File(paths.tarvenHome, "node-compile-cache").apply { mkdirs() }.absolutePath
            // Resolve modules from the shared private-storage tree; a local
            // node_modules inside the instance directory still takes precedence.
            dependencyArchive(paths).lockKey(File(targetServerDir, "package-lock.json"))?.let { lockKey ->
                val modules = dependencyTrees(paths).modulesFor(lockKey)
                if (modules.isDirectory) {
                    env["NODE_PATH"] = modules.absolutePath
                    env["SILLYCLIENT_NODE_MODULES"] = modules.absolutePath
                }
            }
            // ESM ignores NODE_PATH entirely, and tree-backed instances carry no
            // local node_modules; without this bridge every ESM import in the
            // server dies at startup with MODULE_NOT_FOUND.
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

    /**
     * Node's ESM resolver ignores NODE_PATH, so shared-tree instances need a
     * resolve hook that retries failed bare specifiers from the tree. The bridge
     * only activates when SILLYCLIENT_NODE_MODULES is present at launch.
     */
    private fun writeEsmResolutionBridge(paths: RuntimePaths): String {
        val bridge = File(paths.tarvenHome, "sc-esm-bridge.mjs")
        bridge.writeText(
            """
            const tree = process.env.SILLYCLIENT_NODE_MODULES;
            if (tree) {
              const { registerHooks } = await import('node:module');
              const { pathToFileURL } = await import('node:url');
              const parent = pathToFileURL(tree.endsWith('/') ? tree : tree + '/').href;
              registerHooks({
                resolve(specifier, context, nextResolve) {
                  try {
                    return nextResolve(specifier, context);
                  } catch (error) {
                    if (specifier.startsWith('.') || specifier.startsWith('/') || specifier.startsWith('#') ||
                        specifier.startsWith('node:') || specifier.startsWith('file:') ||
                        specifier.startsWith('data:')) throw error;
                    return nextResolve(specifier, { ...context, parentURL: parent });
                  }
                },
              });
            }
            """.trimIndent()
        )
        return bridge.absolutePath
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
        val name = bundledReleaseAsset ?: error("Bundled source archive is unavailable")
        val tmpDir = paths.tmpDir.apply { check(isDirectory || mkdirs()) { "Cannot prepare the staging directory" } }
        val temp = File.createTempFile("bundled-release-", ".zip", tmpDir)
        return try {
            assets.open(name).use { input ->
                FileOutputStream(temp).use { output -> copyWhileActive(input, output) }
            }
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
                // Skip the archive's node_modules only when the shared tree or a
                // cached dependency archive can rebuild them; a tree carrying
                // user-installed extras must be extracted in full or those
                // packages would be lost.
                skipTopLevel = if (zipModulesCovered(zipFile)) setOf("node_modules") else emptySet(),
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

    /** Peeks the ZIP's top-level package-lock.json; true when dependencies are rebuildable. */
    private fun zipModulesCovered(zipFile: File): Boolean {
        return try {
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entry = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('/') == "package-lock.json" }
                    .firstOrNull { it.name.count { c -> c == '/' } <= 1 }
                    ?: return false
                if (entry.size > com.sillyclient.runtime.DependencyArchive.MAX_LOCK_BYTES) return false
                val lockKey = com.sillyclient.runtime.DependencyArchive
                    .lockKeyFor(zip.getInputStream(entry).readBytes()) ?: return false
                val paths = RuntimePaths.from(this)
                dependencyTrees(paths).containsComplete(lockKey) ||
                    dependencyArchive(paths).hasArchiveFor(lockKey)
            }
        } catch (_: Exception) {
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

    private fun runNpmInstall(paths: RuntimePaths, targetServerDir: File): Boolean {
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
        // An already-complete local tree (full extraction of an uncovered archive)
        // needs no install here; the background promotion hands it to the shared
        // tree after first launch.
        if (DependencyInstaller.hasRequiredPackages(targetServerDir)) return true
        val archive = dependencyArchive(paths)
        val trees = dependencyTrees(paths)
        val lockFile = File(targetServerDir, "package-lock.json")
        val lockKey = archive.lockKey(lockFile)
        // Dependencies live in a private-storage install root shared by every
        // instance with the same lock; the server resolves them via NODE_PATH.
        // Staging on external FUSE storage would be an order of magnitude slower.
        if (lockKey != null) {
            return trees.buildExclusively(lockKey) {
                val treeRoot = trees.adopt(lockKey, lockFile) { ensureOperationActive() }
                if (DependencyInstaller.hasRequiredPackages(treeRoot)) return@buildExclusively true
                if (restoreDependencies(archive, lockKey, treeRoot)) return@buildExclusively true
                if (restoreDependenciesFromSibling(paths, lockKey, treeRoot)) return@buildExclusively true
                DependencyInstaller(paths, operations, processSupervisor, ::appendLog, ::runtimeDiagnostic)
                    .install(treeRoot, operation)
            }
        }
        return DependencyInstaller(paths, operations, processSupervisor, ::appendLog, ::runtimeDiagnostic)
            .install(targetServerDir, operation)
    }

    /** An instance is dependency-complete with a local tree or the shared tree for its lock. */
    private fun instanceDependenciesComplete(directory: File): Boolean {
        if (DependencyInstaller.hasRequiredPackages(directory)) return true
        val paths = RuntimePaths.from(this)
        val lockKey = dependencyArchive(paths).lockKey(File(directory, "package-lock.json")) ?: return false
        return dependencyTrees(paths).containsComplete(lockKey)
    }

    private fun dependencyArchive(paths: RuntimePaths) =
        DependencyArchive(File(paths.tarvenHome, "dependency-archives"))

    private fun dependencyTrees(paths: RuntimePaths) =
        DependencyTrees(File(paths.tarvenHome, "dependency-trees"))

    /** Restores an archived dependency tree; any archive problem falls back to npm. */
    private fun restoreDependencies(
        archive: DependencyArchive,
        lockKey: String,
        directory: File
    ): Boolean {
        val started = System.nanoTime()
        return try {
            archive.restore(lockKey, directory, ::ensureOperationActive) &&
                DependencyInstaller.hasRequiredPackages(directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runtimeDiagnostic("deps.archive.unavailable ${error.message?.take(160)}")
            false
        }.also { restored ->
            if (restored) {
                appendLog("[OK] 依赖归档命中，已跳过网络安装")
                runtimeDiagnostic("deps.archive.restored key=${lockKey.take(12)} " +
                    "elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
            }
        }
    }

    /** Reuses another instance's dependency tree with the same lock before falling back to network installs. */
    private fun restoreDependenciesFromSibling(paths: RuntimePaths, lockKey: String, dependencyRoot: File): Boolean {
        val started = System.nanoTime()
        val target = File(dependencyRoot, "node_modules")
        if (target.exists()) return false
        val archive = dependencyArchive(paths)
        val siblings = mutableListOf<File>()
        siblings.addAll(paths.installLocations.entries().values)
        paths.serversDir.listFiles()?.filter { it.isDirectory }?.let(siblings::addAll)
        for (sibling in siblings.distinctBy { it.canonicalPath }) {
            ensureOperationActive()
            if (sibling.canonicalFile == dependencyRoot.canonicalFile) continue
            val siblingLock = runCatching { archive.lockKey(File(sibling, "package-lock.json")) }.getOrNull()
                ?: continue
            if (siblingLock != lockKey) continue
            val source = File(sibling, "node_modules")
            if (!source.isDirectory || source.listFiles().isNullOrEmpty()) continue
            return try {
                appendLog("> 正在复用现有实例的依赖...")
                copyDependencyTree(source, target)
                if (DependencyInstaller.hasRequiredPackages(dependencyRoot)) {
                    appendLog("[OK] 已从相同版本的现有实例复用依赖，跳过网络安装")
                    runtimeDiagnostic("deps.sibling.restored key=${lockKey.take(12)} " +
                        "elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
                    true
                } else {
                    appendLog("[WARN] 复用的依赖未通过完整性校验，回退网络安装")
                    runCatching { target.deleteRecursively() }
                    false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runtimeDiagnostic("deps.sibling.unavailable ${error.message?.take(160)}")
                runCatching { target.deleteRecursively() }
                false
            }
        }
        return false
    }

    /** Copies a node_modules tree; symbolic links are recreated so npm layout stays intact. */
    private fun copyDependencyTree(source: File, destination: File) {
        fun copyDir(from: File, to: File) {
            ensureOperationActive()
            to.mkdirs()
            val entries = from.listFiles() ?: return
            for (entry in entries) {
                ensureOperationActive()
                val target = File(to, entry.name)
                if (entry.isDirectory) {
                    copyDir(entry, target)
                } else if (java.nio.file.Files.isSymbolicLink(entry.toPath())) {
                    target.parentFile?.mkdirs()
                    val link = java.nio.file.Files.readSymbolicLink(entry.toPath()).toString()
                    java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(link))
                } else {
                    entry.inputStream().use { input ->
                        FileOutputStream(target).use { output -> copyWhileActive(input, output) }
                    }
                    if (entry.canExecute()) target.setExecutable(true, false)
                }
            }
        }
        copyDir(source, destination)
    }

    /** Archives a published instance's dependencies for the next same-lock install. */
    private fun archiveDependenciesInBackground(paths: RuntimePaths, instanceDirectory: File) {
        val archive = dependencyArchive(paths)
        val trees = dependencyTrees(paths)
        val lockKey = archive.lockKey(File(instanceDirectory, "package-lock.json")) ?: return
        // Prefer the shared tree; legacy instances only hold a local node_modules.
        val source = trees.rootFor(lockKey)
            .takeIf { File(it, "node_modules").isDirectory }
            ?: instanceDirectory.takeIf { File(it, "node_modules").isDirectory }
            ?: return
        Thread {
            val started = System.nanoTime()
            try {
                if (archive.archive(source, lockKey)) {
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

    /**
     * Builds the shared dependency tree for a legacy instance that still carries
     * a local node_modules, preferring the bundled archive over a slow per-file
     * copy from external storage. Once the tree serves the lock, the local copy
     * is retired so launches resolve from private storage and relocation no
     * longer copies the dependency tree.
     */
    private fun promoteLocalDependenciesInBackground(paths: RuntimePaths, instanceDirectory: File) {
        val localModules = File(instanceDirectory, "node_modules")
        val archive = dependencyArchive(paths)
        val trees = dependencyTrees(paths)
        val lockFile = File(instanceDirectory, "package-lock.json")
        val lockKey = archive.lockKey(lockFile) ?: return
        // A leftover retired copy also re-enters so a failed cleanup retries.
        if (!localModules.isDirectory && !File(instanceDirectory, RETIRED_MODULES_NAME).exists()) return
        Thread {
            val started = System.nanoTime()
            try {
                val promoted = trees.buildExclusively(lockKey) {
                    if (trees.containsComplete(lockKey)) return@buildExclusively true
                    val treeRoot = trees.adopt(lockKey, lockFile) { }
                    if (!File(treeRoot, "node_modules").exists() &&
                        !restoreDependencies(archive, lockKey, treeRoot)
                    ) {
                        runtimeDiagnostic("deps.local.promote.begin key=${lockKey.take(12)}")
                        copyDependencyTree(localModules, File(treeRoot, "node_modules"))
                    }
                    if (DependencyInstaller.hasRequiredPackages(treeRoot)) {
                        runtimeDiagnostic("deps.local.promoted key=${lockKey.take(12)} " +
                            "elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
                        true
                    } else {
                        runtimeDiagnostic("deps.local.promote_incomplete; keeping local modules")
                        false
                    }
                }
                if (!promoted) return@Thread
                // Retire the local copy with an O(1) rename: module resolution
                // immediately falls through to the shared tree, and the retired
                // directory is then removed natively. The per-file Java walker
                // used here before saturated FUSE for minutes and starved every
                // concurrent install, import and relocation on the volume.
                val retired = File(instanceDirectory, RETIRED_MODULES_NAME)
                if (retired.exists()) retireModules(retired, instanceDirectory, lockKey, started)
                if (localModules.isDirectory && localModules.renameTo(retired)) {
                    runtimeDiagnostic("deps.local.retired key=${lockKey.take(12)}")
                    retireModules(retired, instanceDirectory, lockKey, started)
                } else if (localModules.exists()) {
                    runtimeDiagnostic("deps.local.retire_failed key=${lockKey.take(12)}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runtimeDiagnostic("deps.local.promote_failed ${error.message?.take(160)}")
            }
        }.apply {
            name = "SC-dependency-promote"
            isDaemon = true
        }.start()
    }

    private fun retireModules(retired: File, instanceDirectory: File, lockKey: String, started: Long) {
        try {
            NativeTreeRemoval(processSupervisor).remove(
                listOf(retired), instanceDirectory, instanceDirectory.name
            )
            if (!retired.exists()) {
                runtimeDiagnostic("deps.local.demoted key=${lockKey.take(12)} " +
                    "elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
            }
        } catch (error: Exception) {
            // The retired copy no longer affects resolution; leave it behind and
            // let the next promotion retry the removal.
            runtimeDiagnostic("deps.local.retire_cleanup_failed ${error.message?.take(120)}")
        }
    }

    private fun runtimeDiagnostic(message: String) {
        android.util.Log.i(TAG, "runtime ${message.take(512)}")
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

    /** 扫描本地已存在的酒馆实例。返回五元组:instanceId, version, path, sizeBytes, hasServer。 */
    fun scanInstances(): List<Quint<String, String, String, Long, Boolean>> {
        return instanceRepository.scan().map { info ->
            Quint(info.instanceId, info.version, info.path, info.sizeBytes, info.hasServer)
        }
    }

    /** 实例详情:version, path, sizeBytes, createdAt, status。 */
    fun getInstanceInfo(instanceId: String, port: Int, installPath: String? = null): Quint<String, String, Long, String, String> {
        val paths = RuntimePaths.from(this)
        val dir = paths.serverDirFor(instanceId, installPath, create = false)
        val info = instanceRepository.info(dir)
        return Quint(info.version, info.path, info.sizeBytes, info.createdAt, info.status)
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

    private fun uninstallInstanceFiles(instanceId: String, targetDir: File): Long {
        val paths = RuntimePaths.from(this)
        val operation = operations.context() ?: error("Missing removal operation")
        runtimeDiagnostic("removal.begin")
        com.sillyclient.runtime.InstanceRemoval.remove(
            targetDir, paths.installLocations.allowedRootFor(targetDir),
            verifyIdentity = {
                require(paths.installLocations.resolve(instanceId, targetDir.absolutePath).canonicalFile == targetDir.canonicalFile)
            },
            ensureActive = { operations.ensureCurrent(operation) },
            removeChildren = { children ->
                NativeTreeRemoval(processSupervisor).remove(
                    children, targetDir, instanceId, operation,
                    ensureActive = { operations.ensureCurrent(operation) },
                    onProgress = { progress ->
                        runtimeDiagnostic("removal $progress")
                        pushLog("[cleanup] 已处理 ${progress.removedEntries} 项 · 耗时 ${progress.elapsedMillis / 1000} 秒",
                            null, instanceId)
                    }
                )
            },
            commit = { action -> operations.commit(operation, action) },
            unregister = { paths.installLocations.unregisterAfterDelete(instanceId, targetDir) }
        )
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
        return try {
            operations.run(operation) { action(id) }
        } finally {
            operations.finish(operation)
        }
    }

    /** 简单五元组(Kotlin 标准库无 Quintuple)。 */
    data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
    data class Quartet<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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
    private fun appendLog(line: String) { pushLog(line) }

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

    /**
     * 数据迁移：将旧酒馆目录或 ZIP 压缩包迁入新实例目录。
     * 支持 SAF 目录树 (content://.../tree/...)、SAF 压缩包、本地文件路径及解压排除。
     */
    fun migrateInstance(
        sourcePath: String,
        instanceId: String,
        mode: String,
        includeSecrets: Boolean,
        targetPath: String? = null,
        operationId: String? = null,
        preinstall: PreinstalledExtensionsRequest? = null
    ): Boolean {
        val paths = RuntimePaths.from(this)
        val id = RuntimePaths.normalizeInstanceId(instanceId)
        val managedTarget = paths.launchDirectoryFor(id, targetPath)
        MigrationPolicy.validate(sourcePath, managedTarget, mode, targetPath)
        check(!serverReady && !isWebViewVisible && !operations.hasPendingWork()) { "请先停止当前实例或等待当前任务结束后再迁移" }
        val operation = operations.begin(id, operationId)
        cleanupService.invalidate()
        val stopped = processSupervisor.stopAllAsync()
        val migrated = try {
            operations.run(operation) {
                stopped.get(15, TimeUnit.SECONDS)
                operations.ensureCurrent(operation)
                paths.ensureDirs()
                instanceInstaller(paths).prepare(
                    managedTarget,
                    ensureActive = { operations.ensureCurrent(operation) },
                    extract = { staging ->
                        val verified = migrateInstanceInternal(sourcePath, id, mode, includeSecrets, staging.absolutePath)
                        if (verified && preinstall?.extensionIds?.isNotEmpty() == true) {
                            RuntimeConfiguration(paths, operations, processSupervisor).validateStandardDataRoot(staging, operation)
                            val transaction = PreinstalledExtensionInstaller.install(
                                this, staging, preinstall, operations, operation, ::appendLog
                            )
                            operations.ensureCurrent(operation)
                            transaction.commit()
                        }
                        verified
                    },
                    installDependencies = { directory -> runNpmInstall(paths, directory) },
                    commit = { action -> operations.commit(operation, action) },
                    instanceId = id
                )
                instanceRepository.invalidate(managedTarget)
                updateProgress(100, "Migration verified")
                appendLog("【成功】数据迁移完成，实例 [$instanceId] 已就绪！")
                true
            }
        } finally {
            operations.finish(operation)
        }
        if (migrated) archiveDependenciesInBackground(paths, managedTarget)
        return migrated
    }

    private fun migrateInstanceInternal(
        sourcePath: String,
        instanceId: String,
        mode: String,
        includeSecrets: Boolean,
        targetPath: String? = null
    ): Boolean {
        val paths = RuntimePaths.from(this)
        paths.ensureDirs()
        val targetServerDir = if (!targetPath.isNullOrBlank()) File(targetPath) else paths.serverDirFor(instanceId, create = false)

        val isContentUri = sourcePath.startsWith("content://")
        val effectiveMode = mode

        val modeText = if (effectiveMode == "takeover") "原地接管" else "复制迁移"
        appendLog("【数据迁移】开始${modeText}: $sourcePath")
        updateProgress(10, "Validating migration source")

        // 复制迁移模式
        targetServerDir.mkdirs()

        if (isContentUri) {
            val uri = Uri.parse(sourcePath)
            val isTree = sourcePath.contains("/tree/")
            if (isTree) {
                appendLog("> 正在从系统选择的文件夹提取数据...")
                updateProgress(25, "Accessing document tree")
                val treeDoc = DocumentFile.fromTreeUri(this, uri)
                if (treeDoc == null || !treeDoc.isDirectory) {
                    appendLog("[ERR] 无法访问选中的目录树，可能缺乏访问权限: $sourcePath")
                    return false
                }
                val copiedCount = copyDocumentTreeFiltered(treeDoc, targetServerDir, includeSecrets) { count ->
                    if (count % 50 == 0) {
                        updateProgress(25 + (count / 25).coerceAtMost(60), "Copying data ($count files)")
                    }
                }
                appendLog("[OK] 目录数据提取完成，共复制 $copiedCount 个文件")
            } else {
                appendLog("> 正在解压备份文件流...")
                updateProgress(25, "Extracting backup stream")
                try {
                    val archive = File.createTempFile("migration-", ".zip",
                        paths.tmpDir.apply { check(isDirectory || mkdirs()) { "Cannot prepare the migration staging directory" } })
                    try {
                        contentResolver.openInputStream(uri)?.use { inStream ->
                            FileOutputStream(archive).use { outStream -> copyWhileActive(inStream, outStream) }
                        } ?: throw IOException("无法打开所选文件的输入流")
                        val extracted = com.sillyclient.runtime.SourceArchive.extract(
                            archive, targetServerDir, ::ensureOperationActive,
                            // Skip the archive's node_modules only when the shared tree
                            // can rebuild them; a tree with user-installed extras must
                            // be extracted in full or those packages would be lost.
                            skipTopLevel = if (zipModulesCovered(archive)) setOf("node_modules") else emptySet(),
                            onProgress = { count ->
                                if (count % 100 == 0) {
                                    updateProgress(25 + (count / 60).coerceAtMost(55), "Extracting data ($count files)")
                                }
                            })
                        appendLog("[OK] 备份文件流解压完成，共迁移 $extracted 个文件")
                    } finally {
                        if (!archive.delete() && archive.exists()) appendLog("[WARN] 迁移缓存文件保留待后续清理")
                    }
                } catch (e: Exception) {
                    appendLog("[ERR] 读取文件流失败: ${e.message}")
                    return false
                }
            }
        } else {
            val sourceFile = File(sourcePath)
            val isZip = sourcePath.endsWith(".zip", ignoreCase = true) ||
                    (sourceFile.exists() && sourceFile.isFile && sourceFile.length() > 0)

            if (isZip) {
                if (!sourceFile.exists() || !sourceFile.isFile) {
                    appendLog("[ERR] 来源 ZIP 文件不存在: $sourcePath")
                    return false
                }
                appendLog("> 正在解压旧酒馆备份文件...")
                updateProgress(30, "Extracting backup archive")
                val extracted = com.sillyclient.runtime.SourceArchive.extract(
                    sourceFile, targetServerDir, ::ensureOperationActive,
                    // Skip the archive's node_modules only when the shared tree can
                    // rebuild them; uncovered archives extract in full and runNpmInstall
                    // then keeps their locally complete dependencies as-is.
                    skipTopLevel = if (zipModulesCovered(sourceFile)) setOf("node_modules") else emptySet(),
                    onProgress = { count ->
                        if (count % 100 == 0) {
                            updateProgress(30 + (count / 60).coerceAtMost(45), "Extracting data ($count files)")
                        }
                    })
                appendLog("[OK] 备份解压完成，共迁移 $extracted 个文件")
            } else {
                if (!sourceFile.exists() || !sourceFile.isDirectory) {
                    appendLog("[ERR] 来源目录不存在: $sourcePath")
                    return false
                }

                appendLog("> 正在复制目录数据...")
                updateProgress(30, "Copying directory")
                var copiedFiles = 0
                copyDirectoryFiltered(sourceFile, targetServerDir, includeSecrets) { count ->
                    copiedFiles = count
                    if (copiedFiles % 50 == 0) {
                        updateProgress(30 + (copiedFiles / 20).coerceAtMost(55), "Copying data ($copiedFiles files)")
                    }
                }
                appendLog("[OK] 目录复制完成，共迁移 $copiedFiles 个文件")
            }
        }

        // SourceArchive 无逐条过滤；解压后按用户选择移除密钥文件
        if (!includeSecrets) {
            File(targetServerDir, "secrets.json").delete()
            File(targetServerDir, "secrets.json.enc").delete()
        }

        // 统一检测与补全运行底座 (server.js 及 node_modules)
        val serverJs = File(targetServerDir, "server.js")
        if (!serverJs.exists()) {
            appendLog("> 纯数据备份，正在匹配运行底座...")
            updateProgress(85, "Configuring base runtime")
            val baseInstance = paths.serverDirFor("default", create = false)
            if (baseInstance.exists() && File(baseInstance, "server.js").exists()) {
                copyBaseRuntimeExcludingData(baseInstance, targetServerDir)
                appendLog("[OK] 基础底座配置完成")
            }
        }

        updateProgress(95, "Verifying runtime")
        ensureOperationActive()
        if (!File(targetServerDir, "server.js").isFile) {
            appendLog("[ERR] 备份中未找到 server.js，且没有可用的基础运行底座")
            return false
        }
        if (!File(paths.usrDir, "lib/node_modules/npm/bin/npm-cli.js").isFile) extractNativeLibs(paths)
        if (!MigrationPolicy.verify(targetServerDir, { runNpmInstall(paths, targetServerDir) }, ::instanceDependenciesComplete)) {
            appendLog("[ERR] 迁移依赖安装失败；源文件和迁入的数据均已保留")
            return false
        }
        ensureOperationActive()
        return true
    }

    private fun copyDocumentTreeFiltered(
        treeDoc: DocumentFile,
        targetDir: File,
        includeSecrets: Boolean,
        onProgress: (Int) -> Unit
    ): Int {
        var count = 0
        fun traverse(dirDoc: DocumentFile, currentDest: File) {
            currentDest.mkdirs()
            val files = dirDoc.listFiles()
            for (file in files) {
                ensureOperationActive()
                val name = file.name ?: continue
                if (name == ".git" || name == ".cache" || name == "node_modules") continue
                if (!includeSecrets && (name == "secrets.json" || name == "secrets.json.enc")) continue

                if (file.isDirectory) {
                    val nextDest = safeZipOutputFile(currentDest, name)
                    traverse(file, nextDest)
                } else if (file.isFile) {
                    val outFile = safeZipOutputFile(currentDest, name)
                    val inStream = contentResolver.openInputStream(file.uri)
                        ?: throw IOException("无法读取所选文件: $name")
                    inStream.use {
                        FileOutputStream(outFile).use { outStream ->
                            copyWhileActive(inStream, outStream)
                        }
                    }
                    count++
                    onProgress(count)
                }
            }
        }
        traverse(treeDoc, targetDir)
        return count
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

    private fun copyDirectoryFiltered(
        srcDir: File,
        destDir: File,
        includeSecrets: Boolean,
        onProgress: (Int) -> Unit
    ) {
        var count = 0
        srcDir.walkTopDown()
            .onEnter { it == srcDir || it.name !in setOf(".git", ".cache", "node_modules") }
            .forEach { file ->
            ensureOperationActive()
            require(ManagedFiles.isUnlinked(file)) { "Linked migration files are not supported" }
            val relPath = file.relativeTo(srcDir).path
            if (relPath in setOf(".sc-identity", InstanceInstaller.DEPENDENCY_MARKER) ||
                (!relPath.contains(File.separatorChar) && relPath.startsWith(InstanceInstaller.STAGING_PREFIX))) return@forEach
            if (!includeSecrets && (file.name == "secrets.json" || file.name == "secrets.json.enc")) {
                return@forEach
            }
            val target = File(destDir, relPath)
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                file.inputStream().use { input -> target.outputStream().use { copyWhileActive(input, it) } }
                count++
                onProgress(count)
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
