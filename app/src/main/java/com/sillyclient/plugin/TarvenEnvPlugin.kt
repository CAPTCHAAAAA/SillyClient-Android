package com.sillyclient.plugin

import android.content.Intent
import android.app.AlertDialog
import android.net.Uri
import android.os.Build
import android.Manifest
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.sillyclient.MainActivity
import com.sillyclient.auth.RemoteBasicAuthStore
import com.sillyclient.download.TavernDownloadFiles
import com.sillyclient.runtime.CompanionPresetInstaller
import com.sillyclient.runtime.CompanionPresetRequest
import com.sillyclient.runtime.AppSettingsStore
import com.sillyclient.runtime.ManagedFiles
import com.sillyclient.runtime.PreinstalledExtensionsRequest
import com.sillyclient.runtime.PreinstalledExtensionInstaller
import com.sillyclient.runtime.InstanceMaintenance
import com.sillyclient.runtime.RuntimePaths
import com.sillyclient.storage.InstanceStorageAccess
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Capacitor plugin wrapping the SillyClient tavern startup & reading environment.
 *
 * All heavy lifting lives in MainActivity; this plugin is a thin bridge.
 * Events (progress / log / ready) are pushed from MainActivity via [notify].
 */
@CapacitorPlugin(name = "TarvenEnv", permissions = [
    Permission(alias = InstanceStorageAccess.LEGACY_PERMISSION_ALIAS,
        strings = [Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE])
])
class TarvenEnvPlugin : Plugin() {
    private val releaseExecutor = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "SillyClient-releases").apply { isDaemon = true }
    }
    private var installationPicker: AlertDialog? = null
    private var installationPickerCall: PluginCall? = null
    private var storagePermissionDialog: AlertDialog? = null
    private var storagePermissionCall: PluginCall? = null

    companion object {
        private const val TAG = "SillyClient"
        private var instance: TarvenEnvPlugin? = null

        /** Push event to Capacitor JS listeners. Safe from any thread. */
        fun notify(event: String, data: JSObject, shouldDeliver: () -> Boolean = { true }) {
            val p = instance ?: return
            p.activity?.runOnUiThread {
                if (instance === p && shouldDeliver()) p.notifyListeners(event, data)
            }
        }
    }

    override fun load() {
        instance = this
    }

    override fun handleOnDestroy() {
        cancelStoragePermission()
        releaseExecutor.shutdownNow()
        installationPicker?.setOnCancelListener(null)
        installationPicker?.dismiss()
        installationPicker = null
        installationPickerCall?.reject("cancelled")
        installationPickerCall = null
        if (instance === this) instance = null
        super.handleOnDestroy()
    }

    @PluginMethod
    fun provisionAndStart(call: PluginCall) {
        val act = activity as? MainActivity ?: run {
            call.reject("Not MainActivity")
            return
        }
        val port = call.data.optInt("port", 8000)
        val instanceId = call.data.optString("instanceId", "default")
        val instanceName = call.getString("instanceName")
        val version = call.data.optString("version", "stable")
        val zipballUrl = call.data.optString("zipballUrl", "")
        val localZipPath = call.data.optString("localZipPath", "")
        val companionPreset = call.data.optJSONObject("companionPreset")?.let { preset ->
            val request = CompanionPresetRequest(
                bundleId = preset.optString("bundleId", ""),
                revision = preset.optInt("revision", -1)
            )
            if (request.bundleId != CompanionPresetInstaller.BUNDLE_ID || request.revision > CompanionPresetInstaller.REVISION) {
                call.reject("不支持的主题预设版本")
                return
            }
            request
        }
        val configObj = call.data.optJSONObject("config")
        val config = if (configObj != null) {
            MainActivity.InstanceConfig(
                listen = configObj.optBoolean("listen", false),
                ipv4 = configObj.optBoolean("ipv4", true),
                ipv6 = configObj.optBoolean("ipv6", false),
                dnsIpv6 = configObj.optBoolean("dnsIpv6", false),
                heartbeat = configObj.optInt("heartbeat", 0),
                keepAlive = configObj.optBoolean("keepAlive", false)
            )
        } else {
            MainActivity.InstanceConfig(
                listen = call.data.optBoolean("listen", false),
                ipv4 = call.data.optBoolean("ipv4", true),
                ipv6 = call.data.optBoolean("ipv6", false),
                dnsIpv6 = call.data.optBoolean("dnsIpv6", false),
                heartbeat = call.data.optInt("heartbeat", 0),
                keepAlive = call.data.optBoolean("keepAlive", false)
            )
        }
        val urlArg = if (zipballUrl.isEmpty()) null else zipballUrl
        val localArg = if (localZipPath.isEmpty()) null else localZipPath
        val installPath = if (call.data.isNull("installPath")) null else call.data.optString("installPath", "").ifBlank { null }
        val installPathMode = call.getString("installPathMode", "exact") ?: "exact"
        if (installPathMode !in setOf("root", "exact")) {
            call.reject("Invalid installation path mode")
            return
        }
        val operationId = call.getString("operationId")
        val preinstall = try { parsePreinstall(call) } catch (error: Exception) {
            Log.w(TAG, "reject: ${error.message}")
            call.reject(error.message ?: "Invalid preinstall request", error)
            return
        }
        act.runOnUiThread {
            try {
                val paths = act.runtimePaths
                val destination = paths.launchDirectoryFor(instanceId, installPath, installPathMode, instanceName)
                if (InstanceStorageAccess.requiresPublicAccess(paths, destination) && !requestStorageAccess(call)) {
                    return@runOnUiThread
                }
                act.provisionAndStart(port, instanceId, version, config, urlArg, localArg, companionPreset, operationId, preinstall, installPath, installPathMode, instanceName)
                call.resolve()
            } catch (error: Exception) {
                Log.w(TAG, "reject: ${error.message}")
                call.reject(error.message ?: "Unable to start instance", error)
            }
        }
    }

    private fun parsePreinstall(call: PluginCall): PreinstalledExtensionsRequest? {
        if (!call.data.has("preinstall") || call.data.isNull("preinstall")) return null
        val request = call.data.getJSONObject("preinstall")
        require(request.getInt("revision") == 1) { "不支持的预制安装清单版本" }
        val ids = request.getJSONArray("extensionIds")
        require(ids.length() <= 4) { "预制安装选择过多" }
        val parsed = PreinstalledExtensionsRequest(1, (0 until ids.length()).map { ids.getString(it) })
        val catalog = context.assets.open("preinstalled-extensions/catalog.json").bufferedReader().use { it.readText() }
        PreinstalledExtensionInstaller.selection(catalog, parsed)
        return parsed
    }

    @PluginMethod
    fun openExternalUrl(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val url = call.getString("url") ?: run { call.reject("Missing web address"); return }
        act.runOnUiThread {
            try {
                act.openExternalUrl(url)
                call.resolve()
            } catch (error: Exception) {
                call.reject("无法打开系统浏览器", error)
            }
        }
    }

    @PluginMethod
    fun enterImmersive(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val url = call.data.optString("url", "")
        val target = if (url.isEmpty()) null else url
        val instanceId = call.data.optString("instanceId", "").ifBlank { null }
        val showGestureHint = call.data.optBoolean("showGestureHint", false)
        if (instanceId == null && target != null) {
            act.runOnUiThread {
                try {
                    act.openExternalUrl(target)
                    call.resolve()
                } catch (error: Exception) {
                    call.reject("无法打开系统浏览器", error)
                }
            }
            return
        }
        try {
            val credentials = instanceId?.let { RemoteBasicAuthStore(context).load(it) }
            act.runOnUiThread {
                try {
                    val entered = act.enterTavern(
                        targetUrl = target,
                        basicAuthUsername = credentials?.username,
                        basicAuthPassword = credentials?.password,
                        instanceId = instanceId,
                        showGestureHint = showGestureHint
                    )
                    if (entered) {
                        call.resolve()
                    } else {
                        call.reject("酒馆暂时无法打开")
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "reject: ${error.message}")
                    call.reject(error.message ?: "无法打开酒馆页面", error)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "reject: ${error.message}")
            call.reject(error.message ?: "无法读取远程连接凭据", error)
        }
    }

    @PluginMethod
    fun exitImmersive(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        act.runOnUiThread { act.exitTavern() }
        call.resolve()
    }

    @PluginMethod
    fun returnToTavern(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        act.runOnUiThread {
            try {
                if (act.returnToTavern()) call.resolve()
                else call.reject("酒馆服务尚未就绪，请重新启动实例")
            } catch (error: Exception) {
                Log.w(TAG, "reject: ${error.message}")
                call.reject(error.message ?: "无法返回酒馆页面", error)
            }
        }
    }

    @PluginMethod
    fun closeTavern(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId")?.ifBlank { null }
        val operationId = call.getString("operationId")?.ifBlank { null }
        act.runOnUiThread {
            storagePermissionCall?.takeIf {
                it.methodName == "provisionAndStart" &&
                    (instanceId == null || it.getString("instanceId", "default") == instanceId) &&
                    (operationId == null || it.getString("operationId") == operationId)
            }?.let { cancelStoragePermission() }
            act.closeTavern(instanceId, operationId)
        }
        call.resolve()
    }

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val ret = JSObject()
        ret.put("serverReady", act.isServerReady())
        ret.put("mode", if (act.isTavernVisible()) "tavern" else "launcher")
        ret.put("url", act.getTavernUrl())
        act.getRunningInstanceId()?.let { ret.put("instanceId", it) }
        act.getRunningOperationId()?.let { ret.put("operationId", it) }
        call.resolve(ret)
    }

    /** 拉取 GitHub SillyTavern releases。在子线程执行 HTTP。 */
    @PluginMethod
    fun fetchReleases(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        releaseExecutor.execute {
            try {
                val result = com.sillyclient.runtime.TavernReleaseCatalog(
                    File(context.filesDir, "tarven/tavern-releases.json"),
                    bundledSource = act.bundledTavernSource,
                    diagnostic = { android.util.Log.w(TAG, it) }
                ).loadResult()
                val response = JSObject().put("releases", result.releases)
                result.warning?.let { response.put("warning", it) }
                call.resolve(response)
            } catch (error: Exception) {
                call.reject("Unable to load Tavern versions", error)
            }
        }
    }

    @PluginMethod
    fun pickDirectory(call: PluginCall) {
        val purpose = call.getString("purpose", "source") ?: "source"
        if (purpose !in setOf("installation", "source")) {
            call.reject("Invalid directory selection purpose")
            return
        }

        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        act.runOnUiThread {
            if (purpose == "installation" && !requestStorageAccess(call)) return@runOnUiThread
            startActivityForResult(call, intent, "pickDir")
        }
    }

    /** Called on the UI thread, before any instance operation or file write starts. */
    private fun requestStorageAccess(call: PluginCall): Boolean {
        if (InstanceStorageAccess.isGranted(context)) return true
        if (storagePermissionCall != null) {
            call.reject("请先完成当前存储授权，再重试此操作")
            return false
        }
        storagePermissionCall = call
        storagePermissionDialog = AlertDialog.Builder(activity)
            .setTitle("允许访问实例文件")
            .setMessage("实例将保存到你在创建时选择的文件夹，方便在文件管理器中查看和管理。需要授予存储访问权限；取消不会改存到其他目录，也不会移动现有实例。")
            .setPositiveButton("前往授权") { _, _ ->
                storagePermissionDialog = null
                try {
                    if (InstanceStorageAccess.usesAllFilesAccess(Build.VERSION.SDK_INT)) {
                        val appSettings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"))
                        try {
                            startActivityForResult(call, appSettings, "storageAccessResult")
                        } catch (_: android.content.ActivityNotFoundException) {
                            startActivityForResult(call, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                                "storageAccessResult")
                        }
                    } else {
                        requestPermissionForAlias(InstanceStorageAccess.LEGACY_PERMISSION_ALIAS, call, "legacyStorageAccessResult")
                    }
                } catch (error: Exception) {
                    storagePermissionCall = null
                    call.reject("无法打开存储授权，请在系统应用设置中授权后重试", error)
                }
            }
            .setNegativeButton("取消") { _, _ -> cancelStoragePermission() }
            .setOnCancelListener { cancelStoragePermission() }
            .show()
        return false
    }

    private fun cancelStoragePermission() {
        val pending = storagePermissionCall
        storagePermissionCall = null
        storagePermissionDialog?.setOnCancelListener(null)
        storagePermissionDialog?.dismiss()
        storagePermissionDialog = null
        pending?.reject("cancelled: ${InstanceStorageAccess.DENIED_MESSAGE}")
    }

    @ActivityCallback
    private fun storageAccessResult(call: PluginCall?, result: ActivityResult) {
        resumeStorageOperation(call)
    }

    @PermissionCallback
    private fun legacyStorageAccessResult(call: PluginCall) {
        resumeStorageOperation(call)
    }

    private fun resumeStorageOperation(call: PluginCall?) {
        if (call == null || storagePermissionCall?.callbackId != call.callbackId) return
        storagePermissionCall = null
        if (!InstanceStorageAccess.isGranted(context)) {
            call.reject(InstanceStorageAccess.DENIED_MESSAGE)
            return
        }
        when (call.methodName) {
            "provisionAndStart" -> provisionAndStart(call)
            "pickDirectory" -> pickDirectory(call)
            "relocateInstance" -> relocateInstance(call)
            "migrateLegacyInstances" -> migrateLegacyInstances(call)
            "setInstancesRoot" -> setInstancesRoot(call)
            "uninstallInstance" -> uninstallInstance(call)
            "exportInstance" -> exportInstance(call)
            "importInstanceData" -> importInstanceData(call)
            else -> call.reject("存储授权请求已失效，请重试")
        }
    }

    /** 应用级设置：默认实例存储根。实例始终是该根的直接子目录。 */
    @PluginMethod
    fun getAppSettings(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val paths = act.runtimePaths
        val settings = AppSettingsStore(AppSettingsStore.settingsFile(paths.tarvenHome)).load()
        val response = JSObject()
        response.put("instancesRoot", paths.serversDir.absolutePath)
        // Kept under the build62 key name for compatibility, but it no longer
        // advertises a public root: there is no software default path, creation
        // always requires a user-chosen folder, and this private fallback only
        // hosts instances from the earlier managed-area layout.
        response.put("defaultInstancesRoot", File(paths.appFilesDir, "instances").absolutePath)
        settings.instancesRoot?.let { response.put("configuredInstancesRoot", it) }
        call.resolve(response)
    }

    /** 保存或清除实例存储根；留空 path 表示清除已保存的选择（创建时需要重新选择）。 */
    @PluginMethod
    fun setInstancesRoot(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val paths = act.runtimePaths
        act.runOnUiThread {
            val requested = call.getString("path")?.trim()
                ?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()?.takeIf { it.isNotEmpty() }
            if (requested == null) {
                persistInstancesRoot(call, paths, null)
                return@runOnUiThread
            }
            val destination = try {
                require(requested.none { it.code < 32 } && !requested.contains("://") && !requested.startsWith("file:")) {
                    "存储路径必须是本机绝对路径"
                }
                val directory = File(requested)
                require(directory.isAbsolute) { "存储路径必须是本机绝对路径" }
                require(paths.installLocations.registeredDirectories().none {
                    it == directory || ManagedFiles.isWithin(directory, it)
                }) { "存储路径不能位于某个实例目录之内" }
                paths.installLocations.allowedRootFor(directory)
                directory
            } catch (error: Exception) {
                Log.w(TAG, "reject: ${error.message}")
                call.reject(error.message ?: "存储路径无效", error)
                return@runOnUiThread
            }
            if (InstanceStorageAccess.requiresPublicAccess(paths, destination) && !requestStorageAccess(call)) {
                return@runOnUiThread
            }
            persistInstancesRoot(call, paths, destination.absolutePath)
        }
    }

    private fun persistInstancesRoot(call: PluginCall, paths: RuntimePaths, root: String?) {
        try {
            val file = AppSettingsStore.settingsFile(paths.tarvenHome)
            AppSettingsStore(file).save(AppSettingsStore.Snapshot(instancesRoot = root))
            val effective = if (root != null) root
            else File(paths.appFilesDir, "instances").absolutePath
            // Choosing a root is also the moment leftover deletion remnants in
            // that directory (from interrupted purges) become reclaimable.
            (activity as? MainActivity)?.sweepRemovalRemnants(File(effective), "maintenance")
            call.resolve(JSObject().put("instancesRoot", effective).put("configured", root != null))
        } catch (error: Exception) {
            Log.w(TAG, "reject: ${error.message}")
            call.reject(error.message ?: "无法保存存储路径设置", error)
        }
    }

    /** 共享控制台的小型文本/JSON 导出，Android 通过 SAF 选择保存位置。 */
    @PluginMethod
    fun saveTextFile(call: PluginCall) {
        val fileName = TavernDownloadFiles.sanitizeFileName(
            call.getString("fileName"),
            call.getString("mimeType")
        )
        val mimeType = TavernDownloadFiles.normalizeMimeType(call.getString("mimeType"))
        if (call.getString("content") == null) {
            call.reject("Missing file content")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        startActivityForResult(call, intent, "saveTextFileResult")
    }

    @ActivityCallback
    private fun saveTextFileResult(call: PluginCall?, result: androidx.activity.result.ActivityResult) {
        if (call == null) {
            android.util.Log.e(TAG, "saveTextFile: call is null (process was killed)")
            return
        }
        val destination = result.data?.data
        if (result.resultCode != android.app.Activity.RESULT_OK || destination == null) {
            call.reject("cancelled")
            return
        }
        val content = call.getString("content") ?: run {
            call.reject("Missing file content")
            return
        }
        Thread {
            val tempFile = File(
                getContext().cacheDir,
                "sillyclient-export-${System.currentTimeMillis()}.tmp"
            )
            try {
                FileOutputStream(tempFile).writer(Charsets.UTF_8).use { it.write(content) }
                val output = getContext().contentResolver.openOutputStream(destination, "w")
                    ?: throw IOException("Document provider returned no output stream")
                tempFile.inputStream().use { input ->
                    output.use { sink -> input.copyTo(sink) }
                }
                runCatching { tempFile.delete() }
                call.resolve()
            } catch (error: Exception) {
                runCatching { tempFile.delete() }
                android.util.Log.e(TAG, "saveTextFile error", error)
                call.reject("saveTextFile: ${error.message}")
            }
        }.start()
    }

    /**
     * 系统文件选择器读取小型文本/JSON 导入（如实例备份 instances.json）。
     * Android 通过 SAF (ACTION_OPEN_DOCUMENT) 选择文件并读取 UTF-8 内容返回。
     */
    @PluginMethod
    fun readTextFile(call: PluginCall) {
        val mimeType = call.getString("mimeType", "application/json") ?: "application/json"
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(mimeType, "application/json", "text/plain", "*/*")
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(call, intent, "readTextFileResult")
    }

    @ActivityCallback
    private fun readTextFileResult(call: PluginCall?, result: androidx.activity.result.ActivityResult) {
        if (call == null) {
            android.util.Log.e(TAG, "readTextFile: call is null (process was killed)")
            return
        }
        val uri = result.data?.data
        if (result.resultCode != android.app.Activity.RESULT_OK || uri == null) {
            call.reject("cancelled")
            return
        }
        Thread {
            try {
                val input = getContext().contentResolver.openInputStream(uri)
                    ?: throw IOException("Document provider returned no input stream")
                val content = input.bufferedReader(Charsets.UTF_8).use { it.readText() }

                var fileName = "imported.json"
                try {
                    getContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                fileName = cursor.getString(nameIndex) ?: fileName
                            }
                        }
                    }
                } catch (_: Exception) {}

                val ret = JSObject()
                ret.put("content", content)
                ret.put("fileName", fileName)
                call.resolve(ret)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "readTextFile error", e)
                call.reject("readTextFile: ${e.message}")
            }
        }.start()
    }

    @ActivityCallback
    private fun pickDir(call: PluginCall?, @Suppress("UNUSED_PARAMETER") result: androidx.activity.result.ActivityResult) {
        if (call == null) return
        if (result.resultCode != android.app.Activity.RESULT_OK || result.data?.data == null) {
            call.reject("cancelled")
            return
        }
        val treeUri = result.data?.data ?: run { call.reject("No dir data"); return }
        try {
            getContext().contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { /* 忽略持久化失败 */ }

        val physicalPath = resolveTreeUriToPath(treeUri)
        val pathStr = physicalPath ?: treeUri.path ?: ""
        val name = if (physicalPath != null) File(physicalPath).name else pathStr.substringAfterLast(':').ifEmpty { "selected" }

        val ret = JSObject()
        ret.put("name", name)
        ret.put("path", physicalPath ?: treeUri.toString())
        ret.put("installPathMode", if (call.getString("purpose") == "installation") "root" else "exact")
        call.resolve(ret)
    }

    private fun resolveTreeUriToPath(treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(":")
            val type = parts[0]
            val subPath = if (parts.size > 1) parts[1].trimStart('/') else ""
            if ("primary".equals(type, ignoreCase = true)) {
                val base = android.os.Environment.getExternalStorageDirectory().absolutePath
                if (subPath.isEmpty()) base else "$base/$subPath"
            } else {
                val extDirs = getContext().getExternalFilesDirs(null)
                var sdCardRoot: String? = null
                for (f in extDirs) {
                    if (f != null) {
                        val abs = f.absolutePath
                        val idx = abs.indexOf(type)
                        if (idx != -1) {
                            sdCardRoot = abs.substring(0, idx + type.length)
                            break
                        }
                    }
                }
                if (sdCardRoot != null) {
                    if (subPath.isEmpty()) sdCardRoot else "$sdCardRoot/$subPath"
                } else {
                    "/storage/$type/$subPath"
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 系统图片选择器,复制到 covers/{instanceId}。 */
    @PluginMethod
    fun pickImage(call: PluginCall) {
        val instanceId = call.data.optString("instanceId", "default")
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(call, intent, "pickImage")
    }

    @ActivityCallback
    private fun pickImage(call: PluginCall?, @Suppress("UNUSED_PARAMETER") result: androidx.activity.result.ActivityResult) {
        if (call == null) {
            android.util.Log.e(TAG, "pickImage: call is null (process was killed)")
            return
        }
        val instanceId = call.getString("instanceId", "default") ?: "default"
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            call.reject("cancelled")
            return
        }
        val act = activity as? MainActivity
        if (act == null) { call.reject("Not MainActivity"); return }
        try {
            val uri = data.data ?: run { call.reject("No image data"); return }
            val outPath = act.copyCoverImage(uri, instanceId)
            val ret = JSObject()
            ret.put("path", outPath)
            call.resolve(ret)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "pickImage error", e)
            call.reject("pickImage: ${e.message}")
        }
    }

    /** 系统文件选择器,选择 SillyTavern zip 文件,复制到 tmp 目录并返回路径。 */
    @PluginMethod
    fun pickZipFile(call: PluginCall) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/x-zip",
                    "application/octet-stream",
                    "*/*"
                )
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(call, intent, "pickZipFile")
    }

    @ActivityCallback
    private fun pickZipFile(call: PluginCall?, @Suppress("UNUSED_PARAMETER") result: androidx.activity.result.ActivityResult) {
        if (call == null) {
            android.util.Log.e(TAG, "pickZipFile: call is null (process was killed)")
            return
        }
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            call.reject("cancelled")
            return
        }
        val act = activity as? MainActivity
        if (act == null) { call.reject("Not MainActivity"); return }
        val uri = data.data ?: run { call.reject("No file data"); return }

        Thread {
            try {
                val tmpDir = File(act.cacheDir, "sillyclient-tmp").apply { mkdirs() }
                val destFile = File(tmpDir, "sillytavern-import-${System.currentTimeMillis()}.zip")
                val input = act.contentResolver.openInputStream(uri)
                    ?: throw IOException("Document provider returned no input stream")
                input.use { inStream ->
                    FileOutputStream(destFile).use { outStream -> inStream.copyTo(outStream) }
                }
                val ret = JSObject()
                ret.put("path", destFile.absolutePath)
                ret.put("sizeBytes", destFile.length())
                call.resolve(ret)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "pickZipFile error", e)
                call.reject("pickZipFile: ${e.message}")
            }
        }.start()
    }

    @PluginMethod
    fun scanInstances(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        Thread {
            try {
                val arr = JSArray()
                for (s in act.scanInstances()) {
                    arr.put(JSObject()
                        .put("instanceId", s.instanceId)
                        .put("version", s.version)
                        .put("path", s.path)
                        .put("sizeBytes", s.sizeBytes)
                        .put("hasServer", s.hasServer))
                }
                call.resolve(JSObject().put("instances", arr))
            } catch (error: Exception) {
                Log.w(TAG, "reject: ${error.message}")
                call.reject(error.message ?: "Instance scan failed", error)
            }
        }.start()
    }

    @PluginMethod
    fun getInstanceInfo(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.data.optString("instanceId", "default")
        val port = call.data.optInt("port", 8000)
        val installPath = call.getString("installPath")
        Thread {
            try {
                val info = act.getInstanceInfo(instanceId, port, installPath)
                val ret = JSObject()
                ret.put("instanceId", instanceId)
                ret.put("version", info.version)
                ret.put("path", info.path)
                ret.put("sizeBytes", info.sizeBytes)
                ret.put("createdAt", info.createdAt)
                ret.put("port", port)
                ret.put("status", info.status)
                call.resolve(ret)
            } catch (error: Exception) {
                Log.w(TAG, "reject: ${error.message}")
                call.reject(error.message ?: "Instance details failed", error)
            }
        }.start()
    }

    @PluginMethod
    fun sendCommand(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val text = call.data.optString("text", "")
        val instanceId = call.data.optString("instanceId", "").trim()
        if (instanceId.isBlank()) {
            call.reject("Missing instanceId")
            return
        }
        act.sendCommand(text, instanceId)
        call.resolve()
    }

    @PluginMethod
    fun reloadTavern(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        act.runOnUiThread { act.reloadTavern() }
        call.resolve()
    }

    @PluginMethod
    fun clearWebViewData(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        act.runOnUiThread { act.clearWebViewData() }
        call.resolve()
    }

    @PluginMethod
    fun getSafeInsets(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val (top, bottom, left, right) = act.getSafeInsets()
        val ret = JSObject()
        ret.put("top", top)
        ret.put("bottom", bottom)
        ret.put("left", left)
        ret.put("right", right)
        call.resolve(ret)
    }

    @PluginMethod
    fun setPullToRefresh(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val enabled = call.data.optBoolean("enabled", true)
        act.setPullToRefresh(enabled)
        call.resolve()
    }

    @PluginMethod
    fun setRemoteBasicAuth(call: PluginCall) {
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        val username = call.getString("username") ?: ""
        val password = if (call.data.has("password")) call.data.optString("password", "") else null
        try {
            val credentials = RemoteBasicAuthStore(context).save(instanceId, username, password)
            val ret = JSObject()
            ret.put("configured", true)
            ret.put("username", credentials.username)
            call.resolve(ret)
        } catch (error: Exception) {
            Log.w(TAG, "reject: ${error.message}")
            call.reject(error.message ?: "无法保存远程连接凭据", error)
        }
    }

    @PluginMethod
    fun getRemoteBasicAuthStatus(call: PluginCall) {
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        try {
            val credentials = RemoteBasicAuthStore(context).load(instanceId)
            val ret = JSObject()
            ret.put("configured", credentials != null)
            credentials?.let { ret.put("username", it.username) }
            call.resolve(ret)
        } catch (error: Exception) {
            Log.w(TAG, "reject: ${error.message}")
            call.reject(error.message ?: "无法读取远程连接凭据", error)
        }
    }

    @PluginMethod
    fun clearRemoteBasicAuth(call: PluginCall) {
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        RemoteBasicAuthStore(context).remove(instanceId)
        val ret = JSObject()
        ret.put("success", true)
        call.resolve(ret)
    }

    /** 探测远程实例是否在线(HEAD 请求,5s 超时)。绕过 WebView 的 CORS/mixed-content 限制。 */
    @PluginMethod
    fun pingUrl(call: PluginCall) {
        val urlStr = call.getString("url") ?: run { call.reject("url required"); return }
        val instanceId = call.data.optString("instanceId", "").ifBlank { null }
        Thread {
            try {
                val hasTransientCredentials = call.data.has("username") && call.data.has("password")
                val credentials = if (hasTransientCredentials) {
                    RemoteBasicAuthStore.Credentials(
                        call.data.optString("username", ""),
                        call.data.optString("password", "")
                    )
                } else {
                    instanceId?.let { RemoteBasicAuthStore(context).load(it) }
                }
                val initialUrl = URL(urlStr)
                require(initialUrl.protocol == "http" || initialUrl.protocol == "https") {
                    "连接地址必须使用 HTTP 或 HTTPS"
                }
                val authOrigin = urlOrigin(initialUrl)
                var currentUrl = initialUrl
                var code = 0

                for (redirectCount in 0..5) {
                    val conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 5000
                        readTimeout = 5000
                        instanceFollowRedirects = false
                        if (credentials != null && urlOrigin(currentUrl) == authOrigin) {
                            val token = Base64.encodeToString(
                                "${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8),
                                Base64.NO_WRAP
                            )
                            setRequestProperty("Authorization", "Basic $token")
                        }
                    }
                    code = conn.responseCode
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (code in 300..399 && !location.isNullOrBlank() && redirectCount < 5) {
                        currentUrl = URL(currentUrl, location)
                    } else {
                        break
                    }
                }

                val ret = JSObject()
                ret.put("statusCode", code)
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    ret.put("online", false)
                    ret.put("authRequired", true)
                    ret.put(
                        "error",
                        if (credentials == null) "该地址需要 Basic Auth 账号和密码"
                        else "Basic Auth 验证失败，请检查账号和密码"
                    )
                } else {
                    ret.put("online", code in 200..499)
                }
                call.resolve(ret)
            } catch (e: Exception) {
                val ret = JSObject()
                ret.put("online", false)
                ret.put("error", e.message ?: "unknown")
                call.resolve(ret)
            }
        }.start()
    }

    private fun urlOrigin(url: URL): String {
        val port = if (url.port >= 0) url.port else url.defaultPort
        return "${url.protocol.lowercase()}://${url.host.lowercase()}:$port"
    }

    @PluginMethod
    fun checkLegacyInstances(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        Thread {
            try {
                val instances = JSArray()
                act.checkLegacyInstances().forEach { item ->
                    instances.put(JSObject().put("instanceId", item.instanceId).put("name", item.name)
                        .put("currentPath", item.currentPath).put("targetPath", item.targetPath).put("version", item.version))
                }
                call.resolve(JSObject().put("instances", instances))
            } catch (error: Exception) {
                Log.w(TAG, "reject: ${error.message}")
                call.reject(error.message ?: "Could not check legacy instances", error)
            }
        }.start()
    }

    @PluginMethod
    fun relocateInstance(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val id = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        val target = call.getString("targetPath")
        val installPath = call.getString("installPath")
        val operationId = call.getString("operationId")?.ifBlank { null }
        act.runOnUiThread {
            val paths = act.runtimePaths
            val destination = target?.takeIf { it.isNotBlank() }?.let(::File) ?: paths.serversDir
            if (InstanceStorageAccess.requiresPublicAccess(paths, destination) && !requestStorageAccess(call)) return@runOnUiThread
            Thread {
                try { call.resolve(relocationResult(act.relocateInstance(id, target, installPath, operationId))) }
                catch (error: Exception) {
                    Log.w(TAG, "relocate reject: ${error.javaClass.simpleName}: ${error.message}")
                    call.reject(error.message ?: "Instance relocation failed", error)
                }
            }.start()
        }
    }

    @PluginMethod
    fun renameInstance(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val id = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        val name = call.getString("newName") ?: run { call.reject("newName required"); return }
        val path = call.getString("installPath")
        val operationId = call.getString("operationId")?.ifBlank { null }
        Thread {
            try {
                val result = act.renameInstance(id, name, path, operationId)
                call.resolve(JSObject().put("success", result.success).put("oldId", result.oldId)
                    .put("newId", result.newId).put("oldPath", result.oldPath).put("newPath", result.newPath))
            } catch (error: Exception) {
                Log.w(TAG, "rename reject: ${error.javaClass.simpleName}: ${error.message}")
                call.reject(error.message ?: "Instance rename failed", error)
            }
        }.start()
    }

    @PluginMethod
    fun migrateLegacyInstances(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        act.runOnUiThread {
            if (requestStorageAccess(call)) migrateLegacyInstancesAuthorized(call, act)
        }
    }

    private fun migrateLegacyInstancesAuthorized(call: PluginCall, act: MainActivity) {
        val selected = try {
            if (!call.data.has("instanceIds")) null else call.data.getJSONArray("instanceIds").let { array ->
                require(array.length() <= 512) { "Too many legacy instances selected" }
                (0 until array.length()).map { array.getString(it) }.toSet()
            }
        } catch (error: Exception) { call.reject("Invalid legacy instance selection", error); return }
        Thread {
            try {
                val candidates = act.checkLegacyInstances()
                require(selected == null || candidates.map { it.instanceId }.toSet().containsAll(selected)) {
                    "Legacy instance list changed; refresh it before migrating"
                }
                val results = JSArray()
                var succeeded = true
                for (item in candidates.filter { selected == null || it.instanceId in selected }) {
                    try {
                        results.put(relocationResult(act.relocateInstance(item.instanceId)))
                    } catch (error: Exception) {
                        succeeded = false
                        results.put(JSObject().put("success", false).put("instanceId", item.instanceId)
                            .put("oldPath", item.currentPath).put("newPath", item.currentPath)
                            .put("error", error.message ?: "Instance relocation failed"))
                    }
                }
                call.resolve(JSObject().put("success", succeeded).put("results", results))
            } catch (error: Exception) {
                Log.w(TAG, "migrate reject: ${error.javaClass.simpleName}: ${error.message}")
                call.reject(error.message ?: "Legacy migration failed", error)
            }
        }.start()
    }

    private fun relocationResult(result: com.sillyclient.runtime.InstanceRelocation.Result): JSObject =
        JSObject().put("success", result.success).put("instanceId", result.instanceId)
            .put("oldPath", result.oldPath).put("newPath", result.newPath).put("unchanged", result.unchanged).also {
                result.retainedSourcePath?.let { path -> it.put("retainedSourcePath", path) }
            }

    /** 导出实例:打包为 ZIP 保存到 Download/SillyClient-导出（用户可在文件管理器中查看）。 */
    @PluginMethod
    fun exportInstance(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        act.runOnUiThread {
            if (!InstanceStorageAccess.isGranted(context) && !requestStorageAccess(call)) return@runOnUiThread
            Thread {
                try {
                    com.sillyclient.runtime.KeepAlive.acquire(act)
                    try {
                        val (path, bytes) = act.exportInstance(instanceId, call.getString("installPath"))
                        call.resolve(JSObject().put("path", path).put("bytes", bytes))
                    } finally {
                        act.runOnUiThread { com.sillyclient.runtime.KeepAlive.release(act.applicationContext) }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "export reject: ${error.javaClass.simpleName}: ${error.message}")
                    call.reject(error.message ?: "导出失败，请重试", error)
                }
            }.start()
        }
    }

    /** 只读预检：压缩包里有多少用户数据会被导入、多少依赖/程序文件会被忽略。 */
    @PluginMethod
    fun inspectImportArchive(call: PluginCall) {
        val archivePath = call.getString("archivePath") ?: run { call.reject("archivePath required"); return }
        Thread {
            try {
                val archive = java.io.File(archivePath)
                val summary = com.sillyclient.runtime.InstanceDataImport.inspect(archive)
                call.resolve(JSObject()
                    .put("importEntries", summary.importEntries)
                    .put("importBytes", summary.importBytes)
                    .put("skippedEntries", summary.skippedEntries)
                    .put("skippedBytes", summary.skippedBytes)
                    .put("hasSecrets", summary.hasSecrets)
                    .put("hasConfig", summary.hasConfig)
                    .put("importable", summary.importable))
            } catch (error: Exception) {
                Log.w(TAG, "inspect reject: ${error.javaClass.simpleName}: ${error.message}")
                call.reject(error.message ?: "无法读取该压缩包", error)
            }
        }.start()
    }

    /**
     * 把压缩包里的用户数据无损导入到已有实例：只覆盖用户数据，依赖与程序文件永不写入。
     * 实例必须处于停止状态（原生维护入口会拒绝运行中的实例）。
     */
    @PluginMethod
    fun importInstanceData(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        val archivePath = call.getString("archivePath") ?: run { call.reject("archivePath required"); return }
        val includeOptional = call.getBoolean("includeOptional", false) ?: false
        act.runOnUiThread {
            val paths = act.runtimePaths
            val destination = try {
                paths.serverDirFor(instanceId, call.getString("installPath"), create = false)
            } catch (error: Exception) {
                call.reject(error.message ?: "实例目录无效", error)
                return@runOnUiThread
            }
            if (InstanceStorageAccess.requiresPublicAccess(paths, destination) && !requestStorageAccess(call)) {
                return@runOnUiThread
            }
            Thread {
                try {
                    com.sillyclient.runtime.KeepAlive.acquire(act)
                    try {
                        val (imported, bytes, skipped) = act.importInstanceData(
                            instanceId, call.getString("installPath"), archivePath,
                            includeOptional, call.getString("operationId"))
                        call.resolve(JSObject().put("imported", imported).put("bytes", bytes).put("skipped", skipped))
                    } finally {
                        act.runOnUiThread { com.sillyclient.runtime.KeepAlive.release(act.applicationContext) }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "import reject: ${error.javaClass.simpleName}: ${error.message}")
                    call.reject(error.message ?: "数据导入失败，请重试", error)
                }
            }.start()
        }
    }

    /** 卸载实例:删除安装目录 + 封面图。 */
    @PluginMethod
    fun uninstallInstance(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        val operationId = call.getString("operationId")?.ifBlank { null }
        act.runOnUiThread {
            // Deleting instance files touches public storage: without the access
            // grant the physical delete would fail after the registry was
            // already updated, leaving orphaned directories behind.
            val paths = act.runtimePaths
            val destination = paths.installLocations.entries()[instanceId] ?: paths.serversDir
            if (InstanceStorageAccess.requiresPublicAccess(paths, destination) && !requestStorageAccess(call)) {
                return@runOnUiThread
            }
            Thread {
                try {
                    val freedBytes = act.uninstallInstance(instanceId, call.getString("installPath"), operationId)
                    val ret = JSObject()
                    ret.put("success", true)
                    ret.put("freedBytes", freedBytes)
                    call.resolve(ret)
                } catch (e: Exception) {
                    Log.w(TAG, "uninstall reject: ${e.javaClass.simpleName}: ${e.message}")
                    call.reject(e.message ?: "删除失败，请重试", e)
                }
            }.start()
        }
    }

    /** 清理垃圾:扫描孤立文件/目录。dryRun=true 仅扫描不删除。 */
    @PluginMethod
    fun cleanGarbage(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val dryRun = call.getBoolean("dryRun", true) ?: true
        fun stringList(key: String): List<String>? = call.data.optJSONArray(key)?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        val activeInstanceIds = stringList("activeInstanceIds")
        val activeCoverPaths = stringList("activeCoverPaths")
        Thread {
            try {
                val items = act.cleanGarbage(dryRun, activeInstanceIds, activeCoverPaths)
                var totalBytes = 0L
                val arr = JSArray()
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    totalBytes += o.optLong("sizeBytes", 0)
                    arr.put(o)
                }
                val ret = JSObject()
                ret.put("items", arr)
                ret.put("totalBytes", totalBytes)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("cleanGarbage failed: ${e.message}")
            }
        }.start()
    }

    @PluginMethod
    fun scanInstanceMaintenance(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        val installPath = call.getString("installPath")
        Thread {
            try {
                if (!installPath.isNullOrBlank()) {
                    runCatching {
                        val paths = RuntimePaths.from(act)
                        paths.launchDirectoryFor(instanceId, installPath)
                    }
                }
                val scan = act.scanInstanceMaintenance(instanceId, installPath)
                val items = JSArray()
                scan.items.forEach { item ->
                    items.put(JSObject()
                        .put("id", item.id).put("token", item.token)
                        .put("kind", item.kind).put("relativePath", item.relativePath)
                        .put("sizeBytes", item.sizeBytes).put("description", item.description)
                        .put("confidence", item.confidence).put("defaultSelected", item.defaultSelected)
                        .put("action", item.action))
                }
                call.resolve(JSObject()
                    .put("instanceId", instanceId).put("scanId", scan.scanId).put("expiresAt", scan.expiresAt)
                    .put("items", items).put("warnings", JSArray(scan.warnings)))
            } catch (error: Exception) {
                Log.w(TAG, "reject: ${error.message}")
                call.reject(error.message ?: "Instance maintenance scan failed", error)
            }
        }.start()
    }

    @PluginMethod
    fun applyInstanceMaintenance(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        val scanId = call.getString("scanId") ?: run { call.reject("scanId required"); return }
        val installPath = call.getString("installPath")
        val selection = try {
            val items = call.data.getJSONArray("items")
            require(items.length() <= 512) { "Too many maintenance selections" }
            (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                InstanceMaintenance.Selection(item.getString("id"), item.getString("token"))
            }
        } catch (error: Exception) {
            Log.w(TAG, "reject: ${error.message}")
            call.reject(error.message ?: "Invalid maintenance selection", error)
            return
        }
        Thread {
            try {
                if (!installPath.isNullOrBlank()) {
                    runCatching {
                        val paths = RuntimePaths.from(act)
                        paths.launchDirectoryFor(instanceId, installPath)
                    }
                }
                val applied = act.applyInstanceMaintenance(instanceId, scanId, selection, installPath)
                val results = JSArray()
                applied.results.forEach { result ->
                    val item = JSObject().put("id", result.id).put("success", result.success)
                        .put("action", result.action).put("freedBytes", result.freedBytes)
                        .put("quarantinedBytes", result.quarantinedBytes)
                    result.recoveryId?.let { item.put("recoveryId", it) }
                    result.error?.let { item.put("error", it) }
                    results.put(item)
                }
                call.resolve(JSObject().put("success", applied.success).put("results", results)
                    .put("freedBytes", applied.freedBytes).put("quarantinedBytes", applied.quarantinedBytes)
                    .put("recoveryIds", JSArray(applied.recoveryIds)))
            } catch (error: Exception) {
                Log.w(TAG, "reject: ${error.message}")
                call.reject(error.message ?: "Instance maintenance failed", error)
            }
        }.start()
    }

    @PluginMethod
    fun listInstanceMaintenanceRecovery(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        val installPath = call.getString("installPath")
        Thread {
            try {
                if (!installPath.isNullOrBlank()) {
                    runCatching {
                        val paths = RuntimePaths.from(act)
                        paths.launchDirectoryFor(instanceId, installPath)
                    }
                }
                val recovery = act.listInstanceMaintenanceRecovery(instanceId, installPath)
                val items = JSArray()
                recovery.items.forEach { item ->
                    val value = JSObject().put("recoveryId", item.recoveryId).put("token", item.token)
                        .put("createdAt", item.createdAt).put("description", item.description)
                        .put("relativePath", item.relativePath).put("kind", item.kind).put("action", item.action)
                        .put("sizeBytes", item.sizeBytes).put("canRestore", item.canRestore)
                    item.conflict?.let { value.put("conflict", it) }
                    items.put(value)
                }
                call.resolve(JSObject().put("items", items).put("warnings", JSArray(recovery.warnings)))
            } catch (error: Exception) {
                Log.w(TAG, "reject: ${error.message}")
                call.reject(error.message ?: "Instance recovery scan failed", error)
            }
        }.start()
    }

    @PluginMethod
    fun restoreInstanceMaintenance(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        val recoveryId = call.getString("recoveryId") ?: run { call.reject("recoveryId required"); return }
        val token = call.getString("token") ?: run { call.reject("token required"); return }
        val installPath = call.getString("installPath")
        Thread {
            try {
                if (!installPath.isNullOrBlank()) {
                    runCatching {
                        val paths = RuntimePaths.from(act)
                        paths.launchDirectoryFor(instanceId, installPath)
                    }
                }
                val restored = act.restoreInstanceMaintenance(instanceId, recoveryId, token, installPath)
                val value = JSObject().put("success", restored.success)
                restored.recoveryId?.let { value.put("recoveryId", it) }
                restored.relativePath?.let { value.put("relativePath", it) }
                restored.error?.let { value.put("error", it) }
                call.resolve(value)
            } catch (error: Exception) {
                Log.w(TAG, "reject: ${error.message}")
                call.reject(error.message ?: "Instance maintenance restore failed", error)
            }
        }.start()
    }

    /** 删除指定垃圾项(按 path)。 */
    @PluginMethod
    fun deleteGarbageItem(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val path = call.getString("path") ?: run { call.reject("path required"); return }
        val token = call.getString("token")
        Thread {
            try {
                val freedBytes = act.deleteGarbageItem(path, token)
                val ret = JSObject()
                ret.put("success", true)
                ret.put("freedBytes", freedBytes)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("delete failed: ${e.message}")
            }
        }.start()
    }

    /** 数据迁移已于 1.12.0 正式下线，统一使用“创建实例 + 导入数据（ZIP）”模式。 */
    @PluginMethod
    fun migrateInstance(call: PluginCall) {
        call.reject("旧版数据迁移已下线；请直接创建新实例，并在「实例管理 → 存储路径」中使用「导入数据（ZIP）」无损恢复用户数据。")
    }

    @PluginMethod
    fun setInstancePassword(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        val password = call.getString("password")
        val oldPassword = call.getString("oldPassword")
        try {
            val res = act.runtimePaths.instanceLock.setPassword(instanceId, password, oldPassword)
            val ret = JSObject().apply {
                put("success", res.success)
                put("hasPassword", res.hasPassword)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject(e.message ?: "Failed to set instance password")
        }
    }

    @PluginMethod
    fun verifyInstancePassword(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        val password = call.getString("password") ?: ""
        try {
            val valid = act.runtimePaths.instanceLock.verifyPassword(instanceId, password)
            val ret = JSObject().apply {
                put("valid", valid)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject(e.message ?: "Failed to verify instance password")
        }
    }

    @PluginMethod
    fun hasInstancePassword(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        try {
            val has = act.runtimePaths.instanceLock.hasPassword(instanceId)
            val ret = JSObject().apply {
                put("hasPassword", has)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject(e.message ?: "Failed to check instance password")
        }
    }

    @PluginMethod
    fun clearInstancePassword(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        val oldPassword = call.getString("oldPassword")
        try {
            val success = act.runtimePaths.instanceLock.clearPassword(instanceId, oldPassword)
            val ret = JSObject().apply {
                put("success", success)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject(e.message ?: "Failed to clear instance password")
        }
    }

    @PluginMethod
    fun listInstancePasswordStatus(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        try {
            val statusMap = act.runtimePaths.instanceLock.listStatus()
            val ret = JSObject()
            for ((k, v) in statusMap) {
                ret.put(k, v)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject(e.message ?: "Failed to list instance password status")
        }
    }
}
