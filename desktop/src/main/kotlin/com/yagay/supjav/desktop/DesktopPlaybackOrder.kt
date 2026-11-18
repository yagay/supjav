package com.yagay.supjav.desktop

import com.yagay.supjav.model.VideoServer
import java.util.Locale

/**
 * Desktop-specific automatic server priority.
 * Keep the original CD1 subset and stable order within the same provider.
 * Native resolvers run before hosts that require their actual website.
 */
object DesktopPlaybackOrder {
    private val priority = mapOf(
        "VAS" to 0,
        "VOE" to 1,
        "ST" to 2,
        "EVS" to 3,
        "LUC" to 4
    )

    fun nativeFirst(servers: List<VideoServer>): List<VideoServer> =
        servers.sortedWith(compareBy { server ->
            priority[server.name.trim().uppercase(Locale.ROOT)] ?: 5
        })
}
