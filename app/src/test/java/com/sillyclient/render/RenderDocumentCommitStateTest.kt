package com.sillyclient.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderDocumentCommitStateTest {
    @Test
    fun navigationTokenIsNotCommittedUntilTheWebViewSignalsIt() {
        val state = RenderDocumentCommitState()
        val token = state.advance()

        assertEquals(1L, token)
        assertFalse(state.isCommitted())

        state.markCommitted(token)

        assertTrue(state.isCommitted())
    }

    @Test
    fun staleCommitCannotCommitTheCurrentDocument() {
        val state = RenderDocumentCommitState()
        val staleToken = state.advance()
        state.advance()

        state.markCommitted(staleToken)

        assertFalse(state.isCommitted())
    }

    @Test
    fun aNewNavigationInvalidatesThePriorCommit() {
        val state = RenderDocumentCommitState()
        state.markCommitted(state.currentToken)
        assertTrue(state.isCommitted())

        state.advance()

        assertFalse(state.isCommitted())
    }
}
