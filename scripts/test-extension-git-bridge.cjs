const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");

const source = fs.readFileSync(path.join(__dirname,
  "../app/src/main/java/com/sillyclient/runtime/RuntimeConfiguration.kt"), "utf8");
const marker = "const initBridge = ";
const start = source.indexOf(marker);
const end = source.indexOf("const bridgeModuleSource = [", start);
assert.ok(start >= 0 && end > start, "Embedded extension Git bridge was not found");
const factorySource = source.slice(start + marker.length, end).trim().replace(/;$/, "");
const posix = path.posix;
const ORIGINAL = "a".repeat(40);
const UPDATED = "b".repeat(40);
const OTHER = "c".repeat(40);
const REMOTE = "https://github.com/example/extension.git";
const SECOND_REMOTE = "https://github.com/example/other.git";
const BASE = "/synthetic/instance/extension";

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

function refs(oid = UPDATED) {
  return [{ ref: "refs/heads/main", oid }];
}

function fixture() {
  let now = 100_000;
  const files = new Map();
  const directories = new Set(["/"]);
  const aliases = new Map();
  const discoveries = [];
  const fetches = [];
  const checkouts = [];
  let discover = async () => refs();
  let fetch = async () => ({ fetchHead: UPDATED });
  let checkout = async () => {};
  function addDirectory(name) {
    for (let current = name; !directories.has(current); current = posix.dirname(current)) {
      directories.add(current);
    }
  }
  const memoryFs = {
    existsSync(name) { return files.has(name) || directories.has(name); },
    readFileSync(name) {
      assert.ok(files.has(name), `Unexpected synthetic read: ${name}`);
      return files.get(name);
    },
    writeFileSync(name, value) {
      assert.ok(directories.has(posix.dirname(name)), `Missing synthetic parent: ${name}`);
      files.set(name, value);
    },
    mkdirSync(name) { addDirectory(name); },
    realpathSync(name) {
      if (aliases.has(name)) return aliases.get(name);
      assert.ok(directories.has(name), `Missing synthetic directory: ${name}`);
      return name;
    },
    statSync(name) { return { isFile: () => files.has(name) }; },
  };
  function setRemote(base, url) {
    files.set(posix.join(base, ".git/config"), `[remote "origin"]\n url = ${url}\n`);
  }
  function addRepo(base = BASE, url = REMOTE) {
    for (const directory of [base, ".git", ".git/refs/heads", ".git/refs/remotes/origin"]) {
      addDirectory(directory === base ? base : posix.join(base, directory));
    }
    files.set(posix.join(base, ".git/HEAD"), "ref: refs/heads/main\n");
    files.set(posix.join(base, ".git/refs/heads/main"), ORIGINAL + "\n");
    files.set(posix.join(base, ".git/refs/remotes/origin/main"), ORIGINAL + "\n");
    setRemote(base, url);
  }
  const factory = vm.runInNewContext("(" + factorySource + ")", {
    Buffer, URL, Date: { now: () => now }, setTimeout, clearTimeout,
    process: { env: {}, cwd: () => "/synthetic" },
  }, { filename: "RuntimeConfiguration.kt:initBridge" });
  const bridge = factory(memoryFs, posix, {
    listServerRefs(options) {
      discoveries.push(options);
      return discover(options);
    },
    fetch(options) {
      fetches.push(options);
      return fetch(options);
    },
    checkout(options) {
      checkouts.push(options);
      return checkout(options);
    },
  }, {
    request() { throw new Error("Network requests are forbidden in this test"); },
  });
  addRepo();
  return {
    files, aliases, discoveries, fetches, checkouts, addRepo, setRemote,
    git: (base = BASE) => bridge.simpleGit(base),
    advance(milliseconds) { now += milliseconds; },
    onDiscover(callback) { discover = callback; },
    onFetch(callback) { fetch = callback; },
    onCheckout(callback) { checkout = callback; },
    remoteRef(base = BASE) { return files.get(posix.join(base, ".git/refs/remotes/origin/main")); },
  };
}

test("eight concurrent checks share one discovery across Git client objects", async () => {
  const f = fixture();
  const response = deferred();
  f.onDiscover(() => response.promise);
  const pending = Array.from({ length: 8 }, () => f.git().fetch("origin"));
  assert.equal(f.discoveries.length, 1);
  response.resolve(refs());
  await Promise.all(pending);
  assert.equal(f.remoteRef(), UPDATED + "\n");
});

