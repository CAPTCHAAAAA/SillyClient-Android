package com.sillyclient.runtime

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 实例完整打包导出为 ZIP。
 * 遵循无默认路径原则：由用户显式选择目标位置（SAF 写入流或显式目标路径）。
 */
object InstanceExport {
    private const val COPY_BUFFER_BYTES = 256 * 1024

    data class Result(
        val destinationPath: String,
        val bytes: Long,
        val entryCount: Int
    )

    fun exportToStream(
        instanceDir: File,
        outputStream: OutputStream,
        onProgress: ((Int) -> Unit)? = null
    ): Pair<Int, Long> {
        require(instanceDir.isDirectory && File(instanceDir, "server.js").isFile) { "实例目录不存在或尚未安装" }
        require(ManagedFiles.isUnlinked(instanceDir)) { "实例目录不能是符号链接" }
        var entries = 0
        var bytes = 0L
        val rootPath = instanceDir.toPath()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        ZipOutputStream(BufferedOutputStream(outputStream, 256 * 1024)).use { output ->
            instanceDir.walkTopDown().onEnter { ManagedFiles.isUnlinked(it) }.forEach { file ->
                if (Thread.currentThread().isInterrupted) throw CancellationException("export cancelled")
                if (Files.isSymbolicLink(file.toPath())) return@forEach
                val relative = rootPath.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                if (relative.isEmpty()) return@forEach
                if (file.isDirectory) {
                    output.putNextEntry(ZipEntry("$relative/"))
                    output.closeEntry()
                } else if (file.isFile) {
                    output.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { copyFile(it, output, buffer) }
                    output.closeEntry()
                    entries++
                    bytes += file.length()
                    if (entries % 200 == 0) onProgress?.invoke(entries)
                }
            }
        }
        return entries to bytes
    }

    internal fun copyFile(input: InputStream, output: OutputStream, buffer: ByteArray) {
        while (true) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("export cancelled")
            val count = input.read(buffer)
            if (count < 0) return
            if (Thread.currentThread().isInterrupted) throw CancellationException("export cancelled")
            output.write(buffer, 0, count)
        }
    }

    fun exportToFile(
        instanceDir: File,
        targetFileOrDirectory: File,
        onProgress: ((Int) -> Unit)? = null
    ): Result {
        require(instanceDir.isDirectory && File(instanceDir, "server.js").isFile) { "实例目录不存在或尚未安装" }
        require(ManagedFiles.isUnlinked(instanceDir)) { "实例目录不能是符号链接" }
        val targetFile = if (targetFileOrDirectory.isDirectory) {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(targetFileOrDirectory, "${instanceDir.name}-$stamp.zip")
        } else {
            targetFileOrDirectory
        }
        require(!targetFile.canonicalFile.toPath().startsWith(instanceDir.canonicalFile.toPath())) {
            "导出位置不能位于实例目录内，请选择其他文件夹"
        }
        targetFile.parentFile?.mkdirs()
        val (entries, bytes) = FileOutputStream(targetFile).use { fos ->
            exportToStream(instanceDir, fos, onProgress)
        }
        return Result(targetFile.absolutePath, bytes, entries)
    }
}
