package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceDataImportTest {
    private fun withRoot(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent == null) Files.createTempDirectory("instance-import-").toFile()
            else Files.createTempDirectory(parent.toPath(), "instance-import-").toFile()
        try { test(root) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            ManagedFiles.deleteDirectory(root, parent?.canonicalFile ?: root.parentFile!!.canonicalFile)
        }
    }

    /** 与 SourceArchiveTest 相同：STORED 条目 + 显式 CRC，便于构造真实可解的压缩包。 */
    private fun archive(root: File, name: String = "backup.zip", vararg entries: Pair<String, String>): File {
        val zip = File(root, name)
        ZipOutputStream(zip.outputStream().buffered()).use { output ->
            for ((path, content) in entries) {
                val bytes = content.toByteArray()
                val entry = ZipEntry(path).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
        return zip
    }

    private fun instance(root: File, vararg files: Pair<String, String>): File =
        File(root, "存放文件夹/已有实例").apply {
            mkdirs()
            File(this, "server.js").writeText("server source")
            File(this, "package.json").writeText("""{"version":"1.19.0"}""")
            File(this, "node_modules").mkdirs()
            for ((path, content) in files) {
                val target = File(this, path)
                requireNotNull(target.parentFile).mkdirs()
                target.writeText(content)
            }
        }

    @Test
    fun importsUserDataAndNeverTouchesDependenciesOrProgramFiles() = withRoot { root ->
        val target = instance(root, "data/default-user/settings.json" to "old-settings")
        val backup = archive(root, entries = arrayOf(
            "实例名/data/default-user/settings.json" to "new-settings",
            "实例名/data/default-user/chats/chat.jsonl" to """{"mes":"hi"}""",
            "实例名/data/_webpack/cache/blob.bin" to "compiled-cache",
            "实例名/public/scripts/extensions/third-party/ext/index.js" to "extension",
            "实例名/public/lib/lib.js" to "compiled-frontend",
            "实例名/node_modules/left-pad/index.js" to "dependency",
            "实例名/package.json" to """{"version":"0.0.1"}""",
            "实例名/package-lock.json" to "{}",
            "实例名/.sc-identity" to "foreign-identity",
            "实例名/server.js" to "foreign-server"
        ))
        val summary = InstanceDataImport.inspect(backup)
        assertEquals(3, summary.importEntries)
        assertTrue(summary.importable)

        val outcome = InstanceDataImport.import(backup, target, includeOptional = false, ensureActive = {}, onProgress = { _, _ -> })

        assertEquals(3, outcome.imported)
        // 用户数据被覆盖/新增
        assertEquals("new-settings", File(target, "data/default-user/settings.json").readText())
        assertEquals("""{"mes":"hi"}""", File(target, "data/default-user/chats/chat.jsonl").readText())
        assertEquals("extension", File(target, "public/scripts/extensions/third-party/ext/index.js").readText())
        // 依赖、程序文件与主机元数据保持不变
        assertFalse(File(target, "data/_webpack").exists())
        assertFalse(File(target, "public/lib/lib.js").exists())
        assertFalse(File(target, "node_modules/left-pad").exists())
        assertEquals("""{"version":"1.19.0"}""", File(target, "package.json").readText())
        assertEquals("server source", File(target, "server.js").readText())
        assertFalse(File(target, ".sc-identity").exists())
    }

    @Test
    fun optionalCredentialsAreExcludedByDefaultAndIncludedOnDemand() = withRoot { root ->
        val target = instance(root)
        val backup = archive(root, entries = arrayOf(
            "data/default-user/settings.json" to "settings",
            "secrets.json" to """{"api":"secret"}""",
            "config.yaml" to "dataRoot: ./data\n"
        ))
        val summary = InstanceDataImport.inspect(backup)
        assertTrue(summary.hasSecrets)
        assertTrue(summary.hasConfig)
        assertEquals(1, summary.importEntries)

        InstanceDataImport.import(backup, target, includeOptional = false, ensureActive = {}, onProgress = { _, _ -> })
        assertFalse(File(target, "secrets.json").exists())
        assertFalse(File(target, "config.yaml").exists())

        InstanceDataImport.import(backup, target, includeOptional = true, ensureActive = {}, onProgress = { _, _ -> })
        assertEquals("""{"api":"secret"}""", File(target, "secrets.json").readText())
        assertEquals("dataRoot: ./data\n", File(target, "config.yaml").readText())
    }

    @Test
    fun backupsWithoutWrapperDirectoryImportFromTheirRoot() = withRoot { root ->
        val target = instance(root)
        val backup = archive(root, entries = arrayOf(
            "data/default-user/settings.json" to "settings",
            "public/scripts/extensions/third-party/plugin/index.js" to "plugin"
        ))
        val outcome = InstanceDataImport.import(backup, target, includeOptional = false, ensureActive = {}, onProgress = { _, _ -> })
        assertEquals(2, outcome.imported)
        assertEquals("settings", File(target, "data/default-user/settings.json").readText())
        assertEquals("plugin", File(target, "public/scripts/extensions/third-party/plugin/index.js").readText())
    }

    @Test
    fun cancellationLeavesNoTemporaryFilesAndKeepsReplacedFilesIntact() = withRoot { root ->
        val target = instance(root, "data/first.txt" to "original")
        val backup = archive(root, entries = arrayOf(
            "data/first.txt" to "replaced",
            "data/second.txt" to "second",
            "data/third.txt" to "third"
        ))
        var imported = 0
        assertThrows(IllegalStateException::class.java) {
            InstanceDataImport.import(backup, target, includeOptional = false,
                ensureActive = { if (imported >= 1) throw IllegalStateException("cancelled") },
                onProgress = { _, _ -> imported++ })
        }
        assertEquals("replaced", File(target, "data/first.txt").readText())
        assertFalse(File(target, "data/second.txt").exists())
        assertFalse(File(target, "data/third.txt").exists())
        val leftovers = target.walkTopDown().filter { it.name.startsWith(".sc-import-tmp-") }.toList()
        assertTrue("临时文件必须被清理：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun unsafeArchivePathsAndForeignArchivesAreRejectedBeforeWriting() = withRoot { root ->
        val target = instance(root)
        val traversal = archive(root, "traversal.zip", "../escape/data/file.txt" to "x")
        assertThrows(IllegalArgumentException::class.java) {
            InstanceDataImport.import(traversal, target, false, {}, { _, _ -> })
        }
        val noData = archive(root, "program-only.zip", entries = arrayOf(
            "node_modules/left-pad/index.js" to "dependency",
            "package.json" to "{}"
        ))
        assertFalse(InstanceDataImport.inspect(noData).importable)
        assertThrows(IllegalStateException::class.java) {
            InstanceDataImport.import(noData, target, false, {}, { _, _ -> })
        }
        assertFalse(File(root, "escape").exists())
    }

    @Test
    fun missingInstanceIsRejected() = withRoot { root ->
        val backup = archive(root, entries = arrayOf("data/settings.json" to "x"))
        assertThrows(IllegalArgumentException::class.java) {
            InstanceDataImport.import(backup, File(root, "not-an-instance"), false, {}, { _, _ -> })
        }
    }
}
