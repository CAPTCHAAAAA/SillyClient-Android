package com.sillyclient.runtime

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

object RuntimeFileUtils {

    fun unzipStream(input: InputStream, targetDir: File, ensureActive: () -> Unit = {}) {
        targetDir.mkdirs()
        val canonicalRoot = targetDir.canonicalFile.toPath()
        ZipInputStream(input).use { zip ->
            while (true) {
                ensureActive()
                val entry = zip.nextEntry ?: break
                // Archives built on Windows may carry backslash separators; treat
                // them as path separators so entries nest instead of turning into
                // flat files whose names contain literal backslashes.
                val entryName = entry.name.replace('\\', '/')
                val outFile = File(targetDir, entryName).canonicalFile

                if (outFile.toPath() == canonicalRoot) {
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    throw IllegalStateException("Zip file entry replaces target dir: $entryName")
                }
                if (!outFile.toPath().startsWith(canonicalRoot)) {
                    throw IllegalStateException("Zip entry escapes target dir: $entryName")
                }

                if (entry.isDirectory) {
                    if (outFile.exists() && !outFile.isDirectory) outFile.delete()
                    outFile.mkdirs()
                } else {
                    // Streaming zips can carry directory entries whose local file
                    // header lost the trailing slash while the central directory
                    // kept it; ZipInputStream then reports them as files and the
                    // first nested child fails with ENOTDIR. The directory wins:
                    // a file occupying a directory slot is removed on the way up.
                    var ancestor = outFile.parentFile
                    while (ancestor != null) {
                        if (ancestor.exists()) {
                            if (ancestor.isDirectory) break
                            ancestor.delete()
                        }
                        ancestor = ancestor.parentFile
                    }
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(65_536)
                        var count: Int
                        while (zip.read(buffer).also { count = it } >= 0) {
                            ensureActive()
                            output.write(buffer, 0, count)
                        }
                    }
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
