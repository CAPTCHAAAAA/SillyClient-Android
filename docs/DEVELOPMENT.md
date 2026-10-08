# 开发与调试

## 当前开发状态

2026-10-06 的修复仍在 `feature/release-hardening` 隔离工作树，目标版本为
`1.10.0 / versionCode 32`。源码 gzip 依赖资产的真实离线恢复回归和 Release 构建已完成，
设备安装与实际运行验收另行记录；不能由构建结果推断已装机或已解决真机问题。
下文 `20`、`19` 以及灰屏修复包的结果都是历史检查点，不代表本轮验收。

当前行为及验证重点：

- 正常运行、依赖检查和迁移只使用实例自己的 `node_modules`，不再接入共享依赖树。
- 依赖归档只用于安装加速：摘要校验后恢复到同卷 `.sillyclient-dependency-restore`，
  完整校验后原子换入；失败、取消及强停后按事务状态恢复，不改变实例源码与用户数据。
- `.sillyclient-dependencies-pending` 尚在时必须完成修复，不能仅凭部分包存在就跳过。
- npm 仅配置官方源；已有部分依赖或已知锁文件不匹配时使用 `install`，避免反复通过
  `ci` 删除已有进度。`ci` 与必要回退共用有界总预算，每十五秒检查并输出等待诊断。
- 删除先移除内容，最后删除身份并解除登记。部分删除后重启仍保留扫描和重试能力；
  取消须确认原生进程退出，系统 force-stop 后不承诺后台继续删除。
- 已删除仅凭暂存目录前缀和根目录 mtime 自动清扫的机制；不在下次创建或迁移时
  自动删除其他遗留目录。

当前 APK 源资产 `app/src/main/assets/bundled/` 已补齐与内置 SillyTavern `1.19.0`
锁文件匹配的 `dependency-*.tar.gz`，不再是只有源码 ZIP。当前 Gradle 资产合并后
及最终 APK 内实际文件名为 `dependency-*.tar`，由 APK ZIP 层压缩；条目解压长度
为 `310681600` 字节，ZIP 压缩长度为 `87878235` 字节。运行时兼容两种文件名，
本轮 APK 直接读取 tar，源码 gzip 则先展开；两条路径都先核对 tar 内容摘要，
再以不带 `dependency-` 前缀的规范缓存名原子发布。恢复时再次校验归档，最终
每个实例拥有自己的本地 `node_modules`。资产来源和摘要见
[内置资源说明](../app/src/main/assets/bundled/README.md)。

APK 验收应打开最终 `assets/bundled/dependency-*.tar` 条目，核对解压后的长度及
SHA-256 与文件名第二段一致，不能仅凭源码目录存在 `.tar.gz` 就认为该文件原样入包。

`BundledDependencyArchivesTest.shippedSourceAndCompressedDependenciesRestoreOffline`
实际读取仓库内源码 ZIP 的清单与锁文件，从空缓存导入完整 gzip 依赖资产，校验
匹配锁文件及依赖恢复结果，包含 `yaml/package.json` 断言，不发起 npm 网络请求。
本机 JUnit 回执中该真实资产测试与规范文件名测试均通过；它不执行 Bionic Node、
酒馆启动、插件或 Android FUSE 写入，不是物理设备验收或移动端速度测试。
离线范围限于内置源码及匹配依赖；其他版本、修改过的清单/锁文件仍可能需要 npm
网络，扩展和模型服务请求不在此范围。

定向回归包含 `BundledDependencyArchivesTest`、`DependencyRestoreTransactionTest`、`DependencyArchiveTest`、
`DependencyInstallerTest`、`InstanceInstallerTest`、`InstanceRemovalTest` 和
`NativeTreeRemovalTest`。重点覆盖归档中断恢复、旧依赖保护、pending 修复、npm 命令
选择与统一超时，以及实际部分删除后取消、注册表重载和再次删除。

## 历史验证：2026-10-05

以下为 `1.10.0 / versionCode 20` 检查点，保留原测试事实，不作为当前版本结论。

`BundledRuntimeTest` 验证共享环境异步准备、跨 Activity 串行、失败重试和取消隔离。
`RuntimeConfigurationTest` 同进程执行配置与服务入口；`DependencyInstallerTest`
当时验证实例独立依赖、共享缓存、源切换与超时；当前版本已移除源轮换。
`SourceDownloaderTest`、
`TavernReleaseCatalogTest` 和 `SourceArchiveTest` 分别负责有界下载、版本兜底、
单次解压及路径/CRC 安全；`InstanceInstallerTest` 验证目标同文件系统发布与回滚。
`InstanceRemovalTest` 验证身份标记最后删除、失败可重试和目录替换防护。
`storage/` 测试只读 SAF 文档策略，禁止凭据、扩展代码及符号链接导出。

