package com.sillyclient.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class CompanionPresetRequest(
    val bundleId: String,
    val revision: Int
)

class CompanionPresetTransaction internal constructor(
    val applied: Boolean,
    private val rollbackAction: () -> Unit
) {
    private var active = applied

    @Synchronized
    fun commit() {
        active = false
    }

    @Synchronized
    fun rollback() {
        if (!active) return
        rollbackAction()
        active = false
    }

    companion object {
        fun noOp() = CompanionPresetTransaction(false) {}
    }
}

object CompanionPresetInstaller {
    const val BUNDLE_ID = "sc-bordeaux"
    const val REVISION = 1

    private const val THEME_HASH = "AB0207DE9DD970557D428B47DBA8BD0859B3FA5FC6FD0050011ECF30BCA16E70"
    private const val WALLPAPER_HASH = "7B17E76B5F726B33D36B04C79DAE40DF5E47CF35835C79C273AF10DBEA07FD1A"
    private const val THEME_SOURCE = "themes/SC Bordeaux.json"
    private const val WALLPAPER_SOURCE = "wallpaper/sillyclient-bg-8k.jpg"
    private const val THEME_TARGET = "data/default-user/themes/SC Bordeaux.json"
    private const val WALLPAPER_TARGET = "data/default-user/backgrounds/sillyclient-bg-8k.jpg"

    private data class Snapshot(val file: File, val previous: ByteArray?)

    fun install(
        context: Context,
        serverDir: File,
        request: CompanionPresetRequest
    ): CompanionPresetTransaction {
        if (request.bundleId != BUNDLE_ID || request.revision != REVISION) {
            throw IllegalArgumentException("不支持的主题预设版本")
        }

        val assetRoot = "companion-presets/${request.bundleId}"
        val manifest = JSONObject(readAsset(context, "$assetRoot/manifest.json").toString(StandardCharsets.UTF_8))
        val themeManifest = manifest.getJSONObject("theme")
        val wallpaperManifest = manifest.getJSONObject("wallpaper")
        if (
            manifest.optString("schema") != "sillyclient.companion-preset" ||
            manifest.optInt("version") != 1 ||
            manifest.optString("bundleId") != BUNDLE_ID ||
            manifest.optInt("revision") != REVISION ||
            themeManifest.optString("source") != THEME_SOURCE ||
            themeManifest.optString("target") != THEME_TARGET ||
            themeManifest.optString("sha256").uppercase() != THEME_HASH ||
            wallpaperManifest.optString("source") != WALLPAPER_SOURCE ||
            wallpaperManifest.optString("target") != WALLPAPER_TARGET ||
            wallpaperManifest.optString("sha256").uppercase() != WALLPAPER_HASH
        ) {
            throw IllegalStateException("内置主题预设清单校验失败")
        }

        val themeBytes = readAsset(context, "$assetRoot/$THEME_SOURCE")
        val wallpaperBytes = readAsset(context, "$assetRoot/$WALLPAPER_SOURCE")
        if (sha256(themeBytes) != THEME_HASH || sha256(wallpaperBytes) != WALLPAPER_HASH) {
            throw IllegalStateException("内置主题预设资源校验失败")
        }

        val themeSettings = JSONObject(themeBytes.toString(StandardCharsets.UTF_8))
        val settingsManifest = manifest.getJSONObject("settings")
        val themeName = settingsManifest.getString("themeName")
        if (themeSettings.optString("name") != themeName) {
            throw IllegalStateException("内置主题文件格式无效")
        }

        val markerFile = resolveInside(serverDir, ".sillyclient/companion-presets/$BUNDLE_ID.json")
        if (markerFile.isFile) {
            try {
                val marker = JSONObject(markerFile.readText())
                if (
                    marker.optString("bundleId") == BUNDLE_ID &&
                    marker.optInt("revision") == REVISION &&
                    marker.optString("themeSha256") == THEME_HASH &&
                    marker.optString("wallpaperSha256") == WALLPAPER_HASH
                ) {
                    return CompanionPresetTransaction.noOp()
                }
            } catch (_: Exception) {
                // 损坏或旧版标记会被本次完整安装替换。
            }
        }

        val settingsFile = resolveInside(serverDir, "data/default-user/settings.json")
        val defaultSettingsFile = resolveInside(serverDir, "default/content/settings.json")
        val settingsBase = if (settingsFile.isFile) settingsFile else defaultSettingsFile
        if (!settingsBase.isFile) throw IllegalStateException("SillyTavern 默认设置模板不存在")

        val settings = JSONObject(settingsBase.readText())
        val powerUser = settings.optJSONObject("power_user") ?: JSONObject()
        val themeKeys = themeSettings.keys()
        while (themeKeys.hasNext()) {
            val key = themeKeys.next()
            if (key != "name" && key != "__proto__" && key != "constructor" && key != "prototype") {
                powerUser.put(key, themeSettings.get(key))
            }
        }
        powerUser.put("theme", themeName)
        powerUser.put("theme_fallback", themeName)
        settings.put("power_user", powerUser)

        val background = settings.optJSONObject("background") ?: JSONObject()
        val backgroundPreset = settingsManifest.getJSONObject("background")
        val backgroundKeys = backgroundPreset.keys()
        while (backgroundKeys.hasNext()) {
            val key = backgroundKeys.next()
            background.put(key, backgroundPreset.get(key))
        }
        settings.put("background", background)

        val themeTarget = resolveInside(serverDir, THEME_TARGET)
        val wallpaperTarget = resolveInside(serverDir, WALLPAPER_TARGET)
        val snapshots = listOf(themeTarget, wallpaperTarget, settingsFile, markerFile).map {
            Snapshot(it, if (it.isFile) it.readBytes() else null)
        }

        fun rollbackFiles() {
            snapshots.asReversed().forEach { snapshot -> restore(snapshot.file, snapshot.previous) }
        }

        try {
            writeAtomic(themeTarget, themeBytes)
            writeAtomic(wallpaperTarget, wallpaperBytes)
            writeAtomic(settingsFile, "${settings.toString(2)}\n".toByteArray(StandardCharsets.UTF_8))
            val marker = JSONObject()
                .put("schema", "sillyclient.companion-preset-applied")
                .put("version", 1)
                .put("bundleId", BUNDLE_ID)
                .put("revision", REVISION)
                .put("themeSha256", THEME_HASH)
                .put("wallpaperSha256", WALLPAPER_HASH)
                .put("appliedAt", Instant.now().toString())
            writeAtomic(markerFile, "${marker.toString(2)}\n".toByteArray(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            rollbackFiles()
            throw error
        }

        return CompanionPresetTransaction(true, ::rollbackFiles)
    }

    private fun readAsset(context: Context, assetPath: String): ByteArray =
        context.assets.open(assetPath).use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02X".format(it) }

    private fun resolveInside(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "预设资源路径无效" }
        val canonicalRoot = root.canonicalFile
        val resolved = File(canonicalRoot, relativePath).canonicalFile
        val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        require(resolved == canonicalRoot || resolved.path.startsWith(rootPrefix)) { "预设资源路径越界" }
        return resolved
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        temporary.writeBytes(bytes)
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun restore(target: File, previous: ByteArray?) {
        if (previous == null) {
            target.delete()
        } else {
            writeAtomic(target, previous)
        }
    }
}
