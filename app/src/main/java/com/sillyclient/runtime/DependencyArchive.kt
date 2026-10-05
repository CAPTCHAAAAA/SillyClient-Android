package com.sillyclient.runtime

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * Content-addressed node_modules cache in private runtime storage.
 *
 * Archives are named "<lock SHA-256>-<archive SHA-256>.tar": the first digest
 * addresses the package-lock the tree was built from, the second lets every
 * restore verify the archive file before a single byte is extracted. Instances
 * never borrow live trees; restore materializes a fresh copy inside the
 * destination directory so staging always stays on the destination filesystem.
 */
class DependencyArchive(
    private val archiveDir: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    fun lockKey(lockFile: File): String? {
        if (!lockFile.isFile || lockFile.length() > MAX_LOCK_BYTES) return null
        return sha256Of(lockFile)
    }

    fun hasArchiveFor(lockKey: String): Boolean =
        lockKey.matches(KEY_PATTERN) && !archivesFor(lockKey).isNullOrEmpty()

    fun restore(lockKey: String, instanceDirectory: File, ensureActive: () -> Unit): Boolean {
        require(lockKey.matches(KEY_PATTERN)) { "Invalid dependency archive key" }
        val nodeModules = File(instanceDirectory, "node_modules")
        if (occupied(nodeModules)) return false
        val candidates = archivesFor(lockKey) ?: return false
        for (candidate in candidates) {
            ensureActive()
            if (sha256Of(candidate) != digestInName(candidate)) {
                runCatching { candidate.delete() }
                continue
            }
            try {
                if (UstarArchive.read(candidate, Restorer(nodeModules, ensureActive)) <= 0) {
                    throw IOException("Archive has no entries")
                }
                return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runCatching { ManagedFiles.deleteDirectory(nodeModules, instanceDirectory) }
                runCatching { candidate.delete() }
                return false
            }
        }
        return false
    }

    fun archive(instanceDirectory: File, lockKey: String, ensureActive: () -> Unit = {}): Boolean {
        require(lockKey.matches(KEY_PATTERN)) { "Invalid dependency archive key" }
        val nodeModules = File(instanceDirectory, "node_modules")
        if (!nodeModules.isDirectory || nodeModules.listFiles().isNullOrEmpty()) return false
        if (!archivesFor(lockKey).isNullOrEmpty()) return true
        archiveDir.mkdirs()
        val temporary = File(archiveDir, "tmp-${UUID.randomUUID()}.tar")
        try {
            val entries = collect(nodeModules, ensureActive)
            if (entries.isEmpty()) return false
            UstarArchive.write(temporary, entries.asSequence())
            val digest = sha256Of(temporary) ?: return false
            // The same lock always produces the same tree; concurrent writers replace it.
            archivesFor(lockKey)?.forEach { runCatching { it.delete() } }
            Files.move(
                temporary.toPath(), File(archiveDir, "$lockKey-$digest.tar").toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            runCatching { temporary.delete() }
        }
        prune(ensureActive)
        return true
    }

    private fun collect(nodeModules: File, ensureActive: () -> Unit): List<UstarArchive.Entry> {
        val root = nodeModules.toPath()
        val entries = ArrayList<UstarArchive.Entry>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                ensureActive()
                val relative = root.relativize(directory).toString().replace('\\', '/')
                if (relative.isNotEmpty()) {
                    entries += UstarArchive.Entry(relative, true, false, "",
                        0b111_101_101, 0L, UstarArchive::emptyContent)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                ensureActive()
                val relative = root.relativize(file).toString().replace('\\', '/')
                if (attributes.isSymbolicLink) {
                    val target = Files.readSymbolicLink(file).toString()
                    entries += UstarArchive.Entry(relative, false, true, target,
                        0b111_111_111, 0L, UstarArchive::emptyContent)
                } else {
                    entries += UstarArchive.Entry(relative, false, false, "",
                        if (file.toFile().canExecute()) 0b111_101_101 else 0b110_100_100,
                        attributes.size()) { file.toFile().inputStream() }
                }
                return FileVisitResult.CONTINUE
            }
        })
        return entries
    }

    private fun archivesFor(lockKey: String): List<File>? {
        if (!archiveDir.isDirectory) return null
        val files = archiveDir.listFiles { file ->
            file.isFile && file.name.startsWith("$lockKey-") && file.name.endsWith(".tar")
        } ?: return null
        if (files.isEmpty()) return null
        return files.sortedByDescending { it.lastModified() }
    }

    private fun digestInName(file: File): String? =
        ARCHIVE_NAME.find(file.name)?.groupValues?.get(2)

    private fun occupied(nodeModules: File): Boolean {
        if (!Files.exists(nodeModules.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        if (!nodeModules.isDirectory) return true
        return !nodeModules.listFiles().isNullOrEmpty()
    }

    private fun prune(ensureActive: () -> Unit) {
        if (!archiveDir.isDirectory) return
        val now = System.currentTimeMillis()
        archiveDir.listFiles { file -> file.name.startsWith("tmp-") }?.forEach { temporary ->
            if (now - temporary.lastModified() > TEMPORARY_MAX_AGE_MILLIS) {
                runCatching { temporary.delete() }
            }
        }
        val archives = archiveDir.listFiles { file ->
            file.isFile && file.name.endsWith(".tar") && !file.name.startsWith("tmp-")
        }?.toList() ?: return
        if (archives.size <= maxEntries) return
        archives.sortedByDescending { it.lastModified() }.drop(maxEntries).forEach { archive ->
            ensureActive()
            runCatching { archive.delete() }
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

    private class Restorer(
        private val nodeModules: File,
        private val ensureActive: () -> Unit
    ) : UstarArchive.Visitor {
        override fun directory(name: String, mode: Int) {
            ensureActive()
            resolve(name).mkdirs()
        }

        override fun file(name: String, mode: Int, size: Long, content: InputStream) {
            val target = resolve(name)
            target.parentFile?.mkdirs()
            Files.newOutputStream(target.toPath()).use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0) {
                    ensureActive()
                    val read = content.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("Archive entry content ended early: $name")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            if (mode and 0b001_000_000 != 0) runCatching { target.setExecutable(true, false) }
        }

        override fun symbolicLink(name: String, target: String, mode: Int) {
            ensureActive()
            val link = resolve(name)
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
        }

        private fun resolve(name: String): File {
            val target = File(nodeModules, name)
            require(ManagedFiles.isWithin(target, nodeModules)) {
                "Archive entry escapes the dependency directory"
            }
            return target
        }
    }

    companion object {
        internal const val DEFAULT_MAX_ENTRIES = 3
        internal const val MAX_LOCK_BYTES = 16L * 1024 * 1024
        private const val TEMPORARY_MAX_AGE_MILLIS = 3_600_000L
        private val KEY_PATTERN = Regex("[0-9a-f]{64}")
        private val ARCHIVE_NAME = Regex("^([0-9a-f]{64})-([0-9a-f]{64})\\.tar$")
        internal const val BUNDLED_ASSET_PREFIX = "dependency-"

        /** Cache name for an APK-bundled asset ("dependency-<key>-<digest>.tar" → "<key>-<digest>.tar"); null otherwise. */
        internal fun bundledCacheName(assetName: String): String? {
            if (!assetName.startsWith(BUNDLED_ASSET_PREFIX)) return null
            return assetName.removePrefix(BUNDLED_ASSET_PREFIX).takeIf(ARCHIVE_NAME::matches)
        }

        /** Lock key for in-memory lock content (ZIP peek); null when oversized. */
        internal fun lockKeyFor(content: ByteArray): String? {
            if (content.size > MAX_LOCK_BYTES) return null
            val digest = MessageDigest.getInstance("SHA-256").digest(content)
            return BigInteger(1, digest).toString(16).padStart(64, '0')
        }
    }
}
