const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");
const { test } = require("node:test");
const { loadSource } = require("./load-source.cjs");

const state = loadSource(path.join(__dirname, "../src/lib/instance-location-state.ts"));
const plain = value => JSON.parse(JSON.stringify(value));
const local = { id: "stable-id", installDir: "stable-id", name: "Old", type: "local", installPath: "/old/Old", port: 8000 };
const result = id => ({ success: true, instanceId: id, oldPath: "/old/" + id, newPath: "/new/" + id });

test("returned native ID and path are authoritative and unrelated instances stay unchanged", () => {
  const changed = state.applyInstanceLocation(local, "stable-id", "returned-id", "/actual/New", "New");
  assert.equal(changed.id, "returned-id");
  assert.equal(changed.installDir, "returned-id");
  assert.equal(changed.installPath, "/actual/New");
  assert.equal(changed.installPathMode, "exact");
  assert.equal(changed.subtitle, "New");
  assert.equal(changed.port, 8000);
  assert.equal(state.applyInstanceLocation(local, "other", "wrong", "/wrong"), local);
});

test("unsuccessful or incomplete native relocation results cannot update the UI", () => {
  for (const response of [{ ...result("a"), success: false }, { ...result("a"), instanceId: "" }, { ...result("a"), newPath: "" }]) {
    assert.throws(() => state.requireRelocationResult(response));
  }
});

test("partial legacy migration publishes each commit and retry skips completed instances", async () => {
  const items = ["a", "b", "c"].map(instanceId => ({ instanceId, currentPath: "/old/" + instanceId, targetPath: "/new/" + instanceId }));
  const completed = new Set();
  const committed = [];
  const attempted = [];
  const onCommitted = (item, response) => { completed.add(item.instanceId); committed.push(response.instanceId); };
  await assert.rejects(state.relocateLegacyItems(items, completed, async item => {
    attempted.push(item.instanceId);
    if (item.instanceId === "b") throw new Error("network refusal");
    return result(item.instanceId);
  }, onCommitted));
  assert.deepEqual(committed, ["a"]);
  await state.relocateLegacyItems(items, completed, async item => {
    attempted.push(item.instanceId);
    return result(item.instanceId);
  }, onCommitted);
  assert.deepEqual(attempted, ["a", "b", "b", "c"]);
  assert.deepEqual(committed, ["a", "b", "c"]);
});

test("success false rejects a batch rather than displaying all complete", async () => {
  let committed = false;
  await assert.rejects(state.relocateLegacyItems([{ instanceId: "a" }], new Set(), async () => ({
    ...result("a"), success: false,
  }), () => { committed = true; }));
  assert.equal(committed, false);
});

test("retained source metadata survives a committed item", async () => {
  let retained;
  await state.relocateLegacyItems([{ instanceId: "a" }], new Set(), async () => ({
    ...result("a"), retainedSourcePath: "/private/old/a",
  }), (_, response) => { retained = response.retainedSourcePath; });
  assert.equal(retained, "/private/old/a");
});

test("closing a legacy session stops subsequent work but still publishes an already committed item", async () => {
  let current = true;
  let attempts = 0;
  const committed = [];
  await assert.rejects(state.relocateLegacyItems([{ instanceId: "a" }, { instanceId: "b" }], new Set(), async item => {
    attempts++;
    current = false;
    return result(item.instanceId);
  }, item => committed.push(item.instanceId), () => { if (!current) throw new Error("closed"); }));
  assert.equal(attempts, 1);
  assert.deepEqual(committed, ["a"]);
});

function preview(fixture = {}) {
  const filename = path.join(__dirname, "../src/dev/native-preview.ts");
  const source = ts.transpileModule(fs.readFileSync(filename, "utf8"), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true },
  }).outputText;
  const module = { exports: {} };
  const window = { __SILLYCLIENT_PREVIEW_FIXTURE__: fixture };
  const storage = new Map();
  vm.runInNewContext(source, {
    module, exports: module.exports, window, URL,
    setTimeout, clearTimeout, setInterval, clearInterval,
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    require: name => name === "@capacitor/core"
      ? { Capacitor: {} }
      : loadSource(path.resolve(path.dirname(filename), name + ".ts")),
  }, { filename });
  module.exports.installNativePreview();
  return { api: module.exports.nativePreview, harness: window.__SILLYCLIENT_TEST__ };
}

