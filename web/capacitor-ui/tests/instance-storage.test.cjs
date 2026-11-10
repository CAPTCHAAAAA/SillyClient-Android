const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");
const { test } = require("node:test");
const { loadSource } = require("./load-source.cjs");

const src = path.join(__dirname, "../src");
const routeFile = "routes/index.tsx";
const manageFile = "components/modals/ManageInstanceModal.tsx";
const asts = new Map();
const plain = value => JSON.parse(JSON.stringify(value));
const location = loadSource(path.join(src, "lib/install-location.ts"));
const nativeErrors = loadSource(path.join(src, "lib/native-errors.ts"));
const { OperationCoordinator, OperationCancelledError } = loadSource(path.join(src, "lib/operation-coordinator.ts"));
const release = { tag: "1.19.0", zipballUrl: "https://example.test/source.zip", prerelease: false };
const local = {
  id: "card-id", installDir: "native-id", name: "SillyTavern", subtitle: "Display name",
  type: "local", status: "stopped", installPath: "/registered/Stored name", version: "1.19.0", port: 8000,
};
const summary = { importable: true, importEntries: 3, importBytes: 4096 };

function ast(filename) {
  if (!asts.has(filename)) {
    const full = path.join(src, filename);
    asts.set(filename, ts.createSourceFile(full, fs.readFileSync(full, "utf8"),
      ts.ScriptTarget.Latest, true, filename.endsWith("tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS));
  }
  return asts.get(filename);
}

function nodes(filename, predicate) {
  const found = [];
  function visit(node) {
    if (predicate(node)) found.push(node);
    ts.forEachChild(node, visit);
  }
  visit(ast(filename));
  return found;
}

function declaration(filename, name) {
  const found = nodes(filename, node => ts.isVariableDeclaration(node) &&
    (ts.isIdentifier(node.name) ? node.name.text === name :
      ts.isArrayBindingPattern(node.name) && node.name.elements.some(element =>
        ts.isBindingElement(element) && ts.isIdentifier(element.name) && element.name.text === name)));
  assert.equal(found.length, 1, `Expected one actual declaration of ${name} in ${filename}`);
  return found[0];
}

function callbackSource(filename, name) {
  const initializer = declaration(filename, name).initializer;
  const expression = ts.isCallExpression(initializer) && initializer.expression.getText(ast(filename)) === "useCallback"
    ? initializer.arguments[0] : initializer;
  assert.ok(ts.isArrowFunction(expression) || ts.isFunctionExpression(expression), `${name} must remain a real callback`);
  return expression.getText(ast(filename));
}

function evaluate(source, context, filename = "storage-source.tsx") {
  context.module = { exports: {} };
  const result = ts.transpileModule(`module.exports = (${source});`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.React },
    fileName: "storage-source.tsx",
  }).outputText;
  vm.runInContext(result, context, { filename });
  return context.module.exports;
}

function functionFromSource(filename, name, context) {
  const found = nodes(filename, node => ts.isFunctionDeclaration(node) && node.name?.text === name);
  assert.equal(found.length, 1, `Expected actual function ${name}`);
  const node = found[0];
  const expression = ts.factory.createFunctionExpression(
    node.modifiers?.filter(modifier => modifier.kind === ts.SyntaxKind.AsyncKeyword),
    node.asteriskToken, node.name, node.typeParameters, node.parameters, node.type, node.body,
  );
  return evaluate(ts.createPrinter().printNode(ts.EmitHint.Expression, expression, ast(filename)), context, filename);
}

function context(values = {}) {
  return vm.createContext({
    Error, URL, AbortController, Date, console, setTimeout, clearTimeout, setInterval, clearInterval,
    ...values,
  });
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve();
}

function nativeFixture(overrides = {}) {
  const calls = [];
  const defaults = {
    getAppSettings: async () => ({}),
    setInstancesRoot: async options => ({ instancesRoot: options.path, configured: true }),
    pickDirectory: async () => ({ path: "/picked/Parent", installPathMode: "root" }),
    addListener: async () => ({ remove() {} }),
    provisionAndStart: async () => ({ ready: true }),
    enterImmersive: async () => {},
    getInstanceInfo: async options => ({ path: options.installPath, version: "1.19.0" }),
    fetchReleases: async () => ({ releases: [release] }),
    relocateInstance: async options => ({ success: true, newPath: options.targetPath }),
    pickZipFile: async () => ({ path: "/imports/data.zip" }),
    inspectImportArchive: async () => summary,
    importInstanceData: async () => ({ imported: 3, bytes: 4096 }),
    exportInstance: async () => ({ path: "/exports/data.zip", bytes: 8192 }),
  };
  const api = {};
  for (const method of Object.keys(defaults)) {
    api[method] = async options => {
      calls.push({ method, options: options && plain(options) });
      return (overrides[method] || defaults[method])(options);
    };
  }
  return { api, calls, called: method => calls.filter(call => call.method === method) };
}

function routeFixture(values = {}, overrides = {}) {
  const native = nativeFixture(overrides);
  const scope = context({
    ...location, ...nativeErrors, ...loadSource(path.join(src, "lib/tavern-release.ts")),
    TarvenEnv: native.api, OperationCancelledError,
    React: { createElement: (type, props) => ({ type, props }) }, Folder: "Folder", Cloud: "Cloud",
    operations: new OperationCoordinator(), directoryPickerGeneration: { current: 0 },
    lastLaunchCreateIntentRef: { current: false },
    isWeb: false, isAndroid: true, showNewInstancePanel: true, newInstanceMode: "local",
    newInstanceDir: "/picked/Parent", newInstanceName: "New instance", newInstanceLocalZip: null,
    newInstanceVersion: "1.19.0", newInstanceUrl: "http://", newInstanceExtensionIds: [],
    newInstanceCompanionPresetEnabled: false, newRemoteAuthEnabled: false,
    newRemoteAuthUsername: "", newRemoteAuthPassword: "", instances: [], releases: [release], launchLogs: [],
    ...values,
  });
  scope.DEFAULT_CONFIG = evaluate(declaration("capacitor-plugin.ts", "DEFAULT_CONFIG").initializer.getText(), scope);
  scope.normalizeStoredVersion = functionFromSource("lib/utils.ts", "normalizeStoredVersion", scope);
  scope.formatNativeDate = functionFromSource(routeFile, "formatNativeDate", scope);
  scope.formatUsageDuration = functionFromSource(routeFile, "formatUsageDuration", scope);
  const names = ["resolveInstanceParent", "handleSetNewInstanceDir", "handlePickInstallFolder",
    "doLaunch", "provisionCreatedInstance", "createInstance"];
  for (const name of names) {
    for (const call of nodes(routeFile, node => ts.isCallExpression(node) &&
      ts.isIdentifier(node.expression) && /^set[A-Z]/.test(node.expression.text))) {
      const setter = call.expression.text;
      const field = setter[3].toLowerCase() + setter.slice(4);
      scope[setter] ||= value => { scope[field] = typeof value === "function" ? value(scope[field]) : value; };
    }
    scope[name] = evaluate(callbackSource(routeFile, name), scope, `${routeFile}:${name}`);
  }
  const settingsEffects = nodes(routeFile, node => ts.isCallExpression(node) &&
    ts.isIdentifier(node.expression) && node.expression.text === "useEffect" &&
    node.arguments[0]?.getText(ast(routeFile)).includes("TarvenEnv.getAppSettings()"));
  assert.equal(settingsEffects.length, 1);
  scope.prefill = evaluate(settingsEffects[0].arguments[0].getText(ast(routeFile)), scope);
  return { scope, ...native };
}

// Run actual component hooks and effects; no DOM, native storage or business logic is simulated here.
function hookHost() {
  const slots = [];
  let cursor = 0;
  let dirty = false;
  let pendingEffects = [];
  const hooks = {
    useState(initial) {
      const index = cursor++;
      if (!slots[index]) slots[index] = { value: typeof initial === "function" ? initial() : initial };
      return [slots[index].value, value => {
        const next = typeof value === "function" ? value(slots[index].value) : value;
        if (!Object.is(next, slots[index].value)) { slots[index].value = next; dirty = true; }
      }];
    },
    useRef(initial) {
      const index = cursor++;
      slots[index] ||= { current: initial };
      return slots[index];
    },
    useEffect(callback, deps) {
      const index = cursor++;
      const previous = slots[index];
      if (!previous || !deps || deps.some((value, i) => !Object.is(value, previous.deps?.[i]))) {
        pendingEffects.push(() => {
          previous?.cleanup?.();
          slots[index] = { deps, cleanup: callback() };
        });
      }
    },
  };
  return {
    hooks,
    render(component, props) {
      let result;
      let renders = 0;
      do {
        assert.ok(++renders < 20, "Actual component effects did not settle");
        dirty = false;
        cursor = 0;
        pendingEffects = [];
        result = component(props);
        pendingEffects.forEach(effect => effect());
      } while (dirty);
      return result;
    },
  };
}

function relocateFixture(overrides = {}, props = {}, platform = "android") {
  const native = nativeFixture({
    getInstanceInfo: async () => ({ path: "/source/Physical name" }),
    ...overrides,
  });
  const host = hookHost();
  const react = {
    ...host.hooks, Fragment: "Fragment",
    createElement: (type, attributes, ...children) => ({ type, props: { ...attributes, children } }),
  };
  const filename = path.join(src, "components/modals/RelocateInstanceModal.tsx");
  const scope = context({ require: name => {
    if (name === "react") return react;
    if (name === "@capacitor/core") return { Capacitor: { getPlatform: () => platform } };
    if (name === "lucide-react") return { X: "X", AlertCircle: "AlertCircle", Loader2: "Loader2", Info: "Info" };
    if (name.endsWith("/utils")) return { cn: (...parts) => parts.filter(Boolean).join(" "), formatDisplayVersion: String };
    if (name.endsWith("/layers")) return { LAYERS: { DIALOG_SURFACE: 2, DIALOG_BACKDROP: 1 } };
    if (name.endsWith("/LayerBackdrop")) return { LayerBackdrop: "LayerBackdrop" };
    if (name.endsWith("/capacitor-plugin")) return { TarvenEnv: native.api };
    if (name.endsWith("/install-location")) return location;
    if (name.endsWith("/native-errors")) return nativeErrors;
    throw new Error(`Unexpected component import: ${name}`);
  } });
  scope.module = { exports: {} };
  scope.exports = scope.module.exports;
  const code = ts.transpileModule(fs.readFileSync(filename, "utf8"), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.React, esModuleInterop: true },
    fileName: filename,
  }).outputText;
  vm.runInContext(code, scope, { filename });
  const committed = [];
  let currentProps = { instance: local, isOpen: true, isLight: true, glassBg: "",
    onClose() {}, onRelocated: (id, newPath) => committed.push({ id, newPath }), ...props };
  const render = changes => {
    currentProps = { ...currentProps, ...changes };
    return host.render(scope.module.exports.RelocateInstanceModal, currentProps);
  };
  function find(predicate, node = render()) {
    if (!node || typeof node !== "object") return undefined;
    if (predicate(node)) return node;
    for (const child of node.props?.children?.flat(Infinity) || []) {
      const found = find(predicate, child);
      if (found) return found;
    }
    return undefined;
  }
  function text(node) {
    if (node == null || typeof node === "boolean") return "";
    if (typeof node !== "object") return String(node);
    return (node.props?.children || []).flat(Infinity).map(text).join("");
  }
  const button = label => {
    const found = find(node => node.type === "button" && text(node).includes(label));
    assert.ok(found, `Actual button ${label} was not rendered`);
    return found;
  };
  render();
  return { ...native, render, button, committed, text: () => text(render()),
    input: () => find(node => node.type === "input").props };
}

