const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const vm = require("node:vm");
const ts = require("typescript");

const filename = path.join(__dirname, "../src/components/onboarding/OnboardingGuide.tsx");
const source = ts.createSourceFile(filename, fs.readFileSync(filename, "utf8"),
  ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
let inputEffect;

function visit(node) {
  if (ts.isCallExpression(node) && node.expression.getText(source) === "useEffect"
    && node.arguments[0].getText(source).includes("const handlePointerDown")) {
    assert.equal(inputEffect, undefined, "The guide must have a single input effect");
    inputEffect = node.arguments[0];
  }
  ts.forEachChild(node, visit);
}
visit(source);
assert.ok(inputEffect, "The guide input effect must be present");

class FakeElement {
  constructor(parent = null) {
    this.parentElement = parent;
    this.isConnected = true;
    this.reordering = false;
  }

  contains(element) {
    for (let current = element; current; current = current.parentElement) {
      if (current === this) return true;
    }
    return false;
  }

  closest() { return null; }
  matches(selector) { return selector === '[data-reordering="true"]' && this.reordering; }
}

function event(type, target, values = {}) {
  return {
    type, target, isPrimary: true, button: 0, pointerId: 1,
    clientX: 20, clientY: 30, detail: 1, touches: [],
    defaultPrevented: false, propagationStopped: false,
    preventDefault() { this.defaultPrevented = true; },
    stopPropagation() { this.propagationStopped = true; },
    ...values,
  };
}

function fixture(interaction = "press", id = "card-expand") {
  const listeners = new Map();
  const windowListeners = new Map();
  const target = new FakeElement();
  const child = new FakeElement(target);
  const other = new FakeElement();
  const pointerSessionRef = { current: null };
  const targetElementRef = { current: target };
  const closingRef = { current: false };
  const pendingAdvanceRef = { current: false };
  const step = { id, interaction };
  let targetStep = step;
  let inputReady = true;
  let hit = child;
  let advances = 0;
  let cleanup;
  const document = {
    addEventListener(name, handler) { listeners.set(name, handler); },
    removeEventListener(name, handler) {
      if (listeners.get(name) === handler) listeners.delete(name);
    },
    elementFromPoint() { return hit; },
  };
  const window = {
    addEventListener(name, handler) { windowListeners.set(name, handler); },
    removeEventListener(name, handler) {
      if (windowListeners.get(name) === handler) windowListeners.delete(name);
    },
    setTimeout() { return 1; },
  };
  const compiled = ts.transpileModule(`module.exports = (${inputEffect.getText(source)});`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;

  function install() {
    cleanup?.();
    const context = {
      module: { exports: undefined }, document, window,
      getEventElement: value => value instanceof FakeElement ? value : null,
      closingRef, pendingAdvanceRef, inputReady, targetElementRef, pointerSessionRef,
      step, targetStep, currentIndex: 0, currentIndexRef: { current: 0 },
      dropTimerRef: { current: null }, prepareStepRef: { current() {} },
      hasPendingCardMotion: () => false, scheduleAdvance: () => { advances++; },
      requestClose() {}, performance,
    };
    vm.runInNewContext(compiled, context, { filename });
    cleanup = context.module.exports();
  }

  function dispatch(type, target = child, values = {}) {
    const value = event(type, target, values);
    const handler = listeners.get(type);
    assert.ok(handler, `missing ${type} handler`);
    handler(value);
    return value;
  }

  install();
  return {
    target, child, other, targetElementRef, pointerSessionRef, dispatch,
    dispatchWindow(type) {
      const handler = windowListeners.get(type);
      assert.ok(handler, `missing window ${type} handler`);
      handler(event(type, null));
    },
    setReady(value) { inputReady = value; install(); },
    setTargetStep(value) { targetStep = value; install(); },
    setHit(value) { hit = value; },
    get advances() { return advances; },
  };
}

test("an authorized touch completes while its target animates", () => {
  const f = fixture();
  assert.equal(f.dispatch("pointerdown").defaultPrevented, false);
  f.setReady(false);
  assert.equal(f.dispatch("touchstart", f.child, { touches: [{}] }).defaultPrevented, false);
  assert.equal(f.dispatch("pointerup").defaultPrevented, false);
  assert.equal(f.dispatch("touchend").defaultPrevented, false);
  assert.equal(f.dispatch("mousedown").defaultPrevented, false);
  assert.equal(f.dispatch("click").defaultPrevented, false);
  assert.equal(f.advances, 1);
});

test("a mouse press completes while its target animates", () => {
  const f = fixture();
  f.dispatch("pointerdown");
  f.setReady(false);
  assert.equal(f.dispatch("mousedown").defaultPrevented, false);
  f.dispatch("pointerup");
  assert.equal(f.dispatch("click").defaultPrevented, false);
  assert.equal(f.advances, 1);
});

for (const [name, change] of [
  ["replacement", f => { f.targetElementRef.current = new FakeElement(); }],
  ["detachment", f => { f.target.isConnected = false; }],
  ["step change", f => { f.setTargetStep({ id: "next-step" }); }],
]) {
  test(`an in-flight gesture cannot activate an invalidated target after ${name}`, () => {
    const f = fixture();
    f.dispatch("pointerdown");
    f.setReady(false);
    change(f);
    assert.equal(f.dispatch("touchstart", f.child, { touches: [{}] }).defaultPrevented, true);
    f.dispatch("pointerup");
    assert.equal(f.dispatch("click").defaultPrevented, true);
    assert.equal(f.advances, 0);
  });
}

test("multi-touch cancels the existing gesture", () => {
  const f = fixture();
  f.dispatch("pointerdown");
  f.setReady(false);
  assert.equal(f.dispatch("touchstart", f.child, { touches: [{}, {}] }).defaultPrevented, true);
  f.dispatch("pointerup");
  assert.equal(f.dispatch("click").defaultPrevented, true);
  assert.equal(f.advances, 0);
});

test("a released pointer outside the target cannot advance", () => {
  const f = fixture();
  f.dispatch("pointerdown");
  f.setHit(f.other);
  f.dispatch("pointerup", f.child, { clientX: 50 });
  assert.equal(f.dispatch("click").defaultPrevented, true);
  assert.equal(f.advances, 0);
});

test("other controls remain locked while the target animates", () => {
  const f = fixture();
  f.dispatch("pointerdown");
  f.setReady(false);
  assert.equal(f.dispatch("touchstart", f.other, { touches: [{}] }).defaultPrevented, true);
  assert.equal(f.dispatch("mousedown", f.other).defaultPrevented, true);
  assert.equal(f.dispatch("click", f.other).defaultPrevented, true);
  assert.equal(f.advances, 0);
});

test("keyboard activation still requires an input-ready target", () => {
  const f = fixture();
  f.setReady(false);
  assert.equal(f.dispatch("click", f.child, { detail: 0 }).defaultPrevented, true);
  f.setReady(true);
  assert.equal(f.dispatch("click", f.child, { detail: 0 }).defaultPrevented, false);
  assert.equal(f.advances, 1);
});

for (const cancellation of ["pointercancel", "lostpointercapture", "blur", "visibilitychange"]) {
  test(`${cancellation} before release cancels activation even if readiness returns`, () => {
    const f = fixture();
    f.dispatch("pointerdown");
    f.setReady(false);
    if (cancellation === "blur") {
      f.dispatchWindow(cancellation);
    } else {
      f.dispatch(cancellation);
    }
    f.setReady(true);
    f.dispatch("pointerup");
    assert.equal(f.dispatch("click").defaultPrevented, true);
    assert.equal(f.advances, 0);
  });
}

test("implicit capture release after pointerup does not cancel the click", () => {
  const f = fixture();
  f.dispatch("pointerdown");
  f.setReady(false);
  f.dispatch("pointerup");
  f.dispatch("lostpointercapture");
  assert.equal(f.dispatch("click").defaultPrevented, false);
  assert.equal(f.advances, 1);
});

test("replacing a target blocks a stale click even after readiness returns", () => {
  const f = fixture();
  f.dispatch("pointerdown");
  f.setReady(false);
  f.targetElementRef.current = new FakeElement();
  f.setReady(true);
  f.dispatch("pointerup");
  assert.equal(f.dispatch("click").defaultPrevented, true);
  assert.equal(f.advances, 0);
});

test("a moved press cannot advance as a click", () => {
  const f = fixture();
  f.dispatch("pointerdown");
  f.dispatch("pointermove", f.child, { clientX: 35 });
  f.dispatch("pointerup", f.child, { clientX: 35 });
  assert.equal(f.dispatch("click").defaultPrevented, true);
  assert.equal(f.advances, 0);
});

test("a drag advances only after actual movement", () => {
  const f = fixture("drag", "terminal-drag");
  f.dispatch("pointerdown");
  f.dispatch("pointerup");
  assert.equal(f.advances, 0);
  f.dispatch("pointerdown");
  f.dispatch("pointermove", f.child, { clientX: 35 });
  f.dispatch("pointerup", f.child, { clientX: 35 });
  assert.equal(f.advances, 1);
});

test("a completed gesture remains valid if readiness changes back", () => {
  const f = fixture();
  f.dispatch("pointerdown");
  f.setReady(false);
  f.dispatch("pointerup");
  f.setReady(true);
  assert.equal(f.dispatch("click").defaultPrevented, false);
  assert.equal(f.advances, 1);
});
