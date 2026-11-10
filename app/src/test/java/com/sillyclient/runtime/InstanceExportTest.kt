package com.sillyclient.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class InstanceExportTest {
    private fun withRoot(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent == null) Files.createTempDirectory("instance-export-").toFile()
            else Files.createTempDirectory(parent.toPath(), "instance-export-").toFile()
        try { test(root) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            ManagedFiles.deleteDirectory(root, parent?.canonicalFile ?: root.parentFile!!.canonicalFile)
        }
    }

    private fun sampleInstance(root: File, name: String = "TestInstance"): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "server.js").writeText("// Tavern server entry")
        File(dir, "package.json").writeText("""{"name": "$name", "version": "1.0.0"}""")
        val dataDir = File(dir, "data").apply { mkdirs() }
        File(dataDir, "settings.json").writeText("""{"theme": "dark"}""")
        val subDir = File(dir, "public/scripts").apply { mkdirs() }
        File(subDir, "main.js").writeText("console.log('hello');")
        return dir
    }

    @Test
    fun exportsInstanceToStreamSuccessfully() = withRoot { root ->
        val instance = sampleInstance(root)
        val outStream = ByteArrayOutputStream()
        val (entries, bytes) = InstanceExport.exportToStream(instance, outStream)

        assertTrue("Entries count should be > 0", entries >= 4)
        assertTrue("Byte count should be > 0", bytes > 0)

        // Verify the generated zip contents
        val zipEntries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(outStream.toByteArray())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    zipEntries[entry.name] = zis.bufferedReader().readText()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        assertEquals("// Tavern server entry", zipEntries["server.js"])
        assertEquals("""{"theme": "dark"}""", zipEntries["data/settings.json"])
        assertEquals("console.log('hello');", zipEntries["public/scripts/main.js"])
    }

    @Test
    fun exportsInstanceToFileInSpecifiedDirectory() = withRoot { root ->
        val instance = sampleInstance(root, "MyTavern")
        val exportTargetDir = File(root, "UserChosenExportFolder").apply { mkdirs() }

        val result = InstanceExport.exportToFile(instance, exportTargetDir)
        val exportedFile = File(result.destinationPath)

        assertTrue(exportedFile.exists())
        assertEquals(exportTargetDir.canonicalPath, exportedFile.parentFile!!.canonicalPath)
        assertTrue(exportedFile.name.startsWith("MyTavern-"))
        assertTrue(exportedFile.name.endsWith(".zip"))
        assertTrue(result.bytes > 0)
        assertTrue(result.entryCount >= 4)
    }

    @Test
    fun exportsInstanceToExactFile() = withRoot { root ->
        val instance = sampleInstance(root, "ExactInstance")
        val exactTargetFile = File(root, "custom-backups/custom-name.zip")

        val result = InstanceExport.exportToFile(instance, exactTargetFile)
        val exportedFile = File(result.destinationPath)

        assertTrue(exportedFile.exists())
        assertEquals(exactTargetFile.canonicalPath, exportedFile.canonicalPath)
        assertTrue(result.bytes > 0)
    }

    @Test
    fun rejectsExportIfServerJsIsMissing() = withRoot { root ->
        val incompleteDir = File(root, "BrokenInstance").apply { mkdirs() }
        File(incompleteDir, "package.json").writeText("{}")

        assertThrows(IllegalArgumentException::class.java) {
            InstanceExport.exportToStream(incompleteDir, ByteArrayOutputStream())
        }
    }

    @Test
    fun copiesMultipleFilesWithTheSameProvidedBuffer() {
        val firstBytes = ByteArray(513) { it.toByte() }
        val secondBytes = ByteArray(913) { (it * 3).toByte() }
        val first = RecordingInput(firstBytes)
        val second = RecordingInput(secondBytes)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(17)

        first.use { InstanceExport.copyFile(it, output, buffer) }
        second.use { InstanceExport.copyFile(it, output, buffer) }

        assertArrayEquals(firstBytes + secondBytes, output.toByteArray())
        assertSame(buffer, first.readBuffers.first())
        assertSame(buffer, second.readBuffers.first())
        assertTrue((first.readBuffers + second.readBuffers).all { it === buffer })
    }

    @Test
    fun manySmallFilesPreserveContentsStatisticsAndProgress() = withRoot { root ->
        val instance = sampleInstance(root)
        val expected = linkedMapOf(
            "server.js" to "// Tavern server entry",
            "package.json" to """{"name": "TestInstance", "version": "1.0.0"}""",
            "data/settings.json" to """{"theme": "dark"}""",
            "public/scripts/main.js" to "console.log('hello');"
        )
        repeat(205) { index ->
            val path = "data/chats/$index.jsonl"
            val content = """{"mes":"message $index"}"""
            File(instance, path).apply { parentFile!!.mkdirs(); writeText(content) }
            expected[path] = content
        }
        val progress = mutableListOf<Int>()
        val output = ByteArrayOutputStream()

        val (entries, bytes) = InstanceExport.exportToStream(instance, output, progress::add)

        assertEquals(expected.size, entries)
        assertEquals(expected.values.sumOf { it.toByteArray().size.toLong() }, bytes)
        assertEquals(listOf(200), progress)
        val actual = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) actual[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun interruptionBetweenChunksStopsReadingAndOwnerClosesStreams() {
        val buffer = ByteArray(1024)
        val input = RecordingInput(ByteArray(buffer.size * 3))
        val output = RecordingOutput(interruptOnWrite = true)
        try {
            assertThrows(CancellationException::class.java) {
                input.use { source ->
                    output.use { destination -> InstanceExport.copyFile(source, destination, buffer) }
                }
            }
        } finally {
            Thread.interrupted()
        }

        assertEquals(1, input.readBuffers.size)
        assertEquals(buffer.size, output.size())
        assertTrue(input.closed)
        assertTrue(output.closed)
    }

    @Test
    fun interruptionDuringExportClosesOutputAndPreservesSource() = withRoot { root ->
        val instance = sampleInstance(root)
        val original = Random(123).nextBytes(2 * 1024 * 1024)
        val file = File(instance, "data/large.bin").apply { writeBytes(original) }
        val output = RecordingOutput(interruptOnWrite = true)
        try {
            assertThrows(CancellationException::class.java) {
                InstanceExport.exportToStream(instance, output)
            }
        } finally {
            Thread.interrupted()
        }

        assertTrue(output.closed)
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun explicitSourceFileDestinationIsRejectedBeforeTruncation() = withRoot { root ->
        val instance = sampleInstance(root)
        val source = File(instance, "server.js")
        val original = source.readText()

        assertThrows(IllegalArgumentException::class.java) {
            InstanceExport.exportToFile(instance, source)
        }

        assertEquals(original, source.readText())
    }

    @Test
    fun explicitDestinationInsideInstanceIsRejectedBeforeCreatingDirectories() = withRoot { root ->
        val instance = sampleInstance(root)
        val target = File(instance, "data/backups/new.zip")

        assertThrows(IllegalArgumentException::class.java) {
            InstanceExport.exportToFile(instance, target)
        }
        assertFalse(target.parentFile!!.exists())
        assertThrows(IllegalArgumentException::class.java) {
            InstanceExport.exportToFile(instance, File(instance, "data"))
        }
        assertEquals(listOf("settings.json"), File(instance, "data").listFiles()!!.map { it.name })
    }

    @Test
    fun linkedDirectoriesAreNotTraversedAndLinkedSourceDestinationsAreRejected() = withRoot { root ->
        val instance = sampleInstance(root)
        val outside = File(root, "outside").apply { mkdirs() }
        val privateFile = File(outside, "private.txt").apply { writeText("must stay outside the archive") }
        val directoryLink = File(instance, "linked-directory")
        assumeTrue("Symbolic links are not available on this host",
            runCatching { Files.createSymbolicLink(directoryLink.toPath(), outside.toPath()) }.isSuccess)
        val fileLink = File(instance, "linked-file")
        assumeTrue("Symbolic links are not available on this host",
            runCatching { Files.createSymbolicLink(fileLink.toPath(), privateFile.toPath()) }.isSuccess)
        val instanceAlias = File(root, "instance-alias")
        assumeTrue("Symbolic links are not available on this host",
            runCatching { Files.createSymbolicLink(instanceAlias.toPath(), instance.toPath()) }.isSuccess)
        val output = ByteArrayOutputStream()

        val (entries, _) = InstanceExport.exportToStream(instance, output)

        assertEquals(4, entries)
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                assertFalse(entry.name.startsWith("linked-directory"))
                assertFalse(entry.name == "linked-file")
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val original = File(instance, "server.js").readText()
        assertThrows(IllegalArgumentException::class.java) {
            InstanceExport.exportToFile(instance, File(instanceAlias, "server.js"))
        }
        assertEquals(original, File(instance, "server.js").readText())
        assertEquals("must stay outside the archive", privateFile.readText())
    }

    private class RecordingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val readBuffers = mutableListOf<ByteArray>()
        var closed = false
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readBuffers.add(buffer)
            return super.read(buffer, offset, length)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class RecordingOutput(private val interruptOnWrite: Boolean = false) : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            super.write(buffer, offset, length)
            if (interruptOnWrite) Thread.currentThread().interrupt()
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
