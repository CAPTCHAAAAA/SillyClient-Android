package com.sillyclient.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** A failed release lookup must not prevent downloading the public stable branch. */
class TavernReleaseCatalog internal constructor(
    private val cacheFile: File,
    private val connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val nanoTime: () -> Long = System::nanoTime,
    private val bundledSource: BundledTavernSource? = null,
    private val diagnostic: (String) -> Unit = {}
) {
    data class Result(val releases: JSONArray, val warning: String? = null)

    fun load(): JSONArray = loadResult().releases

    fun loadResult(): Result {
        ensureActive()
        try {
            val releases = download()
            saveCache(releases)
            return Result(withBundledRelease(releases))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ensureActive()
            runCatching {
                diagnostic("tavern.releases.failed type=${error.javaClass.simpleName}" +
                    if (error is HttpFailure) " http_status=${error.status}" else "")
            }
        }
        val releases = withBundledRelease(readCache() ?: JSONArray().put(JSONObject()
            .put("tag", "release")
            .put("name", "SillyTavern stable release branch")
            .put("zipballUrl", STABLE_BRANCH_URL)
            .put("prerelease", false)
            .put("isBranch", true)), preferBundled = true)
        val hasVersion = (0 until releases.length()).any { !releases.getJSONObject(it).optBoolean("isBranch") }
        return Result(releases, if (hasVersion) "在线发行版获取失败，当前显示内置版本或缓存列表。"
            else "在线发行版获取失败，暂无可用的正式版本列表。")
    }

    private fun withBundledRelease(releases: JSONArray, preferBundled: Boolean = false): JSONArray {
        val version = bundledSource?.version ?: return releases
        val bundled = JSONObject().put("tag", version).put("name", "SillyTavern $version")
            .put("zipballUrl", "https://github.com/SillyTavern/SillyTavern/archive/refs/tags/$version.zip")
            .put("prerelease", false).put("isBundled", true)
        val result = JSONArray()
        var included = false
        // Cached metadata alone cannot guarantee an archive can be downloaded while offline.
        if (preferBundled) {
            val cached = (0 until releases.length()).map { releases.getJSONObject(it) }
                .firstOrNull { it.getString("tag") == version }
            result.put(cached?.let { JSONObject(it.toString()).put("isBundled", true) } ?: bundled)
            included = true
        }
        for (index in 0 until releases.length()) {
            val release = JSONObject(releases.getJSONObject(index).toString())
            if (release.getString("tag") == version) {
                if (preferBundled) continue
                release.put("isBundled", true)
                included = true
            }
            result.put(release)
        }
        if (!included) result.put(bundled)
        return result
    }

    private fun download(): JSONArray {
        val started = nanoTime()
        val connection = connect(URI(API_URL))
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "SillyClient")
        fun checkDeadline() {
            ensureActive()
            check(TimeUnit.NANOSECONDS.toMillis(nanoTime() - started) < 10_000) { "Release lookup timed out" }
        }
        try {
            checkDeadline()
            val status = connection.responseCode
            if (status != 200) throw HttpFailure(status)
            checkDeadline()
            val total = connection.contentLengthLong
            check(total in -1..MAX_BYTES.toLong()) { "Release response is too large" }
            val encoding = connection.contentEncoding
            check(encoding.isNullOrBlank() || encoding.equals("identity", ignoreCase = true)) { "Unexpected response encoding" }
            val body = connection.inputStream.use { readBounded(it, ::checkDeadline) }
            check(total < 0 || body.size.toLong() == total) { "Release response was incomplete" }
            val releases = validated(JSONArray(String(body, Charsets.UTF_8)), fromApi = true)
            check(releases.length() > 0) { "No supported public releases" }
            return releases
        } finally { connection.disconnect() }
    }

    private fun readCache(): JSONArray? = try {
        ensureActive()
        if (!cacheFile.isFile || Files.isSymbolicLink(cacheFile.toPath()) || cacheFile.length() > MAX_BYTES) null
        else {
            val bytes = cacheFile.inputStream().use { readBounded(it, ::ensureActive) }
            validated(JSONArray(String(bytes, Charsets.UTF_8)), fromApi = false).takeIf { it.length() > 0 }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) { null }

    private fun saveCache(releases: JSONArray) {
        var temporary: File? = null
        try {
            ensureActive()
            if (Files.isSymbolicLink(cacheFile.toPath())) return
            val parent = cacheFile.absoluteFile.parentFile ?: return
            if (!parent.isDirectory && !parent.mkdirs()) return
            val bytes = releases.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) return
            temporary = File.createTempFile(".tavern-releases-", ".tmp", parent)
            temporary.outputStream().use { it.write(bytes) }
            ensureActive()
            Files.move(temporary.toPath(), cacheFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Cache persistence is optional; usable official metadata still reaches the caller.
        } finally { temporary?.delete() }
    }

    private fun readBounded(input: InputStream, checkActive: () -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            checkActive()
            if (count < 0) return body.toByteArray()
            if (count == 0) continue
            if (body.size() + count > MAX_BYTES) throw IOException("Release catalog exceeds the size limit")
            body.write(buffer, 0, count)
        }
    }

    private fun validated(records: JSONArray, fromApi: Boolean): JSONArray {
        val result = JSONArray()
        val seen = mutableSetOf<String>()
        for (index in 0 until minOf(records.length(), 30)) {
            ensureActive()
            val record = records.optJSONObject(index) ?: continue
            val release = runCatching { validateRecord(record, fromApi) }.getOrNull() ?: continue
            if (seen.add(release.getString("tag"))) result.put(release)
        }
        return result
    }

    private fun validateRecord(record: JSONObject, fromApi: Boolean): JSONObject {
        require(!fromApi || record.opt("draft") != true) { "Draft release" }
        val tag = record.opt(if (fromApi) "tag_name" else "tag") as? String ?: error("Missing release tag")
        require(tag.length in 1..160 && tag.none { it.isWhitespace() || it.code < 32 || it.code == 127 })
        val url = record.opt(if (fromApi) "zipball_url" else "zipballUrl") as? String ?: error("Missing archive URL")
        require(url.length <= 512)
        val expected = SourceDownloader.candidates("https://api.github.com/repos/SillyTavern/SillyTavern/zipball/$tag").first().uri
        val actual = SourceDownloader.candidates(url).first().uri
        require(SourceDownloader.validRedirect(expected, actual)) { "Release tag and archive do not match" }
        val prerelease = if (record.has("prerelease")) record.opt("prerelease") as? Boolean ?: error("Invalid release kind") else false
        val name = record.opt("name").let { if (it == null || it == JSONObject.NULL) tag else it as? String ?: error("Invalid release name") }
        require(name.length <= 256 && name.none { it.code < 32 || it.code == 127 })
        val release = JSONObject().put("tag", tag).put("name", name).put("zipballUrl", url).put("prerelease", prerelease)
        val published = record.opt(if (fromApi) "published_at" else "publishedAt")
        if (published != null && published != JSONObject.NULL) {
            require(published is String && published.length <= 64)
            Instant.parse(published)
            release.put("publishedAt", published)
        }
        return release
    }

    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Release lookup cancelled")
    }

    private class HttpFailure(val status: Int) : IOException("Release lookup was unavailable")

    companion object {
        internal const val MAX_BYTES = 1024 * 1024
        internal const val API_URL = "https://api.github.com/repos/SillyTavern/SillyTavern/releases?per_page=30"
        internal const val STABLE_BRANCH_URL = "https://github.com/SillyTavern/SillyTavern/archive/refs/heads/release.zip"
    }
}
