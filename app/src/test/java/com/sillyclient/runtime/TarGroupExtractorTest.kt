package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TarGroupExtractorTest {
    private fun fixture(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)?.apply { mkdirs() }
        val root = if (parent == null) Files.createTempDirectory("tar-groups-").toFile()
            else Files.createTempDirectory(parent.toPath(), "tar-groups-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    private fun <T> withExtractor(root: File, test: (TarGroupExtractor) -> T): T {
        OperationCoordinator().use { operations ->
            ProcessSupervisor(operations).use { processes ->
                val home = File(root, "tarven")
                val paths = RuntimePaths(root, home, File(home, "bootstrap"), File(root, "Servers"),
                    File(home, "usr"), File(home, "usr/lib"), File(home, "tmp"), File(home, "logs"),
                    File(root, "native"), File(root, "native/node"))
                return test(TarGroupExtractor(paths, operations, processes))
            }
        }
    }

    private fun entry(name: String, content: String): UstarArchive.Entry =
        UstarArchive.Entry(name, false, false, "", 0b110_100_100, content.toByteArray(Charsets.UTF_8).size.toLong()) {
            content.byteInputStream()
        }

    private fun dirEntry(name: String): UstarArchive.Entry =
        UstarArchive.Entry(name, true, false, "", 0b111_101_101, 0L, UstarArchive::emptyContent)

    @Test
    fun scanCountsEntriesPerTopLevelPackage() = fixture { root ->
        val longTop = "longtop-" + "segment/".repeat(14) + "leaf.js"
        val archive = File(root, "dep.tar")
        UstarArchive.write(archive, sequenceOf(
            dirEntry("express"),
            dirEntry("express/lib"),
            entry("express/index.js", "one"),
            entry("express/lib/router.js", "two"),
            entry("yaml/dist/index.js", "three"),
            entry(longTop, "four"),
            entry("package.json", "{}")
        ))
        withExtractor(root) { extractor ->
            val counts = extractor.scanTopLevels(archive)
            // directory entries count too: express + express/lib + index.js + router.js
            assertEquals(4, counts["express"])
            assertEquals(1, counts["yaml"])
            assertEquals(1, counts[longTop.substringBefore('/')])
            assertEquals(1, counts["package.json"])
        }
    }

    @Test
    fun planGroupsBalancesWeightsAndKeepsEveryName() = fixture { root ->
        val entries = linkedMapOf("big" to 9, "medium" to 5, "small" to 1, "tiny" to 1)
        withExtractor(root) { extractor ->
            val plan = extractor.planGroups(entries, 2)
            assertEquals(2, plan.size)
            assertEquals(setOf("big", "medium", "small", "tiny"), plan.flatten().toSet())
            val weights = plan.map { group -> group.sumOf { entries.getValue(it) } }
            // 9 is indivisible, so 9 | 7 is the optimal split for these weights.
            assertTrue("weights must stay close: $weights", kotlin.math.abs(weights[0] - weights[1]) <= 2)
        }
    }

    @Test
    fun planGroupsNeverKeepsEmptyGroups() = fixture { root ->
        withExtractor(root) { extractor ->
            val plan = extractor.planGroups(linkedMapOf("only" to 3), 4)
            assertEquals(listOf(listOf("only")), plan)
        }
    }
}
