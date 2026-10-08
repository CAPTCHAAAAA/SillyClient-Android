package com.sillyclient.runtime

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object ManagedFiles {
    fun isUnlinked(file: File): Boolean {
        return !Files.isSymbolicLink(file.toPath())
    }

    fun isWithin(file: File, root: File): Boolean {
        val absoluteRoot = root.absoluteFile.toPath().normalize()
        val absoluteFile = file.absoluteFile.toPath().normalize()
        if (absoluteFile == absoluteRoot || !absoluteFile.startsWith(absoluteRoot)) return false
        if (!file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) return false
        // System aliases above the approved root are trusted; user-controlled links below it are not.
        var current: File? = absoluteFile.toFile()
        while (current != null) {
            if (!isUnlinked(current)) return false
            if (current.toPath() == absoluteRoot) return true
            current = current.parentFile
        }
        return false
    }

    fun size(directory: File, exclusions: Set<String> = emptySet()): Long {
        if (!isUnlinked(directory)) return 0
        if (directory.isFile) return directory.length()
        return directory.walkTopDown()
            .onEnter { isUnlinked(it) && (it == directory || it.name !in exclusions) }
            .filter { it.isFile && isUnlinked(it) }
            .sumOf { it.length() }
    }

    fun deleteDirectory(directory: File, root: File, ensureActive: () -> Unit = {}): Boolean {
        require(isWithin(directory, root)) { "Directory is outside the managed scope" }
        val target = directory.toPath()
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return true
        // No FOLLOW_LINKS: npm links, including dangling links, are deleted as leaves.
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                ensureActive()
                require(isWithin(path.toFile(), root)) { "Linked directory changed during deletion; its files were preserved" }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                ensureActive()
                require(path != target || !attributes.isSymbolicLink) { "Linked deletion root was preserved" }
                require(isWithin(requireNotNull(path.parent).toFile(), root) || path.parent == root.toPath()) {
                    "File parent changed during deletion; its files were preserved"
                }
                Files.deleteIfExists(path)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                ensureActive()
                require(isWithin(path.toFile(), root)) { "Directory changed during deletion; its files were preserved" }
                Files.deleteIfExists(path)
                return FileVisitResult.CONTINUE
            }
        })
        return !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
    }
}
