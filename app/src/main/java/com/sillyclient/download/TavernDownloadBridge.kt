package com.sillyclient.download

import android.webkit.JavascriptInterface
import java.io.OutputStream
import java.net.URI
import java.util.Base64

data class TavernDownloadRequest(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val expectedBytes: Long,
    val sessionSerial: Long = 0
)

data class TavernDownloadTerminalEvent(
    val request: TavernDownloadRequest,
    val success: Boolean,
    val message: String? = null,
    val notifyPage: Boolean = true,
    val notifyUser: Boolean = true
)

/**
 * Narrow JavaScript bridge used only by the raw Tavern WebView.
 *
 * The top-level page receives a per-navigation capability token. Cross-origin frames can see the
 * Java interface Android adds to WebView, but cannot use it without that token.
 */
class TavernDownloadBridge(
    private val onSaveRequested: (TavernDownloadRequest) -> Unit,
    private val onStartTransfer: (TavernDownloadRequest) -> Unit,
    private val onTerminal: (TavernDownloadTerminalEvent) -> Unit,
    private val onTransientError: (String) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private sealed interface State {
        data object Idle : State
        data class Waiting(
            val request: TavernDownloadRequest,
            val lastActivityAtMillis: Long
        ) : State
        data class Writing(
            val request: TavernDownloadRequest,
            val transfer: TavernDownloadTransfer,
            val lastActivityAtMillis: Long
        ) : State
        data class Terminal(val request: TavernDownloadRequest) : State
    }

    private val lock = Any()
    private var sessionToken: String? = null
    private var sessionSerial = 0L
    private var state: State = State.Idle

    fun installSession(token: String): Boolean = synchronized(lock) {
        when (state) {
            is State.Waiting, is State.Writing -> false
            State.Idle -> {
                sessionToken = token
                sessionSerial += 1
                true
            }
            is State.Terminal -> {
                state = State.Idle
                sessionToken = token
                sessionSerial += 1
                true
            }
        }
    }

    fun isAwaitingDestination(request: TavernDownloadRequest): Boolean = synchronized(lock) {
        val waiting = state as? State.Waiting
        waiting != null && sameHostRequest(waiting.request, request) && sessionToken != null
    }

    fun hasActiveRequest(): Boolean = synchronized(lock) {
        state is State.Waiting || state is State.Writing
    }

    fun expireInactive(
        waitingTimeoutMillis: Long,
        writingTimeoutMillis: Long
    ): Boolean {
        require(waitingTimeoutMillis > 0) { "waiting timeout must be positive" }
        require(writingTimeoutMillis > 0) { "writing timeout must be positive" }

        val event = synchronized(lock) {
            val current = state
            val timeoutMillis = when (current) {
                is State.Waiting -> waitingTimeoutMillis
                is State.Writing -> writingTimeoutMillis
                State.Idle, is State.Terminal -> return false
            }
            val lastActivityAtMillis = when (current) {
                is State.Waiting -> current.lastActivityAtMillis
                is State.Writing -> current.lastActivityAtMillis
                State.Idle, is State.Terminal -> return false
            }
            if ((nowMillis() - lastActivityAtMillis).coerceAtLeast(0L) < timeoutMillis) {
                return false
            }
            terminalizeLocked(
                message = when (current) {
                    is State.Waiting -> "Timed out while waiting for an export destination"
                    is State.Writing -> "Export transfer stopped responding"
                    State.Idle, is State.Terminal -> return false
                },
                notifyPage = sessionToken != null,
                notifyUser = true
            )
        }
        event?.let(onTerminal)
        return event != null
    }

    fun invalidateSession() {
        val event = synchronized(lock) {
            sessionToken = null
            val terminal = terminalizeLocked(
                message = "Tavern page navigated while exporting",
                notifyPage = false,
                notifyUser = false
            )
            if (state is State.Terminal) state = State.Idle
            terminal
        }
        event?.let(onTerminal)
    }

    fun invalidateSession(token: String) {
        val event = synchronized(lock) {
            if (sessionToken != token) return
            sessionToken = null
            val terminal = terminalizeLocked(
                message = "Tavern download bridge injection failed",
                notifyPage = false,
                notifyUser = true
            )
            if (state is State.Terminal) state = State.Idle
            terminal
        }
        event?.let(onTerminal)
    }

    @JavascriptInterface
    fun requestDownload(
        token: String?,
        id: String?,
        requestedFileName: String?,
        requestedMimeType: String?,
        expectedBytesValue: String?
    ): Boolean {
        val request = synchronized(lock) {
            if (!isValidTokenLocked(token) || state !is State.Idle) return false
            val safeId = id?.takeIf(TavernDownloadFiles::isValidTransferId) ?: return false
            val expectedBytes = TavernDownloadFiles.parseByteCount(expectedBytesValue)
            val value = TavernDownloadRequest(
                id = safeId,
                fileName = TavernDownloadFiles.sanitizeFileName(requestedFileName, requestedMimeType),
                mimeType = TavernDownloadFiles.normalizeMimeType(requestedMimeType),
                expectedBytes = expectedBytes,
                sessionSerial = sessionSerial
            )
            state = State.Waiting(value, nowMillis())
            value
        }

        return try {
            onSaveRequested(request)
            true
        } catch (error: Exception) {
            cancelFromHost(
                request,
                "Unable to launch Android document picker: ${error.message}",
                notifyPage = true,
                notifyUser = true
            )
            false
        }
    }

    fun attachDestination(request: TavernDownloadRequest, output: OutputStream): Boolean {
        val activeRequest = synchronized(lock) {
            val waiting = state as? State.Waiting
            if (
                waiting == null ||
                !sameHostRequest(waiting.request, request) ||
                sessionToken == null
            ) {
                runCatching { output.close() }
                return false
            }
            state = State.Writing(
                waiting.request,
                TavernDownloadTransfer(waiting.request, output),
                nowMillis()
            )
            waiting.request
        }

        return try {
            onStartTransfer(activeRequest)
            true
        } catch (error: Exception) {
            cancelFromHost(
                activeRequest,
                "Unable to start JavaScript export stream: ${error.message}",
                notifyPage = true,
                notifyUser = true
            )
            false
        }
    }

    @JavascriptInterface
    fun appendDownloadChunk(
        token: String?,
        id: String?,
        sequence: Int,
        encodedChunk: String?
    ): Boolean {
        val failure = synchronized(lock) {
            if (!isValidTokenLocked(token)) return false
            val writing = state as? State.Writing ?: return false
            if (writing.request.id != id || encodedChunk == null) return false
            try {
                writing.transfer.append(sequence, encodedChunk)
                state = writing.copy(lastActivityAtMillis = nowMillis())
                null
            } catch (error: Exception) {
                terminalizeLocked(
                    message = "Unable to write export data: ${error.message}",
                    notifyPage = true,
                    notifyUser = true
                )
            }
        }
        failure?.let(onTerminal)
        return failure == null
    }

    @JavascriptInterface
    fun finishDownload(
        token: String?,
        id: String?,
        sequence: Int,
        totalBytesValue: String?
    ): Boolean {
        val result = synchronized(lock) {
            if (!isValidTokenLocked(token)) return false
            val writing = state as? State.Writing ?: return false
            if (writing.request.id != id) return false
            try {
                writing.transfer.finish(sequence, TavernDownloadFiles.parseByteCount(totalBytesValue))
                state = State.Terminal(writing.request)
                TavernDownloadTerminalEvent(request = writing.request, success = true)
            } catch (error: Exception) {
                terminalizeLocked(
                    message = "Unable to finish export: ${error.message}",
                    notifyPage = true,
                    notifyUser = true
                ) ?: return false
            }
        }
        onTerminal(result)
        return result.success
    }

    @JavascriptInterface
    fun abortDownload(token: String?, id: String?, message: String?) {
        val event = synchronized(lock) {
            if (!isValidTokenLocked(token)) return
            val request = currentRequestLocked() ?: return
            if (request.id != id) return
            terminalizeLocked(
                message = message?.take(240) ?: "JavaScript export failed",
                notifyPage = true,
                notifyUser = true
            )
        }
        event?.let(onTerminal)
    }

    @JavascriptInterface
    fun reportDownloadError(token: String?, message: String?) {
        synchronized(lock) {
            if (!isValidTokenLocked(token)) return
        }
        onTransientError(message?.take(240) ?: "Unable to start export")
    }

    fun cancelFromHost(
        request: TavernDownloadRequest,
        message: String,
        notifyPage: Boolean,
        notifyUser: Boolean
    ) {
        val event = synchronized(lock) {
            val activeRequest = currentRequestLocked() ?: return
            if (!sameHostRequest(activeRequest, request)) return
            terminalizeLocked(message, notifyPage, notifyUser)
        }
        event?.let(onTerminal)
    }

    fun releaseTerminal(request: TavernDownloadRequest) {
        synchronized(lock) {
            val terminal = state as? State.Terminal ?: return
            if (sameHostRequest(terminal.request, request)) state = State.Idle
        }
    }

    fun destroy() {
        synchronized(lock) {
            (state as? State.Writing)?.transfer?.abort()
            state = State.Idle
            sessionToken = null
        }
    }

    private fun isValidTokenLocked(token: String?): Boolean =
        token != null && token == sessionToken

    private fun sameHostRequest(
        left: TavernDownloadRequest,
        right: TavernDownloadRequest
    ): Boolean = left.id == right.id && left.sessionSerial == right.sessionSerial

    private fun currentRequestLocked(): TavernDownloadRequest? = when (val current = state) {
        State.Idle -> null
        is State.Waiting -> current.request
        is State.Writing -> current.request
        is State.Terminal -> current.request
    }

    private fun terminalizeLocked(
        message: String,
        notifyPage: Boolean,
        notifyUser: Boolean
    ): TavernDownloadTerminalEvent? {
        val request = when (val current = state) {
            State.Idle, is State.Terminal -> return null
            is State.Waiting -> current.request
            is State.Writing -> {
                current.transfer.abort()
                current.request
            }
        }
        state = State.Terminal(request)
        return TavernDownloadTerminalEvent(
            request = request,
            success = false,
            message = message,
            notifyPage = notifyPage,
            notifyUser = notifyUser
        )
    }
}

