package com.sillyclient.plugin

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.ActivityCallback
import com.sillyclient.MainActivity
import org.json.JSONObject

/**
 * Capacitor plugin wrapping the SillyClient tavern startup & reading environment.
 *
 * All heavy lifting lives in MainActivity; this plugin is a thin bridge.
 * Events (progress / log / ready) are pushed from MainActivity via [notify].
 */
@CapacitorPlugin(name = "TarvenEnv")
class TarvenEnvPlugin : Plugin() {

    companion object {
        private var instance: TarvenEnvPlugin? = null

        /** Push event to Capacitor JS listeners. Safe from any thread. */
        fun notify(event: String, data: JSObject) {
            val p = instance ?: return
            p.activity?.runOnUiThread {
                p.notifyListeners(event, data)
            }
        }
    }

    /** 挂起的目录选择回调。 */
    private var pendingDirCall: PluginCall? = null
    /** 挂起的图片选择回调 + 目标 instanceId。 */
    private var pendingImageCall: PluginCall? = null
    private var pendingImageInstanceId: String? = null

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
        val config = MainActivity.InstanceConfig(
            listen = call.data.optBoolean("listen", false),
            ipv4 = call.data.optBoolean("ipv4", true),
            ipv6 = call.data.optBoolean("ipv6", false),
            dnsIpv6 = call.data.optBoolean("dnsIpv6", false),
            heartbeat = call.data.optInt("heartbeat", 0),
            keepAlive = call.data.optBoolean("keepAlive", false)
        )
        val configObj = call.data.optJSONObject("config")
        val real = if (configObj != null) MainActivity.InstanceConfig(
            listen = configObj.optBoolean("listen", config.listen),
            ipv4 = configObj.optBoolean("ipv4", config.ipv4),
            ipv6 = configObj.optBoolean("ipv6", config.ipv6),
            dnsIpv6 = configObj.optBoolean("dnsIpv6", config.dnsIpv6),
            heartbeat = configObj.optInt("heartbeat", config.heartbeat),
            keepAlive = configObj.optBoolean("keepAlive", config.keepAlive)
        ) else config
        val urlArg = if (zipballUrl.isEmpty()) null else zipballUrl
        act.runOnUiThread { act.provisionAndStart(port, instanceId, version, real, urlArg) }
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
                val url = java.net.URL("https://api.github.com/repos/SillyTavern/SillyTavern/releases?per_page=30")
                val conn = url.openConnection() as java.net.HttpURLConnection
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
        pendingDirCall = call
        startActivityForResult(call, intent, "pickDir")
    }

    @ActivityCallback
    private fun pickDir(resultCode: Int, result: Intent?) {
        val call = pendingDirCall ?: return
        pendingDirCall = null
        if (resultCode != android.app.Activity.RESULT_OK || result?.data == null) {
            call.reject("cancelled")
            return
        }
        val treeUri = result.data!!
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
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pendingImageCall = call
        pendingImageInstanceId = instanceId
        startActivityForResult(call, intent, "pickImage")
    }

    @ActivityCallback
    private fun pickImage(resultCode: Int, result: Intent?) {
        val call = pendingImageCall ?: return
        val instanceId = pendingImageInstanceId ?: "default"
        pendingImageCall = null
        pendingImageInstanceId = null
        if (resultCode != android.app.Activity.RESULT_OK || result?.data == null) {
            call.reject("cancelled")
            return
        }
        val act = activity as? MainActivity
        if (act == null) { call.reject("Not MainActivity"); return }
        try {
            val outPath = act.copyCoverImage(result.data!!, instanceId)
            val ret = JSObject()
            ret.put("path", outPath)
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("pickImage: ${e.message}")
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
}
