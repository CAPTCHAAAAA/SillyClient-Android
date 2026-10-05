package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RuntimeConfigurationTest {
    private fun withConfiguration(
        action: (File, RuntimeConfiguration, OperationCoordinator, OperationCoordinator.Operation) -> Unit
    ) {
        val node = System.getenv("SILLYCLIENT_CONFIGURATION_NODE")?.let(::File)
        val yaml = System.getenv("SILLYCLIENT_CONFIGURATION_YAML")?.let(::File)
        assumeTrue("Bundled Node and offline YAML fixtures are required", node?.isFile == true && yaml?.isDirectory == true)
        val runtimeNode = requireNotNull(node)
        val yamlFixture = requireNotNull(yaml)
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        val root = if (parent != null) {
            parent.mkdirs()
            Files.createTempDirectory(parent.toPath(), "sillyclient-config-").toFile()
        } else Files.createTempDirectory("sillyclient-config-").toFile()
        try {
            val server = File(root, "servers/test").apply { mkdirs() }
            check(yamlFixture.copyRecursively(File(server, "node_modules/yaml")))
            val paths = RuntimePaths(
                root, root, File(root, "bootstrap"), File(root, "servers"), File(root, "usr"),
                File(root, "usr/lib"), File(root, "tmp").apply { mkdirs() }, File(root, "logs"),
                requireNotNull(runtimeNode.parentFile), runtimeNode
            )
            OperationCoordinator().use { operations ->
                ProcessSupervisor(operations).use { supervisor ->
                    val operation = operations.begin("test")
                    action(server, RuntimeConfiguration(paths, operations, supervisor), operations, operation)
                }
            }
        } finally {
            if (parent != null) require(ManagedFiles.isWithin(root, parent))
            root.deleteRecursively()
        }
    }

    private fun readConfig(directory: File): JSONObject {
        val node = requireNotNull(System.getenv("SILLYCLIENT_CONFIGURATION_NODE"))
        val process = ProcessBuilder(node, "-e",
            "console.log(JSON.stringify(require('yaml').parse(require('node:fs').readFileSync('config.yaml', 'utf8'))));")
            .directory(directory).redirectErrorStream(true).start()
        try {
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0)
            return JSONObject(process.inputStream.bufferedReader().use { it.readText() })
        } finally { process.destroyForcibly() }
    }

    @Test
    fun updatesOnlyManagedKeysAndPreservesCommentsSecuritySettingsAndUserExtensions() = withConfiguration { directory, configuration, operations, operation ->
        val config = File(directory, "config.yaml").apply {
            writeText(
                """
                # User configuration comment
                port: 8000 # Preserve port comment
                dataRoot: ./data
                whitelistMode: false
                basicAuthMode: true
                basicAuthUser:
                  username: synthetic-user
                  password: synthetic-password
                unknownSetting:
                  nested: [1, 2, 3]
                protocol:
                  ipv4: false
                  ipv6: true # Preserve sibling comment
                  futureOption: keep
                browserLaunch:
                  enabled: true
                  hostname: custom.example
                """.trimIndent()
            )
        }
        val settings = File(directory, "data/default-user/settings.json").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("""{"theme":"User theme","extension_settings":{"disabledExtensions":["third-party/JS-Slash-Runner"]}}""")
        }
        val originalSettings = settings.readText()
        operations.run(operation) {
            configuration.validateStandardDataRoot(directory, operation)
            configuration.write(directory, JSONObject()
                .put("port", 9123).put("protocol.ipv4", true)
                .put("browserLaunch.enabled", false).put("enableKeepAlive", true), operation)
        }
        val result = config.readText()
        assertTrue(result.contains("# User configuration comment"))
        assertTrue(result.contains("port: 9123 # Preserve port comment"))
        val parsed = readConfig(directory)
        assertEquals("./data", parsed.getString("dataRoot"))
        assertFalse(parsed.getBoolean("whitelistMode"))
        assertTrue(parsed.getBoolean("basicAuthMode"))
        assertEquals("synthetic-user", parsed.getJSONObject("basicAuthUser").getString("username"))
        assertEquals("synthetic-password", parsed.getJSONObject("basicAuthUser").getString("password"))
        val nested = parsed.getJSONObject("unknownSetting").getJSONArray("nested")
        assertEquals(listOf(1, 2, 3), (0 until nested.length()).map(nested::getInt))
        assertTrue(parsed.getJSONObject("protocol").getBoolean("ipv4"))
        assertTrue(parsed.getJSONObject("protocol").getBoolean("ipv6"))
        assertEquals("keep", parsed.getJSONObject("protocol").getString("futureOption"))
        assertTrue(result.contains("# Preserve sibling comment"))
        assertFalse(parsed.getJSONObject("browserLaunch").getBoolean("enabled"))
        assertEquals("custom.example", parsed.getJSONObject("browserLaunch").getString("hostname"))
        assertTrue(parsed.getBoolean("enableKeepAlive"))
        assertFalse(parsed.has("protocolIPv4"))
        assertFalse(parsed.has("enableHttpKeepAlive"))
        assertEquals(originalSettings, settings.readText())
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".sillyclient-config-") })
    }

    @Test
    fun supportsFreshConfigurationWithTheStandardDataRoot() = withConfiguration { directory, configuration, operations, operation ->
        operations.run(operation) {
            configuration.validateStandardDataRoot(directory, operation)
            configuration.write(directory, JSONObject().put("port", 8001).put("listen", false)
                .put("protocol.ipv4", false).put("protocol.ipv6", true)
                .put("listenAddress.ipv4", "127.0.0.1").put("listenAddress.ipv6", "::1")
                .put("heartbeatInterval", 30).put("enableKeepAlive", false)
                .put("browserLaunch.enabled", false), operation)
        }
        val parsed = readConfig(directory)
        assertEquals(8001, parsed.getInt("port"))
        assertFalse(parsed.getBoolean("listen"))
        assertFalse(parsed.getJSONObject("protocol").getBoolean("ipv4"))
        assertTrue(parsed.getJSONObject("protocol").getBoolean("ipv6"))
        assertEquals("::1", parsed.getJSONObject("listenAddress").getString("ipv6"))
        assertEquals(30, parsed.getInt("heartbeatInterval"))
        assertFalse(parsed.getBoolean("enableKeepAlive"))
        assertFalse(File(directory, "data").exists())
    }

    @Test
    fun rejectsConflictingNestedConfigurationTypesWithoutOverwritingUserData() = withConfiguration { directory, configuration, operations, operation ->
        val config = File(directory, "config.yaml")
        for (content in listOf("protocol: false\n", "browserLaunch: [custom]\n")) {
            config.writeText(content)
            assertThrows(IllegalStateException::class.java) {
                operations.run(operation) {
                    configuration.write(directory, JSONObject().put("protocol.ipv4", true)
                        .put("browserLaunch.enabled", false), operation)
                }
            }
            assertEquals(content, config.readText())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".sillyclient-config-") })
        }
    }
    @Test
    fun rejectsCustomDataRootsBeforeChangingConfigOrData() = withConfiguration { directory, configuration, operations, operation ->
        val config = File(directory, "config.yaml").apply { writeText("dataRoot: ../shared-user-data\nport: 8123\n") }
        val before = config.readText()
        assertThrows(IllegalArgumentException::class.java) {
            operations.run(operation) { configuration.validateStandardDataRoot(directory, operation) }
        }
        assertEquals(before, config.readText())
        assertFalse(File(directory, "data").exists())
        assertFalse(File(directory.parentFile, "shared-user-data").exists())
    }

    @Test
    fun rejectsScalarAndSequenceYamlBeforePresetWritesAndPreservesTheOriginal() = withConfiguration { directory, configuration, operations, operation ->
        val config = File(directory, "config.yaml")
        for (content in listOf("scalar-value\n", "- array-value\n", "")) {
            config.writeText(content)
            assertThrows(IllegalStateException::class.java) {
                operations.run(operation) { configuration.validateStandardDataRoot(directory, operation) }
            }
            assertThrows(IllegalStateException::class.java) {
                operations.run(operation) { configuration.write(directory, JSONObject().put("port", 8123), operation) }
            }
            assertEquals(content, config.readText())
            assertFalse(File(directory, "data").exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".sillyclient-config-") })
        }
    }

    @Test
    fun rejectsInvalidYamlAndNonStringDataRootsWithoutOverwritingTheOriginal() = withConfiguration { directory, configuration, operations, operation ->
        val config = File(directory, "config.yaml")
        for (content in listOf("port: [invalid\n", "port: 8000\nport: 8123\n", "dataRoot: [data]\n")) {
            config.writeText(content)
            assertThrows(IllegalStateException::class.java) {
                operations.run(operation) { configuration.validateStandardDataRoot(directory, operation) }
            }
            assertEquals(content, config.readText())
            assertFalse(File(directory, "data").exists())
        }
    }

    @Test
    fun serverBuilderConfiguresAndImportsTheRealEsmEntryInTheSameProcess() = withConfiguration { directory, configuration, _, _ ->
        File(directory, "package.json").writeText("""{"name":"synthetic-tavern","type":"module"}""")
        File(directory, "config.yaml").writeText("# Keep my comment\nport: 8000\nunknownSetting: keep\n")
        File(directory, "server.js").writeText("""
            import fs from 'node:fs';
            import YAML from 'yaml';
            import { fileURLToPath } from 'node:url';
            console.log(JSON.stringify({
              pid: process.pid,
              argv: process.argv,
              cwd: process.cwd(),
              entry: fileURLToPath(import.meta.url),
              config: YAML.parse(fs.readFileSync('config.yaml', 'utf8')),
              payloadPresent: Object.hasOwn(process.env, 'SILLYCLIENT_CONFIG_VALUES')
            }));
        """.trimIndent())
        val builder = configuration.serverBuilder(directory, JSONObject().put("port", 9234)
            .put("browserLaunch.enabled", false).put("protocol.ipv4", true))
        assertTrue(builder.environment().containsKey("SILLYCLIENT_CONFIG_VALUES"))
        val process = builder.start()
        try {
            assertTrue("Synthetic server did not exit", process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.exitValue())
            val observed = JSONObject(output)
            val nativePid = Process::class.java.getMethod("pid").invoke(process) as Long
            assertEquals(nativePid, observed.getLong("pid"))
            val argv = observed.getJSONArray("argv")
            assertEquals(2, argv.length())
            assertEquals(File(requireNotNull(System.getenv("SILLYCLIENT_CONFIGURATION_NODE"))).canonicalFile,
                File(argv.getString(0)).canonicalFile)
            assertEquals(File(directory, "server.js").canonicalFile, File(argv.getString(1)).canonicalFile)
            assertEquals(directory.canonicalFile, File(observed.getString("cwd")).canonicalFile)
            assertEquals(File(directory, "server.js").canonicalFile, File(observed.getString("entry")).canonicalFile)
            assertFalse(observed.getBoolean("payloadPresent"))
            val configured = observed.getJSONObject("config")
            assertEquals(9234, configured.getInt("port"))
            assertFalse(configured.getJSONObject("browserLaunch").getBoolean("enabled"))
            assertTrue(configured.getJSONObject("protocol").getBoolean("ipv4"))
            assertEquals("keep", configured.getString("unknownSetting"))
            assertTrue(File(directory, "config.yaml").readText().contains("# Keep my comment"))
        } finally { process.destroyForcibly() }
    }

    @Test
    fun serverBuilderDoesNotRewriteAnUnchangedConfiguration() = withConfiguration { directory, configuration, _, _ ->
        File(directory, "package.json").writeText("""{"type":"module"}""")
        File(directory, "server.js").writeText("export const loaded = true;\n")
        val config = File(directory, "config.yaml")
        val original = "# Preserve exact bytes\nport: 8123 # chosen port\nbrowserLaunch:\n  enabled: false\n"
        config.writeText(original)
        assertTrue(config.setLastModified(1_234_567_000L))
        val originalModified = config.lastModified()
        val process = configuration.serverBuilder(directory, JSONObject().put("port", 8123)
            .put("browserLaunch.enabled", false)).start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.exitValue())
            assertEquals(original, config.readText())
            assertEquals(originalModified, config.lastModified())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".sillyclient-config-") })
        } finally { process.destroyForcibly() }
    }

    @Test
    fun serverBuilderNeverImportsTheServerWhenConfigurationIsInvalid() = withConfiguration { directory, configuration, _, _ ->
        File(directory, "package.json").writeText("""{"type":"module"}""")
        File(directory, "server.js").writeText("import fs from 'node:fs'; fs.writeFileSync('server-imported', 'bad');\n")
        val config = File(directory, "config.yaml")
        for (original in listOf("port: [broken\n", "- sequence\n", "browserLaunch: false\n")) {
            config.writeText(original)
            val process = configuration.serverBuilder(directory, JSONObject().put("port", 8123)
                .put("browserLaunch.enabled", false)).start()
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertTrue(output, process.exitValue() != 0)
                assertTrue(output.contains("Invalid instance configuration"))
                assertFalse(File(directory, "server-imported").exists())
                assertEquals(original, config.readText())
                assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".sillyclient-config-") })
            } finally { process.destroyForcibly() }
        }
    }
}
