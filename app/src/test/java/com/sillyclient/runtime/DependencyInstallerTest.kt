package com.sillyclient.runtime

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DependencyInstallerTest {
    private fun withRoot(test: (File) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent != null) Files.createTempDirectory(parent.toPath(), "dependency-install-").toFile()
            else Files.createTempDirectory("dependency-install-").toFile()
        try { test(root) } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    private fun packageFile(root: File, name: String): File = File(root, "node_modules/$name/package.json").apply {
        parentFile!!.mkdirs()
        writeText(JSONObject().put("name", name).put("version", "1.0.0").toString())
    }

    @Test
    fun commandsUseTheBundledNodeAndVerifiedCacheWithOnlyTheOfficialRegistry() = withRoot { root ->
        val node = File(root, "native/node")
        val npm = File(root, "npm cli.js")
        val instance = File(root, "Instance With Spaces")
        val cache = File(root, "shared cache")
        assertEquals(listOf("https://registry.npmjs.org"), DependencyInstaller.REGISTRIES)
        for (registry in DependencyInstaller.REGISTRIES) {
            for (subcommand in DependencyInstaller.SUBCOMMANDS) {
                val command = DependencyInstaller.command(node, npm, instance, cache, registry, subcommand)
                assertEquals(node.absolutePath, command[0])
                assertEquals("--max-old-space-size=512", command[1])
                assertEquals(npm.absolutePath, command[2])
                assertEquals(subcommand, command[3])
                for (flag in listOf("--omit=dev", "--no-audit", "--no-fund", "--prefer-offline", "--bin-links=false",
                    "--timing", "--loglevel=http", "--fetch-retries=2", "--fetch-timeout=20000",
                    "--fetch-retry-mintimeout=1000", "--fetch-retry-maxtimeout=3000")) {
                    assertTrue("Missing $flag", flag in command)
                }
                assertEquals(cache.absolutePath, command[command.indexOf("--cache") + 1])
                assertEquals(instance.absolutePath, command[command.indexOf("--prefix") + 1])
                assertEquals(registry, command[command.indexOf("--registry") + 1])
                assertFalse(command.any { it == "--force" || it == "--strict-ssl=false" || it == "--offline" ||
                    it.startsWith("--min-release-age") || it.startsWith("--ignore-scripts") })
            }
        }
    }

    @Test
    fun cleanInstallIsPreferredOnlyWhenALockFileExists() = withRoot { root ->
        assertEquals("install", DependencyInstaller.preferredSubcommand(root))
        File(root, "package-lock.json").writeText("""{"name":"synthetic","lockfileVersion":3}""")
        assertEquals("ci", DependencyInstaller.preferredSubcommand(root))
        File(root, "package-lock.json").delete()
        assertEquals("install", DependencyInstaller.preferredSubcommand(root))
    }

    @Test
    fun partialDependencyTreesUseRepairInstallWithoutDiscardingExistingFiles() = withRoot { root ->
        File(root, "package-lock.json").writeText("""{"lockfileVersion":3}""")
        val modules = File(root, "node_modules").apply { mkdirs() }
        assertEquals("ci", DependencyInstaller.preferredSubcommand(root))
        val partial = File(modules, "partial-file").apply { writeText("keep") }
        assertEquals("install", DependencyInstaller.preferredSubcommand(root))
        assertEquals("keep", partial.readText())
    }

    @Test
    fun knownManifestLockMismatchSkipsTheRejectedCiAttemptWithoutRewritingEitherFile() = withRoot { root ->
        val manifest = File(root, "package.json")
        val lock = File(root, "package-lock.json")
        for (field in listOf("dependencies", "devDependencies", "optionalDependencies")) {
            val current = JSONObject().put(field, JSONObject().put("yaml", "^2.0.0"))
            manifest.writeText(current.toString())
            lock.writeText(JSONObject().put("packages", JSONObject().put("", current)).toString())
            assertEquals("ci", DependencyInstaller.preferredSubcommand(root))
            val originalLock = lock.readText()
            manifest.writeText(JSONObject().put(field, JSONObject().put("yaml", "^3.0.0")).toString())
            val originalManifest = manifest.readText()
            assertTrue(DependencyInstaller.lockManifestMismatch(root))
            assertEquals("install", DependencyInstaller.preferredSubcommand(root))
            assertEquals(originalManifest, manifest.readText())
            assertEquals(originalLock, lock.readText())
        }
    }

    @Test
    fun lockDependencyAdditionsAndRemovalsAreDetectedButOrderingIsIgnored() = withRoot { root ->
        val manifest = File(root, "package.json")
        File(root, "package-lock.json").writeText("""{"packages":{"":{"dependencies":{"yaml":"2","express":"5"}}}}""")
        manifest.writeText("""{"dependencies":{"express":"5","yaml":"2"}}""")
        assertEquals("ci", DependencyInstaller.preferredSubcommand(root))
        for (text in listOf("""{"dependencies":{"yaml":"2"}}""", """{}""",
            """{"dependencies":{"express":"5","yaml":"2","extra":"1"}}""")) {
            manifest.writeText(text)
            assertEquals("install", DependencyInstaller.preferredSubcommand(root))
        }
    }

    @Test
    fun unknownAndInvalidLockMetadataIsLeftForNpmToValidate() = withRoot { root ->
        File(root, "package.json").writeText("""{"dependencies":{"yaml":"2"}}""")
        for (text in listOf("not json", """{"lockfileVersion":1}""", """{"packages":{"":{"dependencies":[]}}}""")) {
            File(root, "package-lock.json").writeText(text)
            assertFalse(DependencyInstaller.lockManifestMismatch(root))
            assertEquals("ci", DependencyInstaller.preferredSubcommand(root))
        }
    }

    @Test
    fun shrinkwrapTakesPrecedenceOverPackageLockJustAsItDoesInNpm() = withRoot { root ->
        File(root, "package.json").writeText("""{"dependencies":{"yaml":"2"}}""")
        File(root, "package-lock.json").writeText("""{"packages":{"":{"dependencies":{"yaml":"1"}}}}""")
        val shrinkwrap = File(root, "npm-shrinkwrap.json")
        shrinkwrap.writeText("""{"packages":{"":{"dependencies":{"yaml":"2"}}}}""")
        assertEquals("ci", DependencyInstaller.preferredSubcommand(root))
        File(root, "package-lock.json").delete()
        assertEquals("ci", DependencyInstaller.preferredSubcommand(root))
        shrinkwrap.writeText("""{"packages":{"":{"dependencies":{"yaml":"3"}}}}""")
        assertEquals("install", DependencyInstaller.preferredSubcommand(root))
    }

    @Test
    fun commandRejectsUnapprovedSubcommands() = withRoot { root ->
        for (subcommand in listOf("exec", "run", "test", "", "install --force")) {
            assertThrows(IllegalArgumentException::class.java) {
                DependencyInstaller.command(File(root, "node"), File(root, "npm"), root,
                    File(root, "cache"), DependencyInstaller.REGISTRIES.first(), subcommand)
            }
        }
    }

    @Test
    fun commandRejectsUnapprovedRegistries() = withRoot { root ->
        for (registry in listOf("http://unknown.test", "https://registry.npmmirror.com", "http://registry.npmjs.org")) {
            assertThrows(IllegalArgumentException::class.java) {
                DependencyInstaller.command(File(root, "node"), File(root, "npm"), root, File(root, "cache"), registry)
            }
        }
    }

    @Test
    fun officialRegistryNetworkErrorsAreRecognizedForActionableFailureMessages() {
        for (code in listOf("E403", "E404", "E408", "E429", "E502", "E503", "E504", "ECONNRESET",
            "ECONNREFUSED", "ETIMEDOUT", "EAI_AGAIN", "ENOTFOUND", "EHOSTUNREACH", "ERR_SOCKET_TIMEOUT",
            "FETCH_ERROR", "ECONNABORTED", "ENETUNREACH")) {
            assertTrue(code, DependencyInstaller.isNetworkFailure("npm error code $code"))
            assertTrue(code, DependencyInstaller.isNetworkFailure("npm ERR! code ${code.lowercase()}"))
        }
    }

    @Test
    fun localStorageManifestAndIntegrityErrorsAreNotReportedAsNetworkFailures() {
        for (line in listOf("npm error code ENOSPC", "npm error code EACCES", "npm error code EINTEGRITY",
            "npm error code EJSONPARSE", "npm error code ERESOLVE", "npm error code EBADENGINE", "added 200 packages")) {
            assertFalse(line, DependencyInstaller.isNetworkFailure(line))
        }
    }

    @Test
    fun networkCodesInUrlsPathsOrOrdinaryOutputDoNotChangeTheFailureCategory() {
        for (line in listOf("npm http fetch GET 200 https://mirror.test/ENOTFOUND 10ms (cache miss)",
            "npm error path /private/E404/package.json", "npm error code EINTEGRITY caused by E404",
            "log mentions ETIMEDOUT", "npm timing reifyNode:node_modules/ECONNRESET Completed in 2ms")) {
            assertFalse(line, DependencyInstaller.isNetworkFailure(line))
        }
    }

    @Test
    fun onlyAnExplicitCiLockMismatchCanFallBackToInstall() {
        assertEquals(600_000L, DependencyInstaller.INSTALL_TIMEOUT_MILLIS)
        for (prefix in listOf("npm error", "npm ERR!")) {
            val failure = NpmFailure()
            failure.accept("$prefix code EUSAGE")
            assertFalse(failure.shouldUseInstall("ci", ProcessSupervisor.Result(1, false)))
            failure.accept("$prefix $LOCK_MISMATCH")
            assertTrue(failure.shouldUseInstall("ci", ProcessSupervisor.Result(1, false)))
            assertFalse(failure.shouldUseInstall("install", ProcessSupervisor.Result(1, false)))
            for (result in listOf(ProcessSupervisor.Result(null, true), ProcessSupervisor.Result(0, false),
                ProcessSupervisor.Result(null, false), ProcessSupervisor.Result(1, true))) {
                assertFalse(failure.shouldUseInstall("ci", result))
            }
        }
    }

    @Test
    fun networkStorageAndUnrelatedUsageFailuresNeverStartASecondInstaller() {
        for (code in listOf("ETIMEDOUT", "ENOSPC", "EACCES", "EINTEGRITY", "EJSONPARSE", "EBADENGINE")) {
            val failure = NpmFailure()
            failure.accept("npm error code $code")
            failure.accept("npm error $LOCK_MISMATCH")
            assertFalse(code, failure.shouldUseInstall("ci", ProcessSupervisor.Result(1, false)))
        }
        for (message in listOf("Usage: npm ci", "The npm ci command can only install with an existing package-lock.json",
            "npm error path /private/EUSAGE/package-lock.json", "http fetch $LOCK_MISMATCH")) {
            val failure = NpmFailure()
            failure.accept("npm error code EUSAGE")
            failure.accept(message)
            assertFalse(message, failure.shouldUseInstall("ci", ProcessSupervisor.Result(1, false)))
        }
    }

    @Test
    fun networkFailureOverridesAnAmbiguousMixedErrorLog() {
        val failure = NpmFailure()
        failure.accept("npm error code EUSAGE")
        failure.accept("npm error $LOCK_MISMATCH")
        failure.accept("npm error code ECONNRESET")
        assertTrue(failure.networkFailure)
        assertFalse(failure.shouldUseInstall("ci", ProcessSupervisor.Result(1, false)))
    }

    private fun withInstallerPaths(test: (RuntimePaths, File) -> Unit) = withRoot { root ->
        requireSymlinks(root)
        val home = File(root, "private")
        val usr = File(home, "usr")
        val native = File(root, "native").apply { mkdirs() }
        val node = File(native, "node").apply { writeText("fixture") }
        File(usr, "lib/node_modules/npm/bin/npm-cli.js").apply { parentFile!!.mkdirs(); writeText("fixture") }
        val directory = File(root, "instance").apply { mkdirs() }
        File(directory, "package.json").writeText("{}")
        File(directory, "package-lock.json").writeText("""{"lockfileVersion":1}""")
        test(RuntimePaths(root, home, File(home, "bootstrap"), File(home, "servers"), usr, File(usr, "lib"),
            File(home, "tmp"), File(home, "logs"), native, node), directory)
    }

    private class CompletedNpmProcess(
        output: String,
        private val result: Int,
        private val onWait: (Long) -> Unit
    ) : Process() {
        private val stdout = ByteArrayInputStream(output.toByteArray())
        @Volatile private var alive = true
        override fun getInputStream() = stdout
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun isAlive() = alive
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else result
        override fun waitFor(): Int { alive = false; return result }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            onWait(unit.toMillis(timeout))
            alive = false
            return true
        }
        override fun destroy() { alive = false }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test
    fun lockFallbackReceivesOnlyTheRemainingTotalBudgetAndReusesCompileCache() = withInstallerPaths { paths, directory ->
        val now = AtomicLong(0L)
        val budgets = mutableListOf<Long>()
        val commands = mutableListOf<List<String>>()
        OperationCoordinator().use { operations ->
            ProcessSupervisor(operations).use { supervisor ->
                val installer = DependencyInstaller(paths, operations, supervisor, {}, nanoTime = now::get,
                    startProcess = { builder ->
                        commands.add(builder.command())
                        assertEquals(File(paths.tarvenHome, "node-compile-cache").absolutePath,
                            builder.environment()["NODE_COMPILE_CACHE"])
                        val first = commands.size == 1
                        if (!first) File(directory, "node_modules").mkdirs()
                        CompletedNpmProcess(if (first) "npm error code EUSAGE\nnpm error $LOCK_MISMATCH\n" else "",
                            if (first) 1 else 0) { budget ->
                            budgets.add(budget)
                            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(if (first) 50_000 else 10))
                        }
                    })
                assertTrue(installer.install(directory, operations.begin("fixture")))
                assertEquals(listOf("ci", "install"), commands.map { it[3] })
                assertEquals(listOf(600_000L, 550_000L), budgets)
            }
        }
    }

    @Test
    fun exhaustedBudgetNeverLaunchesTheFallbackInstaller() = withInstallerPaths { paths, directory ->
        val now = AtomicLong(0L)
        var launches = 0
        val logs = mutableListOf<String>()
        OperationCoordinator().use { operations ->
            ProcessSupervisor(operations).use { supervisor ->
                val installer = DependencyInstaller(paths, operations, supervisor, logs::add, nanoTime = now::get,
                    startProcess = {
                        launches++
                        CompletedNpmProcess("npm error code EUSAGE\nnpm error $LOCK_MISMATCH\n", 1) {
                            now.set(TimeUnit.MILLISECONDS.toNanos(600_001))
                        }
                    })
                assertFalse(installer.install(directory, operations.begin("fixture")))
                assertEquals(1, launches)
                assertTrue(logs.last().contains("total time budget"))
            }
        }
    }

    @Test
    fun networkAndDiskFailuresDoNotStartASecondWriter() = withInstallerPaths { paths, directory ->
        for (code in listOf("ETIMEDOUT", "ENOSPC", "EACCES", "EINTEGRITY")) {
            var launches = 0
            val logs = mutableListOf<String>()
            OperationCoordinator().use { operations ->
                ProcessSupervisor(operations).use { supervisor ->
                    val installer = DependencyInstaller(paths, operations, supervisor, logs::add,
                        startProcess = {
                            launches++
                            CompletedNpmProcess("npm error code $code\n", 1) {}
                        })
                    assertFalse(installer.install(directory, operations.begin("fixture")))
                    assertEquals(code, 1, launches)
                    assertEquals(code == "ETIMEDOUT", logs.last().contains("Official npm registry"))
                }
            }
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

    @Test
    fun missingAliasIsCreatedForTheBundledNodeAndRepeatedPreparationIsIdempotent() = withRoot { root ->
        requireSymlinks(root)
        val binary = File(root, "native-new/libtarven-node.so").apply { parentFile!!.mkdirs(); writeText("new") }
        val home = File(root, "private")
        val alias = BundledNodeAlias.ensure(home, binary)
        assertTrue(Files.isSymbolicLink(alias.toPath()))
        assertEquals(binary.toPath(), Files.readSymbolicLink(alias.toPath()))
        assertEquals(alias, BundledNodeAlias.ensure(home, binary))
        assertEquals(listOf("node"), alias.parentFile!!.list()!!.toList())
    }

    @Test
    fun apkUpgradeReplacesTheOldNativeLibraryAliasWithoutChangingEitherBinary() = withRoot { root ->
        requireSymlinks(root)
        val oldBinary = File(root, "native-old/libtarven-node.so").apply { parentFile!!.mkdirs(); writeText("old") }
        val newBinary = File(root, "native-new/libtarven-node.so").apply { parentFile!!.mkdirs(); writeText("new") }
        val home = File(root, "private")
        val alias = BundledNodeAlias.ensure(home, oldBinary)
        BundledNodeAlias.ensure(home, newBinary)
        assertEquals(newBinary.toPath(), Files.readSymbolicLink(alias.toPath()))
        assertEquals("old", oldBinary.readText())
        assertEquals("new", newBinary.readText())
        assertEquals(listOf("node"), alias.parentFile!!.list()!!.toList())
    }

    @Test
    fun danglingAliasAfterApkUpgradeIsRefreshed() = withRoot { root ->
        requireSymlinks(root)
        val oldBinary = File(root, "native-old/libtarven-node.so").apply { parentFile!!.mkdirs(); writeText("old") }
        val newBinary = File(root, "native-new/libtarven-node.so").apply { parentFile!!.mkdirs(); writeText("new") }
        val home = File(root, "private")
        val alias = BundledNodeAlias.ensure(home, oldBinary)
        assertTrue(oldBinary.delete())
        assertFalse(alias.exists())
        assertTrue(Files.exists(alias.toPath(), LinkOption.NOFOLLOW_LINKS))
        BundledNodeAlias.ensure(home, newBinary)
        assertEquals(newBinary.toPath(), Files.readSymbolicLink(alias.toPath()))
        assertEquals("new", alias.readText())
    }

    @Test
    fun ordinaryNodeFilesAndDirectoriesAreNeverReplaced() = withRoot { root ->
        val binary = File(root, "native/libtarven-node.so").apply { parentFile!!.mkdirs(); writeText("bundled") }
        for (directory in listOf(false, true)) {
            val home = File(root, "private-$directory")
            val node = File(home, "bin/node").apply { parentFile!!.mkdirs() }
            if (directory) node.mkdirs() else node.writeText("preserved")
            assertThrows(IllegalStateException::class.java) { BundledNodeAlias.ensure(home, binary) }
            if (directory) assertTrue(node.isDirectory) else assertEquals("preserved", node.readText())
            assertEquals(listOf("node"), node.parentFile!!.list()!!.toList())
        }
    }

    @Test
    fun linkedCommandDirectoriesAreNotFollowed() = withRoot { root ->
        requireSymlinks(root)
        val binary = File(root, "native/libtarven-node.so").apply { parentFile!!.mkdirs(); writeText("bundled") }
        val home = File(root, "private").apply { mkdirs() }
        val outside = File(root, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(home, "bin").toPath(), outside.toPath())
        assertThrows(IllegalStateException::class.java) { BundledNodeAlias.ensure(home, binary) }
        assertTrue(outside.list()!!.isEmpty())
    }

    @Test
    fun unavailableNewRuntimePreservesThePreviousAlias() = withRoot { root ->
        requireSymlinks(root)
        val oldBinary = File(root, "native-old/libtarven-node.so").apply { parentFile!!.mkdirs(); writeText("old") }
        val home = File(root, "private")
        val alias = BundledNodeAlias.ensure(home, oldBinary)
        assertThrows(IllegalStateException::class.java) {
            BundledNodeAlias.ensure(home, File(root, "native-missing/libtarven-node.so"))
        }
        assertEquals(oldBinary.toPath(), Files.readSymbolicLink(alias.toPath()))
        assertEquals("old", alias.readText())
    }

    @Test
    fun diagnosticsThrottleEventCountersAndFlushTheFinalResult() {
        var now = 0L
        val messages = mutableListOf<String>()
        val diagnostics = NpmDiagnostics(2, messages::add) { now }
        diagnostics.start()
        diagnostics.accept("npm http fetch GET 200 https://user:secret@private.test/a 10ms (cache miss)")
        now = 500_000_000L
        diagnostics.accept("npm timing reifyNode:node_modules/private-package Completed in 151ms")
        assertEquals(1, messages.size)
        now = 1_000_000_000L
        diagnostics.accept("npm http cache https://private.test/package 0ms (cache hit)")
        assertEquals(2, messages.size)
        assertTrue(messages.last().contains("registry=2 event=progress elapsed_ms=1000 phase=reifyNode phase_ms=151"))
        now = 1_500_000_000L
        diagnostics.accept("npm http fetch GET 200 https://private.test/package 0ms (cache hit)")
        assertEquals(2, messages.size)
        diagnostics.finish(ProcessSupervisor.Result(0, false))
        assertEquals(3, messages.size)
        assertTrue(messages.last().contains("http_events=2 cache_events=2 idle_ms=0 exit=0 timeout=false cancelled=false"))
        assertTrue(messages.last().contains("elapsed_ms=1500"))
        now = 3_000_000_000L
        diagnostics.accept("npm http fetch GET 200 https://private.test/late 1ms (cache miss)")
        diagnostics.finish(ProcessSupervisor.Result(1, false))
        assertEquals(3, messages.size)
        assertFalse(messages.joinToString().contains("secret"))
        assertFalse(messages.joinToString().contains("private"))
    }

    @Test
    fun diagnosticsOnlyPublishFixedPhaseLabelsAndNeverEchoUntrustedContent() {
        var now = 0L
        val messages = mutableListOf<String>()
        val diagnostics = NpmDiagnostics(1, messages::add) { now }
        diagnostics.start()
        val inputs = listOf(
            "npm timing idealTree:/data/user/0/private-secret Completed in 200ms",
            "npm timing reifyNode:C:\\private-secret\\node_modules Completed in 300ms",
            "npm timing https://user:private-secret@example.test/ Completed in 12ms",
            "npm timing private-secret Completed in 30ms",
            "npm timing reify Completed in -1ms",
            "npm timing reify Completed in 999999999999999999999999ms",
            "npm error Authorization: Bearer private-secret",
            "npm http fetch GET 200 https://private-secret@example.test/ 1ms (cache miss)"
        )
        for (line in inputs) {
            now += 1_000_000_000L
            diagnostics.accept(line)
        }
        diagnostics.finish(ProcessSupervisor.Result(null, true))
        assertTrue(messages.any { it.contains("phase=idealTree phase_ms=200") })
        assertTrue(messages.any { it.contains("phase=reifyNode phase_ms=300") })
        assertTrue(messages.last().endsWith("exit=none timeout=true cancelled=false"))
        val shape = Regex("npm registry=[12] event=(?:start|progress|finish) elapsed_ms=[0-9]+ " +
            "phase=(?:initializing|idealTree|reifyNode) phase_ms=[0-9]+ http_events=[0-9]+ cache_events=[0-9]+ idle_ms=[0-9]+" +
            "(?: exit=(?:none|[0-9]+) timeout=(?:true|false) cancelled=(?:true|false))?")
        assertTrue(messages.all { shape.matches(it) })
        assertFalse(messages.joinToString().contains("private-secret"))
    }

    @Test
    fun diagnosticSinkFailureCannotInterruptDependencyInstallation() {
        var calls = 0
        val diagnostics = NpmDiagnostics(1, { calls++; error("unavailable logger") }) { 0L }
        diagnostics.start()
        diagnostics.accept("npm timing idealTree Completed in 4ms")
        diagnostics.finish(ProcessSupervisor.Result(1, false))
        assertEquals(2, calls)
    }

    @Test
    fun quietInstallationsReportTheLastCompletedPhaseWithoutScanningOrClaimingADeadlock() {
        var now = 0L
        val messages = mutableListOf<String>()
        val waiting = mutableListOf<Pair<Long, Long>>()
        val diagnostics = NpmDiagnostics(1, messages::add, onWaiting = { elapsed, idle ->
            waiting.add(elapsed to idle)
        }) { now }
        diagnostics.start()
        diagnostics.accept("npm timing reify:diffTrees Completed in 235ms")
        now = 14_000_000_000L
        diagnostics.heartbeat()
        assertEquals(1, messages.size)
        assertTrue(waiting.isEmpty())
        now = 15_000_000_000L
        diagnostics.heartbeat()
        assertEquals(2, messages.size)
        assertTrue(messages.last().contains("event=waiting elapsed_ms=15000 phase=reify:diffTrees"))
        assertTrue(messages.last().endsWith("idle_ms=15000"))
        assertEquals(listOf(15_000L to 15_000L), waiting)
        now = 20_000_000_000L
        diagnostics.accept("some private lifecycle output")
        now = 30_000_000_000L
        diagnostics.heartbeat()
        assertTrue(messages.last().endsWith("idle_ms=10000"))
        assertEquals(listOf(15_000L to 15_000L, 30_000L to 10_000L), waiting)
        diagnostics.finish(ProcessSupervisor.Result(0, false))
        val count = messages.size
        now = 60_000_000_000L
        diagnostics.heartbeat()
        assertEquals(count, messages.size)
        assertEquals(2, waiting.size)
        assertFalse(messages.joinToString().contains("private"))
    }

    @Test
    fun waitingLoggerFailureDoesNotStopLaterDiagnostics() {
        var now = 0L
        var waitingCalls = 0
        val messages = mutableListOf<String>()
        val diagnostics = NpmDiagnostics(1, messages::add, onWaiting = { _, _ ->
            waitingCalls++
            error("unavailable UI logger")
        }) { now }
        diagnostics.start()
        now = 15_000_000_000L
        diagnostics.heartbeat()
        now = 30_000_000_000L
        diagnostics.heartbeat()
        diagnostics.finish(ProcessSupervisor.Result(0, false))
        assertEquals(2, waitingCalls)
        assertEquals(4, messages.size)
        assertTrue(messages.last().contains("exit=0"))
    }

    @Test
    fun cancelledInstallationsAreNotRecordedAsTimeoutsOrUnexplainedFailures() {
        val messages = mutableListOf<String>()
        val diagnostics = NpmDiagnostics(1, messages::add) { 0L }
        diagnostics.start()
        diagnostics.finish(ProcessSupervisor.Result(null, false), cancelled = true)
        assertTrue(messages.last().endsWith("exit=none timeout=false cancelled=true"))
    }

    @Test
    fun verboseDiagnosticLinesDoNotReplaceExistingFrontendErrors() {
        assertTrue(DependencyInstaller.isDiagnosticLine("npm timing reify Completed in 200ms"))
        assertTrue(DependencyInstaller.isDiagnosticLine("npm http fetch GET 200 https://example.test/ 1ms"))
        assertFalse(DependencyInstaller.isDiagnosticLine("npm error code ENOSPC"))
        assertFalse(DependencyInstaller.isDiagnosticLine("npm WARN EBADENGINE Unsupported engine"))
        assertFalse(DependencyInstaller.isDiagnosticLine("added 200 packages in 30s"))
    }

    @Test
    fun requiresEveryDirectPackageIncludingScopedNamesWithoutCheckingDevelopmentPackages() = withRoot { root ->
        File(root, "package.json").writeText("""{"dependencies":{"yaml":"^2.0.0","@scope/parser":"1.0.0"},"devDependencies":{"eslint":"*"}}""")
        packageFile(root, "yaml")
        assertFalse(DependencyInstaller.hasRequiredPackages(root))
        val scoped = packageFile(root, "@scope/parser")
        assertTrue(DependencyInstaller.hasRequiredPackages(root))
        assertTrue(scoped.delete())
        assertFalse(DependencyInstaller.hasRequiredPackages(root))
    }

    @Test
    fun restoredStagingModulesAreCheckedAgainstTheActualInstanceManifest() = withRoot { root ->
        File(root, "package.json").writeText("""{"dependencies":{"yaml":"2"}}""")
        packageFile(root, "yaml")
        val staged = File(root, "staged-modules").apply { mkdirs() }
        assertFalse(DependencyInstaller.hasRequiredPackages(root, staged))
        File(staged, "yaml/package.json").apply { parentFile!!.mkdirs(); writeText("{}") }
        assertTrue(DependencyInstaller.hasRequiredPackages(root, staged))
        File(root, "package.json").writeText("""{"dependencies":{"yaml":"2","express":"5"}}""")
        assertFalse(DependencyInstaller.hasRequiredPackages(root, staged))
    }

    @Test
    fun emptyDependencySetStillRequiresAModuleDirectory() = withRoot { root ->
        File(root, "package.json").writeText("""{"name":"synthetic"}""")
        assertFalse(DependencyInstaller.hasRequiredPackages(root))
        assertTrue(File(root, "node_modules").mkdirs())
        assertTrue(DependencyInstaller.hasRequiredPackages(root))
        File(root, "package.json").writeText("""{"dependencies":{}}""")
        assertTrue(DependencyInstaller.hasRequiredPackages(root))
    }

    @Test
    fun malformedOrOversizedManifestsAreNotTreatedAsComplete() = withRoot { root ->
        File(root, "node_modules").mkdirs()
        assertFalse(DependencyInstaller.hasRequiredPackages(root))
        for (text in listOf("", "{invalid", "[]", "null", "{\"dependencies\":[]}",
            "{\"dependencies\":null}", "{\"dependencies\":\"yaml\"}")) {
            File(root, "package.json").writeText(text)
            assertFalse(text, DependencyInstaller.hasRequiredPackages(root))
        }
        File(root, "package.json").writeText("{\"extra\":\"" + "x".repeat(4 * 1024 * 1024) + "\"}")
        assertFalse(DependencyInstaller.hasRequiredPackages(root))
    }

    @Test
    fun dependencyNamesCannotEscapeOrResolveToTheModulesRoot() = withRoot { root ->
        File(root, "node_modules").mkdirs()
        File(root, "node_modules/package.json").writeText("{}")
        for (name in listOf("..", ".", "../outside", "foo/bar", "@scope/..", "@scope/.", "foo\\bar", "C:escape")) {
            File(root, "package.json").writeText(JSONObject().put("dependencies", JSONObject().put(name, "1.0.0")).toString())
            assertFalse(name, DependencyInstaller.hasRequiredPackages(root))
        }
    }

    companion object {
        private const val LOCK_MISMATCH = "`npm ci` can only install packages when your package.json and " +
            "package-lock.json or npm-shrinkwrap.json are in sync. Please update your lock file with `npm install` before continuing."
    }
}
