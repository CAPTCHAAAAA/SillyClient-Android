package com.sillyclient.runtime

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

object RuntimeFileUtils {

    fun unzipStream(input: InputStream, targetDir: File) {
        targetDir.mkdirs()
        val canonicalRoot = targetDir.canonicalFile.toPath()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val outFile = File(targetDir, entry.name).canonicalFile

                if (!outFile.toPath().startsWith(canonicalRoot)) {
                    throw IllegalStateException("Zip entry escapes target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }

    fun chmodExecutable(file: File) {
        if (file.exists()) {
            file.setReadable(true, true)
            file.setExecutable(true, true)
        }
    }
}
