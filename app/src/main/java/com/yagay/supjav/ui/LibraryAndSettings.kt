package com.yagay.supjav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import com.yagay.supjav.player.VideoQualityPolicy
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
    var pauseOnBackground by remember {
        mutableStateOf(prefs.getBoolean("pause_on_background", true))
    }
    var autoPip by remember { mutableStateOf(prefs.getBoolean("auto_pip", false)) }
    var showPip by remember { mutableStateOf(prefs.getBoolean("manual_pip", true)) }
    val posterColumns = app.posterColumns
    val qualityPreference = app.videoQuality
    var autoWebFallback by remember {
        mutableStateOf(prefs.getBoolean("auto_web_fallback", true))
    }
    var confirmClear by remember { mutableStateOf(false) }
    var logError by remember { mutableStateOf<String?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    var logPreview by remember { mutableStateOf("") }
    var confirmClearLogs by remember { mutableStateOf(false) }
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
                SupjavSettingToggle(
                    "网页脚本线路自动兼容",
                    "原生解析无法处理线路参数时，自动打开网站播放器并尝试识别 MP4/HLS",
                    autoWebFallback
                ) {
                    autoWebFallback = it
                    prefs.edit().putBoolean("auto_web_fallback", it).apply()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SupjavSettingToggle("离开应用自动暂停",
                    "切换应用、回桌面或锁屏时暂停视频；返回原播放页面后手动继续",
                    pauseOnBackground) {
                    pauseOnBackground = it
                    prefs.edit().putBoolean("pause_on_background", it).apply()
                    // Automatic PiP must not undermine a background pause.
                    if (it) {
                        autoPip = false
                        prefs.edit().putBoolean("auto_pip", false).apply()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SupjavSettingToggle("离开 App 自动画中画",
                    if (pauseOnBackground) "关闭自动暂停后才可使用，手动小窗不受影响"
                    else "仅在视频正在播放时尝试进入系统画中画",
                    autoPip && !pauseOnBackground) {
                    if (!pauseOnBackground) {
                        autoPip = it
                        prefs.edit().putBoolean("auto_pip", it).apply()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SupjavSettingToggle("显示手动画中画入口",
                    "控制播放器右上角的小窗图标", showPip) {
                    showPip = it
                    prefs.edit().putBoolean("manual_pip", it).apply()
                }
            }
        }
        SupjavSectionTitle("默认视频画质")
        Surface(shape = RoundedCornerShape(SupjavMetrics.cardRadius),
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("默认优先最高可用画质。画质列表以当前播放源实际提供的清晰度为准；网络不足时建议使用自动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("highest", "auto", "p1080", "p720", "p480").forEach { mode ->
                        FilterChip(
                            selected = qualityPreference == mode,
                            onClick = { app.updateVideoQuality(mode) },
                            label = { Text(VideoQualityPolicy.label(mode)) }
                        )
                    }
                }
            }
        }
        SupjavSectionTitle("外观")
        Surface(shape = RoundedCornerShape(SupjavMetrics.cardRadius),
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SupjavSettingToggle("深色模式", "全应用统一主题", dark, onDark)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("视频封面列数", style = MaterialTheme.typography.titleSmall)
                Text("选择海报密度，所有视频列表统一生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "自动", 2 to "2列", 3 to "3列", 4 to "4列").forEach { (count, label) ->
                        FilterChip(
                            selected = posterColumns == count,
                            onClick = { app.updatePosterColumns(count) },
                            label = { Text(label) }
                        )
                    }
                }
            }
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
    if (showLogs) AlertDialog(
        onDismissRequest = { showLogs = false },
        title = { Text("最近诊断事件") },
        text = {
            Text(logPreview, Modifier.heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = { TextButton(onClick = { showLogs = false }) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = {
                showLogs = false
                runCatching { Diagnostics.share(context) }
                    .onFailure { logError = it.message ?: "导出日志失败" }
            }) { Text("导出 ZIP") }
        }
    )
    if (confirmClearLogs) AlertDialog(
        onDismissRequest = { confirmClearLogs = false },
        title = { Text("清除诊断日志？") },
        text = { Text("将删除 App 保存的诊断事件，但不会清空收藏和播放历史。") },
        confirmButton = {
            TextButton(onClick = {
                Diagnostics.clear()
                confirmClearLogs = false
            }) { Text("清除") }
        },
        dismissButton = { TextButton(onClick = { confirmClearLogs = false }) { Text("取消") } }
    )
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
