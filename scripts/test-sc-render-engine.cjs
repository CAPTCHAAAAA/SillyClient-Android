const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { after, afterEach, before, beforeEach, test } = require("node:test");
const { chromium } = require(process.env.SILLYCLIENT_PLAYWRIGHT_MODULE || "playwright");

const sourcePath = process.env.SILLYCLIENT_RENDER_SOURCE_OVERRIDE ||
  path.join(__dirname, "../app/src/main/assets/scripts/sc-render-engine.js");
const source = fs.readFileSync(sourcePath, "utf8");
let browser;
let context;
let page;
let pageErrors;

async function injectRenderer() {
  // Append synchronously without installing Playwright's own DOM/listener instrumentation.
  await page.evaluate(script => {
    const element = document.createElement("script");
    element.textContent = script;
    document.head.append(element);
  }, source);
}

before(async () => {
  browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_EXECUTABLE });
});
after(async () => {
  if (browser) await browser.close();
});
async function setupPage({ initiallyHidden = false, idleCallback = true } = {}) {
  context = await browser.newContext({ viewport: { width: 412, height: 915 } });
  page = await context.newPage();
  pageErrors = [];
  page.on("pageerror", error => pageErrors.push(error.message));
  await page.route("http://render-engine.test/**", route => route.fulfill({
    contentType: "text/html",
    body: '<!doctype html><html><head></head><body><div id="top-bar"></div>' +
      '<main id="chat"><div class="mes"><div class="mes_text"><p>old text</p></div></div></main>' +
      '<div class="spinner"></div><textarea id="send_textarea"></textarea></body></html>',
  }));
  await page.goto("http://render-engine.test/", { waitUntil: "load" });
  await page.evaluate(({ initiallyHidden, idleCallback }) => {
    let nextId = 1;
    let hidden = initiallyHidden;
    const frames = new Map();
    const timers = new Map();
    const intervals = new Map();
    const idle = new Map();
    const observers = [];
    const listeners = [];
    const nativeAdd = EventTarget.prototype.addEventListener;
    const nativeRemove = EventTarget.prototype.removeEventListener;
    const nativeMutationObserver = window.MutationObserver;
    const capture = options => typeof options === "boolean" ? options : !!options?.capture;
    EventTarget.prototype.addEventListener = function(type, callback, options) {
      listeners.push({ target: this, type, callback, capture: capture(options) });
      return nativeAdd.call(this, type, callback, options);
    };
    EventTarget.prototype.removeEventListener = function(type, callback, options) {
      const index = listeners.findIndex(item => item.target === this && item.type === type &&
        item.callback === callback && item.capture === capture(options));
      if (index >= 0) listeners.splice(index, 1);
      return nativeRemove.call(this, type, callback, options);
    };
    window.requestAnimationFrame = callback => {
      const id = nextId++;
      frames.set(id, callback);
      return id;
    };
    window.cancelAnimationFrame = id => frames.delete(id);
    window.setTimeout = (callback, delay) => {
      const id = nextId++;
      timers.set(id, { callback, delay });
      return id;
    };
    window.clearTimeout = id => timers.delete(id);
    window.setInterval = (callback, delay) => {
      const id = nextId++;
      intervals.set(id, { callback, delay });
      return id;
    };
    window.clearInterval = id => intervals.delete(id);
    window.requestIdleCallback = callback => {
      const id = nextId++;
      idle.set(id, callback);
      return id;
    };
    window.cancelIdleCallback = id => idle.delete(id);
    if (!idleCallback) window.requestIdleCallback = undefined;
    window.MutationObserver = class extends nativeMutationObserver {
      constructor(callback) {
        super(callback);
        this.kind = "mutation";
        this.targets = new Set();
        observers.push(this);
      }
      observe(target, options) {
        this.targets.add(target);
        return super.observe(target, options);
      }
      disconnect() {
        this.targets.clear();
        return super.disconnect();
      }
    };
    window.IntersectionObserver = class {
      constructor(callback) {
        this.kind = "intersection";
        this.callback = callback;
        this.targets = new Set();
        observers.push(this);
      }
      observe(target) { this.targets.add(target); }
      unobserve(target) { this.targets.delete(target); }
      disconnect() { this.targets.clear(); }
    };
    window.PerformanceObserver = class {
      constructor(callback) {
        this.kind = "performance";
        this.callback = callback;
        this.targets = new Set();
        observers.push(this);
      }
      observe() { this.targets.add(document); }
      disconnect() { this.targets.clear(); }
    };
    Object.defineProperty(document, "hidden", { configurable: true, get: () => hidden });
    const original = Object.getOwnPropertyDescriptor(Element.prototype, "innerHTML");
    window.renderTest = {
      original,
      originalFocus: HTMLTextAreaElement.prototype.focus,
      frames,
      timers,
      intervals,
      idle,
      observers,
      listeners,
      frame(now = performance.now()) {
        const callbacks = Array.from(frames.values());
        frames.clear();
        callbacks.forEach(callback => callback(now));
      },
      timeout(delay) {
        for (const [id, item] of Array.from(timers.entries())) {
          if (item.delay !== delay) continue;
          timers.delete(id);
          item.callback();
        }
      },
      runIdle() {
        const callbacks = Array.from(idle.values());
        idle.clear();
        callbacks.forEach(callback => callback({ timeRemaining: () => 5, didTimeout: false }));
      },
      tick(delay) {
        for (const item of Array.from(intervals.values())) if (item.delay === delay) item.callback();
      },
      hide(value) {
        hidden = value;
        document.dispatchEvent(new Event("visibilitychange"));
      },
      activeObservers(kind) {
        return observers.filter(item => (!kind || item.kind === kind) && item.targets.size > 0).length;
      },
    };
  }, { initiallyHidden, idleCallback });
  await injectRenderer();
}
beforeEach(async () => setupPage());
afterEach(async () => {
  if (context) await context.close();
  assert.deepEqual(pageErrors, []);
});

