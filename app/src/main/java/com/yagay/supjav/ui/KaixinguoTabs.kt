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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
                SupjavSectionTitle("发现作品", "站点首页 · 下拉刷新")
            }
            TextButton(onClick = onSearch) {
                Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("分类")
            }
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
fun MinePage(
    app: SupjavApp,
    onVideo: (VideoItem) -> Unit,
    onSettings: () -> Unit,
    revision: Int = 0
) {
    var watched by remember { mutableStateOf(emptyList<VideoItem>()) }
    var saved by remember { mutableStateOf(emptyList<VideoItem>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(app, revision, refresh) {
        val lists = withContext(Dispatchers.IO) {
            app.library.list(false) to app.library.list(true)
        }
        watched = lists.first
        saved = lists.second
    }
    val selectedList = if (showHistory) watched else saved
    LazyVerticalGrid(
        columns = GridCells.Adaptive(SupjavMetrics.posterMinWidth),
        contentPadding = PaddingValues(
            start = SupjavMetrics.pageMargin,
            end = SupjavMetrics.pageMargin, bottom = 28.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    SupjavSectionTitle("本地片库", "管理收藏与最近播放")
                }
                IconButton(onClick = { refresh++ }) {
                    Icon(Icons.Outlined.Refresh, "刷新片库")
                }
                IconButton(onClick = {
                    runCatching { Diagnostics.share(app) }
                        .onFailure { Diagnostics.log("EXPORT_FAILED " + it.javaClass.simpleName) }
                }) { Icon(Icons.Outlined.BugReport, "导出诊断 ZIP") }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Outlined.Settings, "打开设置")
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !showHistory,
                    onClick = { showHistory = false },
                    label = { Text("收藏 ${saved.size}") }
                )
                FilterChip(
                    selected = showHistory,
                    onClick = { showHistory = true },
                    label = { Text("最近播放 ${watched.size}") }
                )
            }
        }
        if (selectedList.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SupjavEmptyState(if (showHistory) {
                    "还没有播放记录。播放过的作品会显示在这里。"
                } else {
                    "还没有收藏。打开作品详情即可加入收藏。"
                })
            }
        } else {
            gridItems(selectedList, key = { it.id }) { video ->
                VideoTile(video, onClick = { onVideo(video) }, app = app)
            }
        }
    }
}
