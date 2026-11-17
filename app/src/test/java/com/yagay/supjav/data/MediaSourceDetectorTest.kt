package com.yagay.supjav.data

import org.junit.Assert.*
import org.junit.Test

class MediaSourceDetectorTest {
    private val site = "https://supjav.com/472992.html"

    @Test fun identifiesMasterTxtHlsFromDownloadSource() {
        val source = MediaSourceDetector.fromMediaUrl(
            "https://cdn.example.com/hls3/master.txt?Signature=abc",
            "https://evsishere.xyz/e/player"
        )
        assertNotNull(source)
        assertEquals("application/x-mpegURL", source?.mimeType)
        assertEquals("https://evsishere.xyz/e/player", source?.referer)
    }

    @Test fun identifiesSignedMp4AndStandardHls() {
        assertEquals("video/mp4", MediaSourceDetector.fromMediaUrl(
            "https://cdn.example.com/movie.mp4?sign=abc", site
        )?.mimeType)
        assertEquals("application/x-mpegURL", MediaSourceDetector.fromMediaUrl(
            "https://cdn.example.com/playlist.m3u8?x=1", site
        )?.mimeType)
    }

    @Test fun refusesIframeAdsAndUnsafeAddresses() {
        assertNull(MediaSourceDetector.fromMediaUrl(
            "https://cdn.example.com/ads/preroll.mp4", site))
        assertNull(MediaSourceDetector.fromMediaUrl(
            "https://127.0.0.1/video.mp4", site))
        assertNull(MediaSourceDetector.fromMediaUrl(
            "http://cdn.example.com/movie.mp4", site))
        assertNull(MediaSourceDetector.fromMediaUrl(
            "https://cdn.example.com/player/embed.mp4.html", site))
    }

    @Test fun onlyTrustedPlayerFramesMaySubmitMedia() {
        val session = SelectedMediaSession(site)
        val video = "https://cdn.example.com/hls3/master.txt"
        assertNull(session.fromPageReport(
            "https://advert.example", "https://advert.example/landing", video))
        assertTrue(session.trustFrame(site, "https://lk1.supremejav.com/supjav.php?c=abc"))
        assertTrue(session.trustFrame(
            "https://lk1.supremejav.com/supjav.php?c=abc",
            "https://evsishere.xyz/e/player"))
        assertNotNull(session.fromPageReport(
            "https://evsishere.xyz", "https://evsishere.xyz/e/player", video))
        assertNull(session.fromNetworkRequest(video, "https://advert.example/landing"))
        assertNotNull(session.fromNetworkRequest(video, "https://evsishere.xyz/e/player"))
    }

    @Test fun wrongFrameOriginAndHttpSubframesRejected() {
        val session = SelectedMediaSession(site)
        assertFalse(session.trustFrame(
            "https://random.example/page", "https://evil.example/e/watch"))
        assertFalse(session.trustFrame(
            site, "http://evil.example/player"))
        assertNull(session.fromPageReport(
            "https://evil.example", site,
            "https://cdn.example.com/movie.mp4"))
    }
}
