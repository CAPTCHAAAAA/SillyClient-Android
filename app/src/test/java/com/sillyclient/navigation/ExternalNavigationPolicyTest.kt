package com.sillyclient.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExternalNavigationPolicyTest {
    private val base = "http://127.0.0.1:8000/"

    @Test fun externalAddressesAreLimitedToCredentialFreeHttpWithHosts() {
        for (url in listOf("https://github.com/N0VI028/JS-Slash-Runner", "HTTP://EXAMPLE.TEST:80/path?q=one#two")) {
            assertEquals(url, ExternalNavigationPolicy.externalUrl(url))
        }
        for (url in listOf(
            "", "https://", "https:///missing-host", "http:example.test", "//example.test",
            "javascript:alert(1)", "data:text/html,test", "file:///private", "intent://example.test",
            "https://alice:secret@example.test", "https://alice@example.test", "https://@example.test",
            "https://example.test:65536/", "https://example.test/\npath", "https://example.test\\path"
        )) {
            assertThrows(url, Exception::class.java) { ExternalNavigationPolicy.externalUrl(url) }
        }
    }

    @Test fun sameOriginNavigationRetainsTheInstance() {
        for (url in listOf("$base?query=1", "${base}settings#tab", "http://127.0.0.1:8000/chat")) {
            assertEquals(ExternalNavigationPolicy.Decision.INTERNAL, ExternalNavigationPolicy.navigation(base, url, true))
        }
        assertEquals(ExternalNavigationPolicy.Decision.INTERNAL,
            ExternalNavigationPolicy.navigation("https://EXAMPLE.TEST/", "https://example.test:443/settings", true))
        assertEquals(ExternalNavigationPolicy.Decision.INTERNAL,
            ExternalNavigationPolicy.navigation("http://example.test:80/", "http://example.test/settings", true))
        assertEquals(ExternalNavigationPolicy.Decision.INTERNAL,
            ExternalNavigationPolicy.navigation("http://[::1]:8000/", "http://[::1]:8000/settings", true))
    }

    @Test fun differentSchemePortAndHostAreExternalIncludingRedirectTargets() {
        for (url in listOf(
            "https://127.0.0.1:8000/", "http://127.0.0.1:8001/", "http://localhost:8000/",
            "https://github.com/CAPTCHAAAAA/SillyClient", "https://example.test/login"
        )) {
            assertEquals(url, ExternalNavigationPolicy.Decision.EXTERNAL,
                ExternalNavigationPolicy.navigation(base, url, true))
        }
    }

    @Test fun subframesAreNeverReclassifiedAsExternalNavigation() {
        for (url in listOf("https://example.test/api", "https://example.test/image.png", "javascript:test()", "file:///private")) {
            assertEquals(ExternalNavigationPolicy.Decision.INTERNAL,
                ExternalNavigationPolicy.navigation(base, url, false))
        }
    }

    @Test fun malformedTopLevelNavigationIsBlockedWithoutDelegatingToAnyProtocolHandler() {
        for (url in listOf("https://alice:secret@example.test", "file:///private", "intent://browser", "data:text/html,test")) {
            assertEquals(ExternalNavigationPolicy.Decision.BLOCK, ExternalNavigationPolicy.navigation(base, url, true))
        }
    }

    @Test fun blobDownloadsStayInsideTheExistingDownloadPath() {
        assertEquals(ExternalNavigationPolicy.Decision.INTERNAL,
            ExternalNavigationPolicy.navigation(base, "blob:${base}synthetic-id", true))
        for (url in listOf("blob:https://example.test/id", "blob:null/id", "blob:not-a-url", "blob:${base}bad path")) {
            assertEquals(ExternalNavigationPolicy.Decision.BLOCK,
                ExternalNavigationPolicy.navigation(base, url, true))
        }
    }
}
