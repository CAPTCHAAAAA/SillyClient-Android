package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files

/**
 * Content-addressed dependency roots in private f2fs storage.
 *
 * Every lock gets a full install root under [home]: package manifests plus a
 * materialized node_modules. Instances reference a root through NODE_PATH, so
 * staging never copies tens of thousands of files onto FUSE external storage
 * and the runtime reads modules from fast private storage. A node_modules that
 * already exists inside the instance directory still wins because Node resolves
 * the local tree first.
 */
class DependencyTrees(private val home: File) {

    fun rootFor(lockKey: String): File {
        require(lockKey.matches(KEY_PATTERN)) { "Invalid dependency tree key" }
        return File(home, lockKey)
    }

    fun modulesFor(lockKey: String): File = File(rootFor(lockKey), "node_modules")

    /** A lock key addresses the exact tree, so a complete root needs no further matching. */
    fun containsComplete(lockKey: String): Boolean {
        val root = rootFor(lockKey)
        return File(root, "node_modules").isDirectory && DependencyInstaller.hasRequiredPackages(root)
    }

    fun adopt(lockKey: String, manifest: File, ensureActive: () -> Unit): File {
        val root = rootFor(lockKey)
        // Installation and background promotion can target the same lock
        // concurrently; both mutate the root, so adoption is serialized.
        synchronized(adoptionLock) {
            if (containsComplete(lockKey)) return root
            val source = manifest.parentFile ?: error("Manifest has no parent directory")
            root.deleteRecursively()
            check(root.isDirectory || root.mkdirs()) { "Could not create the dependency root" }
            for (name in listOf("package.json", "package-lock.json")) {
                ensureActive()
                val from = File(source, name)
                if (from.isFile) {
                    val to = File(root, name)
                    Files.copy(from.toPath(), to.toPath())
                }
            }
        }
        return root
    }

    /**
     * Serializes tree construction per lock: adoption only guards manifest
     * setup, so two concurrent restores into the same root would interleave
     * extraction passes and corrupt the tree. The install path and the
     * background promotion both build under this lock.
     */
    fun <T> buildExclusively(lockKey: String, block: () -> T): T =
        synchronized(buildingLocks.computeIfAbsent(lockKey) { Any() }) { block() }

    companion object {
        private val KEY_PATTERN = Regex("[0-9a-f]{64}")
        private val adoptionLock = Any()
        private val buildingLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}
