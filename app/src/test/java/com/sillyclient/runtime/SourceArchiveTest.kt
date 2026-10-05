package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SourceArchiveTest {
    private fun withRoot(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent == null) Files.createTempDirectory("source-archive-").toFile()
            else Files.createTempDirectory(parent.toPath(), "source-archive-").toFile()
        try { test(root) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            if (root.exists()) ManagedFiles.deleteDirectory(root, requireNotNull(root.parentFile))
        }
    }

    private fun archive(root: File, vararg entries: Pair<String, String>): File =
        File(root, "source.zip").apply {
            ZipOutputStream(outputStream()).use { zip ->
                entries.forEach { (name, text) ->
                    val data = text.toByteArray()
                    zip.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(data) }.value
                    })
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }

    private fun extract(archive: File, destination: File, progress: (Int) -> Unit = {}): Int =
        SourceArchive.extract(archive, destination, {}, progress)

    @Test
    fun stripsARealWrapperAndReportsOnlyValidatedFiles() = withRoot { root ->
        val zip = archive(root, "SillyTavern-release/" to "", "SillyTavern-release/server.js" to "server",
            "SillyTavern-release/public/index.html" to "page")
        val destination = File(root, "destination")
        val progress = mutableListOf<Int>()
        assertEquals(2, extract(zip, destination) { progress.add(it) })
        assertEquals("server", File(destination, "server.js").readText())
        assertEquals("page", File(destination, "public/index.html").readText())
        assertFalse(File(destination, "SillyTavern-release").exists())
        assertEquals(listOf(1, 2), progress)
    }

    @Test
    fun keepsUnwrappedSourcePaths() = withRoot { root ->
        val zip = archive(root, "server.js" to "server", "package.json" to "{}", "src/main.js" to "module")
        val destination = File(root, "destination")
        assertEquals(3, extract(zip, destination))
        assertEquals("server", File(destination, "server.js").readText())
        assertTrue(File(destination, "src/main.js").isFile)
    }

    @Test
    fun keepsTheDataRootOfADataOnlyBackup() = withRoot { root ->
        val zip = archive(root, "data/" to "", "data/default-user/settings.json" to "settings")
        val destination = File(root, "destination")
        assertEquals(1, extract(zip, destination))
        assertEquals("settings", File(destination, "data/default-user/settings.json").readText())
        assertFalse(File(destination, "default-user").exists())
    }

    @Test
    fun stripsABackupWrapperButKeepsItsDataDirectory() = withRoot { root ->
        val zip = archive(root, "backup/data/default-user/chats/test.jsonl" to "history")
        val destination = File(root, "destination")
        assertEquals(1, extract(zip, destination))
        assertEquals("history", File(destination, "data/default-user/chats/test.jsonl").readText())
    }

    @Test
    fun aSingleUserDirectoryIsNotAssumedToBeAnArchiveWrapper() = withRoot { root ->
        val zip = archive(root, "characters/character.png" to "character")
        val destination = File(root, "destination")
        extract(zip, destination)
        assertEquals("character", File(destination, "characters/character.png").readText())
    }

    @Test
    fun rejectsUnsafePathsBeforeWritingAnyArchiveFiles() = withRoot { root ->
        for (name in listOf("../escape", "/absolute", "root/../escape", "root/./file", "root//file",
            "C:/file", "root\\file", "root/control\nfile")) {
            val zip = archive(root, "server.js" to "server", name to "unsafe")
            val destination = File(root, "destination")
            assertThrows(IllegalArgumentException::class.java) { extract(zip, destination) }
            assertFalse(destination.exists())
        }
    }

    @Test
    fun rejectsDuplicateCaseAliasesAndParentFileConflictsBeforeWriting() = withRoot { root ->
        val destination = File(root, "destination")
        assertThrows(IllegalArgumentException::class.java) {
            extract(archive(root, "server.js" to "server", "SERVER.JS" to "second"), destination)
        }
        assertFalse(destination.exists())
        assertThrows(IllegalArgumentException::class.java) {
            extract(archive(root, "data" to "file", "data/chat.jsonl" to "history"), destination)
        }
        assertFalse(destination.exists())
    }

    @Test
    fun preservesExistingFilesAndRefusesToOverwriteThem() = withRoot { root ->
        val destination = File(root, "destination").apply { mkdirs() }
        val existing = File(destination, "package.json").apply { writeText("keep") }
        val zip = archive(root, "server.js" to "server", "package.json" to "replacement")
        assertThrows(IllegalArgumentException::class.java) { extract(zip, destination) }
        assertEquals("keep", existing.readText())
        assertFalse(File(destination, "server.js").exists())
    }

    @Test
    fun excludesImportedHostIdentityAndTransactionMarkers() = withRoot { root ->
        val destination = File(root, "destination").apply { mkdirs() }
        val marker = File(destination, ".sillyclient-install-current").apply { writeText("current") }
        val zip = archive(root, "server.js" to "server", ".sc-identity" to "old identity",
            InstanceInstaller.DEPENDENCY_MARKER to "old pending", ".sillyclient-install-current" to "old owner")
        assertEquals(1, extract(zip, destination))
        assertEquals("current", marker.readText())
        assertFalse(File(destination, ".sc-identity").exists())
        assertFalse(File(destination, InstanceInstaller.DEPENDENCY_MARKER).exists())
    }

    @Test
    fun metadataLimitsAreEnforcedBeforeDestinationCreation() = withRoot { root ->
        val zip = archive(root, "first.txt" to "1234", "second.txt" to "5678")
        val destination = File(root, "destination")
        for (limits in listOf(SourceArchive.Limits(entries = 1), SourceArchive.Limits(fileBytes = 3),
            SourceArchive.Limits(expandedBytes = 7), SourceArchive.Limits(archiveBytes = zip.length() - 1))) {
            assertThrows(IllegalArgumentException::class.java) {
                SourceArchive.extractBounded(zip, destination, {}, {}, limits)
            }
            assertFalse(destination.exists())
        }
    }

    @Test
    fun corruptedStoredContentCannotReportAFileAsComplete() = withRoot { root ->
        val zip = archive(root, "server.js" to "uniquepayload")
        val bytes = zip.readBytes()
        val payload = "uniquepayload".toByteArray()
        val offset = (0..bytes.size - payload.size).first { start ->
            payload.indices.all { index -> bytes[start + index] == payload[index] }
        }
        bytes[offset] = 'X'.code.toByte()
        zip.writeBytes(bytes)
        val progress = mutableListOf<Int>()
        assertThrows(IllegalArgumentException::class.java) {
            extract(zip, File(root, "destination")) { progress.add(it) }
        }
        assertTrue(progress.isEmpty())
    }

    @Test
    fun cancellationDuringMetadataValidationDoesNotCreateDestination() = withRoot { root ->
        val zip = archive(root, "server.js" to "server")
        val destination = File(root, "destination")
        var checks = 0
        assertThrows(CancellationException::class.java) {
            SourceArchive.extract(zip, destination, { if (++checks == 2) throw CancellationException() }, {})
        }
        assertFalse(destination.exists())
    }

    @Test
    fun cancellationAfterAFileStopsBeforeTheNextEntry() = withRoot { root ->
        val zip = archive(root, "server.js" to "server", "package.json" to "{}")
        val destination = File(root, "destination")
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            SourceArchive.extract(zip, destination, { if (cancelled) throw CancellationException() }, { cancelled = true })
        }
        assertEquals("server", File(destination, "server.js").readText())
        assertFalse(File(destination, "package.json").exists())
    }

    @Test
    fun extractionNeverFollowsAnExistingDestinationSymlink() = withRoot { root ->
        val destination = File(root, "destination").apply { mkdirs() }
        val outside = File(root, "outside").apply { mkdirs() }
        val link = File(destination, "data")
        assumeTrue("Host does not permit creating symbolic links",
            runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess)
        val zip = archive(root, "server.js" to "server", "data/chat.jsonl" to "history")
        assertThrows(IllegalArgumentException::class.java) { extract(zip, destination) }
        assertFalse(File(outside, "chat.jsonl").exists())
        assertFalse(File(destination, "server.js").exists())
        Files.delete(link.toPath())
    }
}
