@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.yagay.supjav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kaixinguo-like compact cards. Long press opens real local actions. */
@Composable
fun VideoTile(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    app: SupjavApp? = null,
    rank: Int? = null
) {
    var menuOpen by remember(video.id) { mutableStateOf(false) }
    var saved by remember(video.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(menuOpen, video.id) {
        if (menuOpen && app != null) saved = withContext(Dispatchers.IO) {
            app.library.isFavorite(video.id)
        }
    }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(SupjavMetrics.cardRadius))
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
        ) {
            Box {
                AsyncImage(
                    model = video.coverUrl.ifBlank { null },
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)
                        .clip(RoundedCornerShape(SupjavMetrics.cardRadius))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentScale = ContentScale.Crop
                )
                if (rank != null) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = .65f)
                    ) {
                        Text(rank.toString(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (video.code.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = .7f)
                    ) {
                        Text(video.code,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color.White, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(
                video.title,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("查看详情") },
                leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                onClick = { menuOpen = false; onClick() }
            )
            if (app != null) {
                DropdownMenuItem(
                    text = { Text(if (saved) "取消收藏" else "收藏") },
                    leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, null) },
                    onClick = {
                        menuOpen = false
                        scope.launch {
                            saved = withContext(Dispatchers.IO) {
                                app.library.toggleFavorite(video)
                            }
                        }
                    }
                )
            }
        }
    }
}

