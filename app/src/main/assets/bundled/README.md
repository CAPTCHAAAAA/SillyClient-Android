# 内置源码与依赖归档

记录日期：2026-10-06。

本目录是 APK 的直接构建输入。构建使用本仓库内的资产，不依赖下述本机来源路径。
SillyTavern 和各依赖沿用各自许可证，不因启动器采用 MIT 而改变。

## 来源与校验

内置源码：`sillytavern-release.zip`，SillyTavern `1.19.0`。

- ZIP SHA-256：`43c60a0fe9bf52ab42a063796c74a9243b1a7a1cfcedd0b3a0b094cbf9fa50c2`。
- 源码内 `package-lock.json` SHA-256：`5ee4095a82d2b326e30290480b33529553cba60d2f418bc086ed3c08d874d888`。

源码目录中的依赖资产：

```text
dependency-5ee4095a82d2b326e30290480b33529553cba60d2f418bc086ed3c08d874d888-0e4996b0408c11518d0a297f660e6c12e4bd44ed4f141a5d9ffa63d214ba6443.tar.gz
```

- 本机原始 tar 来源：`Local/artifacts/android-fixes-20261005/assets/converted-5ee4.tar`。
- 原始 tar 大小：`310681600` 字节。
- 原始 tar SHA-256：`0e4996b0408c11518d0a297f660e6c12e4bd44ed4f141a5d9ffa63d214ba6443`。
- gzip 文件大小：`87878253` 字节。
- gzip 文件 SHA-256：`bf7bb9f612ee46e5771df04d880f7e67e5d69cb204743c76063c265969500fb1`。

该资产是上述 tar 的 gzip 封装。文件名第一段摘要选择匹配的锁文件，第二段校验
**解压后的 tar 内容**，不是 gzip 文件摘要。更换内置源码或锁文件时必须同时核对
归档匹配关系，不能仅重命名归档使其命中另一份锁文件。

## 最终 APK 条目

本轮 Gradle 资产合并会展开源码 gzip；合并后的 assets 及 APK 内文件名为：

```text
assets/bundled/dependency-5ee4095a82d2b326e30290480b33529553cba60d2f418bc086ed3c08d874d888-0e4996b0408c11518d0a297f660e6c12e4bd44ed4f141a5d9ffa63d214ba6443.tar
```

- APK ZIP 条目解压长度：`310681600` 字节。
- APK ZIP 条目压缩长度：`87878235` 字节。
- 条目解压后的 tar SHA-256：`0e4996b0408c11518d0a297f660e6c12e4bd44ed4f141a5d9ffa63d214ba6443`。

因此不能把源码 `.tar.gz` 的文件名、大小或摘要当作 APK 内条目的验收结果。
最终 APK 必须核对实际 `.tar` 条目解压后的长度与内容摘要；压缩层字节不是 tar 摘要的对象。

## 导入与恢复

`BundledDependencyArchives` 兼容 `.tar` 与 `.tar.gz`：前者直接读取，后者在后台
流式解开 gzip；两条路径均限制输出量不超过 1 GiB，边写入临时文件边计算 tar
SHA-256。本轮 APK 走 `.tar` 导入路径，校验通过后以 `<锁文件摘要>-<tar摘要>.tar`
原子发布到应用私有 `dependency-archives/`。无效文件名不会作为依赖导入，失败不发布半成品。

`DependencyArchive` 恢复前再次校验 tar，通过同卷依赖恢复事务向每个实例写入独立
`node_modules`。归档只是可复用的安装缓存，不作为运行时共享依赖树，也不通过
`NODE_PATH`、符号链接或借用其他实例目录提供依赖。

## 验证边界

`BundledDependencyArchivesTest.shippedSourceAndCompressedDependenciesRestoreOffline`
使用仓库内真实源码 ZIP 和 gzip 依赖，从空缓存完成导入、锁文件匹配及本地依赖恢复，
验证清单所列直接依赖和 YAML 包存在。该主机测试已通过，Release 构建已成功退出。
该测试读取的是源码 gzip 资产，不替代最终 APK 内 `.tar` 条目的长度和摘要核验。

这不是 Android Bionic 执行、FUSE 性能、实际酒馆启动或真机安装验收。离线恢复仅覆盖
内置源码和匹配依赖；其他版本、用户修改的清单或锁文件可能需要官方 npm 网络，
扩展下载及模型服务请求仍按各自配置进行。
