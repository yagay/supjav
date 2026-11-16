package com.yagay.supjav.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(app: SupjavApp, onVideo: (VideoItem) -> Unit) {
    var favorites by remember { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf(emptyList<VideoItem>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(favorites, revision) {
        list = withContext(Dispatchers.IO) { app.library.list(favorites) }
    }
    Column {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = favorites, onClick = { favorites = true },
                label = { Text("收藏") })
            FilterChip(selected = !favorites, onClick = { favorites = false },
                label = { Text("播放历史") })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { revision++ }) { Text("刷新") }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (favorites) "暂无收藏" else "暂无播放记录")
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.id }) { video ->
                    Column {
                        VideoTile(video, { onVideo(video) })
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                if (favorites) app.library.toggleFavorite(video)
                                else app.library.deleteHistory(video.id)
                                withContext(Dispatchers.Main) { revision++ }
                            }
                        }) { Text("移除") }
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
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("播放器设置", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("允许后台播放", Modifier.weight(1f))
            Switch(checked = background, onCheckedChange = {
                background = it
                prefs.edit().putBoolean("background", it).apply()
            })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("深色模式", Modifier.weight(1f))
            Switch(checked = dark, onCheckedChange = onDark)
        }
        HorizontalDivider()
        Text("数据与诊断", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = {
            try { Diagnostics.share(context) }
            catch (e: Exception) { Diagnostics.log("EXPORT_FAILED " + e.message.orEmpty()) }
        }, modifier = Modifier.fillMaxWidth()) { Text("导出诊断 ZIP") }
        OutlinedButton(onClick = { confirmClear = true },
            modifier = Modifier.fillMaxWidth()) { Text("清空播放历史") }
        HorizontalDivider()
        Text("关于", style = MaterialTheme.typography.titleMedium)
        Text("Supjav Native 0.1.0 · Kotlin / Compose / Media3",
            style = MaterialTheme.typography.bodyMedium)
        Text("原生解析只支持可正常访问的公开播放源。遇到网站验证、DRM 或未支持的 JavaScript 播放器时会显示失败原因。",
            style = MaterialTheme.typography.bodySmall)
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("清空历史？") },
        text = { Text("此操作不会删除收藏。") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                scope.launch(Dispatchers.IO) { app.library.clearHistory() }
            }) { Text("清空") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