test("innerHTML retains the exact native synchronous descriptor", async () => {
  const result = await page.evaluate(() => {
    const descriptor = Object.getOwnPropertyDescriptor(Element.prototype, "innerHTML");
    const element = document.querySelector(".mes_text");
    element.innerHTML = "<strong>new content</strong>";
    return {
      sameSetter: descriptor.set === renderTest.original.set,
      sameGetter: descriptor.get === renderTest.original.get,
      sameFlags: descriptor.configurable === renderTest.original.configurable &&
        descriptor.enumerable === renderTest.original.enumerable,
      text: element.textContent,
      pendingFrames: renderTest.frames.size,
    };
  });
  assert.deepEqual(result, {
    sameSetter: true, sameGetter: true, sameFlags: true, text: "new content", pendingFrames: 0,
  });
});

test("same-stack code-block and media postprocessing survives later frames", async () => {
  const result = await page.evaluate(() => {
    const message = document.querySelector(".mes_text");
    message.innerHTML = "<pre><code>new answer</code></pre>";
    const code = message.querySelector("pre code");
    if (code) {
      const copy = document.createElement("button");
      copy.className = "code-copy";
      code.parentElement.append(copy);
    }
    const media = document.createElement("audio");
    media.className = "message-media";
    message.append(media);
    const immediate = [message.querySelectorAll(".code-copy").length, !!message.querySelector("audio")];
    renderTest.frame();
    return { immediate, after: [message.querySelectorAll(".code-copy").length, !!message.querySelector("audio")] };
  });
  assert.deepEqual(result, { immediate: [1, true], after: [1, true] });
});

test("stream finalization can inspect DOM after awaiting the update microtask", async () => {
  const result = await page.evaluate(async () => {
    const message = document.querySelector(".mes_text");
    async function onProgressStreaming() { message.innerHTML = "<pre><code>final answer</code></pre>"; }
    await onProgressStreaming();
    const code = message.querySelector("code");
    return { text: code?.textContent ?? null, pendingFrames: renderTest.frames.size };
  });
  assert.deepEqual(result, { text: "final answer", pendingFrames: 0 });
});

test("detached message edits are not replayed or overwritten by queued HTML", async () => {
  const text = await page.evaluate(() => {
    const message = document.querySelector(".mes_text");
    message.innerHTML = "<p>stream update</p>";
    message.remove();
    message.textContent = "edit after removal";
    renderTest.frame();
    return message.textContent;
  });
  assert.equal(text, "edit after removal");
});

