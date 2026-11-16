package com.yagay.supjav.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    var favorite by remember(video.id) { mutableStateOf(false) }
    var resolving by remember(video.id) { mutableStateOf<String?>(null) }
    var playbackError by remember(video.id) { mutableStateOf<String?>(null) }
    var lineSheet by remember(video.id) { mutableStateOf(false) }
    var request by remember(video.id) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(video.id) {
        loading = true
        error = null
        try {
            favorite = withContext(Dispatchers.IO) { app.library.isFavorite(video.id) }
            detail = app.source.detail(video)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "视频详情加载失败"
        } finally {
            loading = false
        }
    }
    DisposableEffect(video.id) {
        onDispose { request?.cancel() }
    }

    fun play(server: VideoServer) {
        if (resolving != null) return
        lineSheet = false
        playbackError = null
        resolving = server.id
        request = scope.launch {
            try {
                when (val result = app.resolver.resolve(server)) {
                    is ResolveResult.Playable -> {
                        app.playerEngine.play(detail?.video ?: video, result.source)
                        onPlay()
                    }
                    is ResolveResult.Unavailable -> playbackError = result.reason
                    ResolveResult.VerificationRequired ->
                        playbackError = "此播放源需要真人验证，尚不支持原生播放"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                playbackError = e.message ?: "播放器启动失败"
            } finally {
                resolving = null
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AsyncImage(
                model = detail?.video?.coverUrl ?: video.coverUrl,
                contentDescription = video.title,
                modifier = Modifier.width(136.dp).height(192.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(SupjavMetrics.cardRadius)),
                contentScale = ContentScale.Crop
            )
            Column(
                Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    detail?.video?.title ?: video.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 5, overflow = TextOverflow.Ellipsis
                )
                if (video.code.isNotBlank()) {
                    Text(video.code, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = {
                        scope.launch {
                            val value = withContext(Dispatchers.IO) {
                                app.library.toggleFavorite(detail?.video ?: video)
                            }
                            favorite = value
                        }
                    }) {
                        Icon(
                            if (favorite) Icons.Outlined.BookmarkAdded
                            else Icons.Outlined.BookmarkBorder, "收藏",
                            tint = if (favorite) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, (detail?.video ?: video).detailUrl)
                        }
                        context.startActivity(Intent.createChooser(share, "分享作品链接"))
                    }) { Icon(Icons.Outlined.Share, "分享") }
                }
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) {
            SupjavEmptyState(error.orEmpty(), onRetry = {
                detail = null
                loading = true
                scope.launch {
                    try {
                        detail = app.source.detail(video)
                        error = null
                    } catch (e: Exception) {
                        error = e.message ?: "加载失败"
                    } finally { loading = false }
                }
            })
        }

        val d = detail
        if (d != null) {
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { d.servers.firstOrNull()?.let(::play) },
                    modifier = Modifier.weight(1f),
                    enabled = d.servers.isNotEmpty() && resolving == null,
                    shape = RoundedCornerShape(SupjavMetrics.controlRadius)
                ) {
                    if (resolving != null) CircularProgressIndicator(
                        Modifier.size(18.dp), strokeWidth = 2.dp
                    ) else Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (resolving == null) "立即播放" else "正在解析")
                }
                OutlinedButton(
                    onClick = { lineSheet = true },
                    enabled = d.servers.isNotEmpty() && resolving == null,
                    shape = RoundedCornerShape(SupjavMetrics.controlRadius)
                ) {
                    Icon(Icons.Outlined.Tune, null)
                    Spacer(Modifier.width(4.dp))
                    Text("线路 " + d.servers.size)
                }
            }
            if (playbackError != null) {
                Text(
                    playbackError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (d.servers.isEmpty()) {
                SupjavEmptyState("未找到原生可解析的线路，可能需要网页脚本或人工验证")
            }
            if (d.actors.isNotEmpty()) {
                SupjavSectionTitle("演员")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(d.actors) { actor ->
                        AssistChip(onClick = { onFilter(FeedKind.ACTOR, actor) },
                            label = { Text(actor) })
                    }
                }
            }
            if (d.tags.isNotEmpty()) {
                SupjavSectionTitle("标签")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(d.tags) { tag ->
                        AssistChip(onClick = { onFilter(FeedKind.TAG, tag) },
                            label = { Text(tag) })
                    }
                }
            }
            if (d.description.isNotBlank()) {
                SupjavSectionTitle("作品介绍")
                Text(d.description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (d.related.isNotEmpty()) {
                SupjavSectionTitle("相关作品")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(d.related, key = { it.id }) { item ->
                        VideoTile(item, { onVideo(item) }, Modifier.width(144.dp))
                    }
                }
            }
        }
    }
    if (lineSheet && detail != null) {
        SupjavModalSheet("选择播放线路", onDismiss = { lineSheet = false }) {
            detail!!.servers.forEach { server ->
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { play(server) }
                ) {
                    Icon(Icons.Outlined.PlayCircleOutline, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(server.name)
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
    val engine = app.playerEngine
    val player = engine.player
    val video = engine.currentVideo
    val context = LocalContext.current
    val prefs = app.preferences
    var currentError by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var isBuffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var position by remember { mutableLongStateOf(player.currentPosition.coerceAtLeast(0)) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var expandedSettings by remember { mutableStateOf(false) }
    var fill by remember { mutableStateOf(prefs.getBoolean("fill_video", false)) }
    var draggingSeek by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                if (value) currentError = null
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                duration = player.duration.coerceAtLeast(0)
            }
            override fun onPlayerError(error: PlaybackException) {
                currentError = error.errorCodeName + " · " + error.message.orEmpty()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, pip) {
        while (true) {
            if (!draggingSeek) position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            delay(350)
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = if (pip) Modifier.fillMaxSize() else
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        this.player = player
                        useController = false
                        keepScreenOn = true
                        setKeepContentOnPlayerReset(true)
                        resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = {
                    it.player = player
                    it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            )
            if (!pip && isBuffering) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(26.dp),
                    strokeWidth = 2.dp, color = Color.White
                )
            }
        }
        if (!pip) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(video?.title ?: "正在播放", style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(position), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
                }
                Slider(
                    value = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f)
                        else 0f,
                    onValueChange = { fraction ->
                        if (duration > 0L) {
                            draggingSeek = true
                            position = (duration * fraction).toLong()
                        }
                    },
                    onValueChangeFinished = {
                        if (duration > 0L) player.seekTo(position)
                        draggingSeek = false
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    enabled = duration > 0L
                )
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                    }) { Icon(Icons.Outlined.Replay10, "后退10秒") }
                    FilledIconButton(onClick = {
                        if (isPlaying) player.pause() else player.play()
                    }) {
                        Icon(
                            if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            "播放或暂停"
                        )
                    }
                    IconButton(onClick = {
                        player.seekTo((player.currentPosition + 10_000L)
                            .coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE))
                    }) { Icon(Icons.Outlined.Forward10, "快进10秒") }
                    IconButton(onClick = { expandedSettings = true }) {
                        Icon(Icons.Outlined.MoreVert, "播放设置")
                    }
                }
                if (currentError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(SupjavMetrics.controlRadius)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("播放出现错误", style = MaterialTheme.typography.titleSmall)
                            Text(currentError.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                currentError = null
                                player.prepare()
                                player.play()
                            }) { Text("重新尝试") }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = {
                        engine.savePosition()
                        onBack()
                    }) { Text("详情 / 切换线路") }
                    if (prefs.getBoolean("manual_pip", true)) {
                        TextButton(onClick = enterPip) {
                            Icon(Icons.Outlined.PictureInPictureAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("小窗")
                        }
                    }
                }
            }
        }
    }
    if (expandedSettings && !pip) {
        SupjavModalSheet(title = "画面与播放设置", onDismiss = { expandedSettings = false }) {
            SupjavSectionTitle("播放速度")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                    FilterChip(
                        selected = speed == value,
                        onClick = {
                            player.setPlaybackSpeed(value)
                            speed = value
                        },
                        label = { Text(value.toString() + "×") }
                    )
                }
            }
            SupjavSectionTitle("画面比例")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !fill, onClick = {
                    fill = false
                    prefs.edit().putBoolean("fill_video", false).apply()
                }, label = { Text("适应画面") })
                FilterChip(selected = fill, onClick = {
                    fill = true
                    prefs.edit().putBoolean("fill_video", true).apply()
                }, label = { Text("铺满画面") })
            }
            Text("如需切换视频线路，请返回作品详情；不支持的第三方验证线路不会强行解析。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms < 0) return "00:00"
    val seconds = ms / 1000
    val minutes = seconds / 60
    return if (minutes >= 60) {
        (minutes / 60).toString().padStart(2, '0') + ":" +
            (minutes % 60).toString().padStart(2, '0') + ":" +
            (seconds % 60).toString().padStart(2, '0')
    } else minutes.toString().padStart(2, '0') + ":" +
        (seconds % 60).toString().padStart(2, '0')
}
