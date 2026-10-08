package com.sillyclient.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 把完整压缩包（应用导出的实例 ZIP、SillyTavern 自带备份或其它环境的备份）里的
 * **用户数据**无损导入到一个已经创建好的实例目录，覆盖同名文件。
 *
 * 只导入用户数据，程序与依赖永不覆盖，因此跨端/跨版本导入不会破坏实例：
 * - 导入：`data` 目录（除其中的 `_webpack` 构建缓存）、`public/scripts/extensions/third-party`、
 *   `plugins`；可选的 `secrets.json` 与 `config.yaml`（默认关闭）。
 * - 永不导入：`node_modules`、`package.json`、`package-lock.json`、其余 `public` 内容
 *   （按版本编译）、`.git`、`.sc-identity`、依赖标记与 `.sillyclient-*` 临时文件。
 *   被排除的条目不会解压，这是导入速度的来源。
 *
 * 安全与一致性：
 * - 复用 [SourceArchive] 的路径校验（拒绝绝对路径、父目录跳转、反斜杠、控制字符）、条目限额、
 *   压缩方法校验与 CRC 校验；中央目录预检在任何写入之前完成。
 * - 每个文件先写入同目录的临时文件，校验通过后再原子替换目标文件：中途取消不会产生
 *   半个文件，已被替换的文件保持完整。
 * - 目标路径必须落在实例目录内（canonical 校验），实例目录本身不能是符号链接。
 */
object InstanceDataImport {
    private const val EXTENSIONS_ROOT = "public/scripts/extensions/third-party"
    private const val WEBPACK_CACHE = "data/_webpack"
    private val OPTIONAL_FILES = listOf("secrets.json", "config.yaml", "config.yml")
    private const val TMP_PREFIX = ".sc-import-tmp-"

    data class Summary(
        val importEntries: Int,
        val importBytes: Long,
        val skippedEntries: Int,
        val skippedBytes: Long,
        val hasSecrets: Boolean,
        val hasConfig: Boolean,
        val wrapperPrefix: String
    ) {
        val importable: Boolean get() = importEntries > 0
    }

    data class Outcome(val imported: Int, val bytes: Long, val skippedEntries: Int)

    private data class Plan(
        val summary: Summary,
        val included: List<SourceArchive.Item>
    )

    /**
     * 只读中央目录，不写任何文件；用于导入前告知用户会覆盖什么、忽略什么。
     * 按默认口径统计（不含 secrets.json / config.yaml），另用 hasSecrets/hasConfig 提示存在性。
     */
    fun inspect(archive: File): Summary = inspectBounded(archive, SourceArchive.Limits())

    internal fun inspectBounded(archive: File, limits: SourceArchive.Limits): Summary =
        plan(archive, limits, includeOptional = false) { }.summary

    /**
     * 执行导入。[includeOptional] 为 true 时才导入 `secrets.json`/`config.yaml`。
     * 实例必须处于停止状态（由调用方的维护入口保证）。
     */
    fun import(
        archive: File,
        instanceDir: File,
        includeOptional: Boolean,
        ensureActive: () -> Unit,
        onProgress: (Int, Long) -> Unit
    ): Outcome = importBounded(archive, instanceDir, includeOptional, ensureActive, onProgress,
        SourceArchive.Limits())

