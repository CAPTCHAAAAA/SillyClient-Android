package com.sillyclient.runtime

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** npm owns resolution, integrity and its reusable content cache; instances never borrow live trees. */
class DependencyInstaller(
    private val paths: RuntimePaths,
    private val operations: OperationCoordinator,
    private val supervisor: ProcessSupervisor,
    private val log: (String) -> Unit,
    private val diagnostic: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    private val startProcess: (ProcessBuilder) -> Process = { it.start() }
) {
    fun install(directory: File, operation: OperationCoordinator.Operation): Boolean {
        operations.ensureCurrent(operation)
        val npm = File(paths.usrDir, "lib/node_modules/npm/bin/npm-cli.js")
        check(npm.isFile) { "Bundled npm is unavailable" }
        val cache = File(paths.tarvenHome, "npm-cache").apply { mkdirs() }
        val temp = File(paths.tarvenHome, "npm-tmp").apply { mkdirs() }
        val compileCache = File(paths.tarvenHome, "node-compile-cache").apply { mkdirs() }
        val bin = BundledNodeAlias.ensure(paths.tarvenHome, paths.nodeBin).parentFile!!
        paths.logsDir.mkdirs()
        val installLog = File(paths.logsDir, "npm-install.log")
        LogService.rotate(installLog)
        val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(INSTALL_TIMEOUT_MILLIS)
        val heartbeat = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "SillyClient-npm-diagnostics").apply { isDaemon = true }
        }
        val registry = REGISTRIES.single()
        var subcommand = preferredSubcommand(directory)
        try {
            while (true) {
                operations.ensureCurrent(operation)
                val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - nanoTime())
                if (remainingMillis <= 0) {
                    log("[ERR] Dependency installation exhausted its total time budget; see npm-install.log")
                    return false
                }
                val diagnostics = NpmDiagnostics(1, diagnostic, onWaiting = { elapsed, idle ->
                    log("[npm] 正在安装依赖，已用时 ${elapsed / 1000} 秒；最近 ${idle / 1000} 秒暂无新输出，请保持应用在前台")
                }, nanoTime = nanoTime)
                diagnostics.start()
                log("[npm] Running $subcommand with $registry (verified cache preferred)")
                val builder = ProcessBuilder(command(paths.nodeBin, npm, directory, cache, registry, subcommand))
                    .directory(directory).redirectErrorStream(true)
                builder.environment().apply {
                    put("LD_LIBRARY_PATH", "${paths.usrLibDir.absolutePath}:${paths.nativeLibDir.absolutePath}")
                    put("PATH", "${bin.absolutePath}:/system/bin")
                    put("HOME", paths.tarvenHome.absolutePath)
                    put("TMPDIR", temp.absolutePath)
                    put("NODE_COMPILE_CACHE", compileCache.absolutePath)
                    put("npm_config_cache", cache.absolutePath)
                    put("npm_config_script_shell", "/system/bin/sh")
                }
                val process = operations.commit(operation) {
                    supervisor.track(startProcess(builder), operation.instanceId, operation)
                }
                val pulse = heartbeat.scheduleAtFixedRate({
                    if (operations.isCurrent(operation)) diagnostics.heartbeat()
                }, 15, 15, TimeUnit.SECONDS)
                var lastLog = 0L
                val failure = NpmFailure()
                val result = try {
                    supervisor.waitFor(process, remainingMillis) { line ->
                        if (operations.isCurrent(operation)) {
                            LogService.append(installLog, line)
                            diagnostics.accept(line)
                            failure.accept(line)
                            val now = nanoTime()
                            if (!isDiagnosticLine(line) &&
                                (now - lastLog >= 250_000_000 || line.contains("npm error", ignoreCase = true))) {
                                lastLog = now
                                log("[npm] ${line.take(240)}")
                            }
                        }
                    }
                } catch (error: Exception) {
                    diagnostics.finish(ProcessSupervisor.Result(null, false),
                        error is CancellationException || error is InterruptedException || !operations.isCurrent(operation))
                    try { supervisor.stopAndWait(process) } catch (stopError: Exception) { error.addSuppressed(stopError) }
                    throw error
                } finally {
                    pulse.cancel(false)
                }
                try {
                    if (result.timedOut) supervisor.stopAndWait(process)
                } finally {
                    diagnostics.finish(result, !operations.isCurrent(operation))
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
                if (failure.shouldUseInstall(subcommand, result)) {
                    // A lock out of sync with package.json rejects ci locally; install can resolve it.
                    log("[npm] Package manifest and lock are out of sync; resolving with install")
                    subcommand = "install"
                    continue
                }
                log(if (failure.networkFailure) {
                    "[ERR] Official npm registry request failed after bounded retries; check network/proxy and retry. Verified cache is retained"
                } else if (result.exitCode == 0) {
                    "[ERR] npm finished but required instance dependencies are missing; see npm-install.log"
                } else {
                    "[ERR] Dependency installation failed (exit ${result.exitCode}); see npm-install.log"
                })
                return false
            }
        } finally {
            heartbeat.shutdownNow()
        }
    }

    companion object {
        internal const val INSTALL_TIMEOUT_MILLIS = 600_000L
        internal val REGISTRIES = listOf("https://registry.npmjs.org")
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
                "--fetch-retries=2", "--fetch-timeout=20000", "--fetch-retry-mintimeout=1000",
                "--fetch-retry-maxtimeout=3000", "--cache", cache.absolutePath,
                "--prefix", directory.absolutePath, "--registry", registry)
        }

        internal fun isNetworkFailure(line: String): Boolean = NETWORK_ERROR.matches(line.trim())

        /** Preserve partial trees: npm ci would recursively discard them before every retry. */
        internal fun preferredSubcommand(directory: File): String {
            val lock = File(directory, "npm-shrinkwrap.json").takeIf { it.isFile }
                ?: File(directory, "package-lock.json")
            if (!lock.isFile || File(directory, "node_modules").list()?.isNotEmpty() == true) return "install"
            return if (lockManifestMismatch(directory, lock)) "install" else "ci"
        }

        internal fun lockManifestMismatch(
            directory: File,
            lock: File = File(directory, "npm-shrinkwrap.json").takeIf { it.isFile }
                ?: File(directory, "package-lock.json")
        ): Boolean = runCatching {
            val manifest = File(directory, "package.json")
            require(manifest.length() <= 4 * 1024 * 1024 && lock.length() <= 16 * 1024 * 1024)
            val document = JSONObject(manifest.readText())
            val lockedRoot = JSONObject(lock.readText()).optJSONObject("packages")?.optJSONObject("")
                ?: return@runCatching false
            listOf("dependencies", "devDependencies", "optionalDependencies").any { field ->
                fun entries(json: JSONObject): Map<String, String> {
                    val values = if (json.has(field)) json.getJSONObject(field) else JSONObject()
                    return values.keys().asSequence().associateWith { values.getString(it) }
                }
                entries(document) != entries(lockedRoot)
            }
        }.getOrDefault(false)

        internal fun isDiagnosticLine(line: String): Boolean =
            line.startsWith("npm timing ") || line.startsWith("npm http ")

        internal fun hasRequiredPackages(directory: File): Boolean =
            hasRequiredPackages(directory, File(directory, "node_modules"))

        internal fun hasRequiredPackages(directory: File, modulesDirectory: File): Boolean = runCatching {
            val manifest = File(directory, "package.json")
            require(manifest.isFile && manifest.length() <= 4 * 1024 * 1024)
            val document = JSONObject(manifest.readText())
            val dependencies = if (document.has("dependencies")) document.getJSONObject("dependencies") else JSONObject()
            val modules = modulesDirectory
            modules.isDirectory && dependencies.keys().asSequence().all { name ->
                require(name.matches(Regex("(?:@[a-zA-Z0-9_.-]+/)?[a-zA-Z0-9_.-]+")) &&
                    name.split('/').none { it in setOf(".", "..") })
                val entry = File(modules, "$name/package.json")
                ManagedFiles.isWithin(entry, modules) && entry.isFile
            }
        }.getOrDefault(false)
    }
}

