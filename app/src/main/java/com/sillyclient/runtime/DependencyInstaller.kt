package com.sillyclient.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** npm owns resolution, integrity and its reusable content cache; instances never borrow live trees. */
class DependencyInstaller(
    private val paths: RuntimePaths,
    private val operations: OperationCoordinator,
    private val supervisor: ProcessSupervisor,
    private val log: (String) -> Unit,
    private val diagnostic: (String) -> Unit = {}
) {
    fun install(directory: File, operation: OperationCoordinator.Operation): Boolean {
        operations.ensureCurrent(operation)
        val npm = File(paths.usrDir, "lib/node_modules/npm/bin/npm-cli.js")
        check(npm.isFile) { "Bundled npm is unavailable" }
        val cache = File(paths.tarvenHome, "npm-cache").apply { mkdirs() }
        val temp = File(paths.tarvenHome, "npm-tmp").apply { mkdirs() }
        val bin = BundledNodeAlias.ensure(paths.tarvenHome, paths.nodeBin).parentFile!!
        paths.logsDir.mkdirs()
        val installLog = File(paths.logsDir, "npm-install.log")
        LogService.rotate(installLog)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(INSTALL_TIMEOUT_MILLIS)
        for ((index, registry) in REGISTRIES.withIndex()) {
            operations.ensureCurrent(operation)
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMillis <= 0) {
                log("[ERR] Dependency installation exhausted its total time budget")
                return false
            }
            var subcommand = preferredSubcommand(directory)
            while (true) {
                operations.ensureCurrent(operation)
                val diagnostics = NpmDiagnostics(index + 1, diagnostic)
                diagnostics.start()
                log("[npm] Running $subcommand with $registry (verified cache preferred)")
                val builder = ProcessBuilder(command(paths.nodeBin, npm, directory, cache, registry, subcommand))
                    .directory(directory).redirectErrorStream(true)
                builder.environment().apply {
                    put("LD_LIBRARY_PATH", "${paths.usrLibDir.absolutePath}:${paths.nativeLibDir.absolutePath}")
                    put("PATH", "${bin.absolutePath}:/system/bin")
                    put("HOME", paths.tarvenHome.absolutePath)
                    put("TMPDIR", temp.absolutePath)
                    put("npm_config_cache", cache.absolutePath)
                    put("npm_config_script_shell", "/system/bin/sh")
                }
                val process = operations.commit(operation) {
                    supervisor.track(builder.start(), operation.instanceId, operation)
                }
                var lastLog = 0L
                val networkFailure = java.util.concurrent.atomic.AtomicBoolean(false)
                val result = try {
                    supervisor.waitFor(process, remainingMillis) { line ->
                        if (operations.isCurrent(operation)) {
                            LogService.append(installLog, line)
                            diagnostics.accept(line)
                            if (isNetworkFailure(line)) networkFailure.set(true)
                            val now = System.nanoTime()
                            if (!isDiagnosticLine(line) &&
                                (now - lastLog >= 250_000_000 || line.contains("npm error", ignoreCase = true))) {
                                lastLog = now
                                log("[npm] ${line.take(240)}")
                            }
                        }
                    }
                } catch (error: Exception) {
                    diagnostics.finish(ProcessSupervisor.Result(null, false))
                    try { supervisor.stopAndWait(process) } catch (stopError: Exception) { error.addSuppressed(stopError) }
                    throw error
                }
                try {
                    if (result.timedOut) supervisor.stopAndWait(process)
                } finally {
                    diagnostics.finish(result)
                }
                operations.ensureCurrent(operation)
                if (result.timedOut) {
                    // A total deadline cannot distinguish a slow filesystem from a stalled network.
                    log("[ERR] Dependency installation timed out; see npm-install.log")
                    return false
                }
                if (result.exitCode == 0 && hasRequiredPackages(directory)) {
                    log("[OK] Dependencies installed and checked")
                    return true
                }
                if (shouldRetry(result, networkFailure.get())) {
                    log("[npm] Network failure; trying the next registry")
                    break
                }
                if (subcommand == "ci") {
                    // A lock out of sync with package.json rejects ci locally; install can resolve it.
                    log("[npm] Clean install was not accepted; retrying with install")
                    subcommand = "install"
                    continue
                }
                log("[ERR] Dependency installation failed (exit ${result.exitCode}); see npm-install.log")
                return false
            }
        }
        log("[ERR] All dependency sources failed; retry will reuse the verified npm cache")
        return false
    }

    companion object {
        internal const val INSTALL_TIMEOUT_MILLIS = 600_000L
        internal val REGISTRIES = listOf("https://registry.npmmirror.com", "https://registry.npmjs.org")
        internal val SUBCOMMANDS = setOf("install", "ci")
        private val NETWORK_ERROR = Regex(
            "^npm (?:error|ERR!) code (?:ECONNRESET|ECONNREFUSED|ETIMEDOUT|EAI_AGAIN|ENOTFOUND|EHOSTUNREACH|" +
                "ERR_SOCKET_TIMEOUT|FETCH_ERROR|E403|E404|E408|E429|E502|E503|E504|ECONNABORTED|ENETUNREACH)$",
            RegexOption.IGNORE_CASE
        )

        internal fun command(
            node: File,
            npm: File,
            directory: File,
            cache: File,
            registry: String,
            subcommand: String = "install"
        ): List<String> {
            require(registry in REGISTRIES) { "Unknown dependency registry" }
            require(subcommand in SUBCOMMANDS) { "Unknown dependency subcommand" }
            return listOf(node.absolutePath, "--max-old-space-size=512", npm.absolutePath,
                subcommand, "--omit=dev", "--no-audit", "--no-fund", "--prefer-offline", "--bin-links=false",
                "--timing", "--loglevel=http",
                "--fetch-retries=1", "--fetch-timeout=20000", "--fetch-retry-mintimeout=1000",
                "--fetch-retry-maxtimeout=3000", "--cache", cache.absolutePath,
                "--prefix", directory.absolutePath, "--registry", registry)
        }

        internal fun isNetworkFailure(line: String): Boolean = NETWORK_ERROR.matches(line.trim())

        /** npm ci skips resolution when a lock exists; a local rejection retries with install. */
        internal fun preferredSubcommand(directory: File): String =
            if (File(directory, "package-lock.json").isFile) "ci" else "install"

        internal fun shouldRetry(result: ProcessSupervisor.Result, networkFailure: Boolean): Boolean =
            !result.timedOut && result.exitCode != null && result.exitCode != 0 && networkFailure

        internal fun isDiagnosticLine(line: String): Boolean =
            line.startsWith("npm timing ") || line.startsWith("npm http ")

        internal fun hasRequiredPackages(directory: File): Boolean = runCatching {
            val manifest = File(directory, "package.json")
            require(manifest.isFile && manifest.length() <= 4 * 1024 * 1024)
            val document = JSONObject(manifest.readText())
            val dependencies = if (document.has("dependencies")) document.getJSONObject("dependencies") else JSONObject()
            val modules = File(directory, "node_modules")
            modules.isDirectory && dependencies.keys().asSequence().all { name ->
                require(name.matches(Regex("(?:@[a-zA-Z0-9_.-]+/)?[a-zA-Z0-9_.-]+")) &&
                    name.split('/').none { it in setOf(".", "..") })
                val entry = File(modules, "$name/package.json")
                ManagedFiles.isWithin(entry, modules) && entry.isFile
            }
        }.getOrDefault(false)
    }
}

