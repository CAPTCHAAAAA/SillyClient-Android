package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

class InstanceRelocation(
    private val paths: RuntimePaths,
    private val operations: OperationCoordinator,
    private val processes: ProcessSupervisor,
    private val onProgress: (String) -> Unit = {},
    private val copyVerified: (File, File, OperationCoordinator.Operation) -> Unit =
        { source, target, operation ->
            NativeInstanceTransfer(paths, operations, processes, onProgress,
                sharedModules = { InstanceRelocation.sharedModulesPathFor(paths, it) })
                .copyVerified(source, target, operation,
                    skipNodeModules = InstanceRelocation.dependenciesCoveredByTree(paths, source))
        },
    private val sameFilesystem: (File, File) -> Boolean = ::onSameFilesystem,
    private val validateSource: (File, OperationCoordinator.Operation) -> Unit =
        { source, operation ->
            NativeInstanceTransfer(paths, operations, processes, onProgress,
                sharedModules = { InstanceRelocation.sharedModulesPathFor(paths, it) })
                .validatePortableSource(source, operation)
        },
    private val removeStaging: (File, File, String, () -> Unit) -> Unit = { target, root, id, verify ->
        InstanceRemoval.remove(target, root, verify, ensureActive = {},
            removeChildren = { children -> NativeTreeRemoval(processes, identityFileName = OWNER_MARKER)
                .remove(children, target, id) },
            commit = { it() }, unregister = {}, identityFileName = OWNER_MARKER)
    }
) {
    data class Result(val success: Boolean, val instanceId: String, val oldPath: String, val newPath: String,
        val unchanged: Boolean = false, val retainedSourcePath: String? = null)
    data class LegacyInstance(val instanceId: String, val name: String, val currentPath: String,
        val targetPath: String, val version: String)

    fun legacyInstances(): List<LegacyInstance> {
        val repository = InstanceRepository(paths.serversDir, installLocations = paths.installLocations,
            legacyServersRoot = paths.legacyServersDir)
        return repository.scan().filter { metadata ->
            metadata.hasServer && ManagedFiles.isWithin(File(metadata.path), paths.tarvenHome) &&
                !ManagedFiles.isWithin(File(metadata.path), paths.serversDir)
        }.map { metadata ->
            val source = File(metadata.path)
            LegacyInstance(metadata.instanceId, source.name, source.path,
                paths.installLocations.defaultRelocationTarget(metadata.instanceId, source).path, metadata.version)
        }
    }

    fun relocate(instanceId: String, targetPath: String? = null, installPath: String? = null,
        displayName: String? = null, operation: OperationCoordinator.Operation): Result {
        val id = RuntimePaths.normalizeInstanceId(instanceId)
        require(operation.instanceId == id) { "Relocation operation belongs to another instance" }
        operations.ensureCurrent(operation)
        val plan = paths.installLocations.planRelocation(id, targetPath, installPath, displayName)
        if (plan.source == plan.target) return Result(true, id, plan.source.path, plan.target.path, unchanged = true)
        validateSource(plan.source, operation)
        operations.ensureCurrent(operation)
        val parent = requireNotNull(plan.target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Could not create the relocation destination parent" }
        val sourceIdentity = attributes(plan.source)
        if (sameFilesystem(plan.source, parent)) {
            operations.commit(operation) {
                paths.installLocations.commitRelocation(plan, retainedSource = false, publish = {
                    Files.move(plan.source.toPath(), plan.target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }, rollback = {
                    requireSameDirectory(plan.target, sourceIdentity)
                    require(!exists(plan.source)) { "The original location changed; relocated files were preserved" }
                    Files.move(plan.target.toPath(), plan.source.toPath(), StandardCopyOption.ATOMIC_MOVE)
                })
            }
            return Result(true, id, plan.source.path, plan.target.path)
        }

        val approvedRoot = paths.installLocations.allowedRootFor(plan.target)
        StagingCleanup.sweepOrphans(parent, STAGING_PREFIX, InstanceInstaller.STAGING_PREFIX)
        val staging = Files.createTempDirectory(parent.toPath(), STAGING_PREFIX).toFile()
        val stagingIdentity = attributes(staging)
        val owner = UUID.randomUUID().toString()
        val ownerFile = File(staging, OWNER_MARKER)
        try { ownerFile.writeText(owner) }
        catch (error: Exception) {
            try {
                requireSameDirectory(staging, stagingIdentity)
                Files.deleteIfExists(ownerFile.toPath())
                Files.delete(staging.toPath())
            } catch (cleanupError: Exception) { error.addSuppressed(cleanupError) }
            throw error
        }
        fun requireOwned(directory: File) {
            requireSameDirectory(directory, stagingIdentity)
            val marker = File(directory, OWNER_MARKER)
            require(ManagedFiles.isWithin(marker, directory) && marker.isFile && marker.length() == 36L && marker.readText() == owner) {
                "Relocation staging ownership changed; files were preserved"
            }
        }
        var committed = false
        var failure: Throwable? = null
        try {
            copyVerified(plan.source, staging, operation)
            operations.ensureCurrent(operation)
            requireSameDirectory(plan.source, sourceIdentity)
            requireOwned(staging)
            operations.commit(operation) {
                paths.installLocations.commitRelocation(plan, retainedSource = true, publish = {
                    Files.move(staging.toPath(), plan.target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }, rollback = {
                    requireOwned(plan.target)
                    require(!exists(staging)) { "Relocation rollback destination changed; files were preserved" }
                    Files.move(plan.target.toPath(), staging.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }, stagingOwner = owner)
                committed = true
            }
            // The verified copy is registered and committed; the source directory is
            // now dead weight on the old volume. Remove it natively in the background
            // so the copy never saturates FUSE while the foreground moves on.
            retireSourceInBackground(plan)
            return Result(true, id, plan.source.path, plan.target.path, retainedSourcePath = plan.source.path)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!committed && exists(staging)) {
                try {
                    check(!processes.hasProcesses(id)) { "An owned process is still active; relocation staging was preserved" }
                    requireOwned(staging)
                    removeStaging(staging, approvedRoot, id) { requireOwned(staging) }
                } catch (cleanupError: Exception) {
                    if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
                }
            }
        }
    }

    private fun attributes(directory: File): BasicFileAttributes = Files.readAttributes(directory.toPath(),
        BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
        require(it.isDirectory && !it.isSymbolicLink) { "Instance location is not a plain directory" }
    }

    private fun retireSourceInBackground(plan: InstallLocationRegistry.RelocationPlan) {
        Thread {
            runCatching {
                NativeTreeRemoval(processes).remove(listOf(plan.source), requireNotNull(plan.source.parentFile), plan.instanceId)
            }.onSuccess {
                if (!exists(plan.source)) paths.installLocations.unretainPath(plan.instanceId, plan.source)
            }
        }.apply {
            name = "SC-relocation-retire"
            isDaemon = true
        }.start()
    }

    /**
     * Clean retained and retired source directories whose owning instance is
     * already registered and complete elsewhere. Survives crashes between a
     * committed relocation and its background source removal.
     */
    fun sweepRetainedSources(onDiagnostic: (String) -> Unit = {}) {
        val owners = paths.installLocations.entries()
        for ((id, directory) in owners) {
            if (!File(directory, "server.js").isFile) continue
            if (!(File(directory, "node_modules").isDirectory || dependenciesCoveredByTree(paths, directory))) continue
            for (source in paths.installLocations.retainedByInstance(id)) {
                if (!source.isDirectory) {
                    paths.installLocations.unretainPath(id, source)
                    continue
                }
                onDiagnostic("relocat.retire_source id=${id.take(24)} path=${source.path.take(120)}")
                runCatching {
                    NativeTreeRemoval(processes).remove(listOf(source), requireNotNull(source.parentFile), id)
                }.onSuccess {
                    if (!exists(source)) paths.installLocations.unretainPath(id, source)
                }.onFailure {
                    onDiagnostic("relocat.retire_source_failed id=${id.take(24)} ${it.message?.take(120)}")
                }
            }
        }
        for (source in paths.installLocations.retiredSources()) {
            if (!source.isDirectory) continue
            runCatching { NativeTreeRemoval(processes).remove(listOf(source), requireNotNull(source.parentFile), source.name) }
        }
    }

    private fun requireSameDirectory(directory: File, expected: BasicFileAttributes) {
        paths.installLocations.allowedRootFor(directory)
        val actual = attributes(directory)
        require(expected.fileKey()?.let { it == actual.fileKey() } ?: (expected.creationTime() == actual.creationTime())) {
            "Instance directory identity changed; files were preserved"
        }
    }

    private fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    companion object {
        internal const val STAGING_PREFIX = ".sillyclient-relocate-"
        internal const val OWNER_MARKER = ".sillyclient-relocation-owner"

        /** Shared-tree modules for an instance's lock, when the tree is materialized. */
        internal fun sharedModulesPathFor(paths: RuntimePaths, source: File): String? {
            val lockKey = DependencyArchive(File(paths.tarvenHome, "dependency-archives"))
                .lockKey(File(source, "package-lock.json")) ?: return null
            val modules = DependencyTrees(File(paths.tarvenHome, "dependency-trees")).modulesFor(lockKey)
            return modules.takeIf { it.isDirectory }?.absolutePath
        }

        /** Cross-volume copies skip node_modules whenever the shared tree covers the lock. */
        internal fun dependenciesCoveredByTree(paths: RuntimePaths, source: File): Boolean {
            val lockKey = DependencyArchive(File(paths.tarvenHome, "dependency-archives"))
                .lockKey(File(source, "package-lock.json")) ?: return false
            return DependencyTrees(File(paths.tarvenHome, "dependency-trees")).containsComplete(lockKey)
        }

        /**
         * st_dev cannot be trusted on the emulated-storage fuse layer: app-private
         * directories report the same device as the shared partition yet atomic
         * renames between them fail with EXDEV. Probing with an actual rename is
         * the only reliable way to decide between moving and copying.
         */
        private fun onSameFilesystem(source: File, parent: File): Boolean = try {
            val probe = Files.createTempFile(source.parentFile.toPath(), ".sc-fsprobe-", null)
            val target = File(parent, probe.fileName.toString() + ".probe")
            try {
                Files.move(probe, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                Files.deleteIfExists(target.toPath())
                true
            } catch (_: Exception) {
                Files.deleteIfExists(probe)
                Files.deleteIfExists(target.toPath())
                false
            }
        } catch (_: Exception) { false }
    }
}
