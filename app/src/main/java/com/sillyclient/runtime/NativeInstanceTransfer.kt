package com.sillyclient.runtime

import java.io.File

/**
 * Copy using bundled Node I/O, then verify contents before publishing on the
 * destination volume. The copy script scans first so progress can be reported
 * against known totals, hashes each file exactly twice (source read and
 * destination read-back) and finishes with a metadata-only verification walk:
 * content re-reads dominate FUSE runtime, so every extra digest doubles wall
 * time on Android's emulated storage.
 */
class NativeInstanceTransfer(
    private val paths: RuntimePaths,
    private val operations: OperationCoordinator,
    private val processes: ProcessSupervisor,
    private val onProgress: (String) -> Unit = {},
    private val sharedModules: (File) -> String? = { null }
) {
    fun validatePortableSource(source: File, operation: OperationCoordinator.Operation) {
        if (!File(source, "config.yaml").isFile) return
        runScript(VALIDATE_CONFIG_SCRIPT, source, source, operation, skipNodeModules = false)
    }

    fun copyVerified(
        source: File,
        target: File,
        operation: OperationCoordinator.Operation,
        skipNodeModules: Boolean = false
    ) {
        runScript(SCRIPT, source, target, operation, skipNodeModules)
    }

    private fun runScript(
        script: String,
        source: File,
        target: File,
        operation: OperationCoordinator.Operation,
        skipNodeModules: Boolean
    ) {
        operations.ensureCurrent(operation)
        val builder = if (skipNodeModules) {
            ProcessBuilder(paths.nodeBin.absolutePath, "-e", script,
                source.absolutePath, target.absolutePath, "skip-node-modules")
        } else {
            ProcessBuilder(paths.nodeBin.absolutePath, "-e", script, source.absolutePath, target.absolutePath)
        }
        builder.directory(paths.tarvenHome)
        builder.environment()["LD_LIBRARY_PATH"] = "${paths.usrLibDir.absolutePath}:${paths.nativeLibDir.absolutePath}"
        sharedModules(source)?.let { builder.environment()["NODE_PATH"] = it }
        var lastError = ""
        val generation = processes.generation()
        val process = operations.commit(operation) { processes.launch(builder, operation.instanceId, operation, generation) }
        val result = processes.waitForIdle(process, 120_000,
            ensureActive = { operations.ensureCurrent(operation) },
            onErrorLine = { lastError = it.take(300) }) { line ->
            if (line.startsWith("SC_TRANSFER ")) { onProgress(line); true } else false
        }
        operations.ensureCurrent(operation)
        check(!result.timedOut && result.exitCode == 0) {
            if (result.timedOut) "实例复制长时间无进展已中止；原目录已保留，请重试"
            else "实例复制失败；原目录已保留${if (lastError.isBlank()) "" else "：$lastError"}"
        }
    }

    companion object {
        internal val VALIDATE_CONFIG_SCRIPT = """
            const fs = require('node:fs');
            const path = require('node:path');
            try {
                const source = path.resolve(process.argv[1]);
                const file = path.join(source, 'config.yaml');
                if (fs.statSync(file).size > 1024 * 1024) throw new Error('实例配置文件过大');
                let YAML;
                try { YAML = require(path.join(source, 'node_modules', 'yaml')); }
                catch { YAML = require('yaml'); }
                const document = YAML.parseDocument(fs.readFileSync(file, 'utf8'));
                if (document.errors.length) throw new Error('实例配置文件格式无效');
                const dataRoot = document.get('dataRoot') ?? './data';
                if (typeof dataRoot !== 'string' || path.isAbsolute(dataRoot) || /^[a-zA-Z]:[\\/]/.test(dataRoot)) {
                    throw new Error('请先将 config.yaml 中的绝对 dataRoot 改为相对路径后再迁移');
                }
                const relative = path.relative(source, path.resolve(source, dataRoot));
                if (!relative || relative === '..' || relative.startsWith('..' + path.sep) || path.isAbsolute(relative)) {
                    throw new Error('dataRoot 指向实例目录之外，无法随实例迁移');
                }
                console.log('SC_TRANSFER config verified');
            } catch (error) { console.error(error.message); process.exitCode = 1; }
        """.trimIndent()

        internal val SCRIPT = """
            const fs = require('node:fs');
            const path = require('node:path');
            const crypto = require('node:crypto');
            const source = path.resolve(process.argv[1]);
            const target = path.resolve(process.argv[2]);
            const buffer = Buffer.allocUnsafe(1024 * 1024);
            const manifest = new Map();
            const remaining = new Set();
            let lastProgress = 0, files = 0, bytes = 0, totalFiles = 0, totalBytes = 0, totalEntries = 0;
            function progress(stage, force = false) {
                const now = Date.now();
                if (force || now - lastProgress >= 250) {
                    lastProgress = now;
                    console.log('SC_TRANSFER ' + JSON.stringify({
                        stage, files, bytes,
                        totalFiles: stage === 'verify' ? totalEntries : totalFiles, totalBytes
                    }));
                }
            }
            function checkedJoin(base, relative) {
                const result = path.join(base, relative);
                const difference = path.relative(base, result);
                if (!difference || difference === '..' || difference.startsWith('..' + path.sep) || path.isAbsolute(difference)) {
                    throw new Error('不安全的迁移路径');
                }
                return result;
            }
            function digest(file) {
                const descriptor = fs.openSync(file, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
                try {
                    const hash = crypto.createHash('sha256');
                    let count;
                    while ((count = fs.readSync(descriptor, buffer, 0, buffer.length, null)) > 0) {
                        hash.update(buffer.subarray(0, count));
                    }
                    return hash.digest('hex');
                } finally { fs.closeSync(descriptor); }
            }
            function scan(directory, relative = '', depth = 0) {
                if (depth > 128) throw new Error('实例目录层级过深');
                for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
                    if (manifest.size > 1000000) throw new Error('实例文件数量过多');
                    // Dependencies are restored from the shared tree on the destination
                    // volume, so the source tree is skipped entirely when asked to.
                    if (!relative && entry.name === 'node_modules' && process.argv[3] === 'skip-node-modules') continue;
                    // A retired copy is already replaced by the shared tree and being
                    // deleted in the background; carrying it would copy the whole
                    // tree across volumes for nothing.
                    if (!relative && entry.name === '.sillyclient-retired-modules') continue;
                    const name = relative ? relative + '/' + entry.name : entry.name;
                    if (name === '.sillyclient-relocation-owner') throw new Error('源目录包含保留的迁移标记');
                    const file = checkedJoin(source, name);
                    const state = fs.lstatSync(file);
                    if (state.isSymbolicLink()) {
                        if (/(?:^|\/)node_modules\/\.bin\/[^/]+$/.test(name)) { manifest.set(name, { type: 'bin-link' }); }
                        else throw new Error('实例包含链接文件，无法跨卷迁移');
                    } else if (state.isDirectory()) {
                        manifest.set(name, { type: 'directory' });
                        scan(file, name, depth + 1);
                    } else if (state.isFile()) {
                        manifest.set(name, { type: 'file', size: state.size, mode: state.mode,
                            mtimeMs: state.mtimeMs, dev: state.dev, ino: state.ino });
                        totalFiles++; totalBytes += state.size;
                    } else throw new Error('实例包含不支持的文件类型');
                    files++;
                    progress('scan');
                }
            }
            function verifyWalk(directory, relative = '', depth = 0) {
                if (depth > 128) throw new Error('实例目录层级过深');
                for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
                    if (!relative && entry.name === 'node_modules' && process.argv[3] === 'skip-node-modules') continue;
                    if (!relative && entry.name === '.sillyclient-retired-modules') continue;
                    const name = relative ? relative + '/' + entry.name : entry.name;
                    const expected = manifest.get(name);
                    if (!expected || !remaining.delete(name)) {
                        throw new Error('迁移校验失败：实例内容在复制期间发生变化');
                    }
                    const file = checkedJoin(source, name);
                    const state = fs.lstatSync(file);
                    const type = state.isSymbolicLink()
                        ? (/(?:^|\/)node_modules\/\.bin\/[^/]+$/.test(name) ? 'bin-link' : null)
                        : state.isDirectory() ? 'directory' : state.isFile() ? 'file' : null;
                    if (type === null) throw new Error('实例包含不支持的文件类型');
                    if (expected.type !== type) throw new Error('迁移校验失败：实例条目类型发生变化');
                    if (type === 'file') {
                        if (expected.size !== state.size || expected.mtimeMs !== state.mtimeMs ||
                            expected.dev !== state.dev || expected.ino !== state.ino) {
                            throw new Error('迁移校验失败：源文件在复制后发生变化');
                        }
                        const copy = fs.lstatSync(checkedJoin(target, name));
                        if (!copy.isFile() || copy.size !== expected.size) throw new Error('迁移校验失败：目标文件不完整');
                    }
                    files++;
                    progress('verify');
                    if (type === 'directory') verifyWalk(file, name, depth + 1);
                }
            }
            try {
                if (source === target || path.relative(source, target).split(path.sep)[0] !== '..' ||
                    path.relative(target, source).split(path.sep)[0] !== '..') {
                    throw new Error('迁移来源与目标必须是两个独立目录');
                }
                if (!fs.lstatSync(source).isDirectory() || !fs.lstatSync(target).isDirectory()) {
                    throw new Error('迁移目录不存在');
                }
                scan(source);
                progress('scan', true);
                files = 0;
                for (const [relative, entry] of manifest) {
                    progress('copy');
                    if (entry.type === 'directory') { fs.mkdirSync(checkedJoin(target, relative)); continue; }
                    if (entry.type === 'bin-link') continue;
                    const input = fs.openSync(checkedJoin(source, relative),
                        fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
                    const output = fs.openSync(checkedJoin(target, relative),
                        fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL, entry.mode & 0o777);
                    const hash = crypto.createHash('sha256');
                    try {
                        let count;
                        while ((count = fs.readSync(input, buffer, 0, buffer.length, null)) > 0) {
                            hash.update(buffer.subarray(0, count));
                            let written = 0;
                            while (written < count) written += fs.writeSync(output, buffer, written, count - written);
                            bytes += count;
                            progress('copy');
                        }
                    } finally { fs.closeSync(input); fs.closeSync(output); }
                    if (digest(checkedJoin(target, relative)) !== hash.digest('hex')) {
                        throw new Error('复制校验失败：目标文件与源内容不一致');
                    }
                    const after = fs.lstatSync(checkedJoin(source, relative));
                    if (entry.dev !== after.dev || entry.ino !== after.ino ||
                        entry.size !== after.size || entry.mtimeMs !== after.mtimeMs) {
                        throw new Error('复制期间源文件发生变化，已中止；原目录未改动');
                    }
                    files++;
                }
                progress('copy', true);
                totalEntries = manifest.size;
                remaining.clear();
                for (const key of manifest.keys()) remaining.add(key);
                files = 0;
                verifyWalk(source);
                if (remaining.size) throw new Error('迁移校验失败：源目录条目在复制期间丢失');
                progress('verify', true);
            } catch (error) {
                const collision = error && (error.code === 'EEXIST' || error.code === 'EISDIR');
                console.error(collision ? '目标目录已包含同名条目，已中止且未覆盖任何文件' : error.message);
                process.exitCode = 1;
            }
        """.trimIndent()
    }
}
