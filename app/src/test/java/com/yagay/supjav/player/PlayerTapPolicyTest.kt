package com.yagay.supjav.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTapPolicyTest {
    @Test fun tapShowsHiddenControls() {
        assertEquals(PlayerTapPolicy.Action.SHOW,
            PlayerTapPolicy.onSurfaceTap(controlsVisible = false))
    }

    @Test fun tapHidesVisibleControls() {
        assertEquals(PlayerTapPolicy.Action.HIDE,
            PlayerTapPolicy.onSurfaceTap(controlsVisible = true))
    }
}
