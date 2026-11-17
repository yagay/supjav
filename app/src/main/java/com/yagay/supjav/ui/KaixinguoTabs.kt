@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yagay.supjav.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.BugReport
import com.yagay.supjav.util.Diagnostics
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Site home selection, not a claim of personalized recommendation. */
@Composable
fun RecommendPage(
    app: SupjavApp, onVideo: (VideoItem) -> Unit,
    onSearch: () -> Unit, refreshKey: Int
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                SupjavSectionTitle("发现作品", "来自 Supjav 网站首页，非账号个性化推荐")
            }
            TextButton(onClick = onSearch) { Text("分类筛选") }
        }
        FeedScreen(app, FeedKind.LATEST, "", onVideo, refreshKey)
    }
}

@Composable
fun RankPage(app: SupjavApp, onVideo: (VideoItem) -> Unit) {
    var selected by rememberSaveableRank() 
    val ranks = listOf(FeedKind.DAY, FeedKind.WEEK, FeedKind.MONTH)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SupjavSectionTitle("热门排行榜", "按网站提供的日 / 周 / 月热门排序")
        }
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            ranks.forEachIndexed { index, kind ->
                SegmentedButton(
                    selected = selected == index,
                    onClick = { selected = index },
                    shape = SegmentedButtonDefaults.itemShape(index, ranks.size),
                    icon = {}
                ) { Text(listOf("日榜", "周榜", "月榜")[index]) }
            }
        }
        FeedScreen(app, ranks[selected], "", onVideo)
    }
}

@Composable
private fun rememberSaveableRank(): MutableIntState =
    androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }

@Composable
fun NewReleasesPage(app: SupjavApp, onVideo: (VideoItem) -> Unit, refreshKey: Int = 0) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SupjavSectionTitle("最新更新", "Supjav 网站最新发布的作品")
        }
        FeedScreen(app, FeedKind.LATEST, "", onVideo, refreshKey)
    }
}

@Composable
fun SearchPage(
    app: SupjavApp,
    onVideo: (VideoItem) -> Unit,
    initialKind: FeedKind = FeedKind.SEARCH,
    initialValue: String = ""
) {
    var text by remember { mutableStateOf(initialValue) }
    var kind by remember { mutableStateOf(initialKind) }
    var activeValue by remember { mutableStateOf(initialValue) }
    var activeLabel by remember { mutableStateOf(initialValue) }
    var activeKind by remember { mutableStateOf(initialKind) }
    var recent by remember { mutableStateOf(emptyList<String>()) }
    var options by remember { mutableStateOf(app.categoryStore.snapshot()) }
    var optionsError by remember { mutableStateOf<String?>(null) }
    var optionsLoading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(app, initialKind, initialValue) {
        if (initialValue.isNotEmpty()) {
            kind = initialKind
            activeKind = initialKind
            activeValue = initialValue
            activeLabel = initialValue
            text = initialValue
        }
        recent = withContext(Dispatchers.IO) { app.searchStore.recent() }
    }
    LaunchedEffect(app) {
        try {
            val observed = app.source.browseOptions()
            options = withContext(Dispatchers.IO) {
                app.categoryStore.save(observed)
                app.categoryStore.snapshot()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            optionsError = e.message?.take(100) ?: "网站分类暂时无法加载"
        } finally {
            optionsLoading = false
        }
    }

    fun submit(value: String, chosenKind: FeedKind = kind, label: String = value) {
        val term = value.trim()
        if (term.isBlank()) return
        kind = chosenKind
        activeKind = chosenKind
        activeValue = term
        activeLabel = label
        text = label
        refresh++
        if (chosenKind == FeedKind.SEARCH) {
            scope.launch(Dispatchers.IO) { app.searchStore.add(term) }
            recent = (listOf(term) + recent.filterNot { it == term }).take(20)
        }
        focus.clearFocus()
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            singleLine = true,
            placeholder = { Text("搜索标题、番号、演员、标签") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                TextButton(onClick = { submit(text) }) { Text("搜索") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(text) })
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(FeedKind.SEARCH, FeedKind.CATEGORY, FeedKind.TAG, FeedKind.ACTOR, FeedKind.MAKER)
                .forEach { option ->
                    FilterChip(selected = kind == option, onClick = {
                        kind = option
                        activeValue = ""
                    }, label = { Text(option.title) })
                }
        }

        if (activeValue.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${activeKind.title}：${activeLabel}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = {
                    activeValue = ""
                    text = ""
                }) { Text("清除") }
            }
            FeedScreen(app, activeKind, activeValue, onVideo, refresh)
        } else {
            val shown = options.filter { it.kind == kind || kind == FeedKind.SEARCH }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (recent.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SupjavSectionTitle("最近搜索")
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) { app.searchStore.clear() }
                            recent = emptyList()
                        }) { Icon(Icons.Outlined.DeleteOutline, "清空搜索历史") }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recent.take(10).forEach { word ->
                            AssistChip(onClick = { submit(word, FeedKind.SEARCH) },
                                label = { Text(word) })
                        }
                    }
                }
                SupjavSectionTitle("浏览分类", "直接选取网站提供的标签、演员或分类")
                if (optionsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (optionsError != null) Text(optionsError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!optionsLoading && options.isEmpty()) {
                    Text("网站未返回分类目录，也可以输入分类或关键词进行搜索。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                shown.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { option ->
                            AssistChip(
                                onClick = { submit(option.path, option.kind, option.label) },
                                label = { Text(option.label, maxLines = 1) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                SupjavSectionTitle("搜索帮助")
                Text("支持关键字、作品编号、标签、演员和制作商。实际分类取决于网站当前提供的数据。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MinePage(
    app: SupjavApp,
    onVideo: (VideoItem) -> Unit,
    onSettings: () -> Unit,
    revision: Int = 0
) {
    var watched by remember { mutableStateOf(emptyList<VideoItem>()) }
    var saved by remember { mutableStateOf(emptyList<VideoItem>()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(app, revision, refresh) {
        val lists = withContext(Dispatchers.IO) {
            app.library.list(false) to app.library.list(true)
        }
        watched = lists.first
        saved = lists.second
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(SupjavMetrics.posterMinWidth),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                SupjavSectionTitle("我的", "最近观看和本地收藏")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { refresh++ }) { Text("刷新") }
                IconButton(onClick = {
                    runCatching { Diagnostics.share(app) }
                        .onFailure { Diagnostics.log("EXPORT_FAILED " + it.javaClass.simpleName) }
                }) { Icon(Icons.Outlined.BugReport, "导出诊断 ZIP") }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Outlined.Settings, "打开设置")
                }
            }
        }
        if (watched.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SupjavSectionTitle("最近观看")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(watched.take(15), key = { it.id }) { video ->
                            VideoTile(video, onClick = { onVideo(video) },
                                modifier = Modifier.width(112.dp))
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SupjavSectionTitle("收藏", "${saved.size} 部作品")
        }
        if (saved.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SupjavEmptyState("还没有收藏。打开作品详情即可添加收藏。")
            }
        } else {
            gridItems(saved, key = { it.id }) { video ->
                VideoTile(video, onClick = { onVideo(video) })
            }
        }
    }
}
