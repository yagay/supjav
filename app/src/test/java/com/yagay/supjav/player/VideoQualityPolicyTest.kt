package com.yagay.supjav.player

import org.junit.Assert.*
import org.junit.Test

class VideoQualityPolicyTest {
    @Test fun highestPicksActualLargestResolution() {
        assertEquals(2160, VideoQualityPolicy.targetHeight(listOf(480, 720, 2160), "highest"))
        assertNull(VideoQualityPolicy.targetHeight(listOf(480, 720, 1080), "auto"))
    }

    @Test fun requestedResolutionNeverInventsMissingLevel() {
        assertEquals(720, VideoQualityPolicy.targetHeight(listOf(720, 1440), "p1080"))
        assertEquals(1440, VideoQualityPolicy.targetHeight(listOf(1440, 2160), "p720"))
        assertNull(VideoQualityPolicy.targetHeight(emptyList(), "highest"))
    }

    @Test fun preferenceIsValidatedAndMenuListsOnlyObservedHeights() {
        assertEquals("highest", VideoQualityPolicy.normalize("garbage"))
        assertEquals("highest", VideoQualityPolicy.normalize("p999999"))
        assertEquals("p1080", VideoQualityPolicy.normalize("p1080"))
        assertEquals(listOf("auto", "highest", "p1080", "p720"),
            VideoQualityPolicy.choices(listOf(720, 1080, 720, 0)))
    }
}
