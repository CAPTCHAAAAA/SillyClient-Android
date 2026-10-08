package com.sillyclient.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledRuntimeTest {
    private fun withPaths(test: (RuntimePaths) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent != null) Files.createTempDirectory(parent.toPath(), "bundled-runtime-").toFile()
            else Files.createTempDirectory("bundled-runtime-").toFile()
        try {
            val home = File(root, "tarven")
            val usr = File(home, "usr")
            val native = File(root, "native").apply { mkdirs() }
            val node = File(native, "node").apply { writeText("synthetic native entry") }
            test(RuntimePaths(root, home, File(home, "bootstrap"), File(home, "servers"), usr, File(usr, "lib"),
                File(home, "tmp"), File(home, "logs"), native, node))
        } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    private fun asset(name: String, version: String = "one"): ByteArrayInputStream {
        val files = when (name) {
            "bootstrap/rootfs/rootfs-libs.zip" -> mapOf("lib/libsynthetic.so" to "libs-$version")
            "bootstrap/rootfs/rootfs-usr.zip" -> mapOf("lib/node_modules/npm/bin/npm-cli.js" to "npm-$version")
            else -> throw AssertionError("Unexpected asset: $name")
        }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((entry, text) in files) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun marker(paths: RuntimePaths) = File(paths.usrDir, ".sillyclient-runtime-ready")

    @Test
    fun separateActivityObjectsSerializePreparationOfTheSameRuntime() = withPaths { paths ->
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val reads = AtomicInteger()
        BundledRuntime(paths, "revision-one") { name ->
            if (reads.incrementAndGet() == 1) {
                entered.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
            }
            asset(name)
        }.use { first ->
            BundledRuntime(paths, "revision-one") { reads.incrementAndGet(); asset(it) }.use { second ->
                val initial = first.prepareAsync()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val replacement = second.prepareAsync()
                proceed.countDown()
                initial.get(5, TimeUnit.SECONDS)
                replacement.get(5, TimeUnit.SECONDS)
                assertEquals(2, reads.get())
            }
        }
    }

    @Test
    fun firstPreparationExtractsOnceAndReadyRevisionSkipsSubsequentExtraction() = withPaths { paths ->
        val reads = AtomicInteger()
        BundledRuntime(paths, "revision-one") { reads.incrementAndGet(); asset(it) }.use { runtime ->
            val first = runtime.prepareAsync()
            first.get(5, TimeUnit.SECONDS)
            assertSame(first, runtime.prepareAsync())
            runtime.awaitReady {}
        }
        assertEquals(2, reads.get())
        assertEquals("revision-one", marker(paths).readText())
        assertEquals("npm-one", File(paths.usrDir, "lib/node_modules/npm/bin/npm-cli.js").readText())
        BundledRuntime(paths, "revision-one") { throw AssertionError("Ready runtime must not extract again") }.use {
            it.prepareAsync().get(5, TimeUnit.SECONDS)
        }
        assertFalse(paths.serversDir.exists())
        assertTrue(paths.usrDir.listFiles().orEmpty().none { it.name.startsWith(".runtime-") })
    }

    @Test
    fun concurrentPreparationRequestsShareOneInFlightFuture() = withPaths { paths ->
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val start = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(4)
        val reads = AtomicInteger()
        BundledRuntime(paths, "revision-one") { name ->
            if (reads.incrementAndGet() == 1) {
                entered.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
            }
            asset(name)
        }.use { runtime ->
            try {
                val requests = (0 until 4).map {
                    callers.submit<CompletableFuture<Unit>> {
                        check(start.await(5, TimeUnit.SECONDS))
                        runtime.prepareAsync()
                    }
                }
                start.countDown()
                val futures = requests.map { it.get(5, TimeUnit.SECONDS) }
                val first = futures.first()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFalse(first.isDone)
                futures.forEach { assertSame(first, it) }
                assertEquals(1, reads.get())
                proceed.countDown()
                first.get(5, TimeUnit.SECONDS)
                assertEquals(2, reads.get())
            } finally {
                proceed.countDown()
                start.countDown()
                callers.shutdownNow()
                assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun failedExtractionHasNoSuccessStampAndCanBeRetried() = withPaths { paths ->
        val fail = AtomicBoolean(true)
        val reads = AtomicInteger()
        BundledRuntime(paths, "revision-one") { name ->
            reads.incrementAndGet()
            if (name.endsWith("rootfs-usr.zip") && fail.getAndSet(false)) throw IOException("synthetic extraction failure")
            asset(name)
        }.use { runtime ->
            val first = runtime.prepareAsync()
            assertThrows(ExecutionException::class.java) { first.get(5, TimeUnit.SECONDS) }
            assertFalse(marker(paths).exists())
            val retry = runtime.prepareAsync()
            assertFalse(first === retry)
            retry.get(5, TimeUnit.SECONDS)
            assertEquals(4, reads.get())
            assertEquals("revision-one", marker(paths).readText())
        }
    }

    @Test
    fun revisionChangeReplacesOldStampOnlyAfterBothAssetsComplete() = withPaths { paths ->
        BundledRuntime(paths, "revision-one") { asset(it, "one") }.use { it.prepareAsync().get(5, TimeUnit.SECONDS) }
        val reads = AtomicInteger()
        BundledRuntime(paths, "revision-two") { name ->
            reads.incrementAndGet()
            assertFalse("Old success stamp must be removed before extraction", marker(paths).exists())
            asset(name, "two")
        }.use { it.prepareAsync().get(5, TimeUnit.SECONDS) }
        assertEquals(2, reads.get())
        assertEquals("revision-two", marker(paths).readText())
        assertEquals("npm-two", File(paths.usrDir, "lib/node_modules/npm/bin/npm-cli.js").readText())
        assertEquals("libs-two", File(paths.usrLibDir, "libsynthetic.so").readText())
    }

    @Test
    fun failedUpgradeInvalidatesThePreviousStampAndSupportsRetry() = withPaths { paths ->
        BundledRuntime(paths, "revision-one") { asset(it) }.use { it.prepareAsync().get(5, TimeUnit.SECONDS) }
        BundledRuntime(paths, "revision-two") { throw IOException("synthetic update failure") }.use { runtime ->
            assertThrows(ExecutionException::class.java) { runtime.prepareAsync().get(5, TimeUnit.SECONDS) }
        }
        assertFalse(marker(paths).exists())
        BundledRuntime(paths, "revision-two") { asset(it, "two") }.use { it.prepareAsync().get(5, TimeUnit.SECONDS) }
        assertEquals("revision-two", marker(paths).readText())
    }

    @Test
    fun missingNpmInvalidatesAnOtherwiseMatchingRevision() = withPaths { paths ->
        BundledRuntime(paths, "revision-one") { asset(it) }.use { it.prepareAsync().get(5, TimeUnit.SECONDS) }
        assertTrue(File(paths.usrDir, "lib/node_modules/npm/bin/npm-cli.js").delete())
        val reads = AtomicInteger()
        BundledRuntime(paths, "revision-one") { reads.incrementAndGet(); asset(it) }.use {
            it.prepareAsync().get(5, TimeUnit.SECONDS)
        }
        assertEquals(2, reads.get())
        assertTrue(File(paths.usrDir, "lib/node_modules/npm/bin/npm-cli.js").isFile)
    }

    @Test
    fun cancelledInstanceWaitDoesNotCancelTheSharedPreparation() = withPaths { paths ->
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        BundledRuntime(paths, "revision-one") { name ->
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            asset(name)
        }.use { runtime ->
            try {
                val shared = runtime.prepareAsync()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertThrows(CancellationException::class.java) {
                    runtime.awaitReady { throw CancellationException("synthetic instance cancelled") }
                }
                assertFalse(shared.isCancelled)
                proceed.countDown()
                shared.get(5, TimeUnit.SECONDS)
                assertEquals("revision-one", marker(paths).readText())
            } finally { proceed.countDown() }
        }
    }

    @Test
    fun missingNativeExecutableFailsBeforeOpeningAnyArchive() = withPaths { paths ->
        assertTrue(paths.nodeBin.delete())
        BundledRuntime(paths, "revision-one") { throw AssertionError("Native entry must be checked first") }.use { runtime ->
            val error = assertThrows(ExecutionException::class.java) { runtime.prepareAsync().get(5, TimeUnit.SECONDS) }
            assertTrue(error.cause!!.message!!.contains("Node.js executable"))
            assertFalse(marker(paths).exists())
        }
    }
}
