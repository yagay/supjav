package com.yagay.supjav.data

import com.yagay.supjav.model.PlaybackSource
import org.junit.Assert.*
import org.junit.Test

class BrowserHlsDocumentTest {
    @Test fun signedUrlCannotInjectMarkupAndPlayerStaysScopedToSelectedOrigin() {
        val source = PlaybackSource(
            "https://media.example.com/hls2/master.m3u8?token=%3Cscript%3E&a=1",
            "application/x-mpegURL",
            "https://evsishere.xyz/embed/abc"
        )
        val html = BrowserHlsDocument.render(source)
        assertNotNull(html)
        assertTrue(html!!.contains("hls.js@1.6.15"))
        assertFalse(html.contains(source.url))
        assertTrue(html.contains("SUPJAV_HLS_STATE"))
        assertTrue(html.contains("atob("))
    }

    @Test fun rejectsMp4AndUnsafeHosts() {
        assertNull(BrowserHlsDocument.render(PlaybackSource(
            "https://media.example.com/a.mp4", "video/mp4",
            "https://evsishere.xyz/embed/a")))
        assertNull(BrowserHlsDocument.render(PlaybackSource(
            "https://127.0.0.1/master.m3u8", "application/x-mpegURL",
            "https://evsishere.xyz/embed/a")))
    }
}
