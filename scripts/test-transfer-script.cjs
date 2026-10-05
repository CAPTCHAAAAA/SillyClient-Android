// Behavioral harness for the embedded transfer SCRIPT extracted from NativeInstanceTransfer.kt.
// Replicates the skipped JVM NativeInstanceTransferTest scenarios plus progress-format checks.
const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const kotlin = fs.readFileSync(
  path.join(__dirname, "..", "app", "src", "main", "java", "com", "sillyclient", "runtime", "NativeInstanceTransfer.kt"),
  "utf8",
);
const marker = 'internal val SCRIPT = """';
const start = kotlin.indexOf(marker) + marker.length;
const end = kotlin.indexOf('""".trimIndent()', start);
if (start < marker.length || end < 0) { console.error("SCRIPT extraction failed"); process.exit(2); }

function trimIndent(text) {
  const lines = text.replace(/\r\n/g, "\n").split("\n");
  while (lines.length && lines[0].trim() === "") lines.shift();
  while (lines.length && lines[lines.length - 1].trim() === "") lines.pop();
  const indents = lines.filter(l => l.trim() !== "").map(l => l.match(/^ */)[0].length);
  const min = Math.min(...indents);
  return lines.map(l => l.slice(min)).join("\n");
}
const script = trimIndent(kotlin.slice(start, end));

let failures = 0;
function check(name, condition) {
  if (condition) console.log("[OK] " + name);
  else { console.log("[ERR] " + name); failures++; }
}
const tmpBase = fs.mkdtempSync(path.join(process.env.TEMP || "/tmp", "transfer-harness-"));
function tempDir(name) { return fs.mkdirSync(path.join(tmpBase, name), { recursive: true }); }

function runScript(source, target, wrapperPrefix = "") {
  try {
    const stdout = execFileSync(process.execPath, ["-e", wrapperPrefix + script, source, target],
      { encoding: "utf8", timeout: 60000, maxBuffer: 32 * 1024 * 1024 });
    return { code: 0, stdout, stderr: "" };
  } catch (error) {
    return { code: error.status ?? 1, stdout: error.stdout || "", stderr: error.stderr || "" };
  }
}

// Scenario 1: full copy preserves source, copies files, dirs, node_modules, emits staged progress.
{
  const root = tempDir("basic");
  const source = path.join(root, "Source Tavern");
  fs.mkdirSync(path.join(source, "data/default-user/chats"), { recursive: true });
  fs.mkdirSync(path.join(source, "node_modules/package"), { recursive: true });
  fs.writeFileSync(path.join(source, "server.js"), "source");
  fs.writeFileSync(path.join(source, "data/default-user/chats/conversation.jsonl"), "private chat contents");
  fs.writeFileSync(path.join(source, "node_modules/package/index.js"), "dependency contents");
  fs.mkdirSync(path.join(source, "data/default-user/characters"), { recursive: true });
  const target = path.join(root, "Target Tavern");
  fs.mkdirSync(target);
  fs.writeFileSync(path.join(target, ".sillyclient-relocation-owner"), "owned by relocation");
  const result = runScript(source, target);
  const events = result.stdout.split("\n").filter(l => l.startsWith("SC_TRANSFER ")).map(l => {
    try { return JSON.parse(l.slice(12)); } catch { return { stage: "unparseable" }; }
  });
  check("basic copy exits 0", result.code === 0);
  check("server.js copied", result.code === 0 && fs.readFileSync(path.join(target, "server.js"), "utf8") === "source");
  check("chats copied", result.code === 0 &&
    fs.readFileSync(path.join(target, "data/default-user/chats/conversation.jsonl"), "utf8") === "private chat contents");
  check("dependency copied", result.code === 0 &&
    fs.readFileSync(path.join(target, "node_modules/package/index.js"), "utf8") === "dependency contents");
  check("empty dir copied", result.code === 0 && fs.statSync(path.join(target, "data/default-user/characters")).isDirectory());
  check("source preserved", fs.readFileSync(path.join(source, "server.js"), "utf8") === "source");
  check("owner marker untouched", fs.readFileSync(path.join(target, ".sillyclient-relocation-owner"), "utf8") === "owned by relocation");
  check("scan events emitted", events.some(e => e.stage === "scan"));
  check("copy events emitted", events.some(e => e.stage === "copy"));
  check("verify events emitted", events.some(e => e.stage === "verify"));
  check("all events parse as JSON", !events.some(e => e.stage === "unparseable"));
  const lastCopy = [...events].reverse().find(e => e.stage === "copy");
  check("copy totals reported", !!lastCopy && lastCopy.totalFiles === 3 && lastCopy.totalBytes > 0);
  const lastVerify = [...events].reverse().find(e => e.stage === "verify");
  check("verify totals reported", !!lastVerify && lastVerify.totalFiles === 9 && lastVerify.files === 9);
  const scanEvents = events.filter(e => e.stage === "scan");
  check("scan counts entries", scanEvents.length > 0 && scanEvents[scanEvents.length - 1].files === 9);
}

