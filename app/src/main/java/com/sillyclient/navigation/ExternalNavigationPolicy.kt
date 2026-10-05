package com.sillyclient.navigation

import java.net.URI
import java.util.Locale

object ExternalNavigationPolicy {
    enum class Decision { INTERNAL, EXTERNAL, BLOCK }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    fun externalUrl(value: String): String {
        val raw = value.trim()
        require(Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
            "Only absolute HTTP(S) web addresses are supported"
        }
        require(raw.none { it <= ' ' || it == '\u007f' || it == '\\' }) { "Invalid web address" }
        val uri = runCatching { URI(raw) }.getOrNull()
        require(uri != null) { "Invalid web address" }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in -1..65535) {
            "Web addresses must have a host and cannot contain credentials"
        }
        return uri.toASCIIString()
    }

    fun navigation(instanceUrl: String, targetUrl: String, isMainFrame: Boolean): Decision {
        if (!isMainFrame) return Decision.INTERNAL
        val instanceOrigin = origin(instanceUrl)
        // Blob exports remain owned by the existing download bridge, never the browser.
        if (targetUrl.startsWith("blob:", ignoreCase = true)) {
            return if (instanceOrigin != null && origin(targetUrl.substring(5)) == instanceOrigin) {
                Decision.INTERNAL
            } else Decision.BLOCK
        }
        val target = runCatching { externalUrl(targetUrl) }.getOrNull() ?: return Decision.BLOCK
        return if (instanceOrigin == origin(target)) Decision.INTERNAL else Decision.EXTERNAL
    }

    private fun origin(value: String): Origin? = runCatching {
        val uri = URI(externalUrl(value))
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        Origin(scheme, uri.host.lowercase(Locale.ROOT), if (uri.port == -1) {
            if (scheme == "https") 443 else 80
        } else uri.port)
    }.getOrNull()
}