test("render updates never read or mutate the upstream chat scroll position", async () => {
  const result = await page.evaluate(() => {
    const chat = document.getElementById("chat");
    let reads = 0;
    let writes = 0;
    Object.defineProperty(chat, "scrollHeight", { configurable: true, get() { reads++; return 200; } });
    Object.defineProperty(chat, "clientHeight", { configurable: true, get() { reads++; return 100; } });
    Object.defineProperty(chat, "scrollTop", {
      configurable: true, get() { reads++; return 0; }, set() { writes++; },
    });
    document.querySelector(".mes_text").innerHTML = "<p>updated</p>";
    renderTest.frame();
    return { reads, writes };
  });
  assert.deepEqual(result, { reads: 0, writes: 0 });
});

test("nested scheduler reads and writes complete in read-before-write frame phases", async () => {
  const result = await page.evaluate(() => {
    const scheduler = SillyClientEngine.scheduler;
    const order = [];
    scheduler.read(() => {
      order.push("read");
      scheduler.read(() => order.push("read-from-read"));
      scheduler.write(() => order.push("write-from-read"));
    });
    scheduler.write(() => {
      order.push("write");
      scheduler.read(() => order.push("read-from-write"));
      scheduler.write(() => order.push("write-from-write"));
    });
    renderTest.frame(8.3);
    const first = order.slice();
    const nextFrame = renderTest.frames.size;
    renderTest.frame(16.6);
    return { first, order, nextFrame, remainingFrames: renderTest.frames.size };
  });
  assert.deepEqual(result, {
    first: ["read", "write", "write-from-read"],
    order: ["read", "write", "write-from-read", "read-from-read", "read-from-write", "write-from-write"],
    nextFrame: 1, remainingFrames: 0,
  });
});

test("scheduler callback errors cannot strand later callbacks or create an idle loop", async () => {
  const result = await page.evaluate(() => {
    const scheduler = SillyClientEngine.scheduler;
    const order = [];
    scheduler.read(() => { throw new Error("synthetic callback failure"); });
    scheduler.write(() => {
      order.push("write");
      scheduler.read(() => order.push("next read"));
    });
    renderTest.frame();
    renderTest.frame();
    return { order, frames: renderTest.frames.size, reads: scheduler.readQueue.length, writes: scheduler.writeQueue.length };
  });
  assert.deepEqual(result, { order: ["write", "next read"], frames: 0, reads: 0, writes: 0 });
});

test("explicit and reentrant flush preserve frame ordering without redundant callbacks", async () => {
  const result = await page.evaluate(() => {
    const scheduler = SillyClientEngine.scheduler;
    const order = [];
    scheduler.read(() => {
      order.push("first read");
      scheduler.flush();
    });
    scheduler.read(() => order.push("second read"));
    scheduler.write(() => {
      order.push("write");
      scheduler.write(() => order.push("next write"));
    });
    scheduler.flush();
    const first = order.slice();
    const nextFrame = renderTest.frames.size;
    renderTest.frame();
    return { first, order, nextFrame, frames: renderTest.frames.size };
  });
  assert.deepEqual(result, {
    first: ["first read", "second read", "write"],
    order: ["first read", "second read", "write", "next write"],
    nextFrame: 1, frames: 0,
  });
});

test("scheduler work pauses while hidden and resumes once without starting a closed HUD", async () => {
  const result = await page.evaluate(() => {
    const order = [];
    SillyClientEngine.scheduler.read(() => order.push("read"));
    renderTest.hide(true);
    SillyClientEngine.scheduler.write(() => order.push("write"));
    const hiddenFrames = renderTest.frames.size;
    renderTest.frame();
    const hiddenOrder = order.slice();
    renderTest.hide(false);
    const resumedFrames = renderTest.frames.size;
    renderTest.frame();
    return {
      hiddenFrames, hiddenOrder, resumedFrames, order,
      frames: renderTest.frames.size, intervals: renderTest.intervals.size,
    };
  });
  assert.deepEqual(result, {
    hiddenFrames: 0, hiddenOrder: [], resumedFrames: 1, order: ["read", "write"],
    frames: 0, intervals: 0,
  });
});

test("HUD diagnostics allocate no intervals or long-task observers until enabled", async () => {
  const result = await page.evaluate(() => ({
    intervals: renderTest.intervals.size,
    performanceObservers: renderTest.activeObservers("performance"),
    frames: renderTest.frames.size,
  }));
  assert.deepEqual(result, { intervals: 0, performanceObservers: 0, frames: 0 });
});

