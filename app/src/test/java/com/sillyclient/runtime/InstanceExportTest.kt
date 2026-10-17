package com.sillyclient.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
}
