package com.sillyclient.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernResourcePolicyTest {
    private val target = "http://127.0.0.1:8000/"

    @Test
    fun preservesTheStaticShortcutForAnOwnedSameOriginAsset() {
        assertTrue(TavernResourcePolicy.allows(target, "${target}dist/app.js", "GET", false, true))
    }

    @Test
    fun theMainDocumentAlwaysUsesTheRealBackend() {
        for (path in listOf("", "index.html", "login", "script.js")) {
            assertFalse(TavernResourcePolicy.allows(target, target + path, "GET", true, true))
        }
    }

    @Test
    fun neverServesStaleFilesForARemoteDifferentPortOrUnownedProcess() {
        for (url in listOf("http://127.0.0.1:8001/script.js", "http://localhost:8000/script.js",
            "https://127.0.0.1:8000/script.js", "http://example.org/script.js",
            "blob:${target}synthetic", "http://user:password@127.0.0.1:8000/script.js")) {
            assertFalse(TavernResourcePolicy.allows(target, url, "GET", false, true))
        }
        assertFalse(TavernResourcePolicy.allows(target, "${target}script.js", "GET", false, false))
        assertFalse(TavernResourcePolicy.allows(target, "${target}api/settings/set", "POST", false, true))
    }
}