test("HUD close cancels every diagnostic interval observer and frame", async () => {
  const result = await page.evaluate(() => {
    const initialObservers = renderTest.activeObservers();
    SillyClientEngine.toggleHud();
    const enabled = {
      intervals: renderTest.intervals.size, performanceObservers: renderTest.activeObservers("performance"),
    };
    SillyClientEngine.toggleHud();
    renderTest.timeout(160);
    return {
      enabled, intervals: renderTest.intervals.size, frames: renderTest.frames.size,
      observers: renderTest.activeObservers(), initialObservers,
    };
  });
  assert.deepEqual(result.enabled, { intervals: 3, performanceObservers: 1 });
  assert.equal(result.intervals, 0);
  assert.equal(result.frames, 0);
  assert.equal(result.observers, result.initialObservers);
});

test("hidden pages disconnect monitoring and resume only the enabled HUD", async () => {
  const result = await page.evaluate(() => {
    SillyClientEngine.toggleHud();
    renderTest.timeout(2000);
    renderTest.runIdle();
    renderTest.hide(true);
    const hidden = {
      intervals: renderTest.intervals.size, frames: renderTest.frames.size,
      observers: renderTest.activeObservers(), idle: renderTest.idle.size,
    };
    renderTest.hide(false);
    const resumed = {
      intervals: renderTest.intervals.size, performanceObservers: renderTest.activeObservers("performance"),
    };
    SillyClientEngine.toggleHud();
    renderTest.hide(true);
    renderTest.hide(false);
    return { hidden, resumed, disabled: renderTest.intervals.size };
  });
  assert.deepEqual(result.hidden, { intervals: 0, frames: 0, observers: 0, idle: 0 });
  assert.deepEqual(result.resumed, { intervals: 3, performanceObservers: 1 });
  assert.equal(result.disabled, 0);
});

test("a renderer installed while hidden starts no timers frames or observers", async () => {
  await context.close();
  await setupPage({ initiallyHidden: true });
  const result = await page.evaluate(() => {
    const hidden = {
      intervals: renderTest.intervals.size, frames: renderTest.frames.size,
      observers: renderTest.activeObservers(), timers: renderTest.timers.size,
    };
    renderTest.hide(false);
    return {
      hidden,
      resumed: {
        intervals: renderTest.intervals.size, frames: renderTest.frames.size,
        observers: renderTest.activeObservers(), timers: renderTest.timers.size,
      },
    };
  });
  assert.deepEqual(result, {
    hidden: { intervals: 0, frames: 0, observers: 0, timers: 0 },
    resumed: { intervals: 0, frames: 0, observers: 1, timers: 2 },
  });
});

test("rapid HUD toggles cancel stale reveal and hide jobs without duplicating diagnostics", async () => {
  const result = await page.evaluate(() => {
    SillyClientEngine.toggleHud();
    SillyClientEngine.toggleHud();
    const closedFrames = renderTest.frames.size;
    SillyClientEngine.toggleHud();
    renderTest.timeout(160);
    renderTest.frame();
    const hud = document.getElementById("sc-perf-hud");
    const open = {
      display: hud.style.display, opacity: hud.style.opacity,
      intervals: renderTest.intervals.size, performance: renderTest.activeObservers("performance"),
      frames: renderTest.frames.size, hideTimers: Array.from(renderTest.timers.values()).filter(t => t.delay === 160).length,
    };
    SillyClientEngine.toggleHud();
    renderTest.timeout(160);
    return { closedFrames, open, closed: hud.style.display, intervals: renderTest.intervals.size, frames: renderTest.frames.size };
  });
  assert.deepEqual(result, {
    closedFrames: 0,
    open: { display: "block", opacity: "1", intervals: 3, performance: 1, frames: 1, hideTimers: 0 },
    closed: "none", intervals: 0, frames: 0,
  });
});

