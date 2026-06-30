# SillyClient 架构说明

> 项目架构总览文档。动手前先读，改动方向请对照下述架构约定。

最后更新：2026-06-30

---

## 0. 一句话定位

**SillyClient** 把 SillyTavern 在设备上本地化运行：随 App 打包服务端，本地启动，前端套壳。
**基于 Capacitor 插件框架**（2026-06-30 完成电容化），控制台 webUI 经 `@capacitor/core` 调 `TarvenEnvPlugin`，酒馆仍由插件内原生 WebView 承载。
品牌名 **SillyClient 唯一**，跨所有框架 / 平台 / UI 统一，不接受 `Tarven++` / `TarvenPlus` 等别名。

---

## 1. 核心理念：插件化多端

### 1.1 问题
不同平台的"底座"完全不同：
- **安卓**：没有现成 Node，必须把 Node.js 以原生库（`libtarven-node.so`）打包进 App，运行时解压、用 `ProcessBuilder` 拉起，监听 `127.0.0.1:8000`。
- **PC**：系统里已有 Node，SillyTavern 直接跑在系统 Node 上，**不走虚拟机那套**。
- **iOS**：又是另一套约束。

如果每个平台从零把"启动器 + 阅读环境"重写一遍，工作量与 bug 面不可控。

### 1.2 解法：把"一整个原生阅读环境"封装成插件

**关键认知：插件封装的是"一个完整的沉浸式阅读环境"，不是零散的能力方法集合。**

一个插件实现，内部完整包含：
- Node.js 的启动 / 下载 / 检测 / 配置 / 轮询就绪
- **一个原生 WebView 承载酒馆**（加载 `127.0.0.1:8000` 或远程地址）
- 沉浸式（刘海/状态栏适配、SHORT_EDGES、抗 MIUI/HyperOS）
- 变色龙顶框（PixelCopy 取色 → scrim/光波/色波）
- 进/出阅读环境的生命周期

webUI（控制台）**永远只调统一的插件接口**，不知道底下是安卓虚拟机还是 PC 系统 Node。
**新增平台 = 新增一份插件实现去填接口，webUI 零改动。**

### 1.3 架构约定

| 编号 | 约定 |
|---|---|
| **B1** | 品牌名统一 `SillyClient`，全平台全框架。 |
| **B2** | **酒馆（SillyTavern）永远由插件内的原生 WebView 承载，Capacitor 不渲染酒馆。** Capacitor 的 WebView 只承载控制台 webUI。 |
| **B3** | 启动器本体（环境检测/下载/配置/启动/打开阅读环境）**已完善，不重写**。要做的是封装成插件，不是再造一遍。 |
| **B4** | 锁定项（Node 运行时 / 变色龙顶框 / 沉浸式）原逻辑不动，只加插件接口。详见第 5 节。 |
| **B5** | **最终只存活一个工程：`com.sillyclient`**。它 Capacitor 化后仍叫 `com.sillyclient` / appName `SillyClient` / 仓库 `SillyClient`。`TarvenIonicApp` 那条重写线**作废**。 |

---

## 2. 三层架构

```
┌─────────────────────────────────────────────────────────────┐
│  控制台 webUI (React/Ionic, Capacitor WebView 承载)           │
│  只调统一接口，不认平台。可做"环境管理器"(ComfyUI 式)。        │
└───────────────────────────┬─────────────────────────────────┘
                            │ Capacitor plugin 接口
                            │ (provision / start / enter / …)
┌───────────────────────────┴─────────────────────────────────┐
│  插件接口契约层 (TS interface，全平台共享，写一次)             │
└───────────────────────────┬─────────────────────────────────┘
                            │ 各平台各写一份实现去填接口
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
  安卓插件实现          PC 插件实现         iOS 插件实现
  (Node 虚拟机)         (系统 Node)         (待定)
  ────────────         ────────────         ────────────
  libtarven-node.so    spawn 系统 Node      …
  原生 WebView 承 ST   原生 WebView 承 ST   …
  沉浸式/顶框          沉浸式/顶框          …
```

