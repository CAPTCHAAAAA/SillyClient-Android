package com.sillyclient.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDownloaderTest {
    private val api = "https://api.github.com/repos/SillyTavern/SillyTavern/zipball/1.12.0"
    private val code = "https://codeload.github.com/SillyTavern/SillyTavern/zip/1.12.0"
    private val archive = "https://github.com/SillyTavern/SillyTavern/archive/1.12.0.zip"

    private class Response(
        url: URI,
        private val status: Int = 200,
        private val bytes: ByteArray = byteArrayOf(),
        private val length: Long = bytes.size.toLong(),
        private val location: String? = null,
        private val type: String = "application/zip",
        private val body: (() -> InputStream)? = null,
        private val onDisconnect: () -> Unit = {}
    ) : HttpURLConnection(url.toURL()) {
        var disconnected = false
        var opened = false
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true; onDisconnect() }
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getContentType() = type
        override fun getContentEncoding(): String? = null
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
        override fun getInputStream(): InputStream {
            opened = true
            return body?.invoke() ?: ByteArrayInputStream(bytes)
        }
    }

    private fun zip(vararg names: String = arrayOf("SillyTavern-test/server.js", "SillyTavern-test/package.json")): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            names.forEach { name ->
                output.putNextEntry(ZipEntry(name))
                output.write(if (name.endsWith("package.json")) "{\"name\":\"sillytavern\"}".toByteArray() else "source".toByteArray())
                output.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun withRoot(test: (File, OperationCoordinator, OperationCoordinator.Operation) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent != null) Files.createTempDirectory(parent.toPath(), "source-download-").toFile()
            else Files.createTempDirectory("source-download-").toFile()
        try {
            OperationCoordinator().use { operations -> test(root, operations, operations.begin("test")) }
        } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    @Test
    fun normalizesApiDownloadsToOfficialArchivePaths() {
        val candidates = SourceDownloader.candidates(api)
        // 镜像源已全部移除：仅保留官方 codeload 与 GitHub 归档两个候选。
        assertEquals("codeload.github.com", candidates.first().uri.host)
        assertEquals("github.com", candidates[1].uri.host)
        assertEquals(2, candidates.size)
        assertFalse(candidates.any { "api.github.com" in it.uri.toString() })
        assertEquals(candidates, SourceDownloader.candidates(archive))
        assertEquals(candidates, SourceDownloader.candidates(code))
    }

    @Test
    fun refusesPrivateOrUnknownUrlsBeforeCreatingAnyFilesOrConnections() = withRoot { root, operations, operation ->
        val output = File(root, "downloads")
        var opened = false
        val downloader = SourceDownloader(connect = { opened = true; throw AssertionError("Must not connect") })
        for (url in listOf(
            "http://api.github.com/repos/SillyTavern/SillyTavern/zipball/1.12.0",
            "$api?token=secret", "$api#secret",
            api.replace("https://", "https://user:secret@"),
            api.replace("https://", "https://@"),
            api.replace("api.github.com", "api.github.com.evil.test"),
            api.replace("api.github.com", "127.0.0.1"),
            api.replace("SillyTavern/SillyTavern", "Somebody/private"),
            api.replace("1.12.0", "../secret"), api.replace("1.12.0", "%31.12.0"),
            api.replace("api.github.com", "api.github.com:8443"),
            "https://ghfast.top/$archive"
        )) assertThrows(IllegalArgumentException::class.java) {
            downloader.downloadSillyTavern(url, output, operations, operation)
        }
        assertFalse(opened)
        assertFalse(output.exists())
    }

    @Test
    fun allowsOnlySamePublicRefAcrossRedirects() {
        val source = URI(code)
        assertTrue(SourceDownloader.validRedirect(source,
            URI("https://codeload.github.com/SillyTavern/SillyTavern/legacy.zip/refs/tags/1.12.0")))
        assertTrue(SourceDownloader.validRedirect(URI(code.replace("1.12.0", "release")),
            URI(code.replace("1.12.0", "refs/heads/release"))))
        assertFalse(SourceDownloader.validRedirect(URI(code.replace("1.12.0", "refs/tags/release")),
            URI(code.replace("1.12.0", "refs/heads/release"))))
        for (target in listOf(
            "https://evil.test/file.zip", code.replace("https://", "http://"),
            code.replace("1.12.0", "1.13.0"), "$code?token=secret",
            code.replace("https://", "https://user:secret@"),
            "https://ghfast.top/https://github.com/Private/repo/archive/1.12.0.zip"
        )) assertFalse(SourceDownloader.validRedirect(source, URI(target)))
    }

    @Test
    fun successfulArchiveIsPublishedOnceWithBoundedTransportSettings() = withRoot { root, operations, operation ->
        val responses = mutableListOf<Response>()
        val progress = mutableListOf<SourceDownloader.Progress>()
        val bytes = zip()
        val downloaded = SourceDownloader(connect = {
            Response(it, bytes = bytes).also(responses::add)
        }).downloadSillyTavern(api, root, operations, operation, onProgress = progress::add)
        assertEquals(1, responses.size)
        assertEquals(5_000, responses.single().connectTimeout)
        assertEquals(8_000, responses.single().readTimeout)
        assertFalse(responses.single().instanceFollowRedirects)
        assertFalse(responses.single().useCaches)
        assertEquals("identity", responses.single().getRequestProperty("Accept-Encoding"))
        assertEquals(null, responses.single().getRequestProperty("Authorization"))
        assertTrue(responses.single().disconnected)
        assertTrue(downloaded.readBytes().contentEquals(bytes))
        assertEquals("zip", downloaded.extension)
        assertEquals(listOf(downloaded), root.listFiles()!!.toList())
        assertEquals(bytes.size.toLong(), progress.last().downloadedBytes)
    }

    @Test
    fun unknownContentLengthIsSupportedAndStillBounded() = withRoot { root, operations, operation ->
        val bytes = zip()
        val progress = mutableListOf<SourceDownloader.Progress>()
        val downloaded = SourceDownloader(connect = {
            Response(it, bytes = bytes, length = -1)
        }).downloadSillyTavern(api, root, operations, operation, onProgress = progress::add)
        assertTrue(downloaded.readBytes().contentEquals(bytes))
        assertEquals(null, progress.last().totalBytes)
    }

    @Test
    fun switchesSourceImmediatelyAfterFailureWithoutReusingPartialContent() = withRoot { root, operations, operation ->
        val bytes = zip()
        val requests = mutableListOf<URI>()
        val result = SourceDownloader(connect = { uri ->
            requests.add(uri)
            if (requests.size == 1) Response(uri, length = bytes.size.toLong(), body = {
                object : InputStream() {
                    var supplied = false
                    override fun read(): Int = throw AssertionError("Uses buffered reads")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (supplied) throw IOException("network reset")
                        supplied = true
                        bytes.copyInto(buffer, offset, 0, 8)
                        return 8
                    }
                }
            }) else Response(uri, bytes = bytes)
        }).downloadSillyTavern(api, root, operations, operation)
        assertEquals(2, requests.size)
        assertTrue(result.readBytes().contentEquals(bytes))
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test
    fun fallsBackToTheOfficialArchiveWhenCodeloadIsRateLimited() = withRoot { root, operations, operation ->
        val bytes = zip()
        val responses = mutableListOf<Response>()
        val result = SourceDownloader(connect = { uri ->
            val response = when (responses.size) {
                0 -> Response(uri, status = 429, bytes = bytes)
                else -> Response(uri, bytes = bytes)
            }
            responses.add(response)
            response
        }).downloadSillyTavern(api, root, operations, operation)
        // 两个官方候选：codeload 429 后回退 GitHub 归档成功。
        assertEquals(2, responses.size)
        assertFalse(responses[0].opened)
        assertTrue(responses.all { it.disconnected })
        assertTrue(result.readBytes().contentEquals(bytes))
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test
    fun rejectsMismatchedContentLengthsAndLeavesNoFailedArchives() = withRoot { root, operations, operation ->
        val bytes = zip()
        for (length in listOf(bytes.size - 1L, bytes.size + 1L)) {
            assertThrows(IOException::class.java) {
                SourceDownloader(connect = { Response(it, bytes = bytes, length = length) })
                    .downloadSillyTavern(api, root, operations, operation)
            }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun refusesOversizeHeadersBeforeReadingAndOversizeStreamsDuringTransfer() = withRoot { root, operations, operation ->
        val bytes = zip()
        val responses = mutableListOf<Response>()
        val downloader = SourceDownloader(
            connect = { Response(it, bytes = bytes).also(responses::add) },
            limits = SourceDownloader.Limits(maxArchiveBytes = 16)
        )
        assertThrows(IOException::class.java) { downloader.downloadSillyTavern(api, root, operations, operation) }
        assertTrue(responses.none { it.opened })
        assertThrows(IOException::class.java) {
            SourceDownloader(connect = { Response(it, bytes = bytes, length = -1) },
                limits = SourceDownloader.Limits(maxArchiveBytes = 16))
                .downloadSillyTavern(api, root, operations, operation)
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun followsAllowedRedirectsButNeverContactsAnUntrustedRedirectTarget() = withRoot { root, operations, operation ->
        val requests = mutableListOf<URI>()
        val result = SourceDownloader(connect = { uri ->
            requests.add(uri)
            when (requests.size) {
                1 -> Response(uri, status = 302, location = "https://evil.test/secret")
                2 -> Response(uri, status = 302, location = code)
                else -> Response(uri, bytes = zip())
            }
        }).downloadSillyTavern(api, root, operations, operation)
        assertEquals(3, requests.size)
        assertTrue(requests.none { it.host == "evil.test" })
        assertTrue(result.isFile)
    }

    @Test
    fun redirectLoopsAreBoundedAndDoNotLeakTemporaryFiles() = withRoot { root, operations, operation ->
        val requests = mutableListOf<URI>()
        assertThrows(IOException::class.java) {
            SourceDownloader(connect = { uri ->
                requests.add(uri)
                Response(uri, status = 302, location = uri.toString())
            }).downloadSillyTavern(api, root, operations, operation)
        }
        assertEquals(SourceDownloader.candidates(api).size, requests.size)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun rejectsUnsafeArchivesAndMissingEntrypoints() = withRoot { root, operations, operation ->
        for (bytes in listOf(
            zip("root/server.js", "root/package.json", "root/../escaped"),
            zip("root/server.js", "other/package.json"),
            zip("root/server.js", "root/file.txt"),
            zip("root/server.js", "root/package.json", "root/back\\slash"),
            zip("root/server.js", "root/package.json", "root/C:ads")
        )) {
            assertThrows(IOException::class.java) {
                SourceDownloader(connect = { Response(it, bytes = bytes) })
                    .downloadSillyTavern(api, root, operations, operation)
            }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun boundsUnpackedSizeAndArchiveEntryCount() = withRoot { root, operations, operation ->
        for (limits in listOf(
            SourceDownloader.Limits(maxUnpackedBytes = 10),
            SourceDownloader.Limits(maxFileBytes = 5),
            SourceDownloader.Limits(maxEntries = 1)
        )) {
            assertThrows(IOException::class.java) {
                SourceDownloader(connect = { Response(it, bytes = zip()) }, limits = limits)
                    .downloadSillyTavern(api, root, operations, operation)
            }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun cancellationInterruptsCurrentTransferWithoutTryingAnotherProvider() = withRoot { root, operations, operation ->
        val responses = mutableListOf<Response>()
        val bytes = zip()
        assertThrows(CancellationException::class.java) {
            SourceDownloader(connect = { uri ->
                Response(uri, length = bytes.size.toLong(), body = {
                    object : ByteArrayInputStream(bytes) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            operations.cancel(operation.instanceId, operation.operationId)
                            return super.read(buffer, offset, length)
                        }
                    }
                }).also(responses::add)
            }).downloadSillyTavern(api, root, operations, operation)
        }
        assertEquals(1, responses.size)
        assertTrue(responses.single().disconnected)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun cancelledGenerationCannotPublishCompletedBody() = withRoot { root, operations, operation ->
        assertThrows(CancellationException::class.java) {
            SourceDownloader(connect = { Response(it, bytes = zip()) })
                .downloadSillyTavern(api, root, operations, operation, onProgress = {
                    if (it.downloadedBytes > 0) operations.begin("replacement")
                })
        }
        assertTrue(root.listFiles()!!.isEmpty())
        assertEquals("replacement", operations.current()!!.instanceId)
    }

    @Test
    fun cancellationDisconnectsBlockedIoAndRemovesItsPartialFile() = withRoot { root, operations, operation ->
        val reading = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var requests = 0
        var failure: Throwable? = null
        val worker = Thread {
            try {
                SourceDownloader(connect = { uri ->
                    requests++
                    Response(uri, length = -1, body = {
                        object : InputStream() {
                            override fun read(): Int {
                                reading.countDown()
                                check(disconnected.await(5, TimeUnit.SECONDS)) { "Disconnect did not unblock I/O" }
                                throw IOException("disconnected")
                            }
                        }
                    }, onDisconnect = { disconnected.countDown() })
                }).downloadSillyTavern(api, root, operations, operation)
            } catch (error: Throwable) {
                failure = error
            } finally {
                finished.countDown()
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertTrue(operations.cancel(operation.instanceId, operation.operationId))
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(failure is CancellationException)
            assertEquals(1, requests)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {
            disconnected.countDown()
            worker.join(5_000)
        }
    }

    @Test
    fun totalDeadlineStopsFurtherRequestsAndRemovesPartialFile() = withRoot { root, operations, operation ->
        var now = 0L
        var requests = 0
        assertThrows(IOException::class.java) {
            SourceDownloader(connect = { uri ->
                requests++
                now += TimeUnit.MILLISECONDS.toNanos(101)
                Response(uri, bytes = zip())
            }, limits = SourceDownloader.Limits(totalTimeoutMillis = 100), nanoTime = { now })
                .downloadSillyTavern(api, root, operations, operation)
        }
        assertEquals(1, requests)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun loggingDoesNotExposeArbitraryTransportErrorMessages() = withRoot { root, operations, operation ->
        val log = mutableListOf<String>()
        val error = assertThrows(IOException::class.java) {
            SourceDownloader(connect = { throw IOException("https://username:secret@example.test?token=secret") })
                .downloadSillyTavern(api, root, operations, operation, onLog = log::add)
        }
        assertFalse(log.any { "secret" in it || "username" in it })
        assertFalse(error.message!!.contains("secret"))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun storageFailuresDoNotDownloadAgainFromEveryMirror() = withRoot { root, operations, operation ->
        var requests = 0
        val error = assertThrows(IOException::class.java) {
            SourceDownloader(connect = {
                requests++
                Response(it, bytes = zip())
            }, openOutput = {
                object : OutputStream() {
                    override fun write(value: Int) { throw IOException("ENOSPC") }
                }
            }).downloadSillyTavern(api, root, operations, operation)
        }
        assertEquals(1, requests)
        assertTrue(error.message!!.contains("free space"))
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
