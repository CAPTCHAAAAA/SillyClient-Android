package com.sillyclient.runtime

import java.io.File
import java.io.IOException
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Public SillyTavern archives only. Never forwards credentials or arbitrary URLs to a mirror. */
class SourceDownloader internal constructor(
    private val connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val limits: Limits = Limits(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val openOutput: (File) -> OutputStream = { it.outputStream() }
) {
    internal data class Limits(
        val connectTimeoutMillis: Int = 5_000,
        val readTimeoutMillis: Int = 8_000,
        val sourceTimeoutMillis: Long = 120_000,
        val totalTimeoutMillis: Long = 240_000,
        val maxArchiveBytes: Long = 256L * 1024 * 1024,
        val maxUnpackedBytes: Long = 768L * 1024 * 1024,
        val maxFileBytes: Long = 128L * 1024 * 1024,
        val maxEntries: Int = 40_000,
        val maxRedirects: Int = 3
    )

    data class Progress(val source: String, val downloadedBytes: Long, val totalBytes: Long?)
    internal data class Candidate(val uri: URI, val label: String)

    /** The caller owns and deletes the returned archive after extraction; partial files are never reused. */
    fun downloadSillyTavern(
        sourceUrl: String,
        tempDirectory: File,
        operations: OperationCoordinator,
        operation: OperationCoordinator.Operation,
        onLog: (String) -> Unit = {},
        onProgress: (Progress) -> Unit = {}
    ): File {
        val sources = candidates(sourceUrl)
        operations.ensureCurrent(operation)
        check(tempDirectory.isDirectory || tempDirectory.mkdirs()) { "Could not create download directory" }
        val directory = tempDirectory.canonicalFile
        val started = nanoTime()
        val failures = mutableListOf<String>()
        for (candidate in sources) {
            operations.ensureCurrent(operation)
            if (elapsedMillis(started) >= limits.totalTimeoutMillis) break
            val partial = File.createTempFile("sillytavern-source-", ".part", directory)
            val archive = File(directory, partial.name.removeSuffix(".part") + ".zip")
            var published = false
            try {
                onLog("> Downloading SillyTavern via ${candidate.label}")
                download(candidate, partial, started, operations, operation, onProgress)
                operations.ensureCurrent(operation)
                validateArchive(partial) { operations.ensureCurrent(operation) }
                onLog("[OK] SillyTavern archive downloaded and checked (${partial.length() / 1024} KiB)")
                operations.commit(operation) {
                    if (archive.exists() || !partial.renameTo(archive)) throw StorageFailure()
                    published = true
                }
                return archive
            } catch (error: CancellationException) {
                throw error
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: StorageFailure) {
                throw error
            } catch (error: Exception) {
                operations.ensureCurrent(operation)
                val reason = when (error) {
                    is SourceFailure -> error.message ?: "invalid response"
                    is SocketTimeoutException -> "connection or transfer timed out"
                    is java.util.zip.ZipException -> "incomplete or invalid ZIP archive"
                    else -> "connection or archive validation failed"
                }
                failures.add("${candidate.label}: $reason")
                onLog("[WARN] ${candidate.label}: $reason; trying next source")
            } finally {
                if (partial.exists() && !partial.delete()) onLog("[WARN] Could not remove incomplete download")
                if (!published && archive.exists() && !archive.delete()) onLog("[WARN] Could not remove unpublished archive")
            }
        }
        operations.ensureCurrent(operation)
        throw IOException("SillyTavern download failed. Check the network or import a local ZIP. " + failures.joinToString("; "))
    }

    private fun download(
        candidate: Candidate, destination: File, started: Long,
        operations: OperationCoordinator, operation: OperationCoordinator.Operation,
        onProgress: (Progress) -> Unit
    ) {
        val sourceStarted = nanoTime()
        fun ensureActive() {
            operations.ensureCurrent(operation)
            if (elapsedMillis(started) >= limits.totalTimeoutMillis ||
                elapsedMillis(sourceStarted) >= limits.sourceTimeoutMillis) {
                throw SourceFailure("transfer deadline exceeded")
            }
        }
        var uri = candidate.uri
        val seen = mutableSetOf<URI>()
        for (redirect in 0..limits.maxRedirects) {
            ensureActive()
            if (!seen.add(uri)) throw SourceFailure("redirect loop")
            val connection = connect(uri)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = limits.connectTimeoutMillis
            connection.readTimeout = limits.readTimeoutMillis
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "SillyClient")
            connection.setRequestProperty("Accept", "application/zip, application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                operations.onCancel(operation) {
                    Thread({ connection.disconnect() }, "SillyClient-cancel-source").apply { isDaemon = true }.start()
                }.use {
                    ensureActive()
                    val status = connection.responseCode
                    ensureActive()
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        if (redirect == limits.maxRedirects) throw SourceFailure("too many redirects")
                        val location = connection.getHeaderField("Location") ?: throw SourceFailure("missing redirect location")
                        val next = try { uri.resolve(location) } catch (_: IllegalArgumentException) {
                            throw SourceFailure("invalid redirect")
                        }
                        if (!validRedirect(candidate.uri, next)) throw SourceFailure("unsafe redirect refused")
                        uri = next
                    } else {
                        if (status != 200) throw SourceFailure("HTTP $status")
                        val encoding = connection.contentEncoding
                        if (!encoding.isNullOrBlank() && !encoding.equals("identity", ignoreCase = true)) {
                            throw SourceFailure("unexpected content encoding")
                        }
                        val total = connection.contentLengthLong.takeIf { it >= 0 }
                        if (total != null && (total == 0L || total > limits.maxArchiveBytes)) {
                            throw SourceFailure("archive size limit exceeded")
                        }
                        val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
                        if (contentType == "text/html" || contentType == "application/json") {
                            throw SourceFailure("server returned a page instead of an archive")
                        }
                        onProgress(Progress(candidate.label, 0, total))
                        var downloaded = 0L
                        var lastProgress = nanoTime()
                        connection.inputStream.use { input ->
                            archiveOutput(destination).use { output ->
                                val buffer = ByteArray(65_536)
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    ensureActive()
                                    if (count < 0) break
                                    if (count == 0) continue
                                    downloaded += count
                                    if (downloaded > limits.maxArchiveBytes || (total != null && downloaded > total)) {
                                        throw SourceFailure("archive size limit exceeded")
                                    }
                                    output.write(buffer, 0, count)
                                    if (elapsedMillis(lastProgress) >= 250) {
                                        onProgress(Progress(candidate.label, downloaded, total))
                                        lastProgress = nanoTime()
                                    }
                                }
                            }
                        }
                        if (downloaded == 0L || (total != null && downloaded != total)) {
                            throw SourceFailure("incomplete response")
                        }
                        onProgress(Progress(candidate.label, downloaded, total))
                        return
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw SourceFailure("too many redirects")
    }

    /** Central-directory checks reject truncated responses/HTML and bound extraction before publishing. */
    private fun validateArchive(archive: File, ensureActive: () -> Unit) {
        ZipFile(archive).use { zip ->
            var count = 0
            var size = 0L
            var root: String? = null
            val names = mutableSetOf<String>()
            var server = false
            var manifest = false
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                ensureActive()
                val entry = entries.nextElement()
                if (++count > limits.maxEntries) throw SourceFailure("too many archive entries")
                val name = entry.name
                val parts = name.removeSuffix("/").split('/')
                if (name.contains('\\') || name.contains(':') || name.any { it.code < 32 || it.code == 127 } ||
                    parts.any { it.isEmpty() || it == "." || it == ".." } || !names.add(name)) {
                    throw SourceFailure("unsafe archive path")
                }
                if (root == null) root = parts.first()
                if (parts.first() != root || (parts.size == 1 && !entry.isDirectory)) {
                    throw SourceFailure("unexpected archive root")
                }
                if (entry.size < 0 || entry.size > limits.maxFileBytes) throw SourceFailure("invalid archive entry size")
                size += entry.size
                if (size > limits.maxUnpackedBytes) throw SourceFailure("unpacked archive size limit exceeded")
                if (!entry.isDirectory && parts.size == 2) {
                    if (parts[1] == "server.js") server = true
                    if (parts[1] == "package.json") manifest = true
                }
            }
            if (!server || !manifest) throw SourceFailure("archive has no SillyTavern entry points")
        }
    }

    private fun elapsedMillis(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanoTime() - started)
    private class SourceFailure(message: String) : IOException(message)
    private class StorageFailure : IOException("Could not save the download. Check free space and storage access before retrying.")

    private fun archiveOutput(destination: File): OutputStream {
        fun <T> save(action: () -> T): T = try { action() } catch (_: IOException) { throw StorageFailure() }
        return object : FilterOutputStream(save { openOutput(destination) }) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = save { out.write(bytes, offset, length) }
            override fun write(value: Int) = save { out.write(value) }
            override fun close() = save { out.close() }
        }
    }

    companion object {
        private const val REPOSITORY = "SillyTavern/SillyTavern"
        // Published archive proxy endpoints; their transport does not establish upstream authenticity.
        private val mirrors = listOf("ghfast.top", "gh-proxy.org", "ghproxy.net")

        internal fun candidates(sourceUrl: String): List<Candidate> {
            val source = try { URI(sourceUrl) } catch (_: Exception) {
                throw IllegalArgumentException("Invalid SillyTavern archive URL")
            }
            val ref = archiveRef(source) ?: throw IllegalArgumentException("Only public SillyTavern archive URLs are supported")
            val archive = "https://github.com/$REPOSITORY/archive/$ref.zip"
            return listOf(Candidate(URI("https://codeload.github.com/$REPOSITORY/zip/$ref"), "GitHub codeload")) +
                mirrors.map { Candidate(URI("https://$it/$archive"), it) } + Candidate(URI(archive), "GitHub")
        }

        internal fun validRedirect(source: URI, target: URI): Boolean {
            if (!safeHttps(target)) return false
            val original = unwrapMirror(source) ?: source
            val next = unwrapMirror(target) ?: target
            val expected = archiveRef(original) ?: return false
            val actual = archiveRef(next) ?: return false
            if (actual == expected) return true
            // GitHub resolves an unqualified tag/branch to its fully qualified ref on redirect.
            return !expected.startsWith("refs/") &&
                (actual == "refs/tags/$expected" || actual == "refs/heads/$expected")
        }

        private fun unwrapMirror(uri: URI): URI? {
            if (!safeHttps(uri) || uri.host !in mirrors) return null
            return try { URI(uri.rawPath.removePrefix("/")) } catch (_: Exception) { null }
        }

        private fun safeHttps(uri: URI): Boolean = uri.scheme == "https" && uri.host != null &&
            (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath == uri.path && !uri.toString().contains('\\')

        private fun archiveRef(uri: URI): String? {
            if (!safeHttps(uri)) return null
            val path = uri.path
            val ref = when {
                uri.host == "api.github.com" && path.startsWith("/repos/$REPOSITORY/zipball/") ->
                    path.removePrefix("/repos/$REPOSITORY/zipball/")
                uri.host == "github.com" && path.startsWith("/$REPOSITORY/archive/") && path.endsWith(".zip") ->
                    path.removePrefix("/$REPOSITORY/archive/").removeSuffix(".zip")
                uri.host == "codeload.github.com" && path.startsWith("/$REPOSITORY/zip/") ->
                    path.removePrefix("/$REPOSITORY/zip/")
                uri.host == "codeload.github.com" && path.startsWith("/$REPOSITORY/legacy.zip/") ->
                    path.removePrefix("/$REPOSITORY/legacy.zip/")
                else -> return null
            }
            if (!ref.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,159}")) ||
                ref.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
            return ref
        }
    }
}
