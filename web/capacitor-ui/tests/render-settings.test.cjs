const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");
const { test } = require("node:test");
const { loadSource } = require("./load-source.cjs");

const root = path.join(__dirname, "../src");
const { normalizeRenderEngineSettings } = loadSource(path.join(root, "lib/render-settings.ts"));

test("legacy render settings default the Android performance preset on without losing explicit false", () => {
  assert.equal(normalizeRenderEngineSettings().performancePreset, true);
  assert.equal(normalizeRenderEngineSettings(null).performancePreset, true);
  assert.equal(normalizeRenderEngineSettings({ immersiveColor: false }).performancePreset, true);
  assert.equal(normalizeRenderEngineSettings({ performancePreset: false }).performancePreset, false);
  assert.equal(normalizeRenderEngineSettings({ immersiveColor: false, clickEffect: true }).clickEffect, false);
  assert.equal(normalizeRenderEngineSettings({ colorExtractionMode: "once" }).colorExtractionMode, "once");
});

function previewFixture(renderSettings = {}) {
  const filename = path.join(root, "dev/native-preview.ts");
  const module = { exports: {} };
  const window = { __SILLYCLIENT_PREVIEW_FIXTURE__: { platform: "android", renderSettings } };
  const storage = new Map();
  const source = ts.transpileModule(fs.readFileSync(filename, "utf8"), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  vm.runInNewContext(source, {
    module, exports: module.exports, window, setTimeout, URL,
    localStorage: { setItem: (key, value) => storage.set(key, value), getItem: key => storage.get(key) ?? null },
    require: name => name === "@capacitor/core" ? { Capacitor: {} }
      : loadSource(path.resolve(path.dirname(filename), `${name}.ts`)),
  }, { filename });
  module.exports.installNativePreview();
  return { bridge: module.exports.nativePreview, api: window.__SILLYCLIENT_TEST__ };
}

test("real preview bridge preserves every omitted setting across partial writes", async () => {
  const { bridge } = previewFixture({ settings: { performancePreset: false, clickEffect: false, colorExtractionMode: "once" } });
  await bridge.setRenderEngineSettings({ immersiveColor: true });
  let settings = await bridge.getRenderEngineSettings();
  assert.equal(settings.performancePreset, false);
  assert.equal(settings.clickEffect, false);
  assert.equal(settings.colorExtractionMode, "once");
  await bridge.setRenderEngineSettings({ performancePreset: true });
  settings = await bridge.getRenderEngineSettings();
  assert.equal(settings.performancePreset, true);
  assert.equal(settings.clickEffect, false);
  assert.equal(settings.colorExtractionMode, "once");
  await bridge.setRenderEngineSettings({});
  assert.equal((await bridge.getRenderEngineSettings()).performancePreset, true);
});

test("real preview bridge can reject writes without mutating persisted settings", async () => {
  const { bridge, api } = previewFixture({ writeError: "storage denied" });
  await assert.rejects(bridge.setRenderEngineSettings({ performancePreset: false }), /storage denied/);
  assert.equal((await bridge.getRenderEngineSettings()).performancePreset, true);
  api.configure({ renderSettings: { writeError: null, readError: "read denied" } });
  await bridge.setRenderEngineSettings({ performancePreset: false });
  await assert.rejects(bridge.getRenderEngineSettings(), /read denied/);
  api.configure({ renderSettings: { readError: null } });
  assert.equal((await bridge.getRenderEngineSettings()).performancePreset, false);
});

const routeName = path.join(root, "routes/index.tsx");
const routeAst = ts.createSourceFile(routeName, fs.readFileSync(routeName, "utf8"),
  ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
let save;
let hydrate;
let capability;
function visit(node) {
  if (ts.isVariableDeclaration(node) && node.name.getText(routeAst) === "commitRenderSettings") {
    save = node.initializer.arguments[0];
  }
  if (ts.isCallExpression(node) && node.expression.getText(routeAst) === "useEffect"
    && node.arguments[0].getText(routeAst).includes("TarvenEnv.getRenderEngineSettings()")) {
    hydrate = node.arguments[0];
  }
  if (ts.isJsxAttribute(node) && node.name.getText(routeAst) === "canSetPerformancePreset") {
    capability = node.initializer.expression;
  }
  ts.forEachChild(node, visit);
}
visit(routeAst);
assert.ok(save);
assert.ok(hydrate);
assert.ok(capability);

function evaluate(node, scope) {
  const module = { exports: undefined };
  const source = ts.transpileModule(`module.exports = (${node.getText(routeAst)});`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  vm.runInNewContext(source, { ...scope, module });
  return module.exports;
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

function routeFixture(options = {}) {
  const state = { settings: normalizeRenderEngineSettings(), pending: false, error: null };
  const scope = {
    isMobile: true, canPreviewRenderSettings: false,
    normalizeRenderEngineSettings, humanizeNativeError: text => text,
    renderHydrated: { current: true },
    renderSettingsRef: { current: state.settings },
    renderSavePending: { current: false },
    renderSettingsGeneration: { current: 0 },
    setRenderSettings: value => { state.settings = value; },
    setRenderSettingsPending: value => { state.pending = value; },
    setRenderSettingsError: value => { state.error = value; },
    ...options,
  };
  return { state, scope, save: evaluate(save, scope), hydrate: evaluate(hydrate, scope) };
}

test("real route save rejects overlapping writes synchronously and rolls back with visible failure", async () => {
  const write = deferred();
  let count = 0;
  const f = routeFixture({ TarvenEnv: { setRenderEngineSettings: () => { count++; return write.promise; } } });
  const first = f.save({ ...f.state.settings, performancePreset: false });
  assert.equal(f.state.settings.performancePreset, false);
  assert.equal(f.state.pending, true);
  await f.save({ ...f.state.settings, performancePreset: true });
  assert.equal(count, 1);
  write.reject(new Error("storage denied"));
  await first;
  assert.equal(f.state.settings.performancePreset, true);
  assert.equal(f.scope.renderSettingsRef.current.performancePreset, true);
  assert.equal(f.state.pending, false);
  assert.equal(f.scope.renderSavePending.current, false);
  assert.match(f.state.error, /storage denied/);
});

test("late initial read cannot overwrite a newer successful user save", async () => {
  const read = deferred();
  const f = routeFixture({ TarvenEnv: {
    getRenderEngineSettings: () => read.promise,
    setRenderEngineSettings: async () => {},
  } });
  const dispose = f.hydrate();
  await f.save({ ...f.state.settings, performancePreset: false });
  read.resolve(normalizeRenderEngineSettings());
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.state.settings.performancePreset, false);
  assert.equal(f.state.error, null);
  dispose();
});

test("initial read failure reports its actual cause and cannot permit a write before hydration", async () => {
  let count = 0;
  const f = routeFixture({ renderHydrated: { current: false }, TarvenEnv: {
    getRenderEngineSettings: async () => { throw new Error("bridge unavailable"); },
    setRenderEngineSettings: async () => { count++; },
  } });
  const dispose = f.hydrate();
  await new Promise(resolve => setImmediate(resolve));
  assert.match(f.state.error, /bridge unavailable/);
  await f.save({ ...f.state.settings, performancePreset: false });
  assert.equal(count, 0);
  dispose();
});

test("cancelled settings hydration does not change state", async () => {
  const read = deferred();
  const f = routeFixture({ TarvenEnv: { getRenderEngineSettings: () => read.promise } });
  f.hydrate()();
  read.resolve({ performancePreset: false });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.state.settings.performancePreset, true);
});

test("performance preset visibility is limited to Android or ordinary local web preview", () => {
  for (const [isAndroid, isWeb, canPreviewRenderSettings, expected] of [
    [true, false, false, true], [false, false, false, false],
    [false, false, true, false], [false, true, true, true], [false, true, false, false],
  ]) {
    assert.equal(evaluate(capability, { isAndroid, isWeb, canPreviewRenderSettings }), expected);
  }
});
