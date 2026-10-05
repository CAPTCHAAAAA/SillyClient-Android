package com.sillyclient.storage

import com.sillyclient.runtime.ManagedFiles
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/** Only explicitly shared user assets are reachable through the system file picker. */
class InstanceDocumentPolicy {
    data class Source(val instanceId: String, val directory: File, val identity: String)
    data class Reference(val instanceId: String, val directory: String, val identity: String, val relativePath: String)

    fun reference(source: Source, relativePath: String = ""): Reference =
        Reference(source.instanceId, source.directory.absoluteFile.normalize().path, source.identity, relativePath).also {
            resolve(it, listOf(source))
        }

    fun resolve(reference: Reference, sources: List<Source>): File {
        val source = sources.singleOrNull { it.instanceId == reference.instanceId }
            ?: throw FileNotFoundException("The instance is no longer available")
        require(source.identity.isNotBlank() && reference.identity == source.identity &&
            reference.directory == source.directory.absoluteFile.normalize().path) {
            "The shared instance location or identity changed"
        }
        require(source.directory.isDirectory && ManagedFiles.isUnlinked(source.directory)) {
            "The shared instance directory is unavailable"
        }
        val data = File(source.directory, "data")
        require(data.isDirectory && ManagedFiles.isWithin(data, source.directory)) {
            "The instance has no accessible standard user data directory"
        }
        val parts = components(reference.relativePath)
        val target = if (parts.isEmpty()) data else File(data, parts.joinToString(File.separator))
        require(parts.isEmpty() || ManagedFiles.isWithin(target, data)) { "Document paths cannot contain symbolic links" }
        require(target.isFile || target.isDirectory) { "The document is unavailable" }
        require(parts.size != 1 || target.isDirectory) { "Root configuration files are not shared" }
        require(parts.size != 2 || target.isDirectory) { "Only user asset directories are shared" }
        return target
    }

    fun children(parent: Reference, sources: List<Source>): List<Reference> {
        val directory = resolve(parent, sources)
        require(directory.isDirectory) { "The selected document is not a directory" }
        val source = sources.single { it.instanceId == parent.instanceId }
        return (directory.listFiles() ?: throw FileNotFoundException("Could not read the shared directory"))
            .mapNotNull { file ->
                val relative = if (parent.relativePath.isEmpty()) file.name else "${parent.relativePath}/${file.name}"
                runCatching { reference(source, relative) }.getOrNull()
            }.sortedWith(compareBy<Reference> { !File(directory, it.relativePath.substringAfterLast('/')).isDirectory }
                .thenBy { it.relativePath.lowercase(Locale.ROOT) })
    }

    fun isChild(parent: Reference, child: Reference, sources: List<Source>): Boolean {
        resolve(parent, sources)
        resolve(child, sources)
        return parent.instanceId == child.instanceId && parent.directory == child.directory &&
            parent.identity == child.identity && parent.relativePath != child.relativePath &&
            (parent.relativePath.isEmpty() || child.relativePath.startsWith("${parent.relativePath}/"))
    }

    fun requireReadOnly(mode: String) {
        require(mode == "r") { "Shared instance documents are read-only" }
    }

    private fun components(path: String): List<String> {
        require(path.length <= 4096 && !path.contains('\\') && path.none { it.code < 32 }) { "Invalid document path" }
        if (path.isEmpty()) return emptyList()
        val parts = path.split('/')
        require(parts.size <= 128 && parts.all { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith('.') }) {
            "Invalid document path components"
        }
        require(parts.none { isPrivate(it) }) { "Private instance files are not shared" }
        require(parts.size < 2 || parts[1].lowercase(Locale.ROOT) in ASSET_DIRECTORIES) {
            "Only user asset directories are shared"
        }
        return parts
    }

    private fun isPrivate(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower in PRIVATE_NAMES || lower.startsWith("secrets.") || lower.startsWith("credentials.") ||
            lower.substringAfterLast('.', "") in PRIVATE_EXTENSIONS
    }

    companion object {
        private val ASSET_DIRECTORIES = setOf(
            "characters", "chats", "group chats", "groups", "user avatars", "backgrounds", "worlds",
            "themes", "presets", "context", "instruct", "movingui", "quickreplies", "quickreply",
            "novelai settings", "openai settings", "textgen settings", "koboldai settings", "sysprompt"
        )
        private val PRIVATE_NAMES = setOf(
            "node_modules", "extensions", "plugins", "secrets", "credentials", "accounts",
            "config.yaml", "config.yml", "settings.json", "password", "passwords", "tokens"
        )
        private val PRIVATE_EXTENSIONS = setOf("key", "pem", "p12", "pfx", "kdbx", "db", "sqlite", "sqlite3")
    }
}
