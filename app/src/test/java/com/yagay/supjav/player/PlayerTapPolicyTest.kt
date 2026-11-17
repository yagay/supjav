package com.yagay.supjav.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTapPolicyTest {
    @Test fun firstTapRevealsControlsWithoutPausingPlayback() {
        assertEquals(PlayerTapPolicy.Action.REVEAL,
            PlayerTapPolicy.onSurfaceTap(controlsVisible = false, playWhenReady = true))
    }
    @Test fun secondTapPausesWhenControlsAreVisible() {
        assertEquals(PlayerTapPolicy.Action.PAUSE,
            PlayerTapPolicy.onSurfaceTap(controlsVisible = true, playWhenReady = true))
    }
    @Test fun pausedSurfaceTapResumesPlayback() {
        assertEquals(PlayerTapPolicy.Action.RESUME,
            PlayerTapPolicy.onSurfaceTap(controlsVisible = false, playWhenReady = false))
        assertEquals(PlayerTapPolicy.Action.RESUME,
            PlayerTapPolicy.onSurfaceTap(controlsVisible = true, playWhenReady = false))
    }
}
