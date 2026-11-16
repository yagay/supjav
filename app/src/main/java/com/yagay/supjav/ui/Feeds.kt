package com.yagay.supjav.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.model.VideoItem
import kotlinx.coroutines.launch

@Composable
fun VideoTile(video: VideoItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(12.dp)) {
        Column {
            AsyncImage(
                model = video.coverUrl,
                contentDescription = video.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            Text(video.title, Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun FeedScreen(
    app: SupjavApp, kind: FeedKind, value: String, onVideo: (VideoItem) -> Unit
) {
    val data = remember(kind, value) { mutableStateListOf<VideoItem>() }
    var page by remember(kind, value) { mutableIntStateOf(0) }
    var loading by remember(kind, value) { mutableStateOf(false) }
    var eof by remember(kind, value) { mutableStateOf(false) }
    var error by remember(kind, value) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    suspend fun loadNext() {
        if (loading || eof) return
        loading = true
        error = null
        try {
            val next = app.source.list(kind, value, page + 1)
            val unseen = next.filterNot { result -> data.any { it.id == result.id } }
            data.addAll(unseen)
            page++
            if (next.isEmpty() || unseen.isEmpty()) eof = true
        } catch (e: Exception) { error = e.message ?: "获取列表失败" }
        finally { loading = false }
    }
    LaunchedEffect(kind, value) { loadNext() }
    val nearEnd by remember(grid, data) {
        derivedStateOf {
            data.isNotEmpty() && (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= data.size - 5
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd && !loading && !eof && error == null) loadNext()
    }
    Column(Modifier.fillMaxSize()) {
        if (error != null) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch { loadNext() } }) { Text("重试") }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && data.isEmpty() && error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无内容") }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(2), state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(data, key = { it.id }) { video ->
                    VideoTile(video, { onVideo(video) })
                }
                if (!eof && !loading) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { scope.launch { loadNext() } }) { Text("加载更多") }
                }
            }
        }
    }
}

@Composable
fun DiscoverScreen(app: SupjavApp, onVideo: (VideoItem) -> Unit) {
    var text by remember { mutableStateOf("") }
    var active by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(FeedKind.SEARCH) }
    var activeKind by remember { mutableStateOf(FeedKind.SEARCH) }
    Column {
        OutlinedTextField(value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
            placeholder = { Text("搜索番号、标题、分类、演员或标签") },
            trailingIcon = { TextButton(onClick = { active = text.trim(); activeKind = kind }) {
                Text("搜索")
            } })
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(FeedKind.SEARCH, FeedKind.CATEGORY, FeedKind.TAG, FeedKind.ACTOR, FeedKind.MAKER)
                .forEach { option ->
                    FilterChip(selected = kind == option, onClick = { kind = option },
                        label = { Text(option.title) })
                }
        }
        if (active.isNotBlank()) {
            Text("结果：" + active, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium)
            FeedScreen(app, activeKind, active, onVideo)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("输入关键词后搜索，也可以选择标签、演员或制作商")
            }
        }
    }
}
