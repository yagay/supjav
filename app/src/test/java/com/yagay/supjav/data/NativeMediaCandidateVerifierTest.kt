package com.yagay.supjav.data

import com.yagay.supjav.model.PlaybackSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeMediaCandidateVerifierTest {
    private val manifest = PlaybackSource(
        "https://cdn.example.com/hls3/master.txt?sig=signed",
        "application/x-mpegURL",
        "https://evsishere.xyz/e/player"
    )

    private class FakeGateway(
        private val response: MediaProbe
    ) : MediaGateway {
        var calls = 0
        var sawHls: Boolean? = null
        override fun page(url: String, referer: String): GatewayPage =
            error("Candidate validation must not fetch an HTML page")
        override fun postJson(url: String, referer: String, json: String): GatewayPage =
            error("Candidate validation must not POST")
        override fun probe(url: String, referer: String, hls: Boolean): MediaProbe {
            calls++
            sawHls = hls
            return response
        }
    }

    @Test fun browserDeclaredHls403MustNotBeAdvertisedForNativePlayback() = runBlocking {
        val gateway = FakeGateway(MediaProbe(false, 403, null, "http"))
        val result = NativeMediaCandidateVerifier(gateway).verify(manifest)
        assertTrue(result is NativeMediaCandidateVerifier.Result.Rejected)
        assertEquals(403, (result as NativeMediaCandidateVerifier.Result.Rejected).status)
        assertEquals(true, gateway.sawHls)
    }

    @Test fun verifiedHlsCanOfferNativePlaybackWithFinalUrl() = runBlocking {
        val moved = "https://cdn.example.com/hls3/master.m3u8?sig=signed"
        val gateway = FakeGateway(MediaProbe(true, 200, moved, "hls-ok"))
        val result = NativeMediaCandidateVerifier(gateway).verify(manifest)
        assertTrue(result is NativeMediaCandidateVerifier.Result.Ready)
        assertEquals(moved, (result as NativeMediaCandidateVerifier.Result.Ready).source.url)
        assertEquals(manifest.referer, result.source.referer)
        assertEquals(1, gateway.calls)
    }

    @Test fun html200AndUnverifiedMediaNeverOfferNativePlayback() = runBlocking {
        val gateway = FakeGateway(MediaProbe(false, 200, null, "non-media-response"))
        val result = NativeMediaCandidateVerifier(gateway).verify(manifest)
        assertTrue(result is NativeMediaCandidateVerifier.Result.Rejected)
        assertEquals("non-media-response",
            (result as NativeMediaCandidateVerifier.Result.Rejected).reason)
    }

    @Test fun unsafeUrlIsRejectedBeforeAnyNetworkRequest() = runBlocking {
        val gateway = FakeGateway(MediaProbe(true, 200, manifest.url, "hls-ok"))
        val unsafe = manifest.copy(url = "https://127.0.0.1/master.m3u8")
        val result = NativeMediaCandidateVerifier(gateway).verify(unsafe)
        assertTrue(result is NativeMediaCandidateVerifier.Result.Rejected)
        assertEquals(0, gateway.calls)
    }
}
