package com.yagay.supjav.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.compose.ui.awt.SwingPanel
import coil3.compose.AsyncImage
import com.yagay.supjav.model.*
import com.yagay.supjav.player.VideoParts
import com.yagay.supjav.player.PlaybackQueuePolicy
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailActions(vm: DesktopController) {
    val detail = vm.detail
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(vm.activeVideo?.title ?: vm.playingVideo?.title.orEmpty(),
                Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = vm::toggleFavorite) {
                Text(if (vm.activeVideo?.id?.let(vm::isFavorite) == true)
                    "取消收藏" else "收藏")
            }
            TextButton(onClick = vm::openOfficialPage) { Text("网页兼容播放") }
        }
        if (vm.playingVideo != null && vm.playingVideo?.id != vm.activeVideo?.id) {
            Text("正在播放：" + vm.playingVideo?.title.orEmpty(),
                style = MaterialTheme.typography.labelSmall, color = green)
        }
        if (vm.pendingVideo != null) {
            Text("准备切换：" + vm.pendingVideo?.title.orEmpty(),
                style = MaterialTheme.typography.labelSmall, color = green)
        }
        if (detail == null) {
            Text(vm.status, color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall)
        } else {
            val parts = VideoParts.available(detail.servers)
            if (parts.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("视频分段", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(10.dp))
                    parts.forEach { part ->
                        FilterChip(
                            selected = VideoParts.partOf(vm.activeServer ?: detail.servers.first()) == part,
                            onClick = { vm.changePart(part) },
                            label = { Text(part) },
                            modifier = Modifier.padding(end = 7.dp),
                            enabled = !vm.switching
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("线路", style = MaterialTheme.typography.labelMedium)
                val currentPart = vm.activeServer?.let(VideoParts::partOf) ?: parts.firstOrNull()
                detail.servers.filter { server ->
                    parts.size < 2 || VideoParts.partOf(server) == currentPart
                }.forEach { line ->
                    FilterChip(
                        selected = line.id == vm.activeServer?.id,
                        onClick = { vm.play(line) },
                        label = { Text(line.name) },
                        enabled = !vm.switching
                    )
                }
            }
            if (vm.switching) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun PosterGrid(vm: DesktopController, modifier: Modifier) {
    val scrollKey = vm.feedScrollKey
    val saved = remember(scrollKey) { vm.savedScroll(scrollKey) }
    val grid = rememberLazyGridState(
        initialFirstVisibleItemIndex = saved.first,
        initialFirstVisibleItemScrollOffset = saved.second
    )
    DisposableEffect(scrollKey, grid) {
        onDispose {
            vm.saveScroll(scrollKey, grid.firstVisibleItemIndex,
                grid.firstVisibleItemScrollOffset)
        }
    }
    val last by remember { derivedStateOf {
        grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    } }
    LaunchedEffect(last, vm.videos.size, vm.pageLoading, vm.reachedEnd, vm.pageError) {
        if (last >= vm.videos.size - 5 && vm.videos.isNotEmpty() &&
            !vm.pageLoading && !vm.reachedEnd && vm.pageError == null) vm.loadMore()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(142.dp),
        state = grid,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        items(vm.videos, key = { it.id }) { item ->
            Poster(item, active = vm.activeVideo?.id == item.id) { vm.open(item) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                if (vm.pageLoading) CircularProgressIndicator(Modifier.size(23.dp))
                else if (vm.pageError != null) TextButton(
                    onClick = { if (vm.videos.isEmpty()) vm.loadFeed() else vm.loadMore() }
                ) { Text("加载失败 · 点击重试") }
                else if (!vm.reachedEnd) TextButton(onClick = vm::loadMore) { Text("加载更多") }
            }
        }
    }
}

@Composable
private fun Poster(item: VideoItem, active: Boolean, click: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .clickable(onClick = click),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(9.dp))
                .background(card)
                .border(
                    if (active) 2.dp else 0.dp,
                    if (active) green else Color.Transparent,
                    RoundedCornerShape(9.dp)
                )
        ) {
            AsyncImage(
                model = item.coverUrl.ifBlank { null },
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (item.code.isNotBlank())
                Text(item.code, modifier = Modifier.align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.77f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall)
        }
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun CategoryGrid(vm: DesktopController, modifier: Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(200.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(vm.categories, key = { it.path }) { category ->
            OutlinedButton(
                onClick = { vm.select(category.kind, category.path) },
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text(category.kind.title + " · " + category.label, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun QueueSidebar(vm: DesktopController, modifier: Modifier) {
    val grid = rememberLazyGridState()
    val currentVideoId = (vm.playingVideo ?: vm.activeVideo)?.id
    val idx = vm.playlistItems.indexOfFirst { it.id == currentVideoId }
    LaunchedEffect(currentVideoId) {
        if (idx >= 0) grid.scrollToItem((idx / 2) * 2)
    }
    val last by remember { derivedStateOf {
        grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    } }
    LaunchedEffect(last, vm.playlistItems.size, vm.queueLoading,
        vm.queueEndReached, vm.queueError) {
        if (last >= vm.playlistItems.size - 3 && vm.playlistItems.isNotEmpty() &&
            !vm.queueLoading && vm.canLoadQueueMore && vm.queueError == null)
            vm.loadQueueMore()
    }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("播放列表", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(vm.playlistItems.size.toString(), style = MaterialTheme.typography.labelSmall)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = grid,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(vm.playlistItems, key = { it.id }) { item ->
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.72f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(card)
                        .clickable { vm.open(item, autoplay = true) }
                        .border(
                            if (item.id == currentVideoId) 2.dp else 0.dp,
                            if (item.id == currentVideoId) green else Color.Transparent,
                            RoundedCornerShape(7.dp)
                        )
                ) {
                    AsyncImage(
                        model = item.coverUrl.ifBlank { null },
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (item.code.isNotBlank())
                        Text(item.code, modifier = Modifier.align(Alignment.BottomStart)
                            .background(Color.Black.copy(alpha = .75f))
                            .padding(4.dp), color = Color.White,
                            maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (vm.canLoadQueueMore || vm.queueLoading || vm.queueError != null) {
            TextButton(
                onClick = vm::loadQueueMore,
                enabled = !vm.queueLoading && vm.canLoadQueueMore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(when {
                    vm.queueLoading -> "正在加载..."
                    vm.queueError != null -> "加载失败 · 点击重试"
                    else -> "加载更多"
                })
            }
        }
    }
}
