package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Refresh the private command alias when an APK upgrade relocates its native libraries. */
internal object BundledNodeAlias {
    @Synchronized
    fun ensure(home: File, bundledNode: File): File {
        check(bundledNode.isFile) { "Bundled Node is unavailable" }
        val bin = File(home, "bin").toPath()
        check(!Files.isSymbolicLink(bin)) { "The bundled command directory must not be a link" }
        Files.createDirectories(bin)
        check(Files.isDirectory(bin, LinkOption.NOFOLLOW_LINKS)) { "The bundled command directory is unavailable" }
        val node = bin.resolve("node")
        val target = bundledNode.toPath().toAbsolutePath().normalize()
        fun requireAliasOrAbsent() {
            check(!Files.exists(node, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(node)) {
                "The bundled Node command is not a managed link"
            }
        }
        requireAliasOrAbsent()
        if (Files.isSymbolicLink(node)) {
            val current = node.parent.resolve(Files.readSymbolicLink(node)).toAbsolutePath().normalize()
            if (current == target) return node.toFile()
        }
        val temporary = bin.resolve(".node-${UUID.randomUUID()}.tmp")
        Files.createSymbolicLink(temporary, target)
        try {
            requireAliasOrAbsent()
            // No delete-then-create gap; failed publication leaves the previous alias intact.
            Files.move(temporary, node, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return node.toFile()
    }
}
