package com.sillyclient.runtime

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One shared runtime per app; eager preparation does not start any user's server. */
class BundledRuntime(
    private val paths: RuntimePaths,
    private val revision: String,
    private val diagnostic: (String) -> Unit = {},
    private val openAsset: (String) -> InputStream
) : AutoCloseable {
    private var worker: ExecutorService = newWorker()
    private val directoryLock = locks.computeIfAbsent(paths.usrDir.canonicalPath) { Any() }
    private var pending: CompletableFuture<Unit>? = null

    private fun newWorker(): ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "SillyClient-runtime").apply { isDaemon = true } }

    @Synchronized
    fun prepareAsync(): CompletableFuture<Unit> {
        // The runtime is app-scoped and outlives any single Activity; a worker
        // closed by a lifecycle owner revives here instead of poisoning every
        // later launch in the same process.
        if (worker.isShutdown) worker = newWorker()
        pending?.takeUnless { it.isCompletedExceptionally || it.isCancelled }?.let { return it }
        return CompletableFuture.supplyAsync({
            try { prepare(); Unit }
            catch (error: Throwable) {
                diagnostic("prepare.failed ${error.javaClass.simpleName}: ${error.message?.take(160)}")
                throw error
            }
        }, worker).also { pending = it }
    }

    fun awaitReady(ensureActive: () -> Unit) {
        val future = prepareAsync()
        while (true) {
            ensureActive()
            try { future.get(100, TimeUnit.MILLISECONDS); return }
            catch (_: TimeoutException) { /* The shared preparation continues when one instance is cancelled. */ }
            catch (error: ExecutionException) {
                val cause = error.cause
                throw IllegalStateException(
                    "Bundled runtime preparation failed: ${cause?.javaClass?.simpleName}: ${cause?.message?.take(160)}",
                    cause
                )
            }
        }
    }

    private fun prepare() = synchronized(directoryLock) {
        checkInterrupted()
        check(paths.nodeBin.isFile) { "Bundled Node.js executable is missing" }
        check(paths.usrDir.isDirectory || paths.usrDir.mkdirs()) { "Cannot prepare the shared runtime directory" }
        val marker = File(paths.usrDir, ".sillyclient-runtime-ready")
        val npm = File(paths.usrDir, "lib/node_modules/npm/bin/npm-cli.js")
        if (marker.isFile && marker.length() < 1024 && marker.readText() == revision &&
            npm.isFile && paths.usrLibDir.isDirectory) return@synchronized
        // A failed extraction must not leave a previous success stamp in place.
        check(!marker.exists() || marker.delete()) { "Cannot reset the shared runtime preparation state" }
        // The usr tree is a pure function of the bundled assets and republishes
        // atomically: extraction builds a fresh staging tree on the same volume
        // that is swapped in only after it verifies complete, so a partial wipe
        // or a leftover entry can never surface as a half-written runtime.
        val usrParent = paths.usrDir.parentFile ?: paths.tarvenHome
        val staging = Files.createTempDirectory(usrParent.toPath(), ".usr-staging-").toFile()
        try {
            for (asset in listOf("bootstrap/rootfs/rootfs-libs.zip", "bootstrap/rootfs/rootfs-usr.zip")) {
                openAsset(asset).use { RuntimeFileUtils.unzipStream(it, staging) {
                    checkInterrupted()
                } }
            }
            check(File(staging, "lib/node_modules/npm/bin/npm-cli.js").isFile &&
                File(staging, "lib").isDirectory) { "The bundled runtime archive is incomplete" }
            // PATH-based node lookups (npm lifecycle scripts) resolve through this
            // alias; an APK upgrade relocating the native libraries is healed here
            // with the re-extraction. The npm call site re-checks it strictly, so
            // hosts without symlink privileges only lose this eager pre-heal.
            try { BundledNodeAlias.ensure(paths.tarvenHome, paths.nodeBin) }
            catch (error: Exception) { diagnostic("node.alias.unavailable ${error.message?.take(120)}") }
            checkInterrupted()
            paths.usrDir.deleteRecursively()
            check(!paths.usrDir.exists()) { "Cannot reset the shared runtime directory" }
            Files.move(staging.toPath(), paths.usrDir.toPath())
            val temp = Files.createTempFile(paths.usrDir.toPath(), ".runtime-", ".tmp")
            try {
                Files.write(temp, revision.toByteArray(Charsets.UTF_8))
                Files.move(temp, marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        } finally {
            staging.deleteRecursively()
        }
    }

    @Synchronized
    override fun close() { pending?.cancel(true); worker.shutdownNow() }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Runtime preparation cancelled")
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}
