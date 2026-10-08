package com.sillyclient.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class InstanceRemovalTest {
    private data class Fixture(val root: File, val instances: File, val target: File, val registry: InstallLocationRegistry) {
        fun verify() {
            val resolved = registry.resolve("instance-id")
            // Ghost pruning may already have dropped the vanished registration;
            // whatever resolves must never point at another instance's directory.
            assertTrue(resolved == target || !registry.isRegistered(target))
        }
        fun unregister() { registry.unregisterAfterDelete("instance-id", target) }
        fun delete(children: List<File>) {
            for (child in children) {
                assertEquals(target, child.parentFile)
                assertTrue(child.name != ".sc-identity")
                if (Files.isSymbolicLink(child.toPath()) || child.isFile) Files.delete(child.toPath())
                else assertTrue(ManagedFiles.deleteDirectory(child, target))
            }
        }
        fun remove(
            ensureActive: () -> Unit = {},
            removeChildren: (List<File>) -> Unit = ::delete,
            unregister: () -> Unit = ::unregister,
            deleteEmptyDirectory: (File) -> Unit = { Files.delete(it.toPath()) }
        ) = InstanceRemoval.remove(target, instances, ::verify, ensureActive, removeChildren, { it() }, unregister,
            deleteEmptyDirectory)
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent == null) Files.createTempDirectory("instance-removal-").toFile()
            else Files.createTempDirectory(parent.toPath(), "instance-removal-").toFile()
        try {
            val instances = File(root, "instances")
            val target = File(instances, "named-directory").apply { mkdirs() }
            File(target, "server.js").writeText("server")
            File(target, "package.json").writeText("{}")
            File(target, "node_modules/dependency").apply { parentFile?.mkdirs(); writeText("dependency") }
            File(target, "data/chat.jsonl").apply { parentFile?.mkdirs(); writeText("history") }
            val registry = InstallLocationRegistry(root, instances, File(root, "installations"), File(root, "state/locations.json"))
            registry.registerCommitted("instance-id", target)
            test(Fixture(root, instances, target, registry))
        } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            if (root.exists()) ManagedFiles.deleteDirectory(root, requireNotNull(root.parentFile))
        }
    }

    @Test
    fun removesContentBeforeIdentityAndUnregistersOnlyAfterDirectoryIsGone() = withFixture { fixture ->
        fixture.remove(removeChildren = {
            assertTrue(File(fixture.target, ".sc-identity").isFile)
            fixture.delete(it)
            fixture.verify()
        }, unregister = {
            assertFalse(fixture.target.exists())
            fixture.unregister()
        })
        assertFalse(fixture.target.exists())
        assertTrue(fixture.registry.entries().isEmpty())
    }

    @Test
    fun failedContentDeletionPreservesTheIdentitySoRemovalCanBeRetried() = withFixture { fixture ->
        assertThrows(IOException::class.java) {
            fixture.remove(removeChildren = {
                Files.delete(File(fixture.target, "server.js").toPath())
                throw IOException("simulated native deletion failure")
            })
        }
        fixture.verify()
        assertTrue(File(fixture.target, ".sc-identity").isFile)
        fixture.remove()
        assertTrue(fixture.registry.entries().isEmpty())
    }

    @Test
    fun cancellationAfterDeletingContentDoesNotRemoveTheIdentity() = withFixture { fixture ->
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            fixture.remove(ensureActive = { if (cancelled) throw CancellationException() }, removeChildren = {
                fixture.delete(it)
                cancelled = true
            })
        }
        fixture.verify()
        assertTrue(File(fixture.target, ".sc-identity").isFile)
        fixture.remove()
        assertFalse(fixture.target.exists())
    }

    @Test
    fun partiallyDeletedDependenciesRemainDiscoverableAndRetryableAfterRegistryReload() = withFixture { fixture ->
        val marker = File(fixture.target, ".sc-identity")
        val identity = marker.readText()
        val remaining = File(fixture.target, "node_modules/remaining/index.js").apply {
            parentFile?.mkdirs()
            writeText("partial dependency")
        }
        assertThrows(CancellationException::class.java) {
            fixture.remove(removeChildren = { children ->
                fixture.delete(children.filter { it.name != "node_modules" })
                Files.delete(File(fixture.target, "node_modules/dependency").toPath())
                throw CancellationException("Application stopped during native removal")
            })
        }
        assertEquals(identity, marker.readText())
        assertTrue(remaining.isFile)
        assertFalse(File(fixture.target, "server.js").exists())

        val reloaded = InstallLocationRegistry(fixture.root, fixture.instances,
            File(fixture.root, "installations"), File(fixture.root, "state/locations.json"))
        val scanned = InstanceRepository(fixture.instances, installLocations = reloaded).scan().single()
        assertEquals("instance-id", scanned.instanceId)
        assertEquals(fixture.target.absolutePath, scanned.path)
        assertFalse(scanned.hasServer)
        assertEquals("未完成", scanned.status)
        InstanceRemoval.remove(fixture.target, fixture.instances,
            verifyIdentity = { assertEquals(fixture.target, reloaded.resolve("instance-id")) },
            ensureActive = {}, removeChildren = fixture::delete, commit = { it() },
            unregister = { reloaded.unregisterAfterDelete("instance-id", fixture.target) })
        assertFalse(fixture.target.exists())
        assertTrue(reloaded.entries().isEmpty())
    }

    @Test
    fun legacyFileKeyRegistrationWithoutMarkerRemainsRetryableAfterPartialDeletion() = withFixture { fixture ->
        val fileKey = Files.readAttributes(fixture.target.toPath(), BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS).fileKey()?.toString()
        assumeTrue("Legacy inode registration requires a filesystem file key", fileKey != null)
        val registryFile = File(fixture.root, "state/locations.json")
        val document = JSONObject(registryFile.readText())
        document.getJSONArray("locations").getJSONObject(0).put("fileKey", fileKey)
        registryFile.writeText(document.toString())
        Files.delete(File(fixture.target, ".sc-identity").toPath())

        assertThrows(CancellationException::class.java) {
            fixture.remove(removeChildren = { children ->
                fixture.delete(children.filter { it.name != "node_modules" })
                throw CancellationException("Application stopped with dependency remnants")
            })
        }
        val reloaded = InstallLocationRegistry(fixture.root, fixture.instances,
            File(fixture.root, "installations"), registryFile)
        val scanned = InstanceRepository(fixture.instances, installLocations = reloaded).scan().single()
        assertEquals("instance-id", scanned.instanceId)
        assertFalse(scanned.hasServer)
        assertFalse(File(fixture.target, ".sc-identity").exists())
        InstanceRemoval.remove(fixture.target, fixture.instances,
            verifyIdentity = { assertEquals(fixture.target, reloaded.resolve("instance-id")) },
            ensureActive = {}, removeChildren = fixture::delete, commit = { it() },
            unregister = { reloaded.unregisterAfterDelete("instance-id", fixture.target) })
        assertFalse(fixture.target.exists())
        assertTrue(reloaded.entries().isEmpty())
    }

    @Test
    fun failedFinalDirectoryDeletionRestoresTheOriginalIdentity() = withFixture { fixture ->
        val originalIdentity = File(fixture.target, ".sc-identity").readText()
        assertThrows(IOException::class.java) {
            fixture.remove(deleteEmptyDirectory = {
                assertFalse(File(it, ".sc-identity").exists())
                throw IOException("simulated final directory failure")
            })
        }
        assertEquals(originalIdentity, File(fixture.target, ".sc-identity").readText())
        fixture.verify()
        fixture.remove()
        assertTrue(fixture.registry.entries().isEmpty())
    }

    @Test
    fun filesCreatedDuringContentDeletionAreNotSilentlyDeleted() = withFixture { fixture ->
        val appeared = File(fixture.target, "appeared.json")
        assertThrows(IllegalArgumentException::class.java) {
            fixture.remove(removeChildren = {
                fixture.delete(it)
                appeared.writeText("preserve")
            })
        }
        assertEquals("preserve", appeared.readText())
        fixture.verify()
        assertTrue(File(fixture.target, ".sc-identity").isFile)
    }

    @Test
    fun aFileAppearingImmediatelyBeforeRmdirStillLeavesTheInstanceRetryable() = withFixture { fixture ->
        val appeared = File(fixture.target, "appeared.json")
        assertThrows(IOException::class.java) {
            fixture.remove(deleteEmptyDirectory = {
                appeared.writeText("preserve")
                Files.delete(it.toPath())
            })
        }
        assertEquals("preserve", appeared.readText())
        fixture.verify()
        assertTrue(File(fixture.target, ".sc-identity").isFile)
    }

    @Test
    fun changedMarkerIsNeverOverwrittenByDeletion() = withFixture { fixture ->
        val marker = File(fixture.target, ".sc-identity")
        val replacement = java.util.UUID.randomUUID().toString()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.remove(removeChildren = {
                fixture.delete(it)
                marker.writeText(replacement)
            })
        }
        assertEquals(replacement, marker.readText())
        assertTrue(fixture.target.isDirectory)
    }

    @Test
    fun replacedTargetIsRejectedBeforeCallingTheNativeRemover() = withFixture { fixture ->
        val moved = File(fixture.instances, "moved-original")
        var checks = 0
        assertThrows(IllegalArgumentException::class.java) {
            fixture.remove(ensureActive = {
                if (++checks == 2) {
                    Files.move(fixture.target.toPath(), moved.toPath())
                    fixture.target.mkdirs()
                    File(fixture.target, "server.js").writeText("new user data")
                }
            }, removeChildren = { throw AssertionError("Replacement was passed to the native remover") })
        }
        assertEquals("new user data", File(fixture.target, "server.js").readText())
        assertTrue(File(moved, ".sc-identity").isFile)
    }

    @Test
    fun theFinalStepsUseOneCommit() = withFixture { fixture ->
        var commits = 0
        var committing = false
        InstanceRemoval.remove(fixture.target, fixture.instances, fixture::verify, {}, fixture::delete, { action ->
            assertFalse(committing)
            commits++
            committing = true
            action()
            committing = false
        }, {
            assertTrue(committing)
            fixture.unregister()
        }, {
            assertTrue(committing)
            Files.delete(it.toPath())
        })
        assertEquals(1, commits)
    }

    @Test
    fun unregisterFailureCanBeRetriedWhenTheDirectoryIsAlreadyGone() = withFixture { fixture ->
        assertThrows(IOException::class.java) {
            fixture.remove(unregister = { throw IOException("simulated registry failure") })
        }
        assertFalse(fixture.target.exists())
        fixture.verify()
        fixture.remove(removeChildren = { throw AssertionError("Missing directory was traversed") })
        assertTrue(fixture.registry.entries().isEmpty())
    }

    @Test
    fun linkedChildrenArePassedAsLeavesWithoutResolvingTheirTargets() = withFixture { fixture ->
        val outside = File(fixture.root, "outside").apply { mkdirs() }
        val data = File(outside, "chat.jsonl").apply { writeText("keep") }
        val linkedChild = File(fixture.target, "linked-data")
        assumeTrue("Host does not permit creating symbolic links",
            runCatching { Files.createSymbolicLink(linkedChild.toPath(), outside.toPath()) }.isSuccess)
        fixture.remove(removeChildren = { children ->
            assertTrue(children.contains(linkedChild))
            assertFalse(children.contains(outside))
            fixture.delete(children)
        })
        assertEquals("keep", data.readText())
        assertFalse(fixture.target.exists())
    }

    @Test
    fun stagingCleanupKeepsItsOwnMarkerUntilAllContentIsGone() = withFixture { fixture ->
        val marker = File(fixture.target, ".sillyclient-install-test").apply { writeText("staging-owner") }
        var committed = false
        InstanceRemoval.remove(fixture.target, fixture.instances,
            verifyIdentity = { assertEquals("staging-owner", marker.readText()) }, ensureActive = {},
            removeChildren = { children ->
                assertFalse(children.contains(marker))
                assertTrue(marker.isFile)
                children.forEach { child ->
                    if (child.isDirectory) assertTrue(ManagedFiles.deleteDirectory(child, fixture.target))
                    else Files.delete(child.toPath())
                }
            }, commit = { it() }, unregister = { committed = true }, identityFileName = marker.name)
        assertFalse(fixture.target.exists())
        assertTrue(committed)
    }

    @Test
    fun failedStagingCleanupPreservesItsMarkerForRetry() = withFixture { fixture ->
        val marker = File(fixture.target, ".sillyclient-install-test").apply { writeText("staging-owner") }
        assertThrows(IOException::class.java) {
            InstanceRemoval.remove(fixture.target, fixture.instances, {}, {},
                removeChildren = { throw IOException("simulated incomplete staging cleanup") },
                commit = { it() }, unregister = { throw AssertionError("Incomplete staging was committed") },
                identityFileName = marker.name)
        }
        assertEquals("staging-owner", marker.readText())
        assertTrue(fixture.target.isDirectory)
    }

    @Test
    fun stagingMarkerCannotEscapeItsDirectory() = withFixture { fixture ->
        assertThrows(IllegalArgumentException::class.java) {
            InstanceRemoval.remove(fixture.target, fixture.instances, {}, {}, {}, { it() }, {},
                identityFileName = "../outside")
        }
        fixture.verify()
        assertTrue(File(fixture.target, "data/chat.jsonl").isFile)
    }
}
