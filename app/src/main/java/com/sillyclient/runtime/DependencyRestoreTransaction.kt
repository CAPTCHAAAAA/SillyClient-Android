package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Publish verified dependencies on the instance filesystem without exposing a partial tree. */
internal class DependencyRestoreTransaction(
    private val removeStaging: (File, File, () -> Unit) -> Unit
) {
    fun recover(
        instance: File,
        key: String,
        validate: (File) -> Boolean,
        ensureActive: () -> Unit
    ): Boolean {
        val staging = File(instance, STAGING_NAME)
        if (!exists(staging)) return false
        // A kill between mkdir/marker creation or marker removal/rmdir leaves an
        // empty directory. Removing only an empty, unlinked directory is safe.
        if (ManagedFiles.isWithin(staging, instance) && staging.isDirectory && staging.list()?.isEmpty() == true) {
            ensureActive()
            Files.delete(staging.toPath())
            return false
        }
        val (storedKey, ready) = requireOwned(staging, instance)
        val verifyIdentity = identityVerifier(staging, instance)
        val modules = File(instance, "node_modules")
        val previous = File(staging, "previous")
        val prepared = File(staging, "node_modules")
        ensureActive()
        if (exists(previous)) {
            if (!exists(modules)) {
                move(previous, modules)
            } else {
                check(ready && !exists(prepared) && validate(modules)) {
                    "Dependency recovery is ambiguous; both copies were preserved"
                }
                removeStaging(staging, instance, verifyIdentity)
                return storedKey == key
            }
        }
        if (ready && storedKey == key) {
            if (exists(prepared)) {
                check(validate(prepared)) { "Prepared dependencies failed validation; existing files were preserved" }
                publish(staging, instance, ensureActive, verifyIdentity)
                return true
            }
            check(validate(modules)) { "Published dependencies failed validation; existing files were preserved" }
            removeStaging(staging, instance, verifyIdentity)
            return true
        }
        removeStaging(staging, instance, verifyIdentity)
        return false
    }

    fun restore(
        instance: File,
        key: String,
        replaceExisting: Boolean,
        validate: (File) -> Boolean,
        ensureActive: () -> Unit,
        extract: (File) -> Unit
    ): Boolean {
        val modules = File(instance, "node_modules")
        require(ManagedFiles.isWithin(modules, instance)) { "Linked dependencies were preserved" }
        if (exists(modules) && !replaceExisting && (!modules.isDirectory || modules.list()?.isNotEmpty() != false)) {
            return false
        }
        val staging = File(instance, STAGING_NAME)
        ensureActive()
        val initializing = Files.createTempDirectory(instance.toPath(), ".sillyclient-dependency-init-").toFile()
        try {
            Files.write(File(initializing, IDENTITY).toPath(), (PREFIX + key + "\nextracting").toByteArray(Charsets.US_ASCII),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            move(initializing, staging)
        } finally {
            if (exists(initializing)) {
                Files.deleteIfExists(File(initializing, IDENTITY).toPath())
                Files.delete(initializing.toPath())
            }
        }
        val verifyExtracting = identityVerifier(staging, instance)
        val prepared = File(staging, "node_modules")
        Files.createDirectory(prepared.toPath())
        extract(prepared)
        ensureActive()
        verifyExtracting()
        check(validate(prepared)) { "Archived dependencies do not match this instance" }
        verifyExtracting()
        val readyMarker = File(staging, ".identity-next")
        Files.write(readyMarker.toPath(), (PREFIX + key + "\nready").toByteArray(Charsets.US_ASCII),
            StandardOpenOption.CREATE_NEW)
        Files.move(readyMarker.toPath(), File(staging, IDENTITY).toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        publish(staging, instance, ensureActive, identityVerifier(staging, instance))
        return true
    }

    private fun publish(staging: File, instance: File, ensureActive: () -> Unit, verifyIdentity: () -> Unit) {
        verifyIdentity()
        val modules = File(instance, "node_modules")
        val previous = File(staging, "previous")
        val prepared = File(staging, "node_modules")
        require(ManagedFiles.isWithin(modules, instance) && ManagedFiles.isWithin(prepared, staging)) {
            "Dependency publication paths changed; existing files were preserved"
        }
        ensureActive()
        verifyIdentity()
        if (exists(modules)) move(modules, previous)
        try {
            ensureActive()
            move(prepared, modules)
        } catch (error: Exception) {
            if (!exists(modules) && exists(previous)) {
                try { move(previous, modules) } catch (restoreError: Exception) { error.addSuppressed(restoreError) }
            }
            throw error
        }
        removeStaging(staging, instance, verifyIdentity)
    }

    private fun requireOwned(staging: File, instance: File): Pair<String, Boolean> {
        val marker = File(staging, IDENTITY)
        require(staging.isDirectory && ManagedFiles.isWithin(staging, instance) &&
            ManagedFiles.isWithin(marker, staging) && marker.isFile && marker.length() in 1..200) {
            "Unknown dependency recovery directory was preserved"
        }
        val content = marker.readText(Charsets.US_ASCII)
        val fields = content.removePrefix(PREFIX).split('\n')
        require(content.startsWith(PREFIX) && fields.size == 2 &&
            fields[0].matches(Regex("[0-9a-f]{64}")) && fields[1] in setOf("extracting", "ready")) {
            "Unknown dependency recovery marker was preserved"
        }
        for (name in listOf("previous", "node_modules")) {
            require(ManagedFiles.isWithin(File(staging, name), staging)) { "Linked dependency recovery files were preserved" }
        }
        require(staging.list()?.all { it in setOf(IDENTITY, ".identity-next", "previous", "node_modules") } == true) {
            "Unknown files in dependency recovery directory were preserved"
        }
        return fields[0] to (fields[1] == "ready")
    }

    private fun identityVerifier(staging: File, instance: File): () -> Unit {
        val expectedState = requireOwned(staging, instance)
        val expected = Files.readAttributes(staging.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return {
            check(requireOwned(staging, instance) == expectedState) { "Dependency recovery identity changed" }
            val actual = Files.readAttributes(staging.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(expected.fileKey()?.let { it == actual.fileKey() } ?: (expected.creationTime() == actual.creationTime())) {
                "Dependency recovery directory changed; existing files were preserved"
            }
        }
    }

    private fun move(source: File, target: File) {
        check(!exists(target)) { "Dependency destination appeared; existing files were preserved" }
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    companion object {
        internal const val STAGING_NAME = ".sillyclient-dependency-restore"
        internal const val IDENTITY = ".sc-identity"
        internal const val PREFIX = "sillyclient-dependency-restore-v1\n"
    }
}
