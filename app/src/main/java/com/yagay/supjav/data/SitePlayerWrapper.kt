package com.yagay.supjav.data

import java.net.URI

/**
 * The observed Supjav wrapper convention for opaque ASCII server tokens.
 * Prefer the site's current JumpChain function, which may change independently.
 * Only used inside the site's iframe browsing context, never as a top-level HTTP GET.
 */
internal object SitePlayerWrapper {
    fun fromLegacyToken(rawToken: String): String? {
        val token = rawToken.trim()
        if (!token.matches(Regex("[A-Za-z0-9_-]{3,512}"))) return null
        return "https://lk1.supremejav.com/supjav.php?c=" + token.reversed()
    }

    fun isSiteWrapper(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return@runCatching false
        uri.scheme == "https" && uri.userInfo == null &&
            (host == "supremejav.com" || host.endsWith(".supremejav.com")) &&
            uri.path == "/supjav.php" && uri.rawQuery.orEmpty().contains("c=")
    }.getOrDefault(false)
}
