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
    var webFallback by remember { mutableStateOf<VideoServer?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var libraryRevision by remember { mutableIntStateOf(0) }
    var targetCategoryKind by remember { mutableStateOf<FeedKind?>(null) }
    var targetCategoryPath by remember { mutableStateOf("") }
    var targetSearchKind by remember { mutableStateOf(FeedKind.SEARCH) }
    var targetSearchValue by remember { mutableStateOf("") }

    SideEffect { onPlayerVisibility(showPlayer) }
    val onOpen: (VideoItem) -> Unit = { video ->
        routes.add(video)
        webServer = null
        webFallback = null
        showPlayer = false
        showSettings = false
    }
    val back: () -> Unit = {
        when {
            showSettings -> showSettings = false
            webServer != null -> webServer = null
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
    BackHandler(enabled = showSettings || webServer != null || showPlayer ||
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
                                onPlay = { showPlayer = true },
                                onWebPlay = { server ->
                                    app.playerEngine.player.pause()
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
                Column {
                    if (!landscape) {
                        SupjavHeader("正在播放", onBack = back, actions = {
                            if (app.preferences.getBoolean("manual_pip", true)) {
                                IconButton(onClick = onPip) {
                                    Icon(Icons.Outlined.PictureInPictureAlt, "画中画")
                                }
                            }
                        })
                    }
                    PlayerScreen(
                        app, false, onPip,
                        onBack = { back() },
                        onWebFallback = webFallback?.let { candidate ->
                            {
                                app.playerEngine.player.pause()
                                showPlayer = false
                                webServer = candidate
                                Diagnostics.log("NATIVE_FAILED_RETURN_TO_WEB line=" + candidate.name)
                            }
                        }
                    )
                }
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
                    SupjavHeader("网页兼容播放器", onBack = back)
                    webServer?.let { server ->
                        key(server.id, server.link) {
                            ScriptPlayerScreen(server) { source ->
                                val video = activeVideo
                                if (video != null) {
                                    Diagnostics.log("NATIVE_HANDOFF video=" + video.id +
                                        " line=" + server.name + " type=" + source.mimeType)
                                    webFallback = server
                                    webServer = null
                                    app.playerEngine.play(video, source, resume = false)
                                    showPlayer = true
                                }
                            }
                        }
                    }
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