test("successful discovery is cached for three seconds after completion", async () => {
  const f = fixture();
  const response = deferred();
  f.onDiscover(() => response.promise);
  const first = f.git().fetch("origin");
  f.advance(10_000);
  response.resolve(refs());
  await first;
  f.advance(2999);
  await f.git().fetch("origin");
  assert.equal(f.discoveries.length, 1);
  f.advance(1);
  await f.git().fetch("origin");
  assert.equal(f.discoveries.length, 2);
});

test("clock rollback does not prolong a cached result", async () => {
  const f = fixture();
  await f.git().fetch("origin");
  f.advance(-1000);
  await f.git().fetch("origin");
  assert.equal(f.discoveries.length, 2);
});

test("failed concurrent discovery is shared and a later call can retry", async () => {
  const f = fixture();
  const response = deferred();
  f.onDiscover(() => response.promise);
  const pending = Array.from({ length: 8 }, () => f.git().fetch("origin"));
  const settled = Promise.allSettled(pending);
  assert.equal(f.discoveries.length, 1);
  response.reject(new Error("Synthetic discovery failure"));
  assert.ok((await settled).every(result => result.status === "rejected"));
  assert.equal(f.remoteRef(), ORIGINAL + "\n");
  f.onDiscover(async () => refs());
  await f.git().fetch("origin");
  assert.equal(f.discoveries.length, 2);
  assert.equal(f.remoteRef(), UPDATED + "\n");
});

test("a synchronous discovery failure is not kept in the cache", async () => {
  const f = fixture();
  f.onDiscover(() => { throw new Error("Synthetic synchronous failure"); });
  await assert.rejects(f.git().fetch("origin"), /synchronous failure/);
  f.onDiscover(async () => refs());
  await f.git().fetch("origin");
  assert.equal(f.discoveries.length, 2);
});

test("different instance directories do not share requests or results", async () => {
  const f = fixture();
  const second = "/synthetic/another-instance/extension";
  f.addRepo(second);
  await Promise.all([f.git().fetch("origin"), f.git(second).fetch("origin")]);
  assert.equal(f.discoveries.length, 2);
  assert.equal(f.remoteRef(), UPDATED + "\n");
  assert.equal(f.remoteRef(second), UPDATED + "\n");
});

test("equivalent and realpath-aliased directories share the same in-flight request", async () => {
  const f = fixture();
  const response = deferred();
  f.onDiscover(() => response.promise);
  f.aliases.set("/synthetic/alias", BASE);
  const pending = [
    f.git(BASE).fetch("origin"),
    f.git(BASE + "/../extension/.").fetch("origin"),
    f.git("/synthetic/alias").fetch("origin"),
  ];
  assert.equal(f.discoveries.length, 1);
  response.resolve(refs());
  await Promise.all(pending);
});

test("changing origin invalidates a successful cached result", async () => {
  const f = fixture();
  await f.git().fetch("origin");
  f.setRemote(BASE, SECOND_REMOTE);
  f.onDiscover(async () => refs(OTHER));
  await f.git().fetch("origin");
  assert.equal(f.discoveries.length, 2);
  assert.equal(f.discoveries[1].url, SECOND_REMOTE);
  assert.equal(f.remoteRef(), OTHER + "\n");
  f.setRemote(BASE, REMOTE);
  await f.git().fetch("origin");
  assert.equal(f.discoveries.length, 3);
});

test("late results from a replaced remote cannot overwrite refs or the new cache", async () => {
  const f = fixture();
  const oldResponse = deferred();
  f.onDiscover(() => oldResponse.promise);
  const oldFetch = f.git().fetch("origin");
  f.setRemote(BASE, SECOND_REMOTE);
  f.onDiscover(async () => refs(OTHER));
  await f.git().fetch("origin");
  oldResponse.resolve(refs());
  await oldFetch;
  assert.equal(f.remoteRef(), OTHER + "\n");
  await f.git().fetch("origin");
  assert.equal(f.discoveries.length, 2);
});

