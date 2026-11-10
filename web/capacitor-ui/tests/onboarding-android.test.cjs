const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");
const { test } = require("node:test");

const filename = path.join(__dirname, "../src/routes/index.tsx");
const ast = ts.createSourceFile(filename, fs.readFileSync(filename, "utf8"),
  ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const effects = [];
let cancel;

function visit(node) {
  if (ts.isCallExpression(node) && node.expression.getText(ast) === "useEffect"
    && node.arguments[0].getText(ast).includes('registerLayer("onboarding",')) {
    effects.push(node);
  }
  if (ts.isVariableDeclaration(node) && node.name.getText(ast) === "cancelOnboarding") {
    cancel = node.initializer.arguments[0];
  }
  ts.forEachChild(node, visit);
}
visit(ast);
assert.equal(effects.length, 1, "The parent onboarding must have one layer registration");
assert.ok(cancel, "Onboarding cancellation must remain explicit");

function evaluate(node, scope) {
  const source = ts.transpileModule(`module.exports = (${node.getText(ast)});`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const context = { ...scope, module: { exports: undefined } };
  vm.runInNewContext(source, context, { filename });
  return context.module.exports;
}

function fixture(values = {}) {
  const calls = [];
  const state = { visible: true, storageWrites: 0 };
  const cancelOnboarding = evaluate(cancel, {
    setShowOnboarding: value => { state.visible = value; },
    localStorage: { setItem: () => { state.storageWrites++; } },
  });
  const run = evaluate(effects[0].arguments[0], {
    isAndroid: true, showOnboarding: true, isOnboardingSandbox: false,
    cancelOnboarding,
    registerLayer(id, onClose) {
      const entry = { id, onClose, removed: false };
      calls.push(entry);
      return () => { entry.removed = true; };
    },
    ...values,
  });
  return { run, calls, state };
}

test("Android parent onboarding joins the existing return stack and cancels without completing", () => {
  const f = fixture();
  const dispose = f.run();
  assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].id, "onboarding");
  f.calls[0].onClose();
  assert.equal(f.state.visible, false);
  assert.equal(f.state.storageWrites, 0);
  dispose();
  assert.equal(f.calls[0].removed, true);
});

for (const [name, values] of [
  ["other platforms", { isAndroid: false }],
  ["closed onboarding", { showOnboarding: false }],
  ["isolated practice frame", { isOnboardingSandbox: true }],
]) {
  test(`${name} does not register the Android parent return handler`, () => {
    const f = fixture(values);
    assert.equal(f.run(), undefined);
    assert.equal(f.calls.length, 0);
    assert.equal(f.state.visible, true);
    assert.equal(f.state.storageWrites, 0);
  });
}

test("onboarding return registration tracks every render-dependent value", () => {
  const dependencies = effects[0].arguments[1].elements.map(node => node.getText(ast));
  assert.deepEqual(dependencies.sort(), ["cancelOnboarding", "isAndroid", "registerLayer", "showOnboarding"]);
});