    internal fun importBounded(
        archive: File,
        instanceDir: File,
        includeOptional: Boolean,
        ensureActive: () -> Unit,
        onProgress: (Int, Long) -> Unit,
        limits: SourceArchive.Limits
    ): Outcome {
        require(instanceDir.isDirectory && File(instanceDir, "server.js").isFile) {
            "实例目录不存在或尚未安装"
        }
        require(ManagedFiles.isUnlinked(instanceDir)) { "实例目录不能是符号链接" }
        val planned = plan(archive, limits, includeOptional, ensureActive)
        val summary = planned.summary
        check(summary.importable) { "压缩包里没有可导入的实例数据（$NO_DATA_MESSAGE）" }
        val buffer = ByteArray(65_536)
        var imported = 0
        var bytes = 0L
        ZipFile(archive).use { zip ->
            for ((entry, path) in planned.included) {
                ensureActive()
                val target = File(instanceDir, path)
                require(ManagedFiles.isWithin(target, instanceDir)) { "导入路径逃逸实例目录" }
                val parent = requireNotNull(target.parentFile)
                check(parent.isDirectory || parent.mkdirs()) { "无法创建导入目录" }
                if (entry.isDirectory) {
                    if (!target.isDirectory) check(target.mkdirs()) { "无法创建导入目录" }
                    continue
                }
                require(!(target.exists() && target.isDirectory)) { "目标中存在同名目录：$path" }
                val temporary = File(parent, TMP_PREFIX + UUID.randomUUID().toString())
                val crc = CRC32()
                var written = 0L
                try {
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(temporary.toPath(), StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { sink ->
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(count.toLong() <= entry.size - written) {
                                    "压缩包展开超过声明大小"
                                }
                                written += count
                                crc.update(buffer, 0, count)
                                sink.write(buffer, 0, count)
                            }
                        }
                    }
                    require(written == entry.size && crc.value == entry.crc) { "压缩包文件校验失败：$path" }
                    ensureActive()
                    publish(temporary, target)
                } catch (error: Throwable) {
                    runCatching { temporary.delete() }
                    throw error
                }
                imported++
                bytes += written
                onProgress(imported, bytes)
            }
        }
        return Outcome(imported, bytes, summary.skippedEntries)
    }

    private fun publish(temporary: File, target: File) {
        val source = temporary.toPath()
        val destination = target.toPath()
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun plan(
        archive: File,
        limits: SourceArchive.Limits,
        includeOptional: Boolean,
        ensureActive: () -> Unit
    ): Plan {
        ensureActive()
        require(archive.isFile && ManagedFiles.isUnlinked(archive) && archive.length() in 22..limits.archiveBytes) {
            "压缩包缺失、为符号链接或超过大小上限"
        }
        ZipFile(archive).use { zip ->
            require(zip.size() in 1..limits.entries) { "压缩包条目数量异常" }
            val entries = ArrayList<SourceArchive.Item>(zip.size())
            val names = HashSet<String>()
            var declaredBytes = 0L
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                ensureActive()
                val entry = enumeration.nextElement()
                val path = SourceArchive.validatedPath(entry)
                require(names.add(path.lowercase(Locale.ROOT))) { "压缩包包含重复路径" }
                require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) {
                    "压缩包使用了不支持的压缩方式"
                }
                require(entry.size in 0..limits.fileBytes && entry.crc in 0..0xffffffffL) {
                    "压缩包条目大小或校验位无效"
                }
                require(!entry.isDirectory || entry.size == 0L) { "压缩包目录条目包含数据" }
                require(entry.size <= limits.expandedBytes - declaredBytes) { "压缩包超过展开体积上限" }
                declaredBytes += entry.size
                entries.add(SourceArchive.Item(entry, path))
            }
            val prefix = SourceArchive.wrapperPrefix(entries)
            var importEntries = 0
            var importBytes = 0L
            var skippedEntries = 0
            var skippedBytes = 0L
            var hasSecrets = false
            var hasConfig = false
            val included = ArrayList<SourceArchive.Item>()
            for (item in entries) {
                if (item.entry.isDirectory && prefix.isNotEmpty() && item.path == prefix.removeSuffix("/")) continue
                val path = item.path.removePrefix(prefix)
                if (path.isEmpty()) continue
                if (SourceArchive.isHostMetadata(path)) continue
                val optional = path in OPTIONAL_FILES
                if (optional) {
                    if (path == "secrets.json") hasSecrets = true else hasConfig = true
                    if (includeOptional) {
                        included.add(SourceArchive.Item(item.entry, path))
                        importEntries++
                        importBytes += item.entry.size
                    } else {
                        skippedEntries++
                        skippedBytes += item.entry.size
                    }
                    continue
                }
                if (isUserData(path)) {
                    included.add(SourceArchive.Item(item.entry, path))
                    importEntries++
                    importBytes += item.entry.size
                } else {
                    skippedEntries++
                    skippedBytes += item.entry.size
                }
            }
            return Plan(
                Summary(importEntries, importBytes, skippedEntries, skippedBytes, hasSecrets, hasConfig, prefix),
                included
            )
        }
    }

    /** 白名单：只认用户数据；依赖、程序与构建缓存一律不导入。 */
    private fun isUserData(path: String): Boolean {
        if (path == "data" || path.startsWith("data/")) {
            return path != WEBPACK_CACHE && !path.startsWith("$WEBPACK_CACHE/")
        }
        if (path == "plugins" || path.startsWith("plugins/")) return true
        // 只需要 third-party 这一层的目录骨架与内容，其余 public/ 是按版本编译的程序文件。
        if (path == EXTENSIONS_ROOT || path.startsWith("$EXTENSIONS_ROOT/")) return true
        if (path in setOf("public", "public/scripts", "public/scripts/extensions")) return true
        return false
    }

    private const val NO_DATA_MESSAGE = "需要包含 data/ 或 public/scripts/extensions/third-party/ 的备份"
}
