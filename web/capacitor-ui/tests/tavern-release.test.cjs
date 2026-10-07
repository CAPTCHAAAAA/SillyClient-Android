const assert = require("node:assert/strict");
const path = require("node:path");
const { test } = require("node:test");
const { loadSource } = require("./load-source.cjs");

const { selectTavernRelease } = loadSource(path.join(__dirname, "../src/lib/tavern-release.ts"));
const release = (tag, extra = {}) => ({ tag, prerelease: false, zipballUrl: `https://github.com/SillyTavern/SillyTavern/archive/refs/tags/${tag}.zip`, ...extra });

test("stable prefers a numbered official release over a branch or prerelease", () => {
  const stable = release("1.19.0");
  assert.equal(selectTavernRelease([release("release", { isBranch: true }), release("1.20.0-rc", { prerelease: true }), stable], "stable"), stable);
});

test("offline bundled metadata is a selectable real version", () => {
  const bundled = release("1.19.0", { isBundled: true });
  assert.equal(selectTavernRelease([bundled], "1.19.0"), bundled);
  assert.equal(selectTavernRelease([bundled], "stable"), bundled);
});

test("a removed selection never silently installs a different version", () => {
  assert.throws(() => selectTavernRelease([release("1.19.0")], "1.18.0"), /1\.18\.0/);
});

test("launcher retired versions do not filter unrelated Tavern releases", () => {
  const tavern = release("2.0.1");
  assert.equal(selectTavernRelease([tavern], "2.0.1"), tavern);
});

test("empty or prerelease-only catalogs do not fabricate a stable version", () => {
  assert.throws(() => selectTavernRelease([], "stable"));
  assert.throws(() => selectTavernRelease([release("1.20.0-rc", { prerelease: true })], "stable"));
});

test("explicit prerelease and branch selections are preserved", () => {
  const branch = release("release", { isBranch: true });
  const prerelease = release("1.20.0-rc", { prerelease: true });
  assert.equal(selectTavernRelease([branch, prerelease], "release"), branch);
  assert.equal(selectTavernRelease([branch, prerelease], "1.20.0-rc"), prerelease);
});
