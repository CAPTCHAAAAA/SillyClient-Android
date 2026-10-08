package com.sillyclient.runtime

import java.io.File

object MigrationPolicy {
    fun validate(sourcePath: String, target: File, mode: String, requestedTarget: String?) {
        require(mode == "copy") { "Android 原地接管尚未提供可持久化运行路径；请选择复制迁移，原目录不会更改" }
        require(requestedTarget.isNullOrBlank() || File(requestedTarget).canonicalFile == target.canonicalFile) {
            "迁移目标与已校验的实例路径不一致，原文件未更改"
        }
        require(!target.exists() || target.listFiles()?.isEmpty() == true) {
            "迁移目标包含已有文件，请选择新实例；已有数据未更改"
        }
        if (!sourcePath.startsWith("content://")) {
            val source = File(sourcePath).canonicalFile
            require(!source.toPath().startsWith(target.canonicalFile.toPath()) &&
                !target.canonicalFile.toPath().startsWith(source.toPath())) {
                "迁移来源与目标不能互相包含"
            }
        }
    }

    fun verify(
        target: File,
        installDependencies: () -> Boolean,
        dependenciesComplete: (File) -> Boolean = { File(it, "node_modules").isDirectory }
    ): Boolean =
        File(target, "server.js").isFile && installDependencies() && dependenciesComplete(target)
}
