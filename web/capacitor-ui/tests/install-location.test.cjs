const assert = require("node:assert/strict");
const path = require("node:path");
const { test } = require("node:test");
const { loadSource } = require("./load-source.cjs");

const location = loadSource(path.join(__dirname, "../src/lib/install-location.ts"));
const plain = value => JSON.parse(JSON.stringify(value));

test("installation selection preserves the full path, never the display name", () => {
  for (const selectedPath of [
    "D:\\Taverns\\Same name",
    "/data/user/0/com.sillyclient/files/tarven/installations",
    "/private/var/mobile/Containers/Data/Application/test/Documents/Custom",
  ]) {
    assert.deepEqual(plain(location.installationSelection({ name: "Same name", path: selectedPath })),
      { path: selectedPath, mode: "exact" });
  }
});

test("path quotes normalize without damaging spaces or platform separators", () => {
  assert.equal(location.cleanInstallPath('  "D:\\Taverns\\My Tavern"  '), "D:\\Taverns\\My Tavern");
  assert.equal(location.cleanInstallPath(" '/private/My Tavern' "), "/private/My Tavern");
  assert.equal(location.cleanInstallPath(""), undefined);
});

test("missing picker path cannot silently fall back to a folder name", () => {
  for (const path of ["", "  ", undefined]) {
    assert.throws(() => location.installationSelection({ name: "Tavern", path }));
  }
});

test("installation modes are explicit and invalid modes are rejected", () => {
  assert.equal(location.installationSelection({ path: "/root/exact", installPathMode: "exact" }).mode, "exact");
  assert.throws(() => location.installationSelection({ path: "/root", installPathMode: "guess" }));
});

test("only an explicit root selection adds the dedicated instance folder", () => {
  const selected = location.installationSelection({ path: "/external/Selected", installPathMode: "root" });
  assert.equal(selected.mode, "root");
  assert.equal(location.exactInstallTarget(selected.path, selected.mode, "New instance"), "/external/Selected/New instance");
  const exact = location.installationSelection({ path: "/external/Selected" });
  assert.equal(location.exactInstallTarget(exact.path, exact.mode, "New instance"), "/external/Selected");
});

test("copy migration turns a selected root into a new exact target without changing identity", () => {
  assert.equal(location.exactInstallTarget("D:\\Custom\\", "root", "local-100"), "D:\\Custom\\local-100");
  assert.equal(location.exactInstallTarget("/private/Custom/", "root", "local-100"), "/private/Custom/local-100");
  assert.equal(location.exactInstallTarget("/", "root", "local-100"), "/local-100");
  assert.equal(location.exactInstallTarget("D:\\Custom\\chosen-name", "exact", "local-100"), "D:\\Custom\\chosen-name");
  assert.equal(location.exactInstallTarget("", "root", "local-100"), undefined);
});

test("document-provider URIs cannot be disguised as executable install paths", () => {
  assert.throws(() => location.installationSelection({ path: "content://provider/tree/test" }));
  assert.throws(() => location.exactInstallTarget("content://provider/tree/test", "exact", "local-100"));
});
