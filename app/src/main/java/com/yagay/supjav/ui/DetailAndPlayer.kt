package com.yagay.supjav.ui

import android.content.Intent
import android.content.res.Configuration
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.horizontalScroll
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
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
import com.yagay.supjav.util.Diagnostics
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
    onPlay: (VideoServer) -> Unit,
    onFilter: (FeedKind, String) -> Unit,
    onWebPlay: (VideoServer) -> Unit,
    onServersUpdated: (List<VideoServer>) -> Unit = {}
) {
    var detail by remember(video.id) { mutableStateOf<VideoDetail?>(null) }
    var error by remember(video.id) { mutableStateOf<String?>(null) }
    var loading by remember(video.id) { mutableStateOf(true) }
    var favorite by remember(video.id) { mutableStateOf(false) }
    var resolving by remember(video.id) { mutableStateOf<String?>(null) }
    var playbackError by remember(video.id) { mutableStateOf<String?>(null) }
    var failedServer by remember(video.id) { mutableStateOf<VideoServer?>(null) }
    var lineSheet by remember(video.id) { mutableStateOf(false) }
    var request by remember(video.id) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(video.id) {
        loading = true
        error = null
        try {
            favorite = withContext(Dispatchers.IO) { app.library.isFavorite(video.id) }
            val response = app.source.detail(video)
            detail = response
            onServersUpdated(response.servers)
            withContext(Dispatchers.IO) {
                app.categoryStore.save(response.taxonomy.map {
                    com.yagay.supjav.data.SupjavSource.BrowseOption(
                        it.kind, it.path.substringAfterLast('/'), it.label, it.path
                    )
                })
            }
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
        failedServer = null
        playbackError = null
        resolving = server.id
        Diagnostics.log("PLAY_SELECTED video=" + video.id + " line=" + server.name +
            " entryLength=" + server.link.length)
        request = scope.launch {
            try {
                when (val result = app.resolver.resolve(server)) {
                    is ResolveResult.Playable -> {
                        app.playerEngine.play(detail?.video ?: video, result.source)
                        onPlay(server)
                    }
                    is ResolveResult.Unavailable -> {
                        Diagnostics.log("PLAY_SOURCE_UNAVAILABLE video=" + video.id +
                            " line=" + server.name + " reason=" + result.reason)
                        val scripted = result.reason.contains("网页脚本入口") ||
                            result.reason.contains("需要 JavaScript")
                        if (scripted && app.preferences.getBoolean("auto_web_fallback", true)) {
                            Diagnostics.log("PLAY_AUTO_WEB video=" + video.id +
                                " line=" + server.name)
                            onWebPlay(server.copy(verifiedPlayerUrl = result.verifiedPlayerUrl))
                        } else {
                            failedServer = server.copy(
                                verifiedPlayerUrl = result.verifiedPlayerUrl)
                            playbackError = result.reason
                        }
                    }
                    ResolveResult.VerificationRequired -> {
                        failedServer = server
                        playbackError = "此播放源需要真人验证，尚不支持原生播放"
                        Diagnostics.log("PLAY_VERIFICATION_REQUIRED video=" + video.id +
                            " line=" + server.name)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedServer = server
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
                Column {
                    Text(
                        playbackError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = {
                        runCatching { Diagnostics.share(context) }
                            .onFailure { playbackError = "日志导出失败：" +
                                it.javaClass.simpleName }
                    }) { Text("导出这次失败的诊断 ZIP") }
                    failedServer?.let { failed ->
                        OutlinedButton(onClick = {
                            Diagnostics.log("PLAY_WEB_COMPAT_SELECTED video=" + video.id +
                                " line=" + failed.name)
                            onWebPlay(failed)
                        }) {
                            Icon(Icons.Outlined.OpenInBrowser, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (playbackError.orEmpty().contains("真人验证"))
                                "打开网页完成验证" else "使用网页兼容模式播放此线路")
                        }
                    }
                }
            }
            if (d.servers.isEmpty()) {
                SupjavEmptyState("未找到原生可解析的线路，可能需要网页脚本或人工验证")
                TextButton(onClick = {
                    Diagnostics.log("DETAIL_NO_SERVER_EXPORT video=" + video.id)
                    runCatching { Diagnostics.share(context) }
                }) { Text("导出线路诊断 ZIP") }
            }
            if (d.actors.isNotEmpty()) {
                SupjavSectionTitle("演员")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(d.actors) { actor ->
                        AssistChip(onClick = { onFilter(FeedKind.ACTOR, d.taxonomy.firstOrNull { it.kind == FeedKind.ACTOR && it.label == actor }?.path ?: actor) },
                            label = { Text(actor) })
                    }
                }
            }
            if (d.tags.isNotEmpty()) {
                SupjavSectionTitle("标签")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(d.tags) { tag ->
                        AssistChip(onClick = { onFilter(FeedKind.TAG, d.taxonomy.firstOrNull { it.kind == FeedKind.TAG && it.label == tag }?.path ?: tag) },
                            label = { Text(tag) })
                    }
                }
            }
            val groupLinks = d.taxonomy.filter {
                it.kind == FeedKind.CATEGORY || it.kind == FeedKind.MAKER
            }.distinctBy { it.path }
            if (groupLinks.isNotEmpty()) {
                SupjavSectionTitle("分类与制作商")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(groupLinks, key = { it.path }) { link ->
                        AssistChip(onClick = { onFilter(link.kind, link.path) },
                            label = { Text(link.label) })
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
    onBack: () -> Unit,
    onWebFallback: (() -> Unit)? = null,
    onShowLinePicker: () -> Unit = {}
) {
    val engine = app.playerEngine
    val player = engine.player
    val video = engine.currentVideo
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
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
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible, isPlaying, pip) {
        if (controlsVisible && isPlaying && !pip && currentError == null) {
            delay(3_500)
            if (!draggingSeek) controlsVisible = false
        }
    }
    BackHandler(enabled = landscape && !pip) {
        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize()) {
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
                        keepScreenOn = isPlaying
                        setKeepContentOnPlayerReset(true)
                        resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = {
                    it.player = player
                    it.useController = false
                    it.keepScreenOn = isPlaying
                    it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            )
            if (!pip) {
                // The same gesture surface is used in portrait and landscape.
                Box(Modifier.fillMaxSize().pointerInput(player, duration) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            val delta = if (offset.x < size.width / 2f) -10_000L else 10_000L
                            val target = (player.currentPosition + delta).coerceAtLeast(0L)
                                .coerceAtMost(if (duration > 0L) duration else Long.MAX_VALUE)
                            player.seekTo(target)
                            controlsVisible = true
                        }
                    )
                })
            }
            if (!pip && isBuffering) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(26.dp),
                    strokeWidth = 2.dp, color = Color.White
                )
            }
        }
        if (!pip && (controlsVisible || currentError != null)) {
            IconButton(
                onClick = { engine.savePosition(); onBack() },
                modifier = Modifier.align(Alignment.TopStart)
            ) { Icon(Icons.Outlined.ArrowBack, "返回详情", tint = Color.White) }
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(
                        Color.Transparent, Color.Black.copy(alpha = 0.88f)
                    )))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(video?.title ?: "正在播放", style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, color = Color.White, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(position), style = MaterialTheme.typography.labelSmall,
                        color = Color.White)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(duration), style = MaterialTheme.typography.labelSmall,
                        color = Color.White)
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    enabled = duration > 0L
                )
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                    }) { Icon(Icons.Outlined.Replay10, "后退10秒", tint = Color.White) }
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
                    }) { Icon(Icons.Outlined.Forward10, "快进10秒", tint = Color.White) }
                    IconButton(onClick = onShowLinePicker, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Outlined.Tune, "切换播放线路", tint = Color.White)
                    }
                    if (prefs.getBoolean("manual_pip", true)) {
                        IconButton(onClick = enterPip, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Outlined.PictureInPictureAlt, "画中画", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { expandedSettings = true }, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Outlined.MoreVert, "播放设置", tint = Color.White)
                    }
                    IconButton(onClick = {
                        (context as? Activity)?.requestedOrientation =
                            if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }) {
                        Icon(if (landscape) Icons.Outlined.FullscreenExit
                            else Icons.Outlined.Fullscreen, "切换横竖屏", tint = Color.White)
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
                            if (onWebFallback != null) {
                                TextButton(onClick = onWebFallback) {
                                    Text("返回网页兼容播放")
                                }
                            }
                            TextButton(onClick = {
                                runCatching { Diagnostics.share(context) }
                                    .onFailure { currentError = "日志导出失败：" +
                                        it.javaClass.simpleName }
                            }) { Text("导出播放器日志") }
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
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
            Text("播放界面内可直接点击线路图标切换；需要人工验证的线路不会自动跳过验证。",
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
