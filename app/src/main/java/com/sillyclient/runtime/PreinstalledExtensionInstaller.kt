package com.sillyclient.runtime

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONObject

data class PreinstalledExtensionsRequest(val revision: Int, val extensionIds: List<String>)

class PreinstalledExtensionsTransaction internal constructor(
    private val rollbackAction: () -> Unit,
    private val commitAction: () -> Unit = {}
) {
    private var active = true
    @Synchronized fun commit() { if (active) { commitAction(); active = false } }
    @Synchronized fun rollback() { if (active) { rollbackAction(); active = false } }
}

/** Metadata is bundled, third-party source is downloaded only into the user's instance. */
object PreinstalledExtensionInstaller {
    private const val MAX_ARCHIVE = 32L * 1024 * 1024
    private const val MAX_UNPACKED = 128L * 1024 * 1024
    private const val MAX_FILE = 32L * 1024 * 1024
    private const val MAX_ENTRIES = 8192
    private val repositories = mapOf(
        "tavern-helper" to "N0VI028/JS-Slash-Runner",
        "littlewhitebox" to "RT15548/LittleWhiteBox",
        "prompt-template" to "zonde306/ST-Prompt-Template",
        "dice" to "SillyTavern/Extension-Dice"
    )

    data class Extension(
        val id: String, val name: String, val repository: String, val commit: String,
        val sha256: String, val bytes: Long, val minimumClientVersion: String?, val licensePath: String
    ) {
        val directoryName: String get() = repository.substringAfter('/')
        val archiveRoot: String get() = "$directoryName-$commit/"
        val archiveUrl: String get() = "https://codeload.github.com/$repository/zip/$commit"
    }

