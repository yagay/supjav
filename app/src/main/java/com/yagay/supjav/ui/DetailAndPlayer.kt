package com.yagay.supjav.ui

import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DetailScreen(
    app: SupjavApp,
    video: VideoItem,
    onVideo: (VideoItem) -> Unit,
    onPlay: () -> Unit,
    onFilter: (FeedKind, String) -> Unit
) {
    var detail by remember(video.id) { mutableStateOf<VideoDetail?>(null) }
    var error by remember(video.id) { mutableStateOf<String?>(null) }
    var loading by remember(video.id) { mutableStateOf(true) }
    var favorite by remember(video.id) { mutableStateOf(app.library.isFavorite(video.id)) }
    var resolving by remember(video.id) { mutableStateOf<String?>(null) }
    var playbackError by remember(video.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(video.id) {
        loading = true
        try { detail = app.source.detail(video) }
        catch (e: Exception) { error = e.message ?: "详情读取失败" }
        finally { loading = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AsyncImage(model = detail?.video?.coverUrl ?: video.coverUrl,
                contentDescription = video.title,
                modifier = Modifier.width(128.dp).height(180.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(detail?.video?.title ?: video.title, style = MaterialTheme.typography.titleLarge)
                if (video.code.isNotEmpty()) Text(video.code, style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val result = app.library.toggleFavorite(detail?.video ?: video)
                        withContext(Dispatchers.Main) { favorite = result }
                    }
                }) {
                    Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (favorite) "已收藏" else "收藏")
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        val d = detail
        if (d != null) {
            if (d.tags.isNotEmpty()) {
                Text("标签", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(d.tags) { tag ->
                        AssistChip(onClick = { onFilter(FeedKind.TAG, tag) }, label = { Text(tag) })
                    }
                }
            }
            if (d.actors.isNotEmpty()) {
                Text("演员", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(d.actors) { actor ->
                        AssistChip(onClick = { onFilter(FeedKind.ACTOR, actor) }, label = { Text(actor) })
                    }
                }
            }
            Text("播放线路", style = MaterialTheme.typography.titleMedium)
            if (d.servers.isEmpty()) {
                Text("页面中未找到可用线路。该作品可能需要网页脚本加载。",
                    style = MaterialTheme.typography.bodyMedium)
            }
            d.servers.forEach { server ->
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = resolving == null,
                    onClick = {
                        resolving = server.id
                        playbackError = null
                        scope.launch {
                            when (val result = app.resolver.resolve(server)) {
                                is ResolveResult.Playable -> {
                                    app.playerEngine.play(d.video, result.source)
                                    onPlay()
                                }
                                is ResolveResult.Unavailable -> playbackError = result.reason
                                ResolveResult.VerificationRequired ->
                                    playbackError = "线路要求人工验证，暂不支持原生播放"
                            }
                            resolving = null
                        }
                    }
                ) {
                    if (resolving == server.id) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(server.name)
                }
            }
            if (playbackError != null) Text(playbackError.orEmpty(),
                color = MaterialTheme.colorScheme.error)
            if (d.description.isNotBlank()) {
                Text("详情", style = MaterialTheme.typography.titleMedium)
                Text(d.description, style = MaterialTheme.typography.bodyMedium)
            }
            if (d.related.isNotEmpty()) {
                Text("相关推荐", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(d.related, key = { it.id }) { item ->
                        VideoTile(item, { onVideo(item) }, Modifier.width(146.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(
    app: SupjavApp,
    pip: Boolean,
    enterPip: () -> Unit,
    onBack: () -> Unit
) {
    val video = app.playerEngine.currentVideo
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        AndroidView(
            modifier = if (pip) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT)
                    player = app.playerEngine.player
                    useController = true
                    keepScreenOn = true
                }
            },
            update = { it.player = app.playerEngine.player }
        )
        if (!pip) {
            Text(video?.title ?: "正在播放", Modifier.padding(14.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    app.playerEngine.player.seekTo(
                        (app.playerEngine.player.currentPosition - 10_000).coerceAtLeast(0))
                }) { Icon(Icons.Filled.Replay10, "后退10秒") }
                OutlinedButton(onClick = {
                    app.playerEngine.player.seekTo(app.playerEngine.player.currentPosition + 10_000)
                }) { Icon(Icons.Filled.Forward10, "快进10秒") }
                OutlinedButton(onClick = enterPip) {
                    Icon(Icons.Filled.PictureInPictureAlt, null)
                    Text("小窗")
                }
            }
            TextButton(onClick = { app.playerEngine.savePosition(); onBack() }) {
                Text("返回详情 / 切换线路")
            }
            Text("切换应用时可继续后台播放，媒体通知由 Android MediaSession 管理。",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
