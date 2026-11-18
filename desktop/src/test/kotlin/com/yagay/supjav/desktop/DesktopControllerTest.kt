package com.yagay.supjav.desktop

import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.*
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopControllerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun movie(id: String) = VideoItem(
        id = id, title = "Video $id", detailUrl = "https://supjav.com/$id.html"
    )

    private inner class StubGateway(
        val pageDelay: suspend (FeedKind) -> Long = { 0L }
    ) : DesktopGateway {
        val calls = mutableListOf<Pair<FeedKind, Int>>()
        override suspend fun page(
            kind: FeedKind, value: String, number: Int, cursor: String?
        ): SupjavSource.ListingPage {
            calls.add(kind to number)
            delay(pageDelay(kind))
            val items = when (kind) {
                FeedKind.LATEST -> when (number) {
                    1 -> listOf(movie("101"), movie("102"))
                    2 -> listOf(movie("103"))
                    else -> emptyList()
                }
                FeedKind.WEEK -> listOf(movie("201"))
                else -> listOf(movie("301"))
            }
            return SupjavSource.ListingPage(items, null, number < 2)
        }
        override suspend fun detail(video: VideoItem) =
            VideoDetail(video, "", emptyList(), emptyList(), emptyList(), emptyList())
        override suspend fun categories() = emptyList<SupjavSource.BrowseOption>()
        override suspend fun resolve(server: VideoServer): ResolveResult =
            ResolveResult.Unavailable("Not playable in test")
    }

    private fun awaitCondition(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out: $label")
    }

    private fun controller(gateway: DesktopGateway) =
        DesktopController(VlcBackend(discoverNative = false), gateway,
            DesktopVideoStore(temp.newFolder().toPath()))

    @Test fun evsOpensEmbeddedBrowserWithoutWaitingForNativeResolver() {
        val video = movie("472994")
        val evs = VideoServer(
            id = "evs", name = "EVS", link = "testToken",
            detailUrl = video.detailUrl
        )
        var nativeResolveCalls = 0
        val browserGateway = object : DesktopGateway {
            override suspend fun page(
                kind: FeedKind, value: String, number: Int, cursor: String?
            ) = SupjavSource.ListingPage(listOf(video), null, false)

            override suspend fun detail(video: VideoItem) = VideoDetail(
                video, "", emptyList(), emptyList(), listOf(evs), emptyList()
            )

            override suspend fun categories() = emptyList<SupjavSource.BrowseOption>()

            override suspend fun resolve(server: VideoServer): ResolveResult {
                nativeResolveCalls++
                return ResolveResult.Unavailable("needs JavaScript")
            }
        }
        val vm = controller(browserGateway)
        try {
            vm.open(video)
            awaitCondition("detail has EVS server") {
                vm.detail?.servers?.firstOrNull()?.name == "EVS"
            }
            vm.play(evs)
            awaitCondition("browser selected") {
                vm.browserPageUrl == video.detailUrl && !vm.switching
            }
            assertEquals(0, nativeResolveCalls)
            assertEquals(video.id, vm.browserVideo?.id)
            assertNull(vm.playingVideo)
            assertTrue(vm.status.contains("网页播放"))
        } finally { vm.close() }
    }

    @Test fun newFeedIsNotBlockedByLoadingOldFeed() {
        val fake = StubGateway { kind ->
            if (kind == FeedKind.LATEST) 350L else 10L
        }
        val vm = controller(fake)
        try {
            vm.select(FeedKind.WEEK)
            awaitCondition("week feed") {
                vm.videos.firstOrNull()?.id == "201" && !vm.pageLoading
            }
            Thread.sleep(400)
            assertEquals(listOf("201"), vm.videos.map { it.id })
            assertFalse(vm.loading)
        } finally { vm.close() }
    }

    @Test fun playlistKeepsOriginalFeedAfterChangingCategory() {
        val fake = StubGateway()
        val vm = controller(fake)
        try {
            awaitCondition("initial feed") { vm.videos.size >= 2 }
            vm.open(vm.videos.first(), autoplay = false)
            assertEquals(listOf("101", "102"), vm.playlistItems.map { it.id })
            vm.select(FeedKind.WEEK)
            awaitCondition("week feed") { vm.videos.firstOrNull()?.id == "201" }
            assertEquals(listOf("101", "102"), vm.playlistItems.map { it.id })
            vm.loadQueueMore()
            awaitCondition("queue next page") {
                vm.playlistItems.any { it.id == "103" } && !vm.queueLoading
            }
            assertEquals(listOf("101", "102", "103"), vm.playlistItems.map { it.id })
            assertEquals(listOf("201"), vm.videos.map { it.id })
        } finally { vm.close() }
    }

    @Test fun cachedFeedReturnsWithoutRequestingFirstPageAgain() {
        val fake = StubGateway()
        val vm = controller(fake)
        try {
            awaitCondition("initial feed") { vm.videos.size >= 2 }
            vm.select(FeedKind.WEEK)
            awaitCondition("week feed") { vm.videos.firstOrNull()?.id == "201" }
            vm.select(FeedKind.LATEST)
            assertEquals(listOf("101", "102"), vm.videos.map { it.id })
            assertEquals(1, fake.calls.count { it == (FeedKind.LATEST to 1) })
        } finally { vm.close() }
    }

    @Test fun feedScrollPositionsRemainSeparate() {
        val vm = controller(StubGateway())
        try {
            val latest = vm.feedScrollKey
            vm.saveScroll(latest, 23, 17)
            vm.select(FeedKind.WEEK)
            val weekly = vm.feedScrollKey
            assertEquals(0 to 0, vm.savedScroll(weekly))
            vm.saveScroll(weekly, 4, 9)
            assertEquals(23 to 17, vm.savedScroll(latest))
        } finally { vm.close() }
    }
}
