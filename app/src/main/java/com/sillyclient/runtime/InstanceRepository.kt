package com.sillyclient.runtime

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

class InstanceRepository(
    private val serversRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val installLocations: InstallLocationRegistry? = null,
    private val legacyServersRoot: File? = null,
    private val measureSize: (File) -> Long = {
        ManagedFiles.size(it, setOf("node_modules", ".git", ".cache", DependencyRestoreTransaction.STAGING_NAME))
    }
) {
    data class Metadata(
        val instanceId: String,
        val version: String,
        val path: String,
        val sizeBytes: Long,
        val hasServer: Boolean,
        val createdAt: String,
        val status: String
    )

    private data class SizeCache(val bytes: Long, val measuredAt: Long)
    private val sizes = ConcurrentHashMap<String, SizeCache>()

    fun scan(): List<Metadata> {
        val records = installLocations?.entries().orEmpty()
        val registeredPaths = (records.values + installLocations?.retainedSources().orEmpty()).map { it.canonicalPath }.toSet()
        val directories = linkedMapOf<String, File>()
        serversRoot.listFiles()?.filter { isDiscoverable(it) }
            ?.forEach { directory ->
                if (directory.canonicalPath !in registeredPaths && directory.name !in records) {
                    directories[directory.name] = directory
                }
            }
        legacyServersRoot?.listFiles()?.filter { isDiscoverable(it) }
            ?.forEach { directory ->
                if (directory.canonicalPath !in registeredPaths && directory.name !in records && directory.name !in directories) {
                    directories[directory.name] = directory
                }
            }
        for ((id, dir) in records) {
            if (dir.exists()) {
                directories[id] = dir
            }
        }
        return directories.map { (id, directory) -> metadata(id, directory, sizes[directory.absolutePath]?.bytes ?: 0L) }
    }

    fun info(directory: File): Metadata {
        val id = installLocations?.entries()?.entries?.firstOrNull {
            it.value.canonicalFile == directory.canonicalFile
        }?.key ?: directory.name
        if (!directory.exists()) return metadata(id, directory, 0)
        val cached = sizes[directory.absolutePath]
        val bytes = if (cached != null && clock() - cached.measuredAt < 30_000) {
            cached.bytes
        } else {
            measureSize(directory).also { sizes[directory.absolutePath] = SizeCache(it, clock()) }
        }
        return metadata(id, directory, bytes)
    }

    fun invalidate(directory: File) {
        sizes.remove(directory.absolutePath)
    }

    private fun isDiscoverable(directory: File): Boolean = directory.isDirectory &&
        ManagedFiles.isUnlinked(directory) && !directory.name.startsWith(".sillyclient-install-") &&
        !directory.name.startsWith(InstanceRelocation.STAGING_PREFIX) &&
        // Deletion-committed directories wait for their background purge; they
        // must never resurface as instance cards in the meantime. Renamed
        // remnants from older builds are hidden the same way. The shared
        // dependency bank lives inside the instances root and is never an
        // instance (a real instance always carries server.js).
        !InstanceRemoval.RENAME_PATTERN.matches(directory.name) &&
        !File(directory, InstanceRemoval.REMOVAL_MARKER).isFile &&
        !(directory.name == "node_modules" && !File(directory, "server.js").isFile)

    private fun metadata(instanceId: String, directory: File, size: Long): Metadata {
        val installed = directory.exists()
        val source = File(directory, "server.js")
        val hasServer = source.isFile && ManagedFiles.isWithin(source, directory)
        val version = runCatching { readVersion(directory) }.getOrDefault("unknown")
        return Metadata(
            instanceId, version, directory.absolutePath, size, hasServer,
            if (installed) SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(directory.lastModified())) else "",
            if (!installed) "未安装" else if (hasServer) "已就绪" else "未完成"
        )
    }

    private fun readVersion(directory: File): String {
        val file = File(directory, "package.json")
        require(file.isFile && ManagedFiles.isWithin(file, directory) && file.length() in 1..1024L * 1024) {
            "Package metadata is not a bounded regular file"
        }
        val output = ByteArrayOutputStream()
        Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(8192)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                require(output.size().toLong() + count <= 1024L * 1024) { "Package metadata exceeds its size limit" }
                output.write(buffer, 0, count)
            }
        }
        return JSONObject(output.toString(Charsets.UTF_8.name())).optString("version", "unknown")
    }
}