### 2.1 接口契约层（写一次，全平台共享）

Capacitor plugin 的 TypeScript 接口声明。webUI 只 import 这个，绝不直接碰原生。
**所有平台共享同一份接口，差异只在实现。**

### 2.2 平台实现层

每个平台一份 `@CapacitorPlugin` 实现，填契约层的接口。
安卓复用现有已完善的代码（见第 4 节），**不重写**。

### 2.3 数据流（一次完整调用的链路）

以控制台点击「进入酒馆」为例，调用链如下：

```
控制台 webUI (React)
  │  await TarvenEnv.enterImmersive()
  ▼
@capacitor/core  registerPlugin('TarvenEnv')
  │  跨 JS 桥，序列化为 {pluginId, methodId, options}
  ▼
Android BridgeActivity
  │  按 pluginId 路由到 TavernEnvPlugin
  ▼
@PluginMethod enterImmersive(call: PluginCall)
  │  调用现有 MainActivity 的 enterImmersive()/switchToWebView(true)
  │  （原逻辑一行不动：沉浸式 + WebView 承 ST + 顶框探针）
  ▼
call.resolve()  →  Promise 在 webUI 侧 resolve
```

- JS 侧拿到的是 Promise，错误自动传播（插件内 `call.reject(msg)` → webUI `catch`）。
- 原生→JS 的事件推送（如顶框色变化）用 `notifyListeners("color", jsObject)`，webUI 用 `TarvenEnv.addListener('color', cb)` 接收。

### 2.4 多环境层（ComfyUI 式，规划中）

一个系统上可配多个环境，可切换：
- 安卓本地虚拟机环境
- PC 本地 Node 环境
- 远程服务器环境

webUI 提供"环境管理器"：增删环境 / 切换当前环境 / 启停。
酒馆 WebView 的加载地址跟随当前环境（`127.0.0.1:8000` / 远程 URL / …）。

---

## 3. 两个 WebView 的分工（约定 B2 详解）

| WebView | 承载 | 谁渲染 | 归属 | 说明 |
|---|---|---|---|---|
| **Capacitor WebView** | 控制台 webUI | Capacitor (React/Ionic) | 控制层 | 控制台通过插件接口控制阅读环境 |
| **插件内原生 WebView** | 酒馆 SillyTavern | 插件自己（原生） | 阅读环境层 | 承载 `127.0.0.1:8000`，叠沉浸式/顶框 |

### 3.1 为什么不能用一个 WebView

1. **跨源取色**：酒馆是 `127.0.0.1:8000` 的远端内容，与控制台 webUI 不同源。同源策略下 JS 读不到酒馆 DOM，canvas 也会被污染画不出像素。取色只能靠原生（PixelCopy），所以承载酒馆的 WebView 必须在原生侧。
2. **沉浸式/顶框依赖原生**：刘海像素、状态栏控制、顶框 scrim 渲染都是原生 API，无法在 web 里做。这些必须贴着承载酒馆的那个 WebView。
3. **职责正交**：控制台是自有 webUI（跨平台复用，归 Capacitor）；酒馆是远端内容（原生承载，归插件）。混在一个 WebView 里既取不了色也复用不了。

结论：**控制台归 Capacitor，酒馆归插件内原生 WebView，二者不混用。**

---

## 4. 现有安卓代码怎么变插件（不重写，只封装）

### 4.1 现有能力清单（已完善，能跑）