function manageFixture(instance = local, overrides = {}) {
  const native = nativeFixture(overrides);
  const host = hookHost();
  const names = ["exportStatus", "isExporting", "importArchive", "importIncludeOptional", "importStatus",
    "isInspectingImport", "isImporting", "localAboutInfo", "mp", "isLocalInstance", "isInstanceRunning",
    "selectImportArchive", "importSelectedArchive", "exportInstanceArchive"];
  const statements = names.map(name => declaration(manageFile, name).parent.parent.getText(ast(manageFile)));
  const reset = nodes(manageFile, node => ts.isExpressionStatement(node) && ts.isCallExpression(node.expression) &&
    node.expression.expression.getText(ast(manageFile)) === "useEffect" &&
    node.expression.arguments[0]?.getText(ast(manageFile)).includes("setImportIncludeOptional(false)"));
  assert.equal(reset.length, 1);
  const scope = context({ ...host.hooks, TarvenEnv: native.api });
  scope.formatArchiveBytes = functionFromSource(manageFile, "formatArchiveBytes", scope);
  scope.isNativeCancelError = functionFromSource(manageFile, "isNativeCancelError", scope);
  const component = evaluate(`(instance: any) => {
    ${statements.join("\n")}
    ${reset[0].getText(ast(manageFile))}
    return { exportStatus, isExporting, importArchive, importIncludeOptional, importStatus,
      isInspectingImport, isImporting, selectImportArchive, importSelectedArchive,
      exportInstanceArchive, setImportIncludeOptional };
  }`, scope, `${manageFile}:storage`);
  let selected = instance;
  return { ...native, render(next = selected) { selected = next; return host.render(component, selected); } };
}

