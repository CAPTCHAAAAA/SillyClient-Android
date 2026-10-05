package com.sillyclient.runtime

import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Device-side cache of downloaded SillyTavern source archives, keyed by the
 * request URL and sealed by the downloaded content digest, so reinstalling a
 * version the device already fetched needs no network. Same content-addressed
 * shape as DependencyArchive; entries never leave private storage and never
 * borrow an existing instance's source.
 */
class SourceArchiveCache(
    private val cacheDir: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    /** Returns the verified cached archive for the key, dropping corrupt entries. */
    fun cachedArchive(urlKey: String): File? {
        if (!urlKey.matches(KEY_PATTERN)) return null
        val candidates = archivesFor(urlKey) ?: return null
        for (candidate in candidates) {
            if (candidate.length() > 0 && sha256Of(candidate) == digestInName(candidate)) return candidate
            runCatching { candidate.delete() }
        }
        return null
    }

    /**
     * Moves the downloaded archive into the cache under its content digest and
     * returns the sealed entry; null leaves the original untouched. A same-url
     * entry already cached consumes the download, whose content is identical.
     */
    fun store(urlKey: String, archive: File): File? {
        if (!urlKey.matches(KEY_PATTERN) || !archive.isFile || archive.length() == 0L) return null
        cacheDir.mkdirs()
        val digest = sha256Of(archive) ?: return null
        val target = File(cacheDir, "$urlKey-$digest.zip")
        if (target.exists()) {
            runCatching { archive.delete() }
            return cachedArchive(urlKey)
        }
        val temporary = File(cacheDir, "tmp-${UUID.randomUUID()}.zip")
        return try {
            Files.move(archive.toPath(), temporary.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            prune()
            target
        } catch (error: Exception) {
            runCatching { temporary.delete() }
            null
        }
    }

    private fun archivesFor(urlKey: String): List<File>? {
        if (!cacheDir.isDirectory) return null
        val files = cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("$urlKey-") && file.name.endsWith(".zip")
        } ?: return null
        if (files.isEmpty()) return null
        return files.sortedByDescending { it.lastModified() }
    }

    private fun digestInName(file: File): String? =
        ARCHIVE_NAME.find(file.name)?.groupValues?.get(2)

    private fun prune() {
        if (!cacheDir.isDirectory) return
        val now = System.currentTimeMillis()
        cacheDir.listFiles { file -> file.name.startsWith("tmp-") }?.forEach { temporary ->
            if (now - temporary.lastModified() > TEMPORARY_MAX_AGE_MILLIS) {
                runCatching { temporary.delete() }
            }
        }
        val archives = cacheDir.listFiles { file ->
            file.isFile && file.name.endsWith(".zip") && !file.name.startsWith("tmp-")
        }?.toList() ?: return
        if (archives.size <= maxEntries) return
        archives.sortedByDescending { it.lastModified() }.drop(maxEntries).forEach { stale ->
            runCatching { stale.delete() }
        }
    }

    private fun sha256Of(file: File): String? = runCatching {
        Files.newInputStream(file.toPath()).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
        }
    }.getOrNull()

    companion object {
        // Source archives weigh tens of MiB each; a small bound keeps the
        // private volume predictable while still covering recent versions.
        internal const val DEFAULT_MAX_ENTRIES = 2
        private const val TEMPORARY_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
        private val KEY_PATTERN = Regex("[0-9a-f]{64}")
        private val ARCHIVE_NAME = Regex("^([0-9a-f]{64})-([0-9a-f]{64})\\.zip$")

        internal fun urlKey(url: String): String =
            BigInteger(1, MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)))
                .toString(16).padStart(64, '0')
    }
}
