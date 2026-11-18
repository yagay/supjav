package com.yagay.supjav.desktop

import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.model.VideoServer
import com.yagay.supjav.player.VideoParts
import com.yagay.supjav.player.PlaybackQueuePolicy
import org.junit.Assert.*
import org.junit.Test

class SharedCoreTest {
    @Test fun sharesAndroidSiteRouting() {
        val site = SupjavSource()
        assertEquals("https://supjav.com/page/2/", site.pageUrl(FeedKind.LATEST, "", 2))
        assertTrue(site.pageUrl(FeedKind.SEARCH, "test", 1).contains("s=test"))
    }

    @Test fun cdAndMirrorSeparationIsShared() {
        val servers = (1..2).flatMap { cd ->
            listOf("EVS", "ST", "VOE", "LUC", "VAS").map { name ->
                VideoServer(
                    id = cd.toString() + name, name = name,
                    link = "token" + cd + name,
                    detailUrl = "https://supjav.com/123.html"
                )
            }
        }
        val grouped = VideoParts.groupRepeatedProviderSets(servers)
        assertEquals(listOf("CD1", "CD2"), VideoParts.available(grouped))
        assertEquals("CD2", VideoParts.partOf(grouped.last()))
    }

    @Test fun nextVideoUsesPageOrdering() {
        val page = listOf("1", "2", "3").map {
            VideoItem(it, "Title " + it, detailUrl = "https://supjav.com/" + it + ".html")
        }
        assertEquals("3", PlaybackQueuePolicy.adjacent(page, "2", 1)?.id)
        assertEquals("1", PlaybackQueuePolicy.adjacent(page, "2", -1)?.id)
        assertNull(PlaybackQueuePolicy.adjacent(page, "3", 1))
    }
}
