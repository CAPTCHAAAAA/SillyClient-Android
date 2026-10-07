package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsStoreTest {
    private fun fixture(test: (File, AppSettingsStore) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)?.apply { mkdirs() }
        val root = if (parent == null) Files.createTempDirectory("app-settings-").toFile()
            else Files.createTempDirectory(parent.toPath(), "app-settings-").toFile()
        try { test(root, AppSettingsStore(AppSettingsStore.settingsFile(File(root, "tarven")))) }
        finally { root.deleteRecursively() }
    }

    @Test
    fun missingFileLoadsDefaults() = fixture { _, store ->
        val snapshot = store.load()
        assertNull(snapshot.instancesRoot)
    }

    @Test
    fun writtenRootsRoundTrip() = fixture { _, store ->
        store.save(AppSettingsStore.Snapshot(instancesRoot = "/storage/emulated/0/Tavern"))
        assertEquals("/storage/emulated/0/Tavern", store.load().instancesRoot)
        store.save(AppSettingsStore.Snapshot())
        assertNull(store.load().instancesRoot)
    }

    @Test
    fun corruptDocumentsFallBackToDefaults() = fixture { root, store ->
        store.save(AppSettingsStore.Snapshot(instancesRoot = "/storage/emulated/0/Tavern"))
        store.file.writeText("{ not json ")
        assertNull(store.load().instancesRoot)
        store.file.writeText("x".repeat(128 * 1024))
        assertNull(store.load().instancesRoot)
        assertTrue(store.file.isFile)
        assertEquals(root.resolve("tarven"), store.file.parentFile)
    }
}
