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
        assertThrows(IllegalArgumentException::class.java) {
            InstanceRename(paths, service).rename("stable-id", "Third Tavern", other.path, operation)
        }
        assertEquals(source, paths.serverDirFor("stable-id", create = false))
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
