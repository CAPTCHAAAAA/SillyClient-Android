package com.sillyclient.storage

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class InstanceDocumentPolicyTest {
    private val policy = InstanceDocumentPolicy()

    private fun fixture(test: (File, InstanceDocumentPolicy.Source) -> Unit) {
        val parent = System.getenv("SILLYCLIENT_TEST_TEMP_DIR")?.let(::File)
        parent?.mkdirs()
        val root = if (parent == null) Files.createTempDirectory("instance-documents-").toFile()
            else Files.createTempDirectory(parent.toPath(), "instance-documents-").toFile()
        try {
            val instance = File(root, "Readable Tavern").apply { mkdirs() }
            File(instance, "data/default-user").mkdirs()
            test(root, InstanceDocumentPolicy.Source("immutable-id", instance, "marker:fixture"))
        } finally { root.deleteRecursively() }
    }

    private fun write(source: InstanceDocumentPolicy.Source, path: String, contents: String = "user data"): File =
        File(source.directory, path).apply { parentFile!!.mkdirs(); writeText(contents) }

    @Test
    fun exposesUserAssetsThroughDataWithoutExposingTheInstanceRoot() = fixture { _, source ->
        val chat = write(source, "data/default-user/chats/A/chat.jsonl")
        write(source, "server.js")
        write(source, "config.yaml")
        write(source, "node_modules/private/index.js")
        val root = policy.reference(source)
        val account = policy.children(root, listOf(source)).single()
        assertEquals("default-user", account.relativePath)
        assertEquals(listOf("default-user/chats"), policy.children(account, listOf(source)).map { it.relativePath })
        assertEquals(chat, policy.resolve(policy.reference(source, "default-user/chats/A/chat.jsonl"), listOf(source)))
    }

    @Test
    fun hidesSettingsSecretsExtensionsAndUnknownAccountDirectories() = fixture { _, source ->
        write(source, "data/default-user/characters/card.png")
        write(source, "data/default-user/chats/log.jsonl")
        write(source, "data/default-user/secrets.json", "private")
        write(source, "data/default-user/settings.json", "private")
        write(source, "data/default-user/extensions/plugin/secrets.json", "private")
        write(source, "data/default-user/unknown-private-dir/keys.json", "private")
        write(source, "data/accounts.db", "private")
        assertEquals(listOf("default-user"), policy.children(policy.reference(source), listOf(source)).map { it.relativePath })
        assertEquals(listOf("default-user/characters", "default-user/chats"),
            policy.children(policy.reference(source, "default-user"), listOf(source)).map { it.relativePath })
    }

    @Test
    fun rejectsSecretNamesEvenInsideAnOtherwiseAllowedAssetDirectory() = fixture { _, source ->
        for (name in listOf("secrets.json", "Secrets.JSON.enc", "credentials.json", ".env", "private.pem",
            "key.p12", "vault.kdbx", "settings.json", "tokens")) {
            write(source, "data/default-user/chats/$name", "private")
            assertThrows(IllegalArgumentException::class.java) {
                policy.reference(source, "default-user/chats/$name")
            }
        }
        assertTrue(policy.children(policy.reference(source, "default-user/chats"), listOf(source)).isEmpty())
    }

    @Test
    fun rejectsTraversalAbsolutePathsControlCharactersAndHiddenSegments() = fixture { _, source ->
        for (path in listOf("../config.yaml", "/data/default-user", "default-user//chats", "default-user/../chats",
            "default-user\\chats", "default-user/chats/.git/config", "default-user/chats/line\nname")) {
            assertThrows(IllegalArgumentException::class.java) { policy.reference(source, path) }
        }
    }

    @Test
    fun aPersistedHandleCannotSwitchInstancePathOrIdentity() = fixture { root, source ->
        val reference = policy.reference(source)
        assertThrows(IllegalArgumentException::class.java) {
            policy.resolve(reference, listOf(source.copy(identity = "marker:replacement")))
        }
        val other = File(root, "Other Tavern").apply { File(this, "data/default-user").mkdirs() }
        assertThrows(IllegalArgumentException::class.java) {
            policy.resolve(reference, listOf(source.copy(directory = other)))
        }
        assertThrows(FileNotFoundException::class.java) { policy.resolve(reference, emptyList()) }
    }

    @Test
    fun matchingPathNamesDoNotPermitWritingThroughTheProvider() {
        policy.requireReadOnly("r")
        for (mode in listOf("w", "rw", "rwt", "wa", "wt", "", "R")) {
            assertThrows(IllegalArgumentException::class.java) { policy.requireReadOnly(mode) }
        }
    }

    @Test
    fun childChecksUsePathComponentsAndTheSameInstanceIdentity() = fixture { root, source ->
        write(source, "data/default-user/chats/chat/log.jsonl")
        write(source, "data/default-user/chats/chat-other/log.jsonl")
        val parent = policy.reference(source, "default-user/chats/chat")
        assertTrue(policy.isChild(parent, policy.reference(source, "default-user/chats/chat/log.jsonl"), listOf(source)))
        assertFalse(policy.isChild(parent, policy.reference(source, "default-user/chats/chat-other/log.jsonl"), listOf(source)))
        assertFalse(policy.isChild(parent, parent, listOf(source)))
        val otherDir = File(root, "Other Tavern").apply { File(this, "data/default-user/chats").mkdirs() }
        val other = InstanceDocumentPolicy.Source("other-id", otherDir, "marker:other")
        assertFalse(policy.isChild(parent, policy.reference(other, "default-user/chats"), listOf(source, other)))
    }

    @Test
    fun rejectsLinksAtTheDataRootOrInsideAnAllowedDirectory() = fixture { root, source ->
        val outside = File(root, "outside").apply { mkdirs() }
        val secret = File(outside, "private.json").apply { writeText("private") }
        val chats = File(source.directory, "data/default-user/chats").apply { mkdirs() }
        val link = File(chats, "looks-like-a-chat.jsonl")
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), secret.toPath()) }.isSuccess
        assumeTrue("Symlink creation is not available on this host", linked)
        assertThrows(IllegalArgumentException::class.java) {
            policy.reference(source, "default-user/chats/looks-like-a-chat.jsonl")
        }
        assertTrue(policy.children(policy.reference(source, "default-user/chats"), listOf(source)).isEmpty())
        Files.delete(link.toPath())
        val data = File(source.directory, "data")
        assertTrue(data.renameTo(File(source.directory, "original-data")))
        Files.createSymbolicLink(data.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { policy.reference(source) }
        Files.delete(data.toPath())
        assertEquals("private", secret.readText())
    }
}
