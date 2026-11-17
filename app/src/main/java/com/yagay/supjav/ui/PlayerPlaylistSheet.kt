package com.yagay.supjav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.model.VideoItem

/**
 * Compact poster-only queue. Like the source page: three portrait columns,
 * overlaid code, and no repeated title beneath each image.
 */
@Composable
fun PlayerPlaylistSheet(
    videos: List<VideoItem>,
    activeVideoId: String?,
    sourceKind: FeedKind?,
    paging: Boolean,
    endReached: Boolean,
    pagingError: String?,
    onLoadMore: () -> Unit,
    switchingVideoId: String?,
    error: String?,
    onSelect: (VideoItem) -> Unit,
    onDismiss: () -> Unit
) {
    val activeIndex = videos.indexOfFirst { it.id == activeVideoId }
    val grid = rememberLazyGridState(
        initialFirstVisibleItemIndex = (activeIndex.coerceAtLeast(0) / 3) * 3
    )
    // Anchor on the current item's ROW. Do not re-scroll when an extra page
    // arrives: it must remain possible to browse farther down the list.
    LaunchedEffect(activeVideoId) {
        if (activeIndex >= 0) grid.scrollToItem((activeIndex / 3) * 3)
    }
    val lastVisible by remember(grid) {
        derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
    }
    LaunchedEffect(lastVisible, videos.size, paging, endReached, pagingError) {
        if (lastVisible >= videos.size - 6 && videos.isNotEmpty() &&
            !paging && !endReached && pagingError == null) {
            onLoadMore()
        }
    }

    SupjavModalSheet("播放列表 · ${videos.size} 部", onDismiss = onDismiss) {
        if (sourceKind != null) {
            Text("来源：${sourceKind.title} · 向下滑动加载更多",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (videos.isEmpty()) {
            Text("当前页面没有可用的作品",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = grid,
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(videos, key = { it.id }) { video ->
                    val active = video.id == activeVideoId
                    val switching = video.id == switchingVideoId
                    val shape = RoundedCornerShape(9.dp)
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable(enabled = switchingVideoId == null && !active) {
                                onSelect(video)
                            }
                            .border(
                                width = if (active) 2.dp else 0.dp,
                                color = if (active) MaterialTheme.colorScheme.primary
                                    else Color.Transparent,
                                shape = shape
                            )
                    ) {
                        AsyncImage(
                            model = video.coverUrl.ifBlank { null },
                            contentDescription = video.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (video.code.isNotBlank()) {
                            Surface(
                                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
                                color = Color.Black.copy(alpha = .76f),
                                shape = RoundedCornerShape(5.dp)
                            ) {
                                Text(
                                    video.code.take(30),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        if (active) {
                            Surface(
                                modifier = Modifier.align(Alignment.TopStart).padding(5.dp),
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(5.dp)
                            ) {
                                Text("播放中", modifier = Modifier.padding(
                                    horizontal = 5.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (switching) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)),
                                contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    Modifier.size(25.dp), color = Color.White, strokeWidth = 2.dp)
                            }
                        }
                    }
                }
                if (!endReached || paging || pagingError != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center) {
                            when {
                                paging -> CircularProgressIndicator(Modifier.size(24.dp))
                                pagingError != null -> TextButton(onClick = onLoadMore) {
                                    Text("加载失败 · 点击重试")
                                }
                                !endReached -> TextButton(onClick = onLoadMore) {
                                    Text("加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (switchingVideoId != null) {
            Text("正在切换作品，原视频保持播放",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
