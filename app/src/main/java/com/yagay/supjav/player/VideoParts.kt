package com.yagay.supjav.player

import com.yagay.supjav.data.SupjavLine
import com.yagay.supjav.model.VideoServer

/**
 * CD selection is derived from actual server entries, never fabricated from
 * the movie title or the number of available playback mirrors.
 */
object VideoParts {
    private val partPattern = Regex(
        """(?i)(?<![a-z0-9])(?:cd|disc|part)[\\s_-]*0?([1-9][0-9]?)(?![0-9])"""
    )

    fun normalize(label: String?): String? {
        val number = partPattern.find(label.orEmpty())?.groupValues?.get(1)
            ?.toIntOrNull() ?: return null
        return "CD$number"
    }

    fun partOf(server: VideoServer): String? =
        normalize(server.part) ?: normalize(server.name)

    fun available(servers: List<VideoServer>): List<String> =
        servers.mapNotNull(::partOf).distinct()
            .sortedBy { it.removePrefix("CD").toIntOrNull() ?: Int.MAX_VALUE }

    fun forPart(
        servers: List<VideoServer>, part: String,
        preferredLine: VideoServer? = null
    ): VideoServer? {
        val candidates = servers.filter { partOf(it) == part }
        val preferred = preferredLine?.let { SupjavLine.of(it.name) }
        return candidates.firstOrNull {
            preferred != null && preferred != SupjavLine.UNKNOWN &&
                SupjavLine.of(it.name) == preferred
        } ?: candidates.firstOrNull()
    }

    fun firstPartFirst(servers: List<VideoServer>): List<VideoServer> {
        val parts = available(servers)
        if (parts.size < 2) return servers
        val firstPart = parts.first()
        return servers.filter { partOf(it) == firstPart } +
            servers.filter { partOf(it) != firstPart }
    }
}
