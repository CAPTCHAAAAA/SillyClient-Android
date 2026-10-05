package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Source transactions never replace a nonempty existing instance. */
class InstanceInstaller(
    private val serversRoot: File,
    private val installLocations: InstallLocationRegistry? = null,
    private val dependenciesComplete: (File) -> Boolean = { File(it, "node_modules").isDirectory },
    private val removeStaging: (File, File, File, () -> Unit) -> Boolean = { directory, root, _, verify ->
        verify()
        ManagedFiles.deleteDirectory(directory, root)
    }
) {
    fun prepare(
        target: File,
        ensureActive: () -> Unit,
        extract: (File) -> Boolean,
        installDependencies: (File) -> Boolean,
        commit: (() -> Unit) -> Unit,
        instanceId: String? = null
    ) {
        ensureActive()
        val id = instanceId ?: target.name
        if (installLocations != null) {
            require(installLocations.resolve(id, target.absolutePath).canonicalFile == target.canonicalFile) {
                "Installation target does not match the instance location"
            }
        } else require(ManagedFiles.isWithin(target, serversRoot)) { "Instance is outside the managed scope" }
        require(ManagedFiles.isWithin(File(target, "server.js"), target)) { "Linked instance source was preserved" }
        if (File(target, "server.js").isFile) {
            prepareDependencies(target, ensureActive, installDependencies, commit)
            ensureActive()
            commit { installLocations?.registerCommitted(id, target) }
            return
        }
        requireEmptyOrMissing(target)
        val originalTarget = if (exists(target)) attributes(target) else null
        val parent = requireNotNull(target.absoluteFile.parentFile) { "Instance has no parent directory" }
        StagingCleanup.sweepOrphans(parent, STAGING_PREFIX, InstanceRelocation.STAGING_PREFIX)
        check(parent.isDirectory || parent.mkdirs()) { "Could not create the installation parent directory" }
        val root = installLocations?.allowedRootFor(target) ?: serversRoot
        require(ManagedFiles.isWithin(target, root)) { "Installation parent changed; existing files were preserved" }
        // A sibling is on the destination filesystem; publication never copies node_modules.
        val staging = Files.createTempDirectory(parent.toPath(), STAGING_PREFIX).toFile()
        val identity = attributes(staging)
        val owner = UUID.randomUUID().toString()
        val markerName = ".sillyclient-install-$owner"
        try {
            Files.newOutputStream(File(staging, markerName).toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
                it.write(owner.toByteArray(Charsets.US_ASCII))
            }
        } catch (error: Exception) {
            try {
                if (sameDirectory(staging, identity)) Files.delete(staging.toPath())
            } catch (cleanupError: Exception) { error.addSuppressed(cleanupError) }
            throw error
        }
        fun requireOwned(directory: File) {
            require(ManagedFiles.isWithin(directory, root) &&
                (identity.fileKey() == null || sameDirectory(directory, identity))) {
                "Installation directory changed; its files were preserved"
            }
            val marker = File(directory, markerName)
            require(ManagedFiles.isWithin(marker, directory) && marker.isFile && marker.length() == owner.length.toLong() &&
                marker.readText() == owner) { "Installation ownership changed; its files were preserved" }
        }
        var committed = false
        var failure: Throwable? = null
        try {
            check(extract(staging) && File(staging, "server.js").isFile &&
                ManagedFiles.isWithin(File(staging, "server.js"), staging)) { "Source extraction failed" }
            requireOwned(staging)
            ensureActive()
            prepareDependencies(staging, ensureActive, installDependencies, commit)
            ensureActive()
            commit {
                val publish = {
                    requireOwned(staging)
                    requireEmptyOrMissing(target)
                    if (originalTarget != null) {
                        require(sameDirectory(target, originalTarget)) { "Installation target changed; existing files were preserved" }
                        Files.delete(target.toPath())
                    } else require(!exists(target)) { "Installation target appeared; existing files were preserved" }
                    try {
                        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } catch (error: Exception) {
                        if (originalTarget != null && !exists(target)) {
                            try { Files.createDirectory(target.toPath()) }
                            catch (restoreError: Exception) { error.addSuppressed(restoreError) }
                        }
                        throw error
                    }
                    Unit
                }
                if (installLocations != null) {
                    installLocations.commitNewInstallation(id, target, publish) {
                        requireOwned(target)
                        require(!exists(staging)) { "Installation rollback destination changed; files were preserved" }
                        Files.move(target.toPath(), staging.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        if (originalTarget != null) Files.createDirectory(target.toPath())
                    }
                } else publish()
                committed = true
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                if (committed) {
                    requireOwned(target)
                    check(File(target, markerName).delete()) { "Installation completed but its temporary ownership marker could not be removed" }
                } else if (exists(staging)) {
                    requireOwned(staging)
                    check(removeStaging(staging, root, File(staging, markerName)) { requireOwned(staging) }) {
                        "Could not remove incomplete installation staging"
                    }
                }
            } catch (cleanupError: Exception) {
                if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }

    private fun prepareDependencies(
        directory: File,
        ensureActive: () -> Unit,
        installDependencies: (File) -> Boolean,
        commit: (() -> Unit) -> Unit
    ) {
        val dependencies = File(directory, "node_modules")
        val marker = File(directory, DEPENDENCY_MARKER)
        require(ManagedFiles.isWithin(dependencies, directory) && ManagedFiles.isWithin(marker, directory)) {
            "Dependency paths escape the instance; existing files were preserved"
        }
        if (dependenciesComplete(directory) && !exists(marker)) return
        fun ownedMarker(): Boolean = marker.isFile && marker.length() == MARKER_CONTENT.length.toLong() &&
            marker.readText() == MARKER_CONTENT
        ensureActive()
        commit {
            if (exists(marker)) require(ownedMarker()) { "Dependency state marker is not owned by the runtime" }
            else Files.newOutputStream(marker.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
                it.write(MARKER_CONTENT.toByteArray(Charsets.US_ASCII))
            }
        }
        // Failed or cancelled npm may leave a partial node_modules directory; retry while marked.
        check(installDependencies(directory) && ManagedFiles.isWithin(dependencies, directory) &&
            dependenciesComplete(directory)) {
            "Dependency installation failed; existing instance data was preserved"
        }
        ensureActive()
        commit {
            check(ownedMarker() && marker.delete()) { "Could not finish dependency installation state" }
        }
    }

    private fun requireEmptyOrMissing(directory: File) {
        require(!exists(directory) || (directory.isDirectory && directory.listFiles()?.isEmpty() == true)) {
            "Installation destination must be new or empty; existing files were preserved"
        }
    }

    private fun attributes(directory: File): BasicFileAttributes =
        Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
            require(it.isDirectory && !it.isSymbolicLink) { "Installation directory is not a safe directory" }
        }

    private fun sameDirectory(directory: File, expected: BasicFileAttributes): Boolean {
        if (!exists(directory) || !ManagedFiles.isUnlinked(directory)) return false
        val actual = attributes(directory)
        return expected.fileKey()?.let { it == actual.fileKey() }
            ?: (expected.creationTime() == actual.creationTime())
    }

    private fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    companion object {
        internal const val STAGING_PREFIX = ".sillyclient-install-"
        internal const val DEPENDENCY_MARKER = ".sillyclient-dependencies-pending"
        private const val MARKER_CONTENT = "sillyclient-dependencies-v1\n"
    }
}
