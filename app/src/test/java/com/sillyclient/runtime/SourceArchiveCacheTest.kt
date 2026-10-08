package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceArchiveCacheTest {
    private fun withCache(maxEntries: Int = SourceArchiveCache.DEFAULT_MAX_ENTRIES, test: (SourceArchiveCache, File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent != null) Files.createTempDirectory(parent.toPath(), "source-cache-").toFile()
            else Files.createTempDirectory("source-cache-").toFile()
        try { test(SourceArchiveCache(File(root, "source-archives"), maxEntries), root) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    /** Downloads land in the same volume as the cache, mirroring the runtime's private tmpDir. */
    private fun download(root: File, name: String, content: String): File =
        File(root, name).apply { writeText(content) }

    private fun keyOf(url: String): String = SourceArchiveCache.urlKey(url)

    @Test
    fun storedArchiveIsReturnedWithItsContentDigestSealedInTheName() = withCache { cache, root ->
        val url = keyOf("https://example.com/sillytavern-1.18.0.zip")
        val downloaded = download(root, "download-a.zip", "payload")
        val sealed = cache.store(url, downloaded)
        assertNotNull(sealed)
        assertTrue(sealed!!.name.startsWith("$url-") && sealed.name.endsWith(".zip"))
        assertTrue(!downloaded.exists())
        assertEquals(sealed.canonicalFile, cache.cachedArchive(url)!!.canonicalFile)
    }

    @Test
    fun sameUrlRestoreConsumesANewDownloadOfIdenticalContent() = withCache { cache, root ->
        val url = keyOf("https://example.com/sillytavern-1.18.0.zip")
        cache.store(url, download(root, "download-1.zip", "payload"))
        val second = download(root, "download-2.zip", "payload")
        val sealed = cache.store(url, second)
        assertNotNull(sealed)
        assertTrue(!second.exists())
        assertEquals(sealed!!.canonicalFile, cache.cachedArchive(url)!!.canonicalFile)
    }

    @Test
    fun tamperedCacheEntriesAreDroppedInsteadOfServed() = withCache { cache, root ->
        val url = keyOf("https://example.com/sillytavern-1.18.0.zip")
        val sealed = cache.store(url, download(root, "download-a.zip", "payload"))!!
        sealed.writeText("tampered")
        assertNull(cache.cachedArchive(url))
        assertTrue(!sealed.exists())
    }

    @Test
    fun cacheStaysBoundedToMaxEntriesByRecency() = withCache(maxEntries = 1) { cache, root ->
        val firstKey = keyOf("https://example.com/a.zip")
        val secondKey = keyOf("https://example.com/b.zip")
        assertNotNull(cache.store(firstKey, download(root, "download-a.zip", "a")))
        Thread.sleep(5)
        assertNotNull(cache.store(secondKey, download(root, "download-b.zip", "b")))
        assertNull(cache.cachedArchive(firstKey))
        assertNotNull(cache.cachedArchive(secondKey))
    }

    @Test
    fun unknownKeysAndEmptyArchivesAreRejected() = withCache { cache, root ->
        val downloaded = download(root, "download-a.zip", "payload")
        assertNull(cache.store("not-a-key", downloaded))
        val url = keyOf("https://example.com/empty.zip")
        downloaded.writeText("")
        assertNull(cache.store(url, downloaded))
        assertTrue(downloaded.exists())
    }
}