/** Only fixed phase labels and numeric counters may leave the private npm log. */
internal class NpmDiagnostics(
    private val registryIndex: Int,
    private val emit: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val started = nanoTime()
    private var lastEmission = started
    private var httpEvents = 0L
    private var cacheEvents = 0L
    private var phase = "initializing"
    private var phaseMillis = 0L
    private var finished = false

    fun start() = publish("start")

    @Synchronized
    fun accept(line: String) {
        if (finished) return
        var changed = false
        if (line.startsWith("npm http fetch ")) {
            httpEvents++
            changed = true
        }
        if (line.startsWith("npm http cache ") ||
            (line.startsWith("npm http fetch ") && line.contains("(cache hit)"))) {
            cacheEvents++
            changed = true
        }
        TIMING.matchEntire(line)?.let { match ->
            val candidate = match.groupValues[1].substringBefore(':')
            val duration = match.groupValues[2].toLongOrNull()
            if (candidate in PHASES && duration != null) {
                phase = candidate
                phaseMillis = duration
                changed = true
            }
        }
        if (changed && nanoTime() - lastEmission >= 1_000_000_000L) publish("progress")
    }

    @Synchronized
    fun finish(result: ProcessSupervisor.Result) {
        if (finished) return
        finished = true
        publish("finish", " exit=${result.exitCode ?: "none"} timeout=${result.timedOut}")
    }

    private fun publish(event: String, result: String = "") {
        val now = nanoTime()
        lastEmission = now
        val elapsed = ((now - started) / 1_000_000L).coerceAtLeast(0)
        runCatching {
            emit("npm registry=$registryIndex event=$event elapsed_ms=$elapsed phase=$phase phase_ms=$phaseMillis" +
                " http_events=$httpEvents cache_events=$cacheEvents$result")
        }
    }

    companion object {
        private val TIMING = Regex("^npm timing (\\S+) Completed in ([0-9]{1,12})ms$")
        private val PHASES = setOf("npm", "command", "idealTree", "reify", "reifyNode", "build", "load", "arborist")
    }
}
