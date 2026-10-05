package com.sillyclient.navigation

/** Only owned, same-origin subresources may use the native static shortcut. */
object TavernResourcePolicy {
    fun allows(
        targetUrl: String,
        requestUrl: String,
        method: String,
        mainFrame: Boolean,
        ownedServerReady: Boolean
    ): Boolean = ownedServerReady && !mainFrame && method.equals("GET", ignoreCase = true) &&
        ExternalNavigationPolicy.navigation(targetUrl, requestUrl, true) == ExternalNavigationPolicy.Decision.INTERNAL &&
        runCatching { ExternalNavigationPolicy.externalUrl(requestUrl) }.isSuccess
}
