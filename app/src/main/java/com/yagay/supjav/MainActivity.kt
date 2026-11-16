package com.yagay.supjav

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.ui.*

class MainActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)
    private var playerVisible by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SupjavApp
        setContent {
            var dark by remember { mutableStateOf(app.preferences.getBoolean("dark", true)) }
            SupjavTheme(dark) {
                Root(app, inPip, { visible -> playerVisible = visible }, ::requestPip,
                    dark) { enabled ->
                    dark = enabled
                    app.preferences.edit().putBoolean("dark", enabled).apply()
                }
            }
        }
    }
    private fun requestPip() {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !isFinishing &&
            (application as SupjavApp).playerEngine.player.isPlaying) {
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
    var ageConfirmed by remember { mutableStateOf(app.preferences.getBoolean("age_confirmed", false)) }
    val context = androidx.compose.ui.platform.LocalContext.current
    if (!ageConfirmed) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("年龄确认 / Age verification") },
            text = { Text("仅供成年人使用。请确认你已年满18岁，并遵守当地法律和网站使用条款。") },
            confirmButton = {
                TextButton(onClick = {
                    app.preferences.edit().putBoolean("age_confirmed", true).apply()
                    ageConfirmed = true
                }) { Text("已满18岁") }
            },
            dismissButton = {
                TextButton(onClick = { (context as? ComponentActivity)?.finish() }) { Text("退出") }
            }
        )
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var feedKind by remember { mutableStateOf(FeedKind.LATEST) }
    var feedValue by remember { mutableStateOf("") }
    var discoverKind by remember { mutableStateOf(FeedKind.SEARCH) }
    var discoverValue by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<VideoItem?>(app.playerEngine.currentVideo) }
    var showDetail by remember { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }

    SideEffect { onPlayerVisibility(showPlayer) }

    val back: () -> Unit = {
        when {
            showPlayer -> { app.playerEngine.savePosition(); showPlayer = false }
            showDetail -> showDetail = false
            tab != 0 -> tab = 0
        }
    }
    BackHandler(enabled = showPlayer || showDetail || tab != 0) { back() }

    val onOpen: (VideoItem) -> Unit = {
        selected = it
        showDetail = true
        showPlayer = false
    }

    if (inPip) {
        PlayerScreen(app, true, onPip, { showPlayer = false })
        return
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                SupjavHeader(
                    title = listOf("Supjav", "发现", "我的")[tab],
                    actions = {
                        if (tab == 0) {
                            IconButton(onClick = { tab = 1 }) {
                                Icon(Icons.Outlined.Search, "搜索")
                            }
                            IconButton(onClick = { refreshKey++ }) {
                                Icon(Icons.Outlined.Refresh, "重新加载")
                            }
                        }
                    }
                )
            },
            bottomBar = { SupjavPrimaryNavigation(tab) { tab = it } }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    0 -> Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(FeedKind.LATEST, FeedKind.DAY, FeedKind.WEEK, FeedKind.MONTH).forEach { kind ->
                                FilterChip(selected = feedKind == kind, onClick = {
                                    feedKind = kind
                                    feedValue = ""
                                }, label = { Text(kind.title) })
                            }
                        }
                        FeedScreen(app, feedKind, feedValue, onOpen, refreshKey)
                    }
                    1 -> DiscoverScreen(app, onOpen, discoverKind, discoverValue)
                    else -> MyHubScreen(app, dark, onDark, onOpen)
                }
            }
        }

        // The root destination remains mounted behind detail and player.
        // Media3 stays service-owned; changing a tab never recreates a player instance.
        AnimatedVisibility(
            visible = showDetail,
            modifier = Modifier.fillMaxSize().zIndex(1f),
            enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it } +
                fadeIn(tween(160)),
            exit = slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it } +
                fadeOut(tween(160))
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    SupjavHeader("视频详情", onBack = back)
                    selected?.let { video ->
                        DetailScreen(app, video, onVideo = onOpen, onPlay = {
                            showPlayer = true
                        }, onFilter = { kind, value ->
                            discoverKind = kind
                            discoverValue = value
                            tab = 1
                            showDetail = false
                        })
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showPlayer,
            modifier = Modifier.fillMaxSize().zIndex(2f),
            enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it },
            exit = slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it }
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    SupjavHeader("正在播放", onBack = back, actions = {
                        IconButton(onClick = onPip) {
                            Icon(Icons.Outlined.PictureInPictureAlt, "画中画")
                        }
                    })
                    PlayerScreen(app, false, onPip, { showPlayer = false })
                }
            }
        }

        if (!showDetail && !showPlayer && app.playerEngine.currentVideo != null) {
            // Discrete mini entry, like Hongguo's mini presentation:
            // player continues underneath the navigation destinations.
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp).zIndex(0.5f)) {
                MiniPlayerBar(app, onClick = { showPlayer = true })
            }
        }
    }
}
