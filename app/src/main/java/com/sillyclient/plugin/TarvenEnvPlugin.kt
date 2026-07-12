package com.sillyclient.plugin

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.ActivityCallback
import com.sillyclient.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Capacitor plugin wrapping the SillyClient tavern startup & reading environment.
 *
 * All heavy lifting lives in MainActivity; this plugin is a thin bridge.
 * Events (progress / log / ready) are pushed from MainActivity via [notify].
 */
@CapacitorPlugin(name = "TarvenEnv")
class TarvenEnvPlugin : Plugin() {

    companion object {
        private const val TAG = "SillyClient"
        private var instance: TarvenEnvPlugin? = null

        /** Push event to Capacitor JS listeners. Safe from any thread. */
        fun notify(event: String, data: JSObject) {
            val p = instance ?: return
            p.activity?.runOnUiThread {
                p.notifyListeners(event, data)
            }
        }
    }

    override fun load() {
        instance = this
    }

    @PluginMethod
    fun provisionAndStart(call: PluginCall) {
        val act = activity as? MainActivity ?: run {
            call.reject("Not MainActivity")
            return
        }
        val port = call.data.optInt("port", 8000)
        val instanceId = call.data.optString("instanceId", "default")
        val version = call.data.optString("version", "stable")
        val zipballUrl = call.data.optString("zipballUrl", "")
        val localZipPath = call.data.optString("localZipPath", "")
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
        act.runOnUiThread { act.provisionAndStart(port, instanceId, version, config, urlArg, localArg) }
        call.resolve()
    }

    @PluginMethod
    fun enterImmersive(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val url = call.data.optString("url", "")
        val target = if (url.isEmpty()) null else url
        act.runOnUiThread { act.enterTavern(target) }
        call.resolve()
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
        act.runOnUiThread { act.returnToTavern() }
        call.resolve()
    }