使用下文固定 Node、YAML 与扩展 ZIP 夹具执行
`:app:testDebugUnitTest :app:assembleRelease --offline --no-daemon` 已通过：
265 项中 257 通过，8 项因 Windows 符号链接权限跳过，0 失败；
渲染器非视觉浏览器回归 24/24 通过。不能把权限跳过视为通过。
配置 9 项和扩展 14 项真实夹具全部执行，前端 typecheck、生产构建与同步通过。

APK 沿用历史升级证书，关闭调试，28 项前端资产与构建逐字节一致。
授权设备覆盖安装成功，拉回的 APK 哈希一致，首次安装时间未变；未启动应用或实例，
未清理数据。完整证据在工作区 `Local/evidence/android-runtime-20261005/README.md`，
安装包在 `Local/artifacts/android-runtime-20261005/1.10.0/`。
不宣称灰屏、Bionic 端到端、手机无 VPN 网络或实际启动延迟通过真机验收。
镜像探测使用本机既有代理；旧实例同 ID 跨卷迁出仍未实现，不自动移动用户目录。

## 首次准备

```bash
cd web/capacitor-ui
pnpm install --frozen-lockfile
pnpm run typecheck
pnpm run build
cd ../..
node scripts/sync-frontend.mjs
./gradlew :app:assembleDebug
```

仓库中的 Gradle Wrapper 是唯一 Gradle 入口。不要把 `node_modules`、`.gradle`、`dist`、APK 或设备截图提交到仓库。

## 常用检查

```bash
pnpm --dir web/capacitor-ui run typecheck
pnpm --dir web/capacitor-ui run build
node scripts/sync-frontend.mjs
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
| 控制台界面与主题 | 本仓库 `web/capacitor-ui/src/` |
| 跨平台接口声明 | 本仓库 `web/capacitor-ui/src/capacitor-plugin.ts` |
| Android 接口实现 | `app/src/main/java/com/sillyclient/plugin/` |
| 下载、解压和 Node.js 进程 | `app/src/main/java/com/sillyclient/runtime/` |
| WebView、沉浸式和系统栏 | `MainActivity.kt`、`ui/TopScrimBar.kt` |
| 系统浏览器外链、顶层导航与新窗口 | `navigation/`、`MainActivity.kt`、`plugin/TarvenEnvPlugin.kt` |

涉及接口载荷、实例状态或前端生成物的改动不能只验证一端。至少运行前端构建和 Android 编译；准备发布时再安装真实 APK 完成创建、返回、停止和删除流程。

## 后端回归

`RuntimeHardeningTest` 使用合成文件、受控进程和任务代际验证清理保护、安装暂存、
迁移失败、取消后的旧回调、命令超时、元数据缓存及日志边界，不接触真实实例。
单独执行 `:app:testDebugUnitTest --offline` 会编译测试所需的 Kotlin，但不生成 APK；
前端审批闸门仍禁止在预览批准前运行正式前端构建、资产同步、`assemble` 或安装。
Windows 无创建符号链接权限时对应测试跳过，必须在允许该能力的 CI 或设备补验，
不能把跳过宣称为已验证。

`PreinstalledExtensionInstallerTest` 覆盖选择/来源白名单、归档完整性、编译入口、兼容性、
已有文件与禁用设置保留、取消和仅删除未变化自有文件的回滚。设置
`SILLYCLIENT_EXTENSION_AUDIT_DIR` 后还会使用目录内的四个固定版本 ZIP 完成离线安装验证；
缓存是测试夹具，不得提交到 MIT 产品仓库。

`RuntimeConfigurationTest` 使用固定运行时 Node 和离线 `yaml` 副本，在合成目录实际执行
配置脚本，验证注释、未知键、安全配置和扩展设置保留，以及自定义数据根和非法 YAML 拒绝。
未提供夹具时这些测试明确跳过。工作区可使用：

```powershell
$env:SILLYCLIENT_TEST_TEMP_DIR = 'D:\BACKUP\Project\SillyClient\Local\临时\preinstalled-extension-tests'
$env:SILLYCLIENT_EXTENSION_AUDIT_DIR = 'D:\BACKUP\Project\SillyClient\Local\cache\preinstalled-extension-audit'
$env:SILLYCLIENT_CONFIGURATION_NODE = 'D:\BACKUP\Project\SillyClient\SillyClient_Windows\runtime\node\node.exe'
$env:SILLYCLIENT_CONFIGURATION_YAML = 'D:\BACKUP\Project\SillyClient\Local\cache\windows-data-migration\runtime-deps\node_modules\yaml'
.\gradlew.bat :app:testDebugUnitTest --offline --no-daemon
```

此验证不运行扩展 JavaScript、不操作真实实例，也不代表 Android Bionic 或设备验收。

## 外链与导航回归

`ExternalNavigationPolicyTest` 验证 HTTP(S) 白名单、内嵌凭据与空 user-info 拒绝、
IPv6 和有效端口的同源分类、子框架不接管，以及同源 blob 下载与异源 blob 阻止。
保持完整夹具环境后运行全部 JVM 回归，或单独定位导航策略：

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --no-daemon
.\gradlew.bat :app:testDebugUnitTest --tests 'com.sillyclient.navigation.ExternalNavigationPolicyTest' --offline --no-daemon
```

