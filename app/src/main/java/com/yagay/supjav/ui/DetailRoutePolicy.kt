package com.yagay.supjav.ui

import com.yagay.supjav.model.VideoItem

/**
 * Routes represent *visible* detail pages. Consecutive copies of the same
 * video should never require multiple Back presses to leave one page.
 */
internal object DetailRoutePolicy {
    fun shouldPush(routes: List<VideoItem>, next: VideoItem): Boolean =
        routes.lastOrNull()?.id != next.id

    /** Pops the visible detail, including any duplicate entries left by older builds. */
    fun popVisible(routes: MutableList<VideoItem>): Int {
        val activeId = routes.lastOrNull()?.id ?: return 0
        var count = 0
        while (routes.lastOrNull()?.id == activeId) {
            routes.removeAt(routes.lastIndex)
            count++
        }
        return count
    }
}
