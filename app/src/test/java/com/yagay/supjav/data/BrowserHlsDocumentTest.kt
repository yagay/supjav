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

    @Test fun mobileControlsAreInsideVideoLayerAndSupportSeekingAndSpeed() {
        val source = PlaybackSource(
            "https://cdn.example.com/hls2/master.m3u8?sig=abc",
            "application/x-mpegURL",
            "https://luminscdn.xyz/e/player"
        )
        val html = requireNotNull(BrowserHlsDocument.render(source))
        assertTrue(html.contains("id=\"center\""))
        assertTrue(html.contains("id=\"progress\""))
        assertTrue(html.contains("id=\"back10\""))
        assertTrue(html.contains("id=\"forward10\""))
        assertTrue(html.contains("id=\"speed\""))
        assertTrue(html.contains("id=\"lines\""))
        assertTrue(html.contains("state('lines')"))
        assertTrue(html.contains("id=\"rotate\""))
        assertTrue(html.contains("id=\"back\""))
        assertTrue(html.contains("timeupdate"))
        assertTrue(html.contains("double") || html.contains("lastTapSide"))
        assertTrue(html.contains("speedList = [0.75,1,1.25,1.5,2]") ||
            html.contains("speedList=[0.75,1,1.25,1.5,2]"))
        assertTrue(html.contains("video.currentTime="))
        assertFalse(html.contains("id=\"video\" controls"))
        assertTrue(html.contains("SUPJAV_HLS_STATE:"))
    }

    @Test fun compactGesturesUseTapDoubleTapHoldAndBlankAreaSeek() {
        val html = requireNotNull(BrowserHlsDocument.render(PlaybackSource(
            "https://cdn.example.com/hls2/master.m3u8?token=protected",
            "application/x-mpegURL",
            "https://evsishere.xyz/embed/a"
        )))
        assertTrue(html.contains("height:3px"))
        assertTrue(html.contains("width:11px;height:11px"))
        assertTrue(html.contains("pointerdown"))
        assertTrue(html.contains("pointermove"))
        assertTrue(html.contains("pointerup"))
        assertTrue(html.contains("pointercancel"))
        assertTrue(html.contains("video.playbackRate=2"))
        assertTrue(html.contains("previousHoldRate"))
        assertTrue(html.contains("g.y<g.height*.30 || g.y>g.height*.70"))
        assertTrue(html.contains("singleTapTimer"))
        assertTrue(html.contains("toggle();"))
        assertTrue(html.contains("skip(side==='left'?-10:10)"))
        assertFalse(html.contains("lastTapTime=0"))
    }

    @Test fun resumedVideoEmitsOnlyBoundedProgressMetadata() {
        val html = requireNotNull(BrowserHlsDocument.render(PlaybackSource(
            "https://cdn.example.com/hls2/master.m3u8?token=sensitive",
            "application/x-mpegURL",
            "https://evsishere.xyz/embed/abc"
        ), resumePositionMs = 91_500L))
        assertTrue(html.contains("var pendingResumeSeconds = 91;"))
        assertTrue(html.contains("SUPJAV_HLS_PROGRESS:"))
        assertTrue(html.contains("applyResume()"))
        assertFalse(html.contains("token=sensitive"))
        assertTrue(html.contains("networkRecoveries<2"))
        assertTrue(html.contains("status!==403"))
        assertTrue(html.contains("mediaRecoveries<1"))
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
