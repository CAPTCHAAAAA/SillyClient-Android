package com.sillyclient.runtime

import java.io.File

/**
 * Removes abandoned staging directories left behind by cancelled or crashed
 * installation and relocation transactions. A staging directory that received
 * writes recently is always left alone: only transactions that went quiet for
 * longer than the grace period can be considered abandoned, and concurrent
 * operations keep their staging fresh through continued writes.
 */
object StagingCleanup {

    fun sweepOrphans(parent: File, vararg prefixes: String, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS) {
        val directory = parent.takeIf { it.isDirectory } ?: return
        val now = System.currentTimeMillis()
        val orphans = directory.listFiles { file ->
            file.isDirectory && prefixes.any { file.name.startsWith(it) }
        }?.filter { now - it.lastModified() > maxAgeMillis }.orEmpty()
        if (orphans.isEmpty()) return
        // Removal stays off the operation path: deleting tens of thousands of
        // orphaned dependency files through FUSE takes minutes and must never
        // stall a foreground install or relocation that triggered the sweep.
        Thread {
            for (staging in orphans) {
                runCatching { ManagedFiles.deleteDirectory(staging, directory) }
            }
        }.apply {
            name = "SC-staging-sweep"
            isDaemon = true
        }.start()
    }

    private const val DEFAULT_MAX_AGE_MILLIS = 3_600_000L
}