test("Android preview rename preserves stable native ID and persists the returned path", async () => {
  const { api } = preview({ platform: "android", scannedInstanceIds: ["stable-id"] });
  const renamed = await api.renameInstance({ instanceId: "stable-id", newName: "New name", installPath: "/storage/emulated/0/instances/Old" });
  assert.equal(renamed.newId, "stable-id");
  assert.equal(renamed.newPath, "/storage/emulated/0/instances/New name");
  const scanned = await api.scanInstances();
  assert.equal(scanned.instances[0].path, renamed.newPath);
  assert.equal((await api.getInstanceInfo({ instanceId: "stable-id" })).path, renamed.newPath);
});

test("Windows preview rename updates the native ID and preserves a custom parent", async () => {
  const { api } = preview({ platform: "windows", scannedInstanceIds: ["old"] });
  const renamed = await api.renameInstance({ instanceId: "old", newName: "New name", installPath: "E:\\Custom\\Old" });
  assert.equal(renamed.newId, "New name");
  assert.equal(renamed.newPath, "E:\\Custom\\New name");
  const scanned = await api.scanInstances();
  assert.deepEqual(plain(scanned.instances.map(item => item.instanceId)), ["New name"]);
});

test("preview relocation persists actual paths and reports retained source paths", async () => {
  const { api } = preview({ platform: "android", scannedInstanceIds: ["a"], storage: { retainedSourceIds: ["a"] } });
  const relocated = await api.relocateInstance({ instanceId: "a", installPath: "/private/a", targetPath: "/external/a" });
  assert.equal(relocated.retainedSourcePath, "/private/a");
  assert.equal((await api.getInstanceInfo({ instanceId: "a" })).path, "/external/a");
});

test("preview failure leaves storage state unchanged and can be retried", async () => {
  const { api, harness } = preview({ platform: "android", scannedInstanceIds: ["a"], storage: { falseRelocateIds: ["a"] } });
  const failed = await api.relocateInstance({ instanceId: "a", installPath: "/old/a", targetPath: "/new/a" });
  assert.equal(failed.success, false);
  assert.equal(failed.newPath, "/old/a");
  harness.configure({ storage: { falseRelocateIds: [] } });
  assert.equal((await api.relocateInstance({ instanceId: "a", targetPath: "/new/a" })).newPath, "/new/a");
});

test("preview legacy discovery stops reporting committed instances", async () => {
  const legacyInstances = [{ instanceId: "a", name: "A", currentPath: "/private/a", targetPath: "/external/a" }];
  const { api } = preview({ platform: "android", storage: { legacyInstances } });
  assert.equal((await api.checkLegacyInstances()).instances.length, 1);
  await api.relocateInstance({ instanceId: "a", targetPath: "/external/a" });
  assert.equal((await api.checkLegacyInstances()).instances.length, 0);
});

test("preview refuses native storage mutation while the runtime is busy", async () => {
  const { api } = preview({ status: { serverReady: true, instanceId: "a", mode: "launcher" } });
  await assert.rejects(api.renameInstance({ instanceId: "a", newName: "B" }));
  await assert.rejects(api.relocateInstance({ instanceId: "a", targetPath: "/new" }));
});

test("approved button palette, motion layers and inline async wiring remain present", () => {
  const read = filename => fs.readFileSync(path.join(__dirname, "../src", filename), "utf8");
  const running = read("components/instance/RunningConsoleCard.tsx");
  assert.ok(running.includes("text-red-900/50 hover:text-red-900/80"));
  assert.ok(running.includes("bg-white/20 border-white/15 text-white hover:bg-white/30"));
  const relocate = read("components/modals/RelocateInstanceModal.tsx");
  assert.ok(relocate.includes("LAYERS.DIALOG_SURFACE"));
  assert.ok(relocate.includes("motion-panel-stack"));
  assert.ok(relocate.includes("retainedSourcePath"));
  const stopped = read("components/instance/InstanceStoppedCard.tsx");
  assert.ok(stopped.includes("await onRenameSave"));
  assert.ok(stopped.includes("motion-instance-card"));
});
