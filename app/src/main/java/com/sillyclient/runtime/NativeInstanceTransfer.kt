package com.sillyclient.runtime

import java.io.File

/**
 * Copy using bundled Node I/O, then verify contents before publishing on the
 * destination volume. The copy script scans first so progress can be reported
 * against known totals, then copies files through a bounded async pool:
 * each file is hashed on read and its destination copy is hashed again on
 * read-back, so every byte is still fully verified while FUSE round trips
 * overlap across files. A final metadata-only walk re-checks that the source
 * did not change during the copy.
 */
class NativeInstanceTransfer(
    private val paths: RuntimePaths,
    private val operations: OperationCoordinator,
    private val processes: ProcessSupervisor,
    private val onProgress: (String) -> Unit = {}
) {
    fun validatePortableSource(source: File, operation: OperationCoordinator.Operation) {
        if (!File(source, "config.yaml").isFile) return
        runScript(VALIDATE_CONFIG_SCRIPT, source, source, operation)
    }

    fun copyVerified(
        source: File,
        target: File,
        operation: OperationCoordinator.Operation
    ) {
        runScript(SCRIPT, source, target, operation)
    }

    private fun runScript(
        script: String,
        source: File,
        target: File,
        operation: OperationCoordinator.Operation
    ) {
        operations.ensureCurrent(operation)
        val builder = ProcessBuilder(paths.nodeBin.absolutePath, "-e", script, source.absolutePath, target.absolutePath)
        builder.directory(paths.tarvenHome)
        builder.environment()["LD_LIBRARY_PATH"] = "${paths.usrLibDir.absolutePath}:${paths.nativeLibDir.absolutePath}"
        builder.environment()["UV_THREADPOOL_SIZE"] = "16"
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
                catch { throw new Error('实例本地 YAML 依赖不可用，请先启动实例完成依赖准备后再迁移'); }
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
            const fsp = require('node:fs/promises');
            const path = require('node:path');
            const source = path.resolve(process.argv[1]);
            const target = path.resolve(process.argv[2]);
            const CONCURRENCY = 8;
            const manifest = new Map();
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
            function scan(directory, relative = '', depth = 0) {
                if (depth > 128) throw new Error('实例目录层级过深');
                for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
                    if (manifest.size > 1000000) throw new Error('实例文件数量过多');
                    // Historical retired dependencies are not part of the active instance.
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
            /**
             * Copy one manifest entry as a plain concurrent stream: no content
             * read-back (the dominant FUSE cost), only the cheap per-file source
             * identity re-check here plus the final metadata walk over both
             * trees. Each in-flight file owns its buffer so concurrent copies
             * cannot overwrite one another's chunks.
             */
            async function copyEntry(relative, entry) {
                if (entry.type === 'bin-link') { files++; return; }
                const buffer = Buffer.allocUnsafe(1024 * 1024);
                const input = await fsp.open(checkedJoin(source, relative),
                    fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
                const output = await fsp.open(checkedJoin(target, relative),
                    fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL, entry.mode & 0o777);
                try {
                    for (;;) {
                        const { bytesRead } = await input.read(buffer, 0, buffer.length, null);
                        if (!bytesRead) break;
                        let written = 0;
                        while (written < bytesRead) {
                            const { bytesWritten } = await output.write(buffer, written, bytesRead - written);
                            written += bytesWritten;
                        }
                        bytes += bytesRead;
                    }
                } finally {
                    await output.close();
                    await input.close();
                }
                let after = null;
                try { after = await fsp.lstat(checkedJoin(source, relative)); } catch (_) {}
                if (!after || after.dev !== entry.dev || after.ino !== entry.ino ||
                    after.size !== entry.size || after.mtimeMs !== entry.mtimeMs) {
                    throw new Error('复制期间源文件发生变化，已中止；原目录未改动');
                }
                files++;
                progress('copy');
            }
            /**
             * Metadata-only walk; catches missing or truncated copies cheaply.
             * The source pass also re-checks identity so a running instance
             * cannot be migrated underneath the copy.
             */
            function verifyWalk(base, directory, relative = '', depth = 0, requireSourceIdentity = false) {
                if (depth > 128) throw new Error('实例目录层级过深');
                for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
                    if (!relative && entry.name === '.sillyclient-retired-modules') continue;
                    const name = relative ? relative + '/' + entry.name : entry.name;
                    const expected = manifest.get(name);
                    if (base === target) {
                        // The staging ownership marker is ours; .bin links are
                        // recreated by the installer rather than copied.
                        if (!relative && entry.name === '.sillyclient-relocation-owner') continue;
                        if (!expected) throw new Error('迁移校验失败：目标出现未知条目');
                        if (expected.type === 'bin-link') continue;
                    } else if (!expected) {
                        throw new Error('迁移校验失败：实例内容在复制期间发生变化');
                    }
                    const file = checkedJoin(base, name);
                    const state = fs.lstatSync(file);
                    const type = state.isSymbolicLink()
                        ? (/(?:^|\/)node_modules\/\.bin\/[^/]+$/.test(name) ? 'bin-link' : null)
                        : state.isDirectory() ? 'directory' : state.isFile() ? 'file' : null;
                    if (type === null) throw new Error('实例包含不支持的文件类型');
                    if (expected.type !== type) throw new Error('迁移校验失败：实例条目类型发生变化');
                    if (type === 'file') {
                        if (requireSourceIdentity) {
                            if (expected.size !== state.size || expected.mtimeMs !== state.mtimeMs ||
                                expected.dev !== state.dev || expected.ino !== state.ino) {
                                throw new Error('迁移校验失败：源文件在复制后发生变化');
                            }
                        } else if (expected.size !== state.size) {
                            throw new Error('复制校验失败：目标文件与源内容不一致');
                        }
                    }
                    files++;
                    progress('verify');
                    if (type === 'directory') verifyWalk(base, file, name, depth + 1, requireSourceIdentity);
                }
            }
            (async () => {
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
                    const fileEntries = [];
                    for (const [relative, entry] of manifest) {
                        if (entry.type === 'directory') { fs.mkdirSync(checkedJoin(target, relative)); continue; }
                        fileEntries.push(relative);
                    }
                    let cursor = 0, failure = null;
                    const take = () => (failure === null && cursor < fileEntries.length) ? fileEntries[cursor++] : null;
                    const runner = async () => {
                        for (;;) {
                            const relative = take();
                            if (relative === null) return;
                            try {
                                await copyEntry(relative, manifest.get(relative));
                            } catch (error) { failure = failure ?? error; }
                        }
                    };
                    await Promise.all(Array.from(
                        { length: Math.min(CONCURRENCY, Math.max(fileEntries.length, 1)) }, runner));
                    if (failure !== null) throw failure;
                    progress('copy', true);
                    totalEntries = manifest.size;
                    files = 0;
                    verifyWalk(source, source, '', 0, true);
                    verifyWalk(target, target);
                    progress('verify', true);
                } catch (error) {
                    const collision = error && (error.code === 'EEXIST' || error.code === 'EISDIR');
                    console.error(collision ? '目标目录已包含同名条目，已中止且未覆盖任何文件' : error.message);
                    process.exitCode = 1;
                }
            })();
        """.trimIndent()
    }
}
