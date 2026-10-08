package com.sillyclient.runtime

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** Read only the bundled root manifest; SourceArchive still validates the full ZIP at installation. */
class BundledTavernSource(
    private val openAsset: (String) -> InputStream,
    private val diagnostic: (String) -> Unit = {}
) {
    val version: String? by lazy {
        try {
            readVersion()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ensureActive()
            runCatching { diagnostic("bundled.source.unavailable type=${error.javaClass.simpleName}") }
            null
        }
    }

    fun matchesRequestedVersion(requested: String, sourceUrl: String?): Boolean {
        val actual = version ?: return false
        // Keep the old no-URL native default, but never replace an explicitly selected live branch.
        return requested == actual || (requested == "stable" && sourceUrl.isNullOrBlank())
    }

    private fun readVersion(): String {
        ensureActive()
        val bounded = object : FilterInputStream(openAsset(ASSET_PATH)) {
            private var consumed = 0L
            private fun count(value: Int): Int {
                if (value > 0) consumed += value
                require(consumed <= MAX_SCAN_BYTES) { "Bundled ZIP exceeds the metadata scan limit" }
                return value
            }
            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                count(`in`.read(buffer, offset, length))
        }
        ZipInputStream(BufferedInputStream(bounded)).use { zip ->
            val buffer = ByteArray(32 * 1024)
            var expanded = 0L
            var root: String? = null
            var singleWrapper = true
            repeat(MAX_ENTRIES) {
                ensureActive()
                val entry = zip.nextEntry ?: error("Bundled root package manifest is missing")
                val name = entry.name.removeSuffix("/")
                require(name.isNotEmpty() && name.length <= 4096 && name.none { char ->
                    char == '\\' || char == ':' || char.code < 32 || char.code == 127
                } && name.split('/').none { part -> part.isEmpty() || part == "." || part == ".." }) {
                    "Bundled ZIP contains an unsafe path"
                }
                if (root == null) root = name.substringBefore('/')
                if (name != root && !name.startsWith("$root/")) singleWrapper = false
                val isManifest = !entry.isDirectory && (name == "package.json" ||
                    (singleWrapper && root !in NON_ROOT_DIRECTORIES && name == "$root/package.json"))
                val manifest = if (isManifest) ByteArrayOutputStream() else null
                while (true) {
                    ensureActive()
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expanded += count
                    require(expanded <= MAX_SCAN_BYTES) { "Bundled ZIP exceeds the metadata scan limit" }
                    if (manifest != null) {
                        require(manifest.size() + count <= MAX_MANIFEST_BYTES) { "Bundled package manifest is too large" }
                        manifest.write(buffer, 0, count)
                    }
                }
                if (manifest != null) {
                    val document = JSONObject(manifest.toString(Charsets.UTF_8.name()))
                    require(document.opt("name") == "sillytavern") { "Bundled source is not SillyTavern" }
                    val value = document.opt("version") as? String ?: error("Bundled source version is missing")
                    require(value.length <= 80 && RELEASE_VERSION.matches(value)) { "Bundled source has no formal version" }
                    return value
                }
            }
        }
        error("Bundled ZIP exceeds the entry scan limit")
    }

    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Bundled source lookup cancelled")
    }

    companion object {
        const val ASSET_PATH = "bundled/sillytavern-release.zip"
        internal const val MAX_MANIFEST_BYTES = 256 * 1024
        internal const val MAX_SCAN_BYTES = 64L * 1024 * 1024
        internal const val MAX_ENTRIES = 8192
        private val RELEASE_VERSION = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")
        private val NON_ROOT_DIRECTORIES = setOf("src", "public", "data", "node_modules", "plugins", "default-user")
    }
}
