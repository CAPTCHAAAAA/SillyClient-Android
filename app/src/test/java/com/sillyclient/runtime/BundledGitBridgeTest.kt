package com.sillyclient.runtime

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledGitBridgeTest {
    @Test
    fun extractsOnceAtomicallyAndAppendsPreloadToNodeOptions() {
        val root = Files.createTempDirectory("sillyclient-git-bridge-").toFile()
        try {
            val bootstrap = File(root, "dir with spaces/bootstrap")
            val reads = AtomicInteger()
            val dir = BundledGitBridge.ensurePrepared(bootstrap) { name ->
                reads.incrementAndGet()
                ByteArrayInputStream("// asset $name\n".toByteArray(Charsets.UTF_8))
            }
            assertEquals(4, reads.get())
            assertTrue(File(dir, "isomorphic-git.cjs").isFile)
            assertTrue(File(dir, "isomorphic-git-http-node.cjs").isFile)
            assertTrue(File(dir, "git-bridge-preload.cjs").isFile)
            assertTrue(File(dir, "THIRD_PARTY_NOTICES.txt").isFile)

            val second = BundledGitBridge.ensurePrepared(bootstrap) {
                throw AssertionError("Already-prepared bridge should not re-extract")
            }
            assertEquals(dir.canonicalFile, second.canonicalFile)

            val env = mutableMapOf("NODE_OPTIONS" to "--max-old-space-size=2048")
            BundledGitBridge.applyEnvironment(env, dir)
            BundledGitBridge.applyEnvironment(env, dir)
            assertEquals(dir.absolutePath, env["SILLYCLIENT_GIT_BRIDGE_DIR"])
            assertEquals("bridge", env["SILLYCLIENT_GIT_BACKEND"])
            val expectedPreload = File(dir, "git-bridge-preload.cjs").absolutePath.replace('\\', '/')
            assertEquals("--max-old-space-size=2048 --require=\"$expectedPreload\"", env["NODE_OPTIONS"])
        } finally {
            root.deleteRecursively()
        }
    }
}
