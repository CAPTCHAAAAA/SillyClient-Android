package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceUsageStoreTest {
    @Test
    fun beginCheckpointAndFinishUseMonotonicElapsedTime() = withFixture { root ->
        var wall = 1_700_000_000_000L
        var elapsed = 10_000_000_000L
        val directory = instance(root, "alpha", "identity-alpha")
        val store = InstanceUsageStore(File(root, "instance-usage.json"), { wall }, { elapsed })

        val started = store.begin("alpha", directory)
        assertEquals(0L, started.totalUsageMs)
        elapsed += 5_000_000_000L
        val checkpoint = requireNotNull(store.checkpoint("alpha"))
        assertEquals(5_000L, checkpoint.totalUsageMs)
        elapsed += 7_000_000_000L
        val finished = requireNotNull(store.finish("alpha"))
        assertEquals(12_000L, finished.totalUsageMs)
        assertNull(store.finish("alpha"))
    }

    @Test
    fun sessionsRemainIndependentAndIdentityMismatchDoesNotCarryTotals() = withFixture { root ->
        var wall = 1_700_000_000_000L
        var elapsed = 20_000_000_000L
        val file = File(root, "instance-usage.json")
        val alpha = instance(root, "alpha", "identity-alpha")
        val beta = instance(root, "beta", "identity-beta")
        val store = InstanceUsageStore(file, { wall }, { elapsed })

        store.begin("alpha", alpha)
        store.begin("beta", beta)
        elapsed += 3_000_000_000L
        assertEquals(3_000L, requireNotNull(store.finish("alpha")).totalUsageMs)
        elapsed += 2_000_000_000L
        assertEquals(5_000L, requireNotNull(store.finish("beta")).totalUsageMs)

        val replacement = instance(root, "replacement", "identity-new")
        assertNull(store.usageFor("alpha", replacement))
        val fresh = store.begin("alpha", replacement)
        assertEquals(0L, fresh.totalUsageMs)
    }

    @Test
    fun staleSessionsAreClearedWithoutCountingOfflineTime() = withFixture { root ->
        var wall = 1_700_000_000_000L
        var elapsed = 30_000_000_000L
        val file = File(root, "instance-usage.json")
        val directory = instance(root, "alpha", "identity-alpha")
        val first = InstanceUsageStore(file, { wall }, { elapsed })
        first.begin("alpha", directory)
        elapsed += 4_000_000_000L
        first.checkpoint("alpha")

        // A new app process has no monotonic session clock. It clears only the
        // persisted active marker, never guessing the time spent offline.
        wall += 86_400_000L
        elapsed += 86_400_000_000_000L
        val restarted = InstanceUsageStore(file, { wall }, { elapsed })
        restarted.finishStaleSessions()
        assertEquals(4_000L, requireNotNull(restarted.usageFor("alpha", directory)).totalUsageMs)
        assertNull(restarted.finish("alpha"))
    }

    @Test
    fun corruptLedgerDoesNotAffectInstanceFilesAndCanRecover() = withFixture { root ->
        val file = File(root, "instance-usage.json").apply { writeText("{not-json") }
        val directory = instance(root, "alpha", "identity-alpha")
        val store = InstanceUsageStore(file)

        assertNull(store.usageFor("alpha", directory))
        val usage = store.begin("alpha", directory)
        assertEquals(0L, usage.totalUsageMs)
        assertTrue(file.isFile)
        assertTrue(directory.isDirectory)
    }

    @Test
    fun removeClearsOnlyTheUsageRecord() = withFixture { root ->
        var elapsed = 40_000_000_000L
        val file = File(root, "instance-usage.json")
        val directory = instance(root, "alpha", "identity-alpha")
        val store = InstanceUsageStore(file, { 1_700_000_000_000L }, { elapsed })

        store.begin("alpha", directory)
        elapsed += 1_000_000_000L
        store.finish("alpha")
        assertTrue(store.remove("alpha", directory))
        assertNull(store.usageFor("alpha", directory))
        assertNotNull(directory.listFiles())
    }

    private fun instance(root: File, name: String, identity: String): File =
        File(root, name).apply {
            mkdirs()
            File(this, ".sc-identity").writeText(identity)
            File(this, "server.js").writeText("// test")
        }

    private fun withFixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("instance-usage-").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
