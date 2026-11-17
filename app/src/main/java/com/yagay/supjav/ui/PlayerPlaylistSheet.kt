package com.yagay.supjav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yagay.supjav.model.VideoItem

/** Shows the actual video cards from the source page, not fabricated titles. */
@Composable
fun PlayerPlaylistSheet(
    videos: List<VideoItem>,
    activeVideoId: String?,
    switchingVideoId: String?,
    error: String?,
    onSelect: (VideoItem) -> Unit,
    onDismiss: () -> Unit
) {
    val activeIndex = videos.indexOfFirst { it.id == activeVideoId }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = activeIndex.coerceAtLeast(0)
    )
    // Position the visible list on the playing video when the sheet opens.
    // Also follow a genuine active-item change, without resetting on every recomposition.
    LaunchedEffect(activeVideoId, videos) {
        if (activeIndex >= 0) listState.scrollToItem(activeIndex)
    }
    SupjavModalSheet("播放列表 · ${videos.size} 部", onDismiss = onDismiss) {
        if (videos.isEmpty()) {
            Text("当前页面没有可用的作品列表",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(videos, key = { it.id }) { video ->
                    val isActive = video.id == activeVideoId
                    val isSwitching = video.id == switchingVideoId
                    val enabled = switchingVideoId == null && !isActive
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isActive) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .clickable(enabled = enabled) { onSelect(video) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AsyncImage(
                            model = video.coverUrl.ifBlank { null },
                            contentDescription = video.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 54.dp, height = 72.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Column(modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                video.title,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (video.code.isNotBlank()) {
                                Text(video.code, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isActive) {
                                Text("正在播放", color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        when {
                            isSwitching -> CircularProgressIndicator(
                                Modifier.size(22.dp), strokeWidth = 2.dp)
                            isActive -> Icon(Icons.Outlined.CheckCircle,
                                contentDescription = "当前作品",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        if (switchingVideoId != null) {
            Text("正在解析所选作品，成功后自动切换；当前视频保持不变。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