    @PluginMethod
    fun closeTavern(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        act.runOnUiThread { act.closeTavern() }
        call.resolve()
    }

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val ret = JSObject()
        ret.put("serverReady", act.isServerReady())
        ret.put("mode", if (act.isTavernVisible()) "tavern" else "launcher")
        ret.put("url", act.getTavernUrl())
        call.resolve(ret)
    }

    /** 拉取 GitHub SillyTavern releases。在子线程执行 HTTP。 */
    @PluginMethod
    fun fetchReleases(call: PluginCall) {
        Thread {
            try {
                val url = URL("https://api.github.com/repos/SillyTavern/SillyTavern/releases?per_page=30")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "SillyClient")
                conn.connect()
                if (conn.responseCode != 200) {
                    call.reject("GitHub API HTTP ${conn.responseCode}")
                    conn.disconnect()
                    return@Thread
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val arr = org.json.JSONArray(body)
                val releases = JSArray()
                val n = Math.min(arr.length(), 30)
                for (i in 0 until n) {
                    val o = arr.getJSONObject(i)
                    val r = JSObject()
                    r.put("tag", o.optString("tag_name", ""))
                    r.put("name", o.optString("name", o.optString("tag_name", "")))
                    r.put("publishedAt", o.optString("published_at", ""))
                    r.put("zipballUrl", o.optString("zipball_url", ""))
                    r.put("prerelease", o.optBoolean("prerelease", false))
                    releases.put(r)
                }
                val ret = JSObject()
                ret.put("releases", releases)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("fetchReleases: ${e.message}")
            }
        }.start()
    }

    /** 系统目录选择器(ACTION_OPEN_DOCUMENT_TREE)。 */
    @PluginMethod
    fun pickDirectory(call: PluginCall) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        startActivityForResult(call, intent, "pickDir")
    }

    @ActivityCallback
    private fun pickDir(call: PluginCall, @Suppress("UNUSED_PARAMETER") result: androidx.activity.result.ActivityResult) {
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
        // 从 tree URI 提取显示名(最后一个 path 段解码)
        val path = treeUri.path ?: ""
        val name = path.substringAfterLast(':').ifEmpty { "selected" }
        val ret = JSObject()
        ret.put("name", name)
        ret.put("path", treeUri.toString())
        call.resolve(ret)
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
    private fun pickImage(call: PluginCall, @Suppress("UNUSED_PARAMETER") result: androidx.activity.result.ActivityResult) {
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
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(call, intent, "pickZipFile")
    }

    @ActivityCallback
    private fun pickZipFile(call: PluginCall, @Suppress("UNUSED_PARAMETER") result: androidx.activity.result.ActivityResult) {
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
        try {
            val uri = data.data ?: run { call.reject("No file data"); return }
            val tmpDir = File(act.cacheDir, "sillyclient-tmp").apply { mkdirs() }
            val destFile = File(tmpDir, "sillytavern-import-${System.currentTimeMillis()}.zip")
            act.contentResolver.openInputStream(uri).use { input ->
                FileOutputStream(destFile).use { out -> input?.copyTo(out) }
            }
            val ret = JSObject()
            ret.put("path", destFile.absolutePath)
            ret.put("sizeBytes", destFile.length())
            call.resolve(ret)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "pickZipFile error", e)
            call.reject("pickZipFile: ${e.message}")
        }
    }

    @PluginMethod
    fun scanInstances(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val list = act.scanInstances()
        val arr = JSArray()
        for (s in list) {
            val o = JSObject()
            o.put("instanceId", s.first)
            o.put("version", s.second)
            o.put("path", s.third)
            o.put("sizeBytes", s.fourth)
            o.put("hasServer", s.fifth)
            arr.put(o)
        }
        val ret = JSObject()
        ret.put("instances", arr)
        call.resolve(ret)
    }

    @PluginMethod
    fun getInstanceInfo(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.data.optString("instanceId", "default")
        val port = call.data.optInt("port", 8000)
        val info = act.getInstanceInfo(instanceId, port)
        val ret = JSObject()
        ret.put("instanceId", instanceId)
        ret.put("version", info.first)
        ret.put("path", info.second)
        ret.put("sizeBytes", info.third)
        ret.put("createdAt", info.fourth)
        ret.put("port", port)
        ret.put("status", info.fifth)
        call.resolve(ret)
    }

    @PluginMethod
    fun sendCommand(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val text = call.data.optString("text", "")
        act.sendCommand(text)
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

    /** 探测远程实例是否在线(HEAD 请求,5s 超时)。绕过 WebView 的 CORS/mixed-content 限制。 */
    @PluginMethod
    fun pingUrl(call: PluginCall) {
        val urlStr = call.getString("url") ?: run { call.reject("url required"); return }
        Thread {
            try {
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 5000
                    readTimeout = 5000
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                conn.disconnect()
                val ret = JSObject()
                ret.put("online", code in 200..499) // 2xx/3xx/4xx 都算可达(服务在跑)
                ret.put("statusCode", code)
                call.resolve(ret)
            } catch (e: Exception) {
                val ret = JSObject()
                ret.put("online", false)
                ret.put("error", e.message ?: "unknown")
                call.resolve(ret)
            }
        }.start()
    }

    /** 卸载实例:删除安装目录 + 封面图。 */
    @PluginMethod
    fun uninstallInstance(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val instanceId = call.getString("instanceId") ?: run { call.reject("instanceId required"); return }
        Thread {
            try {
                val freedBytes = act.uninstallInstance(instanceId)
                val ret = JSObject()
                ret.put("success", true)
                ret.put("freedBytes", freedBytes)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("uninstall failed: ${e.message}")
            }
        }.start()
    }

    /** 清理垃圾:扫描孤立文件/目录。dryRun=true 仅扫描不删除。 */
    @PluginMethod
    fun cleanGarbage(call: PluginCall) {
        val act = activity as? MainActivity ?: run { call.reject("Not MainActivity"); return }
        val dryRun = call.getBoolean("dryRun", true) ?: true
        Thread {
            try {
                val items = act.cleanGarbage(dryRun)
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

    /** 删除指定垃圾项(按 path)。 */
    @PluginMethod
    fun deleteGarbageItem(call: PluginCall) {
        val path = call.getString("path") ?: run { call.reject("path required"); return }
        Thread {
            try {
                val file = java.io.File(path)
                if (!file.exists()) {
                    call.reject("file not found")
                    return@Thread
                }
                file.deleteRecursively()
                val ret = JSObject()
                ret.put("success", true)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("delete failed: ${e.message}")
            }
        }.start()
    }
}
