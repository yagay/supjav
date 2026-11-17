package com.yagay.supjav

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.model.VideoServer
import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.ui.*
import com.yagay.supjav.util.Diagnostics
import com.yagay.supjav.model.ResolveResult
import com.yagay.supjav.data.SupjavLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)
    private var playerVisible by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SupjavApp
        setContent {
            var dark by remember { mutableStateOf(app.preferences.getBoolean("dark", false)) }
            SupjavTheme(dark) {
                Root(
                    app = app, inPip = inPip,
                    onPlayerVisibility = { playerVisible = it },
                    onPip = ::requestPip, dark = dark,
                    onDark = {
                        dark = it
                        app.preferences.edit().putBoolean("dark", it).apply()
                    }
                )
            }
        }
    }

    private fun requestPip() {
        val app = application as SupjavApp
        if (android.os.Build.VERSION.SDK_INT >= 26 && !isFinishing &&
            app.playerEngine.player.isPlaying) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9)).build())
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val app = application as SupjavApp
        if (playerVisible && app.preferences.getBoolean("auto_pip", false) &&
            app.playerEngine.player.isPlaying) requestPip()
    }

    override fun onPictureInPictureModeChanged(mode: Boolean, config: Configuration) {
        super.onPictureInPictureModeChanged(mode, config)
        inPip = mode
    }

    override fun onStop() {
        super.onStop()
        val app = application as SupjavApp
        if (!app.preferences.getBoolean("background", true) && !isInPictureInPictureMode) {
            app.playerEngine.player.pause()
            app.webPlayback.pauseForBackground()
        }
        app.playerEngine.savePosition()
    }
}

