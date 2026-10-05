package com.sillyclient.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernPageSessionTest {
    private val base = "http://127.0.0.1:8000/"

    @Test fun firstReturnAfterCreateLoadsTheReadyInstance() {
        val session = TavernPageSession()
        val loads = mutableListOf<String>()
        assertTrue(session.ensureLoaded("new-instance", base, null, loadUrl = loads::add))
        assertEquals(listOf(base), loads)
    }

    @Test fun emptyAndBlankDocumentsCannotBeReused() {
        for (page in listOf(null, "", " ", "about:blank", "about:blank#empty", "chrome-error://chromewebdata/")) {
            val session = TavernPageSession()
            val loads = mutableListOf<String>()
            session.ensureLoaded("instance", base, null, loadUrl = loads::add)
            assertTrue(session.ensureLoaded("instance", base, page, loadUrl = loads::add))
            assertEquals(listOf(base, base), loads)
        }
    }

    @Test fun returningToTheSameLoadedSessionPreservesItsPageAndChatState() {
        val session = TavernPageSession()
        val loads = mutableListOf<String>()
        session.ensureLoaded("instance", base, null, loadUrl = loads::add)
        assertFalse(session.ensureLoaded("instance", base, "${base}chat#current", loadUrl = loads::add))
        assertEquals(listOf(base), loads)
    }

    @Test fun differentInstancesOnTheSamePortDoNotReuseTheOldDocument() {
        val session = TavernPageSession()
        val loads = mutableListOf<String>()
        session.ensureLoaded("first", base, null, loadUrl = loads::add)
        assertTrue(session.ensureLoaded("second", base, base, loadUrl = loads::add))
        assertEquals(listOf(base, base), loads)
    }

    @Test fun changingPortsLoadsTheCurrentTargetInsteadOfShowingTheOldPage() {
        val session = TavernPageSession()
        val loads = mutableListOf<String>()
        val next = "http://127.0.0.1:9123/"
        session.ensureLoaded("instance", base, null, loadUrl = loads::add)
        assertTrue(session.ensureLoaded("instance", next, base, loadUrl = loads::add))
        assertEquals(listOf(base, next), loads)
    }

    @Test fun explicitEntryStillReloadsAndResetRequiresAnewLoad() {
        val session = TavernPageSession()
        val loads = mutableListOf<String>()
        session.ensureLoaded("instance", base, null, loadUrl = loads::add)
        assertTrue(session.ensureLoaded("instance", base, base, true, loads::add))
        session.reset()
        assertTrue(session.ensureLoaded("instance", base, base, loadUrl = loads::add))
        assertEquals(listOf(base, base, base), loads)
    }

    @Test fun failedLoadingDoesNotMarkTheNewSessionAsLoaded() {
        val session = TavernPageSession()
        assertThrows(IllegalStateException::class.java) {
            session.ensureLoaded("instance", base, null) { throw IllegalStateException("Load failed") }
        }
        val loads = mutableListOf<String>()
        assertTrue(session.ensureLoaded("instance", base, base, loadUrl = loads::add))
        assertEquals(listOf(base), loads)
    }

    @Test fun anUnexpectedExternalDocumentIsNotTreatedAsTheTavernSession() {
        val session = TavernPageSession()
        val loads = mutableListOf<String>()
        session.ensureLoaded("instance", base, null, loadUrl = loads::add)
        assertTrue(session.ensureLoaded("instance", base, "https://example.test/", loadUrl = loads::add))
        assertEquals(listOf(base, base), loads)
    }
}
