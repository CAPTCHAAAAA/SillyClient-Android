# SillyClient 工作树规约与接手中枢：Android 主力加固与设置/导出重构 (Release Hardening Android)

> **[LTS 核心工作树]** 本文档为 `.worktrees/release-hardening-android/` 的自包含边界与接手中枢。
> **所有后续接手本工作树的 AI Agent 必须完整研读本文档后再行操作！**

---

## 一、 工作树物理拓扑与 Git 状态 (Worktree Topology & Git State)

- **工作树物理绝对路径**: `D:\BACKUP\Project\SillyClient\.worktrees\release-hardening-android`
- **所属产品仓库**: `SillyClient_Android` (GitHub: `CAPTCHAAAAA/SillyClient-Android`)
- **当前本地分支**: `lts/android-release-hardening`
- **跟踪远端分支**: `origin/feature/release-hardening`
- **基线提交**: `9912e36` (`refactor(android-backend): 剥离精修纯净后端骨架，引入强类型领域模型并清零编译器告警`)
- **工作区定位**: Android 平台主力发布加固、SAF 存储访问框架对接、设置面板精简与实例导出重构的唯一主力工作树。

---

## 二、 当前未提交现场明细 (Current Uncommitted WIP Inventory)

用户明确指令：**“前端的修改仍保留在工作树 并指明这是安卓前端的工作树”**。
以下修改目前保留在工作树中（未执行 Git 提交），所有状态均通过全量编译与单测验证：

| 变更文件 | 职责模块 | 核心改动摘要 |
| :--- | :--- | :--- |
| `app/src/main/java/com/sillyclient/runtime/InstanceExport.kt` | 原生运行时 (新增) | 独立自治领域对象：提供 `exportToStream` 与 `exportToFile`，纯净流式压缩，彻底拔除旧代码中硬编码的私设默认路径 `Download/SillyClient-导出` |
| `app/src/test/java/com/sillyclient/runtime/InstanceExportTest.kt` | JVM 测试 (新增) | 针对 `InstanceExport` 的全量自动化测试，包含流式导出、目录导出、显式目标文件导出、损坏实例断言及解压内容逐条校验 |
| `app/src/main/java/com/sillyclient/MainActivity.kt` | 原生宿主 (修改) | 将实例压缩打包逻辑委托给 `InstanceExport`，彻底消除所有私设导出路径硬编码 |
| `app/src/main/java/com/sillyclient/plugin/TarvenEnvPlugin.kt` | Capacitor 插件 (修改) | `exportInstance`：未传 `targetPath` 时调起 Android 系统级 SAF 另存为选择器（`ACTION_CREATE_DOCUMENT`，类型 `application/zip`），让用户自主选择保存目录与文件名；流式写入所选 URI；取消时静默复位 |
| `web/capacitor-ui/src/capacitor-plugin.ts` | 前端插件契约 (修改) | `exportInstance` 接口强类型扩展，接收可选 `targetPath`，回传 `{ path, bytes, uri? }` |
| `web/capacitor-ui/src/components/modals/AppSettingsDrawer.tsx` | 设置面板 (修改) | 遵照用户要求纯净删除“选择默认实例存储路径”与“实例备份”等鸡肋功能；Tab 纯净收拢为【通用】与【维护】两项；将【浏览数据】迁入【维护】面板 |
| `web/capacitor-ui/src/components/modals/ManageInstanceModal.tsx` | 实例管理 (修改) | 废除硬编码的“打包到 下载/SillyClient-导出”提示，更新为“选择保存位置并导出完整实例压缩包（ZIP）”，点击导出调起系统文件保存器，取消时优雅复位，完成展示体积 (MB) |
| `web/capacitor-ui/src/dev/native-preview.ts` | 前端走查桩 (修改) | 剔除假数据中的硬编码路径，对齐真实运行时契约 |
| `app/src/main/assets/public/` | Android 资产 (同步) | 前端最新 Vite 生产打包产物，已通过官方同步脚本同步至原生资源目录 |

---

## 三、 核心架构契约与红线高压线 (Invariants & Hard Constraints)