/** Retain only npm's fixed failure facts; package paths and credentials never enter diagnostics. */
internal class NpmFailure {
    var networkFailure = false
        private set
    private var usageError = false
    private var lockMismatch = false

    @Synchronized
    fun accept(line: String) {
        if (DependencyInstaller.isNetworkFailure(line)) networkFailure = true
        val message = ERROR_LINE.matchEntire(line.trim())?.groupValues?.get(1) ?: return
        if (message.equals("code EUSAGE", ignoreCase = true)) usageError = true
        if (message.startsWith("`npm ci` can only install packages when your package.json") &&
            message.contains("are in sync")) lockMismatch = true
    }

    @Synchronized
    fun shouldUseInstall(subcommand: String, result: ProcessSupervisor.Result): Boolean =
        subcommand == "ci" && result.exitCode != null && result.exitCode != 0 && !result.timedOut &&
            usageError && lockMismatch && !networkFailure

    companion object {
        private val ERROR_LINE = Regex("^npm (?:error|ERR!) (.*)$", RegexOption.IGNORE_CASE)
    }
}

/** Only fixed phase labels and numeric counters may leave the private npm log. */
internal class NpmDiagnostics(
    private val registryIndex: Int,
    private val emit: (String) -> Unit,
    private val onWaiting: (elapsedMillis: Long, idleMillis: Long) -> Unit = { _, _ -> },
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val started = nanoTime()
    private var lastEmission = started
    private var lastOutput = started
    private var httpEvents = 0L
    private var cacheEvents = 0L
    private var phase = "initializing"
    private var phaseMillis = 0L
    private var finished = false

    fun start() = publish("start")

    @Synchronized
    fun accept(line: String) {
        if (finished) return
        lastOutput = nanoTime()
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
            val raw = match.groupValues[1]
            val candidate = if (raw in PHASES) raw else raw.substringBefore(':')
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
    fun heartbeat() {
        if (!finished && nanoTime() - lastEmission >= 15_000_000_000L) publish("waiting")
    }

    @Synchronized
    fun finish(result: ProcessSupervisor.Result, cancelled: Boolean = false) {
        if (finished) return
        finished = true
        publish("finish", " exit=${result.exitCode ?: "none"} timeout=${result.timedOut} cancelled=$cancelled")
    }

    private fun publish(event: String, result: String = "") {
        val now = nanoTime()
        lastEmission = now
        val elapsed = ((now - started) / 1_000_000L).coerceAtLeast(0)
        val idle = ((now - lastOutput) / 1_000_000L).coerceAtLeast(0)
        runCatching {
            emit("npm registry=$registryIndex event=$event elapsed_ms=$elapsed phase=$phase phase_ms=$phaseMillis" +
                " http_events=$httpEvents cache_events=$cacheEvents idle_ms=$idle$result")
        }
        if (event == "waiting") runCatching { onWaiting(elapsed, idle) }
    }

    companion object {
        private val TIMING = Regex("^npm timing (\\S+) Completed in ([0-9]{1,12})ms$")
        private val PHASES = setOf("npm", "command", "idealTree", "reify", "reifyNode", "build", "load", "arborist",
            "reify:loadTrees", "reify:diffTrees", "reify:retireShallow", "reify:createSparse", "reify:unpack",
            "reify:unretire", "reify:build", "reify:trash", "reify:save", "build:run:preinstall",
            "build:run:install", "build:run:postinstall")
    }
}
