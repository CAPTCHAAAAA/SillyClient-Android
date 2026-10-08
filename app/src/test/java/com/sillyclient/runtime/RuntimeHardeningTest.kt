package com.sillyclient.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RuntimeHardeningTest {
    private val now = 2_000_000L

    @Test
    fun maintenanceCannotCancelAnOperationReservedBeforeItsExecutorStarts() {
        OperationCoordinator().use { operations ->
            val first = operations.beginIfIdle("first") { true }
            assertTrue(operations.hasPendingWork())
            assertThrows(IllegalStateException::class.java) {
                operations.beginIfIdle("second") { true }
            }
            assertSame(first, operations.current())
            assertTrue(operations.isCurrent(first))
            operations.finish(first)
            assertFalse(operations.hasPendingWork())
            assertEquals("second", operations.beginIfIdle("second") { true }.instanceId)
        }
    }

    @Test
    fun unavailableRuntimeCannotBeReplacedByMaintenance() {
        OperationCoordinator().use { operations ->
            assertThrows(IllegalStateException::class.java) {
                operations.beginIfIdle("local") { false }
            }
            assertEquals(null, operations.current())
        }
    }

    private fun withRoot(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        val root = if (parent != null) {
            parent.mkdirs()
            Files.createTempDirectory(parent.toPath(), "sillyclient-hardening-").toFile()
        } else Files.createTempDirectory("sillyclient-hardening").toFile()
        try { test(root) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    private fun oldFile(root: File, path: String, text: String = "keep"): File =
        File(root, path).apply { parentFile?.mkdirs(); writeText(text); setLastModified(1) }

    private fun cleanup(root: File, busy: () -> Boolean = { false }): CleanupService = CleanupService(
        File(root, "servers"), File(root, "covers"), listOf(File(root, "tmp")), File(root, "logs"),
        busy, { now }, minimumAgeMillis = 1_000
    )

    private fun runtimePaths(root: File): RuntimePaths {
        val home = File(root, "tarven")
        val bootstrap = File(home, "bootstrap")
        val usr = File(home, "usr")
        val native = File(root, "native")
        return RuntimePaths(root, home, bootstrap, File(bootstrap, "servers"), usr, File(usr, "lib"),
            File(home, "tmp"), File(home, "logs"), native, File(native, "node"))
    }

    @Test
    fun absentLaunchPathsSelectTheManagedInstanceWithoutCreatingDirectories() = withRoot { root ->
        val paths = runtimePaths(root)
        val target = paths.serverDirFor("local", create = false)
        for (requested in listOf(null, "", "  ")) assertEquals(target, paths.launchDirectoryFor("local", requested))
        assertFalse(target.exists())
        assertFalse(paths.tarvenHome.exists())
    }

    @Test
    fun acceptsScannedManagedAndEquivalentCanonicalLaunchPaths() = withRoot { root ->
        val paths = runtimePaths(root)
        val target = paths.serverDirFor("local")
        File(target, "server.js").writeText("existing source")
        for (requested in listOf(target.absolutePath, target.canonicalPath, File(target, ".").absolutePath,
            "\"${target.absolutePath}\"", " '${target.absolutePath}' ")) {
            assertEquals(target, paths.launchDirectoryFor("local", requested))
        }
        assertEquals("existing source", File(target, "server.js").readText())
    }

    @Test
    fun rejectsForeignOrMismatchedLaunchPathsBeforeChangingAnActiveOperation() = withRoot { root ->
        val paths = runtimePaths(root)
        val target = paths.serverDirFor("new", create = false)
        val outside = File(root, "external")
        val otherInstance = paths.serverDirFor("other", create = false)
        otherInstance.mkdirs()
        File(otherInstance, "server.js").writeText("other instance source")
        File(otherInstance, "package.json").writeText("{\"version\":\"1.19.0\"}")
        File(otherInstance, "node_modules").mkdirs()
        val otherData = File(otherInstance, "data/chat.jsonl").apply {
            parentFile!!.mkdirs()
            writeText("other instance history")
        }
        paths.installLocations.registerCommitted("other", otherInstance)
        OperationCoordinator().use { operations ->
            val previous = operations.begin("currently-running", "existing-operation")
            for (requested in listOf(outside.absolutePath, otherInstance.absolutePath,
                "D:\\OldWindowsTavern", "relative-instance", "content://provider/tree/tavern")) {
                assertThrows(IllegalArgumentException::class.java) {
                    val validated = paths.launchDirectoryFor("new", requested)
                    operations.begin(validated.name)
                }
                assertSame(previous, operations.current())
                assertTrue(operations.isCurrent(previous))
                assertFalse(target.exists())
                assertFalse(outside.exists())
                assertEquals("other instance source", File(otherInstance, "server.js").readText())
                assertEquals("other instance history", otherData.readText())
                assertEquals(mapOf("other" to otherInstance), paths.installLocations.entries())
            }
        }
    }

    @Test
    fun cleanupNeverDeletesValidOrUnreferencedInstanceData() = withRoot { root ->
        val server = oldFile(root, "servers/local-1/server.js")
        val data = oldFile(root, "servers/local-1/data/chat.jsonl")
        val partial = oldFile(root, "servers/partial/data/chat.jsonl")
        val cover = oldFile(root, "covers/local-1.png")
        val items = cleanup(root).scan(emptyList(), emptyList())
        assertTrue(items.isEmpty())
        assertTrue(server.exists() && data.exists() && partial.exists() && cover.exists())
    }

    @Test
    fun cleanupPreservesCoversWithoutAnAuthoritativeSnapshot() = withRoot { root ->
        oldFile(root, "covers/remote.png")
        assertTrue(cleanup(root).scan().isEmpty())
        assertTrue(cleanup(root).scan(activeCoverPaths = listOf("not-a-native-path")).isEmpty())
    }

    @Test
    fun cleanupPreservesActiveRemoteCovers() = withRoot { root ->
        val cover = oldFile(root, "covers/remote.png")
        assertTrue(cleanup(root).scan(activeCoverPaths = listOf(cover.absolutePath)).isEmpty())
        assertTrue(cleanup(root).scan(activeInstanceIds = listOf("remote"), activeCoverPaths = emptyList()).isEmpty())
    }

    @Test
    fun cleanupProtectsNativeCoversWhenIdsNeedFilenameEscaping() = withRoot { root ->
        oldFile(root, "servers/实例/server.js")
        oldFile(root, "covers/__.png")
        assertTrue(cleanup(root).scan(activeCoverPaths = emptyList()).isEmpty())
    }

    @Test
    fun cleanupRequiresMatchingOneUseTokenAndCanonicalScope() = withRoot { root ->
        val file = oldFile(root, "tmp/download.zip")
        val service = cleanup(root)
        val item = service.scan().single()
        assertThrows(IllegalArgumentException::class.java) { service.delete(file.absolutePath, null) }
        assertThrows(IllegalArgumentException::class.java) { service.delete(File(root, "outside.zip").absolutePath, item.token) }
        assertTrue(file.exists())
        val fresh = service.scan().single()
        assertEquals(file.length(), service.delete(file.absolutePath, fresh.token))
        assertThrows(IllegalArgumentException::class.java) { service.delete(file.absolutePath, fresh.token) }
    }

    @Test
    fun managedPathsRejectSiblingPrefixesAndTraversal() = withRoot { root ->
        val scope = File(root, "servers").apply { mkdirs() }
        val outside = oldFile(root, "servers-other/data.json")
        assertFalse(ManagedFiles.isWithin(outside, scope))
        assertFalse(ManagedFiles.isWithin(File(scope, "../servers-other/data.json"), scope))
        assertFalse(ManagedFiles.isWithin(scope, scope))
    }

    @Test
    fun cleanupRevalidatesRuntimeAndChangedFiles() = withRoot { root ->
        val file = oldFile(root, "tmp/download.zip")
        var busy = false
        val service = cleanup(root) { busy }
        val item = service.scan().single()
        busy = true
        assertThrows(IllegalArgumentException::class.java) { service.delete(item.path, item.token) }
        busy = false
        val next = service.scan().single()
        file.appendText("changed")
        assertThrows(IllegalArgumentException::class.java) { service.delete(next.path, next.token) }
        assertTrue(file.exists())
    }

    @Test
    fun cleanupRefusesSymlinkEscapes() = withRoot { root ->
        val outside = oldFile(root, "outside/secret.json")
        val link = File(root, "tmp/escape.json").apply { parentFile?.mkdirs() }
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        assumeTrue("Symlink creation is not available on this host", linked)
        assertFalse(ManagedFiles.isWithin(link, File(root, "tmp")))
        assertTrue(cleanup(root).scan().isEmpty())
        assertTrue(outside.exists())
    }

    @Test
    fun scansDoNotMeasureTreesAndDetailsUseCache() = withRoot { root ->
        oldFile(root, "servers/local/server.js")
        oldFile(root, "servers/local/package.json", """{"version":"1.2.3"}""")
        val measured = AtomicInteger()
        val repository = InstanceRepository(File(root, "servers"), { now }) { measured.incrementAndGet(); 123L }
        assertEquals("1.2.3", repository.scan().single().version)
        assertEquals(0, measured.get())
        val directory = File(root, "servers/local")
        assertEquals(123, repository.info(directory).sizeBytes)
        assertEquals(123, repository.info(directory).sizeBytes)
        assertEquals(1, measured.get())
        repository.invalidate(directory)
        repository.info(directory)
        assertEquals(2, measured.get())
    }

    @Test
    fun staleInstallCannotLaunchOrPublishAfterClose() {
        val coordinator = OperationCoordinator()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val launched = AtomicBoolean(false)
        val operation = coordinator.begin("local", "first")
        try {
            coordinator.execute(operation) {
                entered.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) {}
                }
                try { coordinator.commit(operation) { launched.set(true) } }
                catch (_: CancellationException) {}
                finally { finished.countDown() }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(coordinator.cancel("local", "first"))
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertFalse(launched.get())
        } finally {
            release.countDown()
            coordinator.close()
        }
    }

    @Test
    fun oldStopCannotCancelANewerOperation() {
        OperationCoordinator().use { coordinator ->
            val old = coordinator.begin("local", "first")
            val current = coordinator.begin("local", "second")
            assertFalse(coordinator.cancel("local", "first"))
            assertFalse(coordinator.isCurrent(old))
            assertTrue(coordinator.isCurrent(current))
            assertFalse(coordinator.cancel("other", "second"))
            assertTrue(coordinator.isCurrent(current))
        }
    }

    @Test
    fun commandTimeoutIsIndependentOfOutputEof() {
        OperationCoordinator().use { coordinator ->
            ProcessSupervisor(coordinator).use { supervisor ->
                val process = OpenOutputProcess()
                supervisor.track(process, "local")
                val startedAt = System.nanoTime()
                val result = supervisor.waitFor(process, 50) {}
                assertTrue(result.timedOut)
                assertFalse(process.isAlive)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 2_000)
            }
        }
    }

    @Test
    fun stoppingDoesNotWaitOnAStuckOutputClose() {
        val releaseClose = CountDownLatch(1)
        OperationCoordinator().use { coordinator ->
            ProcessSupervisor(coordinator).use { supervisor ->
                val process = OpenOutputProcess(releaseClose)
                try {
                    supervisor.track(process, "local")
                    val startedAt = System.nanoTime()
                    supervisor.stopAllAsync().get(500, TimeUnit.MILLISECONDS)
                    assertFalse(process.isAlive)
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 500)
                } finally {
                    releaseClose.countDown()
                }
            }
        }
    }

    @Test
    fun closeCancelsCommandsQueuedBeforeTheirProcessStarts() {
        OperationCoordinator().use { coordinator ->
            ProcessSupervisor(coordinator).use { supervisor ->
                val generation = supervisor.generation()
                supervisor.stopAllAsync()
                assertThrows(CancellationException::class.java) {
                    supervisor.launch(ProcessBuilder("must-not-be-executed"), "local", null, generation)
                }
            }
        }
    }

    @Test
    fun logTailReadsBoundedUtf8Lines() = withRoot { root ->
        val file = File(root, "server.log")
        file.writeText((1..10_000).joinToString("\n") { "line $it" } + "\n")
        val lines = LogService.tail(file, 30, 1_024)
        assertEquals(30, lines.size)
        assertEquals("line 9971", lines.first())
        assertEquals("line 10000", lines.last())
        file.writeText("x".repeat(200_000))
        assertTrue(LogService.tail(file, 30, 1_024).isEmpty())
    }

    @Test
    fun logWritesRotateAtABoundedSize() = withRoot { root ->
        val file = File(root, "server.log")
        repeat(20) { LogService.append(file, "line number $it", 100) }
        assertTrue(file.length() <= 100)
        assertTrue(File(root, "server.log.1").length() <= 100)
        assertEquals("line number 19", LogService.tail(file).last())
    }

    @Test
    fun missingDependenciesAreInstalledInPlaceWithoutDeletingData() = withRoot { root ->
        val target = File(root, "servers/local")
        val source = oldFile(root, "servers/local/server.js", "server source")
        val data = oldFile(root, "servers/local/data/chat.jsonl", "user history")
        val installer = InstanceInstaller(File(root, "servers"))
        installer.prepare(target, {}, extract = { throw AssertionError("Existing source was re-extracted") },
            installDependencies = {
                assertEquals(target, it)
                File(it, "node_modules").mkdirs()
                true
            }, commit = { it() })
        assertEquals("server source", source.readText())
        assertEquals("user history", data.readText())
        assertTrue(File(target, "node_modules").isDirectory)
    }

    @Test
    fun dependencyFailurePreservesExistingSourceAndData() = withRoot { root ->
        val target = File(root, "servers/local")
        val source = oldFile(root, "servers/local/server.js", "source")
        val data = oldFile(root, "servers/local/data/chat.jsonl", "history")
        assertThrows(IllegalStateException::class.java) {
            InstanceInstaller(File(root, "servers")).prepare(
                target, {}, { throw AssertionError("Existing source was replaced") }, { false }, { it() }
            )
        }
        assertEquals("source", source.readText())
        assertEquals("history", data.readText())
    }

    @Test
    fun retriesPartialDependenciesAfterAFailedInstallWithoutReplacingTheInstance() = withRoot { root ->
        val target = File(root, "servers/local")
        val source = oldFile(root, "servers/local/server.js", "source")
        val data = oldFile(root, "servers/local/data/chat.jsonl", "history")
        val installer = InstanceInstaller(File(root, "servers"))
        val marker = File(target, InstanceInstaller.DEPENDENCY_MARKER)
        assertThrows(IllegalStateException::class.java) {
            installer.prepare(target, {}, { throw AssertionError("Existing source was replaced") }, {
                File(it, "node_modules").mkdirs()
                false
            }, { it() })
        }
        assertTrue(marker.isFile)
        var retried = false
        installer.prepare(target, {}, { throw AssertionError("Existing source was replaced") }, {
            retried = true
            File(it, "node_modules/complete").writeText("installed")
            true
        }, { it() })
        assertTrue(retried)
        assertFalse(marker.exists())
        assertEquals("source", source.readText())
        assertEquals("history", data.readText())
    }

    @Test
    fun cancelledDependencyInstallCanBeRetriedAndCannotClearThePendingState() = withRoot { root ->
        val target = File(root, "servers/local")
        val data = oldFile(root, "servers/local/data/chat.jsonl", "history")
        oldFile(root, "servers/local/server.js", "source")
        val installer = InstanceInstaller(File(root, "servers"))
        val marker = File(target, InstanceInstaller.DEPENDENCY_MARKER)
        OperationCoordinator().use { operations ->
            val operation = operations.begin("local")
            assertThrows(CancellationException::class.java) {
                installer.prepare(target, { operations.ensureCurrent(operation) }, { true }, {
                    File(it, "node_modules").mkdirs()
                    operations.cancel()
                    true
                }, { action -> operations.commit(operation, action) })
            }
        }
        assertTrue(marker.isFile)
        var retried = false
        installer.prepare(target, {}, { true }, { retried = true; true }, { it() })
        assertTrue(retried)
        assertFalse(marker.exists())
        assertEquals("history", data.readText())
    }

    @Test
    fun anUnrecognizedDependencyMarkerIsPreservedInsteadOfOverwritten() = withRoot { root ->
        val target = File(root, "servers/local")
        oldFile(root, "servers/local/server.js", "source")
        val marker = oldFile(root, "servers/local/${InstanceInstaller.DEPENDENCY_MARKER}", "user content")
        assertThrows(IllegalArgumentException::class.java) {
            InstanceInstaller(File(root, "servers")).prepare(
                target, {}, { true }, { throw AssertionError("npm started despite an unowned marker") }, { it() }
            )
        }
        assertEquals("user content", marker.readText())
    }

    @Test
    fun incompleteExistingDirectoryIsNeverDiscardedForReinstall() = withRoot { root ->
        val target = File(root, "servers/partial")
        val data = oldFile(root, "servers/partial/data/chat.jsonl", "history")
        assertThrows(IllegalArgumentException::class.java) {
            InstanceInstaller(File(root, "servers")).prepare(
                target, {}, { throw AssertionError("Existing data was replaced") }, { true }, { it() }
            )
        }
        assertEquals("history", data.readText())
    }

    @Test
    fun failedNewInstallationNeverCommitsPartialSource() = withRoot { root ->
        val target = File(root, "servers/new")
        File(root, "servers").mkdirs()
        assertThrows(IllegalStateException::class.java) {
            InstanceInstaller(File(root, "servers")).prepare(
                target, {}, { File(it, "server.js").writeText("source"); true }, { false }, { it() }
            )
        }
        assertFalse(target.exists())
        assertTrue(File(root, "tmp").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationCannotCommitDownloadedSource() = withRoot { root ->
        val target = File(root, "servers/new")
        File(root, "servers").mkdirs()
        OperationCoordinator().use { coordinator ->
            val operation = coordinator.begin("new")
            assertThrows(CancellationException::class.java) {
                InstanceInstaller(File(root, "servers")).prepare(
                    target, { coordinator.ensureCurrent(operation) },
                    { directory ->
                        File(directory, "server.js").writeText("source")
                        coordinator.cancel()
                        true
                    }, { true }, { action -> coordinator.commit(operation, action) }
                )
            }
        }
        assertFalse(target.exists())
        assertTrue(File(root, "tmp").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun migrationRejectsUnsupportedOrExistingTargetsBeforeWriting() = withRoot { root ->
        val original = oldFile(root, "original/data/chat.jsonl", "original history")
        val target = File(root, "servers/new")
        assertThrows(IllegalArgumentException::class.java) {
            MigrationPolicy.validate(File(root, "original").absolutePath, target, "takeover", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MigrationPolicy.validate(File(root, "original").absolutePath, target, "copy", File(root, "custom").absolutePath)
        }
        assertFalse(target.exists())
        val existing = oldFile(root, "servers/new/data/chat.jsonl", "existing target")
        assertThrows(IllegalArgumentException::class.java) {
            MigrationPolicy.validate(File(root, "original").absolutePath, target, "copy", null)
        }
        assertEquals("original history", original.readText())
        assertEquals("existing target", existing.readText())
    }

    @Test
    fun migrationNeverVerifiesWhenDependenciesFail() = withRoot { root ->
        val original = oldFile(root, "original/data/chat.jsonl", "original history")
        oldFile(root, "servers/new/server.js")
        val target = File(root, "servers/new")
        assertFalse(MigrationPolicy.verify(target, { false }))
        assertEquals("original history", original.readText())
        assertTrue(File(target, "server.js").exists())
    }

    @Test
    fun failedMigrationCopyDoesNotCommitOrChangeTheSource() = withRoot { root ->
        val source = oldFile(root, "original/data/chat.jsonl", "original history")
        val target = File(root, "servers/new")
        File(root, "servers").mkdirs()
        assertThrows(java.io.IOException::class.java) {
            InstanceInstaller(File(root, "servers")).prepare(
                target, {},
                { staging ->
                    File(staging, "server.js").writeText("partial source")
                    throw java.io.IOException("Document provider stopped reading")
                },
                { true }, { it() }
            )
        }
        assertEquals("original history", source.readText())
        assertFalse(target.exists())
        assertTrue(File(root, "tmp").listFiles().orEmpty().isEmpty())
    }

    private class OpenOutputProcess(private val closeGate: CountDownLatch? = null) : Process() {
        private val alive = AtomicBoolean(true)
        private val finished = CountDownLatch(1)
        private val streamClosed = CountDownLatch(1)
        private val output = object : InputStream() {
            override fun read(): Int { streamClosed.await(); return -1 }
            override fun close() { closeGate?.await(); streamClosed.countDown() }
        }
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = output
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int { finished.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = finished.await(timeout, unit)
        override fun exitValue(): Int { check(!alive.get()); return 0 }
        override fun destroy() { alive.set(false); finished.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive(): Boolean = alive.get()
    }
}
