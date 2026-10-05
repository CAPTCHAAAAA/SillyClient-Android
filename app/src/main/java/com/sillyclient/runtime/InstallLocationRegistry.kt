package com.sillyclient.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Instance identity is independent of the final directory's display name. */
class InstallLocationRegistry(
    private val appFilesDir: File,
    private val serversRoot: File,
    private val installationsRoot: File,
    private val registryFile: File,
    private val legacyServersRoot: File? = null,
    private val customRootsProvider: (() -> List<File>)? = null,
    // Tree-backed instances resolve dependencies through the shared private-storage
    // tree and intentionally carry no local node_modules directory.
    private val dependenciesComplete: (File) -> Boolean = { File(it, "node_modules").isDirectory }
) {
    private data class Location(val directory: File, val fileKey: String?, val createdAt: Long,
        val retainedDirectories: List<File> = emptyList())
    private data class RegistryState(
        val locations: MutableMap<String, Location> = linkedMapOf(),
        val retiredSources: MutableSet<File> = linkedSetOf()
    ) {
        fun retainedCount(): Int = retiredSources.size + locations.values.sumOf { it.retainedDirectories.size }
    }
    data class RelocationPlan internal constructor(
        val instanceId: String, val source: File, val target: File,
        internal val expectedKey: String?, internal val createdAt: Long
    )
    private val lock = locks.computeIfAbsent(registryFile.canonicalPath) { Any() }
    private val relocationJournal = RelocationJournal(File(registryFile.parentFile, "install-location-move.json"), appFilesDir)
    val allowedRoots: List<File> get() = (listOfNotNull(serversRoot, installationsRoot, legacyServersRoot,
        legacyServersRoot?.parentFile?.let { File(it, "installations") }) +
        customRootsProvider?.invoke().orEmpty()).distinctBy { normalized(it).path }

    fun entries(): Map<String, File> = synchronized(lock) {
        read().locations.mapNotNull { (id, location) ->
            validateIdentity(location)
            if (location.directory.exists() && location.directory.isDirectory) {
                id to location.directory
            } else null
        }.toMap()
    }

    fun retainedSources(): Set<File> = synchronized(lock) {
        val state = read()
        (state.locations.values.flatMap { it.retainedDirectories } + state.retiredSources).toSet()
    }

    /** Retired sources with no owning registration left, kept only to protect their files. */
    internal fun retiredSources(): Set<File> = synchronized(lock) { read().retiredSources.toSet() }

    /** Retained source directories still recorded for one instance. */
    internal fun retainedByInstance(instanceId: String): List<File> = synchronized(lock) {
        read().locations[normalizeInstanceId(instanceId)]?.retainedDirectories.orEmpty()
    }

    /** Drop a retired retained source from the registry once it is fully removed. */
    internal fun unretainPath(instanceId: String, path: File) = synchronized(lock) {
        val id = normalizeInstanceId(instanceId)
        val state = read()
        val current = state.locations[id] ?: return@synchronized
        val updated = current.copy(retainedDirectories = current.retainedDirectories.filterNot {
            it.canonicalFile == path.canonicalFile
        })
        if (updated.retainedDirectories.size != current.retainedDirectories.size) {
            state.locations[id] = updated
        }
        state.retiredSources.removeIf { it.canonicalFile == path.canonicalFile }
        write(state)
    }

    fun defaultRelocationTarget(instanceId: String, source: File, displayName: String? = null): File = synchronized(lock) {
        val id = normalizeInstanceId(instanceId)
        val name = normalizeInstanceId(displayName?.takeIf { it.isNotBlank() } ?: source.name)
        val preferred = File(serversRoot, name)
        if (preferred.canonicalFile == source.canonicalFile) source
        else chooseNamedTarget(serversRoot, id, name, read())
    }

    fun planRelocation(instanceId: String, targetPath: String? = null,
        installPath: String? = null, displayName: String? = null): RelocationPlan = synchronized(lock) {
        val id = normalizeInstanceId(instanceId)
        val source = resolve(id, installPath)
        require(source.isDirectory && File(source, "server.js").isFile && File(source, "package.json").isFile) {
            "The source is not an installed instance"
        }
        val records = read()
        val clean = targetPath?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()?.takeIf { it.isNotEmpty() }
        val target = if (clean == null) {
            defaultRelocationTarget(id, source, displayName)
        } else {
            require(clean.none { it.code < 32 } && !clean.contains("://") && !clean.startsWith("file:")) {
                "Relocation destinations must be native absolute paths"
            }
            File(clean)
        }
        validateRelocationTarget(id, source, target, records)
        registerCommitted(id, source)
        val registered = read()
        var current = requireNotNull(registered.locations[id])
        if (current.fileKey?.startsWith("marker:") != true) {
            validateIdentity(current)
            current = current.copy(fileKey = ensureDirectoryKey(source))
            registered.locations[id] = current
            write(registered)
        }
        RelocationPlan(id, current.directory, normalized(target), current.fileKey, current.createdAt)
    }

    /** Change one path while retaining its immutable identity; publish failures restore the source. */
    internal fun commitRelocation(plan: RelocationPlan, retainedSource: Boolean,
        publish: () -> Unit, rollback: () -> Unit, stagingOwner: String? = null) = synchronized(lock) {
        val state = read()
        val records = state.locations
        val current = requireNotNull(records[plan.instanceId]) { "The source registration disappeared" }
        require(current.directory == plan.source && current.fileKey == plan.expectedKey && current.createdAt == plan.createdAt) {
            "The instance registration changed during relocation"
        }
        validateIdentity(current)
        require(exists(plan.source)) { "The relocation source disappeared" }
        validateRelocationTarget(plan.instanceId, plan.source, plan.target, state)
        if (plan.source == plan.target) return@synchronized
        val retained = (current.retainedDirectories + if (retainedSource) listOf(plan.source) else emptyList()).distinct()
        require(retained.size <= 32) { "Too many retained relocation sources; review existing retained copies first" }
        require(state.retainedCount() + (if (retainedSource) 1 else 0) <= MAX_RETAINED_PATHS) {
            "The retained relocation history is full; existing sources were preserved"
        }
        require(stagingOwner == null || (retainedSource && UUID.fromString(stagingOwner).toString() == stagingOwner)) {
            "Invalid relocation staging ownership"
        }
        val intent = RelocationJournal.Entry(plan.instanceId, plan.source, plan.target,
            requireNotNull(plan.expectedKey), current.createdAt, retainedSource, current.retainedDirectories, stagingOwner)
        relocationJournal.write(intent)
        try {
            publish()
            val relocated = Location(plan.target, plan.expectedKey, current.createdAt, retained)
            validateRelocated(relocated)
            records[plan.instanceId] = relocated
            write(state)
        } catch (error: Exception) {
            try {
                if (exists(plan.target)) rollback()
                validateRelocated(current)
                require(!exists(plan.target)) { "Relocation rollback did not restore the original directory" }
                relocationJournal.clear()
            } catch (rollbackError: Exception) { error.addSuppressed(rollbackError) }
            throw error
        }
        // A failed journal deletion must not undo a registry update that is already durable.
        clearRelocationOwner(intent)
        relocationJournal.clear()
    }

    private fun validateRelocationTarget(id: String, source: File, target: File, state: RegistryState) {
        val records = state.locations
        allowedRootFor(target)
        val normalizedTarget = normalized(target)
        require(allowedRoots.none { normalized(it).canonicalFile == normalizedTarget.canonicalFile }) {
            "An installation root cannot be used as a relocation destination"
        }
        if (source.canonicalFile == normalizedTarget.canonicalFile) return
        requireNotRetired(normalizedTarget, state)
        require(!overlaps(source, normalizedTarget)) { "Source and destination cannot contain one another" }
        require(!exists(normalizedTarget)) { "The relocation destination already exists; existing files were preserved" }
        require(records[id]?.retainedDirectories.orEmpty().none { overlaps(normalizedTarget, it) }) {
            "The relocation destination overlaps a retained source; existing files were preserved"
        }
        require(records.all { (otherId, location) -> otherId == id ||
            (!overlaps(normalizedTarget, location.directory) && location.retainedDirectories.none { overlaps(normalizedTarget, it) }) }) {
            "The relocation destination overlaps another instance"
        }
        require(!ManagedFiles.isWithin(normalizedTarget, serversRoot) || normalizedTarget.parentFile?.canonicalFile == serversRoot.canonicalFile) {
            "Default installations must be direct children of the instance root"
        }
        val approvedRoot = allowedRootFor(normalizedTarget).canonicalFile
        var ancestor = normalizedTarget.parentFile
        while (ancestor != null && ancestor.canonicalFile != approvedRoot) {
            require(!exists(File(ancestor, "server.js"))) { "Relocation cannot target an existing source tree" }
            ancestor = ancestor.parentFile
        }
    }

    fun resolve(instanceId: String, requestedPath: String? = null, installPathMode: String = "exact", displayName: String? = null): File =
        synchronized(lock) {
            val id = normalizeInstanceId(instanceId)
            require(installPathMode in setOf("exact", "root")) { "Unknown installation path mode" }
            val state = read()
            val records = state.locations
            val recorded = records[id]
            val clean = requestedPath?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()
            val requested = clean?.takeIf { it.isNotBlank() }?.let {
                require(it.none { char -> char.code < 32 } && !it.contains("://") && !it.startsWith("file:")) {
                    "Installation destinations must be native application-private paths, not document URIs"
                }
                val path = File(it)
                require(path.isAbsolute) { "Installation destinations must be absolute paths" }
                if (installPathMode == "root") {
                    requireRoot(path)
                    if (recorded != null && normalized(path).canonicalFile == recorded.directory.parentFile?.canonicalFile) {
                        recorded.directory
                    } else chooseNamedTarget(path, id, displayName, state)
                } else {
                    require(allowedRoots.none { r -> normalized(r) == normalized(path) }) {
                        "An installation root cannot be used as an exact instance directory"
                    }
                    path
                }
            }
            if (recorded != null) {
                validateIdentity(recorded)
                require(requested == null || requested.canonicalFile == recorded.directory.canonicalFile) {
                    "The registered instance location cannot be changed by launch parameters"
                }
                if (requested != null) allowedRootFor(requested)
                return@synchronized recorded.directory
            }
            val rawTarget = requested ?: run {
                val existingDefault = File(serversRoot, id)
                val legacy = legacyServersRoot?.let { File(it, id) }
                when {
                    exists(existingDefault) -> existingDefault
                    legacy != null && exists(legacy) -> legacy
                    else -> chooseNamedTarget(serversRoot, id, displayName, state)
                }
            }
            allowedRootFor(rawTarget)
            val target = normalized(rawTarget)
            validateNewTarget(id, target, state)
            target
        }

    fun allowedRootFor(directory: File): File {
        require(directory.isAbsolute) {
            "Installation destinations must be absolute paths"
        }
        require(directory.toPath().none { it.toString() == ".." }) {
            "Installation paths cannot contain parent traversal"
        }
        var curr: File? = directory
        while (curr != null) {
            if (exists(curr)) {
                require(curr.isDirectory) { "Installation path contains a non-directory component" }
            }
            curr = curr.parentFile
        }
        val root = allowedRoots.sortedByDescending { normalized(it).path.length }.firstOrNull { r ->
            normalized(directory) == normalized(r) || ManagedFiles.isWithin(directory, r)
        } ?: throw IllegalArgumentException("Installation destination must be within an approved root without linked components")
        return root
    }

    fun isRegistered(directory: File): Boolean = synchronized(lock) {
        val target = normalized(directory).canonicalPath
        read().locations.values.any { it.directory.canonicalPath == target }
    }

    fun registerCommitted(instanceId: String, directory: File) = synchronized(lock) {
        val id = normalizeInstanceId(instanceId)
        val records = read()
        allowedRootFor(directory)
        register(id, normalized(directory), records)
    }

    /** The publish and registration share one lock across all RuntimePaths objects. */
    internal fun commitNewInstallation(
        instanceId: String, directory: File, publish: () -> Unit, rollback: () -> Unit
    ) = synchronized(lock) {
        val id = normalizeInstanceId(instanceId)
        allowedRootFor(directory)
        val target = normalized(directory)
        val records = read()
        // A registration whose directory has disappeared (manual deletion or a
        // lost volume) is a ghost: importing under the same id must be allowed
        // again instead of failing with "cannot be replaced".
        pruneDisappeared(records)
        require(id !in records.locations) { "An existing registered instance cannot be replaced" }
        validateNewTarget(id, target, records)
        publish()
        var publishedState: BasicFileAttributes? = null
        var publishedIdentity: Location? = null
        try {
            val state = attributes(target)
            publishedState = state
            val key = ensureDirectoryKey(target)
            publishedIdentity = Location(target, key, state.creationTime().toMillis())
            register(id, target, records)
        } catch (error: Exception) {
            try {
                require(exists(target)) { "Published installation disappeared; no rollback was attempted" }
                if (publishedIdentity != null) {
                    validateIdentity(publishedIdentity)
                } else {
                    // Marker creation can fail after publication, including on a full disk.
                    // The installer rollback also verifies its independent ownership token.
                    val expected = requireNotNull(publishedState) {
                        "Published installation identity could not be verified; files were preserved"
                    }
                    allowedRootFor(target)
                    val actual = attributes(target)
                    require(expected.fileKey()?.let { it == actual.fileKey() }
                        ?: (expected.creationTime() == actual.creationTime())) {
                        "Published installation changed before rollback; files were preserved"
                    }
                }
                rollback()
            } catch (rollbackError: Exception) { error.addSuppressed(rollbackError) }
            throw error
        }
    }

    fun unregisterAfterDelete(instanceId: String, directory: File) = synchronized(lock) {
        val id = normalizeInstanceId(instanceId)
        val state = read()
        val records = state.locations
        val target = normalized(directory)
        allowedRootFor(target)
        val recorded = records[id]
        require(recorded == null || recorded.directory.canonicalFile == target.canonicalFile) {
            "The removed directory does not match the registered instance"
        }
        require(!exists(target)) { "The instance directory must be fully removed before unregistering it" }
        state.retiredSources.addAll(recorded?.retainedDirectories.orEmpty())
        records.remove(id)
        write(state)
    }

    /**
     * Registrations whose directory vanished (manual deletion or a lost volume)
     * are ghosts: they can neither launch nor be replaced. Drop them so the same
     * identity can be imported again. Retained copies that still exist on disk
     * keep their retired-source protection, mirroring [unregisterAfterDelete];
     * vanished retired sources no longer block any path and are dropped too.
     */
    private fun pruneDisappeared(state: RegistryState) {
        val iterator = state.locations.entries.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val location = iterator.next().value
            // An unavailable root means an unmounted volume, not a ghost: keep
            // the registration so it returns when the volume does.
            if (exists(location.directory) || !rootIsAvailable(location.directory)) continue
            iterator.remove()
            changed = true
            for (retained in location.retainedDirectories) {
                if (!exists(retained) || !rootIsAvailable(retained)) continue
                // Retiring a path another registration already claims would
                // violate the registry's pairwise non-overlap invariant.
                if (state.locations.values.any { other ->
                        overlaps(retained, other.directory) ||
                            other.retainedDirectories.any { overlaps(retained, it) }
                    }) continue
                if (state.retiredSources.any { overlaps(retained, it) }) continue
                state.retiredSources.add(retained)
            }
        }
        changed = state.retiredSources.removeAll { !exists(it) && rootIsAvailable(it) } || changed
        if (changed) write(state)
    }

    /** False when the approved root hosting this path is unmounted or no longer configured. */
    private fun rootIsAvailable(directory: File): Boolean = try {
        exists(allowedRootFor(directory))
    } catch (error: IllegalArgumentException) {
        false
    }

    private fun register(id: String, directory: File, state: RegistryState) {
        val records = state.locations
        allowedRootFor(directory)
        requireCorePathsSafe(directory)
        require(allowedRoots.none { normalized(it).canonicalFile == directory.canonicalFile }) {
            "An installation root cannot be registered as an instance"
        }
        val server = File(directory, "server.js")
        val dependencies = File(directory, "node_modules")
        val hasLocalModules = dependencies.isDirectory && ManagedFiles.isWithin(dependencies, directory)
        require(server.isFile && ManagedFiles.isWithin(server, directory) &&
            (hasLocalModules || dependenciesComplete(directory)) &&
            !exists(File(directory, InstanceInstaller.DEPENDENCY_MARKER))) {
            "Only a complete source and dependency installation can be registered"
        }
        val existing = records[id]
        if (existing != null) {
            require(existing.directory.canonicalFile == directory.canonicalFile) {
                "The registered instance location cannot be changed"
            }
            validateIdentity(existing)
            return
        }
        validateOwnership(id, directory, state)
        val directoryState = attributes(directory)
        val key = ensureDirectoryKey(directory)
        records[id] = Location(directory, key, directoryState.creationTime().toMillis())
        write(state)
    }

    private fun validateNewTarget(id: String, directory: File, records: RegistryState) {
        allowedRootFor(directory)
        requireCorePathsSafe(directory)
        validateOwnership(id, directory, records)
        val isDefaultOrLegacy = directory.canonicalFile == File(serversRoot, id).canonicalFile ||
            (legacyServersRoot != null && directory.canonicalFile == File(legacyServersRoot, id).canonicalFile)
        if (!isDefaultOrLegacy) {
            val isMatchingExistingInstance = directory.name == id &&
                File(directory, "server.js").isFile && File(directory, "package.json").isFile
            if (!isMatchingExistingInstance) {
                require(!exists(directory) || (directory.isDirectory && directory.listFiles()?.isEmpty() == true)) {
                    "Custom installation destinations must be new or empty; existing files were preserved"
                }
            }
        }
    }

    private fun validateOwnership(id: String, directory: File, state: RegistryState) {
        val records = state.locations
        requireNotRetired(directory, state)
        val default = File(serversRoot, id)
        val legacy = legacyServersRoot?.let { File(it, id) }
        require(!ManagedFiles.isWithin(directory, serversRoot) || directory.parentFile?.canonicalFile == serversRoot.canonicalFile) {
            "Default installations must be direct children of the instance root"
        }
        if (legacy != null && ManagedFiles.isWithin(directory, legacyServersRoot)) {
            require(directory.canonicalFile == legacy.canonicalFile) {
                "A default installation directory belongs to its matching immutable instance ID"
            }
        }
        require(!exists(default) || directory.canonicalFile == default.canonicalFile ||
            (legacy != null && directory.canonicalFile == legacy.canonicalFile)) {
            "An existing default instance cannot be relocated by launch parameters"
        }
        require(records.all { (otherId, location) ->
            otherId == id || (!overlaps(directory, location.directory) && location.retainedDirectories.none { overlaps(directory, it) })
        }) { "Installation locations cannot overlap another registered instance" }
        var ancestor = directory.parentFile
        val approvedRoot = allowedRootFor(directory).canonicalFile
        while (ancestor != null && ancestor.canonicalFile != approvedRoot) {
            require(!exists(File(ancestor, "server.js"))) {
                "Installation destinations cannot be nested inside an existing source tree"
            }
            ancestor = ancestor.parentFile
        }
    }

    private fun validateIdentity(location: Location) {
        allowedRootFor(location.directory)
        if (!exists(location.directory)) return
        require(location.directory.isDirectory) {
            "The registered installation directory is not a directory"
        }
        require(!Files.isSymbolicLink(location.directory.toPath())) {
            "The registered installation directory cannot be a symbolic link"
        }
        requireCorePathsSafe(location.directory)
        if (location.fileKey != null) {
            require(directoryKey(location.directory, location.fileKey) == location.fileKey) {
                "The registered installation directory identity changed; existing files were preserved"
            }
        }
    }

    private fun ensureDirectoryKey(directory: File): String? {
        val identityFile = File(directory, ".sc-identity")
        if (exists(identityFile)) {
            return "marker:${readDirectoryMarker(identityFile)}"
        }
        val newKey = UUID.randomUUID().toString()
        FileChannel.open(identityFile.toPath(), StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
            val bytes = ByteBuffer.wrap(newKey.toByteArray(Charsets.US_ASCII))
            while (bytes.hasRemaining()) it.write(bytes)
            it.force(true)
        }
        return "marker:$newKey"
    }

    private fun directoryKey(directory: File, expected: String): String? {
        val identityFile = File(directory, ".sc-identity")
        if (expected.startsWith("marker:")) {
            return if (exists(identityFile)) "marker:${readDirectoryMarker(identityFile)}" else null
        }
        val state = attributes(directory)
        val jvmKey = state.fileKey()?.toString()
        if (jvmKey != null) return jvmKey
        return if (exists(identityFile)) readDirectoryMarker(identityFile) else null
    }

    private fun readDirectoryMarker(file: File): String {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == 36L) {
            "Installation directory identity marker is invalid"
        }
        val value = Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
            val bytes = ByteArray(37)
            var count = 0
            while (count < bytes.size) {
                val read = it.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            require(count == 36) { "Installation directory identity marker is invalid" }
            String(bytes, 0, count, Charsets.US_ASCII)
        }
        require(UUID.fromString(value).toString() == value) { "Installation directory identity marker is invalid" }
        return value
    }

    private fun requireCorePathsSafe(directory: File) {
        for (name in listOf("server.js", "package.json", "config.yaml", "node_modules", ".sc-identity", InstanceInstaller.DEPENDENCY_MARKER)) {
            val file = File(directory, name)
            require(!exists(file) || ManagedFiles.isWithin(file, directory)) {
                "Linked instance source, configuration, or dependencies were preserved"
            }
        }
    }

    private fun requireRoot(directory: File) {
        require(directory.isAbsolute) { "Installation roots must be absolute paths" }
        require(!exists(directory) || directory.isDirectory) { "Installation root is not a directory" }
        allowedRootFor(File(directory, ".installation-validation"))
    }

    private fun read(): RegistryState {
        val records = readRaw()
        relocationJournal.read()?.let { recoverRelocation(it, records) }
        return records
    }

    private fun recoverRelocation(intent: RelocationJournal.Entry, state: RegistryState) {
        val records = state.locations
        val current = requireNotNull(records[intent.instanceId]) { "Interrupted relocation registration disappeared; files were preserved" }
        require(current.fileKey == intent.fileKey && current.createdAt == intent.createdAt &&
            current.directory in listOf(intent.source, intent.target)) { "Interrupted relocation registration changed; files were preserved" }
        val allPaths = listOf(intent.source, intent.target) + intent.retainedPaths
        allPaths.forEach { path ->
            allowedRootFor(path)
            requireNotRetired(path, state)
            require(allowedRoots.none { normalized(it).canonicalFile == path.canonicalFile }) { "Relocation journal targets an installation root" }
            require(!ManagedFiles.isWithin(path, serversRoot) || path.parentFile?.canonicalFile == serversRoot.canonicalFile) {
                "Relocation journal contains a nested default installation"
            }
        }
        allPaths.forEachIndexed { index, path ->
            require(allPaths.drop(index + 1).none { overlaps(path, it) }) { "Relocation journal paths overlap; files were preserved" }
            require(records.all { (id, location) -> id == intent.instanceId ||
                (listOf(location.directory) + location.retainedDirectories).none { overlaps(path, it) } }) {
                "Relocation journal overlaps another instance; files were preserved"
            }
        }
        val original = Location(intent.source, intent.fileKey, intent.createdAt, intent.retainedPaths)
        val retained = intent.retainedPaths + if (intent.retainSource) listOf(intent.source) else emptyList()
        require(retained.size <= 32) { "Too many retained relocation sources" }
        if (current.directory == intent.source) {
            require(current.retainedDirectories == intent.retainedPaths) { "Interrupted relocation history changed; files were preserved" }
            if (!exists(intent.target)) {
                validateRelocated(original)
                relocationJournal.clear()
                return
            }
        } else {
            require(current.retainedDirectories == retained) { "Completed relocation history changed; files were preserved" }
        }
        if (intent.retainSource) validateRelocated(original)
        else require(!exists(intent.source)) { "Interrupted relocation has two possible source directories; files were preserved" }
        val relocated = Location(intent.target, intent.fileKey, intent.createdAt, retained)
        validateRelocated(relocated)
        requireRelocationOwner(intent)
        if (current.directory != intent.target) {
            records[intent.instanceId] = relocated
            write(state)
        }
        clearRelocationOwner(intent)
        relocationJournal.clear()
    }

    private fun requireRelocationOwner(intent: RelocationJournal.Entry) {
        intent.stagingOwner?.let { owner ->
            val marker = File(intent.target, InstanceRelocation.OWNER_MARKER)
            if (exists(marker)) {
                require(ManagedFiles.isWithin(marker, intent.target) && readDirectoryMarker(marker) == owner) {
                    "Relocation staging ownership changed; files were preserved"
                }
            }
        }
    }

    private fun clearRelocationOwner(intent: RelocationJournal.Entry) {
        requireRelocationOwner(intent)
        if (intent.stagingOwner != null) Files.deleteIfExists(File(intent.target, InstanceRelocation.OWNER_MARKER).toPath())
    }

    private fun validateRelocated(location: Location) {
        validateIdentity(location)
        require(File(location.directory, "server.js").isFile && File(location.directory, "package.json").isFile &&
            (File(location.directory, "node_modules").isDirectory || dependenciesComplete(location.directory)) &&
            !exists(File(location.directory, InstanceInstaller.DEPENDENCY_MARKER))) {
            "The relocated instance is incomplete; files were preserved"
        }
    }

    private fun readRaw(): RegistryState {
        require(registryFile.isAbsolute) { "Unsafe installation registry path" }
        if (!exists(registryFile)) return RegistryState()
        require(registryFile.isFile && registryFile.length() in 1..MAX_BYTES) {
            "Installation registry is not a bounded regular file"
        }
        val output = ByteArrayOutputStream()
        Files.newInputStream(registryFile.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(8192)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                require(output.size().toLong() + count <= MAX_BYTES) { "Installation registry exceeds its size limit" }
                output.write(buffer, 0, count)
            }
        }
        val document = try { JSONObject(output.toString(Charsets.UTF_8.name())) }
        catch (error: Exception) { throw IllegalStateException("Installation registry is corrupted; no fallback was used", error) }
        require(document.opt("revision") == 1 && document.opt("owner") == "sillyclient") {
            "Installation registry ownership or revision is invalid; no fallback was used"
        }
        val locations = document.getJSONArray("locations")
        require(locations.length() <= MAX_LOCATIONS) { "Installation registry exceeds its entry limit" }
        val records = linkedMapOf<String, Location>()
        for (index in 0 until locations.length()) {
            val record = locations.getJSONObject(index)
            val id = record.getString("instanceId")
            require(id == normalizeInstanceId(id) && id !in records) { "Invalid or duplicate registered instance identity" }
            val path = record.getString("path")
            val directory = normalized(File(path))
            require(File(path).isAbsolute && path == directory.path) { "Invalid registered installation path" }
            allowedRootFor(directory)
            val key = record.get("fileKey")
            require(key == JSONObject.NULL || key is String) { "Invalid registered installation directory identity" }
            val createdAt = record.get("createdAt")
            require((createdAt is Long || createdAt is Int) && (createdAt as Number).toLong() >= 0) {
                "Invalid registered installation creation time"
            }
            require(!ManagedFiles.isWithin(directory, serversRoot) ||
                directory.parentFile?.canonicalFile == serversRoot.canonicalFile) { "Nested default installation directory" }
            require(!record.has("retainedPaths") || record.get("retainedPaths") is JSONArray) { "Invalid retained relocation paths" }
            val retained = record.optJSONArray("retainedPaths")?.let { paths ->
                readRetainedPaths(paths, 32)
            }.orEmpty()
            records[id] = Location(directory, if (key == JSONObject.NULL) null else key as String,
                (createdAt as Number).toLong(), retained)
        }
        require(!document.has("retiredSources") || document.get("retiredSources") is JSONArray) { "Invalid retired relocation sources" }
        val retired = document.optJSONArray("retiredSources")?.let { readRetainedPaths(it, MAX_RETAINED_PATHS) }.orEmpty()
        val state = RegistryState(records, retired.toMutableSet())
        require(state.retainedCount() <= MAX_RETAINED_PATHS) { "Too many retained relocation sources" }
        val allPaths = (records.values.flatMap { listOf(it.directory) + it.retainedDirectories } + retired)
            .map { it.canonicalFile.toPath() }
        allPaths.forEachIndexed { index, path ->
            for (other in index + 1 until allPaths.size) {
                require(!path.startsWith(allPaths[other]) && !allPaths[other].startsWith(path)) {
                    "Installation registry locations or retained sources overlap"
                }
            }
        }
        return state
    }

    private fun readRetainedPaths(paths: JSONArray, maximum: Int): List<File> {
        require(paths.length() <= maximum) { "Too many retained relocation sources" }
        val result = (0 until paths.length()).map { index ->
            val value = paths.getString(index)
            val path = normalized(File(value))
            require(File(value).isAbsolute && value == path.path && value.none { it.code < 32 }) {
                "Invalid retained relocation source"
            }
            allowedRootFor(path)
            require(allowedRoots.none { normalized(it).canonicalFile == path.canonicalFile }) {
                "An installation root cannot be a retained source"
            }
            path
        }
        require(result.distinct().size == result.size) { "Duplicate retained relocation sources" }
        return result
    }

    private fun requireNotRetired(directory: File, state: RegistryState) {
        require(state.retiredSources.none { overlaps(directory, it) }) {
            "The destination overlaps a retired relocation source; existing files were preserved"
        }
    }

    private fun write(state: RegistryState) {
        val records = state.locations
        require(records.size <= MAX_LOCATIONS) { "Installation registry exceeds its entry limit" }
        require(state.retainedCount() <= MAX_RETAINED_PATHS) { "Too many retained relocation sources" }
        val locations = JSONArray()
        records.forEach { (id, location) ->
            locations.put(JSONObject().put("instanceId", id).put("path", location.directory.path)
                .put("fileKey", location.fileKey ?: JSONObject.NULL).put("createdAt", location.createdAt)
                .put("retainedPaths", JSONArray(location.retainedDirectories.map { it.path })))
        }
        val bytes = JSONObject().put("revision", 1).put("owner", "sillyclient")
            .put("locations", locations).put("retiredSources", JSONArray(state.retiredSources.map { it.path }))
            .toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Installation registry exceeds its size limit" }
        val parent = registryFile.parentFile
        require(parent != null) { "Unsafe installation registry destination" }
        check((parent.isDirectory || parent.mkdirs()) && parent.isDirectory) { "Could not create installation registry parent" }
        val temp = File(parent, ".install-locations-${UUID.randomUUID()}.tmp")
        try {
            require(temp.createNewFile()) { "Could not stage installation registry" }
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temp.toPath(), registryFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (exists(temp)) temp.delete()
        }
    }

    private fun attributes(directory: File): BasicFileAttributes {
        val state = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(state.isDirectory && !state.isSymbolicLink) { "Installation location is not a safe directory" }
        return state
    }

    private fun overlaps(first: File, second: File): Boolean {
        val a = first.canonicalFile.toPath()
        val b = second.canonicalFile.toPath()
        return a.startsWith(b) || b.startsWith(a)
    }

    private fun normalized(file: File): File = file.absoluteFile.toPath().normalize().toFile()
    private fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun chooseNamedTarget(root: File, id: String, displayName: String?, state: RegistryState): File {
        val records = state.locations
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() }?.let(::normalizeInstanceId) ?: id
        val preferred = File(root, name)
        if (!exists(preferred) && state.retiredSources.none { overlaps(preferred, it) } && records.values.none {
            overlaps(preferred, it.directory) || it.retainedDirectories.any { retained -> overlaps(preferred, retained) }
        }) return preferred
        if (name == id && records[id]?.directory?.canonicalFile == preferred.canonicalFile) return preferred
        val suffix = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }
        val collisionSafe = File(root, "$name-$suffix")
        require(!exists(collisionSafe) && state.retiredSources.none { overlaps(collisionSafe, it) } && records.values.none {
            overlaps(collisionSafe, it.directory) || it.retainedDirectories.any { retained -> overlaps(collisionSafe, retained) }
        }) {
            "The installation name and its instance-specific fallback are already in use"
        }
        return collisionSafe
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private const val MAX_BYTES = 1024L * 1024
        private const val MAX_LOCATIONS = 512
        private const val MAX_RETAINED_PATHS = 4096

        fun normalizeInstanceId(instanceId: String): String = instanceId.trim()
            .replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "-").trim('.', ' ', '-').take(100).ifBlank { "default" }
    }
}
