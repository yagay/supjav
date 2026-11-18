package com.yagay.supjav.desktop

import org.junit.Assert.*
import org.junit.Test

class DesktopGestureMathTest {
    @Test fun volumeTracksTotalSwipeNotNumberOfCallbacks() {
        val final = DesktopGestureMath.volume(50, -200f, 1000)
        val viaIntermediate = listOf(-25f, -100f, -200f)
            .map { DesktopGestureMath.volume(50, it, 1000) }.last()
        assertEquals(70, final)
        assertEquals(final, viaIntermediate)
    }

    @Test fun brightnessUsesPressBaselineAndClamps() {
        assertEquals(0.68f, DesktopGestureMath.brightness(.5f, -200f, 1000), .0001f)
        assertEquals(1f, DesktopGestureMath.brightness(.9f, -1000f, 1000), 0f)
        assertEquals(.1f, DesktopGestureMath.brightness(.2f, 1000f, 1000), 0f)
    }

    @Test fun seekPreviewCanBeCommittedOnceOnRelease() {
        assertEquals(90_000L,
            DesktopGestureMath.seekTarget(60_000L, 100f, 1000, 300_000L))
        assertEquals(0L,
            DesktopGestureMath.seekTarget(10_000L, -1000f, 1000, 300_000L))
        assertEquals(300_000L,
            DesktopGestureMath.seekTarget(290_000L, 100f, 1000, 300_000L))
    }

    @Test fun zeroDurationAndDimensionsStaySafe() {
        assertEquals(10L, DesktopGestureMath.seekTarget(10L, 1f, 0, 0L))
        assertEquals(100, DesktopGestureMath.volume(70, -1000f, 0))
    }
}
