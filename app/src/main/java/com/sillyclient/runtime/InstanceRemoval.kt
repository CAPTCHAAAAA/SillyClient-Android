package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Keep the registered identity available until every user-content deletion has succeeded. */
object InstanceRemoval {
    /**
     * Marker written into an instance directory whose deletion has been
     * committed: the console forgets the instance immediately and the physical
     * delete streams in the background. Scanners must skip marked directories.
     */
    const val REMOVAL_MARKER = ".sillyclient-removing"
    internal const val MARKER_CONTENT = "sillyclient-removal-v1\n"

    /**
     * Names of directory renames left by older builds (`.name.sillyclient-removing-<uuid>`).
     * Current builds never rename; these remnants are swept and never scanned.
     */
    internal val RENAME_PATTERN = Regex("""\..+\.sillyclient-removing-[0-9a-fA-F-]{36}""")
    internal fun remove(
        target: File,
        root: File,
        verifyIdentity: () -> Unit,
        ensureActive: () -> Unit,
        removeChildren: (List<File>) -> Unit,
        commit: (() -> Unit) -> Unit,
        unregister: () -> Unit,
        deleteEmptyDirectory: (File) -> Unit = { Files.delete(it.toPath()) },
        identityFileName: String = IDENTITY_FILE
    ) {
        require(identityFileName.isNotBlank() && identityFileName !in setOf(".", "..") &&
            identityFileName.none { it == '/' || it == '\\' || it == '\u0000' }) { "Invalid identity marker name" }
        ensureActive()
        require(ManagedFiles.isWithin(target, root)) { "实例目录超出可删除范围，操作已中止" }
        verifyIdentity()
        if (!exists(target)) {
            commit {
                ensureActive()
                verifyIdentity()
                require(!exists(target)) { "实例目录在删除过程中被重新创建，原文件已保留" }
                unregister()
            }
            return
        }
        val original = attributes(target)
        val marker = File(target, identityFileName)
        val identity = if (exists(marker)) readIdentity(marker) else null
        val children = children(target).filter { it.name != identityFileName }
        ensureActive()
        requireSameDirectory(target, root, original)
        verifyIdentity()
        requireIdentity(marker, identity)
        // Callers pass these literal child paths to rm as arguments, never canonicalize links.
        if (children.isNotEmpty()) removeChildren(children)
        ensureActive()
        commit {
            ensureActive()
            requireSameDirectory(target, root, original)
            verifyIdentity()
            require(children(target).all { it.name == identityFileName }) {
                "实例目录仍有残留文件，注册信息已保留，请重试删除"
            }
            requireIdentity(marker, identity)
            var removedIdentity = false
            try {
                if (identity != null) {
                    Files.delete(marker.toPath())
                    removedIdentity = true
                }
                requireSameDirectory(target, root, original)
                deleteEmptyDirectory(target)
                check(!exists(target)) { "实例目录未能删除，请重试" }
            } catch (error: Exception) {
                if (removedIdentity) {
                    try {
                        requireSameDirectory(target, root, original)
                        if (exists(marker)) {
                            require(readIdentity(marker).contentEquals(requireNotNull(identity))) {
                                "实例标识已变化，未写入恢复标记"
                            }
                        } else {
                            Files.newOutputStream(marker.toPath(), StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
                                it.write(requireNotNull(identity))
                            }
                        }
                    } catch (restoreError: Exception) { error.addSuppressed(restoreError) }
                }
                throw error
            }
            unregister()
        }
    }

    private fun children(directory: File): List<File> =
        Files.newDirectoryStream(directory.toPath()).use { stream -> stream.map { it.toFile() } }

    private fun requireSameDirectory(directory: File, root: File, original: BasicFileAttributes) {
        require(ManagedFiles.isWithin(directory, root)) { "实例目录在删除过程中被移动，原文件已保留" }
        val current = attributes(directory)
        require(original.fileKey()?.let { it == current.fileKey() } ?: (original.creationTime() == current.creationTime())) {
            "实例目录标识在删除过程中发生变化，原文件已保留"
        }
    }

    private fun attributes(directory: File): BasicFileAttributes =
        Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
            require(it.isDirectory && !it.isSymbolicLink) { "删除目标必须是真实目录" }
        }

    private fun requireIdentity(marker: File, expected: ByteArray?) {
        require(if (expected == null) !exists(marker) else readIdentity(marker).contentEquals(expected)) {
            "实例标识在删除过程中被修改，注册信息已保留"
        }
    }

    private fun readIdentity(marker: File): ByteArray {
        require(ManagedFiles.isUnlinked(marker) && marker.isFile && marker.length() in 1..MAX_IDENTITY_BYTES) {
            "实例标识文件异常"
        }
        return Files.newInputStream(marker.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
            val bytes = ByteArray(MAX_IDENTITY_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = it.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            require(count in 1..MAX_IDENTITY_BYTES) { "实例标识文件超出大小限制" }
            bytes.copyOf(count)
        }
    }

    private fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private const val IDENTITY_FILE = ".sc-identity"
    private const val MAX_IDENTITY_BYTES = 1024
}
