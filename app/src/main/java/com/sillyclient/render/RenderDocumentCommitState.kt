package com.sillyclient.render

internal class RenderDocumentCommitState {
    @Volatile
    var currentToken: Long = 0L
        private set

    @Volatile
    private var committedToken: Long? = null

    @Synchronized
    fun advance(): Long {
        currentToken++
        committedToken = null
        return currentToken
    }

    @Synchronized
    fun markCommitted(token: Long) {
        if (token == currentToken) committedToken = token
    }

    fun isCommitted(): Boolean = committedToken == currentToken
}
