package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class NativeTreeRemovalTest {
    private fun withRemoval(
        script: String = REMOVE_SCRIPT,
        idleTimeoutMillis: Long = 2_000,
        test: (File, NativeTreeRemoval, ProcessSupervisor) -> Unit
    ) {
        val node = System.getenv("SILLYCLIENT_CONFIGURATION_NODE")?.let(::File)
        assumeTrue("A local Node executable is required for controlled child process tests", node?.isFile == true)
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        val root = if (parent != null) {
            parent.mkdirs()
            Files.createTempDirectory(parent.toPath(), "sillyclient-removal-").toFile()
        } else Files.createTempDirectory("sillyclient-removal-").toFile()
        try {
            val scriptFile = File(root, ".native-removal-fixture.cjs").apply { writeText(script) }
            OperationCoordinator().use { operations ->
                ProcessSupervisor(operations).use { supervisor ->
                    val removal = NativeTreeRemoval(supervisor, idleTimeoutMillis,
                        listOf(requireNotNull(node).absolutePath, scriptFile.absolutePath))
                    test(root, removal, supervisor)
                }
            }
        } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    @Test
    fun literalPathsWithSpacesAndShellCharactersRemainSingleArguments() = withRemoval { root, removal, _ ->
        val marker = File(root, ".sc-identity").apply { writeText("owned") }
        val child = File(root, "user files (2) & dollar ${'$'}(whoami); name").apply { mkdirs() }
        File(child, "keep until removal.txt").writeText("synthetic")
        val progress = mutableListOf<NativeTreeRemoval.Progress>()
        val result = removal.remove(listOf(child), root, "test", onProgress = { progress.add(it) })
        assertFalse(child.exists())
        assertEquals("owned", marker.readText())
        assertEquals(1L, result.removedEntries)
        assertEquals(1, progress.size)
    }

    @Test
    fun refusesIdentityAndPathsOutsideTheImmediateOwner() = withRemoval { root, removal, _ ->
        val marker = File(root, ".sc-identity").apply { writeText("owned") }
        val nested = File(root, "nested/keep").apply { parentFile!!.mkdirs(); writeText("keep") }
        for (path in listOf(marker, nested, root, File(root, "../outside"))) {
            assertThrows(IllegalArgumentException::class.java) { removal.remove(listOf(path), root, "test") }
        }
        assertEquals("owned", marker.readText())
        assertEquals("keep", nested.readText())
    }

    @Test
    fun emptyTargetsDoNotStartAProcessOrRequireAnExistingDirectory() = withRemoval { root, removal, supervisor ->
        val result = removal.remove(emptyList(), File(root, "absent"), "test", onProgress = { error("Unexpected callback") })
        assertEquals(NativeTreeRemoval.Progress(0, 0), result)
        assertFalse(supervisor.hasProcesses())
        assertFalse(File(root, "absent").exists())
    }

    @Test
    fun successfulExitAndPrintedProgressCannotHideRemainingFiles() = withRemoval(
        "console.log(\"rm 'synthetic'\");"
    ) { root, removal, _ ->
        val child = File(root, "remaining").apply { writeText("keep") }
        val error = assertThrows(IllegalStateException::class.java) { removal.remove(listOf(child), root, "test") }
        assertTrue(error.message!!.contains("残留文件"))
        assertEquals("keep", child.readText())
    }

    @Test
    fun errorDiagnosticsAreBoundedAndDoNotExposePaths() = withRemoval(
        "for(let i=0;i<100;i++)console.error('rm: /private/secret-instance/data: EACCES Permission denied'); process.exitCode=1;"
    ) { root, removal, supervisor ->
        val marker = File(root, ".sc-identity").apply { writeText("owned") }
        val child = File(root, "keep").apply { writeText("keep") }
        val error = assertThrows(IllegalStateException::class.java) { removal.remove(listOf(child), root, "test") }
        assertTrue(error.message!!.contains("权限不足"))
        assertFalse(error.message!!.contains("secret-instance"))
        assertTrue(error.message!!.length < 200)
        assertEquals("owned", marker.readText())
        assertEquals("keep", child.readText())
        assertFalse(supervisor.hasProcesses())
    }

    @Test
    fun stalledRemovalPreservesIdentityAndConfirmsExit() = withRemoval(
        "setInterval(()=>{},1000);", idleTimeoutMillis = 150
    ) { root, removal, supervisor ->
        val marker = File(root, ".sc-identity").apply { writeText("owned") }
        val child = File(root, "keep").apply { writeText("keep") }
        val error = assertThrows(IllegalStateException::class.java) { removal.remove(listOf(child), root, "test") }
        assertTrue(error.message!!.contains("无进展"))
        assertEquals("owned", marker.readText())
        assertEquals("keep", child.readText())
        assertFalse(supervisor.hasProcesses())
    }

    @Test
    fun cancelledRemovalPreservesIdentityAndConfirmsExit() = withRemoval(
        "setInterval(()=>console.log(\"rm 'synthetic'\"),20);"
    ) { root, removal, supervisor ->
        val marker = File(root, ".sc-identity").apply { writeText("owned") }
        val child = File(root, "keep").apply { writeText("keep") }
        val cancelled = AtomicBoolean()
        assertThrows(CancellationException::class.java) {
            removal.remove(listOf(child), root, "test", ensureActive = {
                if (cancelled.get()) throw CancellationException("Synthetic cancellation")
            }, onProgress = { cancelled.set(true) })
        }
        assertEquals("owned", marker.readText())
        assertFalse(supervisor.hasProcesses())
    }

    @Test
    fun chattyNativeOutputProducesOnlyBoundedSummaryCallbacks() = withRemoval(
        "for(let i=0;i<4000;i++)console.log(\"rm 'synthetic'\");" + REMOVE_SCRIPT
    ) { root, removal, _ ->
        val child = File(root, "remove").apply { writeText("synthetic") }
        val progress = mutableListOf<NativeTreeRemoval.Progress>()
        val result = removal.remove(listOf(child), root, "test", onProgress = { progress.add(it) })
        assertEquals(4001L, result.removedEntries)
        assertTrue(progress.size <= result.elapsedMillis / 1_000 + 1)
        assertFalse(child.exists())
    }

    @Test
    fun batchesLargeArgumentListsWithoutTouchingTheOwnerMarker() = withRemoval { root, removal, _ ->
        val marker = File(root, ".sc-identity").apply { writeText("owned") }
        val children = (1..70).map { File(root, "synthetic-$it").apply { writeText("remove") } }
        val result = removal.remove(children, root, "test")
        assertEquals(70L, result.removedEntries)
        assertTrue(children.none { it.exists() })
        assertEquals("owned", marker.readText())
    }

    @Test
    fun childSymlinkIsRemovedWithoutFollowingItsTarget() = withRemoval { root, removal, _ ->
        val preserved = File(root, "preserved").apply { mkdirs() }
        val data = File(preserved, "history.jsonl").apply { writeText("keep") }
        val link = File(root, "link")
        try { Files.createSymbolicLink(link.toPath(), preserved.toPath()) }
        catch (error: Exception) { assumeNoException("The filesystem must support test symlinks", error) }
        removal.remove(listOf(link), root, "test")
        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertEquals("keep", data.readText())
    }

    companion object {
        private const val REMOVE_SCRIPT = """
            const fs=require('node:fs');
            const args=process.argv.slice(2);
            if(args[0]!=='-rfv'||args[1]!=='--')throw Error('Unexpected literal arguments');
            for(const path of args.slice(2)) {
                fs.rmSync(path,{recursive:true,force:true});
                console.log("rm '"+path+"'");
            }
        """
    }
}