test("Android creation requires an explicit directory and never falls back to a default", async () => {
  const f = routeFixture({ newInstanceDir: "" });
  await f.scope.createInstance();
  assert.match(f.scope.newInstanceError, /必选/);
  assert.equal(f.scope.operations.current, null);
  assert.equal(f.calls.length, 0);
});

test("native configured root prefill uses only configuredInstancesRoot", async () => {
  const f = routeFixture({ newInstanceDir: "" }, { getAppSettings: async () => ({
    configuredInstancesRoot: ' "/selected/Parent" ', instancesRoot: "/private/default", defaultInstancesRoot: "/private/default",
  }) });
  f.scope.prefill();
  await flush();
  assert.equal(f.scope.newInstanceDir, "/selected/Parent");
  assert.equal(f.called("setInstancesRoot").length, 0);
  const absent = routeFixture({ newInstanceDir: "" }, { getAppSettings: async () => ({
    instancesRoot: "/private/default", defaultInstancesRoot: "/private/default",
  }) });
  absent.scope.prefill();
  await flush();
  assert.equal(absent.scope.newInstanceDir, "");
});

test("late settings prefill respects user edits, explicit clearing, and closed sessions", async () => {
  for (const action of ["edit", "clear", "close"]) {
    const response = deferred();
    const f = routeFixture({ newInstanceDir: "" }, { getAppSettings: () => response.promise });
    const cleanup = f.scope.prefill();
    if (action === "close") cleanup();
    else f.scope.handleSetNewInstanceDir(action === "edit" ? "/user/Choice" : "");
    response.resolve({ configuredInstancesRoot: "/late/Choice" });
    await flush();
    assert.equal(f.scope.newInstanceDir, action === "edit" ? "/user/Choice" : "");
  }
});

