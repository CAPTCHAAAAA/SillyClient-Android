package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DependencyRestoreTransactionTest {
    private val key = "a".repeat(64)

    private fun withInstance(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent == null) Files.createTempDirectory("dependency-transaction-").toFile()
            else Files.createTempDirectory(parent.toPath(), "dependency-transaction-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    private fun transaction() = DependencyRestoreTransaction { directory, root, verify ->
        verify()
        assertTrue(ManagedFiles.deleteDirectory(directory, root))
    }

    private fun tree(directory: File, content: String) {
        directory.mkdirs()
        File(directory, "entry.js").writeText(content)
    }

    private fun valid(directory: File) = File(directory, "entry.js").readText() == "new"

    private fun stage(instance: File, ready: Boolean): File =
        File(instance, DependencyRestoreTransaction.STAGING_NAME).apply {
            mkdirs()
            File(this, DependencyRestoreTransaction.IDENTITY).writeText(
                DependencyRestoreTransaction.PREFIX + key + if (ready) "\nready" else "\nextracting")
        }

    @Test
    fun partialDependenciesAreReplacedOnlyAfterValidation() = withInstance { instance ->
        val modules = File(instance, "node_modules")
        tree(modules, "old")
        File(instance, "data").mkdirs()
        File(instance, "data/chat.jsonl").writeText("keep")
        assertTrue(transaction().restore(instance, key, true, ::valid, {}) { prepared ->
            assertEquals("old", File(modules, "entry.js").readText())
            tree(prepared, "new")
        })
        assertTrue(valid(modules))
        assertEquals("keep", File(instance, "data/chat.jsonl").readText())
        assertFalse(File(instance, DependencyRestoreTransaction.STAGING_NAME).exists())
    }

    @Test
    fun failedValidationPreservesTheOldTree() = withInstance { instance ->
        val modules = File(instance, "node_modules")
        tree(modules, "old")
        assertThrows(IllegalStateException::class.java) {
            transaction().restore(instance, key, true, ::valid, {}) { tree(it, "bad") }
        }
        assertEquals("old", File(modules, "entry.js").readText())
        assertFalse(transaction().recover(instance, key, ::valid) {})
        assertFalse(File(instance, DependencyRestoreTransaction.STAGING_NAME).exists())
    }

    @Test
    fun cancellationDuringExtractionDoesNotPublishAndCanRetry() = withInstance { instance ->
        val modules = File(instance, "node_modules")
        tree(modules, "old")
        assertThrows(CancellationException::class.java) {
            transaction().restore(instance, key, true, ::valid, {}) {
                tree(it, "partial")
                throw CancellationException("stop")
            }
        }
        assertEquals("old", File(modules, "entry.js").readText())
        assertFalse(transaction().recover(instance, key, ::valid) {})
        assertTrue(transaction().restore(instance, key, true, ::valid, {}) { tree(it, "new") })
    }

    @Test
    fun restartBetweenTheTwoRenamesPublishesTheVerifiedTree() = withInstance { instance ->
        val staging = stage(instance, ready = true)
        tree(File(staging, "previous"), "old")
        tree(File(staging, "node_modules"), "new")
        assertTrue(transaction().recover(instance, key, ::valid) {})
        assertTrue(valid(File(instance, "node_modules")))
        assertFalse(staging.exists())
    }

    @Test
    fun restartDuringOldDependencyCleanupDoesNotReextract() = withInstance { instance ->
        val staging = stage(instance, ready = true)
        tree(File(staging, "previous"), "partially deleted old")
        tree(File(instance, "node_modules"), "new")
        assertTrue(transaction().recover(instance, key, ::valid) {})
        assertTrue(valid(File(instance, "node_modules")))
        assertFalse(staging.exists())
    }

    @Test
    fun restartAfterOldTreeWasRemovedStillRecognizesPublishedDependencies() = withInstance { instance ->
        val staging = stage(instance, ready = true)
        tree(File(instance, "node_modules"), "new")
        assertTrue(transaction().recover(instance, key, ::valid) {})
        assertFalse(staging.exists())
    }

    @Test
    fun unknownRecoveryDirectoriesAreNeverDeleted() = withInstance { instance ->
        val staging = File(instance, DependencyRestoreTransaction.STAGING_NAME).apply { mkdirs() }
        File(staging, "user.txt").writeText("keep")
        assertThrows(IllegalArgumentException::class.java) { transaction().recover(instance, key, ::valid) {} }
        assertEquals("keep", File(staging, "user.txt").readText())
    }

    @Test
    fun killBetweenDirectoryAndMarkerCreationDoesNotBlockTheNextAttempt() = withInstance { instance ->
        val staging = File(instance, DependencyRestoreTransaction.STAGING_NAME).apply { mkdirs() }
        assertFalse(transaction().recover(instance, key, ::valid) {})
        assertFalse(staging.exists())
        assertTrue(transaction().restore(instance, key, true, ::valid, {}) { tree(it, "new") })
    }

    @Test
    fun changingTheLockDoesNotPublishTheOldPreparedTree() = withInstance { instance ->
        val staging = stage(instance, ready = true)
        tree(File(staging, "previous"), "old")
        tree(File(staging, "node_modules"), "new")
        assertFalse(transaction().recover(instance, "b".repeat(64), ::valid) {})
        assertEquals("old", File(instance, "node_modules/entry.js").readText())
        assertFalse(staging.exists())
    }

    @Test
    fun invalidPublishedTreePreservesTheBackupForRecovery() = withInstance { instance ->
        val staging = stage(instance, ready = true)
        tree(File(staging, "previous"), "old")
        tree(File(instance, "node_modules"), "bad")
        assertThrows(IllegalStateException::class.java) { transaction().recover(instance, key, ::valid) {} }
        assertEquals("old", File(staging, "previous/entry.js").readText())
        assertEquals("bad", File(instance, "node_modules/entry.js").readText())
    }

    @Test
    fun unknownFilesInsideAnOwnedStageAreNotCleaned() = withInstance { instance ->
        val staging = stage(instance, ready = false)
        File(staging, "user.txt").writeText("keep")
        assertThrows(IllegalArgumentException::class.java) { transaction().recover(instance, key, ::valid) {} }
        assertEquals("keep", File(staging, "user.txt").readText())
    }

    @Test
    fun changingStageIdentityDuringValidationPreventsPublication() = withInstance { instance ->
        val modules = File(instance, "node_modules")
        tree(modules, "old")
        assertThrows(IllegalStateException::class.java) {
            transaction().restore(instance, key, true, { prepared ->
                File(prepared.parentFile, DependencyRestoreTransaction.IDENTITY).writeText(
                    DependencyRestoreTransaction.PREFIX + "b".repeat(64) + "\nextracting")
                true
            }, {}) { tree(it, "new") }
        }
        assertEquals("old", File(modules, "entry.js").readText())
    }
}
