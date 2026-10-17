# Android v1.13.0 第八轮 - 根因诊断与系统性修复

## 问题分类与根因假设

### 1. 删除功能失效
**现象**: 软件内删除实例后，本地文件没有同步删除，卡片不消失

**可能根因**:
a) 存储权限检查在某些情况下失败但没有正确反馈
b) NativeTreeRemoval 在某些文件系统/权限配置下静默失败
c) operations.commit() 提前注销实例，导致失败时状态不一致
d) 前端错误处理逻辑问题（catch 后没有回滚 UI 状态）

**诊断计划**:
1. 检查 MainActivity.uninstallInstance 的异常处理链路
2. 检查 TarvenEnvPlugin.uninstallInstance 的权限检查逻辑
3. 检查前端 confirmDeleteInstance 的状态管理

**修复方案**:
- 确保删除失败时前端不移除卡片
- 添加更详细的错误日志和用户提示
- 考虑将 operations.commit() 移到物理删除成功之后

### 2. 创建/启动发热
**现象**: "烫的要死"、"手机发热都快关机了"

**已做措施**:
- .nomedia 免疫媒体扫描（第六轮）
- 流光去滤镜化（第七轮）
- offscreenPreRaster 关闭（第五轮）
- 后台依赖归档节流（第五轮）

**可能剩余根因**:
a) 依赖安装过程（node_modules 解压/复制）仍然很慢
b) WebView 初始化时的资源加载
c) 前端大量 DOM 操作和重排
d) 文件系统频繁 fsync

**诊断计划**:
1. 在创建/启动流程中添加性能埋点，记录各阶段耗时
2. 对比 Termux/SillyDroid 的实现差异
3. 使用 adb shell top 监控 CPU 占用

**修复方案**:
- 优化依赖解压逻辑（考虑流式解压而非全量缓存）
- 延迟非关键资源的加载
- 减少文件系统同步操作

### 3. 前端卡顿（保持视觉效果）
**现象**: "明明大型游戏都能跑，TG 动画流畅，我们却卡"

**已发现**: 26 处 backdrop-filter/blur

**优化方案（不降级视觉）**:
a) 动画时禁用毛玻璃，静止时恢复
b) 使用 will-change 和 transform: translateZ(0) 强制 GPU 加速
c) 使用 contain: layout style paint 隔离重绘
d) 减小模糊半径但增加饱和度/明度补偿

### 4. 键盘避让抽搐
**已做**: 移除动画起点 JS 派发（第七轮）

**进一步优化**:
- 使用 CSS contain 隔离键盘区域的重排
- 确保 WebView 渲染与原生动画完全同步
