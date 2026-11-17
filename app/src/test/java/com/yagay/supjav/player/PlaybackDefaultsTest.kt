package com.yagay.supjav.player

import com.yagay.supjav.model.VideoServer
import org.junit.Assert.*
import org.junit.Test

class PlaybackDefaultsTest {
    @Test fun validatesAndFallsBackForPreferences() {
        assertEquals(1f, PlaybackDefaults.speed(9f), 0.001f)
        assertEquals(1.25f, PlaybackDefaults.speed(1.25f), 0.001f)
        assertEquals(2f, PlaybackDefaults.holdSpeed(99f), 0.001f)
        assertEquals(30, PlaybackDefaults.skip(30))
        assertEquals(10, PlaybackDefaults.skip(99))
        assertEquals("auto", PlaybackDefaults.line("invalid"))
        assertEquals("EVS", PlaybackDefaults.line("evs"))
        assertEquals("RECOMMEND", PlaybackDefaults.startup("unknown"))
    }

    @Test fun preferredServerUsesRealAvailableEntries() {
        val servers = listOf(
            VideoServer("0", "LUC", "code", "https://supjav.com/123.html"),
            VideoServer("1", "EVS HD", "code2", "https://supjav.com/123.html")
        )
        assertEquals("0", PlaybackDefaults.preferredServer(servers, "auto")?.id)
        assertEquals("1", PlaybackDefaults.preferredServer(servers, "EVS")?.id)
        assertEquals("0", PlaybackDefaults.preferredServer(servers, "VAS")?.id)
        assertNull(PlaybackDefaults.preferredServer(emptyList(), "EVS"))
    }
}