这些命令不生成 APK，也不会唤起浏览器。实际 `onCreateWindow`/下载回调顺序、
延迟的 `about:blank` 新窗口和系统浏览器选择，须在预览批准后使用真实 WebView 补验；
不能将纯策略结果当作宿主端到端通过。`shouldOverrideUrlLoading` 不覆盖任意 POST
导航或应用主动 `loadUrl`，显式入口另行校验；不通过网络拦截或静态网关扩大覆盖范围。
本轮仍只在 `feature/release-hardening` 隔离工作树实现，未提交、推送或发布。
用户批准预览及构建安装后，已逐文件同步审核过的 Main 前端改动，保留 Android
`build:android` / `sync:android` 脚本，并使用 Android 自身的生产构建同步资产。
生产包未包含 `nativePreview` 合成夹具；前端与原生扩展清单一致，
`sillyclient-build.json` 摘要和 APK 内全部 28 项前端资产逐字节校验通过。

完整 `:app:testDebugUnitTest :app:assembleRelease --offline --no-daemon --rerun-tasks`
已执行：71 项测试，70 项通过，1 项因宿主符号链接权限跳过，0 失败。
Release APK 保留 `2.0.1` / `versionCode 19`、原有签名方式和关闭 WebView 调试的设置。
测试包归档在工作区
`Local/artifacts/release-hardening-test-20261003/Android/`，不覆盖已发布包。
已在授权设备 `af72222f` 执行 `adb install -r`，结果为成功；设备安装的 `base.apk`
SHA-256 与归档 APK 一致，首次安装时间未变。安装后已有一次冷启动返回成功。

用户最终要求不做 Windows/Android 真机验收，本轮因此不再操作设备。
未执行实际创建、迁移、启动酒馆或扩展安装；主题、分页、向导交互、系统浏览器、
弹窗/下载次序及 Bionic 扩展运行兼容性不属于本轮已验证结果。
合成测试、构建和安装证据统一保存在工作区
`Local/evidence/release-hardening-20261003/output/android-approved-*`。

## 历史验证：首次返回酒馆灰屏急修

2026-10-04，用户反馈最新本地测试包进入酒馆后停留在灰色页面。
已确认的一条路径是创建流程以 `enter=false` 完成后台启动后，首次从运行态卡片
或手势返回酒馆；旧 `returnToTavern` 直接显示尚未加载页面的 WebView。
`TavernPageSession` 现在统一管理首次、空页、实例切换和端口切换时的加载决策；
返回同一实例的已加载同源页面不主动重载。插件只在原生返回成功后 resolve，
未就绪或不可进入时 reject，关闭实例会重置页面会话。
未改动静态网关、运行端口策略、前端资产或 Release WebView 调试设置。

使用完整 YAML、固定 Node.js 和四个真实扩展 ZIP 夹具重新执行
`:app:testDebugUnitTest :app:assembleRelease --offline --no-daemon --rerun-tasks`：
72 个构建任务实际执行，79 项 JVM 测试中 78 项通过，
1 项既有宿主符号链接权限跳过，0 失败；新增加载决策回归 8 项全部通过。
配置 5 项、扩展 14 项均执行且无跳过。此结果不代表 Android Bionic 或实际酒馆页面验收。

修复包单独归档为
`Local/artifacts/release-hardening-test-20261003/Android/SillyClient-Android-v2.0.1-webview-fix.apk`，
保留旧测试包，版本仍为 `2.0.1 / 19`。SHA-256 为
`da2d37db314504cfcd145818de06f5ee0d040c59b06a71a257ad733e92c52653`。
APK v2 签名验证通过，包内 28 项前端资产与平台构建逐字节一致；
原生扩展清单与前端清单一致，DEX 中已确认存在新 `TavernPageSession` 类定义。
证据位于 `Local/evidence/release-hardening-20261003/output/android-webview-fix-*`。
主任务于 2026-10-04 01:00（北京时间）在 `af72222f` 串行执行
`adb install -r`，结果为成功；安装后 `base.apk` SHA-256 与上述归档包一致。
首次安装时间仍为 `2026-08-23 05:17:20`，更新时间为 `2026-10-04 01:00:15`。
本轮仅确认保留数据覆盖安装及包回执，未做真机 UI 验收、启动应用或酒馆实例、
清理应用数据或开启 WebView 调试，未提交、推送或发布。
