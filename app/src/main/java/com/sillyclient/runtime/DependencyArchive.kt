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
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val removeStaging: (File, File, () -> Unit) -> Unit = { directory, root, verify ->
        verify()
        check(ManagedFiles.deleteDirectory(directory, root)) { "Could not remove dependency staging" }
    },
    /**
     * Optional out-of-process extractor (parallel platform tar on shards).
     * When it reports success the in-process restorer is skipped entirely;
     * otherwise its partial output is wiped and the restorer takes over.
     */
    private val childExtract: ((File, File, () -> Unit, (Int) -> Unit) -> Boolean)? = null
) {
    fun lockKey(lockFile: File): String? {
        if (!lockFile.isFile || lockFile.length() > MAX_LOCK_BYTES) return null
        return sha256Of(lockFile)
    }

    fun hasArchiveFor(lockKey: String): Boolean =
        lockKey.matches(KEY_PATTERN) && !archivesFor(lockKey).isNullOrEmpty()

    fun restore(
        lockKey: String,
        instanceDirectory: File,
        onFileRestored: (Int) -> Unit = {},
        replaceIncomplete: Boolean = false,
        skipExecutableLinks: Boolean = false,
        validateModules: (File) -> Boolean = { true },
        ensureActive: () -> Unit
    ): Boolean {
        require(lockKey.matches(KEY_PATTERN)) { "Invalid dependency archive key" }
        val transaction = DependencyRestoreTransaction(removeStaging)
        if (transaction.recover(instanceDirectory, lockKey, validateModules, ensureActive)) return true
        val nodeModules = File(instanceDirectory, "node_modules")
        if (!replaceIncomplete && occupied(nodeModules)) return false
        val candidates = archivesFor(lockKey) ?: return false
        for (candidate in candidates) {
            ensureActive()
            val digest = sha256Of(candidate, ensureActive) ?: throw IOException("Could not read dependency archive")
            if (digest != digestInName(candidate)) {
                runCatching { candidate.delete() }
                continue
            }
            return transaction.restore(instanceDirectory, lockKey, replaceIncomplete, validateModules, ensureActive) { prepared ->
                val extractedByChildren = childExtract?.invoke(candidate, prepared, ensureActive, onFileRestored) ?: false
                if (!extractedByChildren) {
                    // A failed child attempt may have written a partial tree; the
                    // in-process restorer must start from a clean directory.
                    prepared.listFiles()?.forEach { partial -> partial.deleteRecursively() }
                    if (!prepared.isDirectory) Files.createDirectories(prepared.toPath())
                    Restorer(prepared, ensureActive, onFileRestored, skipExecutableLinks).use { restorer ->
                        if (UstarArchive.read(candidate, restorer) <= 0) {
                            throw IOException("Archive has no entries")
                        }
                        restorer.await()
                    }
                }
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

    private fun sha256Of(file: File, ensureActive: () -> Unit = {}): String? = try {
        Files.newInputStream(file.toPath()).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        null
    }

    /**
     * Tar is read strictly sequentially, but FUSE file creation is what makes a
     * restore take minutes: file bodies are therefore read on this thread and
     * handed to a bounded pool of concurrent writers. In-flight bytes stay
     * capped so memory cannot balloon; oversized entries write inline.
     */
    private class Restorer(
        private val nodeModules: File,
        private val ensureActive: () -> Unit,
        private val onFileRestored: (Int) -> Unit,
        private val skipExecutableLinks: Boolean
    ) : UstarArchive.Visitor, AutoCloseable {
        private val writers = java.util.concurrent.Executors.newFixedThreadPool(WRITERS) { runnable ->
            Thread(runnable, "SC-dependency-writer").apply { isDaemon = true }
        }
        private val futures = java.util.concurrent.ConcurrentLinkedQueue<java.util.concurrent.Future<*>>()
        private val inFlight = java.util.concurrent.Semaphore(IN_FLIGHT_BYTES)
        private val restored = java.util.concurrent.atomic.AtomicInteger()
        // The tar lists every directory before its contents, so only unseen
        // parents need a mkdir; on FUSE each avoided stat is a round trip.
        private val createdDirectories = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        @Volatile private var failure: Exception? = null

        override fun directory(name: String, mode: Int) {
            ensureActive()
            resolve(name).mkdirs()
            createdDirectories.add(name)
        }

        override fun file(name: String, mode: Int, size: Long, content: InputStream) {
            val target = resolve(name)
            val parentName = name.substringBeforeLast('/', "")
            if (parentName.isNotEmpty() && !createdDirectories.contains(parentName)) {
                target.parentFile?.mkdirs()
                createdDirectories.add(parentName)
            }
            if (size > IN_FLIGHT_BYTES) {
                writeInline(target, size, content, mode)
                return
            }
            inFlight.acquireUninterruptibly(size.toInt())
            val bytes = ByteArray(size.toInt())
            var filled = 0
            while (filled < size) {
                ensureActive()
                val read = content.read(bytes, filled, size.toInt() - filled)
                if (read < 0) throw IOException("Archive entry content ended early: $name")
                filled += read
            }
            futures.add(writers.submit {
                try {
                    Files.newOutputStream(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE).use { output -> output.write(bytes) }
                    if (mode and 0b001_000_000 != 0) runCatching { target.setExecutable(true, false) }
                    onFileRestored(restored.incrementAndGet())
                } catch (error: Exception) {
                    failure = failure ?: (error as? IOException ?: IOException("Could not restore $name", error))
                } finally {
                    inFlight.release(bytes.size)
                }
            })
        }

        private fun writeInline(target: File, size: Long, content: InputStream, mode: Int) {
            try {
                target.parentFile?.mkdirs()
                Files.newOutputStream(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = size
                    while (remaining > 0) {
                        val read = content.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw IOException("Archive entry content ended early: ${target.name}")
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
                if (mode and 0b001_000_000 != 0) runCatching { target.setExecutable(true, false) }
                onFileRestored(restored.incrementAndGet())
            } catch (error: Exception) {
                failure = failure ?: (error as? IOException ?: IOException("Could not restore ${target.name}", error))
            }
        }

        override fun symbolicLink(name: String, target: String, mode: Int) {
            ensureActive()
            // npm's --bin-links=false policy on shared storage: executable aliases
            // are unused by Node resolution and cannot be created on FUSE.
            if (skipExecutableLinks && name.split('/').dropLast(1).lastOrNull() == ".bin") return
            val link = resolve(name)
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
        }

        /** Blocks until every queued write has finished and surfaces failures. */
        fun await() {
            writers.shutdown()
            for (future in futures) {
                try { future.get() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    failure = failure ?: (error.cause as? Exception ?: IOException("Dependency restore failed", error))
                }
            }
            failure?.let { throw it }
        }

        override fun close() {
            writers.shutdownNow()
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
        internal const val WRITERS = 16
        internal const val IN_FLIGHT_BYTES = 48 * 1024 * 1024
        private const val TEMPORARY_MAX_AGE_MILLIS = 3_600_000L
        private val KEY_PATTERN = Regex("[0-9a-f]{64}")
        private val ARCHIVE_NAME = Regex("^([0-9a-f]{64})-([0-9a-f]{64})\\.tar$")
        internal const val BUNDLED_ASSET_PREFIX = "dependency-"

        /** Cache name for an APK-bundled asset ("dependency-<key>-<digest>.tar" → "<key>-<digest>.tar"); null otherwise. */
        internal fun bundledCacheName(assetName: String): String? {
            if (!assetName.startsWith(BUNDLED_ASSET_PREFIX)) return null
            return assetName.removePrefix(BUNDLED_ASSET_PREFIX).removeSuffix(".gz").takeIf(ARCHIVE_NAME::matches)
        }

        /** Lock key for in-memory lock content (ZIP peek); null when oversized. */
        internal fun lockKeyFor(content: ByteArray): String? {
            if (content.size > MAX_LOCK_BYTES) return null
            val digest = MessageDigest.getInstance("SHA-256").digest(content)
            return BigInteger(1, digest).toString(16).padStart(64, '0')
        }
    }
}
