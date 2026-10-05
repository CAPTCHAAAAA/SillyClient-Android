package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DependencyArchiveTest {
    private fun withRoot(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent != null) {
            Files.createTempDirectory(parent.toPath(), "dependency-archive-").toFile()
        } else Files.createTempDirectory("dependency-archive-").toFile()
        try { test(root) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    private fun requireSymlinks(root: File) {
        val target = File(root, "symlink-probe-target").apply { writeText("probe") }.toPath()
        val link = File(root, "symlink-probe").toPath()
        val available = runCatching { Files.createSymbolicLink(link, target) }.isSuccess
        Files.deleteIfExists(link)
        Files.deleteIfExists(target)
        assumeTrue("The test host cannot create symbolic links", available)
    }

    private fun instance(root: File, name: String): File =
        File(root, name).apply { mkdirs() }

    private fun lockFor(directory: File, content: String) =
        File(directory, "package-lock.json").apply { writeText(content) }

    private fun buildModules(nodeModules: File) {
        File(nodeModules, "yaml").mkdirs()
        File(nodeModules, "yaml/package.json").writeText("""{"name":"yaml","version":"2.0.0"}""")
        File(nodeModules, "@scope/parser/node_modules/dep").mkdirs()
        File(nodeModules, "@scope/parser/package.json").writeText("""{"name":"@scope/parser"}""")
        File(nodeModules, "@scope/parser/node_modules/dep/package.json").writeText("""{"name":"dep"}""")
        File(nodeModules, ".package-lock.json").writeText("""{"hidden":true}""")
        File(nodeModules, "empty-dir").mkdirs()
        File(nodeModules, "说明.md").writeText("unicode documentation")
        File(nodeModules, "big.dat").writeBytes(ByteArray(5000) { (it % 251).toByte() })
        File(nodeModules, "bin.sh").writeText("#!/system/bin/sh\nexit 0\n")
    }

    @Test
    fun archivedTreesRestoreIntoAFreshInstance() = withRoot { root ->
        requireSymlinks(root)
        val source = instance(root, "source")
        lockFor(source, "lock-v1")
        val nodeModules = File(source, "node_modules")
        buildModules(nodeModules)
        Files.createSymbolicLink(
            File(nodeModules, "link").toPath(),
            java.nio.file.Paths.get("yaml/package.json")
        )
        val archive = DependencyArchive(File(root, "store"))
        val key = archive.lockKey(File(source, "package-lock.json"))
        assertNotNull(key)
        assertTrue(archive.archive(source, key!!))

        val target = instance(root, "target")
        lockFor(target, "lock-v1")
        File(target, "package.json").writeText(
            JSONObject().put("dependencies", JSONObject().put("yaml", "^2.0.0")).toString()
        )
        assertTrue(archive.restore(key, target) { })
        val restored = File(target, "node_modules")
        assertEquals("""{"name":"yaml","version":"2.0.0"}""", File(restored, "yaml/package.json").readText())
        assertEquals("""{"name":"dep"}""",
            File(restored, "@scope/parser/node_modules/dep/package.json").readText())
        assertEquals("unicode documentation", File(restored, "说明.md").readText())
        assertArrayEquals(ByteArray(5000) { (it % 251).toByte() }, File(restored, "big.dat").readBytes())
        assertTrue(File(restored, "empty-dir").isDirectory)
        assertTrue(Files.isSymbolicLink(File(restored, "link").toPath()))
        assertEquals(
            java.nio.file.Paths.get("yaml/package.json"),
            Files.readSymbolicLink(File(restored, "link").toPath())
        )
        assertTrue(DependencyInstaller.hasRequiredPackages(target))
    }

    @Test
    fun restoreRefusesInstancesThatAlreadyHoldModules() = withRoot { root ->
        val source = instance(root, "source")
        lockFor(source, "lock-v1")
        buildModules(File(source, "node_modules"))
        val archive = DependencyArchive(File(root, "store"))
        val key = archive.lockKey(File(source, "package-lock.json"))!!
        assertTrue(archive.archive(source, key))
        val target = instance(root, "target")
        lockFor(target, "lock-v1")
        File(target, "node_modules/leftover.txt").apply { parentFile!!.mkdirs(); writeText("partial") }
        assertFalse(archive.restore(key, target) { })
        assertEquals("partial", File(target, "node_modules/leftover.txt").readText())
    }

    @Test
    fun tamperedArchivesAreRejectedAndRemoved() = withRoot { root ->
        val source = instance(root, "source")
        lockFor(source, "lock-v1")
        buildModules(File(source, "node_modules"))
        val store = File(root, "store")
        val archive = DependencyArchive(store)
        val key = archive.lockKey(File(source, "package-lock.json"))!!
        assertTrue(archive.archive(source, key))
        val stored = store.listFiles()!!.single()
        stored.appendBytes(byteArrayOf(0))  // content no longer matches its recorded digest
        val target = instance(root, "target")
        lockFor(target, "lock-v1")
        assertFalse(archive.restore(key, target) { })
        assertFalse(stored.exists())
        assertFalse(File(target, "node_modules").exists())
    }

    @Test
    fun lockKeysAreContentAddressedAndRequireALock() = withRoot { root ->
        val first = instance(root, "first")
        val second = instance(root, "second")
        lockFor(first, "same lock")
        lockFor(second, "same lock")
        val archive = DependencyArchive(File(root, "store"))
        assertEquals(archive.lockKey(File(first, "package-lock.json")), archive.lockKey(File(second, "package-lock.json")))
        lockFor(second, "different lock")
        assertNotEquals(archive.lockKey(File(first, "package-lock.json")), archive.lockKey(File(second, "package-lock.json")))
        assertNull(archive.lockKey(File(instance(root, "third"), "package-lock.json")))
    }

    @Test
    fun emptyOrMissingTreesAreNotArchived() = withRoot { root ->
        val empty = instance(root, "empty")
        lockFor(empty, "lock-v1")
        File(empty, "node_modules").mkdirs()
        val missing = instance(root, "missing")
        lockFor(missing, "lock-v1")
        val archive = DependencyArchive(File(root, "store"))
        assertFalse(archive.archive(empty, "0".repeat(64)))
        assertFalse(archive.archive(missing, "0".repeat(64)))
        assertNull(File(root, "store").listFiles())
    }

    @Test
    fun archivingSkipsWhenAVerifiedArchiveAlreadyExists() = withRoot { root ->
        val source = instance(root, "source")
        lockFor(source, "lock-v1")
        buildModules(File(source, "node_modules"))
        val store = File(root, "store")
        val archive = DependencyArchive(store)
        val key = archive.lockKey(File(source, "package-lock.json"))!!
        assertTrue(archive.archive(source, key))
        File(source, "node_modules/yaml/package.json").writeText("""{"name":"yaml","version":"3.0.0"}""")
        assertTrue(archive.archive(source, key))
        assertEquals(1, store.listFiles()!!.size)
        val target = instance(root, "target")
        lockFor(target, "lock-v1")
        assertTrue(archive.restore(key, target) { })
        assertEquals("""{"name":"yaml","version":"2.0.0"}""",
            File(target, "node_modules/yaml/package.json").readText())
    }

    @Test
    fun pruningKeepsOnlyTheNewestEntries() = withRoot { root ->
        val store = File(root, "store")
        val archive = DependencyArchive(store, maxEntries = 1)
        val first = instance(root, "first")
        val second = instance(root, "second")
        lockFor(first, "lock-a")
        lockFor(second, "lock-b")
        buildModules(File(first, "node_modules"))
        buildModules(File(second, "node_modules"))
        val firstKey = archive.lockKey(File(first, "package-lock.json"))!!
        val secondKey = archive.lockKey(File(second, "package-lock.json"))!!
        assertTrue(archive.archive(first, firstKey))
        store.listFiles()!!.single().setLastModified(System.currentTimeMillis() - 100_000)
        assertTrue(archive.archive(second, secondKey))
        val remaining = store.listFiles()!!.single()
        assertTrue(remaining.name.startsWith(secondKey))
        assertFalse(archive.restore(firstKey, instance(root, "unused")) { })
    }

    @Test
    fun restoreWithoutAnyArchiveReturnsFalse() = withRoot { root ->
        val archive = DependencyArchive(File(root, "store"))
        val target = instance(root, "target")
        lockFor(target, "lock-v1")
        assertFalse(archive.restore("1".repeat(64), target) { })
        assertFalse(File(target, "node_modules").exists())
    }

    @Test
    fun restorePropagatesCancellationAndKeepsTheArchive() = withRoot { root ->
        val source = instance(root, "source")
        lockFor(source, "lock-v1")
        buildModules(File(source, "node_modules"))
        val store = File(root, "store")
        val archive = DependencyArchive(store)
        val key = archive.lockKey(File(source, "package-lock.json"))!!
        assertTrue(archive.archive(source, key))
        val target = instance(root, "target")
        lockFor(target, "lock-v1")
        val calls = AtomicInteger()
        val cancellation = CancellationException("stop")
        assertThrows(CancellationException::class.java) {
            archive.restore(key, target) {
                if (calls.incrementAndGet() >= 2) throw cancellation
            }
        }
        assertTrue(store.listFiles()!!.isNotEmpty())
    }

    @Test
    fun restoreRejectsMalformedKeys() = withRoot { root ->
        val archive = DependencyArchive(File(root, "store"))
        for (key in listOf("", "UPPERCASE", "xyz", "../escape")) {
            assertThrows(IllegalArgumentException::class.java) { archive.restore(key, root) { } }
            assertThrows(IllegalArgumentException::class.java) { archive.archive(root, key) }
        }
    }
}
