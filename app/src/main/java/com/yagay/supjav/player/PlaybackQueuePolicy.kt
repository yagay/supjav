package com.yagay.supjav.player

import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.model.VideoServer

/** The source page's displayed item order is the authoritative playlist. */
object PlaybackQueuePolicy {
    fun snapshot(items: List<VideoItem>, active: VideoItem? = null): List<VideoItem> {
        val page = items.filter { it.id.isNotBlank() && it.detailUrl.isNotBlank() }
            .distinctBy { it.id }.take(200)
        return if (active != null && page.none { it.id == active.id }) {
            page + active
        } else page
    }

    /** Start with the first actual CD, then the user's preferred host. */
    fun startingServers(
        servers: List<VideoServer>, preferredLine: String?
    ): List<VideoServer> {
        val parts = VideoParts.available(servers)
        val candidates = if (parts.size > 1) {
            servers.filter { VideoParts.partOf(it) == parts.first() }
        } else servers
        val preferred = PlaybackDefaults.preferredServer(candidates, preferredLine)
        return listOfNotNull(preferred) + candidates.filter { it != preferred }
    }
}
