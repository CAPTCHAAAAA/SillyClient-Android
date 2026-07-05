# SillyClient Roadmap

> 最后更新：2026-07-06

---

## v0.1 - Runtime Host (done)

- [x] Android project with package `com.sillyclient`
- [x] Kotlin runtime: Paths → Extract → Probe → Start
- [x] Asset extraction from APK assets
- [x] Native library detection from nativeLibraryDir
- [x] Native binary smoke test (`libtarven-node.so --version`)
- [x] Server readiness probe
- [x] Diagnostic UI for self-check

## v0.2 - Build Pipeline (done)

- [x] Pre-built Node.js for Android/Bionic (arm64-v8a) → `libtarven-node.so`
- [x] Pre-built shell, git, curl → `libtarven-*.so`
- [x] Package rootfs-libs.zip (system shared libraries)
- [x] Package rootfs-usr.zip (npm + node_modules)
- [x] Copy finished .so files to jniLibs
- [x] Copy finished .zip files to assets

## v0.3 - Server Launch (done)

- [x] `start-server.sh` works with native Node
- [x] SillyTavern configured for local-only mode (127.0.0.1:8000)
- [x] Server starts and responds on 127.0.0.1:8000
- [x] Server log captured to app private storage
- [x] npm install with Taobao mirror + retry + correct TMPDIR
- [x] Webpack frontend compilation (NODE_OPTIONS=--max-old-space-size=2048)
- [x] Port conflict detection (kill stale process before start)
- [x] Process exit detection during polling

## v0.4 - WebView (done)

- [x] Native WebView loads http://127.0.0.1:8000
- [x] Back navigation handled
- [x] Server lifecycle (start on open, stop on close)
- [x] Chameleon top bar (PixelCopy color extraction + scrim)
- [x] Immersive mode (cutout / status bar adaptation)
- [x] SHORT_EDGES display + MIUI/HyperOS compatibility

## v0.5 - Manager Features (done)

- [x] Multi-instance management (local + remote)
- [x] GitHub Releases version integration (API + proxy mirrors)
- [x] Local zip import (offline install)
- [x Instance config editor (config.yaml sync)
- [x] Terminal panel (shell commands + real-time log)
- [x] Background service
- [x] Garbage cleanup

## v1.0 - Capacitor Plugin Architecture (done)

- [x] Capacitor 7 plugin framework
- [x] TarvenEnvPlugin (provision / start / enter / exit)
- [x] Control webUI (React + TanStack Router)
- [x] Dual WebView: Capacitor webUI + native WebView for SillyTavern
- [x] Liquid Glass UI design system

## v1.1 - UI Polish & npm Fix (current, 2026-07-06)

- [x] Remove Montserrat/Nunito Google Fonts (AI-looking fonts)
- [x] System font stack (PingFang SC → system-ui)
- [x] Liquid Glass for all secondary/tertiary menus
- [x] Reduce backdrop blur on overlays
- [x] Dark mode glass color follows blue-purple theme (#1a1625)
- [x] Dynamic mode glass color follows rose theme (#6a112e)
- [x] Remove redundant icon annotations
- [x] npm install fix: TMPDIR env + Taobao mirror + retry
- [x] npm packaged in APK (rootfs-usr.zip)
- [x] Server startup fix: kill stale process + NODE_OPTIONS + pushReady
- [x] Poll timeout 120s → 180s (webpack needs 24s)
- [x] Clean stale instance dir on install failure

## v1.2 - Stability & UX (next)

- [ ] Pre-bundle node_modules to skip npm install entirely
- [ ] Foreground service for reliable background operation
- [ ] Auto-restart on crash
- [ ] Instance import/export
- [ ] Screenshot support in README

## Future

- [ ] Windows support (system Node.js)
- [ ] iOS support
- [ ] Extension marketplace
- [ ] Cloud sync for configs
