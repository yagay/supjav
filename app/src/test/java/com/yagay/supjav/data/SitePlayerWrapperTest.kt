package com.yagay.supjav.data

import org.junit.Assert.*
import org.junit.Test

class SitePlayerWrapperTest {
    @Test fun buildsLegacyIframeUrlOnlyForHexTokens() {
        val token = "a1".repeat(64)
        val url = SitePlayerWrapper.fromLegacyToken(token)
        assertNotNull(url)
        assertEquals("https://lk1.supremejav.com/supjav.php?c=" + token.reversed(), url)
        assertTrue(SitePlayerWrapper.isSiteWrapper(requireNotNull(url)))
        assertNull(SitePlayerWrapper.fromLegacyToken("https://example.com/video.m3u8"))
        assertNull(SitePlayerWrapper.fromLegacyToken("short-token"))
        assertNull(SitePlayerWrapper.fromLegacyToken("a1".repeat(64) + "!"))
    }

    @Test fun rejectsUntrustedWrapperHostAndNonHttps() {
        assertFalse(SitePlayerWrapper.isSiteWrapper(
            "https://supremejav.com.evil.example/supjav.php?c=foo"))
        assertFalse(SitePlayerWrapper.isSiteWrapper(
            "http://lk1.supremejav.com/supjav.php?c=foo"))
        assertFalse(SitePlayerWrapper.isSiteWrapper(
            "https://lk1.supremejav.com/other.php?c=foo"))
    }
}
