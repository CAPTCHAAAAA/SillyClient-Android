package com.sillyclient.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class TavernDownloadBridgeTest {

    @Test
    fun transferWritesOrderedChunksAndVerifiesSize() {
        val output = ByteArrayOutputStream()
        val request = TavernDownloadRequest("transfer_01", "chat.json", "application/json", 6)
        val transfer = TavernDownloadTransfer(request, output)

        transfer.append(0, encode("abc"))
        transfer.append(1, encode("def"))
        transfer.finish(2, 6)

        assertEquals("abcdef", output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun transferRejectsMissingOrExtraData() {
        val missing = TavernDownloadTransfer(
            TavernDownloadRequest("transfer_02", "chat.json", "application/json", 4),
            ByteArrayOutputStream()
        )
        missing.append(0, encode("abc"))
        assertThrows(IllegalArgumentException::class.java) { missing.finish(1, 3) }

        val outOfOrder = TavernDownloadTransfer(
            TavernDownloadRequest("transfer_03", "chat.json", "application/json", -1),
            ByteArrayOutputStream()
        )
        assertThrows(IllegalArgumentException::class.java) {
            outOfOrder.append(1, encode("abc"))
        }
    }

    @Test
    fun bridgeSerializesRequestsAndCompletesOneTransfer() {
        val requested = mutableListOf<TavernDownloadRequest>()
        val started = mutableListOf<TavernDownloadRequest>()
        val terminal = mutableListOf<TavernDownloadTerminalEvent>()
        val output = ByteArrayOutputStream()
        val bridge = TavernDownloadBridge(
            onSaveRequested = requested::add,
            onStartTransfer = started::add,
            onTerminal = terminal::add,
            onTransientError = {}
        )
        bridge.installSession("session-token")

        assertTrue(
            bridge.requestDownload(
                "session-token",
                "transfer_04",
                "../character.json",
                "application/json; charset=utf-8",
                "3"
            )
        )
        assertFalse(
            bridge.requestDownload(
                "session-token",
                "transfer_05",
                "second.json",
                "application/json",
                "1"
            )
        )
        assertEquals("character.json", requested.single().fileName)
        assertEquals("application/json", requested.single().mimeType)

        val request = requested.single()
        assertTrue(bridge.attachDestination(request, output))
        assertEquals(listOf(request), started)
        assertTrue(bridge.appendDownloadChunk("session-token", "transfer_04", 0, encode("abc")))
        assertTrue(bridge.finishDownload("session-token", "transfer_04", 1, "3"))
        assertTrue(terminal.single().success)
        assertEquals("abc", output.toString(Charsets.UTF_8.name()))

        bridge.releaseTerminal(request)
        assertTrue(
            bridge.requestDownload(
                "session-token",
                "transfer_05",
                "second.json",
                "application/json",
                "1"
            )
        )
    }

    @Test
    fun activeTransferKeepsItsOriginalSessionCapability() {
        val requested = mutableListOf<TavernDownloadRequest>()
        val terminal = mutableListOf<TavernDownloadTerminalEvent>()
        val bridge = TavernDownloadBridge(
            onSaveRequested = requested::add,
            onStartTransfer = {},
            onTerminal = terminal::add,
            onTransientError = {}
        )

        assertTrue(bridge.installSession("first-session"))
        assertTrue(
            bridge.requestDownload(
                "first-session",
                "transfer_06",
                "chat.json",
                "application/json",
                "3"
            )
        )
        assertFalse(bridge.installSession("second-session"))
        val request = requested.single()
        assertTrue(bridge.isAwaitingDestination(request))

        val output = ByteArrayOutputStream()
        assertTrue(bridge.attachDestination(request, output))
        assertFalse(bridge.appendDownloadChunk("second-session", "transfer_06", 0, encode("abc")))
        assertTrue(bridge.appendDownloadChunk("first-session", "transfer_06", 0, encode("abc")))
        assertTrue(bridge.finishDownload("first-session", "transfer_06", 1, "3"))
        assertTrue(terminal.single().success)
    }

    @Test
    fun navigationInvalidatesStaleDestinationSelection() {
        val requested = mutableListOf<TavernDownloadRequest>()
        val terminal = mutableListOf<TavernDownloadTerminalEvent>()
        val bridge = TavernDownloadBridge(
            onSaveRequested = requested::add,
            onStartTransfer = {},
            onTerminal = terminal::add,
            onTransientError = {}
        )
        bridge.installSession("session-token")
        assertTrue(
            bridge.requestDownload(
                "session-token",
                "transfer_07",
                "chat.json",
                "application/json",
                "-1"
            )
        )

        bridge.invalidateSession()
        val request = requested.single()
        val output = TrackingOutputStream()
        assertFalse(bridge.isAwaitingDestination(request))
        assertFalse(bridge.attachDestination(request, output))
        assertTrue(output.closed)
        assertFalse(terminal.single().success)
        assertFalse(terminal.single().notifyUser)
    }

    @Test
    fun inactiveRequestsTimeOutAndWritingActivityRefreshesDeadline() {
        var now = 0L
        val requested = mutableListOf<TavernDownloadRequest>()
        val terminal = mutableListOf<TavernDownloadTerminalEvent>()
        val bridge = TavernDownloadBridge(
            onSaveRequested = requested::add,
            onStartTransfer = {},
            onTerminal = terminal::add,
            onTransientError = {},
            nowMillis = { now }
        )
        bridge.installSession("session-token")
        assertTrue(
            bridge.requestDownload(
                "session-token",
                "transfer_08",
                "chat.json",
                "application/json",
                "-1"
            )
        )
        now = 999
        assertFalse(bridge.expireInactive(1_000, 500))
        now = 1_000
        assertTrue(bridge.expireInactive(1_000, 500))
        assertFalse(bridge.hasActiveRequest())
        assertTrue(terminal.single().message?.contains("destination") == true)

        bridge.releaseTerminal(requested.single())
        now = 2_000
        assertTrue(
            bridge.requestDownload(
                "session-token",
                "transfer_09",
                "chat.json",
                "application/json",
                "-1"
            )
        )
        val output = TrackingOutputStream()
        assertTrue(bridge.attachDestination(requested.last(), output))
        now = 2_400
        assertTrue(bridge.appendDownloadChunk("session-token", "transfer_09", 0, encode("abc")))
        now = 2_899
        assertFalse(bridge.expireInactive(1_000, 500))
        now = 2_900
        assertTrue(bridge.expireInactive(1_000, 500))
        assertTrue(output.closed)
        assertTrue(terminal.last().message?.contains("responding") == true)
    }

    @Test
    fun fileNamesMimeTypesAndOriginsAreRestricted() {
        assertEquals(
            "name_.json",
            TavernDownloadFiles.sanitizeFileName("../../unsafe/name?.json", "application/json")
        )
        assertEquals(
            "sillytavern-export.png",
            TavernDownloadFiles.sanitizeFileName("..", "image/png")
        )
        assertEquals(
            "application/octet-stream",
            TavernDownloadFiles.normalizeMimeType("text/html\r\nX-Test: bad")
        )

        assertTrue(
            TavernDownloadFiles.sameOrigin(
                "http://127.0.0.1:8000/",
                "http://localhost:8000/settings"
            )
        )
        assertTrue(
            TavernDownloadFiles.sameOrigin(
                "https://example.com/tavern",
                "https://example.com:443/chat"
            )
        )
        assertFalse(
            TavernDownloadFiles.sameOrigin(
                "https://example.com/",
                "https://evil.example/"
            )
        )
        assertFalse(
            TavernDownloadFiles.sameOrigin(
                "https://example.com/",
                "http://example.com/"
            )
        )
        assertTrue(
            TavernDownloadFiles.sameOrigin(
                "http://example.com/",
                "https://example.com/settings"
            )
        )
        assertTrue(
            TavernDownloadFiles.sameOrigin(
                "http://example.com:8443/",
                "https://example.com:8443/chat"
            )
        )
        assertTrue(TavernDownloadFiles.isLoopbackHttpUrl("http://localhost:8000/"))
        assertFalse(TavernDownloadFiles.isLoopbackHttpUrl("http://localhost.evil.test/"))
        assertFalse(TavernDownloadFiles.isLoopbackHttpUrl("https://127.0.0.1:8000/"))
    }

    @Test
    fun injectedScriptCoversDetachedBlobDownloads() {
        val script = TavernDownloadScript.build("12345678-1234-1234-1234-123456789abc")

        assertTrue(script.contains("HTMLAnchorElement.prototype.click"))
        assertTrue(script.contains("URL.createObjectURL"))
        assertTrue(script.contains("URL.revokeObjectURL"))
        assertTrue(script.contains("appendDownloadChunk"))
        assertTrue(script.contains("256 * 1024"))
    }

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray())

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
