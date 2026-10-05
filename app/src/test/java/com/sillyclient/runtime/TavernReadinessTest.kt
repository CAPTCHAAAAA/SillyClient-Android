package com.sillyclient.runtime

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernReadinessTest {
    private class Response(
        url: URL, private val code: Int, private val type: String, private val body: String = "",
        private val location: String? = null
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getContentType() = type
        override fun getHeaderField(name: String): String? = if (name == "Location") location else null
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }

    @Test
    fun requiresTheRealDocumentAndCsrfEndpointAndClosesEveryConnection() {
        val clients = mutableListOf<Response>()
        val probe = TavernReadiness { url ->
            Response(url, 200, if (url.path == "/") "text/html; charset=utf-8" else "application/json",
                if (url.path == "/") "<html></html>" else """{"token":"synthetic"}""")
                .also(clients::add)
        }
        assertTrue(probe.probe("http://127.0.0.1:8000/").ready)
        assertEquals(listOf("/", "/csrf-token"), clients.map { it.url.path })
        assertTrue(clients.all { it.disconnected && !it.instanceFollowRedirects && !it.useCaches })
    }

    @Test
    fun supportsIpv6OnlyInstances() {
        val probe = TavernReadiness { url ->
            Response(url, 200, if (url.path == "/") "text/html" else "application/json", """{"token":"ipv6"}""")
        }
        assertTrue(probe.probe("http://[::1]:8012/").ready)
    }

    @Test
    fun unauthorizedForbiddenMissingAndNonHtmlListenersAreNotReady() {
        for (code in listOf(401, 403, 404, 500)) {
            val result = TavernReadiness { Response(it, code, "text/html") }.probe("http://localhost:8001/")
            assertFalse(result.ready)
            assertEquals(code, result.status)
            assertEquals(code in setOf(401, 403), result.terminal)
        }
        assertFalse(TavernReadiness { Response(it, 200, "application/json") }.probe("http://localhost:8001/").ready)
    }

    @Test
    fun rejectsMissingMalformedEmptyNonStringAndOversizedTokens() {
        for (body in listOf("{}", "not-json", """{"token":""}""", """{"token":123}""",
            """{"token":"${"a".repeat(9_000)}"}""")) {
            val result = TavernReadiness { url ->
                Response(url, 200, if (url.path == "/") "text/html" else "application/json", body)
            }.probe("http://127.0.0.1:8000/")
            assertFalse(result.ready)
            assertEquals(TavernReadiness.Failure.CSRF, result.failure)
        }
    }

    @Test
    fun csrfHtmlAndErrorResponsesCannotMasqueradeAsAnInitializedTavern() {
        for (code in listOf(200, 401, 403, 404, 500)) {
            val result = TavernReadiness { url ->
                Response(url, if (url.path == "/") 200 else code, "text/html", """{"token":"synthetic"}""")
            }.probe("http://127.0.0.1:8000/")
            assertFalse(result.ready)
        }
    }

    @Test
    fun permitsARealSameOriginLoginWithoutBypassingUserAccounts() {
        assertTrue(TavernReadiness { Response(it, 302, "text/html", location = "/login") }
            .probe("http://127.0.0.1:8000/").ready)
        for (location in listOf("http://example.org/login", "http://127.0.0.1:8001/login",
            "http://user:password@127.0.0.1:8000/login", "/other", "")) {
            assertFalse(TavernReadiness { Response(it, 302, "text/html", location = location) }
                .probe("http://127.0.0.1:8000/").ready)
        }
    }

    @Test
    fun unreachableBackendAndRemoteOrCredentialUrlsAreRejected() {
        assertFalse(TavernReadiness { throw java.io.IOException("synthetic offline") }
            .probe("http://127.0.0.1:8000/").ready)
        for (url in listOf("https://127.0.0.1:8000/", "http://example.org:8000/",
            "http://user:password@127.0.0.1:8000/", "http://127.0.0.1/")) {
            assertThrows(IllegalArgumentException::class.java) { TavernReadiness().probe(url) }
        }
    }
}
