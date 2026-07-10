<div align="center">

<!-- Logo placeholder -->
<img src="docs/screenshots/launcher.png" alt="SillyClient" width="120" height="120" style="border-radius: 24px;">

# SillyClient

**跨平台 SillyTavern 启动器 -- 在 Android 上一键运行完整酒馆实例**

[![GitHub release](https://img.shields.io/github/v/release/CAPTCHAAAAA/SillyClient?style=flat-square&color=blue)](https://github.com/CAPTCHAAAAA/SillyClient/releases)
[![Version](https://img.shields.io/badge/Version-1.2.0-blue.svg?style=flat-square)](https://github.com/CAPTCHAAAAA/SillyClient/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg?style=flat-square)](https://github.com/CAPTCHAAAAA/SillyClient)
[![Windows: In Progress](https://img.shields.io/badge/Windows-In_Progress-orange.svg?style=flat-square)](https://github.com/CAPTCHAAAAA/SillyClient)
[![iOS: Planned](https://img.shields.io/badge/iOS-Planned-lightgrey.svg?style=flat-square)](https://github.com/CAPTCHAAAAA/SillyClient)

</div>

---

## SillyClient 是什么？

SillyClient 将 Node.js 运行时、SillyTavern 服务端和沉浸式 WebView 打包在一起，让用户在 Android 设备上**一键运行完整的 SillyTavern 实例**，无需 Termux 或任何额外配置。

> "把酒馆装进口袋。"

## ✨ 核心特性

| 特性 | 描述 |
|:---|:---|
| 📦 **内置 Node.js 运行时** | 基于 Termux/Proot 的 Linux ARM64 环境，预打包 Node.js 及全部依赖，开箱即用 |
| 🔀 **多实例管理** | 同时配置多个本地/远程 SillyTavern 实例，独立端口、独立配置，随时切换 |
| 🚀 **GitHub Releases 集成** | 从 GitHub 实时拉取 SillyTavern 版本列表，可选任意版本一键安装 |
| 🖥️ **沉浸式 WebView** | 原生 WebView 承载酒馆界面，变色龙顶框自动取色，刘海/挖孔完美适配 |
| 🎨 **VisionOS 风格 UI** | 液态玻璃导航栏、动态背景光晕、毛玻璃面板，视觉体验拉满 |
| 💻 **termux 式终端** | 内置终端面板，可执行 shell 命令、实时查看日志输出 |
| ⚙️ **实例配置同步** | 管理面板设置实时写入 config.yaml，启动端口/网络协议/心跳等参数一键生效 |

## 📸 截图

> 以下为 Android 端截图占位，实际图片待替换。Windows 端截图将在 v1.2.0 脚手架就绪后补入。

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/launcher.png" alt="启动器主界面" width="240" style="border-radius: 16px;"><br><sub>启动器主界面（实例列表）</sub></td>
    <td align="center"><img src="docs/screenshots/launcher.png" alt="沉浸式酒馆阅读环境" width="240" style="border-radius: 16px;"><br><sub>沉浸式酒馆阅读环境（变色龙顶框）</sub></td>
    <td align="center"><img src="docs/screenshots/launcher.png" alt="termux 式终端面板" width="240" style="border-radius: 16px;"><br><sub>termux 式终端面板（实时日志）</sub></td>
  </tr>
</table>

## 🏗️ 架构概览

SillyClient 采用**三层插件化架构**，前端 UI 与平台实现完全解耦：

```
┌──────────────────────────────────────────────┐
│  控制台 webUI (React + TanStack Router)       │
│  由 Capacitor WebView 承载                    │
└─────────────────────┬────────────────────────┘
                      │  Capacitor Plugin 接口
┌─────────────────────┴────────────────────────┐
│  插件接口契约层 (TypeScript，全平台共享)        │
└────────┬──────────────────────┬───────────────┘
         │                      │
    Android 实现           Windows 实现 (进行中) / iOS (规划中)
    ─────────────          ──────────────────
    libtarven-node.so      系统 Node.js (Windows)
    原生 WebView           WebView2 (Windows)
    沉浸式 / 变色龙顶框     沉浸式 / 变色龙顶框
```

**双 WebView 分工：**

| WebView | 承载内容 | 归属 |
|:---|:---|:---|
| Capacitor WebView | 控制台 webUI | 控制层 |
| 原生 WebView | SillyTavern 酒馆 | 阅读环境层 |

> 酒馆由插件内原生 WebView 承载（解决跨源取色与沉浸式适配问题），Capacitor 只负责控制台 UI。

## 🛠️ 技术栈

| 层级 | 技术 |
|:---|:---|
| 前端 UI | React 19 + TypeScript + Vite + TailwindCSS 4 |
| 路由 | TanStack Router |
| UI 组件 | Radix UI + Framer Motion + shadcn/ui |
| 跨平台框架 | Capacitor 7 |
| 原生层 (Android) | Kotlin + BridgeActivity |
| 运行时 | Node.js v24 (Bionic ARM64) |

## 🚀 快速开始

### 下载安装

前往 [GitHub Releases](https://github.com/CAPTCHAAAAA/SillyClient/releases) 下载最新的 Android APK，直接安装即可。

### 首次启动

1. 打开 App，进入管理面板
2. 从 GitHub Releases 列表中选择 SillyTavern 版本并安装
3. 等待服务端自动配置（首次需下载运行时与资源）
4. 点击「进入酒馆」-- 沉浸式阅读环境即刻呈现

### 系统要求

- Android 10+ (API 29+)
- ARM64 架构设备
- 约 500MB 可用存储空间（含运行时 + SillyTavern）

## 🔧 构建说明

### 环境准备

```bash
# 前端依赖
pnpm install  # Node.js 22+ required

# Android 构建
Android Studio Ladybug+ | Gradle 8.x | JDK 17+
```

### 构建步骤

```bash
# 1. 构建控制台 webUI
cd web/capacitor-ui
pnpm install
pnpm build
# 产物自动输出至 app/src/main/assets/public/

# 2. 构建 Android APK
# 在项目根目录执行
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 🗺️ 路线图

### v1.1.x（已完成）

- [x] Android 端 -- Node.js 运行时 + SillyTavern 服务端 + 沉浸式 WebView
- [x] Capacitor 插件化架构 (TarvenEnvPlugin)
- [x] 变色龙顶框 (PixelCopy 取色 + 毛玻璃渲染)
- [x] GitHub Releases 版本管理集成
- [x] 多实例/多环境管理器 (ComfyUI 式)
- [x] 本地 zip 导入 (离线安装)
- [x] npm install 修复 (淘宝镜像 + 重试 + TMPDIR)
- [x] 液态玻璃 UI (所有二级三级菜单统一质感)

### v1.2.0（进行中）

- [ ] Windows 端支持 (系统 Node.js) — 🚧 进行中
  - Electron 外壳 + WebView2 承载酒馆界面
  - 复用 `capacitor-ui` 前端，Electron 主进程实现 TarvenEnv 插件接口
  - 调用系统 Node.js 启动 SillyTavern 服务端（不打包运行时）

### 后续规划

- [ ] iOS 端支持

## 🤝 贡献

欢迎任何形式的贡献！无论是 Bug 报告、功能建议还是代码提交。

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交改动 (`git commit -m 'Add some amazing feature'`)
4. 推送至远程 (`git push origin feature/amazing-feature`)
5. 发起 Pull Request

> 提交前请确保 `./gradlew assembleDebug` 构建通过，前端请确保 `pnpm build` 无报错。

## ⚠️ 发版规则

**本仓库（SillyClient-Android）仅作为源码仓库，禁止创建 Release 和 Tag。**

所有版本发布统一在主仓库 [SillyClient](https://github.com/CAPTCHAAAAA/SillyClient) 进行：
- Release 和 Tag 只在主仓库创建
- APK 文件只上传到主仓库的 Release
- 本仓库零 Release、零 Tag

详见 [发版规则文档](https://github.com/CAPTCHAAAAA/SillyClient/blob/main/release/RELEASE-GUIDE.md)。

## 📄 License

本项目基于 [MIT License](https://opensource.org/licenses/MIT) 开源。

---

<div align="center">

**SillyClient** -- 把酒馆装进口袋。

Made with ❤️ by [CAPTCHAAAAA](https://github.com/CAPTCHAAAAA)

</div>