    fun selection(catalogText: String, request: PreinstalledExtensionsRequest): List<Extension> {
        val catalog = JSONObject(catalogText)
        require(request.revision == 1 && catalog.getInt("revision") == request.revision) { "不支持的预制安装清单版本" }
        require(request.extensionIds.size <= 4 && request.extensionIds.toSet().size == request.extensionIds.size) {
            "预制扩展选择无效"
        }
        val records = catalog.getJSONArray("extensions")
        val extensions = (0 until records.length()).map { index ->
            val record = records.getJSONObject(index)
            Extension(
                record.getString("id"), record.getString("displayName"), record.getString("repository"),
                record.getString("commit"), record.getString("archiveSha256"), record.getLong("archiveBytes"),
                record.optString("minimumClientVersion").takeIf { it.isNotBlank() }, record.getString("licensePath")
            ).also {
                require(repositories[it.id] == it.repository) { "扩展不在预制安装允许清单中" }
                require(it.repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) &&
                    it.directoryName != "." && it.directoryName != "..") { "Invalid catalog repository" }
                require(it.commit.matches(Regex("[a-f0-9]{40}")) && it.sha256.matches(Regex("[A-Fa-f0-9]{64}"))) {
                    "Invalid catalog pin"
                }
                require(it.bytes in 1..MAX_ARCHIVE) { "Invalid catalog archive size" }
                validateRelativePath(it.licensePath)
                require(it.licensePath == if (it.id == "littlewhitebox") "docs/LICENSE.md" else "LICENSE") {
                    "Invalid catalog license path"
                }
            }
        }.associateBy { it.id }
        require(extensions.size == records.length()) { "Duplicate catalog entries" }
        return request.extensionIds.map { extensions[it] ?: throw IllegalArgumentException("未知预制扩展: $it") }
    }

    fun install(
        context: Context, directory: File, request: PreinstalledExtensionsRequest,
        operations: OperationCoordinator, operation: OperationCoordinator.Operation,
        onLog: (String) -> Unit
    ): PreinstalledExtensionsTransaction {
        val catalog = context.assets.open("preinstalled-extensions/catalog.json").bufferedReader().use { it.readText() }
        return installInternal(
            catalog, directory, request, { operations.ensureCurrent(operation) }, onLog,
            download = { extension, destination ->
                download(extension, destination, operations, operation)
            },
            commit = { action -> operations.commit(operation, action) }
        )
    }

    internal fun installInternal(
        catalogText: String, directory: File, request: PreinstalledExtensionsRequest,
        ensureActive: () -> Unit, onLog: (String) -> Unit,
        download: (Extension, File) -> Unit,
        commit: (() -> Unit) -> Unit = { it() }
    ): PreinstalledExtensionsTransaction {
        val selected = selection(catalogText, request)
        if (selected.isEmpty()) return PreinstalledExtensionsTransaction(rollbackAction = {})
        ensureActive()
        val version = JSONObject(File(directory, "package.json").readText()).getString("version")
        val userRoot = File(directory, "data/default-user")
        val targetRoot = File(userRoot, "extensions")
        require(ManagedFiles.isWithin(targetRoot, directory)) { "Extension root escapes the instance" }
        val stagingRoot = File(userRoot, ".sillyclient-preinstall-${UUID.randomUUID()}")
        require(ManagedFiles.isWithin(stagingRoot, directory)) { "Extension staging root escapes the instance" }
        val created = mutableListOf<File>()
        val identities = mutableMapOf<String, String>()
        val ownership = UUID.randomUUID().toString()
        val markerName = ".sillyclient-preinstall-$ownership.owned"
        fun removeOwned(target: File) {
            val marker = File(target, markerName)
            if (marker.isFile && marker.length() == ownership.length.toLong() &&
                ManagedFiles.isWithin(marker, target) && marker.readText() == ownership &&
                runCatching { treeIdentity(target) }.getOrNull() == identities[target.absolutePath]) {
                check(ManagedFiles.deleteDirectory(target, targetRoot)) { "Could not roll back extension" }
            } else if (target.exists()) onLog("[WARN] 扩展目录归属已变化，保留文件: ${target.name}")
        }
        var handedOff = false
        try {
            check(stagingRoot.mkdirs()) { "Could not create extension staging directory" }
            val pending = mutableListOf<Pair<File, File>>()
            for (extension in selected) {
                ensureActive()
                requireCompatible(version, extension.minimumClientVersion)
                val target = File(targetRoot, extension.directoryName)
                require(ManagedFiles.isWithin(target, directory)) { "Extension target escapes the instance" }
                if (target.exists()) {
                    validateManifest(target, version)
                    onLog("[OK] ${extension.name} 已存在，保留现有文件和启用设置")
                    continue
                }
                onLog("> 下载并校验 ${extension.name} (${extension.commit.take(12)})")
                val archive = File(stagingRoot, "${extension.id}.zip")
                download(extension, archive)
                ensureActive()
                check(archive.length() == extension.bytes && archive.length() <= MAX_ARCHIVE) { "扩展压缩包大小校验失败" }
                check(sha256(archive, ensureActive).equals(extension.sha256, ignoreCase = true)) { "扩展压缩包 SHA-256 校验失败" }
                val staged = File(stagingRoot, extension.directoryName)
                extractVerifiedArchive(archive, staged, extension.archiveRoot, ensureActive)
                validateManifest(staged, version)
                check(safeFile(staged, extension.licensePath).isFile) { "扩展授权文件缺失" }
                File(staged, markerName).writeText(ownership)
                identities[target.absolutePath] = treeIdentity(staged, ensureActive)
                pending.add(staged to target)
            }
            ensureActive()
            commit {
                targetRoot.mkdirs()
                require(ManagedFiles.isWithin(targetRoot, directory)) { "Extension root changed" }
                for ((source, target) in pending) {
                    check(!target.exists() && ManagedFiles.isWithin(target, directory)) { "扩展目录在安装期间发生变化，未覆盖原目录" }
                    check(source.renameTo(target)) { "Could not commit extension directory" }
                    created.add(target)
                }
            }
            val transaction = PreinstalledExtensionsTransaction(
                rollbackAction = { created.asReversed().forEach(::removeOwned) },
                commitAction = {
                    created.forEach { target ->
                        val marker = File(target, markerName)
                        if (marker.isFile && marker.length() == ownership.length.toLong() &&
                            ManagedFiles.isWithin(marker, target) && marker.readText() == ownership) {
                            if (!marker.delete()) onLog("[WARN] 扩展已安装，暂存归属标记未清除: ${target.name}")
                        }
                    }
                }
            )
            ensureActive()
            handedOff = true
            return transaction
        } finally {
            if (!handedOff) created.asReversed().forEach(::removeOwned)
            if (stagingRoot.exists()) ManagedFiles.deleteDirectory(stagingRoot, userRoot)
        }
    }

    private fun download(
        extension: Extension, destination: File,
        operations: OperationCoordinator, operation: OperationCoordinator.Operation
    ) {
        val connection = URL(extension.archiveUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "SillyClient")
        operations.onCancel(operation) {
            Thread({ connection.disconnect() }, "SillyClient-cancel-extension").apply { isDaemon = true }.start()
        }.use {
            try {
                operations.ensureCurrent(operation)
                check(connection.responseCode == 200) { "扩展下载失败 (HTTP ${connection.responseCode})" }
                if (connection.contentLengthLong >= 0) check(connection.contentLengthLong <= MAX_ARCHIVE) { "扩展下载超过大小上限" }
                connection.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(65_536)
                        var total = 0L
                        var count: Int
                        while (input.read(buffer).also { count = it } >= 0) {
                            operations.ensureCurrent(operation)
                            total += count
                            check(total <= MAX_ARCHIVE && total <= extension.bytes) { "扩展下载超过大小上限" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } finally { connection.disconnect() }
        }
    }

    internal fun extractVerifiedArchive(
        archive: File, destination: File, expectedRoot: String, ensureActive: () -> Unit
    ) {
        validateCentralDirectory(archive)
        check(destination.mkdirs()) { "Could not create extension extraction directory" }
        var total = 0L
        val paths = mutableSetOf<String>()
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                ensureActive()
                val entry = entries.nextElement()
                check(++count <= MAX_ENTRIES) { "扩展压缩包条目过多" }
                require(entry.name.startsWith(expectedRoot)) { "扩展压缩包根目录不匹配" }
                val relative = entry.name.removePrefix(expectedRoot)
                if (relative.isEmpty() && entry.isDirectory) continue
                val output = safeFile(destination, relative)
                require(paths.add(output.absolutePath)) { "扩展压缩包包含重复路径" }
                check(entry.size in 0..MAX_FILE && total + entry.size <= MAX_UNPACKED) { "扩展解压大小超过上限" }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    var written = 0L
                    zip.getInputStream(entry).use { input ->
                        output.outputStream().use { sink ->
                            val buffer = ByteArray(65_536)
                            var bytes: Int
                            while (input.read(buffer).also { bytes = it } >= 0) {
                                ensureActive()
                                written += bytes
                                total += bytes
                                check(written <= MAX_FILE && total <= MAX_UNPACKED) { "扩展解压大小超过上限" }
                                sink.write(buffer, 0, bytes)
                            }
                        }
                    }
                    check(written == entry.size) { "扩展压缩包条目大小不匹配" }
                }
            }
        }
    }

    /** The central directory carries Unix symlink modes; ZipEntry does not expose them. */
    private fun validateCentralDirectory(archive: File) {
        RandomAccessFile(archive, "r").use { input ->
            check(input.length() in 22..MAX_ARCHIVE) { "Invalid extension archive" }
            val tailSize = minOf(input.length(), 65_557).toInt()
            val tail = ByteArray(tailSize)
            input.seek(input.length() - tailSize)
            input.readFully(tail)
            val end = (tailSize - 22 downTo 0).firstOrNull { offset ->
                u32(tail, offset) == 0x06054b50L && offset + 22 + u16(tail, offset + 20) == tailSize
            } ?: throw IllegalArgumentException("Missing ZIP directory")
            check(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0) { "Multi-disk ZIPs are not supported" }
            val count = u16(tail, end + 10)
            val bytes = u32(tail, end + 12)
            val offset = u32(tail, end + 16)
            check(count in 1..MAX_ENTRIES && count == u16(tail, end + 8)) { "Invalid ZIP entry count" }
            check(offset + bytes <= input.length() - tailSize + end) { "Invalid ZIP directory bounds" }
            input.seek(offset)
            repeat(count) {
                val header = ByteArray(46)
                input.readFully(header)
                check(u32(header, 0) == 0x02014b50L) { "Invalid ZIP directory header" }
                check(u16(header, 8) and 1 == 0 && u16(header, 34) == 0) { "Encrypted ZIPs are not supported" }
                check(u16(header, 10) in setOf(0, 8)) { "Unsupported ZIP compression" }
                check(u32(header, 24) <= MAX_FILE) { "Extension archive file is too large" }
                val mode = (u32(header, 38) ushr 16).toInt() and 0xf000
                check(mode != 0xa000) { "扩展压缩包不能包含符号链接" }
                input.seek(input.filePointer + u16(header, 28) + u16(header, 30) + u16(header, 32))
                check(input.filePointer <= offset + bytes) { "Invalid ZIP directory entry bounds" }
            }
            check(input.filePointer == offset + bytes) { "Invalid ZIP directory size" }
        }
    }

    private fun safeFile(root: File, path: String): File {
        validateRelativePath(path)
        val file = File(root, path)
        require(ManagedFiles.isWithin(file, root)) { "扩展文件路径越界" }
        return file
    }

    private fun validateRelativePath(path: String) {
        require(path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000') &&
            path.split('/').none { it == ".." || it == "." } && !path.contains(':')) { "扩展文件路径无效" }
    }

    private fun validateManifest(directory: File, clientVersion: String) {
        require(ManagedFiles.isUnlinked(directory)) { "Linked extension directory is not supported" }
        val file = safeFile(directory, "manifest.json")
        check(file.isFile && file.length() <= 64 * 1024) { "扩展 manifest.json 缺失或无效" }
        val manifest = JSONObject(file.readText())
        check(manifest.optString("display_name").isNotBlank() && manifest.optString("version").isNotBlank()) { "扩展清单无效" }
        for (key in listOf("js", "css")) {
            val path = manifest.optString(key)
            if (path.isNotEmpty()) check(safeFile(directory, path).isFile) { "扩展 $key 入口缺失" }
        }
        check(manifest.optString("js").isNotEmpty() || manifest.optString("css").isNotEmpty()) { "扩展缺少入口文件" }
        requireCompatible(clientVersion, manifest.optString("minimum_client_version").takeIf { it.isNotBlank() })
    }

    private fun requireCompatible(client: String, minimum: String?) {
        if (minimum == null) return
        fun version(text: String): List<Int> {
            val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matchEntire(text)
                ?: throw IllegalArgumentException("无法核验酒馆版本与扩展兼容性")
            return (1..3).map { match.groupValues[it].toInt() }
        }
        val actual = version(client)
        val required = version(minimum)
        val comparison = actual.zip(required).firstOrNull { (a, b) -> a != b }?.let { it.first.compareTo(it.second) } ?: 0
        require(comparison > 0 || (comparison == 0 && !client.contains('-'))) { "扩展要求 SillyTavern $minimum 或更新版本" }
    }

    private fun sha256(file: File, ensureActive: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) { ensureActive(); digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun treeIdentity(directory: File, ensureActive: () -> Unit = {}): String {
        val paths = directory.walkTopDown().onEnter {
            ensureActive()
            require(ManagedFiles.isUnlinked(it)) { "Extension ownership changed" }
            true
        }.take(MAX_ENTRIES + 3).toList()
        require(paths.size <= MAX_ENTRIES + 2) { "Extension ownership changed" }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        paths.sortedBy { it.relativeTo(directory).path }.forEach { file ->
            ensureActive()
            require(ManagedFiles.isUnlinked(file)) { "Extension ownership changed" }
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            val metadata = JSONObject()
                .put("path", file.relativeTo(directory).path)
                .put("directory", attributes.isDirectory)
                .put("identity", attributes.fileKey()?.toString())
                .put("created", attributes.creationTime().toString())
                .put("modified", attributes.lastModifiedTime().toString())
            if (file.isFile) {
                bytes += file.length()
                require(file.length() <= MAX_FILE && bytes <= MAX_UNPACKED + 4096) { "Extension ownership changed" }
                metadata.put("content", sha256(file, ensureActive))
            }
            digest.update(metadata.toString().toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 255) shl (8 * index)) }
}
