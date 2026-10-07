package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Converts the bundled source ZIP into a prefix-stripped ustar archive, cached
 * in private storage, so instances extract their ~1100 source files through
 * the same parallel tar-group path used for dependencies (a few hundred
 * milliseconds of parallel children) instead of thousands of in-process
 * per-file writes. Directory entries are emitted before their files so the
 * platform tar never has to invent parents. Any failure returns null and the
 * caller keeps the in-process extractor.
 */
object SourceTarCache {
    fun tarFor(zip: File, cacheDir: File): File? = runCatching {
        val digest = sha256(zip) ?: return null
        val target = File(cacheDir, "source-$digest.tar")
        if (target.isFile && target.length() > 0) return target
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) return null
        val staging = File(cacheDir, "source-$digest.tmp")
        try {
            ZipFile(zip).use { archive ->
                val files = archive.entries().asSequence()
                    .filter { !it.isDirectory }
                    .mapNotNull { entry ->
                        val stripped = stripRoot(entry.name) ?: return@mapNotNull null
                        Triple(stripped, entry, entry.size)
                    }
                    .toList()
                if (files.isEmpty()) return null
                val directories = linkedSetOf<String>()
                for ((name, _, _) in files) {
                    var parent = name.substringBeforeLast('/', "")
                    while (parent.isNotEmpty()) {
                        directories.add(parent)
                        parent = parent.substringBeforeLast('/', "")
                    }
                }
                val entries = sequence {
                    for (dir in directories.sorted()) {
                        yield(UstarArchive.Entry(dir, true, false, "", 0b111_101_101, 0L, UstarArchive::emptyContent))
                    }
                    for ((name, entry, size) in files) {
                        val executable = name.endsWith(".sh")
                        yield(UstarArchive.Entry(name, false, false, "",
                            if (executable) 0b111_101_101 else 0b110_100_100, size) { archive.getInputStream(entry) })
                    }
                }
                UstarArchive.write(staging, entries)
            }
            Files.move(staging.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            target
        } finally {
            if (staging.exists()) staging.delete()
        }
    }.getOrNull()

    /** "SillyTavern-release/src/x" -> "src/x"; files at the zip root are dropped. */
    private fun stripRoot(name: String): String? {
        val slash = name.indexOf('/')
        if (slash < 0) return null
        val stripped = name.substring(slash + 1)
        return stripped.takeIf { it.isNotEmpty() }
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
