package com.yagay.supjav.data

import org.junit.Assert.*
import org.junit.Test

class SupjavSourceTest {
    private val source = SupjavSource()

    @Test fun parsesListingWithLazyImagesAndDistinctItems() {
        val html = """
            <div class="posts clearfix">
              <div class="post"><a class="img" href="/187772.html" title="ABP-123">
                <img data-original="https://example.org/cover.jpg!resize"/></a></div>
              <div class="post"><h3><a href="/187773.html">Example clip</a></h3>
                <img data-src="/thumb.png"/></div>
              <div class="post"><a href="/187772.html">Duplicate</a></div>
            </div>
        """.trimIndent()
        val items = source.parseList(html, "https://supjav.com/")
        assertEquals(2, items.size)
        assertEquals("187772", items.first().id)
        assertEquals("ABP-123", items.first().title)
        assertEquals("https://example.org/cover.jpg", items.first().coverUrl)
        assertEquals("https://supjav.com/thumb.png", items[1].coverUrl)
    }

    @Test fun readsSiteTaxonomyAndPreservesExactRoute() {
        val html = """
          <nav>
            <a href="/zh/category/uncensored-jav/">无码</a>
            <a href="/category/censored-jav/">有码</a>
            <a href="/cast/fukada-eimi/">演员</a>
            <a href="/category/cast/legacy/">旧版演员</a>
            <a href="/maker/1pondo/">制作商</a>
            <a href="/tag/doll/">标签</a>
            <a href="https://other.example/category/wrong/">错误</a>
          </nav>
        """.trimIndent()
        val categories = source.parseBrowseOptions(html)
        assertEquals(6, categories.size)
        assertEquals("/zh/category/uncensored-jav", categories[0].path)
        assertEquals("https://supjav.com/zh/category/uncensored-jav/page/2/",
            source.pageUrl(categories[0].kind, categories[0].path, 2))
        assertEquals("https://supjav.com/cast/fukada-eimi/",
            source.pageUrl(categories[2].kind, categories[2].path, 1))
        assertEquals("https://supjav.com/category/cast/legacy/",
            source.pageUrl(categories[3].kind, categories[3].path, 1))
        assertEquals("https://supjav.com/maker/1pondo/",
            source.pageUrl(categories[4].kind, categories[4].path, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesMalformedExternalCategoryPath() {
        source.pageUrl(com.yagay.supjav.model.FeedKind.CATEGORY, "//evil.com/", 1)
    }

    @Test fun rejectsLocalOrInsecureMediaTargets() {
        assertFalse(source.isPublicHttps("http://example.com/a.mp4"))
        assertFalse(source.isPublicHttps("https://localhost:1000/"))
        assertFalse(source.isPublicHttps("https://192.168.1.5/file"))
        assertTrue(source.isPublicHttps("https://example.com/video.m3u8"))
    }
}