test("unavailable settings leave directory selection required", async () => {
  const f = routeFixture({ newInstanceDir: "" }, { getAppSettings: async () => { throw new Error("unavailable"); } });
  f.scope.prefill();
  await flush();
  await f.scope.createInstance();
  assert.equal(f.called("provisionAndStart").length, 0);
  assert.equal(f.scope.newInstanceDir, "");
});

test("failed parent registration aborts creation before native provisioning", async () => {
  const f = routeFixture({}, { setInstancesRoot: async () => { throw new Error("permission denied"); } });
  await f.scope.createInstance();
  assert.equal(f.called("provisionAndStart").length, 0);
  assert.equal(f.called("fetchReleases").length, 0);
  assert.equal(f.scope.instances.length, 0);
  assert.equal(f.scope.isCreatingInstance, false);
  assert.match(f.scope.newInstanceError, /不能作为实例存放位置/);
});

test("creation carries createIntent and a local ZIP source while persisting only the native instance path", async () => {
  const f = routeFixture({ newInstanceDir: ' "/picked/New instance" ', newInstanceLocalZip: "/source/local.zip" });
  await f.scope.createInstance();
  const payload = f.called("provisionAndStart")[0].options;
  assert.equal(payload.createIntent, true);
  assert.equal(payload.localZipPath, "/source/local.zip");
  assert.equal(payload.version, "local");
  assert.equal(payload.installPath, "/picked/New instance/New instance");
  assert.equal(payload.installPathMode, "exact");
  assert.equal(f.called("setInstancesRoot")[0].options.path, "/picked/New instance");
  assert.equal(f.called("fetchReleases").length, 0);
  assert.equal(f.scope.instances.length, 1);
  assert.equal(f.scope.instances[0].installPath, payload.installPath);
  for (const field of ["zipballUrl", "localZipPath", "preinstall", "companionPreset"]) {
    assert.equal(Object.hasOwn(f.scope.instances[0], field), false);
    assert.equal(Object.hasOwn(f.scope.lastLaunchParams, field), false);
  }
});

test("release creation keeps its selected archive URL and explicit create intent", async () => {
  const f = routeFixture();
  await f.scope.createInstance();
  const payload = f.called("provisionAndStart")[0].options;
  assert.equal(payload.createIntent, true);
  assert.equal(payload.zipballUrl, release.zipballUrl);
  assert.equal(payload.localZipPath, undefined);
});

test("starting an existing card cannot replay one-time ZIP or preset creation parameters", async () => {
  const f = routeFixture();
  const operation = f.scope.operations.begin(local.installDir, "launch");
  await f.scope.doLaunch({ ...local, localZipPath: "/stale.zip", zipballUrl: release.zipballUrl,
    companionPreset: { id: "stale" }, preinstall: { revision: 1, extensionIds: ["dice"] } }, operation);
  const payload = f.called("provisionAndStart")[0].options;
  assert.equal(payload.createIntent, false);
  for (const field of ["zipballUrl", "localZipPath", "preinstall", "companionPreset"]) {
    assert.equal(Object.hasOwn(payload, field), false);
  }
  operation.finish();
});

