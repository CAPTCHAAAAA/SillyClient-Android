package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceInstallerTest {
    private fun withRoot(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent == null) Files.createTempDirectory("installer-").toFile()
            else Files.createTempDirectory(parent.toPath(), "installer-").toFile()
        try { test(root) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    private fun source(directory: File): Boolean {
        File(directory, "server.js").writeText("source")
        File(directory, "package.json").writeText("{}")
        return true
    }

    private fun dependencies(directory: File): Boolean = File(directory, "node_modules").mkdirs()

    private fun assertNoStaging(parent: File) =
        assertTrue(parent.listFiles().orEmpty().none { it.name.startsWith(InstanceInstaller.STAGING_PREFIX) })

    @Test
    fun newInstanceIsPreparedBesideItsFinalPathAndPublishedOnceComplete() = withRoot { root ->
        val servers = File(root, "servers")
        val target = File(servers, "readable name")
        var stage: File? = null
        InstanceInstaller(servers).prepare(target, {}, {
            stage = it
            assertEquals(target.parentFile, it.parentFile)
            assertNotEquals(target, it)
            assertFalse(target.exists())
            source(it)
        }, {
            assertEquals(stage, it)
            assertFalse(target.exists())
            dependencies(it)
        }, { it() })
        assertTrue(File(target, "server.js").isFile)
        assertTrue(File(target, "node_modules").isDirectory)
        assertFalse(requireNotNull(stage).exists())
        assertFalse(target.listFiles().orEmpty().any { it.name.startsWith(InstanceInstaller.STAGING_PREFIX) })
        assertNoStaging(servers)
    }

    @Test
    fun failedExtractionAndDependencyInstallationRemoveOnlyOwnedSibling() = withRoot { root ->
        val servers = File(root, "servers")
        val neighbor = File(servers, "other/data.json").apply { parentFile.mkdirs(); writeText("keep") }
        val target = File(servers, "new")
        for (extractSucceeds in listOf(false, true)) {
            assertThrows(IllegalStateException::class.java) {
                InstanceInstaller(servers).prepare(target, {}, { source(it); extractSucceeds }, {
                    dependencies(it)
                    false
                }, { it() })
            }
            assertFalse(target.exists())
            assertEquals("keep", neighbor.readText())
            assertNoStaging(servers)
        }
    }

    @Test
    fun cancelledDependencyInstallationRemovesStageEvenWhenCancellationRemainsActive() = withRoot { root ->
        val servers = File(root, "servers")
        val target = File(servers, "new")
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            InstanceInstaller(servers).prepare(target, {
                if (cancelled) throw CancellationException("cancelled")
            }, ::source, {
                dependencies(it)
                cancelled = true
                true
            }, { it() })
        }
        assertFalse(target.exists())
        assertNoStaging(servers)
    }

    @Test
    fun failedNewInstallDelegatesOnlyItsOwnedStageWithTheOwnershipMarkerIntact() = withRoot { root ->
        val servers = File(root, "servers")
        val target = File(servers, "new")
        val neighbor = File(servers, "keep/chat.jsonl").apply { parentFile.mkdirs(); writeText("keep") }
        var cleanupCalls = 0
        val installer = InstanceInstaller(servers, removeStaging = { directory, scope, marker, verify ->
            cleanupCalls++
            verify()
            assertEquals(servers, scope)
            assertEquals(servers, directory.parentFile)
            assertNotEquals(target, directory)
            assertEquals(directory, marker.parentFile)
            assertTrue(marker.isFile)
            assertTrue(marker.name.startsWith(InstanceInstaller.STAGING_PREFIX))
            ManagedFiles.deleteDirectory(directory, scope)
        })
        assertThrows(IllegalStateException::class.java) {
            installer.prepare(target, {}, ::source, { false }, { it() })
        }
        assertEquals(1, cleanupCalls)
        assertEquals("keep", neighbor.readText())
        assertNoStaging(servers)
    }

    @Test
    fun failedStageCleanupPreservesTheOriginalErrorAndTheOwnedDirectory() = withRoot { root ->
        val servers = File(root, "servers")
        val target = File(servers, "new")
        var staged: File? = null
        var owner: File? = null
        val installer = InstanceInstaller(servers, removeStaging = { directory, _, marker, _ ->
            staged = directory
            owner = marker
            false
        })
        val error = assertThrows(IllegalStateException::class.java) {
            installer.prepare(target, {}, ::source, { false }, { it() })
        }
        assertTrue(error.message.orEmpty().contains("依赖安装被拒绝"))
        assertEquals(1, error.suppressed.size)
        assertTrue(requireNotNull(staged).isDirectory)
        assertTrue(requireNotNull(owner).isFile)
        assertFalse(target.exists())
    }

    @Test
    fun failedDependencyRepairNeverUsesTheNewInstallationCleanup() = withRoot { root ->
        val servers = File(root, "servers")
        val target = File(servers, "existing").apply { mkdirs() }
        source(target)
        val data = File(target, "chat.jsonl").apply { writeText("keep") }
        val installer = InstanceInstaller(servers, removeStaging = { _, _, _, _ ->
            throw AssertionError("Existing user data must not enter installation cleanup")
        })
        assertThrows(IllegalStateException::class.java) {
            installer.prepare(target, {}, ::source, { false }, { it() })
        }
        assertEquals("keep", data.readText())
        assertTrue(File(target, InstanceInstaller.DEPENDENCY_MARKER).isFile)
    }

    @Test
    fun preexistingEmptyTargetIsUntouchedUntilACompleteStageIsReady() = withRoot { root ->
        val servers = File(root, "servers")
        val target = File(servers, "empty").apply { mkdirs() }
        InstanceInstaller(servers).prepare(target, {}, {
            assertTrue(target.isDirectory)
            assertTrue(target.listFiles().orEmpty().isEmpty())
            source(it)
        }, ::dependencies, { it() })
        assertTrue(File(target, "server.js").isFile)
        assertNoStaging(servers)
    }

    @Test
    fun failedInstallPreservesPreexistingEmptyTarget() = withRoot { root ->
        val servers = File(root, "servers")
        val target = File(servers, "empty").apply { mkdirs() }
        assertThrows(IllegalStateException::class.java) {
            InstanceInstaller(servers).prepare(target, {}, ::source, { false }, { it() })
        }
        assertTrue(target.isDirectory)
        assertTrue(target.listFiles().orEmpty().isEmpty())
        assertNoStaging(servers)
    }

    @Test
    fun fileAppearingInTheTargetDuringInstallationIsNeverOverwritten() = withRoot { root ->
        val servers = File(root, "servers")
        val target = File(servers, "new")
        val userFile = File(target, "chat.jsonl")
        assertThrows(IllegalArgumentException::class.java) {
            InstanceInstaller(servers).prepare(target, {}, ::source, {
                target.mkdirs()
                userFile.writeText("new user data")
                dependencies(it)
            }, { it() })
        }
        assertEquals("new user data", userFile.readText())
        assertFalse(File(target, "server.js").exists())
        assertNoStaging(servers)
    }

    @Test
    fun sourceCompleteButMissingRequiredDependencyRetriesWithoutReplacingData() = withRoot { root ->
        val servers = File(root, "servers")
        val target = File(servers, "existing").apply { mkdirs() }
        source(target)
        dependencies(target)
        val data = File(target, "chat.jsonl").apply { writeText("keep") }
        var retried = false
        InstanceInstaller(servers, dependenciesComplete = { File(it, "node_modules/required.js").isFile })
            .prepare(target, {}, { throw AssertionError("Existing source was replaced") }, {
                retried = true
                File(it, "node_modules/required.js").writeText("dependency")
                true
            }, { it() })
        assertTrue(retried)
        assertEquals("keep", data.readText())
        assertFalse(File(target, InstanceInstaller.DEPENDENCY_MARKER).exists())
    }

    @Test
    fun dependencyCommandSuccessAloneCannotClearThePendingMarker() = withRoot { root ->
        val servers = File(root, "servers")
        val target = File(servers, "existing").apply { mkdirs() }
        source(target)
        assertThrows(IllegalStateException::class.java) {
            InstanceInstaller(servers, dependenciesComplete = { false })
                .prepare(target, {}, ::source, ::dependencies, { it() })
        }
        assertTrue(File(target, InstanceInstaller.DEPENDENCY_MARKER).isFile)
        assertEquals("source", File(target, "server.js").readText())
    }
}
