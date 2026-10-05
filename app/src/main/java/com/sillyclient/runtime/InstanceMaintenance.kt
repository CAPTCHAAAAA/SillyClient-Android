package com.sillyclient.runtime

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Maintenance plans are capabilities for one stopped, managed instance, never arbitrary paths. */
class InstanceMaintenance(
    private val serversRoot: File,
    private val isRuntimeBusy: (String) -> Boolean,
    private val validateStandardDataRoot: (File) -> Unit,
    private val commitMutation: (String, () -> Unit) -> Unit = { _, action -> action() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val minimumCacheAgeMillis: Long = 24 * 60 * 60 * 1000L,
    private val limits: Limits = Limits(),
    private val installLocations: InstallLocationRegistry? = null
) {
    data class Limits(
        val maxEntries: Int = 8192,
        val maxBytes: Long = 128 * 1024 * 1024L,
        val maxUsers: Int = 256,
        val maxItems: Int = 512,
        val maxDepth: Int = 64,
        val maxScanEntries: Int = 16_384,
        val maxScanBytes: Long = 256 * 1024 * 1024L,
        val maxWarnings: Int = 512
    )
    data class Item(
        val id: String,
        val token: String,
        val kind: String,
        val relativePath: String,
        val sizeBytes: Long,
        val description: String,
        val confidence: String,
        val defaultSelected: Boolean,
        val action: String
    )
    data class Scan(val scanId: String, val expiresAt: Long, val items: List<Item>, val warnings: List<String>)
    data class Selection(val id: String, val token: String)
    data class Result(
        val id: String,
        val success: Boolean,
        val action: String,
        val freedBytes: Long = 0,
        val quarantinedBytes: Long = 0,
        val recoveryId: String? = null,
        val error: String? = null
    )
    data class Applied(
        val success: Boolean,
        val results: List<Result>,
        val freedBytes: Long,
        val quarantinedBytes: Long,
        val recoveryIds: List<String>
    )
    data class Recovery(
        val recoveryId: String, val token: String, val createdAt: Long, val description: String,
        val relativePath: String, val kind: String, val action: String, val sizeBytes: Long,
        val canRestore: Boolean, val conflict: String? = null
    )
    data class RecoveryList(val items: List<Recovery>, val warnings: List<String>)
    data class Restored(
        val success: Boolean, val recoveryId: String? = null, val relativePath: String? = null,
        val error: String? = null
    )
    private data class Guard(val key: String?, val created: FileTime, val modified: FileTime,
        val size: Long, val directory: Boolean)
    private data class Snapshot(val digest: String, val bytes: Long, val guard: Guard, val contentDigest: String)
    private class InspectionBudget(var entries: Int = 0, var bytes: Long = 0, var exhausted: Boolean = false)
    private data class Planned(
        val item: Item,
        val file: File,
        val snapshot: Snapshot,
        val settingsReference: String? = null,
        val userRoot: File? = null
    )
    private data class Plan(
        val instanceId: String,
        val directory: File,
        val identity: String,
        val config: Snapshot?,
        val settings: Map<File, Snapshot>,
        val scan: Scan,
        val items: Map<String, Planned>
    )
    private data class RestorePlan(
        val item: Recovery, val instanceId: String, val directory: File, val identity: String,
        val config: Snapshot?, val recovery: File, val record: Snapshot, val payload: Snapshot,
        val destination: File, val destinationState: Snapshot?, val expiresAt: Long
    )

    private var plan: Plan? = null
    private val restorePlans = mutableMapOf<String, RestorePlan>()

    @Synchronized
    fun invalidate() { plan = null; restorePlans.clear() }

    @Synchronized
    fun scan(instanceId: String, requestedPath: String? = null): Scan {
        plan = null
        val budget = InspectionBudget()
        val directory = instance(instanceId, requestedPath)
        val identity = identity(directory)
        val config = configSnapshot(directory, budget)
        val warnings = mutableListOf<String>()
        val planned = linkedMapOf<String, Planned>()
        val settings = linkedMapOf<File, Snapshot>()
        val users = userRoots(directory)
        fun warn(file: File, reason: String) {
            if (warnings.size < limits.maxWarnings) warnings.add("${relative(directory, file)}: $reason")
            else budget.exhausted = true
        }
        fun add(
            file: File, kind: String, action: String, description: String,
            confidence: String = "suspected", defaultSelected: Boolean = false,
            reference: String? = null, user: File? = null, state: Snapshot? = null
        ) {
            check(planned.size < limits.maxItems) { "Maintenance item limit reached; no plan was issued" }
            val snapshot = state ?: snapshot(file, directory, budget)
            val id = UUID.randomUUID().toString()
            val path = relative(directory, file) +
                if (reference == null) "" else "#extension_settings.disabledExtensions/$reference"
            val item = Item(id, UUID.randomUUID().toString(), kind, path,
                if (reference == null) snapshot.bytes else 0, description, confidence, defaultSelected, action)
            planned[id] = Planned(item, file, snapshot, reference, user)
        }
        val roots = listOf(globalExtensions(directory)) + users.map { File(it, "extensions") }
        roots.forEach { root ->
            if (budget.exhausted) return@forEach
            if (!exists(root)) return@forEach
            if (!ManagedFiles.isWithin(root, directory) || !root.isDirectory) {
                warn(root, "Unsafe extension root was preserved")
                return@forEach
            }
            val children = children(root)
            children.sortedBy { it.name }.forEach { extension ->
                if (budget.exhausted) return@forEach
                try {
                    spend(budget, entries = 1)
                    require(extension.isDirectory && ManagedFiles.isWithin(extension, directory)) {
                        "Linked or non-directory extension was preserved"
                    }
                    val issue = manifestIssue(extension, budget)
                    if (issue != null) add(extension, "broken_extension", "quarantine", issue)
                } catch (error: Exception) {
                    warn(extension, error.message ?: "Extension could not be safely scanned")
                }
            }
        }
        users.forEach { user ->
            if (budget.exhausted) return@forEach
            val file = File(user, "settings.json")
            if (!exists(file)) return@forEach
            try {
                val settingsSnapshot = snapshot(file, directory, budget)
                require(file.isFile && file.length() <= MAX_JSON_BYTES) { "Settings are not a bounded regular file" }
                settings[file] = settingsSnapshot
                val document = JSONObject(readJsonText(file, directory, budget))
                val disabled = disabledReferences(document) ?: return@forEach
                (0 until disabled.length()).map { disabled.getString(it) }.distinct().forEach { reference ->
                    if (missingReference(directory, user, reference)) {
                        add(file, "stale_extension_reference", "remove_disabled_reference",
                            "Missing extension is still disabled: $reference", reference = reference, user = user,
                            state = settingsSnapshot)
                    }
                }
            } catch (error: Exception) {
                warn(file, "Settings were preserved because their metadata could not be safely inspected")
            }
        }
        val cacheRoot = File(directory, "$MAINTENANCE/download-cache")
        if (exists(cacheRoot) && !budget.exhausted) {
            if (!ManagedFiles.isWithin(cacheRoot, directory) || !cacheRoot.isDirectory) {
                warn(cacheRoot, "Unsafe download cache was preserved")
            } else {
                val children = children(cacheRoot)
                children.sortedBy { it.name }.forEach { cache ->
                    if (budget.exhausted) return@forEach
                    try {
                        spend(budget, entries = 1)
                        if (ownedCache(cache, directory, instanceId, budget)) {
                            add(cache, "download_cache", "delete_cache", "Expired owned download cache",
                                confidence = "owned", defaultSelected = true)
                        }
                    } catch (error: Exception) {
                        warn(cache, error.message ?: "Unverified download cache was preserved")
                    }
                }
            }
        }
        val finalBudget = InspectionBudget()
        check(!isRuntimeBusy(instanceId) && identity(directory) == identity &&
            configSnapshot(directory, finalBudget) == config &&
            settings.all { (file, state) -> snapshot(file, directory, finalBudget) == state }) {
            "Instance changed during the scan; scan again after stopping it"
        }
        val scan = Scan(UUID.randomUUID().toString(), clock() + TOKEN_LIFETIME_MILLIS,
            planned.values.map { it.item }, warnings)
        plan = Plan(instanceId, directory, identity, config, settings, scan, planned)
        return scan
    }

    @Synchronized
    fun apply(instanceId: String, scanId: String, selected: List<Selection>, requestedPath: String? = null): Applied {
        val current = plan ?: throw IllegalArgumentException("A current maintenance scan is required")
        require(current.instanceId == instanceId && current.scan.scanId == scanId) { "Maintenance scan does not match" }
        plan = null
        require(clock() <= current.scan.expiresAt) { "Maintenance scan expired" }
        require(selected.map { it.id }.distinct().size == selected.size) { "Duplicate maintenance selection" }
        val actions = selected.map { selection ->
            val item = current.items[selection.id] ?: throw IllegalArgumentException("Unknown maintenance item")
            require(item.item.token == selection.token) { "Invalid maintenance token" }
            item
        }
        val directory = instance(instanceId, requestedPath)
        val budget = InspectionBudget()
        require(directory == current.directory && identity(directory) == current.identity &&
            configSnapshot(directory, budget) == current.config &&
            current.settings.all { (file, state) -> snapshot(file, directory, budget) == state }) {
            "Instance or settings changed; scan again"
        }
        val revalidatedSettings = mutableSetOf<File>()
        actions.forEach {
            if (it.settingsReference == null || revalidatedSettings.add(it.file)) revalidate(it, current, budget)
            if (it.settingsReference != null) require(missingReference(directory, it.userRoot!!, it.settingsReference)) {
                "Extension reference is no longer stale"
            }
        }
        val postCommitBudget = InspectionBudget()
        val preparationBudget = InspectionBudget()
        val results = mutableListOf<Result>()
        val settingsActions = actions.filter { it.settingsReference != null }.groupBy { it.file }
        actions.filter { it.settingsReference == null }.forEach { action ->
            var recoveryId: String? = null
            var moved = false
            var quarantinedBytes = 0L
            try {
                recoveryId = UUID.randomUUID().toString()
                val recovery = createRecovery(directory, recoveryId)
                val destination = File(recovery, "payload")
                val record = writeRecoveryRecord(recovery, action, recoveryId, directory)
                val recordFile = File(recovery, "record.json")
                val recordState = snapshot(recordFile, directory, preparationBudget)
                // Cache is also quarantined in this first version: no recursive deletion under the runtime lock.
                commitMutation(instanceId) {
                    finalGuards(action, current, recovery)
                    require(!exists(destination)) { "Recovery destination already exists" }
                    Files.move(action.file.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    moved = true
                }
                val actual = snapshot(destination, directory, postCommitBudget)
                require(actual.guard.key == action.snapshot.guard.key &&
                    actual.guard.created == action.snapshot.guard.created &&
                    actual.guard.directory == action.snapshot.guard.directory) {
                    "Quarantine identity changed; files and metadata were preserved"
                }
                quarantinedBytes = actual.bytes
                if (actual != action.snapshot) {
                    require(snapshot(recordFile, directory, postCommitBudget) == recordState) {
                        "Recovery metadata changed; files were preserved"
                    }
                    record.put("payloadDigest", actual.digest).put("status", "quarantined_changed")
                    writeRecordAtomically(recordFile, directory, record)
                    throw IllegalStateException("Quarantined content changed during maintenance; the actual content remains recoverable")
                }
                results.add(Result(action.item.id, true, action.item.action,
                    quarantinedBytes = quarantinedBytes, recoveryId = recoveryId))
            } catch (error: Exception) {
                results.add(Result(action.item.id, false, action.item.action,
                    quarantinedBytes = quarantinedBytes,
                    recoveryId = recoveryId.takeIf { moved },
                    error = error.message ?: "Maintenance failed; existing files were preserved"))
            }
        }
        settingsActions.forEach { (file, items) ->
            var recoveryId: String? = null
            var replaced = false
            var temp: File? = null
            try {
                val referenceRoots = listOf(globalExtensions(directory), File(items.first().userRoot, "extensions"))
                val referenceStates = referenceRoots.associateWith { root ->
                    if (exists(root)) guard(root, directory) else null
                }
                items.forEach { require(missingReference(directory, it.userRoot!!, it.settingsReference!!)) {
                    "Extension reference is no longer stale"
                } }
                val document = JSONObject(readJsonText(file, directory, preparationBudget))
                val disabled = disabledReferences(document) ?: throw IllegalStateException("Disabled settings changed")
                val removals = items.mapNotNull { it.settingsReference }.toSet()
                val kept = (0 until disabled.length()).map { disabled.getString(it) }.filterNot { it in removals }
                document.getJSONObject("extension_settings").put("disabledExtensions", JSONArray(kept))
                recoveryId = UUID.randomUUID().toString()
                val recovery = createRecovery(directory, recoveryId)
                val original = File(recovery, "payload")
                Files.copy(file.toPath(), original.toPath(), LinkOption.NOFOLLOW_LINKS)
                val backup = snapshot(original, directory, preparationBudget)
                require(backup.contentDigest == items.first().snapshot.contentDigest &&
                    snapshot(file, directory, preparationBudget) == items.first().snapshot) { "Settings changed during preparation" }
                val staged = File(file.parentFile, ".sillyclient-settings-${UUID.randomUUID()}.tmp")
                temp = staged
                check(staged.createNewFile()) { "Could not stage settings maintenance" }
                staged.writeText(document.toString(2), Charsets.UTF_8)
                val stagedState = snapshot(staged, directory, preparationBudget)
                writeRecoveryRecord(recovery, items.first(), recoveryId, directory, backup.digest, stagedState.digest)
                commitMutation(instanceId) {
                    finalGuards(items.first(), current, recovery)
                    require(guard(staged, directory) == stagedState.guard &&
                        referenceStates.all { (root, state) ->
                            if (state == null) !exists(root) else guard(root, directory) == state
                        }) {
                        "Settings preparation changed"
                    }
                    Files.move(staged.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                    replaced = true
                }
                items.forEach { results.add(Result(it.item.id, true, it.item.action, recoveryId = recoveryId)) }
            } catch (error: Exception) {
                items.forEach { results.add(Result(it.item.id, false, it.item.action,
                    recoveryId = recoveryId.takeIf { replaced },
                    error = "Settings maintenance could not complete safely; original settings and recovery were preserved")) }
            } finally {
                temp?.takeIf { exists(it) && ManagedFiles.isWithin(it, directory) }?.delete()
            }
        }
        return Applied(results.all { it.success }, results, results.sumOf { it.freedBytes },
            results.sumOf { it.quarantinedBytes }, results.mapNotNull { it.recoveryId }.distinct())
    }

    @Synchronized
    fun listRecovery(instanceId: String, requestedPath: String? = null): RecoveryList {
        restorePlans.clear()
        val directory = instance(instanceId, requestedPath)
        val instanceIdentity = identity(directory)
        val budget = InspectionBudget()
        val config = configSnapshot(directory, budget)
        val root = File(directory, "$MAINTENANCE/recovery")
        if (!exists(root)) return RecoveryList(emptyList(), emptyList())
        require(root.isDirectory && ManagedFiles.isWithin(root, directory)) { "Unsafe maintenance recovery root" }
        val items = mutableListOf<Recovery>()
        val warnings = mutableListOf<String>()
        fun warn(message: String) {
            if (warnings.size < limits.maxWarnings) warnings.add(message)
            else budget.exhausted = true
        }
        Files.newDirectoryStream(root.toPath()).use { paths ->
            for (path in paths) {
                if (budget.exhausted) {
                    warn("Recovery inspection limit reached; uninspected records were preserved")
                    break
                }
                val recovery = path.toFile()
                try {
                    spend(budget, entries = 1)
                    require(recovery.name.matches(UUID_PATTERN) && recovery.isDirectory &&
                        ManagedFiles.isWithin(recovery, directory)) { "Unsafe recovery directory was preserved" }
                    val recordFile = File(recovery, "record.json")
                    val record = JSONObject(readJsonText(recordFile, directory, budget))
                    if (record.optString("status") == "restored") continue
                    require(record.optInt("revision") == 1 && record.optString("owner") == "sillyclient" &&
                        record.optString("recoveryId") == recovery.name) { "Recovery ownership metadata is invalid" }
                    val payload = File(recovery, "payload")
                    if (!exists(payload)) continue
                    if (items.size >= limits.maxItems) {
                        warn("Recovery item limit reached; remaining records were preserved")
                        break
                    }
                    val destination = recoveryDestination(directory, record.getString("originalRelativePath"),
                        record.getString("action"))
                    val payloadState = snapshot(payload, directory, budget)
                    require(payloadState.digest == record.getString("payloadDigest")) {
                        "Recovery content changed; it was preserved"
                    }
                    val action = record.getString("action")
                    val currentState = if (action == "remove_disabled_reference" && exists(destination))
                        snapshot(destination, directory, budget) else null
                    val conflict = when {
                        destination.parentFile?.isDirectory != true -> "Original parent directory is unavailable"
                        action == "remove_disabled_reference" && (currentState == null ||
                            currentState.digest != record.optString("appliedDigest")) -> "Settings changed after maintenance"
                        action != "remove_disabled_reference" && exists(destination) -> "Original name is already occupied"
                        else -> null
                    }
                    val kind = when (action) {
                        "quarantine" -> "broken_extension"
                        "delete_cache" -> "download_cache"
                        else -> "stale_extension_reference"
                    }
                    val item = Recovery(recovery.name, UUID.randomUUID().toString(), record.getLong("createdAt"),
                        "Recoverable instance maintenance backup", relative(directory, destination), kind, action,
                        payloadState.bytes, conflict == null, conflict)
                    items.add(item)
                    if (item.canRestore) restorePlans[item.recoveryId] = RestorePlan(item, instanceId, directory,
                        instanceIdentity, config, recovery, snapshot(recordFile, directory, budget), payloadState,
                        destination, currentState, clock() + TOKEN_LIFETIME_MILLIS)
                } catch (_: Exception) {
                    warn("${relative(directory, recovery)}: Recovery was preserved but could not be safely inspected")
                }
            }
        }
        require(!isRuntimeBusy(instanceId) && identity(directory) == instanceIdentity &&
            configSnapshot(directory) == config) { "Instance changed while listing recovery" }
        return RecoveryList(items, warnings)
    }

    @Synchronized
    fun restore(instanceId: String, recoveryId: String, token: String?, requestedPath: String? = null): Restored {
        val restore = restorePlans[recoveryId] ?: throw IllegalArgumentException("List current recoveries before restoring")
        require(restore.instanceId == instanceId && token == restore.item.token) { "A matching recovery token is required" }
        restorePlans.remove(recoveryId)
        require(clock() <= restore.expiresAt) { "Recovery token expired" }
        val directory = instance(instanceId, requestedPath)
        val payload = File(restore.recovery, "payload")
        val recordFile = File(restore.recovery, "record.json")
        val budget = InspectionBudget()
        require(directory == restore.directory && identity(directory) == restore.identity &&
            configSnapshot(directory, budget) == restore.config &&
            snapshot(recordFile, directory, budget) == restore.record &&
            snapshot(payload, directory, budget) == restore.payload &&
            (if (restore.destinationState == null) !exists(restore.destination)
                else snapshot(restore.destination, directory, budget) == restore.destinationState)) {
            "Recovery or destination changed; list recovery again"
        }
        val record = JSONObject(readJsonText(recordFile, directory, budget))
        require(recoveryDestination(directory, restore.item.relativePath, restore.item.action) == restore.destination) {
            "Recovery destination changed"
        }
        var restored = false
        return try {
            if (restore.destinationState != null) {
                val backup = File(restore.recovery, "replaced-settings.json")
                if (!exists(backup)) Files.copy(restore.destination.toPath(), backup.toPath(), LinkOption.NOFOLLOW_LINKS)
                require(snapshot(backup, directory, budget).contentDigest == restore.destinationState.contentDigest) {
                    "Settings changed while preparing recovery"
                }
            }
            commitMutation(instanceId) {
                val configFile = File(directory, "config.yaml")
                require(!isRuntimeBusy(instanceId) && identity(directory) == restore.identity &&
                    guard(recordFile, directory) == restore.record.guard &&
                    guard(payload, directory) == restore.payload.guard &&
                    (if (restore.config == null) !exists(configFile) else guard(configFile, directory) == restore.config.guard) &&
                    (if (restore.destinationState == null) !exists(restore.destination)
                        else guard(restore.destination, directory) == restore.destinationState.guard) &&
                    ManagedFiles.isWithin(restore.destination, directory) &&
                    restore.destination.parentFile?.isDirectory == true) { "Recovery changed before commit" }
                if (restore.destinationState == null) {
                    Files.move(payload.toPath(), restore.destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } else {
                    Files.move(payload.toPath(), restore.destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                }
                restored = true
            }
            require(snapshot(restore.destination, directory, budget) == restore.payload) {
                "Restored content changed; existing data was preserved"
            }
            record.put("status", "restored").put("restoredAt", clock())
            writeRecordAtomically(recordFile, directory, record)
            runCatching { archiveRestoredRecovery(restore.recovery, directory) }
            Restored(true, recoveryId, restore.item.relativePath)
        } catch (_: Exception) {
            Restored(false, recoveryId.takeIf { restored }, restore.item.relativePath,
                "Recovery could not complete safely; original files and recovery metadata were preserved")
        }
    }

    private fun instance(instanceId: String, requestedPath: String? = null): File {
        require(instanceId.matches(Regex("[\\p{L}\\p{N}._-]{1,80}")) && instanceId !in setOf(".", "..")) {
            "Invalid managed instance identity"
        }
        require(!isRuntimeBusy(instanceId)) { "Stop the instance and wait for runtime tasks before maintenance" }
        val directory = installLocations?.resolve(instanceId, requestedPath) ?: (requestedPath?.let { File(it) } ?: File(serversRoot, instanceId))
        val root = installLocations?.allowedRootFor(directory) ?: serversRoot
        require(directory.isDirectory && ManagedFiles.isWithin(directory, root) &&
            File(directory, "server.js").isFile && ManagedFiles.isWithin(File(directory, "server.js"), directory)) {
            "Only installed local managed instances support maintenance"
        }
        validateStandardDataRoot(directory)
        require(ManagedFiles.isWithin(File(directory, "data"), directory)) { "Unsafe instance data root" }
        return directory
    }

    private fun userRoots(directory: File): List<File> {
        val data = File(directory, "data")
        if (!exists(data)) return emptyList()
        require(data.isDirectory && ManagedFiles.isWithin(data, directory)) { "Unsafe instance data root" }
        val children = children(data, limits.maxUsers + 16)
        val users = children.filter { it.isDirectory && !it.name.startsWith('_') }
        require(users.all { ManagedFiles.isWithin(it, directory) }) { "Linked user data was preserved" }
        check(users.size <= limits.maxUsers) { "Instance user limit reached" }
        return users.sortedBy { it.name }
    }

    private fun globalExtensions(directory: File) = File(directory, "public/scripts/extensions/third-party")

    private fun manifestIssue(directory: File, budget: InspectionBudget? = null): String? {
        val manifest = File(directory, "manifest.json")
        if (!exists(manifest)) return "Extension manifest is missing; installation may be incomplete"
        require(manifest.isFile && ManagedFiles.isWithin(manifest, directory)) { "Unsafe extension manifest was preserved" }
        if (manifest.length() !in 1..MAX_JSON_BYTES) return "Extension manifest is empty or exceeds the inspection limit"
        val document = try { JSONObject(readJsonText(manifest, directory, budget)) }
        catch (_: Exception) { return "Extension manifest is not a JSON object" }
        for (key in listOf("js", "css")) {
            val value = document.opt(key)
            if (value == null || value == JSONObject.NULL || value == "") continue
            if (value !is String) return "Extension declares an invalid $key asset"
            val path = assetPath(value) ?: throw IllegalArgumentException("Unsafe declared extension asset was preserved")
            val file = File(directory, path)
            require(ManagedFiles.isWithin(file, directory)) { "Linked extension asset was preserved" }
            if (!file.isFile || file.length() == 0L) return "Declared extension $key asset is missing or empty"
        }
        return null
    }

    private fun assetPath(value: String): String? =
        value.takeIf { it.isNotBlank() && !it.startsWith('/') && !it.contains('\\') && !it.contains(':') &&
            it.none { char -> char.code < 32 } && it.split('/').none { part -> part in setOf("", ".", "..") } }

    private fun disabledReferences(document: JSONObject): JSONArray? {
        val extensions = document.opt("extension_settings") ?: return null
        require(extensions is JSONObject) { "Extension settings are not a JSON object" }
        val disabled = extensions.opt("disabledExtensions") ?: return null
        require(disabled is JSONArray && (0 until disabled.length()).all { disabled.opt(it) is String }) {
            "Disabled extension settings are not a string array"
        }
        return disabled
    }

    private fun missingReference(directory: File, user: File, reference: String): Boolean {
        if (!reference.startsWith("third-party/")) return false
        val name = reference.removePrefix("third-party/")
        if (name.isBlank() || name in setOf(".", "..") || name.any { it in "/\\:" || it.code < 32 }) return false
        val local = File(user, "extensions/$name")
        val global = File(globalExtensions(directory), name)
        if (listOf(local.parentFile, global.parentFile).any { root ->
            root == null || (exists(root) && !root.isDirectory)
        }) return false
        return ManagedFiles.isWithin(local, directory) && ManagedFiles.isWithin(global, directory) &&
            !exists(local) && !exists(global)
    }

    private fun ownedCache(
        cache: File, directory: File, instanceId: String, budget: InspectionBudget? = null
    ): Boolean {
        require(cache.name.matches(UUID_PATTERN) && cache.isDirectory && ManagedFiles.isWithin(cache, directory)) {
            "Unowned download cache was preserved"
        }
        val marker = File(cache, "owner.json")
        val payload = File(cache, "download.zip")
        val children = children(cache, 3)
        require(children.map { it.name }.toSet() == setOf("owner.json", "download.zip") &&
            marker.isFile && payload.isFile && ManagedFiles.isWithin(marker, cache) &&
            ManagedFiles.isWithin(payload, cache) && marker.length() in 1..MAX_JSON_BYTES) {
            "Unverified download cache was preserved"
        }
        val record = JSONObject(readJsonText(marker, directory, budget))
        require(record.optInt("revision") == 1 && record.optString("owner") == "sillyclient" &&
            record.optString("instanceId") == instanceId && record.optString("payload") == "download.zip" &&
            record.optLong("sizeBytes", -1) == payload.length()) { "Download ownership record does not match" }
        val digest = snapshot(payload, directory, budget)
        require(record.optString("sha256").equals(contentDigest(payload, budget), ignoreCase = true) &&
            digest.bytes == payload.length()) { "Download ownership digest does not match" }
        return children.all { clock() - it.lastModified() >= minimumCacheAgeMillis } &&
            clock() - cache.lastModified() >= minimumCacheAgeMillis
    }

    private fun revalidate(action: Planned, current: Plan, budget: InspectionBudget) {
        require(snapshot(action.file, current.directory, budget) == action.snapshot) { "Maintenance item changed; scan again" }
        when (action.item.action) {
            "quarantine" -> require(manifestIssue(action.file, budget) != null) { "Extension is no longer broken" }
            "delete_cache" -> require(ownedCache(action.file, current.directory, current.instanceId, budget)) {
                "Download cache is no longer eligible"
            }
            "remove_disabled_reference" -> require(action.userRoot != null &&
                missingReference(current.directory, action.userRoot, action.settingsReference!!)) {
                "Extension reference is no longer stale"
            }
            else -> throw IllegalArgumentException("Unknown maintenance action")
        }
    }

    private fun finalGuards(action: Planned, current: Plan, recovery: File) {
        val config = File(current.directory, "config.yaml")
        require(!isRuntimeBusy(current.instanceId) && identity(current.directory) == current.identity &&
            guard(action.file, current.directory) == action.snapshot.guard &&
            ManagedFiles.isWithin(recovery, current.directory) && recovery.isDirectory &&
            (if (current.config == null) !exists(config) else guard(config, current.directory) == current.config.guard)) {
            "Instance or maintenance item changed before commit"
        }
    }

    private fun createRecovery(directory: File, id: String): File {
        val recovery = File(directory, "$MAINTENANCE/recovery/$id")
        require(ManagedFiles.isWithin(recovery, directory)) { "Unsafe recovery directory" }
        check(!exists(recovery) && recovery.mkdirs()) { "Could not create maintenance recovery" }
        return recovery
    }

    private fun writeRecoveryRecord(
        recovery: File, action: Planned, recoveryId: String, directory: File,
        payloadDigest: String = action.snapshot.digest, appliedDigest: String? = null
    ): JSONObject {
        val record = JSONObject().put("revision", 1).put("owner", "sillyclient").put("recoveryId", recoveryId)
            .put("action", action.item.action).put("originalRelativePath", relative(directory, action.file))
            .put("digest", action.snapshot.digest).put("payloadDigest", payloadDigest).put("createdAt", clock())
        appliedDigest?.let { record.put("appliedDigest", it) }
        val file = File(recovery, "record.json")
        check(file.createNewFile()) { "Could not record maintenance recovery" }
        file.writeText(record.toString(2), Charsets.UTF_8)
        return record
    }

    private fun recoveryDestination(directory: File, path: String, action: String): File {
        val safePath = assetPath(path) ?: throw IllegalArgumentException("Unsafe recovery path")
        val segments = safePath.split('/')
        val allowed = when (action) {
            "quarantine" -> (segments.size == 5 && segments.take(4) ==
                listOf("public", "scripts", "extensions", "third-party")) ||
                (segments.size == 4 && segments[0] == "data" && segments[2] == "extensions" &&
                    !segments[1].startsWith('_'))
            "delete_cache" -> segments.size == 3 && segments.take(2) == listOf(MAINTENANCE, "download-cache") &&
                segments.last().matches(UUID_PATTERN)
            "remove_disabled_reference" -> segments.size == 3 && segments[0] == "data" &&
                !segments[1].startsWith('_') && segments[2] == "settings.json"
            else -> false
        }
        val destination = File(directory, safePath)
        require(allowed && ManagedFiles.isWithin(destination, directory)) { "Recovery is outside its original maintenance scope" }
        return destination
    }

    private fun writeRecordAtomically(file: File, directory: File, record: JSONObject) {
        val temp = File(file.parentFile, ".record-${UUID.randomUUID()}.tmp")
        require(ManagedFiles.isWithin(file, directory) && ManagedFiles.isWithin(temp, directory)) {
            "Unsafe recovery metadata path"
        }
        try {
            check(temp.createNewFile()) { "Could not record recovery completion" }
            temp.writeText(record.toString(2), Charsets.UTF_8)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { if (exists(temp) && ManagedFiles.isWithin(temp, directory)) temp.delete() }
    }

    private fun archiveRestoredRecovery(recovery: File, directory: File) {
        val history = File(directory, "$MAINTENANCE/recovery-history")
        val destination = File(history, recovery.name)
        require(ManagedFiles.isWithin(history, directory) && ManagedFiles.isWithin(recovery, directory)) {
            "Unsafe maintenance history path"
        }
        check((exists(history) || history.mkdirs()) && history.isDirectory &&
            ManagedFiles.isWithin(destination, directory) && !exists(destination)) {
            "Maintenance history is unavailable"
        }
        Files.move(recovery.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun configSnapshot(directory: File, budget: InspectionBudget? = null): Snapshot? =
        File(directory, "config.yaml").takeIf(::exists)?.let { snapshot(it, directory, budget) }

    private fun identity(directory: File): String {
        if (installLocations != null) {
            val registered = installLocations.entries().values.any { it.canonicalFile == directory.canonicalFile }
            require(registered || installLocations.resolve(directory.name).canonicalFile == directory.canonicalFile) {
                "Instance maintenance location no longer matches its registration"
            }
        }
        val attributes = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val root = installLocations?.allowedRootFor(directory) ?: serversRoot
        require(attributes.isDirectory && !attributes.isSymbolicLink && ManagedFiles.isWithin(directory, root)) {
            "Instance identity is no longer a safe directory"
        }
        return "${directory.canonicalPath}:${attributes.fileKey()}:${attributes.creationTime().toMillis()}"
    }

    private fun snapshot(file: File, directory: File, budget: InspectionBudget? = null): Snapshot {
        require(ManagedFiles.isWithin(file, directory) && exists(file)) { "Maintenance path is unsafe or missing" }
        val digest = MessageDigest.getInstance("SHA-256")
        val content = MessageDigest.getInstance("SHA-256")
        var entries = 0
        var bytes = 0L
        fun visit(current: File, depth: Int) {
            spend(budget, entries = 1)
            check(++entries <= limits.maxEntries) { "Maintenance tree exceeds the inspection entry limit" }
            check(depth <= limits.maxDepth) { "Maintenance tree exceeds the inspection depth limit" }
            require(ManagedFiles.isWithin(current, directory)) { "Linked maintenance files were preserved" }
            val before = Files.readAttributes(current.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(!before.isSymbolicLink && (before.isDirectory || before.isRegularFile)) { "Non-regular maintenance file was preserved" }
            val path = if (current == file) "." else current.relativeTo(file).invariantSeparatorsPath
            digest.update("$path\u0000${before.fileKey()}\u0000${before.creationTime().toMillis()}\u0000${before.lastModifiedTime().toMillis()}\u0000${before.size()}\u0000".toByteArray(Charsets.UTF_8))
            content.update("$path\u0000${before.isDirectory}\u0000".toByteArray(Charsets.UTF_8))
            if (before.isDirectory) {
                val children = children(current, limits.maxEntries - entries)
                children.sortedBy { it.name }.forEach { visit(it, depth + 1) }
            } else {
                check(before.size() <= limits.maxBytes - bytes) { "Maintenance tree exceeds the inspection byte limit" }
                Files.newInputStream(current.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        spend(budget, bytes = count.toLong())
                        bytes += count
                        check(bytes <= limits.maxBytes) { "Maintenance tree exceeds the inspection byte limit" }
                        digest.update(buffer, 0, count)
                        content.update(buffer, 0, count)
                    }
                }
            }
            val after = Files.readAttributes(current.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(before.fileKey() == after.fileKey() && before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime() && !after.isSymbolicLink) {
                "Maintenance files changed while being inspected"
            }
        }
        val rootGuard = guard(file, directory)
        visit(file, 0)
        require(rootGuard == guard(file, directory)) { "Maintenance root changed during inspection" }
        return Snapshot(hex(digest), bytes, rootGuard, hex(content))
    }

    private fun guard(file: File, directory: File): Guard {
        require(ManagedFiles.isWithin(file, directory) && exists(file)) { "Maintenance path is unsafe or missing" }
        val state = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(!state.isSymbolicLink && (state.isDirectory || state.isRegularFile)) { "Unsafe maintenance file" }
        return Guard(state.fileKey()?.toString(), state.creationTime(), state.lastModifiedTime(), state.size(), state.isDirectory)
    }

    private fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it) }

    private fun contentDigest(file: File, budget: InspectionBudget? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(32 * 1024)
            var bytes = 0L
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                spend(budget, bytes = count.toLong())
                bytes += count
                check(bytes <= limits.maxBytes) { "Download exceeds the inspection byte limit" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readJsonText(file: File, directory: File, budget: InspectionBudget? = null): String {
        require(file.isFile && ManagedFiles.isWithin(file, directory) && file.length() <= MAX_JSON_BYTES) {
            "JSON metadata is not a bounded regular file"
        }
        Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                spend(budget, bytes = count.toLong())
                require(output.size().toLong() + count <= MAX_JSON_BYTES) { "JSON metadata exceeds the inspection limit" }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun children(directory: File, limit: Int = limits.maxEntries): List<File> =
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            val result = mutableListOf<File>()
            for (path in stream) {
                check(result.size < limit) { "Maintenance directory exceeds the inspection entry limit" }
                result.add(path.toFile())
            }
            result
        }

    private fun spend(budget: InspectionBudget?, entries: Int = 0, bytes: Long = 0) {
        if (budget == null) return
        if (budget.exhausted || entries > limits.maxScanEntries - budget.entries ||
            bytes > limits.maxScanBytes - budget.bytes) {
            budget.exhausted = true
            throw IllegalStateException("Maintenance scan limit reached; uninspected files were preserved")
        }
        budget.entries += entries
        budget.bytes += bytes
    }

    private fun relative(directory: File, file: File) = file.relativeTo(directory).invariantSeparatorsPath
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    companion object {
        private const val MAINTENANCE = ".sillyclient-maintenance"
        private const val MAX_JSON_BYTES = 1024L * 1024
        private const val TOKEN_LIFETIME_MILLIS = 5 * 60 * 1000L
        private val UUID_PATTERN = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
    }
}
