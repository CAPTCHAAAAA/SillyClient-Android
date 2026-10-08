package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class InstanceMaintenanceTest {
    private val now = 200_000_000L
    private val config = "dataRoot: ./data\n"

    private fun withRoot(test: (File, File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent == null) Files.createTempDirectory("instance-maintenance").toFile()
            else Files.createTempDirectory(parent.toPath(), "instance-maintenance-").toFile()
        val instance = File(root, "servers/local").apply { mkdirs() }
        File(instance, "server.js").writeText("server source")
        File(instance, "config.yaml").writeText(config)
        try { test(root, instance) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    private fun write(directory: File, path: String, text: String): File =
        File(directory, path).apply { parentFile?.mkdirs(); writeText(text) }

    private fun extension(instance: File, name: String, manifest: String? = null, user: String = "default-user"): File {
        val directory = File(instance, "data/$user/extensions/$name").apply { mkdirs() }
        if (manifest != null) write(directory, "manifest.json", manifest)
        return directory
    }

    private fun service(
        root: File,
        busy: (String) -> Boolean = { false },
        time: () -> Long = { now },
        commit: (String, () -> Unit) -> Unit = { _, action -> action() },
        limits: InstanceMaintenance.Limits = InstanceMaintenance.Limits()
    ) = InstanceMaintenance(File(root, "servers"), busy,
        { directory -> require(File(directory, "config.yaml").readText() == config) { "Unsupported data root" } },
        commit, time, limits = limits)

    private fun apply(service: InstanceMaintenance, scan: InstanceMaintenance.Scan,
        items: List<InstanceMaintenance.Item> = scan.items) =
        service.apply("local", scan.scanId, items.map { InstanceMaintenance.Selection(it.id, it.token) })

    @Test
    fun healthyZipAndOptionalAssetManifestsAreProtected() = withRoot { root, instance ->
        extension(instance, "metadata-only", """{"display_name":"Metadata","version":"1"}""")
        val js = extension(instance, "zip-plugin", """{"js":"dist/main.js"}""")
        write(js, "dist/main.js", "export default true")
        val css = extension(instance, "css-only", """{"css":"theme.css"}""")
        write(css, "theme.css", "body{}")
        val withGit = extension(instance, "git-plugin", """{}""")
        write(withGit, ".git/objects/work.tmp", "not evidence of a broken extension")
        assertTrue(service(root).scan("local").items.isEmpty())
        assertTrue(File(js, "dist/main.js").exists())
    }

    @Test
    fun interruptedCloneMissingManifestAndMissingAssetAreNeverDefaultSelected() = withRoot { root, instance ->
        val missing = extension(instance, "interrupted")
        write(missing, ".git/HEAD", "ref: refs/heads/main")
        extension(instance, "invalid-json", "{")
        extension(instance, "empty")
        extension(instance, "not-built", """{"js":"dist/main.js"}""")
        val scan = service(root).scan("local")
        assertEquals(4, scan.items.size)
        assertTrue(scan.items.all { it.kind == "broken_extension" && it.action == "quarantine" &&
            it.confidence == "suspected" && !it.defaultSelected })
        assertTrue(missing.exists())
    }

    @Test
    fun scansAllUserAndGlobalExtensionsButNotBuiltins() = withRoot { root, instance ->
        extension(instance, "default", user = "default-user")
        extension(instance, "admin", user = "alice")
        File(instance, "public/scripts/extensions/third-party/global").mkdirs()
        File(instance, "public/scripts/extensions/builtin-no-manifest").mkdirs()
        val scan = service(root).scan("local")
        assertEquals(3, scan.items.size)
        assertTrue(scan.items.any { it.relativePath == "data/alice/extensions/admin" })
        assertTrue(scan.items.any { it.relativePath == "public/scripts/extensions/third-party/global" })
    }

    @Test
    fun quarantineReleasesOriginalNameAndPreservesDataPreferencesAndRecovery() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        val content = write(broken, "user-note.txt", "keep extension data")
        val chat = write(instance, "data/default-user/chats/chat.jsonl", "keep chat")
        val settings = write(instance, "data/default-user/settings.json", """{"custom":true}""")
        val runtime = service(root)
        val scan = runtime.scan("local")
        val originalBytes = content.length()
        val result = apply(runtime, scan)
        assertTrue(result.success)
        assertEquals(0, result.freedBytes)
        assertEquals(originalBytes, result.quarantinedBytes)
        assertFalse(broken.exists())
        assertEquals("keep chat", chat.readText())
        assertEquals("""{"custom":true}""", settings.readText())
        val recovery = File(instance, ".sillyclient-maintenance/recovery/${result.recoveryIds.single()}")
        assertEquals("keep extension data", File(recovery, "payload/user-note.txt").readText())
        assertEquals(scan.items.single().relativePath, JSONObject(File(recovery, "record.json").readText()).getString("originalRelativePath"))
        assertTrue(broken.mkdirs())
    }

    @Test
    fun tokenIsOneUseAndBoundToScanInstanceAndItem() = withRoot { root, instance ->
        extension(instance, "interrupted")
        val runtime = service(root)
        val scan = runtime.scan("local")
        val item = scan.items.single()
        assertThrows(IllegalArgumentException::class.java) {
            runtime.apply("../local", scan.scanId, listOf(InstanceMaintenance.Selection(item.id, item.token)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime.apply("local", scan.scanId, listOf(InstanceMaintenance.Selection(item.id, "wrong")))
        }
        assertThrows(IllegalArgumentException::class.java) { apply(runtime, scan) }
        val fresh = runtime.scan("local")
        assertNotEquals(item.token, fresh.items.single().token)
        assertTrue(apply(runtime, fresh).success)
        assertThrows(IllegalArgumentException::class.java) { apply(runtime, fresh) }
    }

    @Test
    fun aNewScanInvalidatesThePreviousCapabilities() = withRoot { root, instance ->
        extension(instance, "interrupted")
        val runtime = service(root)
        val old = runtime.scan("local")
        val fresh = runtime.scan("local")
        assertThrows(IllegalArgumentException::class.java) { apply(runtime, old) }
        assertTrue(apply(runtime, fresh).success)
    }

    @Test
    fun expiredTokenPreservesTheOriginalDirectory() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        var time = now
        val runtime = service(root, time = { time })
        val scan = runtime.scan("local")
        time = scan.expiresAt + 1
        assertThrows(IllegalArgumentException::class.java) { apply(runtime, scan) }
        assertTrue(broken.exists())
    }

    @Test
    fun sameLengthSameTimestampChangesInvalidateTreeHash() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        val file = write(broken, "user-data", "original")
        val runtime = service(root)
        val scan = runtime.scan("local")
        val timestamp = file.lastModified()
        file.writeText("modified")
        file.setLastModified(timestamp)
        assertThrows(IllegalArgumentException::class.java) { apply(runtime, scan) }
        assertEquals("modified", file.readText())
    }

    @Test
    fun changedSettingsInvalidateEvenAnExtensionOnlyPlan() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        val settings = write(instance, "data/default-user/settings.json", """{"custom":1}""")
        val runtime = service(root)
        val scan = runtime.scan("local")
        settings.writeText("""{"custom":2}""")
        assertThrows(IllegalArgumentException::class.java) { apply(runtime, scan) }
        assertTrue(broken.exists())
        assertEquals("""{"custom":2}""", settings.readText())
    }

    @Test
    fun replacingAnInstanceDirectoryInvalidatesIdentity() = withRoot { root, instance ->
        extension(instance, "interrupted")
        val runtime = service(root)
        val scan = runtime.scan("local")
        val previous = File(instance.parentFile, "previous")
        assertTrue(instance.renameTo(previous))
        assertTrue(instance.mkdirs())
        File(instance, "server.js").writeText("server source")
        File(instance, "config.yaml").writeText(config)
        val fresh = extension(instance, "interrupted")
        assertThrows(IllegalArgumentException::class.java) { apply(runtime, scan) }
        assertTrue(fresh.exists())
        assertTrue(File(previous, "data/default-user/extensions/interrupted").exists())
    }

    @Test
    fun runningAndUnsupportedInstancesAreRejectedWithoutMutation() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        var busy = true
        val runtime = service(root, busy = { busy })
        assertThrows(IllegalArgumentException::class.java) { runtime.scan("local") }
        busy = false
        val scan = runtime.scan("local")
        busy = true
        assertThrows(IllegalArgumentException::class.java) { apply(runtime, scan) }
        busy = false
        for (id in listOf("..", "../local", "remote-absent", instance.absolutePath)) {
            assertThrows(IllegalArgumentException::class.java) { runtime.scan(id) }
        }
        File(instance, "config.yaml").writeText("dataRoot: ../external\n")
        assertThrows(IllegalArgumentException::class.java) { runtime.scan("local") }
        assertTrue(broken.exists())
        assertFalse(File(instance, ".sillyclient-maintenance").exists())
    }

    @Test
    fun runtimeStartingAtCommitCannotQuarantine() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        var busy = false
        val runtime = service(root, busy = { busy }, commit = { _, action -> busy = true; action() })
        val result = apply(runtime, runtime.scan("local"))
        assertFalse(result.success)
        assertFalse(result.results.single().success)
        assertTrue(broken.exists())
    }

    @Test
    fun onlyExplicitMissingThirdPartyDisabledReferencesAreRemoved() = withRoot { root, instance ->
        extension(instance, "healthy", """{}""")
        File(instance, "public/scripts/extensions/third-party/global").mkdirs()
        val original = """{"custom":{"keep":1},"extension_settings":{"apiKey":"keep-private","disabledExtensions":["third-party/missing","third-party/healthy","third-party/global","builtin/missing"],"custom":{"keep":true}}}"""
        val settings = write(instance, "data/default-user/settings.json", original)
        val runtime = service(root)
        val scan = runtime.scan("local")
        val reference = scan.items.single { it.kind == "stale_extension_reference" }
        assertFalse(reference.defaultSelected)
        val result = apply(runtime, scan, listOf(reference))
        assertTrue(result.success)
        assertEquals(0, result.freedBytes)
        assertEquals(0, result.quarantinedBytes)
        val modified = JSONObject(settings.readText())
        assertEquals(1, modified.getJSONObject("custom").getInt("keep"))
        assertEquals("keep-private", modified.getJSONObject("extension_settings").getString("apiKey"))
        assertTrue(modified.getJSONObject("extension_settings").getJSONObject("custom").getBoolean("keep"))
        val references = modified.getJSONObject("extension_settings").getJSONArray("disabledExtensions")
        assertEquals(listOf("third-party/healthy", "third-party/global", "builtin/missing"),
            (0 until references.length()).map { references.getString(it) })
        val recovery = File(instance, ".sillyclient-maintenance/recovery/${result.recoveryIds.single()}/payload")
        assertEquals(original, recovery.readText())
    }

    @Test
    fun multipleSelectedReferencesUseOneAtomicSettingsWritePerUser() = withRoot { root, instance ->
        val original = """{"extension_settings":{"disabledExtensions":["third-party/a","third-party/b","third-party/a"]},"keep":"value"}"""
        val settings = write(instance, "data/alice/settings.json", original)
        val runtime = service(root)
        val scan = runtime.scan("local")
        assertEquals(2, scan.items.size)
        val result = apply(runtime, scan)
        assertTrue(result.success)
        assertEquals(1, result.recoveryIds.size)
        assertEquals(0, JSONObject(settings.readText()).getJSONObject("extension_settings").getJSONArray("disabledExtensions").length())
        assertEquals("value", JSONObject(settings.readText()).getString("keep"))
    }

    @Test
    fun newlyInstalledExtensionPreventsStalePreferenceRemoval() = withRoot { root, instance ->
        val settings = write(instance, "data/default-user/settings.json",
            """{"extension_settings":{"disabledExtensions":["third-party/missing"]}}""")
        val runtime = service(root)
        val scan = runtime.scan("local")
        extension(instance, "missing", """{}""")
        assertThrows(IllegalArgumentException::class.java) { apply(runtime, scan) }
        assertTrue(settings.readText().contains("third-party/missing"))
    }

    @Test
    fun malformedSettingsArePreservedAndNeverOfferPreferenceRemoval() = withRoot { root, instance ->
        val file = write(instance, "data/default-user/settings.json", """{"extension_settings":{"disabledExtensions":[42]}}""")
        val scan = service(root).scan("local")
        assertTrue(scan.items.isEmpty())
        assertTrue(scan.warnings.isNotEmpty())
        assertEquals("""{"extension_settings":{"disabledExtensions":[42]}}""", file.readText())
    }

    @Test
    fun unsafeDeclaredAssetsDoNotReceiveQuarantineCapabilities() = withRoot { root, instance ->
        val extension = extension(instance, "unsafe", """{"js":"../../settings.json"}""")
        val scan = service(root).scan("local")
        assertTrue(scan.items.isEmpty())
        assertTrue(scan.warnings.isNotEmpty())
        assertTrue(extension.exists())
    }

    @Test
    fun byteEntryAndDepthLimitsPreserveUninspectedTrees() = withRoot { root, instance ->
        val broken = extension(instance, "too-large")
        write(broken, "payload", "x".repeat(256))
        val bytes = service(root, limits = InstanceMaintenance.Limits(maxBytes = 128)).scan("local")
        assertTrue(bytes.items.isEmpty())
        assertTrue(bytes.warnings.isNotEmpty())
        val entries = service(root, limits = InstanceMaintenance.Limits(maxEntries = 1)).scan("local")
        assertTrue(entries.items.isEmpty())
        assertTrue(entries.warnings.isNotEmpty())
        var deepest = extension(instance, "too-deep")
        repeat(5) { deepest = File(deepest, "d").apply { mkdirs() } }
        val depth = service(root, limits = InstanceMaintenance.Limits(maxDepth = 2)).scan("local")
        assertTrue(depth.items.none { it.relativePath.endsWith("too-deep") })
        assertTrue(broken.exists() && deepest.exists())
    }

    @Test
    fun linkedDescendantsArePreservedWithoutReadingOrDeletingTheTarget() = withRoot { root, instance ->
        val broken = extension(instance, "linked")
        val outside = write(root, "outside/important.txt", "do not touch")
        val link = File(broken, "escape")
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        assumeTrue("Symlink creation is not available on this host", linked)
        try {
            val scan = service(root).scan("local")
            assertTrue(scan.items.isEmpty())
            assertTrue(scan.warnings.isNotEmpty())
            assertEquals("do not touch", outside.readText())
        } finally { Files.deleteIfExists(link.toPath()) }
    }

    private fun ownedCache(instance: File, time: Long = 1): File {
        val directory = File(instance, ".sillyclient-maintenance/download-cache/${UUID.randomUUID()}").apply { mkdirs() }
        val payload = write(directory, "download.zip", "owned downloaded bytes")
        val sha = MessageDigest.getInstance("SHA-256").digest(payload.readBytes()).joinToString("") { "%02x".format(it) }
        val marker = write(directory, "owner.json", JSONObject().put("revision", 1)
            .put("owner", "sillyclient").put("instanceId", "local").put("payload", "download.zip")
            .put("sizeBytes", payload.length()).put("sha256", sha).toString())
        payload.setLastModified(time)
        marker.setLastModified(time)
        directory.setLastModified(time)
        return directory
    }

    @Test
    fun onlyExpiredVerifiedOwnedDownloadCachesAreRemovedFromTheirActiveLocation() = withRoot { root, instance ->
        val expired = ownedCache(instance)
        val fresh = ownedCache(instance, now)
        val unowned = write(instance, ".sillyclient-maintenance/download-cache/unowned/download.zip", "keep")
        val model = write(instance, "data/_cache/model.bin", "keep model")
        val output = write(instance, "data/_webpack/build/output/lib.js", "keep runtime")
        val runtime = service(root)
        val scan = runtime.scan("local")
        val item = scan.items.single()
        assertEquals("download_cache", item.kind)
        assertEquals("owned", item.confidence)
        assertTrue(item.defaultSelected)
        val result = apply(runtime, scan)
        assertTrue(result.success)
        assertEquals(0, result.freedBytes)
        assertEquals(item.sizeBytes, result.quarantinedBytes)
        assertFalse(expired.exists())
        assertTrue(fresh.exists() && unowned.exists() && model.exists() && output.exists())
        val recovery = File(instance, ".sillyclient-maintenance/recovery/${result.recoveryIds.single()}/payload")
        assertEquals("owned downloaded bytes", File(recovery, "download.zip").readText())
    }

    @Test
    fun cacheOwnershipHashAndUnexpectedFilesPreventDeletion() = withRoot { root, instance ->
        val tampered = ownedCache(instance)
        File(tampered, "download.zip").writeText("different bytes")
        val extra = ownedCache(instance)
        write(extra, "user.json", "keep")
        val scan = service(root).scan("local")
        assertTrue(scan.items.isEmpty())
        assertEquals(2, scan.warnings.size)
        assertTrue(tampered.exists() && extra.exists())
    }

    @Test
    fun changingOwnedCacheAfterScanInvalidatesItsToken() = withRoot { root, instance ->
        val cache = ownedCache(instance)
        val runtime = service(root)
        val scan = runtime.scan("local")
        write(cache, "unexpected.json", "keep")
        assertThrows(IllegalArgumentException::class.java) { apply(runtime, scan) }
        assertTrue(cache.exists())
    }

    @Test
    fun duplicateSelectionsAreRejectedBeforeAnyMutation() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        val runtime = service(root)
        val scan = runtime.scan("local")
        val item = scan.items.single()
        assertThrows(IllegalArgumentException::class.java) { apply(runtime, scan, listOf(item, item)) }
        assertTrue(broken.exists())
    }

    @Test
    fun extensionRecoveryRestoresDataAndRetiresItsCapability() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted#clone")
        write(broken, "user-note", "keep")
        val runtime = service(root)
        val result = apply(runtime, runtime.scan("local"))
        val recovery = runtime.listRecovery("local").items.single()
        assertEquals(result.recoveryIds.single(), recovery.recoveryId)
        assertEquals("data/default-user/extensions/interrupted#clone", recovery.relativePath)
        assertEquals("broken_extension", recovery.kind)
        assertTrue(recovery.canRestore)
        val restored = runtime.restore("local", recovery.recoveryId, recovery.token)
        assertTrue(restored.success)
        assertEquals("keep", File(broken, "user-note").readText())
        assertTrue(runtime.listRecovery("local").items.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            runtime.restore("local", recovery.recoveryId, recovery.token)
        }
    }

    @Test
    fun recoveryNeverOverwritesAReinstalledExtension() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        write(broken, "old-data", "keep old data")
        val runtime = service(root)
        apply(runtime, runtime.scan("local"))
        val first = runtime.listRecovery("local").items.single()
        assertTrue(broken.mkdirs())
        write(broken, "manifest.json", """{}""")
        write(broken, "new-data", "keep new installation")
        assertThrows(IllegalArgumentException::class.java) {
            runtime.restore("local", first.recoveryId, first.token)
        }
        val fresh = runtime.listRecovery("local").items.single()
        assertFalse(fresh.canRestore)
        assertTrue(fresh.conflict!!.contains("occupied"))
        assertThrows(IllegalArgumentException::class.java) {
            runtime.restore("local", fresh.recoveryId, fresh.token)
        }
        assertEquals("keep new installation", File(broken, "new-data").readText())
        assertEquals("keep old data", File(instance,
            ".sillyclient-maintenance/recovery/${first.recoveryId}/payload/old-data").readText())
    }

    @Test
    fun changedRecoveryPayloadCannotBeRestored() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        write(broken, "user-note", "keep")
        val runtime = service(root)
        apply(runtime, runtime.scan("local"))
        val recovery = runtime.listRecovery("local").items.single()
        write(instance, ".sillyclient-maintenance/recovery/${recovery.recoveryId}/payload/user-note", "user changed")
        assertThrows(IllegalArgumentException::class.java) {
            runtime.restore("local", recovery.recoveryId, recovery.token)
        }
        val list = runtime.listRecovery("local")
        assertTrue(list.items.isEmpty())
        assertTrue(list.warnings.isNotEmpty())
        assertFalse(broken.exists())
    }

    @Test
    fun recoveryRequiresAnUnexpiredMatchingOneUseToken() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        var time = now
        val runtime = service(root, time = { time })
        apply(runtime, runtime.scan("local"))
        val recovery = runtime.listRecovery("local").items.single()
        assertThrows(IllegalArgumentException::class.java) {
            runtime.restore("local", recovery.recoveryId, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime.restore("local", recovery.recoveryId, "wrong")
        }
        time += 5 * 60_000 + 1
        assertThrows(IllegalArgumentException::class.java) {
            runtime.restore("local", recovery.recoveryId, recovery.token)
        }
        assertFalse(broken.exists())
    }

    @Test
    fun aNewRecoveryListInvalidatesPreviousTokens() = withRoot { root, instance ->
        extension(instance, "interrupted")
        val runtime = service(root)
        apply(runtime, runtime.scan("local"))
        val old = runtime.listRecovery("local").items.single()
        val current = runtime.listRecovery("local").items.single()
        assertNotEquals(old.token, current.token)
        assertThrows(IllegalArgumentException::class.java) {
            runtime.restore("local", old.recoveryId, old.token)
        }
        assertTrue(runtime.restore("local", current.recoveryId, current.token).success)
    }

    @Test
    fun settingsRecoveryIsExactAndRetainsTheMaintainedSettingsBackup() = withRoot { root, instance ->
        val original = """{"extension_settings":{"disabledExtensions":["third-party/missing"]},"unknown":"keep-private"}"""
        val settings = write(instance, "data/default-user/settings.json", original)
        val runtime = service(root)
        apply(runtime, runtime.scan("local"))
        val maintained = settings.readText()
        val recovery = runtime.listRecovery("local").items.single()
        assertEquals("stale_extension_reference", recovery.kind)
        assertTrue(recovery.canRestore)
        assertTrue(runtime.restore("local", recovery.recoveryId, recovery.token).success)
        assertEquals(original, settings.readText())
        val backup = File(instance,
            ".sillyclient-maintenance/recovery-history/${recovery.recoveryId}/replaced-settings.json")
        assertEquals(maintained, backup.readText())
    }

    @Test
    fun settingsChangedAfterMaintenanceAreNeverOverwrittenByRecovery() = withRoot { root, instance ->
        val settings = write(instance, "data/default-user/settings.json",
            """{"extension_settings":{"disabledExtensions":["third-party/missing"]},"unknown":"keep"}""")
        val runtime = service(root)
        apply(runtime, runtime.scan("local"))
        val beforeChange = runtime.listRecovery("local").items.single()
        val changed = """{"extension_settings":{"disabledExtensions":[]},"unknown":"user changed"}"""
        settings.writeText(changed)
        assertThrows(IllegalArgumentException::class.java) {
            runtime.restore("local", beforeChange.recoveryId, beforeChange.token)
        }
        val recovery = runtime.listRecovery("local").items.single()
        assertFalse(recovery.canRestore)
        assertEquals("Settings changed after maintenance", recovery.conflict)
        assertEquals(changed, settings.readText())
    }

    @Test
    fun isolatedOwnedCachesAlsoHaveRealRecovery() = withRoot { root, instance ->
        val cache = ownedCache(instance)
        val runtime = service(root)
        apply(runtime, runtime.scan("local"))
        val recovery = runtime.listRecovery("local").items.single()
        assertEquals("download_cache", recovery.kind)
        assertEquals("delete_cache", recovery.action)
        assertTrue(runtime.restore("local", recovery.recoveryId, recovery.token).success)
        assertEquals("owned downloaded bytes", File(cache, "download.zip").readText())
    }

    @Test
    fun recoveryMetadataCannotTargetArbitraryInstanceData() = withRoot { root, instance ->
        extension(instance, "interrupted")
        val chat = write(instance, "data/default-user/chats/keep.jsonl", "keep chat")
        val runtime = service(root)
        val result = apply(runtime, runtime.scan("local"))
        val file = File(instance, ".sillyclient-maintenance/recovery/${result.recoveryIds.single()}/record.json")
        val record = JSONObject(file.readText()).put("originalRelativePath", "data/default-user/chats/keep.jsonl")
        file.writeText(record.toString())
        val list = runtime.listRecovery("local")
        assertTrue(list.items.isEmpty())
        assertTrue(list.warnings.isNotEmpty())
        assertEquals("keep chat", chat.readText())
    }

    @Test
    fun globalInspectionBudgetStopsFurtherTraversalWithoutDeletingAnything() = withRoot { root, instance ->
        repeat(5) { extension(instance, "incomplete-$it") }
        val scan = service(root, limits = InstanceMaintenance.Limits(maxScanEntries = 3)).scan("local")
        assertTrue(scan.items.size < 5)
        assertTrue(scan.warnings.isNotEmpty())
        repeat(5) { assertTrue(File(instance, "data/default-user/extensions/incomplete-$it").exists()) }
    }

    @Test
    fun wholeSelectionIsValidatedBeforeTheFirstMutation() = withRoot { root, instance ->
        val first = extension(instance, "a-incomplete")
        val second = extension(instance, "b-incomplete")
        val data = write(second, "user-note", "keep")
        val runtime = service(root)
        val scan = runtime.scan("local")
        data.writeText("changed")
        assertThrows(IllegalArgumentException::class.java) { apply(runtime, scan) }
        assertTrue(first.exists() && second.exists())
        assertFalse(File(instance, ".sillyclient-maintenance").exists())
    }

    @Test
    fun aChangedChildAtAtomicCommitIsPreservedAndReportedAsFailedRecovery() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        val file = write(broken, "user-note", "keep")
        var changed = false
        val runtime = service(root, commit = { _, action ->
            if (!changed) { file.writeText("user changed"); changed = true }
            action()
        })
        val result = apply(runtime, runtime.scan("local"))
        assertFalse(result.success)
        assertEquals(0, result.freedBytes)
        assertEquals("user changed".toByteArray().size.toLong(), result.quarantinedBytes)
        val recovery = File(instance,
            ".sillyclient-maintenance/recovery/${result.recoveryIds.single()}/payload/user-note")
        assertEquals("user changed", recovery.readText())
        val current = runtime.listRecovery("local").items.single()
        assertTrue(current.canRestore)
        assertTrue(runtime.restore("local", current.recoveryId, current.token).success)
        assertEquals("user changed", file.readText())
    }

    @Test
    fun changedQuarantineBeyondTheInspectionLimitCannotReceiveARecoveryToken() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        val file = write(broken, "user-note", "keep")
        val runtime = service(root, commit = { _, action -> file.writeText("x".repeat(2048)); action() },
            limits = InstanceMaintenance.Limits(maxBytes = 1024))
        val result = apply(runtime, runtime.scan("local"))
        assertFalse(result.success)
        assertEquals(0, result.freedBytes)
        assertEquals(0, result.quarantinedBytes)
        val recovery = File(instance,
            ".sillyclient-maintenance/recovery/${result.recoveryIds.single()}/payload/user-note")
        assertEquals("x".repeat(2048), recovery.readText())
        val current = runtime.listRecovery("local")
        assertTrue(current.items.isEmpty())
        assertTrue(current.warnings.isNotEmpty())
    }

    @Test
    fun settingsFailuresDoNotExposeTheirContents() = withRoot { root, instance ->
        val settings = write(instance, "data/default-user/settings.json",
            """{"extension_settings":{"disabledExtensions":["third-party/missing"],"apiKey":"TOP_SECRET"}}""")
        val runtime = service(root, commit = { _, _ -> throw IllegalStateException("TOP_SECRET") })
        val result = apply(runtime, runtime.scan("local"))
        assertFalse(result.success)
        assertFalse(result.results.single().error!!.contains("TOP_SECRET"))
        assertTrue(settings.readText().contains("TOP_SECRET"))
    }

    @Test
    fun anExtensionInstalledDuringAtomicCommitKeepsItsDisabledPreference() = withRoot { root, instance ->
        val original = """{"extension_settings":{"disabledExtensions":["third-party/missing"]}}"""
        val settings = write(instance, "data/default-user/settings.json", original)
        val runtime = service(root, commit = { _, action -> extension(instance, "missing", """{}"""); action() })
        val result = apply(runtime, runtime.scan("local"))
        assertFalse(result.success)
        assertEquals(original, settings.readText())
        assertTrue(File(instance, "data/default-user/extensions/missing/manifest.json").exists())
    }

    @Test
    fun completedAndUnpublishedHistoryDoesNotUseTheActiveRecoveryItemLimit() = withRoot { root, instance ->
        val recoveryRoot = File(instance, ".sillyclient-maintenance/recovery")
        repeat(6) { index ->
            val id = UUID.randomUUID().toString()
            write(recoveryRoot, "$id/record.json", JSONObject().put("revision", 1)
                .put("owner", "sillyclient").put("recoveryId", id)
                .put("status", if (index % 2 == 0) "restored" else "prepared").toString())
        }
        extension(instance, "interrupted")
        val runtime = service(root, limits = InstanceMaintenance.Limits(maxItems = 1))
        val applied = apply(runtime, runtime.scan("local"))
        val list = runtime.listRecovery("local")
        assertTrue(list.warnings.isEmpty())
        assertEquals(applied.recoveryIds.single(), list.items.single().recoveryId)
        assertTrue(list.items.single().canRestore)
    }

    @Test
    fun activeRecoveryOverflowReturnsSafeItemsAndKeepsRemainingRecords() = withRoot { root, instance ->
        repeat(3) { extension(instance, "interrupted-$it") }
        val runtime = service(root)
        val applied = apply(runtime, runtime.scan("local"))
        val limited = service(root, limits = InstanceMaintenance.Limits(maxItems = 1))
        repeat(3) {
            val list = limited.listRecovery("local")
            assertEquals(1, list.items.size)
            if (it < 2) assertTrue(list.warnings.isNotEmpty())
            val item = list.items.single()
            assertTrue(limited.restore("local", item.recoveryId, item.token).success)
        }
        assertTrue(limited.listRecovery("local").items.isEmpty())
        for (id in applied.recoveryIds) {
            val history = File(instance, ".sillyclient-maintenance/recovery-history/$id/record.json")
            assertEquals("restored", JSONObject(history.readText()).getString("status"))
        }
        repeat(3) { assertTrue(File(instance, "data/default-user/extensions/interrupted-$it").exists()) }
    }

    @Test
    fun historyArchivalFailureDoesNotUndoACompletedRecoveryOrDeleteAnything() = withRoot { root, instance ->
        val broken = extension(instance, "interrupted")
        write(broken, "user-note", "keep")
        val runtime = service(root)
        apply(runtime, runtime.scan("local"))
        val item = runtime.listRecovery("local").items.single()
        val history = write(instance, ".sillyclient-maintenance/recovery-history", "occupied history")
        assertTrue(runtime.restore("local", item.recoveryId, item.token).success)
        assertEquals("keep", File(broken, "user-note").readText())
        assertEquals("occupied history", history.readText())
        val record = File(instance, ".sillyclient-maintenance/recovery/${item.recoveryId}/record.json")
        assertEquals("restored", JSONObject(record.readText()).getString("status"))
        assertTrue(runtime.listRecovery("local").items.isEmpty())
    }

    @Test
    fun recoveryHistoryInspectionHasABudgetAndReturnsWarningsInsteadOfThrowing() = withRoot { root, instance ->
        repeat(8) {
            val id = UUID.randomUUID().toString()
            write(instance, ".sillyclient-maintenance/recovery/$id/record.json",
                JSONObject().put("status", "restored").toString())
        }
        val runtime = service(root, limits = InstanceMaintenance.Limits(maxScanEntries = 3))
        val list = runtime.listRecovery("local")
        assertTrue(list.items.isEmpty())
        assertTrue(list.warnings.isNotEmpty())
        assertEquals(8, File(instance, ".sillyclient-maintenance/recovery").listFiles()!!.size)
    }
}
