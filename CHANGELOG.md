# Changelog

---

## v1.1.0 (2026-07-06)

### npm install 修复

- **打包 npm 进 APK**：从 node-v24.17.0 提取 `lib/node_modules/npm`，压缩成 `rootfs-usr.zip` 放入 assets，解决 npm 不可用问题
- **修复 zip 路径分隔符**：Windows `Compress-Archive` 使用反斜杠导致 Android 解压失败，改用 7z 打包
- **修复 npm 缓存路径**：node 编译时 hardcode 了 termux 路径，通过 `TMPDIR` 环境变量覆盖 `os.tmpdir()`
- **npm 11+ 兼容**：移除废弃的 `--tmp` 参数，改用 `--omit=dev` 和 `TMPDIR`
- **淘宝镜像加速**：添加 `--registry https://registry.npmmirror.com`
- **自动重试**：npm install 失败后自动重试 3 次，每次间隔 3 秒

### 服务器启动修复

- **端口冲突检测**：启动前杀掉旧 server 进程，等待端口释放
- **pushReady 缺失修复**：`pollUntilReady` 成功连接后未调用 `pushReady(true)`，导致前端永远 waiting
- **进程退出检测**：轮询时检查 node 进程是否存活，立即报错而非等超时
- **堆内存增加**：`NODE_OPTIONS=--max-old-space-size=2048` 给 webpack 编译更多内存
- **超时延长**：poll 从 120s 增加到 180s（webpack 编译需 24s）
- **残留清理**：检测到 `server.js` 存在但 `node_modules` 不存在时自动清理残缺目录

### UI 改进

- **字体**：移除 Montserrat/Nunito（AI 痕迹明显的 Google Fonts），改用系统字体栈
- **液态玻璃**：所有二级三级弹出菜单统一使用 `.liquid-glass` 液态玻璃质感
- **遮罩虚化减轻**：`backdrop-blur-sm` → `backdrop-blur-[2px]`，背景更清晰
- **配色跟随主题**：亮色模式玫红底色，暗色模式蓝紫底色（#1a1625）
- **日志符号**：原生层 `✓ ✗ ⚠ →` 替换为 `[OK] [ERR] [WARN] >`
- **移除冗余注释**：新建实例面板的小字说明全部移除

---

## v1.0.0 (2026-06-30)

- Capacitor 7 插件化架构
- 三层架构：控制台 webUI + 插件接口契约 + 平台实现
- 双 WebView 分工：Capacitor webUI + 原生 WebView 承载酒馆
- 沉浸式阅读环境：变色龙顶框、刘海/挖孔适配、SHORT_EDGES
- 多实例管理：本地/远程实例、独立端口、独立配置
- GitHub Releases 版本集成：API 获取版本列表 + 代理镜像加速
- 本地 zip 导入：完全离线安装
- 终端面板：shell 命令 + 实时日志
- 垃圾清理
