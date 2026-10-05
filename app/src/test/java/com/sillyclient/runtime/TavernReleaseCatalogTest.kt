package com.sillyclient.runtime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernReleaseCatalogTest {
    private class Response(uri: URI, private val bytes: ByteArray, private val status: Int = 200,
        private val length: Long = bytes.size.toLong()) : HttpURLConnection(uri.toURL()) {
        var disconnected = false
        var opened = false
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getContentEncoding(): String? = null
        override fun getInputStream(): ByteArrayInputStream { opened = true; return ByteArrayInputStream(bytes) }
    }

    private fun withCache(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent != null) Files.createTempDirectory(parent.toPath(), "release-catalog-").toFile()
            else Files.createTempDirectory("release-catalog-").toFile()
        try { test(File(root, "catalog/releases.json")) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    private fun apiRecord(tag: String = "1.12.0") = JSONObject()
        .put("tag_name", tag).put("name", "SillyTavern $tag")
        .put("zipball_url", "https://api.github.com/repos/SillyTavern/SillyTavern/zipball/$tag")
        .put("published_at", "2024-07-01T12:00:00Z").put("prerelease", false).put("draft", false)
        .put("body", "Release notes must not be persisted")

    private fun successful(cache: File, records: JSONArray = JSONArray().put(apiRecord())): JSONArray =
        TavernReleaseCatalog(cache, connect = { Response(it, records.toString().toByteArray()) }).load()

    private fun offline(cache: File): JSONArray = TavernReleaseCatalog(cache, connect = { throw IOException("offline") }).load()

    private fun assertBranch(releases: JSONArray) {
        assertEquals(1, releases.length())
        val branch = releases.getJSONObject(0)
        assertEquals("release", branch.getString("tag"))
        assertTrue(branch.getString("name").contains("stable release branch"))
        assertEquals(TavernReleaseCatalog.STABLE_BRANCH_URL, branch.getString("zipballUrl"))
        assertTrue(branch.getBoolean("isBranch"))
        assertFalse(branch.has("publishedAt"))
        assertFalse(branch.getBoolean("prerelease"))
        assertTrue(SourceDownloader.candidates(branch.getString("zipballUrl")).isNotEmpty())
    }

    @Test
    fun officialLookupIsBoundedAndPersistsOnlyValidatedPublicMetadata() = withCache { cache ->
        val responses = mutableListOf<Response>()
        val releases = TavernReleaseCatalog(cache, connect = { uri ->
            assertEquals(TavernReleaseCatalog.API_URL, uri.toString())
            Response(uri, JSONArray().put(apiRecord()).toString().toByteArray()).also(responses::add)
        }).load()
        assertEquals("1.12.0", releases.getJSONObject(0).getString("tag"))
        val connection = responses.single()
        assertEquals(5_000, connection.connectTimeout)
        assertEquals(5_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.disconnected)
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertEquals(releases.toString(), cache.readText())
        assertFalse(cache.readText().contains("Release notes"))
        assertEquals(1, cache.parentFile!!.listFiles()!!.size)
    }

    @Test
    fun officialFailureUsesThePersistedVersionListBeforeTheBranchFallback() = withCache { cache ->
        val saved = successful(cache)
        val bytes = cache.readBytes()
        assertEquals(saved.toString(), offline(cache).toString())
        assertTrue(cache.readBytes().contentEquals(bytes))
    }

    @Test
    fun firstOfflineLookupOffersAnHonestStableBranchWithoutInventedVersionOrDate() = withCache { cache ->
        assertBranch(offline(cache))
        assertFalse(cache.exists())
    }

    @Test
    fun invalidOrEmptyOfficialResponseDoesNotReplaceTheLastValidCache() = withCache { cache ->
        val saved = successful(cache)
        val bytes = cache.readBytes()
        for (body in listOf("[]", "<html>blocked</html>", "{}", "[{}]")) {
            val result = TavernReleaseCatalog(cache, connect = { Response(it, body.toByteArray()) }).load()
            assertEquals(saved.toString(), result.toString())
            assertTrue(cache.readBytes().contentEquals(bytes))
        }
    }

    @Test
    fun httpErrorsAndRedirectsFallbackWithoutReadingTheResponseOrFollowingAnotherHost() = withCache { cache ->
        for (status in listOf(301, 302, 403, 404, 429, 500)) {
            val responses = mutableListOf<Response>()
            assertBranch(TavernReleaseCatalog(cache, connect = {
                Response(it, byteArrayOf(), status).also(responses::add)
            }).load())
            assertEquals(1, responses.size)
            assertFalse(responses.single().opened)
            assertTrue(responses.single().disconnected)
        }
    }

    @Test
    fun boundsKnownAndUnknownResponseSizesAndDetectsTruncation() = withCache { cache ->
        val responses = mutableListOf<Response>()
        assertBranch(TavernReleaseCatalog(cache, connect = {
            Response(it, byteArrayOf(), length = TavernReleaseCatalog.MAX_BYTES + 1L).also(responses::add)
        }).load())
        assertFalse(responses.single().opened)
        assertBranch(TavernReleaseCatalog(cache, connect = {
            Response(it, ByteArray(TavernReleaseCatalog.MAX_BYTES + 1), length = -1)
        }).load())
        assertBranch(TavernReleaseCatalog(cache, connect = {
            Response(it, JSONArray().put(apiRecord()).toString().toByteArray(), length = 1)
        }).load())
        assertFalse(cache.exists())
    }

    @Test
    fun unknownResponseLengthCanStillProduceAUsableOfficialList() = withCache { cache ->
        val result = TavernReleaseCatalog(cache, connect = {
            Response(it, JSONArray().put(apiRecord()).toString().toByteArray(), length = -1)
        }).load()
        assertEquals("1.12.0", result.getJSONObject(0).getString("tag"))
    }

    @Test
    fun filtersPrivateCredentialBearingMismatchedAndMalformedArchiveRecords() = withCache { cache ->
        val records = JSONArray()
        for (url in listOf(
            "https://api.github.com/repos/Private/repo/zipball/1.12.0",
            "https://api.github.com/repos/SillyTavern/SillyTavern/zipball/1.12.0?token=secret",
            "https://user:secret@api.github.com/repos/SillyTavern/SillyTavern/zipball/1.12.0",
            "https://api.github.com/repos/SillyTavern/SillyTavern/zipball/1.13.0",
            "https://ghfast.top/https://github.com/SillyTavern/SillyTavern/archive/1.12.0.zip"
        )) records.put(apiRecord().put("zipball_url", url))
        records.put(apiRecord("x".repeat(161)))
        records.put(apiRecord("../bad"))
        records.put(apiRecord().put("name", "name\nwith control"))
        records.put(apiRecord().put("prerelease", "false"))
        records.put(apiRecord().put("published_at", "not a timestamp"))
        records.put(apiRecord().put("draft", true))
        records.put(apiRecord("1.14.0"))
        val result = successful(cache, records)
        assertEquals(1, result.length())
        assertEquals("1.14.0", result.getJSONObject(0).getString("tag"))
        assertFalse(cache.readText().contains("secret"))
    }

    @Test
    fun preservesOfficialOrderingAndPrereleasesButRemovesDuplicateTags() = withCache { cache ->
        val result = successful(cache, JSONArray()
            .put(apiRecord("1.14.0-rc1").put("prerelease", true))
            .put(apiRecord("1.13.0"))
            .put(apiRecord("1.13.0").put("name", "duplicate")))
        assertEquals(2, result.length())
        assertEquals("1.14.0-rc1", result.getJSONObject(0).getString("tag"))
        assertTrue(result.getJSONObject(0).getBoolean("prerelease"))
        assertEquals("1.13.0", result.getJSONObject(1).getString("tag"))
    }

    @Test
    fun malformedOversizedAndUnsafeCachedListsFallbackToTheStableBranch() = withCache { cache ->
        cache.parentFile!!.mkdirs()
        for (text in listOf("not json", "[]", "x".repeat(TavernReleaseCatalog.MAX_BYTES + 1),
            """[{"tag":"1.12.0","name":"cached","zipballUrl":"https://private.test/archive.zip","prerelease":false}]""")) {
            cache.writeText(text)
            assertBranch(offline(cache))
        }
    }

    @Test
    fun unavailableCacheStorageDoesNotHideUsableOfficialResults() = withCache { cache ->
        cache.parentFile!!.parentFile!!.mkdirs()
        cache.parentFile!!.writeText("blocks directory creation")
        val result = successful(cache)
        assertEquals("1.12.0", result.getJSONObject(0).getString("tag"))
        assertFalse(cache.exists())
    }

    @Test
    fun responseDeadlineFallsBackInsteadOfAcceptingAStalledSuccessfulResponse() = withCache { cache ->
        var now = 0L
        assertBranch(TavernReleaseCatalog(cache, connect = {
            now += TimeUnit.SECONDS.toNanos(11)
            Response(it, JSONArray().put(apiRecord()).toString().toByteArray())
        }, nanoTime = { now }).load())
        assertFalse(cache.exists())
    }

    @Test
    fun interruptedLookupDoesNotConnectOrReturnFallbackAsSuccess() = withCache { cache ->
        try {
            Thread.currentThread().interrupt()
            assertThrows(CancellationException::class.java) {
                TavernReleaseCatalog(cache, connect = { throw AssertionError("Must not connect") }).load()
            }
            assertFalse(cache.exists())
        } finally { Thread.interrupted() }
    }
}
