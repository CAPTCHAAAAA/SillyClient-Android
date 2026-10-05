package com.sillyclient.runtime

import java.io.File
import org.json.JSONObject

/** Use SillyTavern's installed YAML parser; preserve unrelated configuration and comments. */
class RuntimeConfiguration(
    private val paths: RuntimePaths,
    private val operations: OperationCoordinator,
    private val supervisor: ProcessSupervisor
) {
    fun validateStandardDataRoot(directory: File, operation: OperationCoordinator.Operation) {
        val config = File(directory, "config.yaml")
        require(ManagedFiles.isWithin(config, directory)) { "Instance configuration path escapes the instance" }
        val root = if (config.isFile) {
            JSONObject(run(directory, operation, READ_ROOT)).optString("dataRoot", "./data")
        } else "./data"
        val configured = File(root)
        val data = if (configured.isAbsolute) configured else File(directory, root)
        require(data.canonicalFile == File(directory, "data").canonicalFile && ManagedFiles.isWithin(data, directory)) {
            "预设安装暂不支持自定义 dataRoot；原配置与预设内容未更改"
        }
    }

    fun write(directory: File, values: JSONObject, operation: OperationCoordinator.Operation) {
        require(ManagedFiles.isWithin(File(directory, "config.yaml"), directory)) { "Instance configuration path escapes the instance" }
        run(directory, operation, WRITE_CONFIG, values.toString())
    }

    /** Configure and import the server in one Node process, without patching upstream files. */
    fun serverBuilder(directory: File, values: JSONObject): ProcessBuilder {
        require(ManagedFiles.isWithin(File(directory, "config.yaml"), directory)) { "Instance configuration path escapes the instance" }
        return ProcessBuilder(paths.nodeBin.absolutePath, "-e", SERVER_BOOTSTRAP).apply {
            directory(directory)
            redirectErrorStream(true)
            environment()["SILLYCLIENT_CONFIG_VALUES"] = values.toString()
        }
    }

    private fun run(
        directory: File, operation: OperationCoordinator.Operation, script: String, values: String? = null
    ): String {
        operations.ensureCurrent(operation)
        val builder = ProcessBuilder(paths.nodeBin.absolutePath, "-e", script)
        builder.directory(directory)
        builder.redirectErrorStream(true)
        builder.environment()["LD_LIBRARY_PATH"] = "${paths.usrLibDir.absolutePath}:${paths.nativeLibDir.absolutePath}"
        builder.environment()["HOME"] = paths.tarvenHome.absolutePath
        builder.environment()["TMPDIR"] = paths.tmpDir.absolutePath
        if (values != null) builder.environment()["SILLYCLIENT_CONFIG_VALUES"] = values
        // The YAML helper resolves modules under the same rules as the server:
        // tree-backed instances keep no local node_modules, so the shared tree
        // must join the resolution roots or require('yaml') cannot resolve.
        DependencyArchive(File(paths.tarvenHome, "dependency-archives"))
            .lockKey(File(directory, "package-lock.json"))
            ?.let { key -> DependencyTrees(File(paths.tarvenHome, "dependency-trees")).modulesFor(key) }
            ?.takeIf { it.isDirectory }
            ?.let { builder.environment()["NODE_PATH"] = it.absolutePath }
        val output = StringBuilder()
        val process = operations.commit(operation) {
            supervisor.track(builder.start(), operation.instanceId, operation)
        }
        val result = supervisor.waitFor(process, 10_000) { line ->
            synchronized(output) { if (output.length + line.length < 4096) output.append(line) }
        }
        if (result.timedOut) {
            supervisor.stopAsync(process).get(5, java.util.concurrent.TimeUnit.SECONDS)
            check(!process.isAlive) { "Configuration process did not stop" }
        }
        operations.ensureCurrent(operation)
        check(!result.timedOut && result.exitCode == 0) { "无法安全读取或更新实例 YAML 配置；原配置已保留" }
        return output.toString()
    }

    companion object {
        private val READ_ROOT = """
            try {
              const fs = require('node:fs');
              const YAML = require('yaml');
              const doc = YAML.parseDocument(fs.readFileSync('config.yaml', 'utf8'));
              if (doc.errors.length || !YAML.isMap(doc.contents)) throw new Error('invalid YAML');
              const root = doc.get('dataRoot') ?? './data';
              if (typeof root !== 'string') throw new Error('invalid data root');
              console.log(JSON.stringify({dataRoot: root}));
            } catch (_) { process.stderr.write('Invalid instance configuration'); process.exit(1); }
        """.trimIndent()
        private val WRITE_CONFIG = """
            try {
              const fs = require('node:fs');
              const YAML = require('yaml');
              const doc = YAML.parseDocument(fs.existsSync('config.yaml') ? fs.readFileSync('config.yaml', 'utf8') : '{}');
              if (doc.errors.length || !YAML.isMap(doc.contents)) throw new Error('invalid YAML');
              const values = JSON.parse(process.env.SILLYCLIENT_CONFIG_VALUES);
              delete process.env.SILLYCLIENT_CONFIG_VALUES;
              let changed = !fs.existsSync('config.yaml');
              for (const [key, value] of Object.entries(values)) {
                const path = key.split('.');
                for (let i = 1; i < path.length; i++) {
                  const parent = path.slice(0, i);
                  if (doc.hasIn(parent) && !YAML.isMap(doc.getIn(parent, true))) throw new Error('invalid configuration mapping');
                }
                if (doc.getIn(path) !== value) { doc.setIn(path, value); changed = true; }
              }
              const temp = '.sillyclient-config-' + require('node:crypto').randomUUID() + '.tmp';
              try { if (changed) { fs.writeFileSync(temp, String(doc), {flag: 'wx', mode: 0o600}); fs.renameSync(temp, 'config.yaml'); } }
              finally { try { fs.unlinkSync(temp); } catch (_) {} }
            } catch (_) { process.stderr.write('Invalid instance configuration'); process.exit(1); }
        """.trimIndent()
        private val SERVER_BOOTSTRAP = WRITE_CONFIG + "\n" + """
            process.argv = [process.execPath, require('node:path').resolve('server.js')];
            import(require('node:url').pathToFileURL(process.argv[1]).href).catch(error => {
              console.error(error); process.exitCode = 1;
            });
        """.trimIndent()
    }
}
