package com.sillyclient.runtime

import org.json.JSONObject
import java.io.File

/**
 * 已退役的共享依赖库（`<根>/node_modules`）的唯一遗物：读取银行标记。
 *
 * 共享库模型会向用户选择的存放文件夹写入 node_modules，污染相册/文档等日常路径，
 * 已被"实例自持"模型取代（每个实例目录内自带完整 node_modules）。本对象只保留
 * 读取能力，供启动自愈后的旧库清退（sweep）识别"哪些 node_modules 是我们留下的"：
 * 没有银行标记的目录绝不动。
 */
object DependencyBank {
    internal const val DIR_NAME = "node_modules"
    internal const val MARKER = ".sillyclient-bank.json"

    data class State(val root: File, val key: String, val createdAt: Long)

    fun markerFile(bankDirectory: File): File = File(bankDirectory, MARKER)

    fun read(bankDirectory: File): State? {
        val marker = markerFile(bankDirectory)
        if (!marker.isFile || marker.length() > 8 * 1024) return null
        return try {
            val document = JSONObject(marker.readText(Charsets.UTF_8))
            val key = document.optString("key", "")
            val complete = document.optBoolean("complete", false)
            if (!complete || !key.matches(Regex("[0-9a-f]{64}"))) return null
            State(bankDirectory, key, document.optLong("createdAt", 0L))
        } catch (_: Exception) {
            null
        }
    }

}
