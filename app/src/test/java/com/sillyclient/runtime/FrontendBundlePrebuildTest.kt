package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontendBundlePrebuildTest {
    private fun withInstance(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent != null) Files.createTempDirectory(parent.toPath(), "prebuild-").toFile()
            else Files.createTempDirectory("prebuild-").toFile()
        try { test(root) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    @Test
    fun missingMarkerMeansNoBundle() = withInstance { root ->
        assertNull(FrontendBundlePrebuild.markedBundle(root))
    }

    @Test
    fun markerResolvesTheRecordedBundleInsideTheInstance() = withInstance { root ->
        val bundle = File(root, "data/_webpack/deadbeef/output/lib.js").apply {
            parentFile!!.mkdirs()
            writeText("export default {};")
        }
        File(root, FrontendBundlePrebuild.MARKER_NAME).writeText(bundle.absolutePath)
        assertEquals(bundle, FrontendBundlePrebuild.markedBundle(root))
    }

    @Test
    fun relativeRecordedPathsAndEscapingPathsAndVanishedFilesAreRejected() = withInstance { root ->
        val marker = File(root, FrontendBundlePrebuild.MARKER_NAME)
        marker.writeText("data/_webpack/deadbeef/output/lib.js")
        assertNull(FrontendBundlePrebuild.markedBundle(root))

        val outside = File(root.parentFile, "escaped-lib.js").apply { writeText("x") }
        marker.writeText(outside.absolutePath)
        assertNull(FrontendBundlePrebuild.markedBundle(root))

        val vanished = File(root, "data/_webpack/abcd/output/lib.js")
        vanished.parentFile!!.mkdirs()
        marker.writeText(vanished.absolutePath)
        assertNull(FrontendBundlePrebuild.markedBundle(root))
    }

    @Test
    fun emptyBundlesAreNotServedAsPrebuilt() = withInstance { root ->
        val bundle = File(root, "data/_webpack/feedface/output/lib.js").apply {
            parentFile!!.mkdirs()
            writeText("")
        }
        File(root, FrontendBundlePrebuild.MARKER_NAME).writeText(bundle.absolutePath)
        assertNull(FrontendBundlePrebuild.markedBundle(root))
    }

    /** The script runs in its own Node process; the marker name is its only channel back. */
    @Test
    fun scriptRecordsTheMarkerNameThatLookupExpects() {
        val script = FrontendBundlePrebuild::class.java.getDeclaredField("PREBUILD_SCRIPT")
            .apply { isAccessible = true }.get(null) as String
        assertTrue(script.contains(".sillyclient-prebuilt-lib"))
        assertFalse(script.contains(".sc-prebuilt"))
        assertTrue(script.contains("config.cache = false"))
        assertTrue(script.contains("modules: [treeNodeModules, 'node_modules']"))
    }
}
