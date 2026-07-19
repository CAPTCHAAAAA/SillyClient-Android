# SillyClient Android

SillyClient 的 Android 客户端，同时保存 Android 与 Windows 共用的 React 控制台源码。

应用自带 arm64 Bionic Node.js。用户创建实例后，平台层下载并安装指定的 SillyTavern 版本，完成端口检查后再把实例标记为可用。控制台运行在 Capacitor WebView 中，SillyTavern 由独立的原生 WebView 承载。

## 环境

- Android Studio 或 Android SDK 37
- JDK 17
- Node.js 22
- pnpm 11.9.0
- Android 8.0+ arm64 设备或模拟器

## 构建

先构建并同步控制台：

```bash
cd web/capacitor-ui
pnpm install --frozen-lockfile
pnpm run build:android
cd ../..
```

再构建 APK：

```bash
./gradlew testDebugUnitTest :app:assembleDebug
```

Windows 上使用 `gradlew.bat`。调试包位于 `app/build/outputs/apk/debug/`。

实机安装与启动：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.sillyclient -c android.intent.category.LAUNCHER 1
```

## 目录

| 路径 | 内容 |
| --- | --- |
| `web/capacitor-ui/` | 共享 React 控制台的唯一源码 |
| `app/src/main/java/com/sillyclient/` | Kotlin 宿主、运行时和平台接口 |
| `app/src/main/assets/public/` | 已同步的控制台构建产物 |
| `app/src/main/assets/bootstrap/` | Android 运行时所需的脚本与 rootfs |
| `app/src/main/jniLibs/arm64-v8a/` | Bionic Node.js 与 C++ 运行库 |
| `docs/` | Android 架构和开发说明 |

`TarvenEnv`、`TarvenProcessRunner` 等名称是现有桥接协议中的内部标识，不是产品名。对外名称、应用 ID 和 Gradle 工程名统一使用 SillyClient。

继续阅读：[架构](./docs/ARCHITECTURE.md) · [开发与调试](./docs/DEVELOPMENT.md) · [主仓库](https://github.com/CAPTCHAAAAA/SillyClient)

## License

[MIT](./LICENSE)
