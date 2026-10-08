package com.sillyclient.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class InstanceRelocationTest {
    private fun fixture(test: (File, RuntimePaths, OperationCoordinator, ProcessSupervisor) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)?.apply { mkdirs() }
        val root = if (parent == null) Files.createTempDirectory("instance-relocation-").toFile()
            else Files.createTempDirectory(parent.toPath(), "instance-relocation-").toFile()
        val home = File(root, "tarven")
        val paths = RuntimePaths(root, home, File(home, "bootstrap"), File(root, "external/instances"),
            File(home, "usr"), File(home, "usr/lib"), File(home, "tmp"), File(home, "logs"),
            File(root, "native"), File(root, "native/node"), legacyServersDir = File(home, "bootstrap/servers"))
        try {
            OperationCoordinator().use { operations ->
                ProcessSupervisor(operations).use { processes -> test(root, paths, operations, processes) }
            }
        } finally { root.deleteRecursively() }
    }

    private fun complete(directory: File): File = directory.apply {
        mkdirs()
        File(this, "server.js").writeText("source")
        File(this, "package.json").writeText("{\"version\":\"1.19.0\"}")
        File(this, "node_modules").mkdirs()
        File(this, "data/default-user/chats/log.jsonl").apply { parentFile!!.mkdirs(); writeText("chat history") }
    }

    private fun current(paths: RuntimePaths): File = complete(File(paths.serversDir, "Original Tavern")).also {
        paths.installLocations.registerCommitted("stable-id", it)
    }

    private fun legacySharedTree(paths: RuntimePaths, source: File): File {
        val lock = File(source, "package-lock.json").apply { writeText("{\"lockfileVersion\":3,\"packages\":{}}") }
        val key = requireNotNull(DependencyArchive(File(paths.tarvenHome, "dependency-archives")).lockKey(lock))
        return complete(File(paths.tarvenHome, "dependency-trees/$key"))
    }

    private fun copy(source: File, target: File) {
        source.listFiles().orEmpty().forEach { entry ->
            val destination = File(target, entry.name)
            if (entry.isDirectory) { destination.mkdir(); copy(entry, destination) }
            else entry.copyTo(destination)
        }
    }

    private fun cleanup(target: File, root: File, verify: () -> Unit) {
        InstanceRemoval.remove(target, root, verify, ensureActive = {},
            removeChildren = { children -> children.forEach { assertTrue(it.deleteRecursively()) } },
            commit = { it() }, unregister = {}, identityFileName = InstanceRelocation.OWNER_MARKER)
    }

    @Test
    fun physicalRenameKeepsTheImmutableIdAndUserHistory() = fixture { _, paths, operations, processes ->
        val source = current(paths)
        val service = InstanceRelocation(paths, operations, processes,
            copyVerified = { _, _, _ -> throw AssertionError("Same-volume rename must not copy dependencies") },
            sameFilesystem = { _, _ -> true })
        val operation = operations.begin("stable-id")
        val result = InstanceRename(paths, service).rename("stable-id", "New Tavern", operation = operation)
        assertEquals("stable-id", result.oldId)
        assertEquals(result.oldId, result.newId)
        assertEquals(File(paths.serversDir, "New Tavern").path, result.newPath)
        assertFalse(source.exists())
        assertEquals("chat history", File(result.newPath, "data/default-user/chats/log.jsonl").readText())
        assertEquals(File(result.newPath), paths.serverDirFor("stable-id", create = false))
        assertEquals(setOf("stable-id"), paths.installLocations.entries().keys)
    }

    @Test
    fun nameCollisionsAndMismatchedSourcePathsLeaveBothInstancesUntouched() = fixture { _, paths, operations, processes ->
        val source = current(paths)
        val other = complete(File(paths.serversDir, "Other Tavern"))
        paths.installLocations.registerCommitted("other-id", other)
        val service = InstanceRelocation(paths, operations, processes, sameFilesystem = { _, _ -> true })
        val operation = operations.begin("stable-id")
        assertThrows(IllegalArgumentException::class.java) {
            InstanceRename(paths, service).rename("stable-id", "Other Tavern", operation = operation)
        }
        // A stale remembered path cannot hijack the rename towards another
        // instance's directory: the registered location wins, the rename lands
        // on the new name beside the original directory.
        val renamed = InstanceRename(paths, service).rename("stable-id", "Third Tavern", other.path, operation)
        assertEquals(File(paths.serversDir, "Third Tavern").path, renamed.newPath)
        assertFalse(source.exists())
        assertEquals(other, paths.serverDirFor("other-id", create = false))
        assertTrue(operations.isCurrent(operation))
    }

    @Test
    fun samePathRenameDoesNotPublishOrCopy() = fixture { _, paths, operations, processes ->
        val source = current(paths)
        val service = InstanceRelocation(paths, operations, processes,
            copyVerified = { _, _, _ -> throw AssertionError("No copy expected") })
        val result = service.relocate("stable-id", source.path, operation = operations.begin("stable-id"))
        assertTrue(result.unchanged)
        assertEquals(source.path, result.newPath)
    }

    @Test
    fun legacySharedOnlyInstancesMoveFreelyInPlaceAndAcrossVolumes() = fixture { _, paths, operations, processes ->
        val source = current(paths)
        val shared = legacySharedTree(paths, source)
        assertTrue(File(source, "node_modules").delete())
        val moved = File(paths.installationsDir, "Moved Tavern")
        // A same-volume move publishes the very same files, so an instance whose
        // dependencies live on a retired shared tree can still be renamed/moved.
        val mover = InstanceRelocation(paths, operations, processes,
            copyVerified = { _, _, _ -> throw AssertionError("Same-volume move must not copy dependencies") },
            sameFilesystem = { _, _ -> true })
        val result = mover.relocate("stable-id", moved.path, operation = operations.begin("stable-id"))
        assertFalse(source.exists())
        assertEquals(moved, paths.serverDirFor("stable-id", create = false))
        assertEquals("chat history", File(moved, "data/default-user/chats/log.jsonl").readText())
        assertFalse(File(moved, "node_modules").exists())
        assertTrue(shared.isDirectory)
        assertTrue(paths.installLocations.retainedSources().isEmpty())

        // A cross-volume transport copies the directory as-is; dependency state
        // is the first launch's concern, never a relocation blocker.
        val destination = File(paths.installationsDir, "Copied Tavern")
        val crossVolume = InstanceRelocation(paths, operations, processes,
            copyVerified = { from, to, _ -> copy(from, to) },
            sameFilesystem = { _, _ -> false })
        val copied = crossVolume.relocate("stable-id", destination.path, operation = operations.begin("stable-id"))
        assertEquals(moved.path, copied.retainedSourcePath)
        assertEquals(destination, paths.serverDirFor("stable-id", create = false))
        assertFalse(File(destination, "node_modules").exists())
        assertTrue(paths.installLocations.retainedSources().isNotEmpty())
    }

    @Test
    fun pendingDependenciesMoveVerbatimWhenRenamedInPlace() = fixture { _, paths, operations, processes ->
        val source = current(paths)
        val marker = File(source, InstanceInstaller.DEPENDENCY_MARKER).apply {
            writeText("sillyclient-dependencies-v1\n")
        }
        val destination = File(paths.serversDir, "Renamed Tavern")
        val result = InstanceRelocation(paths, operations, processes, sameFilesystem = { _, _ -> true })
            .relocate("stable-id", destination.path, operation = operations.begin("stable-id"))
        assertFalse(source.exists())
        assertEquals(destination, paths.serverDirFor("stable-id", create = false))
        val movedMarker = File(destination, InstanceInstaller.DEPENDENCY_MARKER)
        assertTrue(movedMarker.isFile)
        assertEquals("sillyclient-dependencies-v1\n", movedMarker.readText())
    }

    @Test
    fun aCopyMissingLocalDependenciesStillPublishesWithTheSourceRetained() = fixture { _, paths, operations, processes ->
        val source = current(paths)
        legacySharedTree(paths, source)
        val destination = File(paths.installationsDir, "Incomplete Tavern")
        val service = InstanceRelocation(paths, operations, processes,
            copyVerified = { from, to, _ -> copy(from, to); assertTrue(File(to, "node_modules").delete()) },
            sameFilesystem = { _, _ -> false },
            removeStaging = { target, root, _, verify -> cleanup(target, root, verify) })
        val result = service.relocate("stable-id", destination.path, operation = operations.begin("stable-id"))
        assertEquals(source.path, result.retainedSourcePath)
        assertEquals(destination, paths.serverDirFor("stable-id", create = false))
        assertFalse(File(destination, "node_modules").exists())
        assertEquals("chat history", File(destination, "data/default-user/chats/log.jsonl").readText())
        assertTrue(source.isDirectory)
        assertEquals(setOf(source), paths.installLocations.retainedSources())
    }

    @Test
    fun crossVolumeRelocationPreservesSourceAndDoesNotRediscoverItAsAnotherCard() = fixture { _, paths, operations, processes ->
        val source = current(paths)
        val destination = File(paths.installationsDir, "Moved Tavern")
        val service = InstanceRelocation(paths, operations, processes,
            copyVerified = { from, to, _ -> copy(from, to) }, sameFilesystem = { _, _ -> false })
        val result = service.relocate("stable-id", destination.path, operation = operations.begin("stable-id"))
        assertEquals(source.path, result.retainedSourcePath)
        assertTrue(source.isDirectory)
        assertEquals("chat history", File(destination, "data/default-user/chats/log.jsonl").readText())
        assertEquals(destination, paths.serverDirFor("stable-id", create = false))
        assertEquals(setOf(source), paths.installLocations.retainedSources())
        val repository = InstanceRepository(paths.serversDir, installLocations = paths.installLocations)
        assertEquals(listOf("stable-id"), repository.scan().map { it.instanceId })
        assertFalse(File(destination, InstanceRelocation.OWNER_MARKER).exists())
    }

    @Test
    fun failedOrCancelledCopyPreservesTheSourceAndRegistration() = fixture { _, paths, operations, processes ->
        val source = current(paths)
        val destination = File(paths.installationsDir, "Moved Tavern")
        var cleaned = 0
        var copyReturned = false
        val cleanup: (File, File, String, () -> Unit) -> Unit = { target, root, _, verify ->
            assertTrue(copyReturned)
            cleaned += 1
            cleanup(target, root, verify)
        }
        val failing = InstanceRelocation(paths, operations, processes,
            copyVerified = { _, to, _ ->
                try { File(to, "partial").writeText("partial"); throw IOException("synthetic failure") }
                finally { copyReturned = true }
            }, sameFilesystem = { _, _ -> false }, removeStaging = cleanup)
        assertThrows(IOException::class.java) {
            failing.relocate("stable-id", destination.path, operation = operations.begin("stable-id"))
        }
        val cancelled = InstanceRelocation(paths, operations, processes,
            copyVerified = { from, to, _ -> copy(from, to); operations.cancel() }, sameFilesystem = { _, _ -> false },
            removeStaging = cleanup)
        assertThrows(CancellationException::class.java) {
            cancelled.relocate("stable-id", destination.path, operation = operations.begin("stable-id"))
        }
        assertEquals(source, paths.serverDirFor("stable-id", create = false))
        assertEquals("chat history", File(source, "data/default-user/chats/log.jsonl").readText())
        assertFalse(destination.exists())
        assertTrue(paths.installationsDir.listFiles().orEmpty().isEmpty())
        assertEquals(2, cleaned)
    }

    @Test
    fun relocationRejectsNestedRootsAndOtherInstanceOperations() = fixture { _, paths, operations, processes ->
        val source = current(paths)
        val service = InstanceRelocation(paths, operations, processes)
        val operation = operations.begin("stable-id")
        for (target in listOf(source.parentFile!!.path, File(source, "nested").path, "content://tree/path")) {
            assertThrows(IllegalArgumentException::class.java) { service.relocate("stable-id", target, operation = operation) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.relocate("stable-id", operation = operations.begin("other-id"))
        }
        assertEquals(source, paths.serverDirFor("stable-id", create = false))
    }

    @Test
    fun relocationPlanCannotOverwriteAChangedRegistry() = fixture { _, paths, _, _ ->
        val source = current(paths)
        val first = paths.installLocations.planRelocation("stable-id", File(paths.serversDir, "First").path)
        val second = paths.installLocations.planRelocation("stable-id", File(paths.serversDir, "Second").path)
        paths.installLocations.commitRelocation(second, false,
            { Files.move(source.toPath(), second.target.toPath(), StandardCopyOption.ATOMIC_MOVE) },
            { throw AssertionError("Unexpected rollback") })
        var published = false
        assertThrows(IllegalArgumentException::class.java) {
            paths.installLocations.commitRelocation(first, false, { published = true }, {})
        }
        assertFalse(published)
        assertEquals(second.target, paths.serverDirFor("stable-id", create = false))
    }

    @Test
    fun aRegistryWriteFailureMovesTheSameDirectoryBack() = fixture { _, paths, _, _ ->
        val source = current(paths)
        val plan = paths.installLocations.planRelocation("stable-id", File(paths.serversDir, "Moved").path)
        val registryFile = File(paths.tarvenHome, "install-locations.json")
        val registryBefore = registryFile.readText()
        var rolledBack = false
        assertThrows(Exception::class.java) {
            paths.installLocations.commitRelocation(plan, false, {
                Files.move(source.toPath(), plan.target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                check(registryFile.delete())
                check(registryFile.mkdir())
            }, {
                rolledBack = true
                Files.move(plan.target.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE)
            })
        }
        assertTrue(rolledBack)
        assertFalse(plan.target.exists())
        assertEquals("chat history", File(source, "data/default-user/chats/log.jsonl").readText())
        check(registryFile.delete())
        registryFile.writeText(registryBefore)
        assertEquals(source, paths.serverDirFor("stable-id", create = false))
    }

    @Test
    fun legacyDiscoveryDoesNotMoveFilesOrRegisterAnything() = fixture { _, paths, operations, processes ->
        val source = complete(File(requireNotNull(paths.legacyServersDir), "local-old"))
        val items = InstanceRelocation(paths, operations, processes).legacyInstances()
        assertEquals(1, items.size)
        assertEquals("local-old", items.single().instanceId)
        assertEquals(source.path, items.single().currentPath)
        assertEquals(File(paths.serversDir, "local-old").path, items.single().targetPath)
        assertFalse(File(paths.tarvenHome, "install-locations.json").exists())
        assertFalse(File(source, ".sc-identity").exists())
    }

    @Test
    fun legacyDiscoveryIncludesRegisteredRestrictedStorageWithoutChangingIt() = fixture { root, originalPaths, operations, processes ->
        val restrictedRoot = File(root, "Android/data/com.sillyclient/files/instances")
        val paths = originalPaths.copy(legacyExternalServersDir = restrictedRoot,
            customRootsProvider = { listOf(restrictedRoot) })
        val source = complete(File(restrictedRoot, "My Tavern"))
        paths.installLocations.registerCommitted("restricted-id", source)
        val registry = File(paths.tarvenHome, "install-locations.json")
        val before = registry.readText() to registry.lastModified()
        val filesBefore = source.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(source).path to (it.readText() to it.lastModified()) }

        val item = InstanceRelocation(paths, operations, processes).legacyInstances().single()

        assertEquals("restricted-id", item.instanceId)
        assertEquals("My Tavern", item.name)
        assertEquals(source.path, item.currentPath)
        assertEquals(File(paths.serversDir, "My Tavern").path, item.targetPath)
        assertFalse(File(item.targetPath).exists())
        assertEquals(before, registry.readText() to registry.lastModified())
        assertEquals(filesBefore, source.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(source).path to (it.readText() to it.lastModified()) })
        assertEquals(mapOf("restricted-id" to source), paths.installLocations.entries())
    }

    @Test
    fun legacyDiscoveryExcludesPublicCustomAndUnregisteredRestrictedInstances() = fixture { root, originalPaths, operations, processes ->
        val externalFiles = File(root, "Android/data/com.sillyclient/files")
        val restrictedRoot = File(externalFiles, "instances")
        val customRoot = File(externalFiles, "instances-other")
        val paths = originalPaths.copy(legacyExternalServersDir = restrictedRoot,
            customRootsProvider = { listOf(externalFiles) })
        val privateSource = complete(File(requireNotNull(paths.legacyServersDir), "private-old"))
        val restricted = complete(File(restrictedRoot, "Restricted Tavern"))
        paths.installLocations.registerCommitted("restricted-id", restricted)
        paths.installLocations.registerCommitted("custom-id", complete(File(customRoot, "Custom Tavern")))
        current(paths)
        complete(File(restrictedRoot, "Unregistered Tavern"))

        val items = InstanceRelocation(paths, operations, processes).legacyInstances()

        assertEquals(setOf("private-old", "restricted-id"), items.map { it.instanceId }.toSet())
        assertEquals(setOf(privateSource.path, restricted.path), items.map { it.currentPath }.toSet())
        assertEquals(setOf("restricted-id", "custom-id", "stable-id"), paths.installLocations.entries().keys)
    }

    @Test
    fun explicitlyRelocatedRestrictedInstanceLeavesTheLegacyListAndPreservesIdentity() = fixture { root, originalPaths, operations, processes ->
        val restrictedRoot = File(root, "Android/data/com.sillyclient/files/instances")
        val paths = originalPaths.copy(legacyExternalServersDir = restrictedRoot,
            customRootsProvider = { listOf(restrictedRoot) })
        val source = complete(File(restrictedRoot, "My Tavern"))
        paths.installLocations.registerCommitted("restricted-id", source)
        val identity = File(source, ".sc-identity").readText()
        val service = InstanceRelocation(paths, operations, processes,
            copyVerified = { _, _, _ -> throw AssertionError("Same-volume relocation must not copy") },
            sameFilesystem = { _, _ -> true }, validateSource = { _, _ -> })
        assertEquals(1, service.legacyInstances().size)

        val result = service.relocate("restricted-id", installPath = source.path,
            operation = operations.begin("restricted-id"))

        assertEquals("restricted-id", result.instanceId)
        assertEquals(File(paths.serversDir, "My Tavern").path, result.newPath)
        assertFalse(source.exists())
        assertEquals(identity, File(result.newPath, ".sc-identity").readText())
        assertEquals("chat history", File(result.newPath, "data/default-user/chats/log.jsonl").readText())
        assertEquals(File(result.newPath), paths.serverDirFor("restricted-id", create = false))
        assertTrue(service.legacyInstances().isEmpty())
    }

    private class SimulatedInterruption : Error("Simulated process exit")

    @Test
    fun interruptedRenameCompletesTheRegistryOnNextOpen() = fixture { _, paths, _, _ ->
        val source = current(paths)
        val plan = paths.installLocations.planRelocation("stable-id", File(paths.serversDir, "Recovered").path)
        assertThrows(SimulatedInterruption::class.java) {
            paths.installLocations.commitRelocation(plan, false, {
                Files.move(source.toPath(), plan.target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                throw SimulatedInterruption()
            }, { throw AssertionError("A terminated process cannot roll back") })
        }
        assertTrue(File(paths.tarvenHome, "install-location-move.json").isFile)
        assertEquals(plan.target, paths.copy().serverDirFor("stable-id", create = false))
        assertFalse(source.exists())
        assertEquals("chat history", File(plan.target, "data/default-user/chats/log.jsonl").readText())
        assertFalse(File(paths.tarvenHome, "install-location-move.json").exists())
    }

    @Test
    fun interruptedCrossVolumePublicationRetainsTheOriginalAndRemovesOnlyItsOwnerMarker() = fixture { _, paths, _, _ ->
        val source = current(paths)
        val plan = paths.installLocations.planRelocation("stable-id", File(paths.installationsDir, "Recovered").path)
        val owner = UUID.randomUUID().toString()
        assertThrows(SimulatedInterruption::class.java) {
            paths.installLocations.commitRelocation(plan, true, {
                plan.target.mkdirs()
                copy(source, plan.target)
                File(plan.target, InstanceRelocation.OWNER_MARKER).writeText(owner)
                throw SimulatedInterruption()
            }, { throw AssertionError("A terminated process cannot roll back") }, stagingOwner = owner)
        }
        val recovered = paths.copy()
        assertEquals(plan.target, recovered.serverDirFor("stable-id", create = false))
        assertEquals(setOf(source), recovered.installLocations.retainedSources())
        assertEquals("chat history", File(source, "data/default-user/chats/log.jsonl").readText())
        assertEquals("chat history", File(plan.target, "data/default-user/chats/log.jsonl").readText())
        assertFalse(File(plan.target, InstanceRelocation.OWNER_MARKER).exists())
    }

    @Test
    fun interruptedPublicationRecoveryAcceptsADependencylessCopy() = fixture { _, paths, _, _ ->
        val source = current(paths)
        legacySharedTree(paths, source)
        val plan = paths.installLocations.planRelocation("stable-id", File(paths.installationsDir, "Incomplete recovery").path)
        val registry = File(paths.tarvenHome, "install-locations.json")
        assertThrows(SimulatedInterruption::class.java) {
            paths.installLocations.commitRelocation(plan, true, {
                plan.target.mkdirs()
                copy(source, plan.target)
                assertTrue(File(plan.target, "node_modules").delete())
                throw SimulatedInterruption()
            }, { throw AssertionError("A terminated process cannot roll back") })
        }
        // Journal recovery moves the registration to the copied directory even
        // without local dependencies; the first launch finishes preparation.
        val recovered = paths.copy()
        assertEquals(plan.target, recovered.serverDirFor("stable-id", create = false))
        assertEquals(source, recovered.installLocations.retainedSources().single())
        assertEquals("chat history", File(plan.target, "data/default-user/chats/log.jsonl").readText())
        assertFalse(File(paths.tarvenHome, "install-location-move.json").isFile)
    }

    @Test
    fun interruptionBeforePublicationKeepsTheSourceRegistration() = fixture { _, paths, _, _ ->
        val source = current(paths)
        val plan = paths.installLocations.planRelocation("stable-id", File(paths.serversDir, "Never published").path)
        assertThrows(SimulatedInterruption::class.java) {
            paths.installLocations.commitRelocation(plan, false, { throw SimulatedInterruption() }, {})
        }
        assertEquals(source, paths.copy().serverDirFor("stable-id", create = false))
        assertFalse(plan.target.exists())
        assertFalse(File(paths.tarvenHome, "install-location-move.json").exists())
    }

    @Test
    fun completedRegistryWithAnUnremovedJournalIsIdempotentlyRecovered() = fixture { _, paths, _, _ ->
        val source = current(paths)
        val plan = paths.installLocations.planRelocation("stable-id", File(paths.serversDir, "Completed").path)
        val journal = File(paths.tarvenHome, "install-location-move.json")
        var pending = ""
        paths.installLocations.commitRelocation(plan, false, {
            pending = journal.readText()
            Files.move(source.toPath(), plan.target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }, { throw AssertionError("No rollback expected") })
        journal.writeText(pending)
        assertEquals(plan.target, paths.copy().serverDirFor("stable-id", create = false))
        assertFalse(journal.exists())
    }

    @Test
    fun replacedTargetIdentityDuringRecoveryPreservesEveryFileAndTheJournal() = fixture { _, paths, _, _ ->
        val source = current(paths)
        val plan = paths.installLocations.planRelocation("stable-id", File(paths.installationsDir, "Replaced").path)
        val registry = File(paths.tarvenHome, "install-locations.json")
        val before = registry.readText()
        assertThrows(SimulatedInterruption::class.java) {
            paths.installLocations.commitRelocation(plan, true, {
                plan.target.mkdirs()
                copy(source, plan.target)
                throw SimulatedInterruption()
            }, {})
        }
        File(plan.target, ".sc-identity").writeText(UUID.randomUUID().toString())
        assertThrows(IllegalArgumentException::class.java) { paths.copy().installLocations.entries() }
        assertEquals(before, registry.readText())
        assertEquals("chat history", File(source, "data/default-user/chats/log.jsonl").readText())
        assertEquals("chat history", File(plan.target, "data/default-user/chats/log.jsonl").readText())
        assertTrue(File(paths.tarvenHome, "install-location-move.json").isFile)
    }

    @Test
    fun ambiguousSameVolumeRecoveryNeverChoosesBetweenTwoCopies() = fixture { _, paths, _, _ ->
        val source = current(paths)
        val plan = paths.installLocations.planRelocation("stable-id", File(paths.serversDir, "Ambiguous").path)
        assertThrows(SimulatedInterruption::class.java) {
            paths.installLocations.commitRelocation(plan, false, {
                plan.target.mkdirs()
                copy(source, plan.target)
                throw SimulatedInterruption()
            }, {})
        }
        assertThrows(IllegalArgumentException::class.java) { paths.copy().installLocations.entries() }
        assertTrue(source.isDirectory)
        assertTrue(plan.target.isDirectory)
        assertTrue(File(paths.tarvenHome, "install-location-move.json").isFile)
    }

    @Test
    fun linkedRecoveryDestinationIsRejectedWithoutFollowingOrDeletingIt() = fixture { _, paths, _, _ ->
        val source = current(paths)
        val plan = paths.installLocations.planRelocation("stable-id", File(paths.serversDir, "Linked").path)
        assertThrows(SimulatedInterruption::class.java) {
            paths.installLocations.commitRelocation(plan, false, {
                Files.move(source.toPath(), plan.target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                throw SimulatedInterruption()
            }, {})
        }
        val actual = File(paths.serversDir, "Moved elsewhere")
        Files.move(plan.target.toPath(), actual.toPath(), StandardCopyOption.ATOMIC_MOVE)
        assumeTrue("Symlink creation is not available on this host",
            runCatching { Files.createSymbolicLink(plan.target.toPath(), actual.toPath()) }.isSuccess)
        try {
            assertThrows(IllegalArgumentException::class.java) { paths.copy().installLocations.entries() }
            assertEquals("chat history", File(actual, "data/default-user/chats/log.jsonl").readText())
            assertTrue(File(paths.tarvenHome, "install-location-move.json").isFile)
        } finally { Files.delete(plan.target.toPath()) }
    }

    @Test
    fun malformedOversizedAndUnsafeJournalsFailWithoutChangingTheRegistration() = fixture { root, paths, _, _ ->
        val source = current(paths)
        val journal = File(paths.tarvenHome, "install-location-move.json")
        val plan = paths.installLocations.planRelocation("stable-id", File(paths.serversDir, "Target").path)
        assertThrows(SimulatedInterruption::class.java) {
            paths.installLocations.commitRelocation(plan, false, { throw SimulatedInterruption() }, {})
        }
        val valid = journal.readText()
        val registry = File(paths.tarvenHome, "install-locations.json")
        val before = registry.readText()
        for (invalid in listOf("{", "x".repeat(70_000), JSONObject(valid).put("target", File(root, "outside").path).toString(),
            JSONObject(valid).put("retainSource", "true").toString())) {
            journal.writeText(invalid)
            assertThrows(Exception::class.java) { paths.copy().installLocations.entries() }
            assertEquals(before, registry.readText())
            assertEquals("chat history", File(source, "data/default-user/chats/log.jsonl").readText())
        }
        journal.writeText(valid)
        assertEquals(source, paths.copy().serverDirFor("stable-id", create = false))
    }

    private fun retireRelocatedInstance(paths: RuntimePaths, operations: OperationCoordinator, processes: ProcessSupervisor): File {
        val source = current(paths)
        val destination = File(paths.installationsDir, "Retired Tavern")
        InstanceRelocation(paths, operations, processes, copyVerified = { from, to, _ -> copy(from, to) },
            sameFilesystem = { _, _ -> false }).relocate("stable-id", destination.path, operation = operations.begin("stable-id"))
        assertTrue(destination.deleteRecursively())
        paths.installLocations.unregisterAfterDelete("stable-id", destination)
        return source
    }

    @Test
    fun deletingTheActiveCopyPermanentlyExcludesItsUnmodifiedRetainedSourceFromScanning() = fixture { _, paths, operations, processes ->
        val source = current(paths)
        val before = source.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(source).path to (it.readText() to it.lastModified()) }
        val destination = File(paths.installationsDir, "Retired Tavern")
        InstanceRelocation(paths, operations, processes, copyVerified = { from, to, _ -> copy(from, to) },
            sameFilesystem = { _, _ -> false }).relocate("stable-id", destination.path, operation = operations.begin("stable-id"))
        assertTrue(destination.deleteRecursively())
        paths.installLocations.unregisterAfterDelete("stable-id", destination)
        val restarted = paths.copy()
        assertTrue(restarted.installLocations.entries().isEmpty())
        assertEquals(setOf(source), restarted.installLocations.retainedSources())
        val repository = InstanceRepository(paths.serversDir, installLocations = restarted.installLocations,
            legacyServersRoot = paths.legacyServersDir)
        assertTrue(repository.scan().isEmpty())
        val other = complete(File(paths.serversDir, "Other Tavern"))
        restarted.installLocations.registerCommitted("other-id", other)
        assertEquals(listOf("other-id"), repository.scan().map { it.instanceId })
        assertEquals(setOf(source), paths.copy().installLocations.retainedSources())
        assertEquals(before, source.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(source).path to (it.readText() to it.lastModified()) })
    }

    @Test
    fun retiredSourcesCannotBeTakenOverReplacedOrUsedAsRelocationDestinations() = fixture { _, paths, operations, processes ->
        val source = retireRelocatedInstance(paths, operations, processes)
        val restarted = paths.copy()
        assertThrows(IllegalArgumentException::class.java) { restarted.installLocations.registerCommitted("new-id", source) }
        assertThrows(IllegalArgumentException::class.java) { restarted.installLocations.resolve("new-id", source.path) }
        assertThrows(IllegalArgumentException::class.java) { restarted.installLocations.resolve(source.name) }
        var published = false
        assertThrows(IllegalArgumentException::class.java) {
            restarted.installLocations.commitNewInstallation("new-id", source, { published = true }, {})
        }
        assertFalse(published)
        val other = complete(File(paths.serversDir, "Other Tavern"))
        restarted.installLocations.registerCommitted("other-id", other)
        assertThrows(IllegalArgumentException::class.java) {
            restarted.installLocations.planRelocation("other-id", source.path)
        }
        assertThrows(IllegalArgumentException::class.java) {
            restarted.installLocations.planRelocation("other-id", File(source, "nested").path)
        }
        assertEquals("chat history", File(source, "data/default-user/chats/log.jsonl").readText())
        assertEquals(other, restarted.serverDirFor("other-id", create = false))
        assertEquals(setOf(source), restarted.installLocations.retainedSources())
    }

    @Test
    fun malformedAndOverlappingRetiredSourcesAreRejectedWithoutTouchingTheirFiles() = fixture { root, paths, operations, processes ->
        val source = retireRelocatedInstance(paths, operations, processes)
        val registry = File(paths.tarvenHome, "install-locations.json")
        val before = registry.readText()
        val invalidValues: List<Any> = listOf("not-an-array", JSONArray(listOf(source.path, source.path)),
            JSONArray(listOf(source.path, File(source, "nested").path)), JSONArray(listOf(paths.serversDir.path)),
            JSONArray(listOf(File(root, "outside").path)), JSONArray(List(4097) { source.path }))
        for (invalid in invalidValues) {
            registry.writeText(JSONObject(before).put("retiredSources", invalid).toString())
            assertThrows(Exception::class.java) { paths.copy().installLocations.entries() }
            assertEquals("chat history", File(source, "data/default-user/chats/log.jsonl").readText())
        }
        registry.writeText(before)
        assertEquals(setOf(source), paths.copy().installLocations.retainedSources())
    }
}
