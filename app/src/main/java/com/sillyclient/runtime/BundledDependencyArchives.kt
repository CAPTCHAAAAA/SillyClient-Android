package com.sillyclient.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Materializes the dependency archives shipped inside the APK into the shared
 * dependency-archives directory so [DependencyArchive] restores hit locally and
 * instance creation never waits on a network npm install.
 */
class BundledDependencyArchives(
    private val archiveDir: File,
    private val openAsset: (String) -> InputStream,
    private val listAssets: (String) -> Set<String>
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "SillyClient-deps").apply { isDaemon = true } }
    private var pending: CompletableFuture<Unit>? = null

    @Synchronized
    fun prepareAsync(): CompletableFuture<Unit> {
        pending?.takeUnless { it.isCompletedExceptionally || it.isCancelled }?.let { return it }
        return CompletableFuture.supplyAsync({ publish(); Unit }, worker).also { pending = it }
    }

    fun awaitReady(ensureActive: () -> Unit) {
        val future = prepareAsync()
        while (true) {
            ensureActive()
            try { future.get(100, TimeUnit.MILLISECONDS); return }
            catch (_: TimeoutException) { /* Continue waiting while the caller remains active. */ }
            catch (error: ExecutionException) { throw IllegalStateException("Bundled dependency archives failed", error.cause) }
        }
    }

    private fun publish() = synchronized(archiveDir) {
        checkInterrupted()
        if (archiveDir.isDirectory || archiveDir.mkdirs()) {
            for (name in listAssets(BUNDLED_ASSET_DIR)) {
                checkInterrupted()
                // Assets carry a "dependency-" marker; the cache layer only ever
                // looks files up by their canonical "<lock>-<digest>.tar" name.
                val cacheName = DependencyArchive.bundledCacheName(File(name).name) ?: continue
                val target = File(archiveDir, cacheName)
                if (target.isFile && target.length() > 0) continue
                val temp = Files.createTempFile(archiveDir.toPath(), ".bundled-", ".tmp")
                try {
                    openAsset("$BUNDLED_ASSET_DIR/$name").use { input ->
                        FileOutputStream(temp.toFile()).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                checkInterrupted()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    check(temp.toFile().length() > 0) { "Bundled dependency archive '$name' is empty" }
                    Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
        }
    }

    @Synchronized
    override fun close() { pending?.cancel(true); worker.shutdownNow() }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Bundled archive publication cancelled")
    }

    companion object {
        internal const val BUNDLED_ASSET_DIR = "bundled"
    }
}
