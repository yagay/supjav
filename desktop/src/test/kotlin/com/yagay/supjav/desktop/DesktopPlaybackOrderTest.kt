package com.yagay.supjav.desktop

import com.yagay.supjav.model.VideoServer
import com.yagay.supjav.player.PlaybackQueuePolicy
import com.yagay.supjav.player.VideoParts
import org.junit.Assert.*
import org.junit.Test

class DesktopPlaybackOrderTest {
    private fun server(name: String, cd: String = "CD1") =
        VideoServer(
            id = cd + name, name = name, link = "abc123",
            detailUrl = "https://supjav.com/472799.html", part = cd
        )

    @Test fun nativeMirrorsComeBeforeJavaScriptPagesForAutoplay() {
        val lines = listOf(server("EVS"), server("VAS"), server("ST"),
            server("VOE"), server("LUC"))
        assertEquals(listOf("VAS", "VOE", "ST", "EVS", "LUC"),
            DesktopPlaybackOrder.nativeFirst(lines).map { it.name })
    }

    @Test fun doesNotJumpToAnotherCdWhenSelectingNativeMirror() {
        val lines = listOf(
            server("EVS", "CD1"), server("VAS", "CD1"),
            server("EVS", "CD2"), server("VAS", "CD2")
        )
        val firstPart = PlaybackQueuePolicy.startingServers(lines, "auto")
        assertEquals(listOf("CD1"), firstPart.map(VideoParts::partOf).distinct())
        assertEquals("VAS",
            DesktopPlaybackOrder.nativeFirst(firstPart).first().name)
    }

    @Test fun preservesOrderForUnknownOrRepeatedNames() {
        val lines = listOf(server("UNKNOWN"), server("MYSTERY"),
            server("VAS"), server("VAS", "CD2"))
        assertEquals(listOf("VAS", "VAS", "UNKNOWN", "MYSTERY"),
            DesktopPlaybackOrder.nativeFirst(lines).map { it.name })
    }
}
