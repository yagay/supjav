package com.yagay.supjav.desktop

import com.yagay.supjav.data.PlaybackResolver
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.*

/** Narrow boundary for desktop page loads and media resolution. */
interface DesktopGateway {
    suspend fun page(kind: FeedKind, value: String, number: Int, cursor: String?):
        SupjavSource.ListingPage
    suspend fun detail(video: VideoItem): VideoDetail
    suspend fun categories(): List<SupjavSource.BrowseOption>
    suspend fun resolve(server: VideoServer): ResolveResult
}

class LiveDesktopGateway : DesktopGateway {
    private val site = SupjavSource()
    private val resolver = PlaybackResolver(site)

    override suspend fun page(kind: FeedKind, value: String, number: Int, cursor: String?) =
        site.listPage(kind, value, number, cursor)

    override suspend fun detail(video: VideoItem) = site.detail(video)

    override suspend fun categories() =
        site.browseDirectory(maxPages = 2).options.distinctBy { it.path }

    override suspend fun resolve(server: VideoServer) = resolver.resolve(server)
}
