package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
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
    fun commandsUseTheBundledNodeAndVerifiedCacheForBothRegistries() = withRoot { root ->
        val node = File(root, "native/node")
        val npm = File(root, "npm cli.js")
        val instance = File(root, "Instance With Spaces")
        val cache = File(root, "shared cache")
        assertEquals(listOf("https://registry.npmmirror.com", "https://registry.npmjs.org"), DependencyInstaller.REGISTRIES)
        for (registry in DependencyInstaller.REGISTRIES) {
            for (subcommand in DependencyInstaller.SUBCOMMANDS) {
                val command = DependencyInstaller.command(node, npm, instance, cache, registry, subcommand)
                assertEquals(node.absolutePath, command[0])
                assertEquals("--max-old-space-size=512", command[1])
                assertEquals(npm.absolutePath, command[2])
                assertEquals(subcommand, command[3])
                for (flag in listOf("--omit=dev", "--no-audit", "--no-fund", "--prefer-offline", "--bin-links=false",
                    "--timing", "--loglevel=http", "--fetch-retries=1", "--fetch-timeout=20000",
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
        assertThrows(IllegalArgumentException::class.java) {
            DependencyInstaller.command(File(root, "node"), File(root, "npm"), root, File(root, "cache"), "http://unknown.test")
        }
    }

    @Test
    fun unavailableMissingAndRateLimitedMirrorResponsesCanSwitchRegistry() {
        for (code in listOf("E403", "E404", "E408", "E429", "E502", "E503", "E504", "ECONNRESET",
            "ECONNREFUSED", "ETIMEDOUT", "EAI_AGAIN", "ENOTFOUND", "EHOSTUNREACH", "ERR_SOCKET_TIMEOUT",
            "FETCH_ERROR", "ECONNABORTED", "ENETUNREACH")) {
            assertTrue(code, DependencyInstaller.isNetworkFailure("npm error code $code"))
            assertTrue(code, DependencyInstaller.isNetworkFailure("npm ERR! code ${code.lowercase()}"))
        }
    }

    @Test
    fun localStorageManifestAndIntegrityErrorsDoNotTriggerBlindMirrorRetry() {
        for (line in listOf("npm error code ENOSPC", "npm error code EACCES", "npm error code EINTEGRITY",
            "npm error code EJSONPARSE", "npm error code ERESOLVE", "npm error code EBADENGINE", "added 200 packages")) {
            assertFalse(line, DependencyInstaller.isNetworkFailure(line))
        }
    }

    @Test
    fun networkCodesInUrlsPathsOrOrdinaryOutputDoNotTriggerRegistryRetry() {
        for (line in listOf("npm http fetch GET 200 https://mirror.test/ENOTFOUND 10ms (cache miss)",
            "npm error path /private/E404/package.json", "npm error code EINTEGRITY caused by E404",
            "log mentions ETIMEDOUT", "npm timing reifyNode:node_modules/ECONNRESET Completed in 2ms")) {
            assertFalse(line, DependencyInstaller.isNetworkFailure(line))
        }
    }

    @Test
    fun totalDeadlineAllowsSlowIoButNeverTreatsTimeoutAsARegistryFailure() {
        assertEquals(600_000L, DependencyInstaller.INSTALL_TIMEOUT_MILLIS)
        for (networkFailure in listOf(false, true)) {
            assertFalse(DependencyInstaller.shouldRetry(ProcessSupervisor.Result(null, true), networkFailure))
            assertFalse(DependencyInstaller.shouldRetry(ProcessSupervisor.Result(0, false), networkFailure))
            assertFalse(DependencyInstaller.shouldRetry(ProcessSupervisor.Result(null, false), networkFailure))
        }
        assertFalse(DependencyInstaller.shouldRetry(ProcessSupervisor.Result(1, false), false))
        assertTrue(DependencyInstaller.shouldRetry(ProcessSupervisor.Result(1, false), true))
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
        assertTrue(messages.last().contains("http_events=2 cache_events=2 exit=0 timeout=false"))
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
        assertTrue(messages.last().endsWith("exit=none timeout=true"))
        val shape = Regex("npm registry=[12] event=(?:start|progress|finish) elapsed_ms=[0-9]+ " +
            "phase=(?:initializing|idealTree|reifyNode) phase_ms=[0-9]+ http_events=[0-9]+ cache_events=[0-9]+" +
            "(?: exit=(?:none|[0-9]+) timeout=(?:true|false))?")
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
}
