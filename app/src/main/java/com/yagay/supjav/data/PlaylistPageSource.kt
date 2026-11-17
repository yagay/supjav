package com.yagay.supjav.data

import com.yagay.supjav.SupjavApp
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.util.Diagnostics
import kotlinx.coroutines.CancellationException

/**
 * The player playlist points to the SAME page state as the source feed.
 * Loading another page from the player therefore updates the source grid too.
 */
data class PlaylistPageSource(
    val kind: FeedKind,
    val value: String,
    val pageState: FeedPageState
)

object PlaylistPageLoader {
    suspend fun loadMore(app: SupjavApp, source: PlaylistPageSource) {
        val state = source.pageState
        if (state.loading || state.endReached) return
        val page = state.page + 1
        val cursor = state.nextPageUrl
        state.loading = true
        state.error = null
        try {
            val result = app.catalog.listing(source.kind, source.value, page, cursor)
            val known = state.items.mapTo(HashSet<String>()) { it.id }
            val added = result.items.filter { known.add(it.id) }
            state.items.addAll(added)
            state.page = page
            state.loaded = true
            state.nextPageUrl = result.nextUrl?.takeUnless { it == cursor }
            state.hasPager = result.hasPager
            // A missing "next" anchor is not end-of-list; the existing
            // catalog fallback handles a conventional next page.
            state.endReached = result.items.isEmpty()
            state.duplicatePageStreak =
                if (result.items.isNotEmpty() && added.isEmpty())
                    state.duplicatePageStreak + 1 else 0
            if (state.duplicatePageStreak >= 2) {
                state.error = "分页连续返回重复作品。请稍后重试。"
            }
            Diagnostics.log("PLAYLIST_PAGE kind=" + source.kind.name +
                " page=" + page + " added=" + added.size +
                " total=" + state.items.size +
                " end=" + state.endReached)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            state.error = error.message?.take(160) ?: "加载下一页失败，请重试"
            Diagnostics.log("PLAYLIST_PAGE_FAILED kind=" + source.kind.name +
                " page=" + page + " error=" + error.javaClass.simpleName)
        } finally {
            state.loading = false
        }
    }
}
