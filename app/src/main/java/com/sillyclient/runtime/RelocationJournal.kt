package com.sillyclient.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** One private, durable intent bridges the directory rename and registry publication. */
internal class RelocationJournal(private val file: File, private val privateRoot: File) {
    data class Entry(val instanceId: String, val source: File, val target: File, val fileKey: String,
        val createdAt: Long, val retainSource: Boolean, val retainedPaths: List<File>, val stagingOwner: String? = null)

    fun read(): Entry? {
        requireSafePath()
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        require(file.isFile && file.length() in 1..MAX_BYTES) { "Invalid relocation journal size" }
        val bytes = ByteArrayOutputStream()
        Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(4096)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                require(bytes.size() + count <= MAX_BYTES) { "Relocation journal exceeds its size limit" }
                bytes.write(buffer, 0, count)
            }
        }
        val document = JSONObject(bytes.toString(Charsets.UTF_8.name()))
        require(document.opt("revision") == 1 && document.opt("owner") == "sillyclient-relocation") {
            "Invalid relocation journal ownership or revision"
        }
        val id = document.getString("instanceId")
        require(id == InstallLocationRegistry.normalizeInstanceId(id)) { "Invalid relocation journal instance identity" }
        val key = document.getString("fileKey")
        require(key.startsWith("marker:") && UUID.fromString(key.removePrefix("marker:")).toString() == key.removePrefix("marker:")) {
            "Invalid relocation journal directory identity"
        }
        val createdAt = document.get("createdAt")
        require((createdAt is Long || createdAt is Int) && (createdAt as Number).toLong() >= 0) {
            "Invalid relocation journal creation time"
        }
        val retainSource = document.get("retainSource")
        require(retainSource is Boolean) { "Invalid relocation journal retention policy" }
        val retained = document.getJSONArray("retainedPaths")
        require(retained.length() <= 32) { "Too many retained relocation paths" }
        val paths = (0 until retained.length()).map { path(retained.getString(it)) }
        require(paths.distinct().size == paths.size) { "Duplicate retained relocation paths" }
        val owner = if (!document.has("stagingOwner") || document.isNull("stagingOwner")) null else document.getString("stagingOwner")
        require(owner == null || (retainSource && UUID.fromString(owner).toString() == owner)) { "Invalid relocation staging owner" }
        return Entry(id, path(document.getString("source")), path(document.getString("target")), key,
            (createdAt as Number).toLong(), retainSource, paths, owner)
    }

    fun write(entry: Entry) {
        requireSafePath()
        require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "An earlier relocation requires recovery" }
        val bytes = JSONObject().put("revision", 1).put("owner", "sillyclient-relocation")
            .put("instanceId", entry.instanceId).put("source", entry.source.path).put("target", entry.target.path)
            .put("fileKey", entry.fileKey).put("createdAt", entry.createdAt).put("retainSource", entry.retainSource)
            .put("stagingOwner", entry.stagingOwner ?: JSONObject.NULL)
            .put("retainedPaths", JSONArray(entry.retainedPaths.map { it.path })).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Relocation journal exceeds its size limit" }
        val temporary = File(file.parentFile, ".relocation-${UUID.randomUUID()}.tmp")
        try {
            require(temporary.createNewFile()) { "Could not stage relocation journal" }
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    fun clear() {
        requireSafePath()
        Files.deleteIfExists(file.toPath())
    }

    private fun requireSafePath() {
        require(file.isAbsolute && ManagedFiles.isWithin(file, privateRoot)) { "Unsafe relocation journal path" }
    }

    private fun path(value: String): File {
        val file = File(value)
        require(file.isAbsolute && value == file.toPath().normalize().toString() && value.none { it.code < 32 }) {
            "Invalid relocation journal path"
        }
        return file
    }

    companion object { private const val MAX_BYTES = 64 * 1024 }
}
