# 新人上手指南

> 给接手 SillyClient 的人。先读 [`ARCHITECTURE.md`](./ARCHITECTURE.md) 了解架构与约定，再读本指南动手。

## 环境要求

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 当前实测 25 亦可，源码兼容 17 |
| Android SDK | compileSdk 37 / minSdk 26 / targetSdk 37 | |
| Node.js | 18+（建议 20+） | 构建 web 模块用 |
| pnpm | 8+ | web 模块依赖管理（launch / console / capacitor-ui） |
| 设备 | arm64-v8a，Android 8.0+ | 首启需联网下 ~136MB server-source |

`gradlew` wrapper 已在仓库根，无需全局 Gradle。

## 完整首次构建到运行

### 1. 构建 Capacitor 控制台 webUI

```bash
cd web/capacitor-ui
pnpm install                       # 首次
pnpm build                         # vite-plugin-singlefile → dist/index.html
cp dist/index.html ../../app/src/main/assets/public/index.html
```

> 产物拷入 `app/src/main/assets/public/`，由 `BridgeActivity.load()` 加载为 Capacitor 主 WebView。改完 web 必须重新拷贝并重打 APK。

### 2. 构建原生 APK

```bash
cd <仓库根>
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 3. 装机运行

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# -r 保留数据（避免首启重下 136MB）；干净安装去掉 -r
adb shell monkey -p com.sillyclient -c android.intent.category.LAUNCHER 1
```

### 4. 首启验证

App 启动后流程：下载解压 server-source → 启 Node → 轮询 `127.0.0.1:8000` 就绪 → WebView 加载酒馆。

验证服务是否起来：

```bash
adb shell "cat /proc/net/tcp | grep ':1F40'"        # 8000=0x1F40，有输出即 LISTEN
adb shell "ps -A | grep -i tarven"                  # libtarven-node.so 进程
```

## 调试

- **WebView 远程调试**：`chrome://inspect`（已开 `setWebContentsDebuggingEnabled`）
- **原生日志**：`adb logcat -s SillyClient:* HybridUiHost:* AndroidRuntime:E`
- **服务端日志**：`adb shell run-as com.sillyclient cat files/tarven/logs/server.log | tail -50`
- **端口检查**：`adb shell "cat /proc/net/tcp | grep ':1F40'"`（8000 = 0x1F40）

## 关键路径速查

| 想做的事 | 看哪里 |
|---|---|
| 理解启动流程 | `MainActivity.kt` onCreate + `plugin/TarvenEnvPlugin.kt` |
| 改 Node 启动 | `runtime/*`（锁定，谨慎） + MainActivity provisionAndStart |
| 改顶框取色/渲染 | `MainActivity.sampleTopColor` / `ui/TopScrimBar.kt` / `ui/TopColor.kt`（DO NOT CHANGE） |
| 改沉浸式 | `enterImmersive`/`showSystemBars`/`statusBarFixedPx` |
| 改控制台 UI | `web/capacitor-ui/src/main.tsx`（React + Vite + @capacitor/core） |
| 改插件接口 | `plugin/TarvenEnvPlugin.kt` + `web/capacitor-ui/src/capacitor-plugin.ts` |

## 常见问题排查

### 8000 端口没起来
1. 看 Node 进程：`adb shell "ps -A | grep -i tarven"` —— 没有则 Node 没拉起，看 logcat SillyClient tag。
2. 看服务日志：`adb shell run-as com.sillyclient cat files/tarven/logs/server.log | tail -50` —— SillyTavern webpack 编译较慢，可能还在编译中，多等 30s 复查端口。
3. 看 server-source 是否下载解压完整：`adb shell run-as com.sillyclient ls files/tarven/bootstrap/server/`。

### 启动即崩溃 ClassNotFoundException
典型是 Kotlin 没编译进 dex（曾发生在 Capacitor 工程 kotlin 插件未配置）。
- 确认 `app/build.gradle.kts` 应用了 Kotlin 插件。
- 确认 `assembleDebug` 日志里有 `compileDebugKotlin` 任务执行。
- 看 logcat AndroidRuntime:E 的 Caused by 行定位缺哪个类。

### 顶框 scrim 不显示 / 颜色不对
- 顶框只在**酒馆模式**显现（root 可见时）。启动页不显示是正常的。
- 取色需 WebView 已绘制：`sampleTopColor` 有 `isShown` 守卫，恢复态无 surface 时跳过，轮询稍后重试。
- 看是否报 `Window doesn't have a backing surface`（已 try/catch，不崩，但说明取色被跳过）。

### Capacitor 工程 web 构建被 pnpm deps 校验拦截
`pnpm run build` 触发 `verify-deps-before-run` 失败时，绕开 pnpm 直接跑：
```bash
node node_modules/typescript/bin/tsc
node node_modules/vite/bin/vite.js build
node_modules/.bin/cap sync android
```

## ⚠️ 不要踩的坑

1. **别重写启动器本体。** 见 ARCHITECTURE.md §7。封装成插件，不要另起工程重写。
2. **别让 Capacitor 渲染酒馆。** 酒馆归插件内原生 WebView（约定 B2）。
3. **别碰顶框代码**（`TopScrimBar`/`TopColor`/`sampleTopColor`），除非要加新平台的取色实现。
4. **品牌名只用 SillyClient。** 见约定 B1。
5. **别用强推 main。** 远程 `main` 是旧 `com.tarven.plus` + ChameleonController 历史；当前基线在 `sillyclient-baseline` 分支。

## Capacitor 化 ✅ 已完成（2026-06-30）

工程已从自撸桥迁移到 Capacitor 插件框架：
- 自撸 `TarvenN` 桥（`HybridUiHost` + `web/bridge.ts`）：**已退役**，代码保留供参考。
- Capacitor 插件接口（`TarvenEnvPlugin` + `@capacitor/core`）：**已接管**全部酒馆启动/进出/状态功能。
- 控制台 webUI：`web/capacitor-ui/`（React + Vite + @capacitor/core），产物 → `assets/public/index.html`。
- 进度/日志/就绪事件通过 `notifyListeners` 推送到 Capacitor JS 侧。

详见 ARCHITECTURE.md §4.2、§6。

### 关键路径速查（更新后）

| 想做的事 | 看哪里 |
|---|---|
| 理解启动流程 | `MainActivity.kt` + `plugin/TarvenEnvPlugin.kt` |
| 改控制台 UI | `web/capacitor-ui/src/main.tsx` |
| 改插件接口 | `web/capacitor-ui/src/capacitor-plugin.ts` (TS) + `plugin/TarvenEnvPlugin.kt` (Kotlin) |
| 改 Node 启动 | `runtime/*`（锁定，谨慎） + MainActivity provisionAndStart |
| 改顶框取色/渲染 | `MainActivity.sampleTopColor` / `ui/TopScrimBar.kt` / `ui/TopColor.kt`（DO NOT CHANGE） |

## 提交规范

- 中文 commit，说清改了什么、为什么。
- 涉及锁定项（Node/顶框/沉浸式）的改动必须显式说明动机。
- 外发操作（push/release）需明确授权。

## 找不到答案？

读 `ARCHITECTURE.md` 全文 → 读 `MainActivity.kt` 与 `plugin/TarvenEnvPlugin.kt` 全文 → 读 `web/capacitor-ui/src/main.tsx`。
这三处基本能解释整个系统。