test("BFCache suspension releases active resources and restoration resumes the saved HUD", async () => {
  const result = await page.evaluate(() => {
    const order = [];
    SillyClientEngine.toggleHud();
    SillyClientEngine.scheduler.read(() => order.push("read after restore"));
    window.dispatchEvent(new PageTransitionEvent("pagehide", { persisted: true }));
    const suspended = {
      intervals: renderTest.intervals.size, frames: renderTest.frames.size,
      timers: renderTest.timers.size, observers: renderTest.activeObservers(),
      visible: SillyClientEngine.state.hudVisible, listeners: renderTest.listeners.length > 0,
    };
    renderTest.hide(true);
    renderTest.hide(false);
    const stillSuspended = renderTest.intervals.size;
    window.dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true }));
    window.dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true }));
    const restored = {
      intervals: renderTest.intervals.size, performance: renderTest.activeObservers("performance"),
      frames: renderTest.frames.size,
    };
    renderTest.frame();
    SillyClientEngine.dispose();
    return { suspended, stillSuspended, restored, order, intervals: renderTest.intervals.size };
  });
  assert.deepEqual(result, {
    suspended: { intervals: 0, frames: 0, timers: 0, observers: 0, visible: true, listeners: true },
    stillSuspended: 0,
    restored: { intervals: 3, performance: 1, frames: 2 },
    order: ["read after restore"], intervals: 0,
  });
});

test("noncached page exit disposes resources and restores only the engine-owned focus patch", async () => {
  const result = await page.evaluate(() => {
    SillyClientEngine.toggleHud();
    window.dispatchEvent(new PageTransitionEvent("pagehide", { persisted: false }));
    return {
      intervals: renderTest.intervals.size, frames: renderTest.frames.size,
      timers: renderTest.timers.size, observers: renderTest.activeObservers(), listeners: renderTest.listeners.length,
      focus: HTMLTextAreaElement.prototype.focus === renderTest.originalFocus,
      hud: !!document.getElementById("sc-perf-hud"), toggle: SillyClientEngine.toggleHud(),
    };
  });
  assert.deepEqual(result, {
    intervals: 0, frames: 0, timers: 0, observers: 0, listeners: 0, focus: true, hud: false, toggle: false,
  });
});

test("animation observation drops detached nodes and ignores late visibility notifications", async () => {
  const result = await page.evaluate(() => {
    renderTest.timeout(2000);
    renderTest.runIdle();
    const observer = renderTest.observers.find(item => item.kind === "intersection");
    const spinner = document.querySelector(".spinner");
    observer.callback([{ target: spinner, isIntersecting: false }]);
    const frozen = SillyClientEngine.state.frozenAnimCount;
    spinner.remove();
    renderTest.timeout(4000);
    renderTest.runIdle();
    observer.callback([{ target: spinner, isIntersecting: false }]);
    return {
      frozen, count: SillyClientEngine.state.frozenAnimCount,
      observed: observer.targets.size, marked: spinner.classList.contains("__sc-anim-frozen"),
    };
  });
  assert.deepEqual(result, { frozen: 1, count: 0, observed: 0, marked: false });
});

test("fallback animation scan timers are canceled while hidden and on disposal", async () => {
  await context.close();
  await setupPage({ idleCallback: false });
  const result = await page.evaluate(() => {
    renderTest.timeout(2000);
    const scheduled = Array.from(renderTest.timers.values()).filter(t => t.delay === 4000).length;
    renderTest.hide(true);
    const hidden = renderTest.timers.size;
    renderTest.hide(false);
    renderTest.timeout(2000);
    renderTest.timeout(4000);
    const observed = renderTest.activeObservers("intersection");
    SillyClientEngine.dispose();
    return { scheduled, hidden, observed, timers: renderTest.timers.size, observers: renderTest.activeObservers() };
  });
  assert.deepEqual(result, { scheduled: 1, hidden: 0, observed: 1, timers: 0, observers: 0 });
});

test("chameleon reports changed theme color once when a hidden page resumes", async () => {
  const result = await page.evaluate(() => {
    const colors = [];
    window.SillyClientRenderBridge = { onColorChanged: color => colors.push(color) };
    const topBar = document.getElementById("top-bar");
    topBar.style.backgroundColor = "rgb(10, 20, 30)";
    SillyClientEngine.chameleon.sampleAndReport(false);
    renderTest.hide(true);
    topBar.style.backgroundColor = "rgb(40, 50, 60)";
    SillyClientEngine.chameleon.onTargetMutated();
    const hiddenTimers = renderTest.timers.size;
    renderTest.hide(false);
    renderTest.timeout(500);
    return { colors, hiddenTimers, current: SillyClientEngine.state.lastTopColor };
  });
  assert.deepEqual(result, { colors: [-16116706, -14142916], hiddenTimers: 0, current: -14142916 });
});

