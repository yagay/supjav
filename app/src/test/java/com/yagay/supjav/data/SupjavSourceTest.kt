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

    @Test fun multipleVideosInsideOneArticleAreNotCollapsedToFirstItem() {
        val html = """
          <main><article class="listing">
            <div class="thumb"><a href="/300010.html" title="ABC-101">
              <img data-original="/a.jpg"/></a></div>
            <div class="thumb"><a href="/300011.html" title="ABC-102">
              <img data-original="/b.jpg"/></a></div>
            <div class="thumb"><a href="/300012.html" title="ABC-103">
              <img data-original="/c.jpg"/></a></div>
          </article></main>
        """.trimIndent()
        val result = source.parseList(html)
        assertEquals(listOf("300010", "300011", "300012"), result.map { it.id })
        assertEquals("ABC-102", result[1].title)
        assertEquals("https://supjav.com/b.jpg", result[1].coverUrl)
    }

    @Test fun websitePaginationNextLinkBeatsAssumedPageUrl() {
        val html = """
          <main class="posts">
            <div class="post"><a class="img" href="/300001.html" title="First page">
              <img src="/cover.jpg"/></a></div>
          </main>
          <nav class="pagination">
            <a class="page-numbers current" href="/page/1/">1</a>
            <a class="page-numbers" href="/page/2/">2</a>
            <a class="next page-numbers" href="/page/2/">下一页</a>
          </nav>
        """.trimIndent()
        val result = source.parseListingPage(html, "https://supjav.com/", 1)
        assertEquals(listOf("300001"), result.items.map { it.id })
        assertEquals("https://supjav.com/page/2/", result.nextUrl)
        assertTrue(result.hasPager)
    }

    @Test fun paginationSupportsQueryUrlsAndRejectsUntrustedTargets() {
        val html = """
          <div class="post"><a href="/300001.html">A</a></div>
          <link rel="next" href="https://other.example/page/2"/>
          <nav class="pagination">
             <a rel="next" href="/?paged=2">Next</a>
          </nav>
        """.trimIndent()
        val result = source.parseListingPage(html, "https://supjav.com/", 1)
        assertEquals("https://supjav.com/?paged=2", result.nextUrl)
    }

    @Test fun numericPagerWithoutNextClassIsRecognized() {
        val html = """
          <main><a href="/300001.html">Video</a></main>
          <div class="pagination">
            <span class="current">1</span>
            <a href="/page/2/">2</a>
            <a href="/page/3/">3</a>
          </div>
        """.trimIndent()
        val result = source.parseListingPage(html, "https://supjav.com/", 1)
        assertEquals("https://supjav.com/page/2/", result.nextUrl)
        assertTrue(result.hasPager)
    }

    @Test fun classlessNextLinkIsRecognized() {
        val html = """
          <main><a href="/300001.html">Video</a></main>
          <nav class="pager">
            <a href="/page/2/" aria-label="Next Page">Go forward</a>
          </nav>
        """.trimIndent()
        assertEquals("https://supjav.com/page/2/",
            source.parseListingPage(html, "https://supjav.com/", 1).nextUrl)
    }

    @Test fun unknownPagerDoesNotInventAnUnsafeCursor() {
        val html = """
          <main><a href="/300001.html">Video</a></main>
          <nav class="pagination">
            <a href="https://evil.example/page/2/">2</a>
            <span class="current">1</span>
          </nav>
        """.trimIndent()
        val result = source.parseListingPage(html, "https://supjav.com/", 1)
        assertNull(result.nextUrl)
        assertTrue(result.hasPager)
    }

    @Test fun noPaginationReturnsNoCursorWithoutInventingOne() {
        val html = """<article><a href="/300002.html">B</a></article>"""
        val result = source.parseListingPage(html, "https://supjav.com/", 1)
        assertEquals(null, result.nextUrl)
        assertFalse(result.hasPager)
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

    @Test fun externalVideoLinksCannotBecomeDetailRequests() {
        val html = """
            <div class="post"><a href="https://evil.example.com/555001.html">External</a></div>
            <div class="post"><a href="/555002.html">Allowed</a></div>
        """.trimIndent()
        val items = source.parseList(html, "https://supjav.com/")
        assertEquals(1, items.size)
        assertEquals("555002", items.first().id)
    }

    @Test fun taxonomyDirectoryIncludesLinksOutsideNavigation() {
        val html = """
          <section class="results">
            <a href="/cast/performer-a/">演员 A</a>
            <a href="/maker/studio-b/">片商 B</a>
            <a href="/tag/type-c/">标签 C</a>
            <a href="/category/sample/">分类 D</a>
          </section>
        """.trimIndent()
        val items = source.parseBrowseOptions(html)
        assertEquals(4, items.size)
        assertEquals(listOf("演员 A", "片商 B", "标签 C", "分类 D"),
            items.map { it.label })
    }

    @Test fun taxonomyDirectoryDoesNotTruncateAfterThreeHundredItems() {
        val html = buildString {
            append("<main>")
            for (i in 1..360) append("<a href='/tag/example-$i/'>Tag $i</a>")
            append("</main>")
        }
        val options = source.parseBrowseOptions(html)
        assertEquals(360, options.size)
        assertEquals("/tag/example-360", options.last().path)
    }

    @Test fun directoryRejectsCrossDomainAndPagerLinks() {
        val html = """
          <nav>
            <a href="https://malicious.example/tag/bad/">Bad</a>
            <a href="/tag/page/3/">Page 3</a>
            <a href="/category/cast/actor-a/">Actor</a>
            <a href="/category/maker/studio-a/">Maker</a>
          </nav>
        """.trimIndent()
        val items = source.parseBrowseOptions(html)
        assertEquals(2, items.size)
        assertEquals(listOf(com.yagay.supjav.model.FeedKind.ACTOR,
            com.yagay.supjav.model.FeedKind.MAKER), items.map { it.kind })
    }

    @Test fun parsesCardDateAndDurationOnlyWhenPresent() {
        val html = """
          <main><div class="post">
            <a href="/123456.html" title="Example"><img src="/poster.jpg"/></a>
            <time datetime="2026-11-16">16 Nov</time>
            <span class="duration">42:11</span>
          </div></main>
        """.trimIndent()
        val video = source.parseList(html).single()
        assertEquals("2026-11-16", video.publishedAt)
        assertEquals("42:11", video.durationLabel)
    }

    @Test fun parsesMoreThanOneRecognizedServerLine() {
        val html = """
          <div class="servers">
            <button class="btn-server" data-link="abc123">EVS</button>
            <button data-server="LUC" data-link="xyz456">LUC</button>
          </div>
        """.trimIndent()
        val lines = source.parseServers(html, "https://supjav.com/12345.html")
        assertEquals(2, lines.size)
        assertEquals(listOf("EVS", "LUC"), lines.map { it.name })
    }



    @Test fun tenUnlabeledButtonsInTwoCompleteServerSetsExposeCd1Cd2() {
        val lines = listOf("EVS", "ST", "VOE", "LUC", "VAS")
        val html = buildString {
            append("<section class='player'>")
            for (disc in 1..2) {
                append("<div class='btnst'>")
                for (line in lines) {
                    append("<a data-link='token" + disc + "-" + line + "'>" +
                        line + "</a>")
                }
                append("</div>")
            }
            append("</section>")
        }
        val servers = source.parseServers(html, "https://supjav.com/472992.html")
        assertEquals(10, servers.size)
        assertEquals(listOf("CD1", "CD2"),
            com.yagay.supjav.player.VideoParts.available(servers))
        assertEquals(listOf("CD1", "CD1", "CD1", "CD1", "CD1",
            "CD2", "CD2", "CD2", "CD2", "CD2"), servers.map { it.part })
        assertEquals("token2-LUC",
            com.yagay.supjav.player.VideoParts.forPart(servers, "CD2", servers[3])?.link)
    }

    @Test fun identifiesRealCdGroupsFromSourceButtons() {
        val html = """
          <div class="cd-group" data-part="CD1">
            <div class="btnst">
              <a data-link="evs1" class="btn-server">EVS</a>
              <a data-link="luc1">LUC</a>
            </div>
          </div>
          <h4>CD2</h4>
          <div class="btnst">
            <a data-link="evs2">EVS</a>
            <a data-link="luc2">LUC</a>
          </div>
        """.trimIndent()
        val entries = source.parseServers(html, "https://supjav.com/12345.html")
        assertEquals(4, entries.size)
        assertEquals(listOf("CD1", "CD1", "CD2", "CD2"), entries.map { it.part })
        assertEquals(listOf("EVS", "LUC", "EVS", "LUC"), entries.map { it.name })
        assertEquals(listOf("evs1", "luc1", "evs2", "luc2"), entries.map { it.link })
    }

    @Test fun multipleServerMirrorsWithoutCdLabelsStayUnpartitioned() {
        val html = """
          <div class="btnst"><a data-link="a">EVS</a><a data-link="b">LUC</a></div>
        """.trimIndent()
        val entries = source.parseServers(html, "https://supjav.com/12345.html")
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.part == null })
    }


    @Test fun castParagraphWithLinksIsRecognizedAndNavigable() {
        val html = """
          <div class="post-meta clearfix">
            <div class="cats">
              <p><span>Cast: </span>
                <a href="/category/cast/actress-one/">Actress One</a>,
                <a href="/cast/actress-two/">Actress Two</a>
              </p>
              <p><span>Maker:</span><a href="/maker/studio/">Studio</a></p>
            </div>
          </div>
        """.trimIndent()
        val cast = source.parseCastMetadata(html, "https://supjav.com/187772.html")
        assertEquals(listOf("Actress One", "Actress Two"), cast.names)
        assertEquals(2, cast.links.size)
        assertEquals(listOf("/category/cast/actress-one", "/cast/actress-two"),
            cast.links.map { it.path })
        assertTrue(cast.links.all {
            it.kind == com.yagay.supjav.model.FeedKind.ACTOR
        })
    }

    @Test fun textOnlyLabeledCastIsDisplayedWithoutInventingFilterUrl() {
        val html = """
          <div class="post-meta"><div class="cats">
            <p><span>Cast:</span> Person A、Person B</p>
          </div></div>
        """.trimIndent()
        val cast = source.parseCastMetadata(html)
        assertEquals(listOf("Person A", "Person B"), cast.names)
        assertTrue(cast.links.isEmpty())
    }

    @Test fun castParagraphSupportsLegacyCategoryLinkButIgnoresUnrelatedLinks() {
        val html = """
          <div class="post-meta"><div class="cats">
            <p><span>Cast:</span>
              <a href="/category/actor-legacy/">Legacy Performer</a>
              <a href="https://other.example/cast/fake/">External Actor</a>
            </p>
            <p><span>Category:</span><a href="/category/other/">Other</a></p>
          </div></div>
        """.trimIndent()
        val cast = source.parseCastMetadata(html)
        assertEquals(listOf("Legacy Performer", "External Actor"), cast.names)
        assertEquals(listOf("/category/actor-legacy"), cast.links.map { it.path })
    }

    @Test fun castMetadataNeverUsesUnlabeledSiteNavigationAsActors() {
        val html = """
          <nav><a href="/cast/someone/">Site-wide Cast Directory</a></nav>
          <div class="post-meta"><div class="cats">
            <p><span>Maker:</span><a href="/maker/one/">Maker</a></p>
          </div></div>
        """.trimIndent()
        val cast = source.parseCastMetadata(html)
        assertTrue(cast.names.isEmpty())
        assertTrue(cast.links.isEmpty())
    }

    @Test fun rejectsLocalOrInsecureMediaTargets() {
        assertFalse(source.isPublicHttps("http://example.com/a.mp4"))
        assertFalse(source.isPublicHttps("https://localhost:1000/"))
        assertFalse(source.isPublicHttps("https://192.168.1.5/file"))
        assertTrue(source.isPublicHttps("https://example.com/video.m3u8"))
    }
}
