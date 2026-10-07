package com.sillyclient.runtime

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-owned diagnostic log, written straight to private storage.
 *
 * The platform's logcat has proven unreliable on the target ROM (rate
 * throttling and multi-megabyte system floods hide app lines exactly when
 * they are needed), so every diagnostic line lands here as well. The file
 * rotates at 2 MB keeping one previous generation; it is pulled with root
 * during development and costs nothing at runtime.
 */
object Diag {
    private val lock = Any()
    private const val MAX_BYTES = 2L * 1024 * 1024

    fun append(tarvenHome: File, line: String) {
        runCatching {
            synchronized(lock) {
                val dir = File(tarvenHome, "logs").apply { mkdirs() }
                val file = File(dir, "diagnostic.log")
                if (file.length() > MAX_BYTES) {
                    val previous = File(dir, "diagnostic.log.1")
                    previous.delete()
                    file.renameTo(previous)
                }
                val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                file.appendText("$stamp $line\n")
            }
        }
    }
}
