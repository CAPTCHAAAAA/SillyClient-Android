const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");

const source = fs.readFileSync(
  path.join(__dirname, "../src/routes/index.tsx"),
  "utf8",
);

test("Android native preview injects a non-persistent synthetic fault instance", () => {
  assert.match(source, /const DEV_FAULT_TEST_INSTANCE_ID = ["']__dev-fault-test-card__["']/);
  assert.match(source, /const shouldInjectDevFault = import\.meta\.env\.DEV[\s\S]*isNativePreview && isAndroid/);
  assert.match(source, /!isShowcase && !isDemoPreview && !shouldInjectDevFault\) saveInstances\(instances\)/);
  assert.match(source, /shouldInjectDevFault && instance\.id === DEV_FAULT_TEST_INSTANCE_ID/);
  assert.match(source, /ERR_SYNTHETIC_FAULT_INSTANCE/);
});

test("synthetic fault card is development-only and cannot enter persisted records", () => {
  assert.match(source, /const DEV_FAULT_TEST_INSTANCE: TavernInstance \| null = import\.meta\.env\.DEV/);
  assert.match(source, /const persistable = import\.meta\.env\.DEV[\s\S]*DEV_FAULT_TEST_INSTANCE_ID/);
});