test("stream diagnostics coalesce at the native frame rate without touching HTML", async () => {
  await page.evaluate(() => SillyClientEngine.toggleHud());
  await page.evaluate(() => renderTest.frame(8.3));
  for (let index = 0; index < 3; index++) {
    await page.evaluate(index => {
      const message = document.querySelector(".mes_text");
      message.innerHTML = `<p>first ${index}</p>`;
      message.innerHTML = `<p>second ${index}</p>`;
    }, index);
    await page.evaluate(index => renderTest.frame(16.6 + index * 8.3), index);
  }
  const result = await page.evaluate(() => {
    renderTest.tick(1000);
    const count = SillyClientEngine.state.streamingFps;
    SillyClientEngine.toggleHud();
    document.querySelector(".mes_text").innerHTML = "<p>not monitored</p>";
    return { count, text: document.querySelector(".mes_text").textContent, frames: renderTest.frames.size };
  });
  assert.deepEqual(result, { count: 3, text: "not monitored", frames: 0 });
});

test("120Hz diagnostics follow every native frame with no stream-write throttle", async () => {
  const result = await page.evaluate(async () => {
    SillyClientEngine.toggleHud();
    const message = document.querySelector(".mes_text");
    const start = performance.now();
    renderTest.frame(start);
    for (let index = 0; index < 120; index++) {
      message.innerHTML = `<p>frame ${index}</p>`;
      if (message.textContent !== `frame ${index}`) throw new Error("message write was deferred");
      await Promise.resolve();
      renderTest.frame(start + (index + 1) * (1000 / 120));
    }
    renderTest.tick(1000);
    const count = SillyClientEngine.state.streamingFps;
    const fps = SillyClientEngine.state.fps;
    SillyClientEngine.toggleHud();
    return { count, fps, text: message.textContent, frames: renderTest.frames.size };
  });
  assert.deepEqual(result, { count: 120, fps: 120, text: "frame 119", frames: 0 });
});

test("duplicate script injection creates no additional listeners observers styles or frames", async () => {
  const before = await page.evaluate(() => ({
    listeners: renderTest.listeners.length, observers: renderTest.observers.length,
    timers: renderTest.timers.size, styles: document.querySelectorAll("#sc-p1-containment").length,
  }));
  await injectRenderer();
  assert.deepEqual(await page.evaluate(() => ({
    listeners: renderTest.listeners.length, observers: renderTest.observers.length,
    timers: renderTest.timers.size, styles: document.querySelectorAll("#sc-p1-containment").length,
  })), before);
});

test("disposal is idempotent and releases owned diagnostics feature observers queues and listeners", async () => {
  const result = await page.evaluate(() => {
    SillyClientEngine.toggleHud();
    SillyClientEngine.scheduler.read(() => { throw new Error("disposed work executed"); });
    SillyClientEngine.chameleon.onTargetMutated();
    renderTest.timeout(2000);
    renderTest.runIdle();
    SillyClientEngine.dispose();
    SillyClientEngine.dispose();
    SillyClientEngine.scheduler.write(() => { throw new Error("new disposed work executed"); });
    renderTest.hide(false);
    return {
      intervals: renderTest.intervals.size, frames: renderTest.frames.size,
      timers: renderTest.timers.size, idle: renderTest.idle.size,
      observers: renderTest.activeObservers(), listeners: renderTest.listeners.length,
      reads: SillyClientEngine.scheduler.readQueue.length, writes: SillyClientEngine.scheduler.writeQueue.length,
      hud: !!document.getElementById("sc-perf-hud"), toggle: SillyClientEngine.toggleHud(),
    };
  });
  assert.deepEqual(result, {
    intervals: 0, frames: 0, timers: 0, idle: 0, observers: 0, listeners: 0,
    reads: 0, writes: 0, hud: false, toggle: false,
  });
});

test("full-fidelity visual CSS and short existing drawer durations remain unchanged", () => {
  assert.match(source, /#chat \.mes:last-child\s*\{\s*transform:\s*translateZ\(0\)/);
  assert.match(source, /backdrop-filter:\s*blur\(24px\) saturate\(1\.4\)/);
  assert.match(source, /box-shadow:\s*0 16px 40px/);
  assert.match(source, /duration:\s*90\b/);
  assert.match(source, /duration:\s*70\b/);
  assert.doesNotMatch(source, /(?:maxFps|targetFps|streamingFpsLimit)\s*[:=]\s*30\b|duration:\s*260\b|__sc-scrolling/);
});
