package com.yagay.supjav.player

import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.model.VideoServer
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueuePolicyTest {
    private fun video(id: String, cover: String = "") = VideoItem(
        id = id, title = "Title-" + id,
        coverUrl = cover,
        detailUrl = "https://supjav.com/" + id + ".html"
    )
    private fun line(id: String, provider: String, part: String?) = VideoServer(
        id = id, name = provider, link = "token-" + id,
        detailUrl = "https://supjav.com/123.html", part = part
    )

    @Test fun preservesSourcePageTitlesCoversAndOrder() {
        val input = listOf(
            video("1", "https://img.example.com/1.jpg"),
            video("2", "https://img.example.com/2.jpg"),
            video("1", "https://img.example.com/duplicate.jpg")
        )
        val queue = PlaybackQueuePolicy.snapshot(input)
        assertEquals(listOf("1", "2"), queue.map { it.id })
        assertEquals(listOf("Title-1", "Title-2"), queue.map { it.title })
        assertEquals("https://img.example.com/1.jpg", queue[0].coverUrl)
    }

    @Test fun currentlyPlayingVideoRemainsAccessibleWhenNotInPage() {
        val queue = PlaybackQueuePolicy.snapshot(listOf(video("1")), video("9"))
        assertEquals(listOf("1", "9"), queue.map { it.id })
    }

    @Test fun preferredHostStaysWithinFirstCd() {
        val servers = listOf(
            line("c1-evs", "EVS", "CD1"),
            line("c1-luc", "LUC", "CD1"),
            line("c2-evs", "EVS", "CD2"),
            line("c2-luc", "LUC", "CD2")
        )
        val candidates = PlaybackQueuePolicy.startingServers(servers, "LUC")
        assertEquals(listOf("c1-luc", "c1-evs"), candidates.map { it.id })
    }

    @Test fun withoutCdAllMirrorsAreAvailable() {
        val servers = listOf(
            line("evs", "EVS", null), line("luc", "LUC", null)
        )
        val candidates = PlaybackQueuePolicy.startingServers(servers, "LUC")
        assertEquals(listOf("luc", "evs"), candidates.map { it.id })
    }
}
