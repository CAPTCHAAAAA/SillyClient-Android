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
        // 实例自持：YAML 助手与服务器同样从实例目录内的 node_modules 解析依赖。
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
              if (!doc.has('enableServerPlugins')) { doc.set('enableServerPlugins', true); changed = true; }
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
        private val EXTENSION_GIT_BRIDGE = """
            try {
              const path = require('node:path');
              const { pathToFileURL } = require('node:url');
              const Module = require('node:module');
              if (typeof Module.register === 'function') {
                const cwd = process.cwd();
                const isoGitUrl = pathToFileURL(require.resolve('isomorphic-git', { paths: [cwd] })).href;
                const isoHttpUrl = pathToFileURL(require.resolve('isomorphic-git/http/node', { paths: [cwd] })).href;
                const initBridge = function(fs, path, isomorphicGit, origHttp) {
                  const KNOWN_REPOS = {
                    'JS-Slash-Runner': { repo: 'N0VI028/JS-Slash-Runner', version: '4.11.2', commit: '46ec10df770b47e1ce6562de747d72f1bde3549e' },
                    'LittleWhiteBox': { repo: 'RT15548/LittleWhiteBox', version: '3.1.6', commit: '66cb1001e860cdddd77e5a3911c7c47ea325df3a' },
                    'ST-Prompt-Template': { repo: 'zonde306/ST-Prompt-Template', version: '1.17.9', commit: '38ae8edbe4db3db7ad0feacbfba9b5fb2cfcb84f' },
                    'Extension-Dice': { repo: 'SillyTavern/Extension-Dice', version: '2.0.0', commit: '154fb3acccfbdef2fbabdc792acdc6f1bd08cef7' },
                  };
                  function isSha(val) {
                    return typeof val === 'string' && val.length === 40 && /^[0-9a-f]{40}/i.test(val);
                  }
                  function stripGitSuffix(rawUrl) {
                    const text = String(rawUrl || '').trim();
                    return text.toLowerCase().endsWith('.git') ? text.slice(0, -4) : text;
                  }
                  async function collectBody(body) {
                    if (!body) return undefined;
                    if (Buffer.isBuffer(body) || body instanceof Uint8Array) return [Buffer.from(body)];
                    if (Array.isArray(body)) return [Buffer.concat(body.map(b => Buffer.from(b)))];
                    const chunks = [];
                    for await (const chunk of body) chunks.push(Buffer.from(chunk));
                    return [Buffer.concat(chunks)];
                  }
                  function withTimeout(promise, ms) {
                    let timer;
                    return Promise.race([
                      promise,
                      new Promise((_, reject) => {
                        timer = setTimeout(() => reject(new Error('Git HTTP request timed out')), ms);
                      }),
                    ]).finally(() => clearTimeout(timer));
                  }
                  const http = {
                    async request(req) {
                      const mirrors = (process.env.SILLYCLIENT_GIT_MIRRORS ?? 'https://ghfast.top/,https://gh-proxy.com/')
                        .split(',').map(s => s.trim()).filter(Boolean);
                      const isGitHub = /^https:\/\/github\.com\//i.test(req.url);
                      const allowMirrors = isGitHub || Boolean(process.env.SILLYCLIENT_GIT_MIRRORS);
                      const candidates = allowMirrors
                        ? [req.url, ...mirrors.map(m => (m.endsWith('/') ? m : m + '/') + req.url)]
                        : [req.url];
                      const bufferedBody = await collectBody(req.body);
                      let lastError;
                      for (let i = 0; i < candidates.length; i++) {
                        const candidateUrl = candidates[i];
                        const timeoutMs = i === 0 && isGitHub ? 8000 : 15000;
                        try {
                          const res = await withTimeout(
                            origHttp.request({
                              ...req,
                              url: candidateUrl,
                              headers: { 'User-Agent': 'git/2.43.0 SillyClient', ...(req.headers || {}) },
                              body: bufferedBody ? [Buffer.from(bufferedBody[0])] : undefined,
                            }),
                            timeoutMs
                          );
                          if (res && res.statusCode >= 200 && res.statusCode < 400) {
                            return { ...res, url: req.url };
                          }
                          lastError = new Error('HTTP ' + (res && res.statusCode) + ' from ' + candidateUrl);
                        } catch (err) {
                          lastError = err;
                        }
                      }
                      throw lastError || new Error('Git HTTP request failed: ' + req.url);
                    }
                  };
                  function writeGitFiles(baseDir, remoteUrl, branch, commit) {
                    const gitDir = path.join(baseDir, '.git');
                    fs.mkdirSync(path.join(gitDir, 'refs/heads'), { recursive: true });
                    fs.mkdirSync(path.join(gitDir, 'refs/remotes/origin'), { recursive: true });
                    fs.mkdirSync(path.join(gitDir, 'objects/info'), { recursive: true });
                    fs.mkdirSync(path.join(gitDir, 'objects/pack'), { recursive: true });
                    fs.writeFileSync(path.join(gitDir, 'HEAD'), 'ref: refs/heads/' + branch + '\n');
                    fs.writeFileSync(
                      path.join(gitDir, 'config'),
                      '[core]\n\trepositoryformatversion = 0\n\tfilemode = false\n\tbare = false\n\tlogallrefupdates = true\n' +
                      '[remote \x22origin\x22]\n\turl = ' + remoteUrl + '\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n' +
                      '[branch \x22' + branch + '\x22]\n\tremote = origin\n\tmerge = refs/heads/' + branch + '\n'
                    );
                    fs.writeFileSync(path.join(gitDir, 'refs/heads', branch), commit + '\n');
                    fs.writeFileSync(path.join(gitDir, 'refs/remotes/origin', branch), commit + '\n');
                  }
                  function ensureExtensionGitRepo(baseDir) {
                    if (!baseDir || typeof baseDir !== 'string') return;
                    const headFile = path.join(baseDir, '.git/HEAD');
                    if (fs.existsSync(headFile)) return;
                    const manifestFile = path.join(baseDir, 'manifest.json');
                    if (!fs.existsSync(manifestFile)) return;
                    try {
                      const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
                      const dirName = path.basename(baseDir);
                      const known = KNOWN_REPOS[dirName];
                      let remoteUrl = '';
                      let commit = '1111111111111111111111111111111111111111';
                      if (known) {
                        remoteUrl = 'https://github.com/' + known.repo + '.git';
                        if (manifest.version === known.version) commit = known.commit;
                      } else {
                        const rawRepo = manifest.homePage || manifest.homepage ||
                          (manifest.repository && typeof manifest.repository === 'object' ? manifest.repository.url : manifest.repository) || '';
                        const parsed = new URL(String(rawRepo).trim());
                        if (parsed.hostname.toLowerCase() === 'github.com') {
                          const parts = parsed.pathname.split('/').filter(Boolean);
                          if (parts.length >= 2) {
                            remoteUrl = 'https://github.com/' + parts[0] + '/' + stripGitSuffix(parts[1]) + '.git';
                          }
                        }
                      }
                      if (remoteUrl) writeGitFiles(baseDir, remoteUrl, 'main', commit);
                    } catch (_) {}
                  }
                  function readRef(baseDir, refPath) {
                    const full = path.join(baseDir, '.git', refPath);
                    if (fs.existsSync(full) && fs.statSync(full).isFile()) {
                      const val = fs.readFileSync(full, 'utf8').trim();
                      if (val.startsWith('ref: ')) return readRef(baseDir, val.slice(5).trim());
                      if (isSha(val)) return val.toLowerCase();
                    }
                    const packed = path.join(baseDir, '.git/packed-refs');
                    if (fs.existsSync(packed)) {
                      for (const line of fs.readFileSync(packed, 'utf8').split(/\r?\n/)) {
                        if (!line || line.startsWith('#') || line.startsWith('^')) continue;
                        const [oid, ref] = line.trim().split(/\s+/);
                        if (ref === refPath && isSha(oid)) return oid.toLowerCase();
                      }
                    }
                    return '';
                  }
                  function getHeadInfo(baseDir) {
                    const headFile = path.join(baseDir, '.git/HEAD');
                    if (!fs.existsSync(headFile)) return { detached: false, current: '', oid: '' };
                    const raw = fs.readFileSync(headFile, 'utf8').trim();
                    if (raw.startsWith('ref: ')) {
                      const ref = raw.slice(5).trim();
                      const branch = ref.replace(/^refs\/heads\//, '');
                      return { detached: false, current: branch, oid: readRef(baseDir, ref) };
                    }
                    return { detached: true, current: 'HEAD', oid: raw.toLowerCase() };
                  }
                  function getOriginUrl(baseDir) {
                    const cfgFile = path.join(baseDir, '.git/config');
                    if (!fs.existsSync(cfgFile)) return '';
                    const text = fs.readFileSync(cfgFile, 'utf8');
                    const m = text.match(/\[remote\s+\x22origin\x22\][\s\S]*?^\s*url\s*=\s*(\S+)/m);
                    return m ? m[1].trim() : '';
                  }
                  function listRefFiles(rootDir, prefix = '') {
                    if (!fs.existsSync(rootDir)) return [];
                    const results = [];
                    for (const entry of fs.readdirSync(rootDir, { withFileTypes: true })) {
                      const rel = prefix ? prefix + '/' + entry.name : entry.name;
                      const full = path.join(rootDir, entry.name);
                      if (entry.isDirectory()) results.push(...listRefFiles(full, rel));
                      else if (entry.isFile() && entry.name !== 'HEAD') {
                        const oid = fs.readFileSync(full, 'utf8').trim();
                        if (isSha(oid)) results.push({ name: rel, oid: oid.toLowerCase() });
                      }
                    }
                    return results;
                  }
                  const fetchCache = new Map();
                  const CheckRepoActions = {
                    IS_REPO_ROOT: 'root',
                    IS_REPO_HOME: 'home',
                    BARE: 'bare',
                    IN_TREE: 'tree',
                  };
                  function simpleGit(optionsOrPath) {
                    const requestedBaseDir = typeof optionsOrPath === 'string'
                      ? optionsOrPath
                      : (optionsOrPath && optionsOrPath.baseDir) || process.cwd();
                    let baseDir = path.resolve(requestedBaseDir);
                    try { baseDir = fs.realpathSync(baseDir); } catch (_) {}
                    ensureExtensionGitRepo(baseDir);
                    async function doFetch() {
                      const url = getOriginUrl(baseDir);
                      if (!url) { fetchCache.delete(baseDir); return; }
                      const now = Date.now();
                      const cached = fetchCache.get(baseDir);
                      if (cached && cached.url === url) {
                        if (cached.promise) return cached.promise;
                        if (now >= cached.time && now - cached.time < 3000) return cached.refs;
                      }
                      const entry = { url, promise: null, time: 0, refs: null };
                      fetchCache.set(baseDir, entry);
                      entry.promise = (async () => {
                        try {
                          const refs = await isomorphicGit.listServerRefs({
                            http,
                            url,
                            symrefs: true,
                            prefix: 'refs/heads/',
                          });
                          // A remote change or checkout must invalidate both cached and in-flight results.
                          if (fetchCache.get(baseDir) !== entry || getOriginUrl(baseDir) !== url) return refs;
                          for (const r of refs) {
                            if (r.ref && r.ref.startsWith('refs/heads/') && isSha(r.oid)) {
                              const branchName = r.ref.slice('refs/heads/'.length);
                              const dest = path.join(baseDir, '.git/refs/remotes/origin', branchName);
                              fs.mkdirSync(path.dirname(dest), { recursive: true });
                              fs.writeFileSync(dest, r.oid.toLowerCase() + '\n');
                            }
                          }
                          const head = getHeadInfo(baseDir);
                          if (!head.detached && head.current && refs.length > 0) {
                            const hasCurrentOnRemote = refs.some(r => r.ref === 'refs/heads/' + head.current);
                            if (!hasCurrentOnRemote) {
                              const fallbackRef = refs.find(r => r.ref === 'refs/heads/master') || refs[0];
                              const fallbackBranch = fallbackRef.ref.slice('refs/heads/'.length);
                              const oldHeadOid = head.oid || '1111111111111111111111111111111111111111';
                              const newHeadPath = path.join(baseDir, '.git/refs/heads', fallbackBranch);
                              fs.mkdirSync(path.dirname(newHeadPath), { recursive: true });
                              if (!fs.existsSync(newHeadPath)) fs.writeFileSync(newHeadPath, oldHeadOid + '\n');
                              fs.writeFileSync(path.join(baseDir, '.git/HEAD'), 'ref: refs/heads/' + fallbackBranch + '\n');
                              const aliasDest = path.join(baseDir, '.git/refs/remotes/origin', head.current);
                              fs.mkdirSync(path.dirname(aliasDest), { recursive: true });
                              fs.writeFileSync(aliasDest, fallbackRef.oid.toLowerCase() + '\n');
                            }
                          }
                          entry.refs = refs;
                          entry.time = Date.now();
                          return refs;
                        } finally {
                          if (fetchCache.get(baseDir) === entry) {
                            if (entry.refs) entry.promise = null;
                            else fetchCache.delete(baseDir);
                          }
                        }
                      })();
                      return entry.promise;
                    }
                    return {
                      async checkIsRepo() {
                        ensureExtensionGitRepo(baseDir);
                        return fs.existsSync(path.join(baseDir, '.git/HEAD'));
                      },
                      async revparse(args) {
                        const arr = Array.isArray(args) ? args : [args];
                        if (arr.includes('--is-shallow-repository')) return 'false';
                        const head = getHeadInfo(baseDir);
                        if (arr.includes('--abbrev-ref')) {
                          if (arr.includes('@{u}')) return 'origin/' + (head.current || 'main');
                          return head.detached ? 'HEAD' : (head.current || 'main');
                        }
                        if (arr.includes('--short')) return (head.oid || '').slice(0, 7);
                        const target = arr[arr.length - 1] || 'HEAD';
                        if (target === 'HEAD') {
                          if (!head.oid) throw new Error('HEAD cannot be resolved');
                          return head.oid;
                        }
                        if (target.startsWith('origin/')) {
                          return readRef(baseDir, 'refs/remotes/' + target) || head.oid;
                        }
                        return readRef(baseDir, 'refs/heads/' + target) || head.oid;
                      },
                      async branch(args) {
                        const arr = Array.isArray(args) ? args : [];
                        if (arr.includes('-r')) {
                          const remotes = listRefFiles(path.join(baseDir, '.git/refs/remotes/origin'));
                          const branches = {};
                          const all = [];
                          for (const r of remotes) {
                            const name = 'origin/' + r.name;
                            all.push(name);
                            branches[name] = { current: false, name, commit: r.oid.slice(0, 7), label: r.oid.slice(0, 7) };
                          }
                          return { detached: false, current: '', all, branches };
                        }
                        const head = getHeadInfo(baseDir);
                        const locals = listRefFiles(path.join(baseDir, '.git/refs/heads'));
                        const branches = {};
                        const all = [];
                        for (const l of locals) {
                          all.push(l.name);
                          branches[l.name] = {
                            current: !head.detached && l.name === head.current,
                            name: l.name,
                            commit: l.oid.slice(0, 7),
                            label: l.oid.slice(0, 7),
                          };
                        }
                        if (head.current && !branches[head.current] && head.oid) {
                          all.push(head.current);
                          branches[head.current] = { current: !head.detached, name: head.current, commit: head.oid.slice(0, 7), label: head.oid.slice(0, 7) };
                        }
                        return { detached: head.detached, current: head.current, all, branches };
                      },
                      async branchLocal() {
                        return this.branch([]);
                      },
                      async fetch() {
                        await doFetch();
                        return {};
                      },
                      async log(options = {}) {
                        const head = getHeadInfo(baseDir);
                        const fromRef = options.from || head.oid;
                        const toSpec = options.to || ('origin/' + (head.current || 'main'));
                        const fromOid = isSha(fromRef)
                          ? fromRef.toLowerCase()
                          : (readRef(baseDir, 'refs/heads/' + fromRef) || head.oid);
                        const toOid = toSpec.startsWith('origin/')
                          ? readRef(baseDir, 'refs/remotes/' + toSpec)
                          : (isSha(toSpec) ? toSpec.toLowerCase() : readRef(baseDir, 'refs/heads/' + toSpec));
                        if (!toOid || !fromOid || fromOid === toOid) {
                          return { all: [], total: 0, latest: null };
                        }
                        const entry = { hash: toOid, date: '', message: 'Update available', refs: '', body: '', author_name: '', author_email: '' };
                        return { all: [entry], total: 1, latest: entry };
                      },
                      async getRemotes() {
                        const cleanUrl = stripGitSuffix(getOriginUrl(baseDir));
                        return cleanUrl ? [{ name: 'origin', refs: { fetch: cleanUrl, push: cleanUrl } }] : [];
                      },
                      async pull(_remote = 'origin', branch) {
                        fetchCache.delete(baseDir);
                        try {
                          const head = getHeadInfo(baseDir);
                          const targetBranch = branch || head.current || 'main';
                          const url = getOriginUrl(baseDir);
                          if (!url) throw new Error('No origin remote configured');
                          const fetchRes = await isomorphicGit.fetch({
                            fs,
                            http,
                            dir: baseDir,
                            url,
                            ref: targetBranch,
                            depth: 1,
                            singleBranch: true,
                            tags: false,
                          });
                          const latestOid = (fetchRes && fetchRes.fetchHead) || readRef(baseDir, 'refs/remotes/origin/' + targetBranch);
                          if (!latestOid) throw new Error('Unable to resolve remote HEAD for ' + targetBranch);
                          const localRefFile = path.join(baseDir, '.git/refs/heads', targetBranch);
                          fs.mkdirSync(path.dirname(localRefFile), { recursive: true });
                          fs.writeFileSync(localRefFile, latestOid + '\n');
                          const remoteRefFile = path.join(baseDir, '.git/refs/remotes/origin', targetBranch);
                          fs.mkdirSync(path.dirname(remoteRefFile), { recursive: true });
                          fs.writeFileSync(remoteRefFile, latestOid + '\n');
                          fs.writeFileSync(path.join(baseDir, '.git/HEAD'), 'ref: refs/heads/' + targetBranch + '\n');
                          await isomorphicGit.checkout({ fs, dir: baseDir, ref: targetBranch, force: true });
                          return {};
                        } finally { fetchCache.delete(baseDir); }
                      },
                      async remote() {
                        return '';
                      },
                      async checkout(branch) {
                        fetchCache.delete(baseDir);
                        try {
                          const targetBranch = String(branch).replace(/^origin\//, '');
                          const url = getOriginUrl(baseDir);
                          if (url) {
                            const fetchRes = await isomorphicGit.fetch({
                              fs,
                              http,
                              dir: baseDir,
                              url,
                              ref: targetBranch,
                              depth: 1,
                              singleBranch: true,
                              tags: false,
                            });
                            const oid = (fetchRes && fetchRes.fetchHead) || readRef(baseDir, 'refs/remotes/origin/' + targetBranch);
                            if (oid) {
                              const localRefFile = path.join(baseDir, '.git/refs/heads', targetBranch);
                              fs.mkdirSync(path.dirname(localRefFile), { recursive: true });
                              fs.writeFileSync(localRefFile, oid + '\n');
                            }
                          }
                          fs.writeFileSync(path.join(baseDir, '.git/HEAD'), 'ref: refs/heads/' + targetBranch + '\n');
                          await isomorphicGit.checkout({ fs, dir: baseDir, ref: targetBranch, force: true });
                        } finally { fetchCache.delete(baseDir); }
                      },
                      async checkoutBranch(localBranch, startPoint) {
                        await this.checkout(localBranch || startPoint);
                      },
                      async clone(url, localPath, options) {
                        const arr = Array.isArray(options) ? options : [];
                        const bIdx = arr.indexOf('-b');
                        const ref = bIdx >= 0 && arr[bIdx + 1] ? arr[bIdx + 1] : undefined;
                        await isomorphicGit.clone({
                          fs,
                          http,
                          dir: localPath,
                          url,
                          ref,
                          depth: 1,
                          singleBranch: Boolean(ref),
                        });
                      },
                      async show() {
                        return '';
                      }
                    };
                  }
                  return { http, CheckRepoActions, simpleGit };
                };
                const bridgeModuleSource = [
                  'import fs from \x22node:fs\x22;',
                  'import path from \x22node:path\x22;',
                  'import isomorphicGit from ' + JSON.stringify(isoGitUrl) + ';',
                  'import origHttp from ' + JSON.stringify(isoHttpUrl) + ';',
                  'const bridge = (' + initBridge.toString() + ')(fs, path, isomorphicGit, origHttp);',
                  'export const http = bridge.http;',
                  'export const CheckRepoActions = bridge.CheckRepoActions;',
                  'export const simpleGit = bridge.simpleGit;',
                  'export default bridge.simpleGit;',
                ].join('\n');
                const loaderSource = [
                  'const BRIDGE_SOURCE = ' + JSON.stringify(bridgeModuleSource) + ';',
                  'const SIMPLE_GIT_URL = \x22sillyclient://simple-git-bridge.mjs\x22;',
                  'const HTTP_BRIDGE_URL = \x22sillyclient://git-http-bridge.mjs\x22;',
                  'export async function resolve(specifier, context, nextResolve) {',
                  '  if (specifier === \x22simple-git\x22) return { url: SIMPLE_GIT_URL, shortCircuit: true };',
                  '  if (specifier === \x22isomorphic-git/http/node\x22) return { url: HTTP_BRIDGE_URL, shortCircuit: true };',
                  '  return nextResolve(specifier, context);',
                  '}',
                  'export async function load(url, context, nextLoad) {',
                  '  if (url === SIMPLE_GIT_URL) return { format: \x22module\x22, source: BRIDGE_SOURCE, shortCircuit: true };',
                  '  if (url === HTTP_BRIDGE_URL) {',
                  '    return {',
                  '      format: \x22module\x22,',
                  '      source: \x22import { http } from \\\x22\x22 + SIMPLE_GIT_URL + \x22\\\x22; export const request = (req) => http.request(req); export default http;\x22,',
                  '      shortCircuit: true,',
                  '    };',
                  '  }',
                  '  return nextLoad(url, context);',
                  '}',
                ].join('\n');
                Module.register('data:text/javascript;base64,' + Buffer.from(loaderSource).toString('base64'));
              }
            } catch (_) {}
        """.trimIndent()
        private val SERVER_BOOTSTRAP = WRITE_CONFIG + "\n" + EXTENSION_GIT_BRIDGE + "\n" + """
            process.argv = [process.execPath, require('node:path').resolve('server.js')];
            import(require('node:url').pathToFileURL(process.argv[1]).href).catch(error => {
              console.error(error); process.exitCode = 1;
            });
        """.trimIndent()
    }
}
