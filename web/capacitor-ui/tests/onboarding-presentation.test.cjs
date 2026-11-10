const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const ts = require("typescript");

const guideFile = path.join(__dirname, "../src/components/onboarding/OnboardingGuide.tsx");
const targetFile = path.join(__dirname, "../src/components/onboarding/useOnboardingTarget.ts");
const cssFile = path.join(__dirname, "../src/components/onboarding/OnboardingGuide.css");

const guideSource = fs.readFileSync(guideFile, "utf8");
const targetSource = fs.readFileSync(targetFile, "utf8");
const cssSource = fs.readFileSync(cssFile, "utf8");

test("OnboardingGuide preserves presentation across step transitions to prevent strobe/blackout", () => {
  // Verify setPresentation(null) is NOT called on targetIndex changes
  const targetIndexEffectMatch = guideSource.match(/useLayoutEffect\(\(\) => \{[\s\S]*?\}, \[targetIndex\]\);/);
  assert.ok(targetIndexEffectMatch, "targetIndex effect must exist");
  assert.ok(!targetIndexEffectMatch[0].includes("setPresentation(null)"),
    "targetIndex effect must NOT reset presentation to null, as it causes severe screen flashing");
});

test("OnboardingGuide retains presentation when measuredGeometry is temporarily unavailable", () => {
  // Verify that if (!measuredGeometry) does NOT wipe presentation
  const measuredEffectMatch = guideSource.match(/if \(!measuredGeometry\) \{([\s\S]*?)return;/);
  assert.ok(measuredEffectMatch, "measuredGeometry check must exist");
  assert.ok(!measuredEffectMatch[1].includes("setPresentation(null)"),
    "measuredGeometry check must NOT clear presentation to null on temporary layout shifts");
});

test("OnboardingGuide only applies is-tracking during drag interactions", () => {
  assert.ok(guideSource.includes('step.interaction === "drag" ? " is-tracking" : ""'),
    "is-tracking must only be applied during drag interactions to prevent animation jitter");
});

test("OnboardingGuide CSS ring is-hidden avoids full-screen background quad swap", () => {
  const hiddenRingMatch = cssSource.match(/\.sc-onboarding-ring\.is-hidden\s*\{([\s\S]*?)\}/);
  assert.ok(hiddenRingMatch, ".sc-onboarding-ring.is-hidden rule must exist");
  assert.ok(!hiddenRingMatch[1].includes("background: var(--spotlight-shade)"),
    "is-hidden must NOT swap to solid background which causes GPU tile flashes");
  assert.ok(hiddenRingMatch[1].includes("opacity: 0"),
    "is-hidden must use opacity: 0 for smooth fading");
});

test("useOnboardingTarget exports sameGeometry and reuses geometry reference when values are unchanged", () => {
  assert.ok(targetSource.includes("export function sameGeometry"),
    "sameGeometry must be exported for structural equality checks");
  assert.ok(targetSource.includes("geometry = geometryChanged ? nextGeometry : (geometry ?? nextGeometry)"),
    "geometry reference must be preserved when unchanged to prevent re-render cascading");
});

test("OnboardingGuide uses viewport-bounded SVG mask cutout instead of 150vmax box-shadow to prevent GPU raster starvation", () => {
  assert.ok(!cssSource.includes("150vmax"),
    "OnboardingGuide.css must NOT use 150vmax box-shadow which creates massive dirty rects and 120Hz screen tearing");
  assert.ok(guideSource.includes('mask id="sc-spotlight-mask"'),
    "OnboardingGuide must render SVG mask cutout for the spotlight hole");
  assert.ok(guideSource.includes('className="sc-onboarding-mask-hole"'),
    "OnboardingGuide must render mask hole element");
  assert.ok(cssSource.includes(".sc-onboarding-backdrop"),
    "OnboardingGuide.css must style .sc-onboarding-backdrop");
  assert.ok(cssSource.includes(".sc-onboarding-mask-hole"),
    "OnboardingGuide.css must style .sc-onboarding-mask-hole with smooth geometry transitions");
});

test("OnboardingSession cross-fades startup modal and delays hiding parent root to eliminate flash of unstyled content", () => {
  const sessionFile = path.join(__dirname, "../src/components/onboarding/OnboardingSession.tsx");
  const sessionSource = fs.readFileSync(sessionFile, "utf8");
  assert.ok(sessionSource.includes("window.setTimeout"),
    "OnboardingSession must delay hiding root to allow iframe fade-in");
  assert.ok(!sessionSource.includes('{status !== "ready" && <div\n        ref={fallbackRef}'),
    "OnboardingSession must not abruptly unmount fallback modal synchronously upon ready");
});
