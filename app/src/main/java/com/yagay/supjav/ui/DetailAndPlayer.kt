package com.yagay.supjav.ui

import android.content.Intent
import android.content.res.Configuration
import android.app.Activity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.horizontalScroll
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import kotlin.math.abs
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.yagay.supjav.player.VideoQualityPolicy
import com.yagay.supjav.player.PlaybackDefaults
import com.yagay.supjav.player.PlayerTapPolicy
import com.yagay.supjav.player.VideoParts
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
    onServersUpdated: (List<VideoServer>) -> Unit = {},
    onPlaylistCaptured: (List<VideoItem>) -> Unit = {}
) {
    var detail by remember(video.id) { mutableStateOf<VideoDetail?>(null) }
    var error by remember(video.id) { mutableStateOf<String?>(null) }
    var loading by remember(video.id) { mutableStateOf(true) }
    var retryRevision by remember(video.id) { mutableIntStateOf(0) }
    var favorite by remember(video.id) { mutableStateOf(false) }
    var resolving by remember(video.id) { mutableStateOf<String?>(null) }
    var playbackError by remember(video.id) { mutableStateOf<String?>(null) }
    var failedServer by remember(video.id) { mutableStateOf<VideoServer?>(null) }
    var lineSheet by remember(video.id) { mutableStateOf(false) }
    var request by remember(video.id) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(video.id, retryRevision) {
        loading = true
        error = null
        try {
            favorite = withContext(Dispatchers.IO) { app.library.isFavorite(video.id) }
            val response = app.catalog.detail(video)
            detail = response
            onServersUpdated(response.servers)
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
                retryRevision++
            })
        }

        val d = detail
        if (d != null) {
            val parts = VideoParts.available(d.servers)
            if (parts.size > 1) {
                SupjavSectionTitle("视频分段")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(parts) { part ->
                        val candidate = VideoParts.forPart(
                            d.servers, part,
                            PlaybackDefaults.preferredServer(
                                d.servers,
                                app.preferences.getString(PlaybackDefaults.LINE, "auto")
                            )
                        )
                        FilterChip(
                            selected = false,
                            onClick = { candidate?.let(::play) },
                            enabled = candidate != null && resolving == null,
                            label = { Text(part) }
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val orderedParts = VideoParts.available(d.servers)
                        val firstCdServers = if (orderedParts.size > 1)
                            d.servers.filter { VideoParts.partOf(it) == orderedParts.first() }
                        else d.servers
                        PlaybackDefaults.preferredServer(
                            firstCdServers,
                            app.preferences.getString(PlaybackDefaults.LINE, "auto")
                        )?.let(::play)
                    },
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
            if (d.video.publishedAt.isNotBlank() || d.video.durationLabel.isNotBlank()) {
                Text(listOf(d.video.publishedAt, d.video.durationLabel)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Always present the actor section; many source pages have no
            // linked /cast/ path even when the metadata paragraph has names.
            // The directory remains available when no cast data is supplied.
            SupjavSectionTitle("演员")
            if (d.actors.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(d.actors) { actor ->
                        val target = d.taxonomy.firstOrNull {
                            it.kind == FeedKind.ACTOR &&
                                it.label.equals(actor, ignoreCase = true)
                        }?.path
                        AssistChip(
                            onClick = { target?.let { onFilter(FeedKind.ACTOR, it) } },
                            enabled = target != null,
                            label = { Text(actor) }
                        )
                    }
                }
            } else {
                Text("当前作品暂无演员资料",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onFilter(FeedKind.ACTOR, "") }) {
                Text("浏览演员目录")
            }
            if (d.makers.isNotEmpty()) {
                SupjavSectionTitle("制作商")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(d.makers) { maker ->
                        val target = d.taxonomy.firstOrNull {
                            it.kind == FeedKind.MAKER &&
                                it.label.equals(maker, ignoreCase = true)
                        }?.path
                        AssistChip(
                            onClick = { target?.let { onFilter(FeedKind.MAKER, it) } },
                            enabled = target != null,
                            label = { Text(maker) }
                        )
                    }
                }
            }
            if (d.tags.isNotEmpty()) {
                SupjavSectionTitle("标签")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(d.tags) { tag ->
                        val target = d.taxonomy.firstOrNull {
                            it.kind == FeedKind.TAG &&
                                it.label.equals(tag, ignoreCase = true)
                        }?.path
                        AssistChip(
                            onClick = { target?.let { onFilter(FeedKind.TAG, it) } },
                            enabled = target != null,
                            label = { Text(tag) }
                        )
                    }
                }
            }
            val groupLinks = d.taxonomy.filter {
                it.kind == FeedKind.CATEGORY
            }.distinctBy { it.path }
            if (groupLinks.isNotEmpty()) {
                SupjavSectionTitle("分类")
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
                        VideoTile(item, {
                            onPlaylistCaptured(d.related)
                            onVideo(item)
                        }, Modifier.width(144.dp))
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
                    Text(
                        (VideoParts.partOf(server)?.let { it + " · " } ?: "") + server.name
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    app: SupjavApp,
    pip: Boolean,
    enterPip: () -> Unit,
    onBack: () -> Unit,
    onWebFallback: (() -> Unit)? = null,
    onShowLinePicker: () -> Unit = {},
    onShowPartPicker: () -> Unit = {},
    onShowPlaylist: () -> Unit = {},
    onSelectPart: (String) -> Unit = {},
    availableParts: List<String> = emptyList(),
    activePart: String? = null,
    partSwitching: Boolean = false
) {
    val engine = app.playerEngine
    val player = engine.player
    val video = engine.currentVideo
    val context = LocalContext.current
    val systemControls = remember(context) {
        (context as? Activity)?.let(::PlayerSystemControls)
    }
    DisposableEffect(systemControls) {
        onDispose { systemControls?.restoreBrightness() }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val prefs = app.preferences
    var currentError by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var playWhenReady by remember { mutableStateOf(player.playWhenReady) }
    var isBuffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var position by remember { mutableLongStateOf(player.currentPosition.coerceAtLeast(0)) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var expandedSettings by remember { mutableStateOf(false) }
    var qualityPickerOpen by remember { mutableStateOf(false) }
    var qualityHeights by remember { mutableStateOf(engine.availableVideoHeights()) }
    var fill by remember(video?.id) {
        mutableStateOf(prefs.getBoolean(PlaybackDefaults.FIT, false))
    }
    val skipSeconds = PlaybackDefaults.skip(
        prefs.getInt(PlaybackDefaults.SEEK, 10)
    )
    val holdSpeed = PlaybackDefaults.holdSpeed(
        prefs.getFloat(PlaybackDefaults.HOLD, 2f)
    )
    val skipMs = skipSeconds * 1000L
    var draggingSeek by remember { mutableStateOf(false) }
    var gestureScrubbing by remember { mutableStateOf(false) }
    var gesturePositionMs by remember { mutableLongStateOf(0L) }
    var gestureLevelKind by remember { mutableStateOf<String?>(null) }
    var gestureLevel by remember { mutableFloatStateOf(0f) }
    var pressBoostOriginal by remember { mutableStateOf<Float?>(null) }
    var pressBoostSide by remember { mutableIntStateOf(0) }
    var pressBoostWasPaused by remember { mutableStateOf(false) }
    var gestureFlash by remember { mutableStateOf<String?>(null) }
    var gestureFlashSide by remember { mutableIntStateOf(0) }
    var gestureFlashVersion by remember { mutableIntStateOf(0) }
    fun flash(kind: String, side: Int = 0) {
        gestureFlash = kind
        gestureFlashSide = side
        gestureFlashVersion++
    }
    LaunchedEffect(gestureFlashVersion) {
        if (gestureFlash != null) {
            delay(550)
            gestureFlash = null
        }
    }
    var controlsVisible by remember { mutableStateOf(!player.playWhenReady) }
    LaunchedEffect(controlsVisible, isPlaying, pip, gestureScrubbing, draggingSeek, pressBoostOriginal) {
        if (controlsVisible && isPlaying && !pip && currentError == null &&
            !gestureScrubbing && !draggingSeek && pressBoostOriginal == null) {
            delay(3_500)
            controlsVisible = false
        }
    }
    // System Back is handled by Root for both orientations. The explicit
    // fullscreen control remains the only orientation-only action.

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                if (value) currentError = null
            }
            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) {
                playWhenReady = value
                if (!value) controlsVisible = true
            }
            override fun onTracksChanged(tracks: Tracks) {
                qualityHeights = engine.availableVideoHeights()
            }
            override fun onPlaybackParametersChanged(
                params: androidx.media3.common.PlaybackParameters
            ) {
                speed = params.speed
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
        onDispose {
            pressBoostOriginal?.let { player.setPlaybackSpeed(it) }
            if (pressBoostWasPaused) player.pause()
            pressBoostOriginal = null
            pressBoostWasPaused = false
            pressBoostSide = 0
            player.removeListener(listener)
        }
    }
    LaunchedEffect(player, pip) {
        while (true) {
            if (!draggingSeek && !gestureScrubbing)
                position = player.currentPosition.coerceAtLeast(0)
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
                // Top/bottom unused video regions act as a wide seeking
                // touch target. Seeking is previewed while dragging, committed
                // once on release to avoid repeated segment requests.
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(player, duration, systemControls) {
                            // Axis-lock: horizontal seeking anywhere when controls
                            // are hidden; vertical left/right brightness/volume.
                            var startX = 0f
                            var startY = 0f
                            var startingPosition = 0L
                            var startLevel = 0f
                            var direction: String? = null
                            var allowedSeek = false
                            detectDragGestures(
                                onDragStart = { down ->
                                    startX = down.x
                                    startY = down.y
                                    startingPosition = player.currentPosition.coerceAtLeast(0L)
                                    direction = null
                                    startLevel = 0f
                                    allowedSeek = duration > 0L && (
                                        !controlsVisible ||
                                        down.y < size.height * .30f ||
                                        down.y > size.height * .70f
                                    )
                                },
                                onDrag = { change, _ ->
                                    val dx = change.position.x - startX
                                    val dy = change.position.y - startY
                                    if (direction == null) {
                                        if (abs(dx) > abs(dy) * 1.25f && allowedSeek) {
                                            direction = "seek"
                                            gestureScrubbing = true
                                            gesturePositionMs = startingPosition
                                            gestureFlash = null
                                            controlsVisible = false
                                        } else if (abs(dy) > abs(dx) * 1.25f &&
                                            systemControls != null) {
                                            direction = if (startX < size.width / 2f)
                                                "brightness" else "volume"
                                            startLevel = if (direction == "brightness")
                                                systemControls.brightness()
                                                else systemControls.volume()
                                            gestureLevelKind = direction
                                            gestureLevel = startLevel
                                            gestureFlash = null
                                            controlsVisible = false
                                        }
                                    }
                                    when (direction) {
                                        "seek" -> {
                                            gesturePositionMs = (startingPosition +
                                                (dx / size.width.coerceAtLeast(1) *
                                                    duration).toLong())
                                                .coerceIn(0L, duration)
                                            change.consume()
                                        }
                                        "brightness" -> {
                                            gestureLevel = systemControls?.setBrightness(
                                                startLevel - dy /
                                                    size.height.coerceAtLeast(1) * 1.25f
                                            ) ?: startLevel
                                            change.consume()
                                        }
                                        "volume" -> {
                                            gestureLevel = systemControls?.setVolume(
                                                startLevel - dy /
                                                    size.height.coerceAtLeast(1) * 1.25f
                                            ) ?: startLevel
                                            change.consume()
                                        }
                                    }
                                },
                                onDragEnd = {
                                    if (direction == "seek" && gestureScrubbing) {
                                        player.seekTo(gesturePositionMs)
                                        engine.savePosition()
                                    }
                                    gestureScrubbing = false
                                    gestureLevelKind = null
                                    direction = null
                                },
                                onDragCancel = {
                                    gestureScrubbing = false
                                    gestureLevelKind = null
                                    direction = null
                                }
                            )
                        }
                        .pointerInput(player, duration, skipSeconds, holdSpeed) {
                            detectTapGestures(
                                onTap = {
                                    controlsVisible = when (
                                        PlayerTapPolicy.onSurfaceTap(controlsVisible)
                                    ) {
                                        PlayerTapPolicy.Action.SHOW -> true
                                        PlayerTapPolicy.Action.HIDE -> false
                                    }
                                },
                                onDoubleTap = { offset ->
                                    val deltaMs = when {
                                        offset.x < size.width * .38f -> -skipMs
                                        offset.x > size.width * .62f -> skipMs
                                        else -> 0L
                                    }
                                    if (deltaMs != 0L) {
                                        val target = (player.currentPosition + deltaMs)
                                            .coerceAtLeast(0L)
                                            .coerceAtMost(if (duration > 0L) duration
                                                else Long.MAX_VALUE)
                                        player.seekTo(target)
                                        flash("skip", if (deltaMs > 0) 1 else -1)
                                    }
                                },
                                onLongPress = { offset ->
                                    val onSide = offset.x < size.width * .38f ||
                                        offset.x > size.width * .62f
                                    if (onSide && pressBoostOriginal == null) {
                                        pressBoostOriginal = player.playbackParameters.speed
                                        pressBoostWasPaused = !player.playWhenReady
                                        pressBoostSide = if (offset.x < size.width / 2f) -1 else 1
                                        player.setPlaybackSpeed(holdSpeed)
                                        if (!player.playWhenReady) player.play()
                                    }
                                },
                                onPress = {
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        // Temporary 2x must never survive
                                        // finger-up, cancellation or navigation.
                                        pressBoostOriginal?.let { previous ->
                                            player.setPlaybackSpeed(previous)
                                        }
                                        if (pressBoostWasPaused) player.pause()
                                        pressBoostOriginal = null
                                        pressBoostWasPaused = false
                                        pressBoostSide = 0
                                    }
                                }
                            )
                        }
                )
            }
            if (!pip && isBuffering && !gestureScrubbing && gestureLevelKind == null) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(26.dp),
                    strokeWidth = 2.dp, color = Color.White
                )
            }
        }
        // Video stays edge-to-edge, but buttons/seek bar must stay outside
        // status bars, gesture/three-button navigation and landscape cutouts.
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
        if (!pip && gestureScrubbing) {
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth().padding(horizontal = 18.dp, vertical = 34.dp)
                    .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(gesturePositionMs), color = Color.White,
                        style = MaterialTheme.typography.labelMedium)
                    Text(formatTime(duration), color = Color.White,
                        style = MaterialTheme.typography.labelMedium)
                }
                LinearProgressIndicator(
                    progress = {
                        if (duration > 0L)
                            (gesturePositionMs.toFloat() / duration).coerceIn(0f, 1f)
                        else 0f
                    },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = .35f)
                )
            }
        }
        if (!pip && gestureLevelKind != null) {
            Column(
                Modifier.align(if (gestureLevelKind == "brightness")
                    Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 18.dp)
                    .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (gestureLevelKind == "brightness") Icons.Outlined.Brightness6
                    else Icons.Outlined.VolumeUp,
                    gestureLevelKind, tint = Color.White
                )
                Text("${(gestureLevel * 100).toInt()}%", color = Color.White)
                LinearProgressIndicator(
                    progress = { gestureLevel.coerceIn(0f, 1f) },
                    modifier = Modifier.width(72.dp).height(3.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = .35f)
                )
            }
        }
        if (!pip && gestureFlash != null) {
            val direction = gestureFlashSide
            Box(
                Modifier.align(when {
                    direction < 0 -> Alignment.CenterStart
                    direction > 0 -> Alignment.CenterEnd
                    else -> Alignment.Center
                }).padding(horizontal = 24.dp)
                    .background(Color.Black.copy(alpha = .24f), CircleShape)
                    .padding(horizontal = 11.dp, vertical = 8.dp)
            ) {
                when (gestureFlash) {
                    "play" -> Icon(Icons.Outlined.PlayArrow, "播放", tint = Color.White.copy(alpha=.9f),
                        modifier = Modifier.size(27.dp))
                    "pause" -> Icon(Icons.Outlined.Pause, "暂停", tint = Color.White.copy(alpha=.9f),
                        modifier = Modifier.size(27.dp))
                    "skip" -> Text(
                        if (direction < 0) "−" + skipSeconds + "s"
                            else "+" + skipSeconds + "s",
                        color = Color.White.copy(alpha = .9f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        // A paused video keeps its pause mark in the center even after the
        // transient gesture feedback has faded. It never captures touches.
        if (!pip && !playWhenReady && currentError == null &&
            !gestureScrubbing && gestureLevelKind == null &&
            pressBoostOriginal == null) {
            Box(
                Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = .30f), CircleShape)
                    .padding(12.dp)
            ) {
                Icon(
                    Icons.Outlined.Pause, "已暂停，请点击下方播放按钮继续播放",
                    tint = Color.White, modifier = Modifier.size(32.dp)
                )
            }
        }
        if (!pip && pressBoostOriginal != null) {
            Text(
                PlaybackDefaults.speedLabel(holdSpeed),
                modifier = Modifier.align(
                    if (pressBoostSide < 0) Alignment.CenterStart else Alignment.CenterEnd
                ).padding(horizontal = 22.dp)
                    .background(Color.Black.copy(alpha = .20f), CircleShape)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                color = Color.White.copy(alpha = .88f),
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (!pip && !gestureScrubbing && gestureLevelKind == null &&
            (controlsVisible || currentError != null)) {
            IconButton(
                onClick = {
                    Diagnostics.log("NAV_BACK_PLAYER_BUTTON")
                    engine.savePosition()
                    onBack()
                },
                modifier = Modifier.align(Alignment.TopStart)
            ) { Icon(Icons.Outlined.ArrowBack, "返回详情", tint = Color.White) }
            // Less frequently used actions belong above the video, away from
            // the timeline and transport controls. Keep the trailing settings
            // action anchored when optional quality/PiP actions are unavailable.
            Row(
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(end = 12.dp, top = 4.dp)
                    .background(Color.Black.copy(alpha = .32f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (availableParts.size > 1) {
                    TextButton(
                        onClick = onShowPartPicker,
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(activePart ?: availableParts.first(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                IconButton(
                    onClick = onShowLinePicker,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Outlined.Tune, "切换播放线路", tint = Color.White)
                }
                if (qualityHeights.size > 1) {
                    IconButton(
                        onClick = { qualityPickerOpen = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Text("HD", color = Color.White,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (prefs.getBoolean("manual_pip", true)) {
                    IconButton(
                        onClick = enterPip, modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Outlined.PictureInPictureAlt, "画中画",
                            tint = Color.White)
                    }
                }
                IconButton(
                    onClick = { expandedSettings = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Outlined.MoreVert, "播放设置", tint = Color.White)
                }
            }
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
                // A CD is a separate video segment, NOT a playback mirror.
                // Place its selection directly on the player rather than
                // hiding it among the EVS/ST/VOE/LUC/VAS line buttons.
                if (availableParts.size > 1) {
                    LazyRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            Text("分段", color = Color.White.copy(alpha = .75f),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        items(availableParts) { part ->
                            val selected = part == activePart
                            // Material TextButton enforces a 64dp minimum width;
                            // the compact CD labels need only their actual text
                            // width and a small, consistent inset.
                            val shape = RoundedCornerShape(7.dp)
                            Box(
                                modifier = Modifier.height(26.dp)
                                    .clip(shape)
                                    .background(
                                        if (selected) Color.White.copy(alpha = .32f)
                                        else Color.White.copy(alpha = .13f)
                                    )
                                    .clickable(
                                        enabled = !partSwitching && !selected
                                    ) { onSelectPart(part) }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(part, color = Color.White,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (partSwitching) {
                            item {
                                CircularProgressIndicator(
                                    Modifier.size(14.dp), strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(position), style = MaterialTheme.typography.labelSmall,
                        color = Color.White)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(duration), style = MaterialTheme.typography.labelSmall,
                        color = Color.White)
                }
                Slider(
                    value = if (duration > 0L)
                        ((if (gestureScrubbing) gesturePositionMs else position).toFloat() /
                            duration).coerceIn(0f, 1f) else 0f,
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
                    modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 6.dp),
                    enabled = duration > 0L,
                    thumb = {
                        Box(Modifier.size(10.dp).background(Color.White, CircleShape))
                    },
                    track = { slider ->
                        SliderDefaults.Track(
                            sliderState = slider,
                            modifier = Modifier.height(3.dp),
                            colors = SliderDefaults.colors(
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = .38f)
                            )
                        )
                    }
                )
                // Keep transport centered whenever space permits. The
                // playlist button sits immediately before fullscreen in the
                // same bottom row instead of cluttering the top-right actions.
                BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
                    val compact = maxWidth < 320.dp
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (compact) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                modifier = Modifier.size(if (compact) 38.dp else 44.dp),
                                onClick = {
                                    player.seekTo((player.currentPosition - skipMs)
                                        .coerceAtLeast(0L))
                                }
                            ) {
                                if (skipSeconds == 10) Icon(Icons.Outlined.Replay10,
                                    "后退10秒", tint = Color.White)
                                else Text("−" + skipSeconds + "s", color = Color.White)
                            }
                            FilledIconButton(
                                modifier = Modifier.size(if (compact) 44.dp else 48.dp),
                                onClick = {
                                    if (player.playWhenReady) player.pause() else player.play()
                                    controlsVisible = true
                                }
                            ) {
                                Icon(
                                    if (playWhenReady) Icons.Outlined.Pause
                                    else Icons.Outlined.PlayArrow,
                                    "播放或暂停"
                                )
                            }
                            IconButton(
                                modifier = Modifier.size(if (compact) 38.dp else 44.dp),
                                onClick = {
                                    player.seekTo((player.currentPosition + skipMs)
                                        .coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE))
                                }
                            ) {
                                if (skipSeconds == 10) Icon(Icons.Outlined.Forward10,
                                    "快进10秒", tint = Color.White)
                                else Text("+" + skipSeconds + "s", color = Color.White)
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onShowPlaylist,
                                modifier = Modifier.size(if (compact) 36.dp else 40.dp)
                            ) {
                                Icon(Icons.Outlined.PlaylistPlay, "播放列表", tint = Color.White)
                            }
                            IconButton(
                                onClick = {
                                    (context as? Activity)?.requestedOrientation =
                                        if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                },
                                modifier = Modifier.size(if (compact) 36.dp else 40.dp)
                            ) {
                                Icon(
                                    if (landscape) Icons.Outlined.FullscreenExit
                                    else Icons.Outlined.Fullscreen,
                                    "切换横竖屏", tint = Color.White
                                )
                            }
                        }
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
    }
    if (qualityPickerOpen && !pip) {
        SupjavModalSheet(title = "视频清晰度", onDismiss = { qualityPickerOpen = false }) {
            Text("仅显示当前播放源实际支持的分辨率，切换不重建播放器。",
                style = MaterialTheme.typography.bodySmall)
            VideoQualityPolicy.choices(qualityHeights).forEach { mode ->
                val selected = app.videoQuality == mode
                TextButton(
                    onClick = {
                        app.updateVideoQuality(mode)
                        qualityPickerOpen = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text((if (selected) "✓  " else "") +
                        VideoQualityPolicy.label(mode))
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
                    // This player-only change does not alter the saved default.
                }, label = { Text("适应画面") })
                FilterChip(selected = fill, onClick = {
                    fill = true
                    // This player-only change does not alter the saved default.
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