internal class TavernDownloadTransfer(
    private val request: TavernDownloadRequest,
    private val output: OutputStream
) {
    private var nextSequence = 0
    private var writtenBytes = 0L
    private var closed = false

    fun append(sequence: Int, encodedChunk: String) {
        check(!closed) { "transfer is already closed" }
        require(sequence == nextSequence) { "unexpected chunk sequence" }
        require(encodedChunk.length <= MAX_BASE64_CHUNK_CHARS) { "chunk is too large" }

        val decoded = Base64.getDecoder().decode(encodedChunk)
        val nextTotal = Math.addExact(writtenBytes, decoded.size.toLong())
        if (request.expectedBytes >= 0) {
            require(nextTotal <= request.expectedBytes) { "received more data than declared" }
        }

        output.write(decoded)
        writtenBytes = nextTotal
        nextSequence += 1
    }

    fun finish(sequence: Int, reportedBytes: Long) {
        check(!closed) { "transfer is already closed" }
        require(sequence == nextSequence) { "missing export chunk" }
        if (reportedBytes >= 0) require(reportedBytes == writtenBytes) { "reported size mismatch" }
        if (request.expectedBytes >= 0) {
            require(request.expectedBytes == writtenBytes) { "export size mismatch" }
        }
        output.flush()
        output.close()
        closed = true
    }

    fun abort() {
        if (closed) return
        runCatching { output.close() }
        closed = true
    }

    companion object {
        private const val MAX_BASE64_CHUNK_CHARS = 512 * 1024
    }
}

