package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceRepositoryTest {
    @Test
    fun markedRemnantsAreHiddenFromScanning() {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent == null) Files.createTempDirectory("instance-repository-").toFile()
            else Files.createTempDirectory(parent.toPath(), "instance-repository-").toFile()
        try {
            val servers = File(root, "instances")
            File(servers, "live").apply { mkdirs(); File(this, "server.js").writeText("source") }
            val remnant = File(servers, "remnant").apply {
                mkdirs()
                File(this, "server.js").writeText("source")
                File(this, InstanceRemoval.REMOVAL_MARKER).writeText("sillyclient-removal-v1\n")
            }
            val scanned = InstanceRepository(servers).scan().map { it.instanceId }
            assertTrue(scanned.contains("live"))
            assertFalse(scanned.contains("remnant"))
            // Scanning never touches the remnant; its background purge owns it.
            assertTrue(remnant.isDirectory)

            // Renamed remnants from older builds are hidden the same way.
            val renamed = File(servers, ".hidden.sillyclient-removing-12345678-1234-1234-1234-123456789abc").apply {
                mkdirs()
                File(this, "server.js").writeText("source")
            }
            val rescanned = InstanceRepository(servers).scan().map { it.instanceId }
            assertFalse(rescanned.any { it.contains("sillyclient-removing") })
            assertTrue(renamed.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
