package com.yagay.supjav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    app: SupjavApp,
    onVideo: (VideoItem) -> Unit,
    initialFavorites: Boolean = true,
    showTabs: Boolean = true
) {
    var favorites by remember(initialFavorites) { mutableStateOf(initialFavorites) }
    var revision by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf(emptyList<VideoItem>()) }
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    LaunchedEffect(favorites, revision) {
        list = withContext(Dispatchers.IO) { app.library.list(favorites) }
    }
    Column {
        if (showTabs) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = favorites, onClick = { favorites = true },
                    label = { Text("收藏") })
                FilterChip(selected = !favorites, onClick = { favorites = false },
                    label = { Text("历史") })
            }
        }
        if (list.isEmpty()) {
            SupjavEmptyState(
                if (favorites) "暂无收藏" else "暂无播放历史",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.id }) { video ->
                    Column {
                        VideoTile(video, { onVideo(video) })
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        if (favorites) app.library.toggleFavorite(video)
                                        else app.library.deleteHistory(video.id)
                                    }
                                    revision++
                                }
                            }
                        ) { Text("移除") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(app: SupjavApp, dark: Boolean, onDark: (Boolean) -> Unit) {
    val prefs = app.preferences
    var background by remember { mutableStateOf(prefs.getBoolean("background", true)) }
    var autoPip by remember { mutableStateOf(prefs.getBoolean("auto_pip", false)) }
    var showPip by remember { mutableStateOf(prefs.getBoolean("manual_pip", true)) }
    var confirmClear by remember { mutableStateOf(false) }
    var logError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SupjavSectionTitle("播放与窗口")
        Surface(shape = RoundedCornerShape(SupjavMetrics.cardRadius),
            color = MaterialTheme.colorScheme.surface) {
            Column {
                SupjavSettingToggle("后台播放",
                    "切换应用或熄屏时继续播放支持的媒体", background) {
                    background = it
                    prefs.edit().putBoolean("background", it).apply()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SupjavSettingToggle("离开 App 自动画中画",
                    "仅在视频正在播放时尝试进入系统画中画", autoPip) {
                    autoPip = it
                    prefs.edit().putBoolean("auto_pip", it).apply()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SupjavSettingToggle("显示手动画中画入口",
                    "控制播放器右上角的小窗图标", showPip) {
                    showPip = it
                    prefs.edit().putBoolean("manual_pip", it).apply()
                }
            }
        }
        SupjavSectionTitle("外观")
        Surface(shape = RoundedCornerShape(SupjavMetrics.cardRadius),
            color = MaterialTheme.colorScheme.surface) {
            SupjavSettingToggle("深色模式", "全应用统一主题", dark, onDark)
        }
        SupjavSectionTitle("数据与诊断")
        Surface(shape = RoundedCornerShape(SupjavMetrics.cardRadius),
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                OutlinedButton(
                    onClick = {
                        logError = null
                        runCatching { Diagnostics.share(context) }
                            .onFailure { logError = it.message ?: "导出日志失败" }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.BugReport, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导出诊断 ZIP")
                }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("清空播放历史")
                }
                if (logError != null) Text(logError.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
        }
        Text("Supjav Native · Android / Compose / Media3",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("第三方播放需要验证或不支持原生读取时，会明确显示失败原因。不会绕过网站访问控制。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空所有播放历史？") },
        text = { Text("收藏不会被清空。") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                scope.launch(Dispatchers.IO) { app.library.clearHistory() }
            }) { Text("清空") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
    )
}
