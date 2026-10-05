package com.sillyclient.runtime

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject

/** Probe the backend rather than a native static document or any listener on the port. */
class TavernReadiness(
    private val timeoutMillis: Int = 3_000,
    private val connection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    data class Result(val ready: Boolean, val failure: Failure? = null, val status: Int? = null) {
        val terminal: Boolean get() = failure == Failure.ACCESS_DENIED
    }

    enum class Failure { CONNECTION, ACCESS_DENIED, DOCUMENT, CSRF }

    fun probe(address: String): Result {
        val origin = URI(address)
        require(origin.scheme == "http" && origin.host?.removeSurrounding("[", "]") in setOf("127.0.0.1", "localhost", "::1") &&
            origin.rawUserInfo == null && origin.port in 1..65535) { "Invalid local readiness URL" }
        val document = request(origin.resolve("/").toURL())
        if (document.status in setOf(401, 403)) return Result(false, Failure.ACCESS_DENIED, document.status)
        if (document.status in setOf(302, 303, 307, 308)) {
            val login = runCatching { origin.resolve(document.location ?: "").normalize() }.getOrNull()
            if (login != null && sameOrigin(origin, login) && login.path == "/login" &&
                login.rawUserInfo == null) return Result(true)
            return Result(false, Failure.DOCUMENT, document.status)
        }
        if (document.status !in 200..299 || !document.contentType.startsWith("text/html")) {
            return Result(false, if (document.status == null) Failure.CONNECTION else Failure.DOCUMENT, document.status)
        }
        val csrf = request(origin.resolve("/csrf-token").toURL(), readJson = true)
        if (csrf.status in setOf(401, 403)) return Result(false, Failure.ACCESS_DENIED, csrf.status)
        val tokenPresent = csrf.status in 200..299 && csrf.contentType.startsWith("application/json") &&
            runCatching { JSONObject(csrf.body).opt("token") as? String }.getOrNull()?.isNotBlank() == true
        return if (tokenPresent) Result(true) else Result(false, Failure.CSRF, csrf.status)
    }

    private data class Response(
        val status: Int? = null,
        val contentType: String = "",
        val location: String? = null,
        val body: String = ""
    )

    private fun request(url: URL, readJson: Boolean = false): Response {
        var client: HttpURLConnection? = null
        return try {
            client = connection(url)
            client.connectTimeout = timeoutMillis
            client.readTimeout = timeoutMillis
            client.instanceFollowRedirects = false
            client.useCaches = false
            client.setRequestProperty("Accept", if (readJson) "application/json" else "text/html")
            val code = client.responseCode
            val contentType = client.contentType.orEmpty().lowercase()
            val body = if (readJson && code in 200..299 && contentType.startsWith("application/json")) {
                client.inputStream.use { input ->
                    val buffer = ByteArray(8_193)
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count > 8_192) "" else String(buffer, 0, count, Charsets.UTF_8)
                }
            } else ""
            Response(code, contentType, client.getHeaderField("Location"), body)
        } catch (_: Exception) {
            Response()
        } finally {
            client?.disconnect()
        }
    }

    private fun sameOrigin(first: URI, second: URI): Boolean =
        first.scheme == second.scheme && first.host.equals(second.host, ignoreCase = true) &&
            first.port == second.port
}
