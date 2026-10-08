package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Delete approved children without following their links or invoking a shell. */
class NativeTreeRemoval(
    private val supervisor: ProcessSupervisor,
    private val idleTimeoutMillis: Long = 120_000,
    private val command: List<String> = listOf("/system/bin/rm"),
    private val progressIntervalMillis: Long = 1_000,
    private val identityFileName: String = ".sc-identity"
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
        val generation = supervisor.generation()
        try {
            for (batch in batches(targets)) {
                ensureActive()
                requireSameDirectory(owner, canonicalOwner, original)
                val diagnostics = linkedSetOf<String>()
                val process = supervisor.launch(
                    ProcessBuilder(command + listOf("-rfv", "--") + batch.map { it.absolutePath }),
                    instanceId, operation, generation
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
                check(batch.none { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }) {
                    "实例目录仍有残留文件；实例标识已保留，请重试删除"
                }
            }
            return progress()
        } finally {
            report(force = true)
        }
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
