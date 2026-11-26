package com.sillyclient.runtime

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Prepares and exposes the offline isomorphic-git runtime bridge for Node server processes. */
object BundledGitBridge {
    private const val REVISION = "git-bridge-v1-20261127"
    private val ASSET_FILES = listOf(
        "isomorphic-git.cjs",
        "isomorphic-git-http-node.cjs",
        "git-bridge-preload.cjs",
        "THIRD_PARTY_NOTICES.txt"
    )
    private val lock = Any()

    fun ensurePrepared(bootstrapDir: File, openAsset: (String) -> InputStream): File = synchronized(lock) {
        val bridgeDir = File(bootstrapDir, "git-bridge")
        val marker = File(bridgeDir, ".sillyclient-git-bridge-ready")
        val ready = marker.isFile &&
            marker.length() < 1024 &&
            runCatching { marker.readText().trim() == REVISION }.getOrDefault(false) &&
            ASSET_FILES.all { name -> File(bridgeDir, name).let { it.isFile && it.length() > 0 } }
        if (ready) return bridgeDir

        check(bootstrapDir.isDirectory || bootstrapDir.mkdirs()) { "Cannot prepare bootstrap directory" }
        val staging = Files.createTempDirectory(bootstrapDir.toPath(), ".git-bridge-staging-").toFile()
        try {
            for (name in ASSET_FILES) {
                val target = File(staging, name)
                openAsset("bootstrap/git-bridge/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                check(target.isFile && target.length() > 0) { "Bundled Git bridge asset missing: $name" }
            }
            bridgeDir.deleteRecursively()
            check(!bridgeDir.exists()) { "Cannot reset bundled Git bridge directory" }
            Files.move(staging.toPath(), bridgeDir.toPath())
            val temp = Files.createTempFile(bridgeDir.toPath(), ".ready-", ".tmp")
            try {
                Files.write(temp, REVISION.toByteArray(Charsets.UTF_8))
                Files.move(temp, marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
            bridgeDir
        } finally {
            staging.deleteRecursively()
        }
    }

    fun applyEnvironment(env: MutableMap<String, String>, bridgeDir: File) {
        val preloadFile = File(bridgeDir, "git-bridge-preload.cjs")
        if (!preloadFile.isFile) return
        env["SILLYCLIENT_GIT_BRIDGE_DIR"] = bridgeDir.absolutePath
        env["SILLYCLIENT_GIT_BACKEND"] = "bridge"
        val normalizedPreload = preloadFile.absolutePath.replace('\\', '/')
        val requireFlag = "--require=\"$normalizedPreload\""
        val currentOpts = env["NODE_OPTIONS"].orEmpty().trim()
        if (!currentOpts.contains(normalizedPreload)) {
            env["NODE_OPTIONS"] = if (currentOpts.isEmpty()) requireFlag else "$currentOpts $requireFlag"
        }
    }
}
