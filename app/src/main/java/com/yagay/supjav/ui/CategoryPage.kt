package com.yagay.supjav.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.util.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Category index reads actual site links, not a guessed list of category labels. */
@Composable
fun CategoryPage(
    app: SupjavApp,
    onVideo: (VideoItem) -> Unit,
    initialKind: FeedKind? = null,
    initialPath: String = "",
    directoryFilter: String = "",
    backEnabled: Boolean = true
) {
    var directory by remember { mutableStateOf(app.categoryStore.snapshot()) }
    var pinned by remember { mutableStateOf(app.categoryStore.pins().toSet()) }
    var visited by remember { mutableStateOf(app.categoryStore.recent()) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var directoryPageDepth by remember { mutableIntStateOf(2) }
    var directoryHasMore by remember { mutableStateOf(false) }
    var directorySources by remember { mutableIntStateOf(0) }
    var chosenKind by rememberSaveable { mutableStateOf(FeedKind.CATEGORY) }
    var initialFilter by remember(chosenKind) { mutableStateOf("全部") }
    var selectedKind by remember { mutableStateOf<FeedKind?>(null) }
    var selectedPath by rememberSaveable { mutableStateOf("") }
    var selectedTitle by rememberSaveable { mutableStateOf("") }
    // Directory filtering shares the page's inline search field.

    LaunchedEffect(initialKind, initialPath) {
        if (initialKind != null && initialPath.isBlank()) {
            chosenKind = initialKind
            selectedKind = null
            selectedPath = ""
            selectedTitle = ""
        } else if (initialKind != null && initialPath.isNotBlank()) {
            selectedKind = initialKind
            selectedPath = initialPath
            selectedTitle = initialPath.substringAfterLast('/').replace('-', ' ')
            chosenKind = initialKind
            app.categoryStore.markVisited(initialPath)
            visited = app.categoryStore.recent()
        } else if (initialKind == null) {
            // Opening the primary Category tab should start at the directory,
            // not unexpectedly reopen a previously selected item.
            selectedKind = null
            selectedPath = ""
            selectedTitle = ""
        }
    }
    LaunchedEffect(app, reload) {
        fetching = true
        fetchError = null
        try {
            val result = app.catalog.directory(directoryPageDepth, force = reload > 0)
            directoryHasMore = result.hasMore
            directorySources = result.sourceCount
            if (result.options.isNotEmpty()) {
                directory = withContext(Dispatchers.IO) { app.categoryStore.snapshot() }
            }
            if (result.failedSourceCount > 0) {
                fetchError = "部分目录来源暂时不可访问（${result.failedSourceCount} 个），已保留已有分类"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fetchError = e.message?.take(150) ?: "分类目录读取失败"
        } finally {
            fetching = false
        }
    }
    val current = selectedKind
    // A covered category page must not consume Back ahead of the visible
    // detail/player overlay. Root owns Back while an overlay is on top.
    BackHandler(enabled = backEnabled && current != null && selectedPath.isNotEmpty()) {
        Diagnostics.log("NAV_BACK_CATEGORY_TO_DIRECTORY")
        selectedKind = null
        selectedPath = ""
    }
    if (current != null && selectedPath.isNotEmpty()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    selectedKind = null
                    selectedPath = ""
                }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回分类目录")
                }
                Column(Modifier.weight(1f)) {
                    Text(selectedTitle, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current.title, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FeedScreen(app, current, selectedPath, onVideo)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(FeedKind.CATEGORY, FeedKind.TAG, FeedKind.ACTOR, FeedKind.MAKER)
                .forEach { kind ->
                    FilterChip(
                        selected = chosenKind == kind,
                        onClick = { chosenKind = kind },
                        label = { Text(
                            (when (kind) {
                                FeedKind.CATEGORY -> "分类"
                                FeedKind.TAG -> "标签"
                                FeedKind.ACTOR -> "演员"
                                else -> "制作商"
                            }) + " " + directory.count { it.kind == kind }
                        ) }
                    )
                }
        }
        val shortcuts = directory.filter { it.path in pinned || it.path in visited }
            .distinctBy { it.path }.take(20)
        if (shortcuts.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SupjavSectionTitle("常用与最近", "长按或点星标可收藏常用分类")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems(shortcuts, key = { it.path }) { option ->
                        AssistChip(
                            onClick = {
                                selectedKind = option.kind
                                selectedPath = option.path
                                selectedTitle = option.label
                                app.categoryStore.markVisited(option.path)
                                visited = app.categoryStore.recent()
                            },
                            label = { Text(option.label, maxLines = 1) }
                        )
                    }
                }
            }
        }
        val results = remember(directory, chosenKind, directoryFilter) {
            app.catalog.directoryMatches(directory, chosenKind, directoryFilter)
                .sortedWith(compareBy({ it.label.lowercase() }, { it.path }))
        }
        fun initialOf(label: String): String {
            val first = label.firstOrNull()?.uppercaseChar() ?: return "#"
            return if (first in 'A'..'Z') first.toString() else "#"
        }
        val availableInitials = remember(results) {
            results.map { initialOf(it.label) }.distinct().sorted()
        }
        val visibleResults = remember(results, initialFilter) {
            if (initialFilter == "全部") results
            else results.filter { initialOf(it.label) == initialFilter }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SupjavSectionTitle(
                chosenKind.title,
                "共 ${results.size} 项 · 已同步 ${directorySources} 个目录来源"
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "刷新分类") }
        }
        if (results.size > 35) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                (listOf("全部") + availableInitials).forEach { initial ->
                    FilterChip(
                        selected = initialFilter == initial,
                        onClick = { initialFilter = initial },
                        label = { Text(initial) }
                    )
                }
            }
        }
        if (fetching) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (directoryHasMore && directoryPageDepth < 50 && !fetching) {
            TextButton(
                onClick = {
                    directoryPageDepth = (directoryPageDepth + 3).coerceAtMost(50)
                    reload++
                },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Text("继续加载更多目录（已扫描前 ${directoryPageDepth} 页）")
            }
        }
        if (fetchError != null) {
            Text(
                "网站分类更新失败：" + fetchError.orEmpty() +
                    if (directory.isNotEmpty()) "（已显示上次成功获取的分类）" else "",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!fetching && results.isEmpty()) {
            SupjavEmptyState(if (directory.isEmpty()) {
                "网站尚未返回目录。当前可能存在访问限制。"
            } else if (directoryFilter.isNotBlank()) {
                "未找到匹配“${directoryFilter}”的目录；清除顶部搜索框可显示全部。"
            } else "这一类暂无可用目录项，可以切换其他分类。")
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(142.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(visibleResults, key = { it.path }) { option ->
                OutlinedCard(
                    onClick = {
                        selectedKind = option.kind
                        selectedPath = option.path
                        selectedTitle = option.label
                        app.categoryStore.markVisited(option.path)
                        visited = app.categoryStore.recent()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Outlined.Category, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text(option.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(
                            onClick = {
                                app.categoryStore.togglePin(option.path)
                                pinned = app.categoryStore.pins().toSet()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (option.path in pinned) Icons.Filled.Star
                                    else Icons.Outlined.Star,
                                contentDescription = if (option.path in pinned) "取消常用分类" else "收藏分类",
                                tint = if (option.path in pinned)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