@Composable
private fun Root(
    app: SupjavApp,
    inPip: Boolean,
    onPlayerVisibility: (Boolean) -> Unit,
    onPip: () -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var ageConfirmed by remember {
        mutableStateOf(app.preferences.getBoolean("age_confirmed", false))
    }
    if (!ageConfirmed) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("年龄确认 / Age verification") },
            text = { Text("本应用仅供已满18岁的成年人使用，并须遵守当地法律和网站使用条款。") },
            confirmButton = { TextButton(onClick = {
                app.preferences.edit().putBoolean("age_confirmed", true).apply()
                ageConfirmed = true
            }) { Text("我已满18岁") } },
            dismissButton = { TextButton(onClick = {
                (context as? ComponentActivity)?.finish()
            }) { Text("退出") } }
        )
        return
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var tab by rememberSaveable { mutableStateOf(SupjavTab.RECOMMEND) }
    val routes = remember { mutableStateListOf<VideoItem>() }
    val activeVideo = routes.lastOrNull()
    var showPlayer by remember { mutableStateOf(false) }
    var webServer by remember { mutableStateOf<VideoServer?>(null) }
    var embeddedHlsActive by remember { mutableStateOf(false) }
    var webFallback by remember { mutableStateOf<VideoServer?>(null) }
    var availableServers by remember { mutableStateOf<List<VideoServer>>(emptyList()) }
    var selectedServer by remember { mutableStateOf<VideoServer?>(null) }
    var linePickerOpen by remember { mutableStateOf(false) }
    var switchingLine by remember { mutableStateOf<String?>(null) }
    var lineSwitchError by remember { mutableStateOf<String?>(null) }
    var autoOpenWebHls by remember { mutableStateOf(false) }
    var lineSwitchJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var libraryRevision by remember { mutableIntStateOf(0) }
    var targetCategoryKind by remember { mutableStateOf<FeedKind?>(null) }
    var targetCategoryPath by remember { mutableStateOf("") }
    var targetSearchKind by remember { mutableStateOf(FeedKind.SEARCH) }
    var targetSearchValue by remember { mutableStateOf("") }

    SideEffect { onPlayerVisibility(showPlayer) }
    val onOpen: (VideoItem) -> Unit = { video ->
        lineSwitchJob?.cancel()
        lineSwitchJob = null
        switchingLine = null
        linePickerOpen = false
        lineSwitchError = null
        availableServers = emptyList()
        selectedServer = null
        autoOpenWebHls = false
        app.webPlayback.close()
        routes.add(video)
        embeddedHlsActive = false
        webServer = null
        webFallback = null
        showPlayer = false
        showSettings = false
    }
    val back: () -> Unit = {
        when {
            linePickerOpen && switchingLine == null -> linePickerOpen = false
            showSettings -> showSettings = false
            webServer != null -> {
                app.webPlayback.close()
                embeddedHlsActive = false
                webServer = null
            }
            showPlayer -> {
                app.playerEngine.savePosition()
                (context as? ComponentActivity)?.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                showPlayer = false
            }
            routes.isNotEmpty() -> { routes.removeAt(routes.lastIndex); libraryRevision++ }
            tab != SupjavTab.RECOMMEND -> tab = SupjavTab.RECOMMEND
        }
    }
    BackHandler(enabled = linePickerOpen || showSettings || webServer != null || showPlayer ||
        routes.isNotEmpty() || tab != SupjavTab.RECOMMEND) { back() }

    if (inPip) {
        PlayerScreen(app, true, onPip, { showPlayer = false })
        return
    }

    val holder = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (tab != SupjavTab.SEARCH) {
                    SupjavHeader(tab.title, actions = {
                        if (tab == SupjavTab.RECOMMEND || tab == SupjavTab.NEW) {
                            IconButton(onClick = { refreshKey++ }) {
                                Icon(Icons.Outlined.Refresh, "刷新列表")
                            }
                        }
                        if (tab == SupjavTab.RECOMMEND) {
                            IconButton(onClick = { tab = SupjavTab.SEARCH }) {
                                Icon(Icons.Outlined.Search, "搜索")
                            }
                        }
                    })
                }
            },
            bottomBar = {
                SupjavPrimaryNavigation(tab) { selected -> tab = selected }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                holder.SaveableStateProvider(tab.name) {
                    when (tab) {
                        SupjavTab.RECOMMEND -> RecommendPage(
                            app, onOpen,
                            onSearch = { tab = SupjavTab.CATEGORY },
                            refreshKey = refreshKey
                        )
                        SupjavTab.CATEGORY -> CategoryPage(
                            app = app,
                            onVideo = onOpen,
                            initialKind = targetCategoryKind,
                            initialPath = targetCategoryPath
                        )
                        SupjavTab.RANK -> RankPage(app, onOpen)
                        SupjavTab.NEW -> NewReleasesPage(app, onOpen, refreshKey)
                        SupjavTab.MINE -> MinePage(
                            app, onOpen,
                            onSettings = { showSettings = true },
                            revision = libraryRevision
                        )
                        SupjavTab.SEARCH -> SearchPage(
                            app, onOpen, targetSearchKind, targetSearchValue
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = routes.isNotEmpty(),
            modifier = Modifier.fillMaxSize().zIndex(1f),
            enter = slideInHorizontally(tween(270, easing = FastOutSlowInEasing)) { it } +
                fadeIn(tween(100)),
            exit = slideOutHorizontally(tween(250, easing = FastOutSlowInEasing)) { it } +
                fadeOut(tween(100))
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    SupjavHeader("作品详情", onBack = back)
                    activeVideo?.let { video ->
                        key(video.id) {
                            DetailScreen(
                                app = app, video = video, onVideo = onOpen,
                                onPlay = { server ->
                                    selectedServer = server
                                    showPlayer = true
                                },
                                onServersUpdated = { servers -> availableServers = servers },
                                onWebPlay = { server ->
                                    selectedServer = server
                                    autoOpenWebHls = false
                                    // Keep the actual renderer authoritative.
                                    // Media3's old item must not remain in
                                    // system media controls during Web HLS.
                                    app.playerEngine.stop()
                                    app.webPlayback.begin(video)
                                    webFallback = server
                                    webServer = server
                                },
                                onFilter = { kind, value ->
                                    routes.clear()
                                    targetCategoryKind = kind
                                    targetCategoryPath = value
                                    tab = SupjavTab.CATEGORY
                                }
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showPlayer,
            modifier = Modifier.fillMaxSize().zIndex(2f),
            enter = slideInHorizontally(tween(250)) { it },
            exit = slideOutHorizontally(tween(250)) { it }
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                PlayerScreen(
                        app, false, onPip,
                        onBack = { back() },
                        onShowLinePicker = {
                            lineSwitchError = null
                            linePickerOpen = true
                        },
                        onWebFallback = webFallback?.let { candidate ->
                            {
                                app.playerEngine.stop()
                                activeVideo?.let(app.webPlayback::begin)
                                showPlayer = false
                                webServer = candidate
                                Diagnostics.log("NATIVE_FAILED_RETURN_TO_WEB line=" + candidate.name)
                            }
                        }
                    )
            }
        }


        AnimatedVisibility(
            visible = webServer != null,
            modifier = Modifier.fillMaxSize().zIndex(2.5f),
            enter = slideInHorizontally(tween(270)) { it },
            exit = slideOutHorizontally(tween(250)) { it }
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    if (!embeddedHlsActive) SupjavHeader(
                        "网页兼容播放器", onBack = back,
                        actions = {
                            IconButton(onClick = {
                                lineSwitchError = null
                                linePickerOpen = true
                            }) {
                                Icon(Icons.Outlined.Tune, "切换播放线路")
                            }
                        }
                    )
                    webServer?.let { server ->
                        key(server.id, server.link, server.verifiedPlayerUrl) {
                            ScriptPlayerScreen(
                                server = server,
                                onNativePlay = { source ->
                                    val video = activeVideo
                                    if (video != null) {
                                        Diagnostics.log("NATIVE_HANDOFF video=" + video.id +
                                            " line=" + server.name + " type=" + source.mimeType)
                                        webFallback = server
                                        app.webPlayback.close()
                                        embeddedHlsActive = false
                                        webServer = null
                                        app.playerEngine.play(video, source, resume = false)
                                        showPlayer = true
                                    }
                                },
                                onEmbeddedHlsChanged = { embeddedHlsActive = it },
                                onShowLinePicker = {
                                    lineSwitchError = null
                                    linePickerOpen = true
                                },
                                autoEnterHls = autoOpenWebHls,
                                webPlayback = app.webPlayback
                            )
                        }
                    }
                }
            }
        }

        // One line picker for Media3, the website player and HLS.js.
        // Keep the current playback running until the replacement is ready.
        if (linePickerOpen && activeVideo != null) {
            SupjavModalSheet(
                "切换播放线路",
                onDismiss = { if (switchingLine == null) linePickerOpen = false }
            ) {
                if (availableServers.isEmpty()) {
                    Text("当前作品还没有加载到播放线路，请返回详情刷新。")
                } else {
                    availableServers.forEach { candidate ->
                        val active = selectedServer?.id == candidate.id &&
                            selectedServer?.link == candidate.link
                        FilledTonalButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = switchingLine == null && !active,
                            onClick = {
                                val video = activeVideo ?: return@FilledTonalButton
                                switchingLine = candidate.id
                                lineSwitchError = null
                                Diagnostics.log("PLAYER_LINE_SWITCH_BEGIN video=" +
                                    video.id + " line=" + candidate.name)
                                lineSwitchJob?.cancel()
                                lineSwitchJob = scope.launch {
                                    try {
                                        when (val resolved = app.resolver.resolve(candidate)) {
                                            is ResolveResult.Playable -> {
                                                val webPosition = if (webServer != null)
                                                    app.webPlayback.positionMs else 0L
                                                app.webPlayback.close()
                                                app.playerEngine.play(
                                                    video, resolved.source,
                                                    resume = webPosition <= 0L
                                                )
                                                if (webPosition > 0L)
                                                    app.playerEngine.player.seekTo(webPosition)
                                                selectedServer = candidate
                                                webFallback = candidate
                                                webServer = null
                                                autoOpenWebHls = false
                                                embeddedHlsActive = false
                                                showPlayer = true
                                                linePickerOpen = false
                                                Diagnostics.log("PLAYER_LINE_SWITCH_NATIVE line=" +
                                                    candidate.name)
                                            }
                                            is ResolveResult.Unavailable -> {
                                                val resumeMs = if (webServer != null)
                                                    app.webPlayback.positionMs
                                                else app.playerEngine.player.currentPosition
                                                    .coerceAtLeast(0L)
                                                app.playerEngine.stop()
                                                app.webPlayback.begin(video, resumeMs)
                                                selectedServer = candidate
                                                webFallback = candidate
                                                autoOpenWebHls = SupjavLine.of(candidate.name) in
                                                    setOf(SupjavLine.EVS, SupjavLine.LUC)
                                                embeddedHlsActive = false
                                                webServer = candidate.copy(
                                                    verifiedPlayerUrl = resolved.verifiedPlayerUrl
                                                )
                                                showPlayer = false
                                                linePickerOpen = false
                                                Diagnostics.log("PLAYER_LINE_SWITCH_WEB line=" +
                                                    candidate.name)
                                            }
                                            ResolveResult.VerificationRequired -> {
                                                lineSwitchError =
                                                    "此线路需要人工验证；当前视频继续播放。请在详情页选择网页验证。"
                                                Diagnostics.log("PLAYER_LINE_SWITCH_VERIFICATION " +
                                                    candidate.name)
                                            }
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        lineSwitchError = "切换失败：" +
                                            error.javaClass.simpleName + "，原线路继续播放"
                                        Diagnostics.log("PLAYER_LINE_SWITCH_FAILED type=" +
                                            error.javaClass.simpleName)
                                    } finally {
                                        switchingLine = null
                                        lineSwitchJob = null
                                    }
                                }
                            }
                        ) {
                            if (active) {
                                Icon(Icons.Outlined.CheckCircle, null)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(candidate.name + if (active) " · 当前线路" else "")
                            if (switchingLine == candidate.id) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
                if (lineSwitchError != null) {
                    Text(lineSwitchError.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
                if (switchingLine != null) {
                    Text("正在解析所选线路，原视频会保持到解析完成。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        AnimatedVisibility(
            visible = showSettings,
            modifier = Modifier.fillMaxSize().zIndex(3f),
            enter = slideInHorizontally(tween(260)) { it },
            exit = slideOutHorizontally(tween(240)) { it }
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    SupjavHeader("设置", onBack = back)
                    SettingsScreen(app, dark, onDark)
                }
            }
        }

        if (!showSettings && webServer == null && !showPlayer && routes.isEmpty() && app.playerEngine.currentVideo != null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 86.dp).zIndex(0.5f)) {
                MiniPlayerBar(app) { showPlayer = true }
            }
        }
    }
}
