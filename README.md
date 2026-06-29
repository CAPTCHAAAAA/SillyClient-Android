# SillyClient

**SillyClient** 是唯一品牌名，跨所有框架与平台统一使用，不接受别名。

在设备上一键本地化运行 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 的壳应用：把服务端随 App 打包，本地启动后用原生 WebView 承载酒馆，前端控制台通过插件接口控制整套阅读环境。当前安卓端可用，正转向 Capacitor 插件化多端架构。

---

## 它是什么

1. **打包 Node 起本地服务**：Node.js 以原生库打包进 App，启动后在 `127.0.0.1:8000` 跑起 SillyTavern。
2. **原生 WebView 承载酒馆**：用一个原生 WebView 加载酒馆，叠加沉浸式阅读环境（刘海适配、变色龙顶框取色）。
3. **控制台 webUI 控制**：webUI 通过插件接口控制阅读环境（进/出/取色/启停），不直接碰原生。

> 启动器本体（环境检测 / 下载 / 配置 / 启动 / 打开阅读环境）已完善，不重写，只封装成插件。

## 架构速览

三层 + 双 WebView（职责正交）：

| 层 | 内容 |
|---|---|
| 控制台 webUI | React/Ionic，由 Capacitor WebView 承载，只调统一插件接口 |
| 接口契约 | Capacitor plugin TS 接口，全平台共享，写一次 |
| 平台实现 | 各平台一份 `@CapacitorPlugin` 实现，复用各端已做好的原生能力 |

| WebView | 承载 | 归属 |
|---|---|---|
| Capacitor WebView | 控制台 webUI | 控制层 |
| 插件内原生 WebView | 酒馆 SillyTavern | 阅读环境层（**Capacitor 不渲染酒馆**） |

完整说明见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 快速开始（安卓）

```bash
# 原生壳（含已完善的启动器本体）
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# 启动页 webUI（改动 web/launch 后需重新构建并拷入 assets）
cd web/launch && pnpm build
cp dist/index.html ../../app/src/main/assets/ui/launch/index.html
```

装机运行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk   # -r 保留数据，免首启重下 136MB
adb shell monkey -p com.sillyclient -c android.intent.category.LAUNCHER 1
```

首启：下载解压 server-source → 启 Node → 轮询 `127.0.0.1:8000` 就绪 → WebView 加载酒馆。

## 文档导航

| 文档 | 说明 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构总览：三层架构、双 WebView 分工、架构约定、锁定项、路线 |
| [docs/ONBOARDING.md](docs/ONBOARDING.md) | 新人上手：环境要求、构建装机、调试、路径速查、常见排查 |
| [docs/DECISIONS.md](docs/DECISIONS.md) | 决策记录（ADR）：关键架构决策的背景与被否方案 |

## 当前状态

- ✅ 安卓端可用：Node 本地运行时、原生 WebView 承酒馆、沉浸式、变色龙顶框（锁定）
- 🔧 进行中：现有 `com.sillyclient` 工程 Capacitor 化，把上述能力封装成 `TavernEnvPlugin`
- ⛔ 已作废：`SillyClient-Capacitor` 仓（Capacitor 重写探索，仅留参考）

## 相关

- 仓库：https://github.com/CAPTCHAAAAA/SillyClient
- 当前基线分支：`sillyclient-baseline` ｜ 架构文档分支：`capacitor-plugin-architecture`
- 作废参考仓：https://github.com/CAPTCHAAAAA/SillyClient-Capacitor

## License

MIT