// Scenario 2: collision — target already has server.js, nothing overwritten.
{
  const root = tempDir("collision");
  const source = path.join(root, "source");
  fs.mkdirSync(source);
  fs.writeFileSync(path.join(source, "server.js"), "source");
  const target = path.join(root, "target");
  fs.mkdirSync(target);
  fs.writeFileSync(path.join(target, "server.js"), "preserve target");
  const result = runScript(source, target);
  check("collision exits non-zero", result.code !== 0);
  check("source preserved on collision", fs.readFileSync(path.join(source, "server.js"), "utf8") === "source");
  check("target preserved on collision", fs.readFileSync(path.join(target, "server.js"), "utf8") === "preserve target");
  check("collision error is Chinese", /[\u4e00-\u9fff]/.test(result.stderr));
}

// Scenario 3: source mutation during copy is detected by the post-copy metadata check.
{
  const root = tempDir("mutate");
  const source = path.join(root, "source");
  fs.mkdirSync(source);
  fs.writeFileSync(path.join(source, "server.js"), "initial");
  const target = path.join(root, "target");
  fs.mkdirSync(target);
  const wrapper = `
    const fsW = require('node:fs');
    const pathW = require('node:path');
    const realOpenSync = fsW.openSync;
    let mutated = false;
    fsW.openSync = function (p, flags, mode) {
      if (!mutated && p === pathW.join(${JSON.stringify(target)}, 'server.js') && (flags & 3) === 0) {
        mutated = true;
        fsW.writeFileSync(pathW.join(${JSON.stringify(source)}, 'server.js'), 'changed during copy');
      }
      return realOpenSync.call(fsW, p, flags, mode);
    };
  `;
  const result = runScript(source, target, wrapper);
  check("during-copy mutation detected", result.code !== 0);
  check("mutation message is Chinese", /[\u4e00-\u9fff]/.test(result.stderr));
}

// Scenario 4: source drift between scan and copy is caught by the metadata compare.
{
  const root = tempDir("drift");
  const source = path.join(root, "source");
  fs.mkdirSync(source);
  fs.writeFileSync(path.join(source, "early.txt"), "aaa");
  fs.writeFileSync(path.join(source, "late.txt"), "bbb");
  const target = path.join(root, "target");
  fs.mkdirSync(target);
  const wrapper = `
    const fsW = require('node:fs');
    const pathW = require('node:path');
    const realOpenSync = fsW.openSync;
    let mutated = false;
    fsW.openSync = function (p, flags, mode) {
      if (!mutated && p === pathW.join(${JSON.stringify(source)}, 'early.txt') && (flags & 3) === 0) {
        mutated = true;
        fsW.writeFileSync(pathW.join(${JSON.stringify(source)}, 'late.txt'), 'mutated-larger-content');
      }
      return realOpenSync.call(fsW, p, flags, mode);
    };
  `;
  const result = runScript(source, target, wrapper);
  check("scan-to-copy drift detected", result.code !== 0);
  check("drift message is Chinese", /[\u4e00-\u9fff]/.test(result.stderr));
}

// Scenario 5: symlink handling (only when the host allows creating symlinks).
let symlinksAvailable = process.platform !== "win32";
if (!symlinksAvailable && process.env.SC_ENABLE_SYMLINK_TESTS === "1") {
  try {
    const probe = path.join(tmpBase, "symlink-probe");
    fs.symlinkSync(probe, probe + ".link");
    fs.rmSync(probe + ".link");
    symlinksAvailable = true;
  } catch { /* Windows without symlink privileges */ }
}
if (symlinksAvailable) {
  {
    const root = tempDir("link");
    const source = path.join(root, "source");
    fs.mkdirSync(source);
    const secret = path.join(root, "private-secret.txt");
    fs.writeFileSync(secret, "private");
    fs.symlinkSync(secret, path.join(source, "chat.jsonl"));
    const target = path.join(root, "target");
    fs.mkdirSync(target);
    const result = runScript(source, target);
    check("external link exits non-zero", result.code !== 0);
    check("private file untouched", fs.readFileSync(secret, "utf8") === "private");
    check("no link in target", !fs.existsSync(path.join(target, "chat.jsonl")));
  }
  {
    const root = tempDir("binlink");
    const source = path.join(root, "source");
    fs.mkdirSync(path.join(source, "node_modules/.bin"), { recursive: true });
    fs.writeFileSync(path.join(source, "node_modules/package/cli.js"), "module command");
    fs.symlinkSync(path.join(source, "node_modules/package/cli.js"), path.join(source, "node_modules/.bin/command"));
    const target = path.join(root, "target");
    fs.mkdirSync(target);
    const result = runScript(source, target);
    check("bin-link copy exits 0", result.code === 0);
    check("cli.js copied", result.code === 0 && fs.readFileSync(path.join(target, "node_modules/package/cli.js"), "utf8") === "module command");
    check("bin link dropped in target", !fs.existsSync(path.join(target, "node_modules/.bin/command")));
    check("source link preserved", fs.lstatSync(path.join(source, "node_modules/.bin/command")).isSymbolicLink());
  }
} else {
  console.log("[SKIP] symlink scenarios (Windows symlinks need privileges; set SC_ENABLE_SYMLINK_TESTS=1 to force)");
}

fs.rmSync(tmpBase, { recursive: true, force: true });
console.log(failures === 0 ? "ALL SCENARIOS PASSED" : failures + " SCENARIO CHECK(S) FAILED");
process.exit(failures === 0 ? 0 : 1);
