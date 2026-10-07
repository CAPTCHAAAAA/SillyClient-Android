package com.sillyclient.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledTavernSourceTest {
    private fun manifest(version: Any = "1.24.3", name: String = "sillytavern"): String =
        JSONObject().put("name", name).put("version", version).toString()

    private fun archive(vararg entries: Pair<String, String>, stored: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in entries) {
                val content = text.toByteArray()
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = content.size.toLong()
                    entry.compressedSize = entry.size
                    entry.crc = CRC32().apply { update(content) }.value
                }
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun readsTheActualFlatOrWrappedRootManifestOnceWithoutHardcodedVersions() {
        for (prefix in listOf("", "SillyTavern-release/", "SillyTavern-tag/")) {
            val bytes = archive("${prefix}README.md" to "source", "${prefix}package.json" to manifest("9.8.7"))
            var opens = 0
            val source = BundledTavernSource(openAsset = { path ->
                assertEquals(BundledTavernSource.ASSET_PATH, path)
                opens++
                ByteArrayInputStream(bytes)
            })
            assertEquals("9.8.7", source.version)
            assertEquals("9.8.7", source.version)
            assertTrue(source.matchesRequestedVersion("9.8.7", "https://example.test/unused.zip"))
            assertEquals(1, opens)
        }
    }

    @Test
    fun shippedApkSourceVersionMatchesItsRealZipManifest() {
        val assetRoot = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .firstOrNull { File(it, BundledTavernSource.ASSET_PATH).isFile }
        assertNotNull("The build must include the real bundled source ZIP", assetRoot)
        val assets = requireNotNull(assetRoot)
        val expected = ZipFile(File(assets, BundledTavernSource.ASSET_PATH)).use { zip ->
            val entry = zip.entries().asSequence().single {
                !it.isDirectory && it.name.substringAfterLast('/') == "package.json" &&
                    it.name.count { char -> char == '/' } <= 1
            }
            val document = zip.getInputStream(entry).bufferedReader().use { JSONObject(it.readText()) }
            assertEquals("sillytavern", document.getString("name"))
            document.getString("version")
        }
        val source = BundledTavernSource({ File(assets, it).inputStream() })
        assertNotNull("The packaged root manifest must be readable within the scan budget", source.version)
        assertEquals(expected, source.version)
        assertTrue(source.matchesRequestedVersion(expected, null))
        assertFalse(source.matchesRequestedVersion("release", TavernReleaseCatalog.STABLE_BRANCH_URL))
    }

    @Test
    fun onlyTheExactBundledVersionOrLegacyNativeStableDefaultUsesTheAsset() {
        val bytes = archive("package.json" to manifest())
        val source = BundledTavernSource({ ByteArrayInputStream(bytes) })
        assertTrue(source.matchesRequestedVersion("1.24.3", null))
        assertTrue(source.matchesRequestedVersion("1.24.3", "https://github.com/SillyTavern/SillyTavern/archive/refs/tags/1.24.3.zip"))
        assertTrue(source.matchesRequestedVersion("stable", null))
        assertTrue(source.matchesRequestedVersion("stable", ""))
        for (request in listOf("release", "staging", "latest", "v1.24.3", "1.24.2", "1.24.4", " 1.24.3")) {
            assertFalse(request, source.matchesRequestedVersion(request, null))
        }
        assertFalse(source.matchesRequestedVersion("release", TavernReleaseCatalog.STABLE_BRANCH_URL))
        assertFalse(source.matchesRequestedVersion("stable", TavernReleaseCatalog.STABLE_BRANCH_URL))
    }

    @Test
    fun missingArchivesRemainUnavailableAndDoNotLeakExceptionDetails() {
        val diagnostics = mutableListOf<String>()
        var opens = 0
        val source = BundledTavernSource({ opens++; throw IOException("token=private-secret") }, diagnostics::add)
        assertNull(source.version)
        assertFalse(source.matchesRequestedVersion("stable", null))
        assertEquals(1, opens)
        assertEquals(listOf("bundled.source.unavailable type=IOException"), diagnostics)
    }

    @Test
    fun invalidPackageNamesVersionsAndNestedManifestsDoNotInventAnOfficialRelease() {
        val manifests = listOf("{}", "broken", "[]", manifest("1.2"), manifest("v1.2.3"),
            manifest("1.2.3-rc.1"), manifest("1.2.3+local"), manifest("01.2.3"), manifest("1.2.3\n"),
            manifest(123), manifest("1.2.3", "other-package"))
        for (text in manifests) {
            val bytes = archive("package.json" to text)
            assertNull(text, BundledTavernSource({ ByteArrayInputStream(bytes) }).version)
        }
        for (path in listOf("node_modules/package.json", "src/package.json", "public/package.json",
            "wrapper/node_modules/nested/package.json", "../package.json", "/package.json")) {
            val bytes = archive(path to manifest())
            assertNull(path, BundledTavernSource({ ByteArrayInputStream(bytes) }).version)
        }
        val mixed = archive("other/file.txt" to "not a wrapper", "wrapper/package.json" to manifest())
        assertNull(BundledTavernSource({ ByteArrayInputStream(mixed) }).version)
    }

    @Test
    fun corruptTruncatedAndOversizedManifestDataIsRejected() {
        val original = archive("package.json" to manifest(), stored = true)
        val corrupted = original.copyOf()
        corrupted[30 + "package.json".length] = '!'.code.toByte()
        val oversized = archive("package.json" to (" ".repeat(BundledTavernSource.MAX_MANIFEST_BYTES) + manifest()))
        for (bytes in listOf(byteArrayOf(), "not a ZIP".toByteArray(), original.copyOf(40), corrupted, oversized)) {
            assertNull(BundledTavernSource({ ByteArrayInputStream(bytes) }).version)
        }
    }

    @Test
    fun refusesExcessiveEntriesBeforeTheRootManifest() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            repeat(BundledTavernSource.MAX_ENTRIES) { index ->
                zip.putNextEntry(ZipEntry("wrapper/file-$index"))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("wrapper/package.json"))
            zip.write(manifest().toByteArray())
            zip.closeEntry()
        }
        assertNull(BundledTavernSource({ ByteArrayInputStream(bytes.toByteArray()) }).version)
    }

    @Test
    fun closesAssetStreamsAndStopsAfterTheBoundedRootManifestRead() {
        var closed = false
        val bytes = archive("package.json" to manifest(), "large-after-manifest" to "x".repeat(100_000), stored = true)
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        val source = BundledTavernSource({ input })
        assertEquals("1.24.3", source.version)
        assertTrue(closed)
        assertTrue(input.available() > 50_000)
    }

    @Test
    fun cancellationDoesNotBecomeACachedMissingVersion() {
        var opens = 0
        val bytes = archive("package.json" to manifest())
        val source = BundledTavernSource({ opens++; ByteArrayInputStream(bytes) })
        try {
            Thread.currentThread().interrupt()
            assertThrows(CancellationException::class.java) { source.version }
            assertEquals(0, opens)
        } finally { Thread.interrupted() }
        assertEquals("1.24.3", source.version)
        assertEquals(1, opens)
    }
}
