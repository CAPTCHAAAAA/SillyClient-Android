package com.sillyclient.runtime

import java.io.File
import java.io.RandomAccessFile

object LogService {
    @Synchronized
    fun append(file: File, line: String, maxBytes: Long = 2 * 1024 * 1024) {
        val bytes = (line.take(16_384) + "\n").toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs()
        if (file.isFile && file.length() + bytes.size > maxBytes) rotate(file)
        java.io.FileOutputStream(file, true).use { it.write(bytes) }
    }

    /** Bound both I/O and allocations even for a huge log or a single unterminated line. */
    fun tail(file: File, maxLines: Int = 30, maxBytes: Int = 128 * 1024): List<String> {
        if (!file.isFile || maxLines <= 0 || maxBytes <= 0) return emptyList()
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val start = (length - maxBytes).coerceAtLeast(0)
            input.seek(start)
            val bytes = ByteArray((length - start).toInt())
            input.readFully(bytes)
            var text = bytes.toString(Charsets.UTF_8)
            if (start > 0) text = text.substringAfter('\n', "")
            text.lineSequence().filter { it.isNotEmpty() }.toList().takeLast(maxLines)
        }
    }

    @Synchronized
    fun rotate(file: File) {
        if (!file.isFile) return
        val previous = File(file.parentFile, file.name + ".1")
        if (previous.exists() && !previous.delete()) return
        if (!file.renameTo(previous)) return
    }
}
