package com.yagay.supjav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.model.VideoItem
import kotlinx.coroutines.launch

@Composable
fun VideoTile(video: VideoItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(SupjavMetrics.cardRadius))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
    ) {
        Box {
            AsyncImage(
                model = video.coverUrl.ifBlank { null },
                contentDescription = video.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.74f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            if (video.code.isNotBlank()) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(7.dp),
                    color = Color.Black.copy(alpha = 0.69f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(video.code, Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall, color = Color.White,
                        maxLines = 1)
                }
            }
        }
        Text(
            video.title,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun FeedScreen(
    app: SupjavApp, kind: FeedKind, value: String,
    onVideo: (VideoItem) -> Unit, refreshKey: Int = 0
) {
    val data = remember(kind, value, refreshKey) { mutableStateListOf<VideoItem>() }
    var page by remember(kind, value, refreshKey) { mutableIntStateOf(0) }
    var loading by remember(kind, value, refreshKey) { mutableStateOf(false) }
    var endReached by remember(kind, value, refreshKey) { mutableStateOf(false) }
    var error by remember(kind, value, refreshKey) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val grid = androidx.compose.runtime.key(kind, value, refreshKey) { rememberLazyGridState() }

    suspend fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        try {
            val next = app.source.list(kind, value, page + 1)
            val unique = next.filterNot { candidate -> data.any { it.id == candidate.id } }
            data.addAll(unique)
            page++
            if (next.isEmpty() || unique.isEmpty()) endReached = true
        } catch (e: Exception) {
            error = e.message?.take(160) ?: "网络或解析失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(kind, value, refreshKey) { loadMore() }

    val lastVisible by remember(grid) {
        derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
    }
    LaunchedEffect(lastVisible, loading, endReached, error) {
        if (data.isNotEmpty() && lastVisible >= data.size - 5 &&
            !loading && !endReached && error == null) loadMore()
    }
    Column(Modifier.fillMaxSize()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (data.isEmpty() && !loading) {
            SupjavEmptyState(
                error ?: "当前列表暂无内容",
                onRetry = if (error != null) ({ scope.launch { loadMore() } }) else null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                state = grid,
                contentPadding = PaddingValues(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(data, key = { it.id }) { video ->
                    VideoTile(video, { onVideo(video) })
                }
                if (loading || error != null || !endReached) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when {
                                loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                error != null -> TextButton(onClick = { scope.launch { loadMore() } }) {
                                    Text("加载失败，点击重试")
                                }
                                else -> TextButton(onClick = { scope.launch { loadMore() } }) {
                                    Text("加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoverScreen(
    app: SupjavApp,
    onVideo: (VideoItem) -> Unit,
    initialKind: FeedKind = FeedKind.SEARCH,
    initialValue: String = ""
) {
    var text by remember { mutableStateOf(initialValue) }
    var selectedKind by remember { mutableStateOf(initialKind) }
    var activeKind by remember { mutableStateOf(initialKind) }
    var activeValue by remember { mutableStateOf(initialValue) }
    LaunchedEffect(initialKind, initialValue) {
        selectedKind = initialKind
        text = initialValue
        activeKind = initialKind
        activeValue = initialValue
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
            shape = RoundedCornerShape(SupjavMetrics.controlRadius),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            placeholder = { Text("搜索标题、番号、分类、演员或标签") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                activeKind = selectedKind
                activeValue = text.trim()
            }),
            trailingIcon = {
                TextButton(onClick = {
                    activeKind = selectedKind
                    activeValue = text.trim()
                }) { Text("搜索") }
            }
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(FeedKind.SEARCH, FeedKind.CATEGORY, FeedKind.TAG, FeedKind.ACTOR, FeedKind.MAKER)
                .forEach { option ->
                    FilterChip(
                        selected = selectedKind == option,
                        onClick = { selectedKind = option },
                        label = { Text(option.title) }
                    )
                }
        }
        if (activeValue.isBlank()) {
            SupjavEmptyState("输入关键词后搜索；或者从作品详情点击标签/演员", modifier = Modifier.fillMaxSize())
        } else {
            Text(
                activeKind.title + " · " + activeValue,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
            FeedScreen(app, activeKind, activeValue, onVideo)
        }
    }
}
