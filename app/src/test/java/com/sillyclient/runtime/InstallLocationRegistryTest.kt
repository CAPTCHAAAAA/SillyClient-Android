package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class InstallLocationRegistryTest {
    private fun withRoot(test: (File, RuntimePaths) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent == null) Files.createTempDirectory("install-locations-").toFile()
            else Files.createTempDirectory(parent.toPath(), "install-locations-").toFile()
        try { test(root, paths(root)) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    private fun paths(root: File): RuntimePaths {
        val home = File(root, "tarven")
        val bootstrap = File(home, "bootstrap")
        val usr = File(home, "usr")
        val native = File(root, "native")
        return RuntimePaths(root, home, bootstrap, File(bootstrap, "servers"), usr, File(usr, "lib"),
            File(home, "tmp"), File(home, "logs"), native, File(native, "node"))
    }

    private fun write(directory: File, name: String, text: String = "fixture"): File =
        File(directory, name).apply { requireNotNull(parentFile).mkdirs(); writeText(text) }

    private fun complete(directory: File): File = directory.apply {
        mkdirs()
        write(this, "server.js", "server source")
        write(this, "package.json", """{"version":"1.19.0"}""")
        File(this, "node_modules").mkdirs()
        write(this, "config.yaml", "dataRoot: ./data\n")
    }

    private fun installer(paths: RuntimePaths) =
        InstanceInstaller(paths.serversDir, paths.installLocations)

    @Test
    fun resolvingNeverCreatesOrRegistersAnInstallation() = withRoot { _, paths ->
        val registry = paths.installLocations
        val selected = File(paths.installationsDir, "custom-directory")
        assertEquals(selected, paths.launchDirectoryFor("immutable-id", selected.absolutePath))
        assertTrue(registry.entries().isEmpty())
        assertFalse(paths.tarvenHome.exists())
        assertFalse(selected.exists())
    }

    @Test
    fun rootModeAppendsTheStableIdentityWhileExactModeKeepsTheWholePath() = withRoot { _, paths ->
        val root = File(paths.installationsDir, "chosen-root")
        assertEquals(File(root, "immutable-id"), paths.launchDirectoryFor("immutable-id", root.absolutePath, "root"))
        assertEquals(root, paths.launchDirectoryFor("immutable-id", root.absolutePath, "exact"))
        assertEquals(File(paths.installationsDir, "immutable-id"),
            paths.launchDirectoryFor("immutable-id", paths.installationsDir.absolutePath, "root"))
        assertThrows(IllegalArgumentException::class.java) {
            paths.launchDirectoryFor("immutable-id", paths.installationsDir.absolutePath, "exact")
        }
        assertFalse(root.exists())
    }

    @Test
    fun rejectsInvalidModesUrisForeignRootsRuntimePathsAndOtherDefaultIdentities() = withRoot { root, paths ->
        for (path in listOf("content://provider/tree/tavern", "file:${paths.installationsDir.path}", "relative",
            "D:\\ImportedWindowsTavern", root.path, paths.tarvenHome.path, paths.usrDir.path, paths.tmpDir.path,
            paths.logsDir.path, File(root, "external-app-files/instance").path, File(paths.serversDir, "other-id/nested").path,
            File(paths.installationsDir, "../usr/instance").path, "${paths.installationsDir.path}\ninstance")) {
            assertThrows(IllegalArgumentException::class.java) { paths.launchDirectoryFor("immutable-id", path) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            paths.launchDirectoryFor("immutable-id", null, "unsupported")
        }
        assertFalse(paths.tarvenHome.exists())
    }

    @Test
    fun quotedPathsAndDirectoryDisplayNamesDoNotChangeInstanceIdentity() = withRoot { root, paths ->
        val target = File(paths.installationsDir, "descriptive-name")
        val resolved = paths.launchDirectoryFor("stable-id", " '${File(target, ".").absolutePath}' ")
        assertEquals(target, resolved)
        complete(target)
        paths.installLocations.registerCommitted("stable-id", target)
        val restarted = paths(root)
        val metadata = InstanceRepository(restarted.serversDir, installLocations = restarted.installLocations).scan().single()
        assertEquals("stable-id", metadata.instanceId)
        assertEquals(target.absolutePath, metadata.path)
        assertNotEquals(target.name, metadata.instanceId)
        assertEquals(target, restarted.serverDirFor("stable-id", create = false))
    }

    @Test
    fun newDefaultNamesAreReadableAndCollisionsUseAStableIdentitySuffix() = withRoot { _, paths ->
        val first = paths.launchDirectoryFor("local-123", null, displayName = "My Tavern")
        assertEquals(File(paths.serversDir, "My Tavern"), first)
        complete(first)
        paths.installLocations.registerCommitted("local-123", first)
        val second = paths.launchDirectoryFor("local-456", null, displayName = "My Tavern")
        assertTrue(second.name.matches(Regex("My Tavern-[0-9a-f]{8}")))
        assertEquals(second, paths.launchDirectoryFor("local-456", null, displayName = "My Tavern"))
        assertEquals(first, paths.launchDirectoryFor("local-123", null, displayName = "Renamed card"))
        assertEquals("local-123", InstanceRepository(paths.serversDir,
            installLocations = paths.installLocations).scan().single().instanceId)
    }

    @Test
    fun selectedRootUsesDisplayNameAndRegisteredParentDoesNotRelocateOnRename() = withRoot { _, paths ->
        val root = File(paths.installationsDir, "chosen-root")
        val target = paths.launchDirectoryFor("stable-id", root.path, "root", "Readable Tavern")
        assertEquals(File(root, "Readable Tavern"), target)
        complete(target)
        paths.installLocations.registerCommitted("stable-id", target)
        assertEquals(target, paths.launchDirectoryFor("stable-id", root.path, "root", "New title"))
        // A stale remembered root cannot redirect a registered instance: the
        // registry directory always wins.
        assertEquals(target, paths.launchDirectoryFor("stable-id", File(paths.installationsDir, "different-root").path, "root", "New title"))
    }

    @Test
    fun unregisteredNameCollisionNeverAdoptsExistingData() = withRoot { _, paths ->
        val existing = complete(File(paths.serversDir, "My Tavern"))
        write(existing, "data/chat.jsonl", "preserve")
        val destination = paths.launchDirectoryFor("new-id", null, displayName = "My Tavern")
        assertNotEquals(existing, destination)
        assertEquals("preserve", File(existing, "data/chat.jsonl").readText())
        assertThrows(IllegalArgumentException::class.java) {
            paths.launchDirectoryFor("new-id", existing.path)
        }
        assertTrue(paths.installLocations.entries().isEmpty())
    }

    @Test
    fun privateLegacyInstancesRemainDiscoverableWithoutMutatingOrMovingThem() = withRoot { root, paths ->
        val externalRoot = File(root, "external/instances")
        val externalPaths = paths.copy(serversDir = externalRoot, legacyServersDir = paths.serversDir)
        val old = complete(File(paths.serversDir, "local-123"))
        val repository = InstanceRepository(externalRoot, installLocations = externalPaths.installLocations,
            legacyServersRoot = paths.serversDir)
        assertEquals(old.path, repository.scan().single().path)
        assertEquals(old, externalPaths.launchDirectoryFor("local-123", null, displayName = "Readable Tavern"))
        assertFalse(File(paths.tarvenHome, "install-locations.json").exists())
        assertFalse(File(old, ".sc-identity").exists())
        assertFalse(externalRoot.exists())
    }

    @Test
    fun siblingInstallStagesAreNeverExposedAsInstances() = withRoot { _, paths ->
        complete(File(paths.serversDir, ".sillyclient-install-12345678"))
        complete(File(paths.serversDir, "local-123"))
        val repository = InstanceRepository(paths.serversDir, installLocations = paths.installLocations)
        assertEquals(listOf("local-123"), repository.scan().map { it.instanceId })
        assertTrue(paths.installLocations.entries().isEmpty())
    }

    @Test
    fun oldPrivateCustomInstallationsStillResolveAfterDefaultRootMovesExternal() = withRoot { root, paths ->
        val target = complete(File(paths.installationsDir, "old-private-custom"))
        paths.installLocations.registerCommitted("stable-id", target)
        val externalPaths = paths.copy(serversDir = File(root, "external/instances"), legacyServersDir = paths.serversDir)
        assertEquals(target, externalPaths.launchDirectoryFor("stable-id", null, displayName = "Readable Tavern"))
    }

    @Test
    fun configuredRootStillAcceptsHistoricalDefaultDirectoriesAsInstanceLocations() = withRoot { root, paths ->
        // Instances created by earlier builds under the app-private or public
        // default roots must not become "illegal paths" once another root is
        // configured: one such entry used to poison every registry read, so
        // scans, migrations and deletions all failed (or crashed the app).
        val chosenRoot = File(root, "chosen/instances")
        val privateDefault = File(root, "files/instances")
        val publicDefault = File(root, "storage/SillyClient/instances")
        val historyAware = paths.copy(
            serversDir = chosenRoot,
            customRootsProvider = { listOf(privateDefault, publicDefault) }
        )
        assertEquals(privateDefault,
            historyAware.installLocations.allowedRootFor(File(privateDefault, "not-created-yet")))
        val privateInstance = complete(File(privateDefault, "old-private"))
        historyAware.installLocations.registerCommitted("old-private", privateInstance)
        val publicInstance = complete(File(publicDefault, "old-public"))
        historyAware.installLocations.registerCommitted("old-public", publicInstance)
        assertEquals(setOf("old-private", "old-public"), historyAware.installLocations.entries().keys)
        assertEquals(privateInstance, historyAware.launchDirectoryFor("old-private", null))
        assertEquals(publicInstance, historyAware.launchDirectoryFor("old-public", null))
        val intruder = complete(File(root, "outside/instance"))
        assertThrows(IllegalArgumentException::class.java) {
            historyAware.installLocations.registerCommitted("intruder", intruder)
        }
    }

    @Test
    fun registryContainingForeignPathsFailsWithoutRecursiveFallback() = withRoot { root, paths ->
        val target = complete(File(paths.installationsDir, "registered"))
        paths.installLocations.registerCommitted("stable-id", target)
        val registryFile = File(paths.tarvenHome, "install-locations.json")
        val document = JSONObject(registryFile.readText())
        document.getJSONArray("locations").getJSONObject(0).put("path", File(root, "outside/instance").path)
        registryFile.writeText(document.toString())
        assertThrows(IllegalArgumentException::class.java) { paths.installLocations.entries() }
        assertThrows(IllegalArgumentException::class.java) { paths.launchDirectoryFor("stable-id", null) }
    }

    @Test
    fun newRegistrationUsesPersistentMarkerAndRejectsMarkerTampering() = withRoot { _, paths ->
        val target = complete(File(paths.installationsDir, "registered"))
        paths.installLocations.registerCommitted("stable-id", target)
        val marker = File(target, ".sc-identity")
        assertEquals(36L, marker.length())
        val savedKey = JSONObject(File(paths.tarvenHome, "install-locations.json").readText())
            .getJSONArray("locations").getJSONObject(0).getString("fileKey")
        assertEquals("marker:${marker.readText()}", savedKey)
        marker.writeText("00000000-0000-0000-0000-000000000000")
        assertThrows(IllegalArgumentException::class.java) { paths.installLocations.entries() }
        assertEquals("server source", File(target, "server.js").readText())
    }

    @Test
    fun separateRuntimePathsObjectsAlwaysReadFreshRegistrationChanges() = withRoot { root, first ->
        val second = paths(root)
        assertTrue(second.installLocations.entries().isEmpty())
        val target = complete(File(first.installationsDir, "display-name"))
        first.installLocations.registerCommitted("stable-id", target)
        assertEquals(target, second.launchDirectoryFor("stable-id", null))
        assertTrue(ManagedFiles.deleteDirectory(target, second.installLocations.allowedRootFor(target)))
        second.installLocations.unregisterAfterDelete("stable-id", target)
        assertTrue(first.installLocations.entries().isEmpty())
    }

    @Test
    fun registeredAndLegacyInstancesCannotRelocate() = withRoot { _, paths ->
        val target = complete(File(paths.installationsDir, "display-name"))
        paths.installLocations.registerCommitted("stable-id", target)
        assertEquals(target, paths.launchDirectoryFor("stable-id", target.path))
        // Stale launch paths resolve to the registered directory, never relocate it.
        assertEquals(target, paths.launchDirectoryFor("stable-id", File(paths.installationsDir, "elsewhere").path))
        assertThrows(IllegalArgumentException::class.java) {
            paths.installLocations.registerCommitted("stable-id", complete(File(paths.installationsDir, "elsewhere")))
        }
        val legacy = complete(File(paths.serversDir, "legacy-id"))
        assertEquals(legacy, paths.launchDirectoryFor("legacy-id", null))
        assertEquals(legacy, paths.launchDirectoryFor("legacy-id", legacy.path))
        assertThrows(IllegalArgumentException::class.java) {
            paths.launchDirectoryFor("legacy-id", File(paths.installationsDir, "new-legacy-place").path)
        }
        assertEquals("server source", File(legacy, "server.js").readText())
    }

    @Test
    fun overlappingRegistrationsAndSourceTreeDestinationsAreRejected() = withRoot { _, paths ->
        val target = complete(File(paths.installationsDir, "root/display-name"))
        paths.installLocations.registerCommitted("stable-id", target)
        for (requested in listOf(target, target.parentFile, File(target, "nested"), File(target, "node_modules/child"))) {
            assertThrows(IllegalArgumentException::class.java) {
                paths.launchDirectoryFor("other-id", requested.path)
            }
        }
        val unregistered = complete(File(paths.installationsDir, "unregistered-source"))
        assertThrows(IllegalArgumentException::class.java) {
            paths.launchDirectoryFor("other-id", File(unregistered, "nested").path)
        }
        assertEquals(setOf("stable-id"), paths.installLocations.entries().keys)
    }

    @Test
    fun unregisteredNonemptyCustomDestinationsAreNeverAdoptedOrOverwritten() = withRoot { _, paths ->
        val existing = complete(File(paths.installationsDir, "existing"))
        val userFile = write(existing, "data/chat.jsonl", "user history")
        assertThrows(IllegalArgumentException::class.java) {
            paths.launchDirectoryFor("new-id", existing.path)
        }
        assertThrows(IllegalArgumentException::class.java) {
            installer(paths).prepare(existing, {}, { true }, { true }, { it() }, instanceId = "new-id")
        }
        assertEquals("user history", userFile.readText())
        assertTrue(paths.installLocations.entries().isEmpty())
    }

    @Test
    fun regularFilesCannotBeSelectedAsDestinationsOrParentDirectories() = withRoot { _, paths ->
        val file = write(paths.installationsDir, "not-a-directory", "preserve")
        for (destination in listOf(file, File(file, "child"))) {
            assertThrows(IllegalArgumentException::class.java) {
                paths.launchDirectoryFor("stable-id", destination.path)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            paths.launchDirectoryFor("stable-id", File(file, "root").path, "root")
        }
        assertEquals("preserve", file.readText())
        assertTrue(paths.installLocations.entries().isEmpty())
    }

    @Test
    fun incompleteSourceDependenciesAndPendingMarkersCannotBeRegistered() = withRoot { _, paths ->
        val sourceOnly = File(paths.installationsDir, "source-only")
        write(sourceOnly, "server.js")
        assertThrows(IllegalArgumentException::class.java) {
            paths.installLocations.registerCommitted("source-id", sourceOnly)
        }
        val pending = complete(File(paths.installationsDir, "pending"))
        write(pending, InstanceInstaller.DEPENDENCY_MARKER, "sillyclient-dependencies-v1\n")
        assertThrows(IllegalArgumentException::class.java) {
            paths.installLocations.registerCommitted("pending-id", pending)
        }
        assertTrue(paths.installLocations.entries().isEmpty())
    }

    @Test
    fun anExistingModuleDirectoryCannotBypassTheDependencyManifestCheck() = withRoot { _, paths ->
        val target = complete(File(paths.installationsDir, "partial-dependencies"))
        write(target, "package.json", """{"dependencies":{"yaml":"2.0.0"}}""")
        assertThrows(IllegalArgumentException::class.java) {
            paths.installLocations.registerCommitted("partial-id", target)
        }
        assertTrue(File(target, "node_modules").isDirectory)
        assertTrue(paths.installLocations.entries().isEmpty())
    }

    @Test
    fun aLegacySharedTreeCannotSubstituteForInstanceLocalDependencies() = withRoot { _, paths ->
        val target = complete(File(paths.installationsDir, "shared-only"))
        assertTrue(File(target, "node_modules").delete())
        val lock = write(target, "package-lock.json", """{"lockfileVersion":3,"packages":{}}""")
        val key = requireNotNull(DependencyArchive(File(paths.tarvenHome, "dependency-archives")).lockKey(lock))
        val shared = complete(File(paths.tarvenHome, "dependency-trees/$key"))
        assertThrows(IllegalArgumentException::class.java) {
            paths.installLocations.registerCommitted("shared-id", target)
        }
        assertEquals("server source", File(target, "server.js").readText())
        assertTrue(shared.isDirectory)
        assertTrue(paths.installLocations.entries().isEmpty())
    }

    @Test
    fun aSuccessfulNewInstallationPublishesSourceDependenciesAndRegistrationTogether() = withRoot { root, paths ->
        val target = paths.launchDirectoryFor("stable-id", File(paths.installationsDir, "selected").path)
        installer(paths).prepare(target, {},
            { complete(it); true }, { File(it, "node_modules").mkdirs(); true }, { it() }, instanceId = "stable-id")
        assertEquals(target, paths(root).launchDirectoryFor("stable-id", null))
        assertTrue(File(target, "server.js").isFile)
        assertTrue(File(target, "node_modules").isDirectory)
        assertTrue(paths.tmpDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun failedDependenciesAndCancellationNeverRegisterOrPublishTheSelectedDirectory() = withRoot { _, paths ->
        val target = paths.launchDirectoryFor("stable-id", File(paths.installationsDir, "selected").path)
        assertThrows(IllegalStateException::class.java) {
            installer(paths).prepare(target, {}, { write(it, "server.js"); true }, { false }, { it() }, "stable-id")
        }
        assertFalse(target.exists())
        assertTrue(paths.installLocations.entries().isEmpty())
        OperationCoordinator().use { operations ->
            val operation = operations.begin("stable-id")
            assertThrows(CancellationException::class.java) {
                installer(paths).prepare(target, { operations.ensureCurrent(operation) },
                    { complete(it); operations.cancel(); true }, { true },
                    { action -> operations.commit(operation, action) }, "stable-id")
            }
        }
        assertFalse(target.exists())
        assertTrue(paths.installLocations.entries().isEmpty())
        assertTrue(paths.tmpDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun changedRegistryPreventsInstallationCommitAndPreservesExistingData() = withRoot { _, paths ->
        val target = paths.launchDirectoryFor("stable-id", File(paths.installationsDir, "selected").path)
        var installedDependencies = false
        assertThrows(IllegalStateException::class.java) {
            installer(paths).prepare(target, {},
                { write(it, "server.js", "server source"); true }, {
                    installedDependencies = true
                    File(it, "node_modules").mkdirs()
                    write(paths.tarvenHome, "install-locations.json", "not json")
                    true
                }, { it() }, "stable-id")
        }
        assertTrue(installedDependencies)
        assertFalse(target.exists())
        assertTrue(paths.tmpDir.listFiles().orEmpty().isEmpty())
        assertEquals("not json", File(paths.tarvenHome, "install-locations.json").readText())
    }

    @Test
    fun registrationWriteFailureRollsBackPublishedSourceInsteadOfLeavingAnOrphan() = withRoot { _, paths ->
        val target = paths.launchDirectoryFor("stable-id", File(paths.installationsDir, "selected").path)
        val staging = complete(File(paths.tmpDir, "staging"))
        val registryFile = File(paths.tarvenHome, "install-locations.json")
        assertThrows(Exception::class.java) {
            paths.installLocations.commitNewInstallation("stable-id", target, {
                requireNotNull(target.parentFile).mkdirs()
                check(staging.renameTo(target))
                check(registryFile.mkdirs())
            }, {
                check(target.renameTo(staging))
            })
        }
        assertFalse(target.exists())
        assertEquals("server source", File(staging, "server.js").readText())
        assertTrue(paths.tarvenHome.listFiles().orEmpty().none { it.name.startsWith(".install-locations-") })
    }

    @Test
    fun identityMarkerFailureAfterPublicationRollsBackTheSameOwnedDirectory() = withRoot { _, paths ->
        val target = paths.launchDirectoryFor("stable-id", null, displayName = "Readable Tavern")
        val staging = complete(File(paths.serversDir, ".sillyclient-install-marker-failure"))
        var rollbackCount = 0
        assertThrows(Exception::class.java) {
            paths.installLocations.commitNewInstallation("stable-id", target, {
                check(staging.renameTo(target))
                check(File(target, ".sc-identity").mkdir())
            }, {
                rollbackCount++
                check(target.renameTo(staging))
            })
        }
        assertEquals(1, rollbackCount)
        assertFalse(target.exists())
        assertEquals("server source", File(staging, "server.js").readText())
        assertTrue(paths.installLocations.entries().isEmpty())
    }

    @Test
    fun concurrentRegistryObjectsDoNotLoseEachOthersCommittedLocations() = withRoot { root, paths ->
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..12).map { index ->
                Callable {
                    val registry = paths(root).installLocations
                    registry.registerCommitted("id-$index", complete(File(paths.installationsDir, "directory-$index")))
                }
            }
            executor.invokeAll(tasks).forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(12, paths(root).installLocations.entries().size)
            assertEquals((1..12).map { "id-$it" }.toSet(), paths.installLocations.entries().keys)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun corruptOrDuplicateRegistrationNeverFallsBackToTheDefaultDirectory() = withRoot { root, paths ->
        val target = complete(File(paths.installationsDir, "display-name"))
        paths.installLocations.registerCommitted("stable-id", target)
        val file = File(paths.tarvenHome, "install-locations.json")
        val original = file.readText()
        val duplicate = JSONObject(original).apply {
            val records = getJSONArray("locations")
            records.put(JSONObject(records.getJSONObject(0).toString()))
        }.toString()
        for (corruption in listOf("broken json", duplicate, JSONObject(original).put("revision", 2).toString())) {
            file.writeText(corruption)
            assertThrows(Exception::class.java) { paths(root).serverDirFor("stable-id", create = false) }
            assertThrows(Exception::class.java) {
                InstanceRepository(paths.serversDir, installLocations = paths.installLocations).scan()
            }
            assertFalse(File(paths.serversDir, "stable-id").exists())
            assertEquals("server source", File(target, "server.js").readText())
        }
    }

    @Test
    fun changedDirectoryIdentityCannotBeLaunchedOrDeletedAsTheRegisteredInstallation() = withRoot { _, paths ->
        val target = complete(File(paths.installationsDir, "display-name"))
        paths.installLocations.registerCommitted("stable-id", target)
        val moved = File(paths.installationsDir, "preserved-original")
        assertTrue(target.renameTo(moved))
        complete(target)
        write(target, "data/chat.jsonl", "replacement history")
        assertThrows(IllegalArgumentException::class.java) { paths.serverDirFor("stable-id", create = false) }
        assertThrows(IllegalArgumentException::class.java) { paths.installLocations.entries() }
        assertEquals("replacement history", File(target, "data/chat.jsonl").readText())
        assertEquals("server source", File(moved, "server.js").readText())
    }

    @Test
    fun unregisterAcceptsADeletionCommittedDirectory() = withRoot { root, paths ->
        val target = complete(File(paths.installationsDir, "display-name"))
        paths.installLocations.registerCommitted("stable-id", target)
        // Deletion commits on the removal marker; the physical purge runs in
        // the background after the console already forgot the instance.
        File(target, InstanceRemoval.REMOVAL_MARKER).writeText("sillyclient-removal-v1\n")
        paths.installLocations.unregisterAfterDelete("stable-id", target)
        assertTrue(target.exists())
        assertTrue(paths(root).installLocations.entries().isEmpty())
    }

    @Test
    fun markedRemnantsAreNeverAdoptedAsInstallTargets() = withRoot { root, paths ->
        val remnant = complete(File(paths.serversDir, "remnant-name"))
        File(remnant, InstanceRemoval.REMOVAL_MARKER).writeText("sillyclient-removal-v1\n")
        // A new instance with the same name lands beside the remnant instead of
        // installing into a directory that is being purged.
        val target = paths.launchDirectoryFor("remnant-name", null, displayName = "remnant-name")
        assertNotEquals(remnant.canonicalFile, target.canonicalFile)
        assertTrue(target.name.startsWith("remnant-name-"))
    }

    @Test
    fun unregisterRequiresTheMatchingDirectoryToBeFullyRemoved() = withRoot { root, paths ->
        val target = complete(File(paths.installationsDir, "display-name"))
        paths.installLocations.registerCommitted("stable-id", target)
        assertThrows(IllegalArgumentException::class.java) {
            paths.installLocations.unregisterAfterDelete("stable-id", target)
        }
        // A path that no registration owns removes nothing: the registration
        // keyed by the remembered id survives untouched.
        paths.installLocations.unregisterAfterDelete("stable-id", File(paths.installationsDir, "other"))
        assertTrue(paths(root).installLocations.entries().isNotEmpty())
        assertTrue(ManagedFiles.deleteDirectory(target, paths.installLocations.allowedRootFor(target)))
        paths.installLocations.unregisterAfterDelete("stable-id", target)
        assertTrue(paths(root).installLocations.entries().isEmpty())
        assertFalse(target.exists())
    }

    @Test
    fun liveRegistrationsAreStillNeverReplaced() = withRoot { _, paths ->
        complete(File(paths.serversDir, "stable-id"))
            .let { paths.installLocations.registerCommitted("stable-id", it) }
        assertThrows(IllegalArgumentException::class.java) {
            paths.installLocations.commitNewInstallation("stable-id",
                File(paths.installationsDir, "other"), { true }, {})
        }
    }

    @Test
    fun manuallyDeletedRegistrationsAllowTheSameIdentityToBeImportedAgain() = withRoot { _, paths ->
        val directory = complete(File(paths.serversDir, "stable-id"))
        paths.installLocations.registerCommitted("stable-id", directory)
        directory.deleteRecursively()
        assertTrue(paths.installLocations.entries().isEmpty())
        paths.installLocations.commitNewInstallation("stable-id", directory,
            { complete(directory); true }, { directory.deleteRecursively() })
        assertEquals(setOf("stable-id"), paths.installLocations.entries().keys)
    }

    @Test
    fun ghostRetainedCopiesProtectExistingFilesUntilTheLeftoverIsGone() = withRoot { _, paths ->
        val registry = paths.installLocations
        val live = complete(File(paths.installationsDir, "moved-tavern"))
        registry.registerCommitted("stable-id", live)
        val retained = complete(File(paths.serversDir, "old-copy"))
        val file = File(paths.tarvenHome, "install-locations.json")
        val document = JSONObject(file.readText())
        document.getJSONArray("locations").getJSONObject(0)
            .put("retainedPaths", JSONArray().put(retained.path))
        file.writeText(document.toString())
        live.deleteRecursively()
        assertThrows(IllegalArgumentException::class.java) {
            registry.commitNewInstallation("stable-id", retained,
                { complete(retained); true }, { retained.deleteRecursively() })
        }
        retained.deleteRecursively()
        registry.commitNewInstallation("stable-id", retained,
            { complete(retained); true }, { retained.deleteRecursively() })
        assertEquals(setOf("stable-id"), registry.entries().keys)
    }

    @Test
    fun repositoryScansMappedAndLegacyInstancesWithoutMeasuringTreesOrBasenameAliases() = withRoot { _, paths ->
        val custom = complete(File(paths.installationsDir, "friendly-name"))
        paths.installLocations.registerCommitted("stable-id", custom)
        val legacy = complete(File(paths.serversDir, "legacy-id"))
        var measured = 0
        val repository = InstanceRepository(paths.serversDir, installLocations = paths.installLocations,
            measureSize = { measured++; 42L })
        assertEquals(setOf("stable-id", "legacy-id"), repository.scan().map { it.instanceId }.toSet())
        assertEquals(0, measured)
        val info = repository.info(custom)
        assertEquals("stable-id", info.instanceId)
        assertEquals("1.19.0", info.version)
        assertEquals(42L, info.sizeBytes)
        assertEquals(1, measured)
        assertEquals(legacy.path, repository.scan().single { it.instanceId == "legacy-id" }.path)
    }

    @Test
    fun cleanupProtectsRegisteredCustomCoversEvenWithNoFrontendSnapshotIds() = withRoot { _, paths ->
        val target = complete(File(paths.installationsDir, "friendly-name"))
        paths.installLocations.registerCommitted("stable-id", target)
        val cover = write(paths.appFilesDir, "covers/stable-id.png")
        cover.setLastModified(1)
        val cleanup = CleanupService(paths.serversDir, File(paths.appFilesDir, "covers"),
            listOf(paths.tmpDir), paths.logsDir, { false }, clock = { 200_000_000L },
            installLocations = paths.installLocations)
        assertTrue(cleanup.scan(activeInstanceIds = emptyList(), activeCoverPaths = emptyList()).isEmpty())
        assertTrue(cover.exists())
    }

    @Test
    fun oversizedPackageMetadataDoesNotAllocateUnboundedScanPayloads() = withRoot { _, paths ->
        val legacy = complete(File(paths.serversDir, "legacy-id"))
        write(legacy, "package.json", "x".repeat(1024 * 1024 + 1))
        val metadata = InstanceRepository(paths.serversDir).scan().single()
        assertEquals("unknown", metadata.version)
        assertEquals("legacy-id", metadata.instanceId)
        assertTrue(metadata.hasServer)
    }

    @Test
    fun maintenanceAndRecoveryKeepCustomPathsAndStableIdentityAcrossRestart() = withRoot { root, paths ->
        val target = complete(File(paths.installationsDir, "friendly-name"))
        paths.installLocations.registerCommitted("stable-id", target)
        val broken = File(target, "data/default-user/extensions/broken").apply { mkdirs() }
        write(broken, "entry.js", "extension contents")
        fun maintenance(runtime: RuntimePaths) = InstanceMaintenance(runtime.serversDir, { false },
            { require(File(it, "config.yaml").readText() == "dataRoot: ./data\n") },
            installLocations = runtime.installLocations)
        val service = maintenance(paths)
        val scan = service.scan("stable-id")
        val item = scan.items.single()
        val applied = service.apply("stable-id", scan.scanId, listOf(InstanceMaintenance.Selection(item.id, item.token)))
        assertTrue(applied.success)
        assertFalse(broken.exists())
        val restarted = maintenance(paths(root))
        val recovery = restarted.listRecovery("stable-id").items.single()
        assertTrue(recovery.canRestore)
        assertTrue(restarted.restore("stable-id", recovery.recoveryId, recovery.token).success)
        assertEquals("extension contents", File(broken, "entry.js").readText())
        assertFalse(File(paths.serversDir, "stable-id").exists())
    }

    @Test
    fun linkedTargetsAndLinkedParentsCannotBecomeInstallationLocations() = withRoot { root, paths ->
        paths.ensureDirs()
        val external = File(root, "outside").apply { mkdirs() }
        val link = File(paths.installationsDir, "linked")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), external.toPath()) }.isSuccess
        assumeTrue("Symlink creation is not available on this host", created)
        assertThrows(IllegalArgumentException::class.java) { paths.launchDirectoryFor("stable-id", link.path) }
        assertThrows(IllegalArgumentException::class.java) {
            paths.launchDirectoryFor("stable-id", File(link, "child").path)
        }
        assertFalse(File(external, "child").exists())
        val target = complete(File(paths.installationsDir, "original"))
        paths.installLocations.registerCommitted("stable-id", target)
        val outsideSource = write(external, "server.js", "preserve source")
        Files.delete(File(target, "server.js").toPath())
        Files.createSymbolicLink(File(target, "server.js").toPath(), outsideSource.toPath())
        assertThrows(IllegalArgumentException::class.java) { paths.serverDirFor("stable-id", create = false) }
        assertEquals("preserve source", outsideSource.readText())
    }
}
