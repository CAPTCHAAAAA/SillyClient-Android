package com.sillyclient.runtime

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * App-level settings that belong to no single instance, persisted as a small
 * JSON document under [RuntimePaths.tarvenHome]. Corrupt or missing files fall
 * back to defaults; writes publish atomically so a crash never leaves a
 * half-written document behind.
 */
class AppSettingsStore(val file: File) {

    data class Snapshot(val instancesRoot: String? = null)

    fun load(): Snapshot {
        if (!file.isFile) return Snapshot()
        val bytes = try {
            Files.readAllBytes(file.toPath()).also {
                check(it.size <= MAX_BYTES) { "App settings document is too large" }
            }
        } catch (_: Exception) {
            return Snapshot()
        }
        return try {
            val document = JSONObject(String(bytes, Charsets.UTF_8))
            val root = document.optString(KEY_INSTANCES_ROOT, "").trim()
                .takeIf { it.isNotEmpty() && it.length <= MAX_PATH_CHARS }
            Snapshot(instancesRoot = root)
        } catch (_: Exception) {
            Snapshot()
        }
    }

    fun save(snapshot: Snapshot) {
        val document = JSONObject()
        snapshot.instancesRoot?.let { document.put(KEY_INSTANCES_ROOT, it) }
        val parent = requireNotNull(file.parentFile) { "App settings file needs a parent directory" }
        parent.mkdirs()
        val staging = Files.createTempFile(parent.toPath(), PREFIX, null)
        try {
            Files.write(staging, document.toString().toByteArray(Charsets.UTF_8))
            Files.move(staging, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    companion object {
        internal const val KEY_INSTANCES_ROOT = "instancesRoot"
        internal const val MAX_BYTES = 64 * 1024
        internal const val MAX_PATH_CHARS = 500
        private const val PREFIX = ".sillyclient-settings-"

        fun settingsFile(tarvenHome: File): File = File(tarvenHome, "app-settings.json")
    }
}
