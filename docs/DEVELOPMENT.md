# 开发与调试

## 首次准备

```bash
cd web/capacitor-ui
pnpm install --frozen-lockfile
pnpm run typecheck
pnpm run build:android
cd ../..
./gradlew :app:assembleDebug
```

仓库中的 Gradle Wrapper 是唯一 Gradle 入口。不要把 `node_modules`、`.gradle`、`dist`、APK 或设备截图提交到仓库。

## 常用检查

```bash
pnpm --dir web/capacitor-ui run typecheck
pnpm --dir web/capacitor-ui run build
./gradlew testDebugUnitTest :app:assembleDebug
./gradlew lintDebug
git diff --check
```

## 实机调试

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.sillyclient -c android.intent.category.LAUNCHER 1
adb logcat -s SillyClient:* AndroidRuntime:E
adb shell run-as com.sillyclient cat files/tarven/logs/server.log
```

使用 `-r` 会保留已有实例。验证首次安装或失败清理时，应先在测试设备上卸载应用或明确清理测试数据。

## 改动位置

| 任务 | 位置 |
| --- | --- |
| 控制台界面与主题 | `web/capacitor-ui/src/` |
| 跨平台接口声明 | `web/capacitor-ui/src/capacitor-plugin.ts` |
| Android 接口实现 | `app/src/main/java/com/sillyclient/plugin/` |
| 下载、解压和 Node.js 进程 | `app/src/main/java/com/sillyclient/runtime/` |
| WebView、沉浸式和系统栏 | `MainActivity.kt`、`ui/TopScrimBar.kt` |

涉及接口载荷、实例状态或前端生成物的改动不能只验证一端。至少运行前端构建和 Android 编译；准备发布时再安装真实 APK 完成创建、返回、停止和删除流程。
