package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeInstanceTransferTest {
    private fun fixture(test: (File, RuntimePaths, OperationCoordinator, ProcessSupervisor) -> Unit) {
        val node = System.getenv("SILLYCLIENT_CONFIGURATION_NODE")?.let(::File)
        assumeTrue("The bundled Node fixture is not configured", node?.isFile == true)
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)?.apply { mkdirs() }
        val root = if (parent == null) Files.createTempDirectory("native-transfer-").toFile()
            else Files.createTempDirectory(parent.toPath(), "native-transfer-").toFile()
        val home = File(root, "tarven")
        val paths = RuntimePaths(root, home, File(home, "bootstrap"), File(home, "servers"), File(home, "usr"),
            File(home, "usr/lib"), File(home, "tmp"), File(home, "logs"), node!!.parentFile, node)
        paths.ensureDirs()
        try {
            OperationCoordinator().use { operations ->
                ProcessSupervisor(operations).use { processes -> test(root, paths, operations, processes) }
            }
        } finally { root.deleteRecursively() }
    }

    private fun write(root: File, path: String, content: String = "fixture"): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(content)
    }

    @Test
    fun nativeCopyPreservesSourceAndVerifiesCompleteFilesIncludingDependencies() = fixture { root, paths, operations, processes ->
        val source = File(root, "Source Tavern")
        write(source, "server.js", "source")
        write(source, "data/default-user/chats/conversation.jsonl", "private chat contents")
        write(source, "node_modules/package/index.js", "dependency contents")
        File(source, "data/default-user/characters").mkdirs()
        val target = File(root, "Target Tavern").apply { mkdirs() }
        write(target, InstanceRelocation.OWNER_MARKER, "owned by relocation")
        NativeInstanceTransfer(paths, operations, processes).copyVerified(source, target, operations.begin("stable-id"))
        for (relative in listOf("server.js", "data/default-user/chats/conversation.jsonl", "node_modules/package/index.js")) {
            assertEquals(File(source, relative).readText(), File(target, relative).readText())
        }
        assertTrue(File(target, "data/default-user/characters").isDirectory)
        assertTrue(source.isDirectory)
        assertFalse(processes.hasProcesses())
    }

    @Test
    fun nativeTransferRejectsCollisionsWithoutOverwritingTargetFiles() = fixture { root, paths, operations, processes ->
        val source = File(root, "source")
        write(source, "server.js", "source")
        val target = File(root, "target")
        write(target, "server.js", "preserve target")
        assertThrows(IllegalStateException::class.java) {
            NativeInstanceTransfer(paths, operations, processes).copyVerified(source, target, operations.begin("stable-id"))
        }
        assertEquals("source", File(source, "server.js").readText())
        assertEquals("preserve target", File(target, "server.js").readText())
        assertFalse(processes.hasProcesses())
    }

    @Test
    fun cancellingNativeTransferStopsTheOwnedProcessAndLeavesTheSource() = fixture { root, paths, operations, processes ->
        val source = File(root, "source")
        write(source, "server.js", "source")
        val target = File(root, "target").apply { mkdirs() }
        val cancelled = AtomicBoolean()
        val transfer = NativeInstanceTransfer(paths, operations, processes, onProgress = {
            if (cancelled.compareAndSet(false, true)) operations.cancel()
        })
        assertThrows(CancellationException::class.java) { transfer.copyVerified(source, target, operations.begin("stable-id")) }
        assertTrue(cancelled.get())
        assertEquals("source", File(source, "server.js").readText())
        assertFalse(processes.hasProcesses())
    }

    @Test
    fun relocationCleanupBeginsOnlyAfterTheNativeWriterHasStopped() = fixture { _, paths, operations, processes ->
        val source = File(paths.serversDir, "Original")
        write(source, "server.js", "source")
        write(source, "package.json", "{\"version\":\"1.19.0\"}")
        write(source, "node_modules/dependency/index.js", "module contents")
        write(source, "data/default-user/chats/log.jsonl", "private chat contents")
        paths.installLocations.registerCommitted("stable-id", source)
        val cancelled = AtomicBoolean()
        val transfer = NativeInstanceTransfer(paths, operations, processes, onProgress = {
            if (cancelled.compareAndSet(false, true)) operations.cancel()
        })
        var cleaned = false
        val relocation = InstanceRelocation(paths, operations, processes,
            copyVerified = transfer::copyVerified, sameFilesystem = { _, _ -> false },
            removeStaging = { target, root, _, verify ->
                assertFalse("A native writer must exit before staging removal", processes.hasProcesses())
                InstanceRemoval.remove(target, root, verify, ensureActive = {},
                    removeChildren = { children -> children.forEach { assertTrue(it.deleteRecursively()) } },
                    commit = { it() }, unregister = {}, identityFileName = InstanceRelocation.OWNER_MARKER)
                cleaned = true
            })
        assertThrows(CancellationException::class.java) {
            relocation.relocate("stable-id", File(paths.installationsDir, "Moved").path, operation = operations.begin("stable-id"))
        }
        assertTrue(cancelled.get())
        assertTrue(cleaned)
        assertEquals(source, paths.serverDirFor("stable-id", create = false))
        assertEquals("private chat contents", File(source, "data/default-user/chats/log.jsonl").readText())
        assertTrue(paths.installationsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun externalLinksAreRejectedInsteadOfFollowingPrivateFiles() = fixture { root, paths, operations, processes ->
        val source = File(root, "source").apply { mkdirs() }
        val privateFile = write(root, "private-secret.txt", "private")
        val link = File(source, "chat.jsonl")
        assumeTrue("Symlink creation is not available on this host",
            runCatching { Files.createSymbolicLink(link.toPath(), privateFile.toPath()) }.isSuccess)
        val target = File(root, "target").apply { mkdirs() }
        assertThrows(IllegalStateException::class.java) {
            NativeInstanceTransfer(paths, operations, processes).copyVerified(source, target, operations.begin("stable-id"))
        }
        assertEquals("private", privateFile.readText())
        assertFalse(File(target, "chat.jsonl").exists())
        Files.delete(link.toPath())
    }

    @Test
    fun regeneratableNpmBinLinksDoNotPreventExternalFilesystemTransfer() = fixture { root, paths, operations, processes ->
        val source = File(root, "source")
        val command = write(source, "node_modules/package/cli.js", "module command")
        val bin = File(source, "node_modules/.bin").apply { mkdirs() }
        val link = File(bin, "command")
        assumeTrue("Symlink creation is not available on this host",
            runCatching { Files.createSymbolicLink(link.toPath(), command.toPath()) }.isSuccess)
        val target = File(root, "target").apply { mkdirs() }
        NativeInstanceTransfer(paths, operations, processes).copyVerified(source, target, operations.begin("stable-id"))
        assertEquals("module command", File(target, "node_modules/package/cli.js").readText())
        assertFalse(File(target, "node_modules/.bin/command").exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        Files.delete(link.toPath())
    }

    @Test
    fun portableConfigurationIsValidatedWithoutRewritingUserYaml() = fixture { root, paths, operations, processes ->
        val yaml = System.getenv("SILLYCLIENT_CONFIGURATION_YAML")?.let(::File)
        assumeTrue("The YAML fixture is not configured", yaml?.isDirectory == true)
        val source = File(root, "source").apply { mkdirs() }
        yaml!!.copyRecursively(File(source, "node_modules/yaml"))
        val config = write(source, "config.yaml", "# preserve comment\ndataRoot: ./data\n")
        val transfer = NativeInstanceTransfer(paths, operations, processes)
        transfer.validatePortableSource(source, operations.begin("stable-id"))
        assertEquals("# preserve comment\ndataRoot: ./data\n", config.readText())
        val absolute = "dataRoot: '${File(source, "data").path.replace('\\', '/').replace("'", "''")}'\n"
        config.writeText(absolute)
        assertThrows(IllegalStateException::class.java) {
            transfer.validatePortableSource(source, operations.begin("stable-id"))
        }
        assertEquals(absolute, config.readText())
        config.writeText("dataRoot: ../shared-data\n")
        assertThrows(IllegalStateException::class.java) {
            transfer.validatePortableSource(source, operations.begin("stable-id"))
        }
        assertFalse(processes.hasProcesses())
    }
}
