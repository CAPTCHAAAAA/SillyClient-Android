package com.sillyclient.runtime

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledDependencyArchivesTest {
    private fun withRoot(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent != null) {
            Files.createTempDirectory(parent.toPath(), "bundled-archives-").toFile()
        } else Files.createTempDirectory("bundled-archives-").toFile()
        try { test(root) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    private fun buildModules(nodeModules: File) {
        File(nodeModules, "yaml").mkdirs()
        File(nodeModules, "yaml/package.json").writeText("""{"name":"yaml","version":"2.0.0"}""")
        File(nodeModules, "@scope/parser/package.json")
            .apply { parentFile!!.mkdirs() }
            .writeText("""{"name":"@scope/parser"}""")
    }

    @Test
    fun publishesBundledArchivesUnderCanonicalNamesSoRestoreHits() = withRoot { root ->
        val source = File(root, "source").apply { mkdirs() }
        File(source, "package-lock.json").writeText("lock-v1")
        buildModules(File(source, "node_modules"))
        val store = File(root, "store")
        val archive = DependencyArchive(store)
        val key = archive.lockKey(File(source, "package-lock.json"))
        assertNotNull(key)
        assertTrue(archive.archive(source, key!!))
        val canonical = store.listFiles()!!.single { it.name.endsWith(".tar") }

        val assetName = "dependency-${canonical.name}"
        val assets = mapOf("bundled/$assetName" to canonical.readBytes())
        val materialized = File(root, "materialized")
        BundledDependencyArchives(
            materialized,
            openAsset = { name -> ByteArrayInputStream(assets.getValue(name)) },
            listAssets = { setOf(assetName, "sillytavern-release.zip", "dependency-malformed.tar") }
        ).use { bundled -> bundled.awaitReady { } }

        val published = File(materialized, canonical.name)
        assertTrue(published.isFile)
        assertEquals(canonical.length(), published.length())
        assertFalse(File(materialized, assetName).exists())
        assertFalse(File(materialized, "dependency-malformed.tar").exists())
        assertFalse(File(materialized, "sillytavern-release.zip").exists())

        val target = File(root, "target").apply { mkdirs() }
        File(target, "package-lock.json").writeText("lock-v1")
        File(target, "package.json").writeText(
            JSONObject().put("dependencies", JSONObject().put("yaml", "^2.0.0")).toString()
        )
        assertTrue(DependencyArchive(materialized).restore(key, target) { })
        assertEquals("""{"name":"yaml","version":"2.0.0"}""",
            File(File(target, "node_modules"), "yaml/package.json").readText())
        assertTrue(DependencyInstaller.hasRequiredPackages(target))
    }
}