test("an unready native creation never adds a card", async () => {
  const f = routeFixture({}, { provisionAndStart: async () => ({ ready: false }) });
  await f.scope.createInstance();
  assert.equal(f.scope.instances.length, 0);
  assert.match(f.scope.launchError, /未能启动/);
  assert.equal(f.scope.isCreatingInstance, false);
});

test("creation picker registers installation-purpose parents and clears rejected roots", async () => {
  const f = routeFixture({}, { setInstancesRoot: async () => { throw new Error("denied"); } });
  await f.scope.handlePickInstallFolder();
  assert.deepEqual(f.called("pickDirectory")[0].options, { purpose: "installation" });
  assert.equal(f.scope.newInstanceDir, "");
  assert.match(f.scope.newInstanceError, /不能作为实例存放位置/);
  assert.equal(f.called("provisionAndStart").length, 0);
});

test("late creation picker results and root failures cannot erase a newer directory choice", async () => {
  for (const stage of ["picker", "registration"]) {
    const response = deferred();
    const f = routeFixture({}, stage === "picker" ? { pickDirectory: () => response.promise } :
      { setInstancesRoot: () => response.promise });
    const pending = f.scope.handlePickInstallFolder();
    await flush();
    f.scope.handleSetNewInstanceDir("/new/Choice");
    if (stage === "picker") response.resolve({ path: "/stale/Choice", installPathMode: "root" });
    else response.reject(new Error("stale registration failure"));
    await pending;
    assert.equal(f.scope.newInstanceDir, "/new/Choice");
    assert.equal(f.scope.newInstanceError, null);
    if (stage === "picker") assert.equal(f.called("setInstancesRoot").length, 0);
  }
});

test("relocation picks a registered parent and preserves the actual physical basename", async () => {
  const f = relocateFixture({ pickDirectory: async () => ({ path: ' "/picked/Physical name" ', installPathMode: "root" }) });
  await flush();
  assert.equal(f.input().readOnly, true);
  await f.button("浏览").props.onClick();
  assert.deepEqual(f.called("pickDirectory")[0].options, { purpose: "installation" });
  assert.equal(f.called("setInstancesRoot")[0].options.path, "/picked/Physical name");
  await f.button("开始无损迁移").props.onClick();
  assert.deepEqual(f.called("relocateInstance")[0].options, {
    instanceId: "native-id", installPath: local.installPath, targetPath: "/picked/Physical name/Physical name",
  });
  assert.deepEqual(f.committed, [{ id: local.id, newPath: "/picked/Physical name/Physical name" }]);
});

test("relocation waits for the native source path and never moves using the card's cached basename", async () => {
  const response = deferred();
  const f = relocateFixture({ getInstanceInfo: () => response.promise });
  assert.doesNotMatch(f.text(), /Stored name/);
  await f.button("浏览").props.onClick();
  assert.equal(f.input().value, "/picked/Parent");
  assert.equal(f.button("开始无损迁移").props.disabled, true);
  await f.button("开始无损迁移").props.onClick();
  assert.equal(f.called("relocateInstance").length, 0);

  response.resolve({ path: "/native/Physical name" });
  await flush();
  assert.equal(f.button("开始无损迁移").props.disabled, false);
  await f.button("开始无损迁移").props.onClick();
  assert.deepEqual(f.called("relocateInstance")[0].options, {
    instanceId: "native-id", installPath: local.installPath, targetPath: "/picked/Parent/Physical name",
  });
  assert.equal(f.called("getInstanceInfo").length, 1);
});

test("native source path query failures stay visible and prevent relocation after picking a target", async () => {
  const response = deferred();
  const f = relocateFixture({ getInstanceInfo: () => response.promise });
  response.reject(new Error("无法访问原目录"));
  await flush();
  assert.match(f.text(), /无法读取实例的实际存储目录：无法访问原目录/);
  await f.button("浏览").props.onClick();
  assert.match(f.text(), /无法读取实例的实际存储目录：无法访问原目录/);
  assert.doesNotMatch(f.text(), /Stored name/);
  assert.equal(f.button("开始无损迁移").props.disabled, true);
  await f.button("开始无损迁移").props.onClick();
  assert.equal(f.called("relocateInstance").length, 0);
  assert.equal(f.called("getInstanceInfo").length, 1);
});

test("empty native source paths cannot fall back to the stored card path", async () => {
  for (const path of [undefined, "", "  ", ' "" ']) {
    const f = relocateFixture({ getInstanceInfo: async () => ({ path }) });
    await flush();
    await f.button("浏览").props.onClick();
    assert.match(f.text(), /无法确认实例的实际存储目录：原生未返回有效路径/);
    assert.doesNotMatch(f.text(), /Stored name/);
    assert.equal(f.button("开始无损迁移").props.disabled, true);
    await f.button("开始无损迁移").props.onClick();
    assert.equal(f.called("relocateInstance").length, 0);
  }
});

