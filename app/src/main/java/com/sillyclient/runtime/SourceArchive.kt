package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Validate ZIP metadata without decompressing, then stream each regular file exactly once. */
object SourceArchive {
    internal data class Limits(
        val archiveBytes: Long = 2L * 1024 * 1024 * 1024,
        val expandedBytes: Long = 8L * 1024 * 1024 * 1024,
        val fileBytes: Long = 512L * 1024 * 1024,
        val entries: Int = 150_000
    )

    internal data class Item(val entry: ZipEntry, val path: String)

    fun extract(
        archive: File,
        destination: File,
        ensureActive: () -> Unit,
        onProgress: (Int) -> Unit,
        skipTopLevel: Set<String> = emptySet()
    ): Int = extractBounded(archive, destination, ensureActive, onProgress, Limits(), skipTopLevel)

    internal fun extractBounded(
        archive: File,
        destination: File,
        ensureActive: () -> Unit,
        onProgress: (Int) -> Unit,
        limits: Limits,
        skipTopLevel: Set<String> = emptySet()
    ): Int {
        ensureActive()
        require(archive.isFile && ManagedFiles.isUnlinked(archive) && archive.length() in 22..limits.archiveBytes) {
            "Source archive is missing, linked, or exceeds its size limit"
        }
        require(!exists(destination) || destination.isDirectory) { "Archive destination is not a directory" }
        require(ManagedFiles.isUnlinked(destination)) { "Archive destination cannot be a symbolic link" }
        ZipFile(archive).use { zip ->
            require(zip.size() in 1..limits.entries) { "Source archive has an invalid number of entries" }
            val entries = ArrayList<Item>(zip.size())
            val names = HashSet<String>()
            var declaredBytes = 0L
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                ensureActive()
                val entry = enumeration.nextElement()
                val path = validatedPath(entry)
                require(names.add(path.lowercase(Locale.ROOT))) { "Source archive contains duplicate paths" }
                require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) {
                    "Source archive uses an unsupported compression method"
                }
                require(entry.size in 0..limits.fileBytes && entry.crc in 0..0xffffffffL) {
                    "Source archive entry has an invalid size or checksum"
                }
                require(!entry.isDirectory || entry.size == 0L) { "Source archive directory contains file data" }
                require(entry.size <= limits.expandedBytes - declaredBytes) { "Source archive exceeds the expanded size limit" }
                declaredBytes += entry.size
                entries.add(Item(entry, path))
            }
            val prefix = wrapperPrefix(entries)
            val outputs = entries.mapNotNull { item ->
                if (prefix.isNotEmpty() && item.entry.isDirectory && item.path == prefix.removeSuffix("/")) {
                    return@mapNotNull null
                }
                val relative = item.path.removePrefix(prefix)
                if (relative.isEmpty() && item.entry.isDirectory) null
                else Item(item.entry, relative)
            }.filterNot { item ->
                // Callers may explicitly exclude top-level sections of a backup.
                skipTopLevel.any { skip -> item.path == skip || item.path.startsWith("$skip/") }
            }
            validateOutputs(outputs, destination, ensureActive)
            check(destination.isDirectory || destination.mkdirs()) { "Could not create archive destination" }
            val buffer = ByteArray(65_536)
            var extracted = 0
            var writtenTotal = 0L
            for ((entry, path) in outputs) {
                ensureActive()
                if (isHostMetadata(path)) continue
                val output = File(destination, path)
                require(ManagedFiles.isWithin(output, destination)) { "Archive destination changed; existing files were preserved" }
                if (entry.isDirectory) {
                    check(output.isDirectory || output.mkdirs()) { "Could not create archive directory" }
                    continue
                }
                val parent = requireNotNull(output.parentFile)
                check(parent.isDirectory || parent.mkdirs()) { "Could not create archive file parent" }
                val crc = CRC32()
                var written = 0L
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { sink ->
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(count.toLong() <= entry.size - written &&
                                count.toLong() <= limits.expandedBytes - writtenTotal) {
                                "Source archive expanded beyond its declared size"
                            }
                            written += count
                            writtenTotal += count
                            crc.update(buffer, 0, count)
                            sink.write(buffer, 0, count)
                        }
                    }
                }
                require(written == entry.size && crc.value == entry.crc) { "Source archive file checksum or size is invalid" }
                ensureActive()
                onProgress(++extracted)
            }
            ensureActive()
            return extracted
        }
    }

    internal fun validatedPath(entry: ZipEntry): String {
        val path = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
        require(path.isNotEmpty() && path.length <= 4096 && !path.startsWith('/') &&
            path.none { it == '\\' || it == ':' || it.code < 32 || it.code == 127 } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Source archive contains an unsafe path"
        }
        return path
    }

    internal fun wrapperPrefix(entries: List<Item>): String {
        val root = entries.first().path.substringBefore('/')
        if (root in setOf("data", "public", "src", "node_modules", "plugins", "default-user")) return ""
        val prefix = "$root/"
        if (entries.any { it.path != root && !it.path.startsWith(prefix) } ||
            entries.any { it.path == root && !it.entry.isDirectory }) return ""
        val hasInstanceRoot = entries.any {
            val path = it.path.removePrefix(prefix)
            path in setOf("server.js", "package.json", "config.yaml") || path.startsWith("data/") ||
                (path == "data" && it.entry.isDirectory)
        }
        return if (hasInstanceRoot) prefix else ""
    }

    private fun validateOutputs(entries: List<Item>, destination: File, ensureActive: () -> Unit) {
        val filePaths = entries.filterNot { it.entry.isDirectory }
            .mapTo(HashSet()) { it.path.lowercase(Locale.ROOT) }
        for ((entry, path) in entries) {
            ensureActive()
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent.lowercase(Locale.ROOT) !in filePaths) { "Source archive file conflicts with a parent directory" }
                parent = parent.substringBeforeLast('/', "")
            }
            if (isHostMetadata(path)) continue
            val output = File(destination, path)
            require(ManagedFiles.isWithin(output, destination)) { "Source archive path escapes its destination" }
            require(!exists(output) || (entry.isDirectory && output.isDirectory)) {
                "Source archive would replace existing files; existing files were preserved"
            }
        }
    }

    internal fun isHostMetadata(path: String): Boolean =
        path == ".sc-identity" || path == InstanceInstaller.DEPENDENCY_MARKER ||
            (!path.contains('/') && path.startsWith(InstanceInstaller.STAGING_PREFIX))

    private fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}
