# SillyClient

> **SillyClient 是我们唯一的品牌名。** 跨所有框架、所有平台、所有 UI，统一叫 SillyClient。
> 不再有 `Tarven++` / `TarvenPlus` 等别名。

一键把 SillyTavern 在设备上本地化运行的壳应用。SillyTavern 服务端随 App 打包，本地启动后前端套壳提供 UI。当前安卓端已可运行，正转向 **Capacitor 插件化多端架构**。

📖 **完整架构计划必读**：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

---

## 它是什么

在安卓上，SillyClient 内嵌一个 Node.js 运行时（`libtarven-node.so`），启动后在本地 `127.0.0.1:8000` 跑起 SillyTavern 服务，再用一个**原生 WebView** 承载酒馆，并叠加沉浸式阅读环境（刘海/状态栏适配、变色龙顶框取色等）。

启动器本体（环境检测 / 下载 / 配置 / 启动 / 打开阅读环境）**已完善且能跑**，不重写。

## 为什么是插件化（核心理念）

不同平台的"底座"不一样：安卓靠打包 Node 虚拟机，PC 上 SillyTavern 直接跑在系统 Node、不走虚拟机。如果每个平台从零适配，极其痛苦。

解法：**把"一整个原生阅读环境"封装成 Capacitor 插件。**

- 一份 webUI（控制台）只调**统一的插件接口**，不认平台。
- 每个平台写一份插件实现去填接口，复用各平台已做好的原生能力。
- 一个系统上还能配多个环境（ComfyUI 式），可切换。

详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 当下状态（安卓）

- ✅ Node.js 本地运行时（下载/检测/解压/启动 `127.0.0.1:8000`）—— 锁定，不重写
- ✅ 原生 WebView 承载酒馆 + 沉浸式 + 刘海/状态栏适配 —— 锁定
- ✅ 变色龙顶框：`PixelCopy` 条带取色 → `TopScrimBar`（scrim 渐变 + 点击光波 + 色波）—— 锁定（`DO NOT CHANGE`）
- 🔧 正在：现有 `com.sillyclient` 工程 Capacitor 化，把上述能力封装成 `TavernEnvPlugin`

## 快速构建（安卓，当前实现）

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

启动页 web 模块单独构建后拷入 assets：

```bash
cd web/launch && pnpm build   # vite-plugin-singlefile → dist/index.html
cp dist/index.html ../../app/src/main/assets/ui/launch/index.html
```

## 相关

- 仓库：`CAPTCHAAAAA/SillyClient`
- Capacitor 转型探索工程（已作废，仅留参考）：`CAPTCHAAAAA/SillyClient-Capacitor`

## License

MIT