test("native source path confirmation belongs to the full card and storage identity", async () => {
  for (const change of [
    { id: "next-card" }, { installDir: "next-native" }, { installPath: "/registered/Next stored name" },
  ]) {
    const first = deferred();
    const second = deferred();
    let queries = 0;
    const f = relocateFixture({ getInstanceInfo: () => (++queries === 1 ? first.promise : second.promise) });
    const next = { ...local, ...change };
    f.render({ instance: next });
    await f.button("浏览").props.onClick();
    first.resolve({ path: "/native/Stale physical name" });
    await flush();
    assert.doesNotMatch(f.text(), /Stale physical name|Next stored name/);
    assert.equal(f.button("开始无损迁移").props.disabled, true);
    await f.button("开始无损迁移").props.onClick();
    assert.equal(f.called("relocateInstance").length, 0);

    second.resolve({ path: "/native/Current physical name" });
    await flush();
    assert.equal(f.button("开始无损迁移").props.disabled, false);
    await f.button("开始无损迁移").props.onClick();
    assert.deepEqual(f.called("relocateInstance")[0].options, {
      instanceId: next.installDir || next.id, installPath: next.installPath,
      targetPath: "/picked/Parent/Current physical name",
    });
    assert.deepEqual(f.committed, [{ id: next.id, newPath: "/picked/Parent/Current physical name" }]);
    assert.equal(queries, 2);
  }
});

test("late native source failures from a closed session cannot overwrite the reopened instance", async () => {
  const first = deferred();
  const second = deferred();
  let queries = 0;
  const f = relocateFixture({ getInstanceInfo: () => (++queries === 1 ? first.promise : second.promise) });
  f.render({ isOpen: false });
  f.render({ isOpen: true });
  await f.button("浏览").props.onClick();
  second.resolve({ path: "/native/Reopened physical name" });
  await flush();
  first.reject(new Error("stale query failure"));
  await flush();
  assert.doesNotMatch(f.text(), /stale query failure|无法读取实例/);
  assert.equal(f.button("开始无损迁移").props.disabled, false);
  await f.button("开始无损迁移").props.onClick();
  assert.equal(f.called("relocateInstance")[0].options.targetPath, "/picked/Parent/Reopened physical name");
  assert.equal(queries, 2);
});

test("other platforms keep complete target paths and are not blocked on source path queries", async () => {
  for (const platform of ["ios", "windows"]) {
    const response = deferred();
    const f = relocateFixture({ getInstanceInfo: () => response.promise }, {}, platform);
    assert.equal(f.input().readOnly, false);
    assert.match(f.text(), /Stored name/);
    f.input().onChange({ target: { value: "/picked/Full target" } });
    assert.equal(f.button("开始无损迁移").props.disabled, false);
    await f.button("开始无损迁移").props.onClick();
    assert.equal(f.called("relocateInstance")[0].options.targetPath, "/picked/Full target");
    assert.equal(f.called("setInstancesRoot").length, 0);
    response.reject(new Error("late query failure"));
    await flush();
  }
});

test("failed relocation root registration clears the field and cannot invoke a move", async () => {
  let rejectRoot = false;
  const f = relocateFixture({ setInstancesRoot: async () => {
    if (rejectRoot) throw new Error("denied");
    return { configured: true };
  } });
  await flush();
  await f.button("浏览").props.onClick();
  assert.equal(f.input().value, "/picked/Parent");
  rejectRoot = true;
  await f.button("浏览").props.onClick();
  assert.equal(f.input().value, "");
  assert.match(f.text(), /不能作为实例存放位置/);
  await f.button("开始无损迁移").props.onClick();
  assert.equal(f.called("relocateInstance").length, 0);
});

test("relocation ignores a late picker from an earlier instance", async () => {
  const response = deferred();
  const f = relocateFixture({ pickDirectory: () => response.promise });
  const pending = f.button("浏览").props.onClick();
  f.render({ instance: { ...local, id: "another-card", installDir: "another-native" } });
  response.resolve({ path: "/stale/Parent", installPathMode: "root" });
  await pending;
  assert.equal(f.input().value, "");
  assert.equal(f.called("setInstancesRoot").length, 0);
});

test("late relocation registration failures do not mutate a newly opened instance", async () => {
  const response = deferred();
  const f = relocateFixture({ setInstancesRoot: () => response.promise });
  const pending = f.button("浏览").props.onClick();
  await flush();
  f.render({ isOpen: false });
  f.render({ isOpen: true, instance: { ...local, id: "another-card" } });
  response.reject(new Error("stale permission failure"));
  await pending;
  assert.equal(f.input().value, "");
  assert.doesNotMatch(f.text(), /stale permission failure/);
  assert.equal(f.button("浏览").props.disabled, false);
});