| 能力 | 现有实现 | 状态 |
|---|---|---|
| Node.js 本地运行时 | `runtime/*`（AssetExtractor/RuntimeFileUtils/RuntimePaths/TarvenProcessRunner/TarvenRuntimeManager）+ MainActivity 供水链路（provisionAndStart/extractNativeLibs/downloadAndExtractServer/startServer/pollUntilReady） | 锁定 |
| 原生 WebView 承酒馆 | `webView`/`webViewScreen` 加载 `127.0.0.1:8000` | 锁定 |
| 沉浸式 / 进出酒馆 | `switchToWebView`/`enterTavern`/`exitTavern`/`enterImmersive`/刘海取 px | 锁定 |
| 变色龙顶框 | `sampleTopColor`(PixelCopy) + `TopScrimBar` + `TopColor`（`DO NOT CHANGE`） | 锁定 |
| 自撸桥（待替代） | `TarvenN.invoke` / `__tarvenDispatch`（HybridUiHost + web/bridge.ts） | 渐进替代为插件接口 |

### 4.2 封装做法 ✅ 已完成（2026-06-30）

**不是在另一个工程重写，是在现有 `com.sillyclient` 上加 Capacitor 插件层。**

#### 已落地的改动

| # | 改动 | 状态 |
|---|------|------|
| 1 | `app/build.gradle.kts` + `libs.versions.toml`：添加 `capacitor-android` + `appcompat` | ✅ |
| 2 | `MainActivity` 继承 `BridgeActivity`（原 `ComponentActivity`），`onCreate` 前 `registerPlugin(TarvenEnvPlugin)` | ✅ |
| 3 | 新建 `plugin/TarvenEnvPlugin.kt`：`@CapacitorPlugin(name="TarvenEnv")`，封装 `provisionAndStart` / `enterImmersive` / `exitImmersive` / `getStatus` | ✅ |
| 4 | 插件 `companion.notify()` 推送进度/日志/就绪/模式事件 → JS `addListener` 接收 | ✅ |
| 5 | `MainActivity` 进度推送从 `hybridHost.pushXxx` 改为 `TarvenEnvPlugin.notify` | ✅ |
| 6 | `BridgeActivity.load()` 加载 Capacitor 主 WebView（`assets/public/index.html`），原 `HybridUiHost` setContentView 已注释，自撸 `TarvenN` 桥退役 | ✅ |
| 7 | 新建 `web/capacitor-ui/`：React + Vite + `@capacitor/core` + `vite-plugin-singlefile`，产物 → `assets/public/index.html` | ✅ |
| 8 | `AndroidManifest.xml` label 改为 `SillyClient`，`capacitor.config.json` 配置 appId | ✅ |

#### 关键实现细节

- MainActivity 原有逻辑（provisionAndStart / enterTavern / exitTavern / 沉浸式 / 顶框 / Node 运行时）一行未动，仅从 `private` 改为 `public` 供插件调用。
- 原生酒馆 WebView（`root` FrameLayout + `webViewScreen`）仍通过 `addContentView` 叠加在 Capacitor WebView 之上。
- 自撸 `TarvenN` 桥（HybridUiHost + `web/bridge.ts`）已退役但代码保留供参考。

### 4.3 插件接口草案（控制台 → 阅读环境）

```ts
interface TarvenEnvPlugin {
  provision(envId?: string): Promise<void>       // 配置环境（下载/检测/解压）
  start(envId?: string): Promise<{ url: string }> // 启动，返回酒馆地址
  stop(envId?: string): Promise<void>
  status(envId?: string): Promise<{ ready: boolean }>
  enterImmersive(): Promise<void>                 // 进沉浸式（插件搭原生 WebView 承 ST + 顶框）
  exitImmersive(): Promise<void>
  getColor(): Promise<{ hex: string }>            // 取当前顶框色
  addListener(event: 'color', cb: (d: { hex: string }) => void): Promise<PluginListenerHandle>
  // 多环境（后续）
  listEnvironments(): Promise<Environment[]>
  addEnvironment(config): Promise<Environment>
  removeEnvironment(id: string): Promise<void>
  setCurrent(id: string): Promise<void>
}
```

---

## 5. 锁定项与约束

### 5.1 变色龙顶框（`DO NOT CHANGE`）

