package com.yagay.supjav.data

import com.yagay.supjav.model.VideoServer
import java.util.Base64
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SupjavServerAdaptersTest {
    private val site = "https://supjav.com/473013.html"

    @Test fun opaqueTokensWorkForEveryServerLabel() {
        val token = "Ab12_-".repeat(24).take(128)
        for (label in listOf("EVS", "VAS", "VOE", "LUC", "FST", "ST", "TV")) {
            val url = SupjavServerAdapters.startingUrl(VideoServer("0", label, token, site))
            assertEquals("https://lk1.supremejav.com/supjav.php?c=" + token.reversed(), url)
        }
        assertNull(SupjavServerAdapters.startingUrl(VideoServer(
            "0", "EVS", "https://127.1/master.m3u8", site)))
    }

    @Test fun explicitEvsManifestPreservesSignedQuery() {
        val html = """
            <script>jwplayer().setup({sources:[{file:
            "https://media.example.com/hls3/master.txt?token=signed&expires=123",
            type:"hls"}]})</script>
        """.trimIndent()
        val result = SupjavServerAdapters.extract(
            SupjavLine.EVS, html, "https://evsishere.xyz/e/abcd")
        assertEquals("application/x-mpegURL", result.candidates.first().mimeType)
        assertTrue(result.candidates.first().url.endsWith("?token=signed&expires=123"))
    }

    @Test fun fstLinksPrioritizeDeclaredHlsAndResolveRelativeTxt() {
        val html = """
            <script>
              var links = {"hls3":"/hls3/master.txt?sig=top",
                           "hls2":"/hls2/master.m3u8?sig=low"};
              jwplayer().setup({sources:[{file: links.hls3 || links.hls2, type:"hls"}]});
            </script>
        """.trimIndent()
        val result = SupjavServerAdapters.extract(
            SupjavLine.FST, html, "https://cdn.fst.example.com/embed/abc")
        assertEquals("https://cdn.fst.example.com/hls3/master.txt?sig=top",
            result.candidates.first().url)
    }

    @Test fun streamtapeJsExpressionIsUsedInsteadOfDecoyElement() {
        val html = """
            <div id="robotlink">/get_video?id=DECOY</div>
            <script>document.getElementById('robotlink').innerHTML =
            '//streamtape.com/get_' +
            ('xcdvideo?id=ABC&expires=123&token=REAL').substring(2).substring(1);</script>
        """.trimIndent()
        val resolved = SupjavServerAdapters.streamtapeDeclaredSource(html)
        assertEquals(
            "https://streamtape.com/get_video?id=ABC&expires=123&token=REAL",
            resolved
        )
        assertNull(SupjavServerAdapters.streamtapeDeclaredSource(
            "<div id=robotlink>https://streamtape.com/get_video?id=DECOY</div>"))
    }

    @Test fun voeJsonConfigurationTakesPriorityOverDecoy() {
        val url = "https://cdn.example.com/hls/master.m3u8?token=real"
        val config = """{"source":"$url"}"""
        val reversedEncoded = Base64.getEncoder()
            .encodeToString(config.toByteArray(Charsets.UTF_8)).reversed()
        val shifted = reversedEncoded.map { (it.code + 3).toChar() }.joinToString("")
        val encoded = Base64.getEncoder()
            .encodeToString(shifted.toByteArray(Charsets.US_ASCII))
        val rot13 = encoded.map {
            when(it) {
                in 'a'..'z' -> 'a' + ((it - 'a' + 13) % 26)
                in 'A'..'Z' -> 'A' + ((it - 'A' + 13) % 26)
                else -> it
            }
        }.joinToString("")
        val noisy = rot13.chunked(4).joinToString("@$")
        val html = "<script>var source='https://ads.example.com/decoy.mp4';</script>" +
            "<script type='application/json'>[\"$noisy\"]</script>"
        val source = SupjavServerAdapters.voeDeclaredSource(
            Jsoup.parse(html, "https://voe.sx/e/abc"))
        assertEquals(url, source)
        val extraction = SupjavServerAdapters.extract(
            SupjavLine.VOE, html, "https://voe.sx/e/abc")
        assertEquals(url, extraction.candidates.firstOrNull()?.url)
        assertEquals(1, extraction.candidates.size)
    }

    @Test fun vasApiUsesSameOriginAndFilecode() {
        assertEquals("abc123",
            SupjavServerAdapters.vasFileCode("https://vvaassjjav.xyz/e/abc123?lang=en"))
        assertEquals("https://vvaassjjav.xyz/api/stream?lang=en",
            SupjavServerAdapters.vasApiUrl(
                "https://vvaassjjav.xyz/e/abc123?lang=en&filecode=discard"))
        assertNull(SupjavServerAdapters.vasFileCode("https://127.0.0.1/e/abc"))
        assertNull(SupjavServerAdapters.vasApiUrl(
            "https://vvaassjjav.xyz/anything/abc"))
    }

    @Test fun untrustedAdvertisementsNeverBecomeIframeRoutes() {
        val html = """
            <iframe src="https://r.trackwilltrk.com/ad/abc"></iframe>
            <iframe src="https://cdn.example.com/ads/player"></iframe>
            <iframe src="https://evsishere.xyz/e/real"></iframe>
        """.trimIndent()
        val result = SupjavServerAdapters.extract(
            SupjavLine.EVS, html, "https://lk1.supremejav.com/supjav.php?c=abc")
        assertEquals(listOf("https://evsishere.xyz/e/real"), result.nextPages)
    }

    @Test fun multiplePackedScriptsDoNotHideSelectedHlsSource() {
        val junk = "eval(function(p,a,c,k,e,d){}('0=1',10,2,'var|noise'.split('|')))"
        val source = "https://media.example.com/hls3/master.txt?token=signed"
        val good = "eval(function(p,a,c,k,e,d){}('0=\\"1\\"',10,2,'source|" + source + "'.split('|')))"
        val html = "<script>" + junk + "</script><script>" + good + "</script>"
        val result = SupjavServerAdapters.extract(
            SupjavLine.EVS, html, "https://evsishere.xyz/e/current")
        assertEquals(source, result.candidates.firstOrNull()?.url)
    }

    @Test fun packerDictionaryCanBeReadWithoutEvaluatingJs() {
        val input = "eval(function(p,a,c,k,e,d){}('0 1 2',10,3,'var|source|https'.split('|')))"
        assertEquals("var source https", SupjavServerAdapters.unpackPacker(input))
        assertNull(SupjavServerAdapters.unpackPacker(
            "eval(function(p,a,c,k,e,d){}('0',1,1,'var'.split('|')))"))
    }
}