test("origin changes without a second caller still prevent stale ref writes", async () => {
  const f = fixture();
  const response = deferred();
  f.onDiscover(() => response.promise);
  const pending = f.git().fetch("origin");
  f.setRemote(BASE, SECOND_REMOTE);
  response.resolve(refs());
  await pending;
  assert.equal(f.remoteRef(), ORIGINAL + "\n");
  f.onDiscover(async () => refs(OTHER));
  await f.git().fetch("origin");
  assert.equal(f.discoveries.length, 2);
  assert.equal(f.remoteRef(), OTHER + "\n");
});

test("removing origin invalidates pending results and does not issue a request", async () => {
  const f = fixture();
  const response = deferred();
  f.onDiscover(() => response.promise);
  const pending = f.git().fetch("origin");
  f.files.delete(posix.join(BASE, ".git/config"));
  await f.git().fetch("origin");
  response.resolve(refs());
  await pending;
  assert.equal(f.remoteRef(), ORIGINAL + "\n");
  assert.equal(f.discoveries.length, 1);
});

test("an old failure cannot evict a new remote's in-flight request", async () => {
  const f = fixture();
  const oldResponse = deferred();
  const newResponse = deferred();
  f.onDiscover(() => oldResponse.promise);
  const oldFailure = assert.rejects(f.git().fetch("origin"), /old remote failure/);
  f.setRemote(BASE, SECOND_REMOTE);
  f.onDiscover(() => newResponse.promise);
  const newFetch = f.git().fetch("origin");
  oldResponse.reject(new Error("old remote failure"));
  await oldFailure;
  const joined = f.git().fetch("origin");
  assert.equal(f.discoveries.length, 2);
  newResponse.resolve(refs(OTHER));
  await Promise.all([newFetch, joined]);
  assert.equal(f.remoteRef(), OTHER + "\n");
});

for (const method of ["pull", "checkout"]) {
  for (const failureStage of [null, "fetch", "checkout"]) {
    test(`${method} invalidates completed discovery after ${failureStage || "success"}`, async () => {
      const f = fixture();
      await f.git().fetch("origin");
      if (failureStage === "fetch") {
        f.onFetch(async () => { throw new Error("Synthetic fetch failure"); });
      } else if (failureStage === "checkout") {
        f.onCheckout(async () => { throw new Error("Synthetic checkout failure"); });
      }
      const mutation = method === "pull" ? f.git().pull("origin", "main") : f.git().checkout("main");
      if (failureStage) await assert.rejects(mutation, /Synthetic/);
      else await mutation;
      await f.git().fetch("origin");
      assert.equal(f.discoveries.length, 2);
      assert.equal(f.fetches.length, 1);
      if (!failureStage) {
        assert.equal(f.checkouts.length, 1);
        assert.equal(f.checkouts[0].ref, "main");
        assert.equal(f.checkouts[0].force, true);
      }
    });
  }

  test(`${method} invalidates an older in-flight discovery`, async () => {
    const f = fixture();
    const response = deferred();
    f.onDiscover(() => response.promise);
    const pending = f.git().fetch("origin");
    await (method === "pull" ? f.git().pull("origin", "main") : f.git().checkout("main"));
    response.resolve(refs(ORIGINAL));
    await pending;
    if (method === "pull") assert.equal(f.remoteRef(), UPDATED + "\n");
    f.onDiscover(async () => refs(OTHER));
    await f.git().fetch("origin");
    assert.equal(f.discoveries.length, 2);
    assert.equal(f.remoteRef(), OTHER + "\n");
  });

  test(`${method} also invalidates discoveries started during the mutation`, async () => {
    const f = fixture();
    const mutationResponse = deferred();
    const discoveryResponse = deferred();
    f.onFetch(() => mutationResponse.promise);
    const mutation = method === "pull" ? f.git().pull("origin", "main") : f.git().checkout("main");
    f.onDiscover(() => discoveryResponse.promise);
    const pending = f.git().fetch("origin");
    mutationResponse.resolve({ fetchHead: UPDATED });
    await mutation;
    discoveryResponse.resolve(refs(ORIGINAL));
    await pending;
    if (method === "pull") assert.equal(f.remoteRef(), UPDATED + "\n");
    f.onDiscover(async () => refs(OTHER));
    await f.git().fetch("origin");
    assert.equal(f.discoveries.length, 2);
  });
}
