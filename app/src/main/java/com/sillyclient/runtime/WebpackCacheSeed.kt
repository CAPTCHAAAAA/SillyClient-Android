package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Webpack compile-cache seeding.
 *
 * SillyTavern compiles its frontend libraries on every start through a
 * filesystem cache stored at `<instance>/data/_webpack/<version>/cache`. A
 * fresh instance therefore pays a ~25 s cold compile; once one instance has
 * compiled, its cache is harvested here and seeded into every later instance,
 * turning that first start into a warm-cache run. The cache is a handful of
 * pack files, so the copy stays cheap; oversized or sprawling caches are
 * skipped rather than copied, and the compile simply runs cold as before.
 */
class WebpackCacheSeed(private val paths: RuntimePaths) {
    fun seedDirectory(bundleKey: String): File = File(paths.tarvenHome, "webpack-cache/$bundleKey")

    /** Copy a finished instance's compile cache into the seed store (background, best effort). */
    fun harvestInBackground(instanceDirectory: File, bundleKey: String) {
        Thread {
            runCatching {
                val source = File(File(instanceDirectory, "data"), "_webpack")
                val seed = seedDirectory(bundleKey)
                if (!source.isDirectory || seed.exists()) return@runCatching
                val (files, bytes) = measure(source) ?: return@runCatching
                if (files == 0L || files > MAX_FILES || bytes > MAX_BYTES) return@runCatching
                val staging = File(seed.parentFile, "${seed.name}.tmp")
                staging.deleteRecursively()
                if (!copyTree(source, staging)) { staging.deleteRecursively(); return@runCatching }
                Files.move(staging.toPath(), seed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        }.apply {
            name = "SC-webpack-seed"
            isDaemon = true
        }.start()
    }

    /**
     * Seed a fresh instance's data directory before its first start. Returns
     * true when a cache was copied (the first compile then runs warm).
     */
    fun seedInstance(instanceDirectory: File, bundleKey: String, ensureActive: () -> Unit = {}): Boolean {
        val seed = seedDirectory(bundleKey)
        if (!seed.isDirectory) return false
        val target = File(File(instanceDirectory, "data"), "_webpack")
        if (target.isDirectory && target.list()?.isNotEmpty() == true) return false
        val (files, bytes) = measure(seed) ?: return false
        if (files == 0L || files > MAX_FILES || bytes > MAX_BYTES) return false
        return copyTree(seed, target, ensureActive)
    }

    private fun measure(directory: File): Pair<Long, Long>? = runCatching {
        var files = 0L
        var bytes = 0L
        directory.walkTopDown().forEach { entry ->
            if (entry.isFile) {
                files++
                bytes += entry.length()
                require(files <= MAX_FILES && bytes <= MAX_BYTES) { "Webpack cache seed exceeds limits" }
            }
        }
        files to bytes
    }.getOrNull()

    private fun copyTree(source: File, target: File, ensureActive: () -> Unit = {}): Boolean = runCatching {
        val sourcePath = source.toPath()
        source.walkTopDown().forEach { entry ->
            ensureActive()
            val relative = sourcePath.relativize(entry.toPath()).toString()
            val destination = File(target, relative)
            when {
                entry.isDirectory -> destination.mkdirs()
                entry.isFile -> {
                    destination.parentFile?.mkdirs()
                    Files.copy(entry.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        true
    }.getOrDefault(false)

    companion object {
        private const val MAX_FILES = 4000L
        private const val MAX_BYTES = 512L * 1024 * 1024
    }
}
