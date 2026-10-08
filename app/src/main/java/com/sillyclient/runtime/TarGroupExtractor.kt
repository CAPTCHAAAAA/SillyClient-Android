package com.sillyclient.runtime

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException

/**
 * Extracts a dependency archive with several platform `tar` child processes
 * running in parallel, each restricted to a disjoint set of top-level
 * packages via `-T` member lists.
 *
 * Measured on the target device: the app's own threads create files on
 * emulated storage at ~7 ms/file with zero parallel scaling, one tar child
 * copies the same archive at ~5 ms/file, and six children on disjoint package
 * groups reach ~2 ms/file — the platform's single-file creation floor. Writing
 * overlapping directories in parallel is *slower* than serial (FUSE directory
 * contention), so the groups never share a subtree: each top-level package
 * (including a whole `@scope`) belongs to exactly one process. The in-process
 * restorer in [DependencyArchive] remains the fallback when the platform tar
 * is unavailable or any child fails.
 */
class TarGroupExtractor(
    private val paths: RuntimePaths,
    private val operations: OperationCoordinator,
    private val processes: ProcessSupervisor,
    private val groups: Int = GROUPS
) {
    fun available(): Boolean = systemTar() != null

    /**
     * Returns true when every group extracted cleanly into [targetDirectory].
     * On failure the caller falls back to the in-process restorer and owns the
     * (staging) target directory, which may then be wiped.
     */
    fun extract(
        archive: File,
        targetDirectory: File,
        ensureActive: () -> Unit,
        onFiles: (Int) -> Unit = {}
    ): Boolean {
        val tar = systemTar() ?: return false
        return try {
            // ProcessBuilder fails with error=2 when the working directory is
            // absent; the bank directory does not exist before its first use.
            require(targetDirectory.isDirectory || targetDirectory.mkdirs()) {
                "无法创建解包目标目录"
            }
            val plan = loadOrCreateGroups(archive, ensureActive)
            if (plan.isEmpty()) return false
            runGroups(tar, archive, plan.map { it.file }, targetDirectory, ensureActive, onFiles)
            // Every planned top-level package must exist after extraction; a
            // silent member mismatch must fall back rather than ship a
            // partially materialized dependency tree.
            val missing = plan.flatMap { it.names }.firstOrNull { name ->
                !File(targetDirectory, name).exists()
            }
            check(missing == null) { "依赖解包不完整：缺少 $missing" }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("tar-groups failed: ${error.javaClass.simpleName}: ${error.message}")
            false
        }
    }

    private data class GroupPlan(val file: File, val names: List<String>)

    private fun loadOrCreateGroups(archive: File, ensureActive: () -> Unit): List<GroupPlan> {
        // The shard cache of earlier builds is superseded and wastes ~300 MB.
        runCatching { File(archive.parentFile, "shards").deleteRecursively() }
        val groupDir = File(archive.parentFile, "groups/${archive.name}")
        val manifest = File(groupDir, MANIFEST)
        if (manifest.isFile) {
            val listed = manifest.readLines().filter { it.isNotBlank() }.map { File(groupDir, it) }
            if (listed.isNotEmpty()) {
                val plans = listed.map { GroupPlan(it, it.readLines().filter { line -> line.isNotBlank() }) }
                if (plans.all { it.file.isFile && it.file.length() > 0 && it.names.isNotEmpty() }) return plans
            }
        }
        groupDir.deleteRecursively()
        groupDir.mkdirs()
        val entries = scanTopLevels(archive, ensureActive)
        if (entries.isEmpty()) return emptyList()
        val planned = planGroups(entries, groups)
        val plans = planned.mapIndexed { index, names ->
            val file = File(groupDir, "g$index.txt").apply { writeText(names.joinToString("\n") + "\n") }
            GroupPlan(file, names)
        }
        Files.write(manifest.toPath(),
            plans.joinToString("\n") { it.file.name }.toByteArray(Charsets.UTF_8))
        return plans
    }

    private fun runGroups(
        tar: File,
        archive: File,
        groupFiles: List<File>,
        targetDirectory: File,
        ensureActive: () -> Unit,
        onFiles: (Int) -> Unit
    ) {
        val operation = operations.context()
        val generation = processes.generation()
        val total = java.util.concurrent.atomic.AtomicInteger()
        val running = mutableListOf<Process>()
        try {
            for (group in groupFiles) {
                ensureActive()
                val builder = ProcessBuilder(
                    tar.absolutePath, "-x", "-v", "-f", archive.absolutePath, "-T", group.absolutePath
                )
                builder.directory(targetDirectory)
                running += processes.launch(builder, operation?.instanceId ?: "dependencies", operation, generation)
            }
            // Every process needs a consumer from the moment it starts: `-v`
            // prints one line per file, and a 64 KB pipe fills long before a
            // sequential waiter reaches the later processes — which would block
            // them in pipe_write and silently serialize the extraction. One
            // waiter thread per process drains them all concurrently.
            val failure = java.util.concurrent.atomic.AtomicReference<Exception?>()
            val firstError = java.util.concurrent.atomic.AtomicReference<String?>()
            val waiters = running.map { process ->
                Thread {
                    try {
                        val result = processes.waitForIdle(process, IDLE_TIMEOUT_MILLIS, ensureActive,
                            onErrorLine = { line ->
                                if (line.isNotBlank()) firstError.compareAndSet(null, line.take(200))
                            }
                        ) { line ->
                            if (line.isNotBlank()) {
                                onFiles(total.incrementAndGet())
                                true
                            } else false
                        }
                        if (result.timedOut) {
                            failure.compareAndSet(null, IOException("依赖解包长时间无进展已中止"))
                        } else if (result.exitCode != 0) {
                            failure.compareAndSet(null, IOException("依赖解包失败（退出码 ${result.exitCode}）" +
                                (firstError.get()?.let { "：$it" } ?: "")))
                        }
                    } catch (cancelled: CancellationException) {
                        failure.compareAndSet(null, cancelled)
                    } catch (error: Exception) {
                        failure.compareAndSet(null, error)
                    }
                }.apply {
                    name = "SC-tar-wait"
                    isDaemon = true
                }.also { it.start() }
            }
            waiters.forEach { it.join() }
            when (val error = failure.get()) {
                null -> Unit
                is CancellationException -> throw error
                else -> throw error
            }
        } finally {
            running.forEach { process -> runCatching { if (process.isAlive) process.destroyForcibly() } }
        }
    }

    /**
     * Sequential header walk that only records top-level names and their entry
     * counts; data blocks are skipped by seeking, so the 300 MB archive is
     * scanned in well under a second. Long-name ('L') blocks are paired with
     * the entry they describe.
     */
    internal fun scanTopLevels(archive: File, ensureActive: () -> Unit = {}): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        FileChannel.open(archive.toPath(), StandardOpenOption.READ).use { channel ->
            val header = ByteBuffer.allocate(UstarArchive.BLOCK_BYTES)
            var position = 0L
            var pendingName: String? = null
            while (position + UstarArchive.BLOCK_BYTES <= channel.size()) {
                ensureActive()
                header.clear()
                var read = 0
                while (read < UstarArchive.BLOCK_BYTES) {
                    val count = channel.read(header, position + read)
                    if (count < 0) break
                    read += count
                }
                if (read < UstarArchive.BLOCK_BYTES) throw IOException("Truncated archive header")
                val bytes = header.array()
                if (bytes.all { it == 0.toByte() }) break
                val type = bytes[TYPE_OFFSET].toInt().toChar()
                val size = parseOctal(bytes, SIZE_OFFSET, 12)
                require(size >= 0) { "Negative archive entry size" }
                val name = when (type) {
                    'L' -> readContent(channel, position + UstarArchive.BLOCK_BYTES, size)
                    else -> pendingName ?: fieldName(bytes)
                }
                when (type) {
                    'L' -> pendingName = name
                    'K' -> Unit
                    else -> {
                        pendingName = null
                        counts.merge(name.substringBefore('/'), 1, Int::plus)
                    }
                }
                position += UstarArchive.BLOCK_BYTES + size + padding(size)
            }
        }
        return counts
    }

    /** Balanced greedy partition: heaviest packages first, always into the lightest group. */
    internal fun planGroups(entries: Map<String, Int>, groupCount: Int): List<List<String>> {
        require(groupCount > 0) { "Group count must be positive" }
        val groups = List(groupCount) { mutableListOf<String>() }
        val weights = LongArray(groupCount)
        val ordered = entries.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        for (entry in ordered) {
            var target = 0
            for (index in groups.indices) if (weights[index] < weights[target]) target = index
            groups[target].add(entry.key)
            weights[target] += entry.value.toLong()
        }
        return groups.filter { it.isNotEmpty() }
    }

    private fun readContent(channel: FileChannel, offset: Long, size: Long): String {
        val buffer = ByteBuffer.allocate(size.toInt())
        var read = 0
        while (read < size.toInt()) {
            val count = channel.read(buffer, offset + read)
            if (count < 0) break
            read += count
        }
        return String(buffer.array(), 0, read, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun padding(size: Long): Int =
        ((UstarArchive.BLOCK_BYTES - size % UstarArchive.BLOCK_BYTES) % UstarArchive.BLOCK_BYTES).toInt()

    /**
     * ustar stores names longer than 100 bytes either as a GNU 'L' block or by
     * splitting into the 155-byte prefix field plus the 100-byte name field;
     * the split form is what this writer emits for most deep paths, so the
     * prefix must be re-attached or package names would be cut to their tails.
     */
    private fun fieldName(header: ByteArray): String {
        val name = stringField(header, 0, 100)
        val prefix = stringField(header, PREFIX_OFFSET, 155)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun stringField(header: ByteArray, offset: Int, limit: Int): String {
        var end = offset
        while (end < offset + limit && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        var seen = false
        for (index in offset until offset + length) {
            val char = header[index].toInt().toChar()
            if (char == ' ' || char == 0.toChar()) { if (seen) break else continue }
            require(char in '0'..'7') { "Invalid archive size field" }
            value = value * 8 + (char - '0')
            seen = true
        }
        return value
    }

    private fun systemTar(): File? = File("/system/bin/tar").takeIf { it.canExecute() }

    private fun log(message: String) {
        runCatching { android.util.Log.w(TAG, message) }
    }

    companion object {
        private const val TAG = "SillyClient"
        internal const val GROUPS = 6
        private const val MANIFEST = "manifest"
        private const val IDLE_TIMEOUT_MILLIS = 120_000L
        private const val SIZE_OFFSET = 124
        private const val TYPE_OFFSET = 156
        private const val PREFIX_OFFSET = 345
    }
}
