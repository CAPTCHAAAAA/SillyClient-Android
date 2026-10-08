package com.sillyclient.runtime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UstarArchiveTest {
    private fun withRoot(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent != null) {
            parent.mkdirs()
            Files.createTempDirectory(parent.toPath(), "ustar-archive-").toFile()
        } else Files.createTempDirectory("ustar-archive-").toFile()
        try { test(root) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    private fun entry(
        name: String,
        content: ByteArray = ByteArray(0),
        isDirectory: Boolean = false,
        isSymbolicLink: Boolean = false,
        linkTarget: String = "",
        mode: Int = 0b110_100_100,
        size: Long = content.size.toLong()
    ) = UstarArchive.Entry(name, isDirectory, isSymbolicLink, linkTarget, mode, size) {
        ByteArrayInputStream(content)
    }

    private class RecordingVisitor : UstarArchive.Visitor {
        val names = mutableListOf<String>()
        val directories = mutableSetOf<String>()
        val links = mutableMapOf<String, String>()
        val files = mutableMapOf<String, ByteArray>()
        override fun directory(name: String, mode: Int) { names += name; directories += name }
        override fun file(name: String, mode: Int, size: Long, content: java.io.InputStream) {
            names += name
            files[name] = content.readBytes().also { assertEquals(size, it.size.toLong()) }
        }
        override fun symbolicLink(name: String, target: String, mode: Int) {
            names += name; links[name] = target
        }
    }

    @Test
    fun readsPythonGnuArchivesProducedForBundledDependencies() {
        val archive = javaClass.classLoader
            ?.getResourceAsStream("interop/gnu-python-deps.tar")
            ?: return // resource filtering keeps sample out of some build variants
        val temp = Files.createTempFile("interop-", ".tar").toFile()
        try {
            archive.use { input -> temp.outputStream().use { input.copyTo(it) } }
            val visitor = RecordingVisitor()
            assertEquals(4, UstarArchive.read(temp, visitor))
            assertTrue("yaml" in visitor.directories)
            assertEquals("""{"name":"yaml"}""", String(visitor.files.getValue("yaml/package.json")))
            val longName = "@agnai/sentencepiece-js/" + "x".repeat(80) + "/tokenizers/very-long-entry-name.bin"
            assertEquals("long-name-content", String(visitor.files.getValue(longName)))
            assertEquals("#!/bin/sh", String(visitor.files.getValue("scripts/run.sh")))
        } finally {
            temp.delete()
        }
    }

    @Test
    fun roundTripsFilesDirectoriesAndLinksAcrossBlockBoundaries() = withRoot { root ->
        val payload = ByteArray(1200) { (it % 251).toByte() }
        val archive = File(root, "tree.tar")
        val entries = listOf(
            entry("yaml", isDirectory = true, mode = 0b111_101_101),
            entry("yaml/package.json", """{"name":"yaml"}""".toByteArray()),
            entry("@scope/parser/package.json", """{"scoped":true}""".toByteArray()),
            entry("odd.bin", ByteArray(513) { 1 }),
            entry("exact-block.bin", ByteArray(512) { 2 }),
            entry("big.dat", payload),
            entry("link", isSymbolicLink = true, linkTarget = "yaml/package.json")
        )
        val bytes = UstarArchive.write(archive, entries.asSequence())
        assertTrue(bytes > 0)
        assertTrue(bytes % UstarArchive.BLOCK_BYTES == 0L)
        val visitor = RecordingVisitor()
        assertEquals(entries.size, UstarArchive.read(archive, visitor))
        assertEquals(entries.map { it.name }, visitor.names)
        assertArrayEquals(payload, visitor.files["big.dat"])
        assertArrayEquals("""{"scoped":true}""".toByteArray(), visitor.files["@scope/parser/package.json"])
        assertArrayEquals(ByteArray(513) { 1 }, visitor.files["odd.bin"])
        assertEquals("yaml/package.json", visitor.links["link"])
    }

    @Test
    fun gnuLongNamesAndLongLinkTargetsRoundTrip() = withRoot { root ->
        val longName = (1..70).joinToString("/") { "node$it" } + "/deep-file.txt"
        assertTrue(longName.length > 255)
        val longTarget = (1..40).joinToString("/") { "hop$it" }
        assertTrue(longTarget.length > 100)
        val archive = File(root, "long.tar")
        val bytes = UstarArchive.write(archive, listOf(
            entry(longName, "deep".toByteArray()),
            entry("long-link", isSymbolicLink = true, linkTarget = longTarget)
        ).asSequence())
        assertTrue(bytes > 0)
        val visitor = RecordingVisitor()
        assertEquals(2, UstarArchive.read(archive, visitor))
        assertEquals("deep", String(visitor.files[longName]!!))
        assertEquals(longTarget, visitor.links["long-link"])
    }

    @Test
    fun corruptedHeadersAreRejected() = withRoot { root ->
        val archive = File(root, "corrupt.tar")
        UstarArchive.write(archive, listOf(entry("a.txt", "hello".toByteArray())).asSequence())
        val raw = archive.readBytes()
        raw[3] = (raw[3].toInt() xor 0x40).toByte()  // break the checksum
        archive.writeBytes(raw)
        assertThrows(IOException::class.java) { UstarArchive.read(archive, RecordingVisitor()) }
    }

    @Test
    fun truncatedArchivesAreRejected() = withRoot { root ->
        val archive = File(root, "truncated.tar")
        UstarArchive.write(archive, listOf(entry("a.txt", "hello".toByteArray())).asSequence())
        archive.writeBytes(archive.readBytes().copyOf(400))
        assertThrows(IOException::class.java) { UstarArchive.read(archive, RecordingVisitor()) }
    }

    @Test
    fun entryNamesOutsideTheTreeAreRejected() {
        for (name in listOf("", ".", "..", "a/../b", "../escape", "/absolute", "C:/windows",
            "a//b", "a/./b", "\u0000", "back\\slash")) {
            assertNull(name, UstarArchive.safeEntryPath(name))
        }
        assertEquals("a", UstarArchive.safeEntryPath("a/"))
        for (name in listOf("yaml/package.json", "@scope/pkg", "说明.md", "a:b")) {
            assertEquals(name, UstarArchive.safeEntryPath(name))
        }
    }
}