### 1. 铁律：坚决消灭一切“软件默认路径”
- **创建实例**：第一步必须是用户选择存放文件夹（必选项），无任何默认路径。
- **导出实例**：点击导出直接调起系统 SAF 保存选择器，由用户自主指定导出位置，绝对禁止私设 `Download/SillyClient-导出` 这种流氓行为。
- **设置面板**：彻底废弃任何“默认实例存储路径”的概念，实例根由用户在创建向导中指定。

### 2. 铁律：绝对禁止真机操控
- 用户多次严正声明：**“不许打开 App、点击 UI 或截图操作真实设备”**。
- 允许的操作：编译构建、`adb install -r` 覆盖安装、`adb shell dumpsys` 只读状态查询、反汇编字节码比对。
- 严禁的操作：`adb shell am start` 自动启动、`adb shell input tap` 模拟点击、`screencap` 截图走查。所有 UI 行为验证必须交由用户在真实手机上手动体验。

### 3. 铁律：前端修改驻留本工作树，未经授权禁止合入主仓
- 用户明确指示：“前端的修改仍保留在工作树 并指明这是安卓前端的工作树”。
- 主仓库 `SillyClient_Main` 与 Android 正式仓库主干保持干净，禁止擅自提交或合入未经验收的前端改动。

### 4. 铁律：钟表级清洗，拒绝打补丁堆砌屎山
- 代码重构必须按强类型、自治领域模型分层拆分（如 `InstanceExport`、`InstanceRelocation`、`InstanceDataImport`）。
- 保持 Kotlin 编译器 0 警告、0 错误；全量测试网络 100% 绿灯。

---

## 四、 后续 Agent 开发与验证标准流程 (Execution SOP)

### 1. 开工前快速自检
```powershell
cd D:\BACKUP\Project\SillyClient\.worktrees\release-hardening-android

# 检查当前分支与未提交现场
git status --short

# 运行全量 JVM 单元测试（当前为 24 actionable tasks，全绿通过）
.\gradlew.bat testDebugUnitTest
```

### 2. 前端修改与构建同步
修改 `web/capacitor-ui/src/` 后，必须执行标准构建与同步：
```powershell
cd D:\BACKUP\Project\SillyClient\.worktrees\release-hardening-android\web\capacitor-ui

# 1. 前端类型检查与 Vite 构建（必须 0 TS 错误）
pnpm run build

# 2. 同步静态产物至 Android 原生 assets 目录
pnpm run sync:android
```

### 3. 生产 Release APK 构建
```powershell
cd D:\BACKUP\Project\SillyClient\.worktrees\release-hardening-android

# 构建 Release 签名 APK（使用现有签名配置，启用 lintVitalRelease 检查）
.\gradlew.bat :app:assembleRelease --no-daemon
```

### 4. 当前最新构建产物指纹
- **APK 路径**: `app/build/outputs/apk/release/app-release.apk`
- **文件体积**: `198,155,160` 字节（约 188.97 MB）
- **SHA-256 校验**: `037348D74AD496FE6EE093799857EBD7BF0DA9F589EDF873BF843E48CF31DAFF`
- **版本号**: `versionCode = 70` / `versionName = "1.12.0"`

---

## 五、 本树推进台账 (Activity Log)

| 日期 | 操作人 | 改动摘要与内部验证结果 | 状态 |
| :--- | :--- | :--- | :---: |
| 2026-10-08 | Agent (77d088b) | Android 1.12.0：压缩包数据无损导入 + 迁移链路下线，436 项单测全绿，产出 APK a9e27af9 | 已交付 |
| 2026-10-09 | Agent (9912e36) | 纯净后端骨架重构：强类型领域模型取代元组，死代码剥离，清零编译器告警并推送远端 | 已推送 |
| 2026-10-09 | Agent | 设置面板纯净精简：删除“默认实例存储路径”与“实例备份”，将“浏览数据”迁入“维护”面板 | 工作树 WIP |
| 2026-10-09 | Agent | 实例导出（ZIP）纯净重构：彻底消灭硬编码路径，新增 `InstanceExport` 领域服务与 `InstanceExportTest`，接入 SAF 另存为选择器，全量单测通过，产出最新 Release APK (SHA-256: `037348D7...`) | 工作树 WIP |
