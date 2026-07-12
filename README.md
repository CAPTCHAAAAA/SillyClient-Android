<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="SillyClient" width="120" height="120" style="border-radius: 24px;">

# SillyClient

**跨平台 SillyTavern 启动器 -- 在 Android 上一键运行完整酒馆实例**

[![GitHub release](https://img.shields.io/github/v/release/CAPTCHAAAAA/SillyClient?style=flat-square&color=blue)](https://github.com/CAPTCHAAAAA/SillyClient/releases)
[![Version](https://img.shields.io/badge/Version-1.4.0-blue.svg?style=flat-square)](https://github.com/CAPTCHAAAAA/SillyClient/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg?style=flat-square)](https://github.com/CAPTCHAAAAA/SillyClient)

</div>

## 概述

SillyClient Android 端，将 SillyTavern 和嵌入式 Node.js 运行时打包为原生 Android 应用。用户无需 Termux 或任何命令行操作，安装 APK 后即可在手机上运行完整的酒馆实例。

## 下载

前往主仓库 [Releases](https://github.com/CAPTCHAAAAA/SillyClient/releases) 页面下载 APK。

> 最低系统要求：Android 7.0 (API 24)
> 架构：arm64-v8a

## 核心功能

### 实例管理
- 多实例创建与切换，支持本地实例和远程连接
- GitHub 版本动态拉取，一键选择 SillyTavern 版本
- 从本地 zip 或在线 zipball 安装
- 卡片式轮播界面，支持自定义封面图
- 实例配置持久化（监听、IPv4/IPv6、心跳、保活等）

### 运行时
- 嵌入式 Node.js 运行时（Termux bootstrap）
- 本地实例一键启动，自动配置 SillyTavern
- 远程实例在线检测（原生 HEAD 请求，绕过 CORS）
- 自动检测可用端口
- 进程管理（destroyForcibly 停止实例）

### 交互体验
- 顶部状态栏手势：下滑退出酒馆返回启动器（实例继续运行）
- 反方向滑动：返回正在运行的酒馆
- 卡片菜单："返回酒馆" / "停止实例"按钮
- 下拉刷新（iOS 风格阻尼动画）
- 挖孔屏安全区自动避让

### 开发者工具
- 实时终端面板，查看日志、发送命令
- 酒馆页面一键刷新、清除 WebView 数据

### 数据管理
- 数据导入导出（JSON 备份/恢复）
- 垃圾清理（扫描孤立文件、临时文件、缓存）
- 实例卸载（原生清理安装目录）

## 技术栈

| 层 | 技术 |
|----|------|
| 原生壳 | Kotlin + Capacitor 7 |
| 前端 UI | React 19 + Vite 7 + Tailwind CSS v4 |
| 运行时 | 嵌入式 Node.js（Termux bootstrap） |
| WebView | Android System WebView |
| 目标服务 | SillyTavern |

## 目录结构

```
SillyClient-Android/
├── app/
│   ├── build.gradle.kts          # 构建配置（versionCode/versionName）
│   └── src/main/
│       ├── java/com/sillyclient/
│       │   ├── MainActivity.kt   # 主活动（实例管理、手势、窗口）
│       │   └── plugin/
│       │       └── TarvenEnvPlugin.kt  # Capacitor 插件桥接
│       └── assets/public/         # 前端构建产物
├── web/
│   └── capacitor-ui/             # 前端代码（React + Vite）
│       ├── src/
│       │   ├── capacitor-plugin.ts  # 插件接口定义
│       │   └── routes/
│       │       └── index.tsx        # 启动器主界面
│       └── package.json
├── docs/
│   ├── ARCHITECTURE.md
│   ├── DECISIONS.md
│   └── ONBOARDING.md
├── scripts/
│   ├── build-server-source.sh
│   └── build-tarven-runtime.sh
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 构建

### 1. 构建前端

```bash
cd web/capacitor-ui
pnpm install
pnpm build
cp dist/* ../../app/src/main/assets/public/
```

### 2. 构建 APK

```bash
cd ../..
./gradlew :app:assembleDebug
# 或 release 版本
./gradlew :app:assembleRelease
```

## 架构说明

### Capacitor 插件桥接

前端通过 `@capacitor/core` 的 `registerPlugin` 注册 `TarvenEnv` 插件，原生侧由 `TarvenEnvPlugin.kt` 实现：

- `provisionAndStart`：下载/解压 SillyTavern，安装依赖，启动 Node 服务
- `enterImmersive` / `exitImmersive`：进入/退出酒馆 WebView
- `returnToTavern`（v1.4.0 新增）：返回正在运行的酒馆（不重新加载）
- `closeTavern`（v1.4.0 新增）：真正停止实例（destroyForcibly）
- `fetchReleases`：拉取 GitHub SillyTavern releases
- `scanInstances`：扫描本地已存在的实例

### 手势系统（v1.4.0 重写）

v1.4.0 将手势行为从"关闭实例"改为"返回启动器"：

- **下滑手势**：隐藏 WebView，回到启动器（实例继续运行）
- **反方向滑动**：返回正在运行的酒馆（不重新加载）
- **停止实例**：需通过卡片菜单的"停止实例"按钮

通过 `tavernRunning` 标志区分"手势退出"和"真正关闭"：

```kotlin
pushMode("launcher", tavernRunning = true)  // 手势退出，实例继续运行
pushMode("launcher", tavernRunning = false) // 真正关闭
```

前端根据 `tavernRunning` 决定是否将实例状态改为 stopped。

## v1.4.0 更新内容

- 手势退出改为返回启动器（实例继续后台运行）
- 顶部状态栏反方向滑动可返回酒馆
- 卡片菜单新增"返回酒馆"和"停止实例"按钮
- 封面图更换 bug 修复

## 相关仓库

| 仓库 | 说明 |
|------|------|
| [SillyClient](https://github.com/CAPTCHAAAAA/SillyClient) | 主仓库：项目入口、文档、Release |
| [SillyClient-Windows](https://github.com/CAPTCHAAAAA/SillyClient-Windows) | Windows 端：Electron + TypeScript |
| [SillyClient-Frontend](https://github.com/CAPTCHAAAAA/SillyClient-Frontend) | 共享前端：启动页 / 状态栏 UI 源码 |

## License

MIT
