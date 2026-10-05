package com.sillyclient.runtime

import java.io.File
import java.net.URI
import java.util.UUID

/** Cleanup never infers that an instance directory is disposable from frontend state. */
class CleanupService(
    private val serversRoot: File,
    private val coversRoot: File,
    private val tempRoots: List<File>,
    private val logsRoot: File,
    private val isRuntimeBusy: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minimumAgeMillis: Long = 24 * 60 * 60 * 1000L,
    private val installLocations: InstallLocationRegistry? = null
) {
    data class Item(val path: String, val type: String, val sizeBytes: Long, val description: String, val token: String)
    private data class Planned(
        val item: Item, val root: File, val modified: Long, val expiresAt: Long,
        val protectedIds: Set<String>, val activeCovers: Set<String>?
    )

    private val plans = mutableMapOf<String, Planned>()
    private var authoritativeCovers: Set<String>? = null

    @Synchronized
    fun scan(activeInstanceIds: List<String>? = null, activeCoverPaths: List<String>? = null): List<Item> {
        plans.clear()
        authoritativeCovers = activeCoverPaths?.let { paths ->
            runCatching { paths.map(::nativeCoverPath).toSet() }.getOrNull()
        }
        val protectedIds = activeInstanceIds.orEmpty().toSet() + nativeCoverIds()
        val result = mutableListOf<Item>()
        fun add(file: File, root: File, type: String) {
            if (!eligible(file, root, type, protectedIds, authoritativeCovers)) return
            val item = Item(file.absolutePath, type, file.length(), file.name, UUID.randomUUID().toString())
            plans[item.token] = Planned(item, root, file.lastModified(), clock() + 5 * 60_000, protectedIds, authoritativeCovers)
            result.add(item)
        }
        coversRoot.listFiles()?.forEach { add(it, coversRoot, "orphan_cover") }
        if (!isRuntimeBusy()) {
            tempRoots.forEach { root ->
                if (ManagedFiles.isUnlinked(root)) root.walkTopDown()
                    .onEnter { ManagedFiles.isUnlinked(it) && it.name != "bin" }
                    .filter { it.isFile }
                    .forEach { add(it, root, "temp_file") }
            }
            logsRoot.listFiles()?.forEach { add(it, logsRoot, "temp_file") }
        }
        return result
    }

    @Synchronized
    fun invalidate() {
        plans.clear()
    }

    @Synchronized
    fun delete(path: String, token: String?): Long {
        val plan = token?.let(plans::remove) ?: throw IllegalArgumentException("A current cleanup token is required")
        require(path == plan.item.path && clock() <= plan.expiresAt) { "Cleanup plan has expired or changed" }
        val file = File(path)
        require(file.length() == plan.item.sizeBytes && file.lastModified() == plan.modified) { "Cleanup item changed" }
        require(
            plan.activeCovers == authoritativeCovers &&
                eligible(file, plan.root, plan.item.type, plan.protectedIds + nativeCoverIds(), authoritativeCovers)
        ) { "Cleanup item is no longer eligible" }
        check(file.delete() && !file.exists()) { "Cleanup item could not be deleted" }
        return plan.item.sizeBytes
    }

    private fun eligible(file: File, root: File, type: String, protectedIds: Set<String>, activeCovers: Set<String>?): Boolean {
        if (!file.isFile || !ManagedFiles.isWithin(file, root) || file.length() <= 0) return false
        if (clock() - file.lastModified() < minimumAgeMillis) return false
        if (type == "orphan_cover") {
            if (activeCovers == null || file.absolutePath in activeCovers) return false
            val id = file.name.substringBeforeLast('.')
            if (id in protectedIds || File(serversRoot, id).exists()) return false
            return file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")
        }
        return !isRuntimeBusy() && file.name != "xdg-open"
    }

    private fun nativeCoverPath(path: String): String {
        val decoded = when {
            path.startsWith("file:") -> File(URI(path)).path
            path.contains("/_capacitor_file_/") -> URI(path).path.substringAfter("/_capacitor_file_")
            else -> path
        }
        val file = File(decoded)
        require(file.isAbsolute) { "Cover references must be native paths" }
        return file.absoluteFile.toPath().normalize().toString()
    }

    private fun nativeCoverIds(): Set<String> {
        val ids = serversRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty() +
            installLocations?.entries()?.keys.orEmpty()
        return ids.map { it.replace(Regex("[^a-zA-Z0-9._-]"), "_") }.toSet()
    }
}
