package com.sillyclient.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PreinstalledExtensionInstallerTest {
    private val commit = "f".repeat(40)
    private fun selectedId(id: String) = when (id) {
        "example", "first" -> "tavern-helper"
        "second" -> "dice"
        else -> id
    }
    private fun selectedFolder(name: String) = when (name) {
        "Example", "First" -> "JS-Slash-Runner"
        "Second" -> "Extension-Dice"
        else -> name
    }

    private fun withServer(action: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        val root = if (parent != null) {
            parent.mkdirs()
            Files.createTempDirectory(parent.toPath(), "extension-install-").toFile()
        } else Files.createTempDirectory("sillyclient-extensions").toFile()
        try {
            File(root, "package.json").writeText("""{"version":"1.19.0"}""")
            action(root)
        } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    private fun archive(name: String = "Example", extra: Map<String, String> = emptyMap(), manifest: String? = null): ByteArray {
        val content = linkedMapOf(
            "manifest.json" to (manifest ?: """{"display_name":"Example","version":"1.0.0","js":"dist/index.js","css":"style.css"}"""),
            "dist/index.js" to "console.log('example');",
            "style.css" to ".example { color: inherit; }",
            "LICENSE" to "Test fixture license"
        ) + extra
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((path, text) in content) {
                zip.putNextEntry(ZipEntry("${selectedFolder(name)}-$commit/$path"))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun record(id: String, name: String, bytes: ByteArray, minimum: String? = null): JSONObject =
        JSONObject().put("id", selectedId(id)).put("displayName", name)
            .put("repository", if (selectedId(id) == "dice") "SillyTavern/Extension-Dice" else "N0VI028/JS-Slash-Runner")
            .put("commit", commit).put("archiveSha256", sha(bytes)).put("archiveBytes", bytes.size)
            .put("licensePath", "LICENSE").also { if (minimum != null) it.put("minimumClientVersion", minimum) }

    private fun catalog(vararg records: JSONObject): String =
        JSONObject().put("revision", 1).put("extensions", JSONArray(records.toList())).toString()

    private fun install(
        server: File, catalog: String, ids: List<String>, archives: Map<String, ByteArray>,
        ensureActive: () -> Unit = {}, logs: MutableList<String> = mutableListOf()
    ): PreinstalledExtensionsTransaction = PreinstalledExtensionInstaller.installInternal(
        catalog, server, PreinstalledExtensionsRequest(1, ids.map(::selectedId)), ensureActive, logs::add,
        download = { extension, destination ->
            val sourceId = archives.keys.first { selectedId(it) == extension.id }
            destination.writeBytes(archives.getValue(sourceId))
        }
    )

    @Test
    fun installsCompiledAssetsAndLicenseInTheOfficialUserExtensionDirectory() = withServer { server ->
        val bytes = archive()
        val transaction = install(server, catalog(record("example", "Example", bytes)), listOf("example"), mapOf("example" to bytes))
        val target = File(server, "data/default-user/extensions/JS-Slash-Runner")
        assertTrue(File(target, "dist/index.js").isFile)
        assertTrue(File(target, "LICENSE").isFile)
        assertTrue(File(target, "style.css").isFile)
        transaction.commit()
        assertTrue(target.listFiles().orEmpty().none { it.name.endsWith(".owned") })
        assertTrue(File(server, "data/default-user").listFiles().orEmpty().none { it.name.startsWith(".sillyclient-preinstall-") })
    }

    @Test
    fun preservesExistingValidExtensionsAndDisabledSettingsWithoutDownloading() = withServer { server ->
        val bytes = archive()
        val target = File(server, "data/default-user/extensions/JS-Slash-Runner").apply { mkdirs() }
        File(target, "manifest.json").writeText("""{"display_name":"User","version":"0.5.0","js":"index.js"}""")
        File(target, "index.js").writeText("user version")
        val settings = File(server, "data/default-user/settings.json").apply {
            writeText("""{"extension_settings":{"disabledExtensions":["third-party/JS-Slash-Runner"]}}""")
        }
        val logs = mutableListOf<String>()
        val transaction = PreinstalledExtensionInstaller.installInternal(
            catalog(record("example", "Example", bytes)), server,
            PreinstalledExtensionsRequest(1, listOf("tavern-helper")), {}, logs::add,
            download = { _, _ -> throw AssertionError("Existing extension was downloaded again") }
        )
        transaction.rollback()
        assertEquals("user version", File(target, "index.js").readText())
        assertTrue(settings.readText().contains("disabledExtensions"))
        assertTrue(logs.any { it.contains("保留") })
    }

    @Test
    fun rejectsUnknownOrDuplicateSelectionsBeforeCreatingDirectories() = withServer { server ->
        val bytes = archive()
        val catalog = catalog(record("example", "Example", bytes))
        assertThrows(IllegalArgumentException::class.java) {
            install(server, catalog, listOf("https://bad.example/arbitrary.zip"), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            install(server, catalog, listOf("example", "example"), emptyMap())
        }
        assertFalse(File(server, "data").exists())
    }

    @Test
    fun aHashMismatchNeverCommitsAnySelectedExtension() = withServer { server ->
        val first = archive("First")
        val second = archive("Second")
        val corrupted = second.clone().apply { this[30] = (this[30].toInt() xor 1).toByte() }
        assertThrows(IllegalStateException::class.java) {
            install(server, catalog(record("first", "First", first), record("second", "Second", second)),
                listOf("first", "second"), mapOf("first" to first, "second" to corrupted))
        }
        assertFalse(File(server, "data/default-user/extensions/JS-Slash-Runner").exists())
        assertFalse(File(server, "data/default-user/extensions/Extension-Dice").exists())
    }

    @Test
    fun rollbackRemovesOnlyNewlyOwnedExtensionDirectories() = withServer { server ->
        val bytes = archive()
        val unrelated = File(server, "data/default-user/extensions/User").apply { mkdirs() }
        File(unrelated, "keep.js").writeText("keep")
        val transaction = install(server, catalog(record("example", "Example", bytes)), listOf("example"), mapOf("example" to bytes))
        transaction.rollback()
        assertFalse(File(server, "data/default-user/extensions/JS-Slash-Runner").exists())
        assertEquals("keep", File(unrelated, "keep.js").readText())
    }

    @Test
    fun rollbackDoesNotDeleteADirectoryWhoseOwnershipChanged() = withServer { server ->
        val bytes = archive()
        val transaction = install(server, catalog(record("example", "Example", bytes)), listOf("example"), mapOf("example" to bytes))
        val target = File(server, "data/default-user/extensions/JS-Slash-Runner")
        target.listFiles().orEmpty().filter { it.name.endsWith(".owned") }.forEach { it.delete() }
        File(target, "user-file.json").writeText("user replacement")
        transaction.rollback()
        assertEquals("user replacement", File(target, "user-file.json").readText())
    }

    @Test
    fun cancellationDuringDownloadCannotCommit() = withServer { server ->
        val bytes = archive()
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            PreinstalledExtensionInstaller.installInternal(
                catalog(record("example", "Example", bytes)), server, PreinstalledExtensionsRequest(1, listOf("tavern-helper")),
                { if (cancelled) throw CancellationException() }, {},
                download = { _, destination -> destination.writeBytes(bytes); cancelled = true }
            )
        }
        assertFalse(File(server, "data/default-user/extensions/JS-Slash-Runner").exists())
    }

    @Test
    fun rejectsMissingManifestEntrypointsAndIncompatibleClientVersions() = withServer { server ->
        val bytes = archive(manifest = """{"display_name":"Example","version":"1.0.0","js":"missing.js"}""")
        assertThrows(IllegalStateException::class.java) {
            install(server, catalog(record("example", "Example", bytes)), listOf("example"), mapOf("example" to bytes))
        }
        val valid = archive()
        assertThrows(IllegalArgumentException::class.java) {
            install(server, catalog(record("example", "Example", valid, "2.0.0")), listOf("example"), mapOf("example" to valid))
        }
        assertFalse(File(server, "data/default-user/extensions/JS-Slash-Runner").exists())
    }

    @Test
    fun rejectsArchiveTraversalAndMismatchedArchiveRoots() = withServer { server ->
        val traversal = archive(extra = mapOf("../escape.txt" to "escape"))
        assertThrows(IllegalArgumentException::class.java) {
            install(server, catalog(record("example", "Example", traversal)), listOf("example"), mapOf("example" to traversal))
        }
        val wrongRoot = archive("Other")
        assertThrows(IllegalArgumentException::class.java) {
            install(server, catalog(record("example", "Example", wrongRoot)), listOf("example"), mapOf("example" to wrongRoot))
        }
        assertFalse(File(server, "data/default-user/escape.txt").exists())
        assertFalse(File(server, "data/default-user/extensions/JS-Slash-Runner").exists())
    }

    @Test
    fun rejectsSymlinkAndOversizedFileMetadataInCentralDirectory() = withServer { server ->
        fun central(bytes: ByteArray): Int = (0 until bytes.size - 4).first {
            bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() &&
                bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte()
        }
        val symlink = archive().also { bytes ->
            val offset = central(bytes)
            bytes[offset + 40] = 0xff.toByte()
            bytes[offset + 41] = 0xa1.toByte()
        }
        assertThrows(IllegalStateException::class.java) {
            install(server, catalog(record("example", "Example", symlink)), listOf("example"), mapOf("example" to symlink))
        }
        val oversized = archive().also { bytes ->
            val offset = central(bytes)
            val size = 33 * 1024 * 1024
            repeat(4) { bytes[offset + 24 + it] = (size ushr (8 * it)).toByte() }
        }
        assertThrows(IllegalStateException::class.java) {
            install(server, catalog(record("example", "Example", oversized)), listOf("example"), mapOf("example" to oversized))
        }
        assertFalse(File(server, "data/default-user/extensions/JS-Slash-Runner").exists())
    }

    @Test
    fun allFourPinnedAuditArchivesInstallOfflineAndPreserveLicenseFiles() = withServer { server ->
        val audit = System.getenv("SILLYCLIENT_EXTENSION_AUDIT_DIR")?.let(::File)
        assumeTrue("External pinned audit archives are not present in this environment", audit?.isDirectory == true)
        val catalog = File("src/main/assets/preinstalled-extensions/catalog.json").readText()
        val ids = listOf("tavern-helper", "littlewhitebox", "prompt-template", "dice")
        val request = PreinstalledExtensionsRequest(1, ids)
        val selected = PreinstalledExtensionInstaller.selection(catalog, request)
        selected.forEach { assertTrue(File(audit, "${it.id}.zip").isFile) }
        val transaction = PreinstalledExtensionInstaller.installInternal(
            catalog, server, request, {}, {},
            download = { extension, destination -> File(audit, "${extension.id}.zip").copyTo(destination) }
        )
        transaction.commit()
        for (extension in selected) {
            val installed = File(server, "data/default-user/extensions/${extension.directoryName}")
            assertTrue(File(installed, "manifest.json").isFile)
            assertTrue(File(installed, extension.licensePath).isFile)
            val manifest = JSONObject(File(installed, "manifest.json").readText())
            assertTrue(File(installed, manifest.getString("js")).isFile)
            if (manifest.optString("css").isNotEmpty()) assertTrue(File(installed, manifest.getString("css")).isFile)
        }
    }

    @Test
    fun refusesCatalogRepositoriesOutsideTheExactAllowlist() = withServer { server ->
        val bytes = archive()
        val altered = record("example", "Example", bytes).put("repository", "someone/JS-Slash-Runner")
        assertThrows(IllegalArgumentException::class.java) {
            install(server, catalog(altered), listOf("example"), mapOf("example" to bytes))
        }
        assertFalse(File(server, "data").exists())
    }

    @Test
    fun refusesCatalogLicensePathTraversal() = withServer { server ->
        val bytes = archive()
        val altered = record("example", "Example", bytes).put("licensePath", "../LICENSE")
        assertThrows(IllegalArgumentException::class.java) {
            install(server, catalog(altered), listOf("example"), mapOf("example" to bytes))
        }
        assertFalse(File(server, "data").exists())
    }

    @Test
    fun rollbackPreservesFilesChangedAfterPublicationEvenIfTheOwnerMarkerRemains() = withServer { server ->
        val bytes = archive()
        val transaction = install(server, catalog(record("example", "Example", bytes)), listOf("example"), mapOf("example" to bytes))
        val target = File(server, "data/default-user/extensions/JS-Slash-Runner")
        val original = File(target, "dist/index.js")
        val timestamp = original.lastModified()
        original.writeText("user changed code")
        original.setLastModified(timestamp)
        File(target, "user-notes.txt").writeText("new user file")
        transaction.rollback()
        assertEquals("user changed code", original.readText())
        assertEquals("new user file", File(target, "user-notes.txt").readText())
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