- **取色层**：`MainActivity.sampleTopColor`，PixelCopy 读 WebView 顶部 3px×全宽条带 → 平均非透明像素。
- **渲染层**：`TopScrimBar`（scrim 三段渐变 45/80/100% + 点击白色光波 + 自下而上色波）+ `TopColor`（纯色数学）。
- **为什么锁定**：实测已满足需求且渲染开销低；跨源 iframe 下取色无可移植 API，这套是已验证的安卓原生方案，重写收益低、风险高。
- **约束**：酒馆是远端跨源 iframe，JS 取不到色、canvas 被污染，**取色层无可移植 API，必须各端原生**（Android=PixelCopy / iOS=UIGraphics / 桌面=离屏捕获）。
- 历史背景：远程 `main` 分支曾有更重的 `ChameleonController`（单动画器版），本项目保留**轻量版 TopScrimBar**。

### 5.2 Node.js 运行时

- Node v24 Bionic，以 `libtarven-node.so` + `libc++_shared.so`（arm64-v8a）打包。
- `rootfs-libs.zip` 运行时解压，`ProcessBuilder` 拉起，监听 `127.0.0.1:8000`。
- 首启从 GitHub Release 下 `server-source.zip`（SillyTavern + node_modules）。
- **为什么锁定**：Node 虚拟机方案调试链路长、跨 ABI/rootfs 细节多，已验证能稳定起服务；非必要不折腾。
- PC 平台**不走这套**，直接用系统 Node。

### 5.3 沉浸式 / 刘海适配
`DisplayCutout` 取硬件像素（`statusBarFixedPx`），SHORT_EDGES 沉浸式，专门抗 MIUI/HyperOS 系统行为。
**为什么锁定**：厂商系统行为（MIUI/HyperOS）适配试错成本高，当前已稳定。

---

## 6. 路线与阶段

### ✅ 已完成：工程 Capacitor 化 + 封装 TarvenEnvPlugin（2026-06-30）
- [x] 现有 `com.sillyclient` 加 `@capacitor/android`（`capacitor-android:7.2.0`）
- [x] MainActivity → `BridgeActivity`，保留全逻辑
- [x] 封装 `TarvenEnvPlugin`，复用现有 Node 启动 / WebView / 沉浸式 / 顶框
- [x] 控制台 webUI（`web/capacitor-ui/`）经 `@capacitor/core` 调插件
- [x] 自撸 `TarvenN` 桥退役（HybridUiHost + `web/bridge.ts`，代码保留供参考）
- [x] 真机验证通过（Xiaomi 14 / HyperOS）：provisioning → 进度事件推送 → Enter Tavern → 酒馆 WebView 显示 SillyTavern 1.18.0

### 后续阶段
- PC 端插件实现（系统 Node，不走虚拟机）
- iOS 端插件实现
- 多环境管理器（ComfyUI 式）

### 已作废（不再投入）
- `TarvenIonicApp`（`com.tarven.plus`）：在其中重写 `TarvenServerPlugin` 的整条线。
  错误在于"重写壳"而非"封装插件"，且包名/品牌违背约定。仅作探索参考保留。

---

## 7. 历史记录

1. **不要重写已完善的启动器本体。** 2026-06 期间曾在 `TarvenIonicApp` 里把 Node 启动/进酒馆又写了一遍，结果踩了 kotlin 插件未配置（`ClassNotFoundException`）、pnpm deps 校验拦截、jvmTarget 不匹配等一堆 Capacitor/Ionic 工具链坑，纯属重复劳动。**正确做法是封装成插件。**

2. **酒馆渲染权永远在插件内的原生 WebView。** 不要试图让 Capacitor 去渲染酒馆。酒馆是远端跨源内容，沉浸式/顶框必须原生做。

3. **品牌名只有 SillyClient。** 历史上 `Tarven++` / `TarvenPlus` 混用过，造成仓库与应用名漂移。统一用 SillyClient。

