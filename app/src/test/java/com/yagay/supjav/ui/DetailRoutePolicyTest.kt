package com.yagay.supjav.ui

import com.yagay.supjav.model.VideoItem
import org.junit.Assert.*
import org.junit.Test

class DetailRoutePolicyTest {
    private fun video(id: String) = VideoItem(
        id = id, title = "Video " + id,
        detailUrl = "https://supjav.com/" + id + ".html"
    )

    @Test fun sameVideoCannotBePushedTwiceInSequence() {
        val stack = listOf(video("A"))
        assertFalse(DetailRoutePolicy.shouldPush(stack, video("A")))
        assertTrue(DetailRoutePolicy.shouldPush(stack, video("B")))
    }

    @Test fun oneBackRemovesAllContiguousCopiesOfVisibleDetail() {
        val stack = mutableListOf(video("A"), video("B"), video("B"), video("B"))
        assertEquals(3, DetailRoutePolicy.popVisible(stack))
        assertEquals(listOf("A"), stack.map { it.id })
    }

    @Test fun backPopsOneDistinctPageAtATime() {
        val stack = mutableListOf(video("A"), video("B"), video("A"))
        assertEquals(1, DetailRoutePolicy.popVisible(stack))
        assertEquals(listOf("A", "B"), stack.map { it.id })
        assertEquals(1, DetailRoutePolicy.popVisible(stack))
        assertEquals(listOf("A"), stack.map { it.id })
    }

    @Test fun emptyStackHasNoRemoval() {
        val stack = mutableListOf<VideoItem>()
        assertEquals(0, DetailRoutePolicy.popVisible(stack))
        assertTrue(DetailRoutePolicy.shouldPush(stack, video("A")))
    }
}
