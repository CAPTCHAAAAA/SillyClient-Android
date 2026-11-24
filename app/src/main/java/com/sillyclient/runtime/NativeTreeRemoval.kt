package com.sillyclient.runtime

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Delete approved children without following their links or invoking a shell. */
class NativeTreeRemoval(
    private val supervisor: ProcessSupervisor,
    private val idleTimeoutMillis: Long = 120_000,
    private val command: List<String> = listOf("/system/bin/rm"),
    private val progressIntervalMillis: Long = 1_000,
    private val identityFileName: String = ".sc-identity",
    private val fallbackRetries: Int = 0
) {
    data class Progress(val removedEntries: Long, val elapsedMillis: Long)

    fun remove(
        children: List<File>,
        ownerDirectory: File,
        instanceId: String,
        operation: OperationCoordinator.Operation? = null,
        ensureActive: () -> Unit = {},
        onProgress: (Progress) -> Unit = {}
    ): Progress {
        require(command.isNotEmpty() && idleTimeoutMillis > 0 && progressIntervalMillis > 0)
        ensureActive()
        if (children.isEmpty()) return Progress(0, 0)
        val owner = ownerDirectory.absoluteFile
        require(owner.parentFile != null && owner.toPath().normalize() == owner.toPath()) {
            "Native removal requires an explicit non-root directory"
        }
        val original = attributes(owner)
        val canonicalOwner = owner.canonicalFile
        val targets = children.distinct().map { child ->
            val literal = child.absoluteFile
            require(literal.parentFile == owner && literal.toPath().normalize() == literal.toPath()) {
                "Native removal target is not a direct child of the approved directory"
            }
            require(literal.name != identityFileName) { "The ownership marker must survive content removal" }
            literal
        }
        val start = System.nanoTime()
        val removed = AtomicLong()
        val lastReport = AtomicLong(start)
        fun progress() = Progress(removed.get(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
        fun report(force: Boolean = false) {
            val now = System.nanoTime()
            val previous = lastReport.get()
            if (force || now - previous >= TimeUnit.MILLISECONDS.toNanos(progressIntervalMillis)) {
                if (force || lastReport.compareAndSet(previous, now)) runCatching { onProgress(progress()) }
            }
        }
        try {
            for (batch in batches(targets)) {
                ensureActive()
                requireSameDirectory(owner, canonicalOwner, original)
                
                // 幂等性设计：先过滤已不存在的文件（可能被手动删除）
                val existingFiles = batch.filter { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
                if (existingFiles.isEmpty()) {
                    // 批次中所有文件已不存在，目标已达成，跳过 rm 命令
                    continue
                }
                
                val diagnostics = linkedSetOf<String>()
                // 每批重新捕获 generation：无关实例的 stopAll（如并行启动他实例）会递增
                // generation，若沿用旧值会让删除在批间被误杀，留下"已删卡片但文件残留"。
                val process = supervisor.launch(
                    ProcessBuilder(command + listOf("-rfv", "--") + existingFiles.map { it.absolutePath }),
                    instanceId, operation, supervisor.generation()
                )
                val result = supervisor.waitForIdle(process, idleTimeoutMillis, ensureActive,
                    onErrorLine = { line -> synchronized(diagnostics) {
                        if (line.isNotBlank() && diagnostics.size < 4) diagnostics.add(errorCategory(line))
                    } }
                ) { line ->
                    if (line.startsWith("rm '") || line.startsWith("rmdir '") || line.startsWith("removed ")) {
                        removed.incrementAndGet()
                        report()
                        true
                    } else false
                }
                ensureActive()
                val detail = synchronized(diagnostics) { diagnostics.joinToString("; ") }
                check(!result.timedOut) { "删除操作长时间无进展已中止；实例文件已保留，请重试" }
                check(result.exitCode == 0) {
                    "系统删除命令失败（退出码 ${result.exitCode}）" + if (detail.isEmpty()) "" else "：$detail"
                }
                requireSameDirectory(owner, canonicalOwner, original)
                var remaining = batch.filter { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
                if (remaining.isNotEmpty() && fallbackRetries > 0) {
                    remaining = removeRemainingWithFallback(remaining, owner, canonicalOwner, original, ensureActive, removed, ::report)
                }
                check(remaining.isEmpty()) {
                    val summary = summarizeRemaining(remaining, owner)
                    "实例目录仍有残留文件（$summary）；实例标识已保留，请重试删除"
                }
            }
            return progress()
        } finally {
            report(force = true)
        }
    }

    /**
     * Bounded NOFOLLOW_LINKS fallback sweep when `/system/bin/rm` exited 0 yet FUSE visibility lag
     * or a releasing handle left entries behind. Never follows symlinks and never touches identityFileName.
     */
    private fun removeRemainingWithFallback(
        initialRemaining: List<File>,
        owner: File,
        canonicalOwner: File,
        original: BasicFileAttributes,
        ensureActive: () -> Unit,
        removed: AtomicLong,
        report: (Boolean) -> Unit
    ): List<File> {
        var remaining = initialRemaining
        val delays = longArrayOf(200L, 500L, 1_000L)
        for (attempt in 0 until fallbackRetries) {
            ensureActive()
            requireSameDirectory(owner, canonicalOwner, original)
            val delayMillis = delays[attempt.coerceAtMost(delays.lastIndex)]
            try {
                Thread.sleep(delayMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
            ensureActive()
            requireSameDirectory(owner, canonicalOwner, original)
            for (target in remaining) {
                ensureActive()
                if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                if (target.name == identityFileName) continue
                deleteTreeNoFollow(target.toPath(), owner.toPath(), ensureActive, removed, report)
            }
            remaining = remaining.filter { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            if (remaining.isEmpty()) break
        }
        return remaining
    }

    private fun deleteTreeNoFollow(
        target: Path,
        ownerPath: Path,
        ensureActive: () -> Unit,
        removed: AtomicLong,
        report: (Boolean) -> Unit
    ) {
        val attrs = runCatching { Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
            .getOrNull() ?: return
        if (attrs.isSymbolicLink || !attrs.isDirectory) {
            if (target.fileName?.toString() != identityFileName && runCatching { Files.deleteIfExists(target) }.getOrDefault(false)) {
                removed.incrementAndGet()
                report(false)
            }
            return
        }
        runCatching {
            Files.walkFileTree(target, emptySet(), 64, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, fileAttrs: BasicFileAttributes): FileVisitResult {
                    ensureActive()
                    if (file.parent == ownerPath && file.fileName?.toString() == identityFileName) {
                        return FileVisitResult.CONTINUE
                    }
                    if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) {
                        removed.incrementAndGet()
                        report(false)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    ensureActive()
                    if (runCatching { Files.deleteIfExists(dir) }.getOrDefault(false)) {
                        removed.incrementAndGet()
                        report(false)
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    /** Produce sanitized residual counts without leaking any user filenames or absolute paths. */
    private fun summarizeRemaining(remaining: List<File>, owner: File): String {
        var fuseHidden = false
        var scanned = 0
        var dirCount = 0
        var fileCount = 0
        for (item in remaining) {
            val path = item.toPath()
            val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            if (attrs?.isDirectory == true && !attrs.isSymbolicLink) {
                dirCount++
                runCatching {
                    Files.walkFileTree(path, emptySet(), 16, object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, a: BasicFileAttributes): FileVisitResult {
                            scanned++
                            if (file.fileName?.toString()?.startsWith(".fuse_hidden") == true) fuseHidden = true
                            return if (scanned >= 128) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                    })
                }
            } else {
                fileCount++
                if (item.name.startsWith(".fuse_hidden")) fuseHidden = true
            }
            if (!ManagedFiles.isWithin(item, owner)) break
        }
        val parts = mutableListOf<String>()
        parts.add("顶层 ${remaining.size} 项")
        if (dirCount > 0) parts.add("目录 $dirCount")
        if (fileCount > 0) parts.add("文件 $fileCount")
        if (fuseHidden) parts.add("含进程占用句柄(.fuse_hidden)")
        return parts.joinToString("，")
    }

    private fun batches(files: List<File>): List<List<File>> {
        val result = mutableListOf<List<File>>()
        var current = mutableListOf<File>()
        var bytes = 0
        for (file in files) {
            val size = file.absolutePath.toByteArray(Charsets.UTF_8).size + 1
            require(size <= 24_000) { "Native removal target path is too long" }
            if (current.size >= 64 || bytes + size > 24_000) {
                result.add(current)
                current = mutableListOf()
                bytes = 0
            }
            current.add(file)
            bytes += size
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }

    private fun attributes(directory: File): BasicFileAttributes =
        Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
            require(it.isDirectory && !it.isSymbolicLink) { "Native removal requires an unlinked directory" }
        }

    private fun requireSameDirectory(directory: File, canonical: File, original: BasicFileAttributes) {
        require(directory.canonicalFile == canonical) { "实例目录在删除过程中被移动，操作已中止" }
        val current = attributes(directory)
        require(original.fileKey()?.let { it == current.fileKey() } ?: (original.creationTime() == current.creationTime())) {
            "实例目录标识在删除过程中发生变化，操作已中止"
        }
    }

    /** Match patterns stay English because they mirror the rm/Toybox error output. */
    private fun errorCategory(line: String): String = if (line.contains("EACCES")) "权限不足" else listOf(
        "Permission denied" to "权限不足", "Read-only file system" to "只读文件系统",
        "Device or resource busy" to "文件被占用", "Operation not permitted" to "操作不被允许",
        "Directory not empty" to "目录非空", "No such file or directory" to "文件不存在",
        "Invalid argument" to "无效参数"
    ).firstOrNull { line.contains(it.first, ignoreCase = true) }?.second ?: "系统删除命令报告未知错误"
}