4. **取色层没有可移植 API。** 别想在 JS 里读酒馆顶色——跨源 iframe 挡死。各端原生（PixelCopy/UIGraphics/离屏捕获）。

5. **多端复用的是 webUI 和接口契约，不是原生重活。** Node 启动、取色、沉浸式这些重活仍要各端原生重写，Capacitor 只省下 UI 壳和控制接口那一层。

6. **2026-06-30 Capacitor 化完成。** MainActivity 成功继承 BridgeActivity，TarvenEnvPlugin 封装酒馆启动/进出，进度/日志/状态事件经 notifyListeners 推送到 capacitor-ui 前端。真机（Xiaomi 14 / HyperOS）端到端验证通过：Provision → Start → Enter → SillyTavern 1.18.0 正常运行 + 变色龙顶框在位。自撸 TarvenN 桥退役。

---

## 8. 目录与关键文件（当前 `com.sillyclient`，已 Capacitor 化）

```
app/src/main/java/com/sillyclient/
├── MainActivity.kt              # 宿主：BridgeActivity，环境搭建 + 阅读环境
├── runtime/                     # Node 运行时（锁定）
│   ├── AssetExtractor.kt
│   ├── RuntimeFileUtils.kt
│   ├── RuntimePaths.kt
│   ├── TarvenProcessRunner.kt
│   └── TarvenRuntimeManager.kt
├── plugin/                      # Capacitor 插件层 ✅
│   └── TarvenEnvPlugin.kt       # 封装 provision/enter/exit/status + notifyListeners 事件推送
└── ui/
    ├── HybridUiHost.kt          # 自撸桥（退役，代码保留供参考）
    ├── TopScrimBar.kt           # 变色龙顶框渲染（锁定，DO NOT CHANGE）
    └── TopColor.kt              # 取色纯数学（锁定）

web/
├── capacitor-ui/                # Capacitor 控制台 webUI ✅（React + @capacitor/core）
│   ├── src/main.tsx             # 主页面：按钮 + 进度条 + 日志面板 + addListener 事件监听
│   └── src/capacitor-plugin.ts  # TarvenEnv 插件 TS 接口定义
├── launch/                      # 旧启动页（自撸桥时期，代码保留）
└── console/                     # 旧控制台（代码保留，供移植参考）

app/src/main/assets/
├── capacitor.config.json        # Capacitor 配置
└── public/index.html            # capacitor-ui 构建产物（BridgeActivity.load() 加载）
```

---

## 9. 相关链接

- 仓库：https://github.com/CAPTCHAAAAA/SillyClient
- Capacitor 转型探索工程（已作废，仅参考）：https://github.com/CAPTCHAAAAA/SillyClient-Capacitor
- 当前安卓基线 Release：`v0.6-sillyclient-baseline`（分支 `sillyclient-baseline`）

---

## 10. 术语表

| 术语 | 释义 |
|---|---|
| **SillyClient** | 唯一品牌名，全平台统一 |
| **酒馆 / SillyTavern / ST** | 被承载的远端应用，运行在 `127.0.0.1:8000`（或远程），由插件内原生 WebView 承载 |
| **控制台 webUI** | 自有的前端控制界面（React/Ionic），由 Capacitor WebView 承载，通过插件接口控制阅读环境 |
| **启动器本体** | MainActivity 里环境检测/下载/配置/启动/打开阅读环境那一整块已完善的逻辑（不重写） |
| **阅读环境** | 插件封装的完整体验：原生 WebView 承酒馆 + 沉浸式 + 顶框 + 进出生命周期 |
| **TarvenEnvPlugin** | 封装阅读环境的 Capacitor 插件（规划中） |
| **provision** | 配置环境：下载/检测/解压服务端 |
| **变色龙顶框** | 据酒馆页顶取色渲染的顶条带（scrim + 点击光波 + 色波），原生实现 |
| **TarvenN 桥** | 现有自撸 JS↔原生桥，待被插件接口渐进替代 |