test("an already committed relocation persists its result after the old modal closes", async () => {
  const response = deferred();
  const f = relocateFixture({ relocateInstance: () => response.promise });
  await flush();
  await f.button("浏览").props.onClick();
  const pending = f.button("开始无损迁移").props.onClick();
  f.render({ isOpen: false });
  f.render({ isOpen: true, instance: { ...local, id: "another-card" } });
  response.resolve({ success: true, newPath: "/native/actual-result" });
  await pending;
  assert.deepEqual(f.committed, [{ id: local.id, newPath: "/native/actual-result" }]);
  assert.doesNotMatch(f.text(), /实例数据无损迁移完成|actual-result/);
});

test("running instances cannot execute a relocation even if its button handler is invoked", async () => {
  for (const status of ["running", "online"]) {
    const f = relocateFixture({}, { instance: { ...local, status } });
    await f.button("开始无损迁移").props.onClick();
    assert.equal(f.called("relocateInstance").length, 0);
  }
});

test("relocation directory cancellation is silent and restores the picker busy state", async () => {
  const f = relocateFixture({ pickDirectory: async () => { throw new Error("cancelled"); } });
  await f.button("浏览").props.onClick();
  assert.equal(f.input().value, "");
  assert.equal(f.button("浏览").props.disabled, false);
  assert.equal(f.called("setInstancesRoot").length, 0);
  assert.doesNotMatch(f.text(), /cancelled/);
});

for (const selected of [
  { path: " /imports/data.zip " },
  { uri: " content://documents/provider/42 " },
  { path: " /imports/cache.zip ", uri: " content://documents/provider/42 " },
]) {
  test(`ZIP selection and import preserve ${selected.path ? "path" : ""}${selected.uri ? "+URI" : ""} and native instance identity`, async () => {
    const f = manageFixture(local, { pickZipFile: async () => selected });
    await f.render().selectImportArchive();
    const expected = Object.fromEntries(Object.entries(selected).map(([key, value]) => [
      key === "path" ? "archivePath" : "archiveUri", value.trim(),
    ]));
    assert.deepEqual(f.called("pickZipFile")[0].options, { purpose: "dataImport" });
    assert.deepEqual(f.called("inspectImportArchive")[0].options, expected);
    assert.equal(f.render().importIncludeOptional, false);
    await f.render().importSelectedArchive();
    assert.deepEqual(f.called("importInstanceData")[0].options, {
      instanceId: local.installDir, installPath: local.installPath, ...expected, includeOptional: false,
    });
    assert.equal(f.render().isImporting, false);
    assert.match(f.render().importStatus, /已导入 3 项/);
  });
}

test("ZIP optional configuration and secrets require explicit consent and reset on instance switch", async () => {
  const f = manageFixture();
  await f.render().selectImportArchive();
  f.render().setImportIncludeOptional(true);
  await f.render().importSelectedArchive();
  assert.equal(f.called("importInstanceData")[0].options.includeOptional, true);
  f.render({ ...local, id: "another-card", installDir: "another-native" });
  assert.equal(f.render().importIncludeOptional, false);
  assert.equal(f.render().importArchive, null);
});

test("ZIP import refuses running, online, remote, missing and non-importable selections", async () => {
  for (const instance of [{ ...local, status: "running" }, { ...local, status: "online" }, { ...local, type: "remote" }]) {
    const f = manageFixture(instance);
    await f.render().selectImportArchive();
    await f.render().importSelectedArchive();
    assert.equal(f.called("importInstanceData").length, 0);
  }
  const empty = manageFixture();
  await empty.render().importSelectedArchive();
  assert.equal(empty.called("importInstanceData").length, 0);
  const invalid = manageFixture(local, { inspectImportArchive: async () => ({ ...summary, importable: false }) });
  await invalid.render().selectImportArchive();
  await invalid.render().importSelectedArchive();
  assert.equal(invalid.called("importInstanceData").length, 0);
});

test("ZIP selection without either a path or URI never inspects or imports", async () => {
  const f = manageFixture(local, { pickZipFile: async () => ({}) });
  await f.render().selectImportArchive();
  await f.render().importSelectedArchive();
  assert.equal(f.called("inspectImportArchive").length, 0);
  assert.equal(f.called("importInstanceData").length, 0);
  assert.equal(f.render().importArchive, null);
  assert.match(f.render().importStatus, /未选择 ZIP/);
  assert.equal(f.render().isInspectingImport, false);
});

