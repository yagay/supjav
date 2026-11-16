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

    @Test fun rejectsLocalOrInsecureMediaTargets() {
        assertFalse(source.isPublicHttps("http://example.com/a.mp4"))
        assertFalse(source.isPublicHttps("https://localhost:1000/"))
        assertFalse(source.isPublicHttps("https://192.168.1.5/file"))
        assertTrue(source.isPublicHttps("https://example.com/video.m3u8"))
    }
}