object TavernDownloadFiles {
    private val invalidFileNameCharacters = Regex("[\\u0000-\\u001f\\u007f/\\\\:*?\"<>|]")
    private val validMimeType = Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$")
    private val validTransferId = Regex("^[A-Za-z0-9_-]{8,80}$")

    fun sanitizeFileName(requestedName: String?, mimeType: String?): String {
        val normalized = requestedName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(invalidFileNameCharacters, "_")
            ?.trim()
            ?.trim('.')
            ?.take(180)
            .orEmpty()
        return normalized.ifBlank { "sillytavern-export.${defaultExtension(mimeType)}" }
    }

    fun normalizeMimeType(mimeType: String?): String {
        val normalized = mimeType?.substringBefore(';')?.trim().orEmpty()
        return normalized.takeIf(validMimeType::matches) ?: "application/octet-stream"
    }

    fun isValidTransferId(id: String): Boolean = validTransferId.matches(id)

    fun parseByteCount(value: String?): Long =
        value?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L

    fun isLoopbackHttpUrl(value: String?): Boolean {
        val origin = parseOrigin(value) ?: return false
        return origin.scheme == "http" && origin.host in LOOPBACK_HOSTS
    }

    fun sameOrigin(configuredUrl: String?, pageUrl: String?): Boolean {
        val configured = parseOrigin(configuredUrl) ?: return false
        val page = parseOrigin(pageUrl) ?: return false
        val sameHost = configured.host == page.host ||
            (configured.host in LOOPBACK_HOSTS && page.host in LOOPBACK_HOSTS)
        val schemeMatches = configured.scheme == page.scheme ||
            (configured.scheme == "http" && page.scheme == "https" && httpUpgradePortMatches(configured.port, page.port))
        val portMatches = configured.port == page.port ||
            (configured.scheme == "http" && page.scheme == "https" && httpUpgradePortMatches(configured.port, page.port))
        return schemeMatches && sameHost && portMatches
    }

    private fun parseOrigin(value: String?): WebOrigin? {
        val uri = runCatching { URI(value ?: return null) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        val port = when {
            uri.port >= 0 -> uri.port
            scheme == "http" -> 80
            else -> 443
        }
        return WebOrigin(scheme, host, port)
    }

    private fun defaultExtension(mimeType: String?): String = when (normalizeMimeType(mimeType)) {
        "application/json" -> "json"
        "application/zip" -> "zip"
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "text/plain" -> "txt"
        "text/csv" -> "csv"
        else -> "bin"
    }

    private fun httpUpgradePortMatches(configuredPort: Int, pagePort: Int): Boolean =
        configuredPort == pagePort || (configuredPort == 80 && pagePort == 443)

    private data class WebOrigin(val scheme: String, val host: String, val port: Int)

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
}
