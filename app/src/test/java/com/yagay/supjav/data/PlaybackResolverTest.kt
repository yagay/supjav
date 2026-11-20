package com.yagay.supjav.data

import com.yagay.supjav.model.ResolveResult
import com.yagay.supjav.model.VideoServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackResolverTest {
    private val detail = "https://supjav.com/473013.html"

    private class FakeGateway : MediaGateway {
        val pages = mutableMapOf<String, GatewayPage>()
        val probes = mutableMapOf<String, MediaProbe>()
        var postResponse: GatewayPage? = null
        var apiUrl: String? = null
        var apiBody: String? = null
        val fetched = mutableListOf<String>()
        override fun page(url: String, referer: String): GatewayPage {
            fetched.add(url)
            return pages[url] ?: error("No page for " + url)
        }
        override fun probe(url: String, referer: String, hls: Boolean): MediaProbe =
            probes[url] ?: MediaProbe(false, 404, null, "not-found")
        override fun postJson(url: String, referer: String, json: String): GatewayPage {
            apiUrl = url
            apiBody = json
            return postResponse ?: error("Missing API page")
        }
    }

    @Test fun evsUsesOpaqueGatewayAndValidatesHlsBeforeMedia3() = runBlocking {
        val fake = FakeGateway()
        val token = "Ab12".repeat(32)
        val server = VideoServer("0", "EVS", token, detail)
        val wrapper = "https://lk1.supremejav.com/supjav.php?c=" + token.reversed()
        val page = "https://evsishere.xyz/e/abcd"
        val source = "https://cdn.example.com/hls3/master.txt?token=x"
        fake.pages[wrapper] = GatewayPage(page, 200, "text/html",
            "<script>var urlPlay='$source';</script>")
        fake.probes[source] = MediaProbe(true, 200, source, "hls-ok")
        val resolver = PlaybackResolver(SupjavSource())
        resolver.gateway = fake
        val result = resolver.resolve(server)
        assertTrue(result is ResolveResult.Playable)
        assertEquals(source, (result as ResolveResult.Playable).source.url)
        assertEquals("application/x-mpegURL", result.source.mimeType)
        assertEquals(page, result.source.referer)
        assertEquals(listOf(wrapper), fake.fetched)
    }

    @Test fun blockedHls2FallsThroughToWorkingHls4() = runBlocking {
        val fake = FakeGateway()
        val embed = "https://evsishere.xyz/embed/video"
        val hls2 = "https://blocked.example.com/hls2/master.m3u8?s=1"
        val hls4 = "https://good.example.com/hls4/master.m3u8?s=2"
        val hls3 = "https://blocked.example.com/hls3/master.txt?s=3"
        fake.pages[embed] = GatewayPage(embed, 200, "text/html",
            "<script>var links={hls3:'$hls3',hls4:'$hls4',hls2:'$hls2'};</script>")
        fake.probes[hls2] = MediaProbe(false,403,null,"http")
        fake.probes[hls4] = MediaProbe(true,200,hls4,"hls-ok")
        fake.probes[hls3] = MediaProbe(false,403,null,"http")
        val result = PlaybackResolver(SupjavSource()).apply {
            gateway = fake
        }.resolve(VideoServer("0","EVS",embed,detail))
        assertTrue(result is ResolveResult.Playable)
        assertEquals(hls4,(result as ResolveResult.Playable).source.url)
    }

    @Test fun vasApiUsesPlayerJsonAndPreservesReferer() = runBlocking {
        val fake = FakeGateway()
        val embed = "https://vvaassjjav.xyz/e/abc123?lang=en"
        val media = "https://cdn.example.com/hls/master.m3u8?sign=z"
        fake.pages[embed] = GatewayPage(embed, 200, "text/html",
            "<script>let apiURL='/api/stream';</script>")
        fake.postResponse = GatewayPage(
            "https://vvaassjjav.xyz/api/stream?lang=en", 200, "application/json",
            """{"streaming_url":"$media"}""")
        fake.probes[media] = MediaProbe(true, 200, media, "hls-ok")
        val resolver = PlaybackResolver(SupjavSource())
        resolver.gateway = fake
        val result = resolver.resolve(VideoServer("1","VAS",embed,detail))
        assertTrue(result is ResolveResult.Playable)
        assertEquals("https://vvaassjjav.xyz/api/stream?lang=en", fake.apiUrl)
        assertTrue(fake.apiBody.orEmpty().contains("abc123"))
        assertEquals(embed, (result as ResolveResult.Playable).source.referer)
    }

    @Test fun html200DecoyAndGateway404FallBackToBrowser() = runBlocking {
        val fake = FakeGateway()
        val iframe = "https://evsishere.xyz/e/abc"
        val candidate = "https://cdn.example.com/hls3/master.m3u8"
        fake.pages[iframe] = GatewayPage(iframe, 200, "text/html",
            "<script>var urlPlay='$candidate';</script>")
        fake.probes[candidate] = MediaProbe(false, 200, null, "non-media-response")
        val resolver = PlaybackResolver(SupjavSource())
        resolver.gateway = fake
        val result = resolver.resolve(VideoServer("0","EVS",iframe,detail))
        assertTrue(result is ResolveResult.Unavailable)
        assertTrue((result as ResolveResult.Unavailable).reason.contains("JavaScript"))
        val wrapper = "https://lk1.supremejav.com/supjav.php?c=" + "abc".reversed()
        fake.pages[wrapper] = GatewayPage(wrapper, 404, "text/html", "404")
        val next = resolver.resolve(VideoServer("0","EVS","abc",detail))
        assertTrue(next is ResolveResult.Unavailable)
    }

    @Test fun extensionlessHlsIsDetectedFromManifestNotSuffix() = runBlocking {
        val fake = FakeGateway()
        val manifest = "https://stream.example.com/media/playlist"
        fake.pages[manifest] = GatewayPage(manifest, 200,
            "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXT-X-TARGETDURATION:10\n")
        fake.probes[manifest] = MediaProbe(true, 200, manifest, "hls-ok")
        val resolver = PlaybackResolver(SupjavSource())
        resolver.gateway = fake
        val result = resolver.resolve(VideoServer("0","EVS",manifest,detail))
        assertTrue(result is ResolveResult.Playable)
        assertEquals("application/x-mpegURL", (result as ResolveResult.Playable).source.mimeType)
    }

    @Test fun restrictedNativeSourceFallsBackWithoutPretendingToPlay() = runBlocking {
        val fake = FakeGateway()
        val iframe = "https://evsishere.xyz/e/blocked"
        fake.pages[iframe] = GatewayPage(iframe, 403, "text/html", "Forbidden")
        val resolver = PlaybackResolver(SupjavSource())
        resolver.gateway = fake
        val result = resolver.resolve(VideoServer("0", "EVS", iframe, detail))
        assertTrue(result is ResolveResult.Unavailable)
        assertTrue((result as ResolveResult.Unavailable).reason.contains("JavaScript"))
        assertTrue(result.reason.contains("源站限制"))
    }

    @Test fun missavCdn403ReportsAccessDeniedWithoutAutomaticWebFallback() = runBlocking {
        val fake = FakeGateway()
        val detailUrl = "https://missav.ws/en/abc-123"
        val source = "https://surrit.com/example/playlist.m3u8"
        fake.probes[source] = MediaProbe(false, 403, null, "http")
        val resolver = PlaybackResolver(SupjavSource()).apply { gateway = fake }
        val result = resolver.resolve(VideoServer("0", "MISSAV 1", source, detailUrl))
        assertTrue(result is ResolveResult.Unavailable)
        val reason = (result as ResolveResult.Unavailable).reason
        assertTrue(reason.contains("403"))
        assertFalse(reason.contains("JavaScript"))
    }

    @Test fun verificationPageIsNotBypassed() = runBlocking {
        val fake = FakeGateway()
        val url = "https://evsishere.xyz/e/captcha"
        fake.pages[url] = GatewayPage(url,200,"text/html","<h1>Verify you are human</h1>")
        val resolver = PlaybackResolver(SupjavSource())
        resolver.gateway = fake
        assertEquals(ResolveResult.VerificationRequired,
            resolver.resolve(VideoServer("0","EVS",url,detail)))
    }
}
