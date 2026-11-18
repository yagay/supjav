package com.yagay.supjav.desktop

import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.model.VideoServer
import org.junit.Assert.*
import org.junit.Test

class DesktopBrowserPlayerTest {
    private fun video(url: String) =
        VideoItem("472994", "Sample", detailUrl = url)

    @Test fun authenticSiteDetailCanBeLoadedInEmbeddedWebView() {
        assertEquals(
            "https://supjav.com/472994.html",
            DesktopBrowserPolicy.siteDetailUrl(video("https://supjav.com/472994.html"))
        )
        assertEquals(
            "https://www.supjav.com/472994.html",
            DesktopBrowserPolicy.siteDetailUrl(video("https://www.supjav.com/472994.html"))
        )
    }

    @Test fun unsafeOrUnrelatedPagesAreNeverEmbedded() {
        listOf(
            "http://supjav.com/472994.html",
            "https://supjav.com.evil.example/472994.html",
            "https://supjav.com@evil.example/472994.html",
            "https://supjav.com:444/472994.html",
            "https://supjav.com/login",
            "https://supjav.com/472994.html?redirect=https://evil.example"
        ).forEach { assertNull(DesktopBrowserPolicy.siteDetailUrl(video(it))) }
    }

    @Test fun siteLineSelectionAcceptsOnlyOpaqueAsciiTokens() {
        assertEquals("abc-XYZ_123", DesktopBrowserPolicy.safeSiteToken("abc-XYZ_123"))
        assertNull(DesktopBrowserPolicy.safeSiteToken("x' ; window.alert(1)"))
        assertNull(DesktopBrowserPolicy.safeSiteToken("https://other.example/path"))
        assertNull(DesktopBrowserPolicy.safeSiteToken("a"))
    }

    @Test fun javascriptHeavyLinesUseWebViewWhileNativeMirrorsKeepVlc() {
        fun server(name: String) = VideoServer(
            id = name, name = name, link = "safetoken",
            detailUrl = "https://supjav.com/472994.html"
        )
        assertTrue(DesktopBrowserPolicy.preferBrowser(server("EVS")))
        assertTrue(DesktopBrowserPolicy.preferBrowser(server("LUC")))
        assertFalse(DesktopBrowserPolicy.preferBrowser(server("ST")))
        assertFalse(DesktopBrowserPolicy.preferBrowser(server("VOE")))
        assertFalse(DesktopBrowserPolicy.preferBrowser(server("VAS")))
    }
}