test("canceled ZIP selection clears the old archive and leaves no error or busy state", async () => {
  let cancel = false;
  const f = manageFixture(local, { pickZipFile: async () => {
    if (cancel) throw { message: "User canceled the picker" };
    return { path: "/imports/first.zip" };
  } });
  await f.render().selectImportArchive();
  cancel = true;
  await f.render().selectImportArchive();
  assert.equal(f.render().importArchive, null);
  assert.equal(f.render().importStatus, null);
  assert.equal(f.render().isInspectingImport, false);
});

test("canceled native ZIP import leaves the selected archive retryable and clears busy state", async () => {
  let cancel = true;
  const f = manageFixture(local, { importInstanceData: async () => {
    if (cancel) throw new Error("operation cancelled");
    return { imported: 3, bytes: 4096 };
  } });
  await f.render().selectImportArchive();
  await f.render().importSelectedArchive();
  assert.equal(f.render().importStatus, null);
  assert.equal(f.render().isImporting, false);
  assert.ok(f.render().importArchive);
  cancel = false;
  await f.render().importSelectedArchive();
  assert.match(f.render().importStatus, /已导入 3 项/);
});

test("a pending ZIP inspection cannot open a second picker", async () => {
  const response = deferred();
  const f = manageFixture(local, { inspectImportArchive: () => response.promise });
  const pending = f.render().selectImportArchive();
  await flush();
  await f.render().selectImportArchive();
  assert.equal(f.called("pickZipFile").length, 1);
  response.resolve(summary);
  await pending;
  assert.equal(f.render().isInspectingImport, false);
});

for (const cancellation of [{ cancelled: true }, { canceled: true }]) {
  test(`ZIP export accepts native ${Object.keys(cancellation)[0]} and clears busy state`, async () => {
    const f = manageFixture(local, { exportInstance: async () => cancellation });
    await f.render().exportInstanceArchive();
    assert.deepEqual(f.called("exportInstance")[0].options, { instanceId: local.installDir, installPath: local.installPath });
    assert.equal(f.render().exportStatus, null);
    assert.equal(f.render().isExporting, false);
  });
}

test("ZIP export cancellation exceptions are silent, and a retry reports the actual saved filename", async () => {
  let cancel = true;
  const f = manageFixture(local, { exportInstance: async () => {
    if (cancel) throw { message: "User canceled save" };
    return { path: "/chosen/actual-name.zip", bytes: 4096 };
  } });
  await f.render().exportInstanceArchive();
  assert.equal(f.render().exportStatus, null);
  assert.equal(f.render().isExporting, false);
  cancel = false;
  await f.render().exportInstanceArchive();
  assert.equal(f.render().exportStatus, "已导出：actual-name.zip");
});

function preview(fixture = {}) {
  const filename = path.join(src, "dev/native-preview.ts");
  const code = ts.transpileModule(fs.readFileSync(filename, "utf8"), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true },
  }).outputText;
  const module = { exports: {} };
  const window = { __SILLYCLIENT_PREVIEW_FIXTURE__: fixture };
  const storage = new Map();
  vm.runInNewContext(code, {
    module, exports: module.exports, window, URL, setTimeout, clearTimeout, setInterval, clearInterval,
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    require: name => name === "@capacitor/core" ? { Capacitor: {} } :
      loadSource(path.resolve(path.dirname(filename), name + ".ts")),
  }, { filename });
  module.exports.installNativePreview();
  return { api: module.exports.nativePreview, harness: window.__SILLYCLIENT_TEST__ };
}

test("native preview carries URI-only ZIP selection through its real import contract", async () => {
  const { api, harness } = preview({ platform: "android", zipSelection: { uri: "content://provider/backup" } });
  const selected = await api.pickZipFile({ purpose: "dataImport" });
  assert.equal(selected.path, undefined);
  const options = { instanceId: "native-id", installPath: "/chosen/Tavern", archiveUri: selected.uri, includeOptional: false };
  assert.equal((await api.inspectImportArchive({ archiveUri: selected.uri })).importable, true);
  await api.importInstanceData(options);
  assert.deepEqual(plain(harness.calls.find(call => call.method === "importInstanceData").options), options);
});

test("native preview supports both export cancellation forms and explicit picker cancellation", async () => {
  const { api, harness } = preview({ platform: "android", zipSelection: null });
  await assert.rejects(api.pickZipFile({ purpose: "dataImport" }), /cancel/);
  for (const cancellation of [{ cancelled: true }, { canceled: true }]) {
    harness.configure({ exportResult: cancellation });
    assert.deepEqual(plain(await api.exportInstance({ instanceId: "native-id" })), cancellation);
  }
});