/** Cached pages with pull-to-refresh, retry and retained scroll offset. */
@Composable
fun FeedScreen(
    app: SupjavApp, kind: FeedKind, value: String,
    onVideo: (VideoItem) -> Unit, refreshKey: Int = 0
) {
    // The cached feed survives tab/detail navigation. A refresh updates the
    // same list atomically instead of constructing an empty, new cache page.
    val pageState = remember(app, kind, value) {
        app.feedCache.getOrCreate(kind, value, 0)
    }
    val data = pageState.items
    val grid = key(pageState) {
        rememberLazyGridState(
            initialFirstVisibleItemIndex = pageState.firstVisibleIndex,
            initialFirstVisibleItemScrollOffset = pageState.firstVisibleOffset
        )
    }
    val scope = rememberCoroutineScope()
    val layoutSetting = app.preferences.getInt("poster_columns", 0)
    val columns = when (layoutSetting) {
        2 -> GridCells.Fixed(2)
        3 -> GridCells.Fixed(3)
        else -> GridCells.Adaptive(SupjavMetrics.posterMinWidth)
    }

    suspend fun loadMore(forceRefresh: Boolean = false) {
        // A refresh requested during pagination must run after that request.
        if (pageState.loading) {
            if (forceRefresh) pageState.pendingRefresh = true
            return
        }
        if (!forceRefresh && pageState.endReached) return
        if (forceRefresh) pageState.pendingRefresh = false
        pageState.loading = true
        pageState.refreshing = forceRefresh
        pageState.error = null
        val targetPage = if (forceRefresh) 1 else pageState.page + 1
        val cursor = if (forceRefresh) null else pageState.nextPageUrl
        try {
            val response = app.source.listPage(kind, value, targetPage, cursor)
            val oldCount = data.size
            val previousIds = if (forceRefresh) data.mapTo(HashSet()) { it.id }
                else emptySet()
            val knownIds = (if (forceRefresh) emptyList() else data)
                .mapTo(HashSet<String>(oldCount + response.items.size)) { it.id }
            val unseen = response.items.filter { knownIds.add(it.id) }
            // A failed refresh may return an empty challenge/placeholder
            // page. Do not destroy the already-visible cached results.
            if (forceRefresh && response.items.isEmpty() && oldCount > 0) {
                throw IllegalStateException("站点返回空列表，已保留原有作品，请稍后刷新")
            }
            if (forceRefresh) {
                data.clear()
                data.addAll(unseen)
                pageState.page = 1
                pageState.duplicatePageStreak = 0
                pageState.endReached = false
                if (data.isNotEmpty()) grid.scrollToItem(0)
                val newCount = unseen.count { it.id !in previousIds }
                pageState.notice = if (newCount > 0)
                    "刷新完成 · 新增 $newCount 部" else "刷新完成 · 暂无新作品"
            } else {
                data.addAll(unseen)
                pageState.page = targetPage
                pageState.duplicatePageStreak =
                    if (response.items.isNotEmpty() && unseen.isEmpty())
                        pageState.duplicatePageStreak + 1 else 0
            }
            pageState.loaded = true
            // Following the website's pagination is more reliable than
            // synthesizing page/2/... and hoping its routing matches.
            pageState.nextPageUrl = response.nextUrl?.takeUnless {
                it == cursor
            }
            pageState.hasPager = response.hasPager
            // Missing 'next' anchors are common with alternate site themes.
            // Probe the next conventional page instead of declaring end here.
            // Stop only when the requested page contains no videos.
            pageState.endReached = response.items.isEmpty()
            if (response.items.isNotEmpty() && response.nextUrl == null) {
                com.yagay.supjav.util.Diagnostics.log(
                    "FEED_NEXT_FALLBACK kind=${kind.name} fromPage=$targetPage")
            }
            if (pageState.duplicatePageStreak >= 2) {
                // A site which repeats page one for every page number must
                // not silently look like "end of list". Expose the problem
                // and keep the manual retry available.
                pageState.error = "分页连续返回重复作品。可重试加载或下拉刷新。"
            }
            com.yagay.supjav.util.Diagnostics.log(
                "FEED_PAGE kind=${kind.name} page=${pageState.page}" +
                    " added=${unseen.size} total=${data.size}" +
                    " next=${pageState.nextPageUrl != null} end=${pageState.endReached}" +
                    " duplicates=${pageState.duplicatePageStreak}"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pageState.error = e.message?.take(160) ?: "加载失败，请重试"
            if (forceRefresh && data.isNotEmpty()) {
                pageState.notice = "刷新失败 · 已保留原列表"
            }
            com.yagay.supjav.util.Diagnostics.log(
                "FEED_PAGE_FAILED kind=${kind.name} page=$targetPage" +
                    " error=${e.javaClass.simpleName}"
            )
        } finally {
            pageState.loading = false
            pageState.refreshing = false
        }
    }

    LaunchedEffect(pageState, pageState.pendingRefresh, pageState.loading) {
        if (pageState.pendingRefresh && !pageState.loading) {
            pageState.pendingRefresh = false
            // The effect keys include loading; the actual network job must
            // outlive the effect's restart when loading changes to true.
            scope.launch { loadMore(forceRefresh = true) }
        }
    }
    LaunchedEffect(pageState, refreshKey) {
        // Toolbar refresh and pull-to-refresh use exactly the same path.
        if (!pageState.loaded && pageState.error == null) {
            pageState.lastRefreshKey = refreshKey
            loadMore()
        } else if (pageState.lastRefreshKey != refreshKey) {
            pageState.lastRefreshKey = refreshKey
            loadMore(forceRefresh = true)
        }
    }
    LaunchedEffect(pageState, pageState.notice) {
        if (pageState.notice != null) {
            kotlinx.coroutines.delay(2_200)
            pageState.notice = null
        }
    }
    DisposableEffect(grid, pageState) {
        onDispose {
            pageState.firstVisibleIndex = grid.firstVisibleItemIndex
            pageState.firstVisibleOffset = grid.firstVisibleItemScrollOffset
        }
    }
    val lastVisible by remember(grid) {
        derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
    }
    LaunchedEffect(pageState, lastVisible, pageState.loading, pageState.endReached, pageState.error) {
        if (data.isNotEmpty() && lastVisible >= data.size - 9 &&
            !pageState.loading && !pageState.endReached && pageState.error == null) {
            // Keep the pagination job in the composable scope rather than
            // this effect: changing loading/endReached otherwise cancels it.
            scope.launch { loadMore() }
        }
    }

    PullToRefreshBox(
        isRefreshing = pageState.refreshing,
        onRefresh = { scope.launch { loadMore(forceRefresh = true) } },
        modifier = Modifier.fillMaxSize()
    ) {
        if (data.isEmpty() && pageState.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (data.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SupjavEmptyState(pageState.error ?: "暂时没有作品",
                    onRetry = if (pageState.error != null) ({
                        scope.launch { loadMore() }
                    }) else null)
            }
        } else {
            Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = columns,
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                itemsIndexed(data, key = { _, video -> video.id }) { index, video ->
                    VideoTile(
                        video, onClick = { onVideo(video) }, app = app,
                        rank = if (kind in setOf(FeedKind.DAY, FeedKind.WEEK, FeedKind.MONTH))
                            index + 1 else null
                    )
                }
                if (pageState.loading || pageState.error != null || !pageState.endReached) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                            when {
                                pageState.loading -> CircularProgressIndicator(Modifier.size(24.dp))
                                pageState.error != null -> TextButton(
                                    onClick = { scope.launch { loadMore() } }
                                ) { Text(pageState.error.orEmpty().take(60) + " · 重试") }
                                else -> TextButton(
                                    onClick = { scope.launch { loadMore() } }
                                ) { Text("加载更多") }
                            }
                        }
                    }
                }
            }
            pageState.notice?.let { message ->
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter)
                        .padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 2.dp
                ) {
                    Text(message, modifier = Modifier.padding(
                        horizontal = 12.dp, vertical = 6.dp
                    ), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (pageState.refreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                )
            }
            if (grid.firstVisibleItemIndex >= 12) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { grid.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Icon(Icons.Outlined.KeyboardArrowUp, "回到顶部")
                }
            }
            }
        }
    }
}
