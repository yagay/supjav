package com.yagay.supjav.data

import org.junit.Assert.*
import org.junit.Test

class MissavPageNavigationTest {
    @Test fun videoPageControlsAreReadFromTheSelectedPageOnly() {
        val html = """
          <a href="/dm539/en/new?filters=individual">Single actress</a>
          <a href="/dm539/en/new?filters=multiple">Multiple actresses</a>
          <a href="/dm539/en/new?filters=english-subtitle">English subtitle</a>
          <a href="/dm539/en/new?sort=released_at">Release date</a>
          <a href="/dm539/en/new?sort=published_at">Recent update</a>
          <a href="/dm539/en/new?sort=today_views">Today views</a>
          <a href="/dm36/en/siro?sort=saved">Other category option</a>
          <a href="https://ads.example.org/?sort=views">Advertisement</a>
        """.trimIndent()
        val controls = MissavPageNavigation.parseControls(
            html, "https://missav.ws/dm539/en/new")
        assertEquals(4, controls.filters.size)
        assertEquals(4, controls.sorts.size)
        assertEquals("All", controls.filters.first().label)
        assertTrue(controls.sorts.any { it.value == "today_views" })
        assertFalse(controls.sorts.any { it.value == "saved" })
    }

    @Test fun selectedFiltersAndSortsKeepTheirRoute() {
        assertEquals(
            "/dm539/en/new?filters=multiple&sort=weekly_views",
            MissavPageNavigation.queryPath("/dm539/en/new", "multiple", "weekly_views"))
        assertEquals("/dm539/en/new",
            MissavPageNavigation.queryPath("/dm539/en/new", "", ""))
        assertTrue(MissavPageNavigation.isDirectoryIndex("/en/genres"))
        assertTrue(MissavPageNavigation.isDirectoryIndex("/en/actresses?page=3"))
        assertFalse(MissavPageNavigation.isDirectoryIndex("/dm36/en/siro"))
    }

    @Test fun existingPageUrlSupportsSelectedQueryAndPagination() {
        val source = MissavSource()
        assertEquals(
            "https://missav.ws/dm539/en/new?filters=multiple&sort=weekly_views&page=2",
            source.pageUrl( com.yagay.supjav.model.FeedKind.CATEGORY,
                "/dm539/en/new?filters=multiple&sort=weekly_views", 2))
    }
}
