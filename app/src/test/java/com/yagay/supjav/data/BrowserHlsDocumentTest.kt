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
        assertTrue(html.contains("lastTap && now-lastTap.time<=330"))
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
        assertTrue(html.contains("video.playbackRate=boostRate"))
        assertTrue(html.contains("previousHoldRate"))
        assertTrue(html.contains("y<box.height*.30 || y>box.height*.70"))
        assertTrue(html.contains("singleTapTimer"))
        assertTrue(html.contains("onSurfaceTap();"))
        assertTrue(html.contains("skip(side==='left'?-seekAmount:seekAmount)"))
        assertFalse(html.contains("lastTapTime=0"))
    }

    @Test fun gesturesUseSmallNonBlockingFeedbackInsteadOfFullscreenToasts() {
        val html = requireNotNull(BrowserHlsDocument.render(PlaybackSource(
            "https://cdn.example.com/stream.m3u8?secret=not-for-log",
            "application/x-mpegURL", "https://evsishere.xyz/embed/abc"
        )))
        assertTrue(html.contains("id=\"playFlash\""))
        assertTrue(html.contains("id=\"gestureFeedback\""))
        assertTrue(html.contains("pointer-events:none"))
        assertTrue(html.contains("playbackFlash(willPlay)"))
        assertTrue(html.contains("subtleFeedback((seconds>0"))
        assertTrue(html.contains("subtleFeedback(boostRate+'×',side,true)"))
        assertTrue(html.contains("clearSubtleFeedback()"))
        assertFalse(html.contains("message('2× 倍速播放"))
        assertFalse(html.contains("message('已恢复"))
        assertFalse(html.contains("secret=not-for-log"))
    }

    @Test fun firstTapRevealsControlsAndPausedIndicatorPersists() {
        val html = requireNotNull(BrowserHlsDocument.render(PlaybackSource(
            "https://cdn.example.com/hls/master.m3u8?private=yes",
            "application/x-mpegURL", "https://evsishere.xyz/embed/a"
        )))
        assertTrue(html.contains("function onSurfaceTap()"))
        assertTrue(html.contains("if(chrome.classList.contains('hidden'))"))
        assertTrue(html.contains("chromeVisible(true);"))
        assertTrue(html.contains("onSurfaceTap();"))
        assertTrue(html.contains("id=\"pausedIndicator\""))
        assertTrue(html.contains("pausedIndicator.hidden = !video.paused"))
        assertTrue(html.contains(".actions{display:flex"))
        assertFalse(html.contains("private=yes"))
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

    @Test fun hlsQualityPickerReadsActualManifestAndPreservesSource() {
        val source = PlaybackSource(
            "https://cdn.example.com/hls4/master.m3u8?private=abc",
            "application/x-mpegURL", "https://evsishere.xyz/e/a"
        )
        val html = requireNotNull(BrowserHlsDocument.render(source, 0L, "p1080"))
        assertTrue(html.contains("var qualityMode = 'p1080'"))
        assertTrue(html.contains("id=\"quality\""))
        assertTrue(html.contains("id=\"qualityMenu\""))
        assertTrue(html.contains("player.levels"))
        assertTrue(html.contains("Hls.Events.MANIFEST_PARSED"))
        assertTrue(html.contains("player.nextLevel"))
        assertTrue(html.contains("state('quality:'+mode)"))
        assertTrue(html.contains("heights.length<=1"))
        assertFalse(html.contains(source.url))
    }

    @Test fun defaultsApplyToEmbeddedHlsWithoutChangingSignedUrl() {
        val source = PlaybackSource(
            "https://media.example.com/hls2/master.m3u8?token=secret",
            "application/x-mpegURL", "https://evsishere.xyz/e/1"
        )
        val html = requireNotNull(BrowserHlsDocument.render(
            source, 0L, "highest",
            defaultSpeed = 1.5f, defaultFill = true,
            skipSeconds = 30, holdSpeed = 3f, autoPlay = false
        ))
        assertTrue(html.contains("object-fit:cover"))
        assertTrue(html.contains("var seekAmount = 30"))
        assertTrue(html.contains("var boostRate = 3.0"))
        assertTrue(html.contains("var autoStart = false"))
        assertTrue(html.contains("var defaultRate = 1.5"))
        assertTrue(html.contains("skip(-seekAmount)"))
        assertTrue(html.contains("id=\"aspect\""))
        assertFalse(html.contains("token=secret"))
    }

    @Test fun hiddenControlsSupportFullSurfaceSeekingAndSideLevels() {
        val html = requireNotNull(BrowserHlsDocument.render(PlaybackSource(
            "https://cdn.example.com/master.m3u8?secret=hidden",
            "application/x-mpegURL", "https://evsishere.xyz/embed/abc"
        ), initialBrightness = 0.35f, initialVolume = 0.65f))
        assertTrue(html.contains("id=\"gestureSeek\""))
        assertTrue(html.contains("id=\"gestureLevel\""))
        assertTrue(html.contains("chrome.classList.contains('hidden') ||"))
        assertTrue(html.contains("g.mode='seek'"))
        assertTrue(html.contains("g.mode=g.x<g.width/2?'brightness':'volume'"))
        assertTrue(html.contains("SUPJAV_HLS_LEVEL:"))
        assertTrue(html.contains("var brightnessValue = 0.35"))
        assertTrue(html.contains("var volumeValue = 0.65"))
        assertTrue(html.contains("updateGestureSeek(g.preview)"))
        assertFalse(html.contains("secret=hidden"))
    }


    @Test fun cdControlsAreInsideHlsVideoAndDistinctFromLinePicker() {
        val source = PlaybackSource(
            "https://media.example.com/hls2/master.m3u8?token=hidden",
            "application/x-mpegURL",
            "https://evsishere.xyz/embed/a"
        )
        val html = requireNotNull(BrowserHlsDocument.render(
            source, availableParts = listOf("CD1", "CD2"), activePart = "CD1"
        ))
        assertTrue(html.contains("id=\"cdRow\""))
        assertTrue(html.contains("data-part=\"CD1\" disabled"))
        assertTrue(html.contains("data-part=\"CD2\""))
        assertTrue(html.contains("state('part:'+part)"))
        assertTrue(html.contains("id=\"lines\""))
        assertFalse(html.contains("token=hidden"))

        val ordinary = requireNotNull(BrowserHlsDocument.render(source,
            availableParts = listOf("CD1")))
        assertFalse(ordinary.contains("id=\"cdRow\""))
    }


    @Test fun playlistButtonSitsImmediatelyBeforeBottomFullscreenControl() {
        val source = PlaybackSource(
            "https://cdn.example.com/hls2/master.m3u8",
            "application/x-mpegURL",
            "https://evsishere.xyz/embed/test"
        )
        val html = requireNotNull(BrowserHlsDocument.render(source))
        val actionsStart = html.indexOf("<div class=\"actions\">")
        val playlistIndex = html.indexOf("id=\"playlist\"")
        val fullscreenIndex = html.indexOf("id=\"rotateBottom\"")
        assertTrue(actionsStart >= 0)
        assertTrue(playlistIndex > actionsStart)
        assertTrue(fullscreenIndex > playlistIndex)
        assertEquals(1, Regex("""id="playlist"""").findAll(html).count())
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
