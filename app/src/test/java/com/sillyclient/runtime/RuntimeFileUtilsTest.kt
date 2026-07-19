package com.sillyclient.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeFileUtilsTest {

    @Test
    fun unzipStreamExtractsNestedFile() {
        val root = Files.createTempDirectory("sillyclient-unzip").toFile()
        try {
            RuntimeFileUtils.unzipStream(zip("nested/config.json", "{}"), root)
            assertEquals("{}", File(root, "nested/config.json").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unzipStreamRejectsSiblingPrefixTraversal() {
        val parent = Files.createTempDirectory("sillyclient-unzip-parent").toFile()
        val target = File(parent, "target")
        val escaped = File(parent, "target-escape.txt")
        try {
            assertThrows(IllegalStateException::class.java) {
                RuntimeFileUtils.unzipStream(zip("../target-escape.txt", "escaped"), target)
            }
            assertFalse(escaped.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun instanceDirectoryCannotEscapeServersRoot() {
        val root = Files.createTempDirectory("sillyclient-paths").toFile()
        try {
            val servers = File(root, "servers")
            val paths = RuntimePaths(
                appFilesDir = root,
                tarvenHome = File(root, "home"),
                bootstrapDir = File(root, "bootstrap"),
                serversDir = servers,
                usrDir = File(root, "usr"),
                usrLibDir = File(root, "usr/lib"),
                tmpDir = File(root, "tmp"),
                logsDir = File(root, "logs"),
                nativeLibDir = File(root, "native"),
                nodeBin = File(root, "native/libtarven-node.so")
            )

            val instance = paths.serverDirFor("../outside", create = false)
            assertTrue(instance.canonicalFile.toPath().startsWith(servers.canonicalFile.toPath()))
            assertEquals("outside", instance.name)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zip(name: String, content: String): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            output.putNextEntry(ZipEntry(name))
            output.write(content.toByteArray())
            output.closeEntry()
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
