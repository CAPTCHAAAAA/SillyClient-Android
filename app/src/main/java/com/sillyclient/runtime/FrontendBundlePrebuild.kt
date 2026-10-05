package com.sillyclient.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SillyTavern recompiles public/lib.js with webpack at every startup, and
 * webpack's own resolver only walks ancestor directories for a local
 * node_modules: it honors neither NODE_PATH nor the ESM bridge that serves
 * tree-backed instances, so the compile fails and the /lib.js route 404s into
 * a grey screen. The prebuild compiles the bundle once up front through the
 * shared tree, restoring the served file before the server starts.
 */
class FrontendBundlePrebuild(
    private val paths: RuntimePaths,
    private val operations: OperationCoordinator,
    private val supervisor: ProcessSupervisor,
    private val log: (String) -> Unit,
    private val diagnostic: (String) -> Unit = {},
    private val startProcess: (ProcessBuilder) -> Process = { it.start() }
) {
    fun ensure(
        instanceDirectory: File,
        treeModules: File,
        operation: OperationCoordinator.Operation,
        bridgePath: String
    ): Boolean {
        if (!File(instanceDirectory, "webpack.config.js").isFile) return true
        if (!treeModules.isDirectory) return true
        if (markedBundle(instanceDirectory) != null) return true
        val node = paths.nodeBin
        if (!node.isFile) {
            log("[WARN] 前端预构建已跳过：Node.js 运行时未就绪")
            return true
        }
        operations.ensureCurrent(operation)
        val script = writeScript()
        val builder = ProcessBuilder(
            node.absolutePath, script.absolutePath,
            instanceDirectory.absolutePath, treeModules.absolutePath
        ).apply {
            directory(instanceDirectory)
            redirectErrorStream(true)
            environment()["LD_LIBRARY_PATH"] = "${paths.usrLibDir.absolutePath}:${paths.nativeLibDir.absolutePath}"
            environment()["PATH"] = "${paths.tarvenHome.absolutePath}/bin:/system/bin"
            environment()["HOME"] = paths.tarvenHome.absolutePath
            environment()["TMPDIR"] = paths.tmpDir.absolutePath
            environment()["NODE_OPTIONS"] = "--max-old-space-size=2048 --import $bridgePath"
            environment()["NODE_COMPILE_CACHE"] = File(paths.tarvenHome, "node-compile-cache").absolutePath
            environment()["NODE_PATH"] = treeModules.absolutePath
            environment()["SILLYCLIENT_NODE_MODULES"] = treeModules.absolutePath
        }
        paths.logsDir.mkdirs()
        val prebuildLog = File(paths.logsDir, "prebuild.log")
        LogService.rotate(prebuildLog)
        val started = System.nanoTime()
        val process = operations.commit(operation) {
            supervisor.track(startProcess(builder), operation.instanceId, operation)
        }
        val result = supervisor.waitForIdle(
            process,
            idleTimeoutMillis = IDLE_TIMEOUT_MILLIS,
            ensureActive = { operations.ensureCurrent(operation) },
            onErrorLine = { line -> LogService.append(prebuildLog, line) },
            onLine = { line ->
                LogService.append(prebuildLog, line)
                line.isNotBlank()
            }
        )
        operations.ensureCurrent(operation)
        val exit = result.exitCode
        if (result.timedOut || exit == null) {
            log("[ERR] 前端依赖包预构建超时")
            return false
        }
        if (exit == EXIT_ALREADY_PRESENT) {
            runtimeDiagnostic("prebuild.present elapsed_ms=${elapsed(started)}")
            return true
        }
        if (exit != 0) {
            log("[ERR] 前端依赖包预构建失败 (code $exit)，详见 prebuild.log")
            runtimeDiagnostic("prebuild.failed code=$exit elapsed_ms=${elapsed(started)}")
            return false
        }
        if (markedBundle(instanceDirectory) == null) {
            log("[WARN] 前端依赖包预构建结果缺失，服务器将尝试自行编译")
            runtimeDiagnostic("prebuild.output_missing elapsed_ms=${elapsed(started)}")
            return false
        }
        log("[OK] 前端依赖包预构建完成")
        runtimeDiagnostic("prebuild.built elapsed_ms=${elapsed(started)}")
        return true
    }

    private fun writeScript(): File {
        val script = File(paths.tarvenHome, SCRIPT_NAME)
        script.parentFile?.mkdirs()
        script.writeText(PREBUILD_SCRIPT)
        return script
    }

    private fun elapsed(started: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun runtimeDiagnostic(message: String) {
        diagnostic("runtime $message")
    }

    companion object {
        internal const val SCRIPT_NAME = "sc-webpack-prebuild.mjs"
        internal const val EXIT_ALREADY_PRESENT = 3
        private const val IDLE_TIMEOUT_MILLIS = 60_000L

        /**
         * The prebuild script records the bundle it produced; the marker avoids a
         * Node spawn per launch. It sits in the instance root (same convention as
         * .sillyclient-retired-modules) because the output path itself depends on
         * the dataRoot and cache version only the server-side config knows.
         */
        internal const val MARKER_NAME = ".sillyclient-prebuilt-lib"

        internal fun markedBundle(instanceDirectory: File): File? {
            val marker = File(instanceDirectory, MARKER_NAME)
            if (!marker.isFile) return null
            val recorded = runCatching { marker.readText().trim() }.getOrNull() ?: return null
            val output = File(recorded)
            if (!output.isAbsolute || !ManagedFiles.isWithin(output, instanceDirectory)) return null
            return output.takeIf { it.isFile && it.length() > 0 }
        }

        private val PREBUILD_SCRIPT = """
            import process from 'node:process';
            import path from 'node:path';
            import fs from 'node:fs';
            import crypto from 'node:crypto';
            import { pathToFileURL } from 'node:url';

            const [instanceDir, treeNodeModules] = process.argv.slice(2);
            process.chdir(instanceDir);

            function readJson(file) {
                try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
            }

            function bundlePresent(file) {
                try { return fs.statSync(file).size > 0; } catch { return false; }
            }

            function recordMarker(outputFile) {
                try {
                    fs.mkdirSync(path.dirname(outputFile), { recursive: true });
                    // The instance-root marker is the single lookup point; the
                    // output path varies with dataRoot and the cache version.
                    fs.writeFileSync(path.join(instanceDir, '.sillyclient-prebuilt-lib'), path.resolve(outputFile));
                } catch { /* The marker only saves a later spawn */ }
            }

            // Data root mirrors server.js: config.yaml overrides the default.
            let dataRoot = './data';
            try {
                const YAML = (await import('yaml')).default;
                const doc = YAML.parseDocument(fs.readFileSync('config.yaml', 'utf8'));
                if (!doc.errors.length && YAML.isMap(doc.contents)) {
                    const root = doc.get('dataRoot');
                    if (typeof root === 'string' && root) dataRoot = root;
                }
            } catch { /* keep the default */ }
            globalThis.DATA_ROOT = dataRoot;

            // Fast probe: the cache directory is deterministic for the package
            // version, the absent git binary and the tree's webpack version.
            const pkg = readJson(path.join(instanceDir, 'package.json'));
            const webpackPkg = readJson(path.join(treeNodeModules, 'webpack', 'package.json'));
            if (pkg && webpackPkg) {
                const cacheVersion = crypto.createHash('shake256', { outputLength: 8 })
                    .update(JSON.stringify([pkg.version ?? 'UNKNOWN', null, webpackPkg.version]))
                    .digest('hex');
                const quickCheck = path.resolve(dataRoot, '_webpack', cacheVersion, 'output', 'lib.js');
                if (bundlePresent(quickCheck)) {
                    recordMarker(quickCheck);
                    process.exit(3);
                }
            }

            // Authoritative path through the instance's own configuration.
            const config = (await import(pathToFileURL(path.join(instanceDir, 'webpack.config.js')).href)).default({});
            config.stats = { ...config.stats, colors: false };
            const outputFile = path.join(config.output?.path ?? '', config.output?.filename ?? 'lib.js');
            if (bundlePresent(outputFile)) {
                recordMarker(outputFile);
                process.exit(3);
            }

            // webpack's resolver only walks directories; the shared tree
            // carries the modules, so it joins the resolution roots. The
            // filesystem cache stays off: the server's own build hashes its
            // resolve settings and can never reuse this pack anyway.
            config.cache = false;
            config.resolve = { ...(config.resolve ?? {}), modules: [treeNodeModules, 'node_modules'] };
            const webpack = (await import('webpack')).default;
            const compiler = webpack(config);
            compiler.run((error, stats) => {
                if (error) {
                    console.error(String(error));
                    process.exit(1);
                }
                const text = stats?.toString(config.stats);
                if (text) console.log(text);
                compiler.close(() => {
                    if (stats?.hasErrors() || !bundlePresent(outputFile)) process.exit(1);
                    recordMarker(outputFile);
                    process.exit(0);
                });
            });
        """.trimIndent()
    }
}
