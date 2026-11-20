package com.yagay.supjav.data

import com.yagay.supjav.model.FeedKind
import org.junit.Assert.*
import org.junit.Test

class MissavSourceTest {
    private val site = MissavSource()

    @Test fun routesStayOnMissav() {
        assertEquals("https://missav.ws/en/search/ABC-123",
            site.pageUrl(FeedKind.SEARCH, "ABC-123", 1))
        assertEquals("https://missav.ws/en/genres/drama",
            site.pageUrl(FeedKind.CATEGORY, "/en/genres/drama", 1))
        assertEquals("https://missav.ws/en/search/ABC-123?page=2",
            site.pageUrl(FeedKind.SEARCH, "ABC-123", 2))
    }

    @Test fun parsesThumbnailWithoutAdvertisingLink() {
        val html = """
            <div class="thumbnail">
              <a href="/en/abc-123"><img data-src="https://images.example.org/abc.webp"></a>
              <a href="/en/abc-123">ABC-123 Example</a>
              <a href="https://ads.example.org/">Advertisement</a>
            </div>
        """.trimIndent()
        val videos = site.parseList(html)
        assertEquals(1, videos.size)
        assertEquals("abc-123", videos.single().id)
        assertEquals("https://missav.ws/en/abc-123", videos.single().detailUrl)
    }

    @Test fun parsesMetadataAndRejectsOtherSiteTaxonomy() {
        val html = """
          <a href="/en/genres/drama">Drama</a>
          <a href="/en/actresses/sample">Sample</a>
          <a href="https://ads.example.org/en/genres/ads">Ads</a>
        """.trimIndent()
        val items = site.parseBrowseOptions(html)
        assertEquals(2, items.size)
        assertEquals(FeedKind.ACTOR, items[1].kind)
    }

    @Test fun doesNotTreatAdvertisingIframeAsPlayer() {
        val html = """
          <iframe src="https://ads.example.org/banner"></iframe>
          <iframe src="https://player.example.org/embed/123"></iframe>
        """.trimIndent()
        val servers = site.parseServers(html, "https://missav.ws/en/abc-123")
        assertEquals(1, servers.size)
        assertTrue(servers.single().link.contains("player.example.org"))
    }
}
