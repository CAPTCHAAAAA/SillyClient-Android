package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ManagedFilesTest {
    private fun withRoot(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent == null) Files.createTempDirectory("managed-files-").toFile()
            else Files.createTempDirectory(parent.toPath(), "managed-files-").toFile()
        try { test(root) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            if (root.exists()) ManagedFiles.deleteDirectory(root, requireNotNull(root.parentFile))
        }
    }

    private fun write(directory: File, name: String, text: String = "fixture"): File =
        File(directory, name).apply { requireNotNull(parentFile).mkdirs(); writeText(text) }

    private fun link(link: File, target: File) {
        link.parentFile.mkdirs()
        val created = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        assumeTrue("Host does not permit creating symbolic links", created)
    }

    @Test
    fun deletesNestedTreeButNeverItsManagedRootOrNeighbors() = withRoot { root ->
        val target = File(root, "instance")
        repeat(200) { write(target, "node_modules/package-$it/package.json") }
        val neighbor = write(root, "other-instance/chat.jsonl", "keep")
        assertTrue(ManagedFiles.deleteDirectory(target, root))
        assertFalse(target.exists())
        assertEquals("keep", neighbor.readText())
        assertThrows(IllegalArgumentException::class.java) { ManagedFiles.deleteDirectory(root, root) }
    }

    @Test
    fun deletesValidDanglingAndDirectorySymlinksAsLeaves() = withRoot { root ->
        val target = File(root, "instance")
        val externalFile = write(root, "outside/chat.jsonl", "keep")
        val regularFile = write(target, "node_modules/tool/cli.js")
        link(File(target, "node_modules/.bin/tool"), regularFile)
        link(File(target, "node_modules/.bin/missing"), File(root, "missing-target"))
        link(File(target, "node_modules/linked-directory"), externalFile.parentFile)
        assertTrue(ManagedFiles.deleteDirectory(target, root))
        assertFalse(Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", externalFile.readText())
    }

    @Test
    fun refusesLinkedDeletionRoots() = withRoot { root ->
        val externalFile = write(root, "outside/chat.jsonl", "keep")
        val target = File(root, "linked-instance")
        link(target, externalFile.parentFile)
        assertThrows(IllegalArgumentException::class.java) { ManagedFiles.deleteDirectory(target, root) }
        assertEquals("keep", externalFile.readText())
        Files.delete(target.toPath())
    }

    @Test
    fun deletionCancellationStopsTraversalAndCanBeRetried() = withRoot { root ->
        val target = File(root, "instance")
        repeat(50) { write(target, "node_modules/package-$it/package.json") }
        var steps = 0
        assertThrows(CancellationException::class.java) {
            ManagedFiles.deleteDirectory(target, root) {
                if (++steps == 6) throw CancellationException("cancelled")
            }
        }
        assertTrue(target.exists())
        assertTrue(target.walkTopDown().any { it.isFile })
        assertTrue(ManagedFiles.deleteDirectory(target, root))
    }

    @Test
    fun cancellationBeforeTraversalDeletesNothing() = withRoot { root ->
        val target = File(root, "instance")
        val data = write(target, "chat.jsonl", "keep")
        assertThrows(CancellationException::class.java) {
            ManagedFiles.deleteDirectory(target, root) { throw CancellationException("cancelled") }
        }
        assertEquals("keep", data.readText())
    }

    @Test
    fun deletingAnAlreadyAbsentOwnedPathSucceeds() = withRoot { root ->
        assertTrue(ManagedFiles.deleteDirectory(File(root, "missing"), root))
    }
}
