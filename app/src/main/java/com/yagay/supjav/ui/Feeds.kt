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
    var localRefresh by remember(kind, value, refreshKey) { mutableIntStateOf(0) }
    val pageState = remember(app, kind, value, refreshKey, localRefresh) {
        app.feedCache.getOrCreate(kind, value, refreshKey * 10000 + localRefresh)
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

    suspend fun loadMore() {
        if (pageState.loading || pageState.endReached) return
        pageState.loading = true
        pageState.error = null
        try {
            val next = app.source.list(kind, value, pageState.page + 1)
            val knownIds = data.mapTo(HashSet(data.size + next.size)) { it.id }
            val unseen = next.filter { knownIds.add(it.id) }
            data.addAll(unseen)
            pageState.page++
            pageState.loaded = true
            // One duplicate page is not the end of a paginated feed. Two
            // consecutive duplicate pages are a bounded protection against
            // endlessly cycling websites.
            pageState.duplicatePageStreak = if (next.isNotEmpty() && unseen.isEmpty())
                pageState.duplicatePageStreak + 1 else 0
            if (next.isEmpty() || pageState.duplicatePageStreak >= 2)
                pageState.endReached = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pageState.error = e.message?.take(160) ?: "请求失败"
        } finally {
            pageState.loading = false
        }
    }

    LaunchedEffect(pageState) {
        if (!pageState.loaded && pageState.error == null) loadMore()
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
            loadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = pageState.loading && data.isNotEmpty() && pageState.page == 0,
        onRefresh = { localRefresh++ },
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
                                ) { Text("加载失败，点击重试") }
                                else -> TextButton(
                                    onClick = { scope.launch { loadMore() } }
                                ) { Text("加载更多") }
                            }
                        }
                    }
                }
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
