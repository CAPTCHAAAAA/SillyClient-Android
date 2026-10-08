package com.sillyclient.runtime

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One shared dependency tree per instance root, stored at `<root>/node_modules`.
 *
 * Node and webpack resolve packages by walking up from the instance directory,
 * so placing the tree one level above the instances makes every instance
 * resolve it with zero launch wiring — this is the 1.9.1 model with today's
 * safeguards: the bank carries a marker naming the lock key it satisfies,
 * [covers] verifies it cheaply, and the launcher re-materializes it from the
 * bundled archive when it is missing, incomplete, or stale. Instances that
 * still carry their own node_modules keep working: their local tree wins and
 * the bank is simply ignored.
 *
 * Traps already paid for, do not reintroduce: renames of populated trees are
 * banned on shared storage (MediaProvider re-indexes every descendant), and
 * extraction into the public tree must use the parallel tar groups — the
 * measured floor is ~45 s for the 20k-file tree, paid once per root.
 */
object DependencyBank {
    internal const val DIR_NAME = "node_modules"
    internal const val MARKER = ".sillyclient-bank.json"

    data class State(val root: File, val key: String, val createdAt: Long)

    fun bankDirectory(instanceOrRootDirectory: File): File =
        File(instanceOrRootDirectory.parentFile ?: instanceOrRootDirectory, DIR_NAME)

    fun markerFile(bankDirectory: File): File = File(bankDirectory, MARKER)

    fun read(bankDirectory: File): State? {
        val marker = markerFile(bankDirectory)
        if (!marker.isFile || marker.length() > 8 * 1024) return null
        return try {
            val document = JSONObject(marker.readText(Charsets.UTF_8))
            val key = document.optString("key", "")
            val complete = document.optBoolean("complete", false)
            if (!complete || !key.matches(Regex("[0-9a-f]{64}"))) return null
            State(bankDirectory, key, document.optLong("createdAt", 0L))
        } catch (_: Exception) {
            null
        }
    }

    fun write(bankDirectory: File, key: String) {
        require(key.matches(Regex("[0-9a-f]{64}"))) { "Invalid dependency bank key" }
        val document = JSONObject()
            .put("key", key)
            .put("complete", true)
            .put("createdAt", System.currentTimeMillis())
        val staging = File(bankDirectory, "$MARKER.tmp")
        Files.write(staging.toPath(), document.toString().toByteArray(Charsets.UTF_8))
        Files.move(staging.toPath(), markerFile(bankDirectory).toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Complete only when the bank names the same lock key as the instance. */
    fun covers(instanceDirectory: File, instanceLockKey: String?): Boolean {
        if (instanceLockKey == null) return false
        val bank = bankDirectory(instanceDirectory)
        val state = read(bank) ?: return false
        // The bank is the node_modules tree itself and carries no manifest; the
        // instance's package.json defines the dependency set to verify against.
        return state.key == instanceLockKey &&
            DependencyInstaller.hasRequiredPackages(instanceDirectory, bank)
    }
}
