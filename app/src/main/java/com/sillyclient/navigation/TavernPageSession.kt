package com.sillyclient.navigation

/** A ready server does not imply its WebView has loaded the current instance. */
class TavernPageSession {
    private var loadedInstanceId: String? = null
    private var loadedTargetUrl: String? = null

    fun ensureLoaded(
        instanceId: String?,
        targetUrl: String,
        currentPageUrl: String?,
        forceReload: Boolean = false,
        loadUrl: (String) -> Unit
    ): Boolean {
        val hasCurrentPage = !currentPageUrl.isNullOrBlank() &&
            ExternalNavigationPolicy.navigation(targetUrl, currentPageUrl, true) ==
                ExternalNavigationPolicy.Decision.INTERNAL
        if (!forceReload && loadedInstanceId == instanceId &&
            loadedTargetUrl == targetUrl && hasCurrentPage) return false

        loadUrl(targetUrl)
        loadedInstanceId = instanceId
        loadedTargetUrl = targetUrl
        return true
    }

    fun reset() {
        loadedInstanceId = null
        loadedTargetUrl = null
    }
}
