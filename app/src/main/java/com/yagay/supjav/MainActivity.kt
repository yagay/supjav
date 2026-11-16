package com.yagay.supjav

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.ui.*
import com.yagay.supjav.util.Diagnostics

class MainActivity : ComponentActivity() {
    private var pipState by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SupjavApp
        setContent {
            var dark by remember { mutableStateOf(app.preferences.getBoolean("dark", true)) }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                SupjavRoot(app, pipState, { enterPip() }, dark) { enabled ->
                    dark = enabled
                    app.preferences.edit().putBoolean("dark", enabled).apply()
                }
            }
        }
    }
    private fun enterPip() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            enterPictureInPictureMode(PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9)).build())
        }
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipState = isInPictureInPictureMode
    }
    override fun onStop() {
        super.onStop()
        val app = application as SupjavApp
        if (!app.preferences.getBoolean("background", true) && !isInPictureInPictureMode) {
            app.playerEngine.player.pause()
            app.playerEngine.savePosition()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupjavRoot(
    app: SupjavApp,
    pip: Boolean,
    enterPip: () -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit
) {
    var ageConfirmed by remember { mutableStateOf(app.preferences.getBoolean("age_confirmed", false)) }
    if (!ageConfirmed) {
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = {},
            title = { Text("年龄确认 / Age verification") },
            text = { Text("本应用只供成年人使用。请确认你已年满18岁，并遵守所在地法律及内容使用条款。") },
            confirmButton = {
                TextButton(onClick = {
                    app.preferences.edit().putBoolean("age_confirmed", true).apply()
                    ageConfirmed = true
                }) { Text("我已年满18岁") }
            },
            dismissButton = {
                TextButton(onClick = { (context as? ComponentActivity)?.finish() }) { Text("退出") }
            }
        )
        return
    }

    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<VideoItem?>(app.playerEngine.currentVideo) }
    var playerOpen by remember { mutableStateOf(app.playerEngine.currentVideo != null && app.playerEngine.player.isPlaying) }
    var kind by remember { mutableStateOf(FeedKind.LATEST) }
    var feedValue by remember { mutableStateOf("") }
    val video = selected
    val back: () -> Unit = {
        if (playerOpen) { app.playerEngine.savePosition(); playerOpen = false }
        else if (selected != null) selected = null
        else if (tab != 0) tab = 0
    }
    BackHandler(enabled = playerOpen || selected != null || tab != 0) { back() }
    if (pip) {
        PlayerScreen(app, true, enterPip, { playerOpen = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (playerOpen) "正在播放" else if (video != null) "作品详情"
                    else listOf("Supjav", "发现", "我的收藏", "设置")[tab]) },
                navigationIcon = {
                    if (playerOpen || video != null) {
                        IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, "返回") }
                    }
                },
                actions = {
                    if (playerOpen) {
                        IconButton(onClick = enterPip) { Icon(Icons.Filled.PictureInPictureAlt, "画中画") }
                    } else if (video == null && tab == 0) {
                        IconButton(onClick = { tab = 1 }) { Icon(Icons.Filled.Search, "搜索") }
                        IconButton(onClick = { kind = FeedKind.LATEST; feedValue = "" }) {
                            Icon(Icons.Filled.Refresh, "首页")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!playerOpen && video == null) NavigationBar {
                val icons = listOf(Icons.Filled.Home, Icons.Filled.Explore, Icons.Filled.Bookmark, Icons.Filled.Settings)
                val names = listOf("首页", "发现", "收藏", "我的")
                names.forEachIndexed { index, name ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(icons[index], name) }, label = { Text(name) })
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                playerOpen -> PlayerScreen(app, false, enterPip) { playerOpen = false }
                video != null -> DetailScreen(app, video,
                    onVideo = { selected = it },
                    onPlay = { playerOpen = true },
                    onFilter = { filter, term ->
                        kind = filter
                        feedValue = term
                        selected = null
                        tab = 0
                    })
                tab == 0 -> Column {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(FeedKind.LATEST, FeedKind.DAY, FeedKind.WEEK, FeedKind.MONTH).forEach { choice ->
                            FilterChip(selected = kind == choice, onClick = { kind = choice; feedValue = "" },
                                label = { Text(choice.title) })
                        }
                    }
                    FeedScreen(app, kind, feedValue, onVideo = { selected = it })
                }
                tab == 1 -> DiscoverScreen(app, onVideo = { selected = it })
                tab == 2 -> LibraryScreen(app, onVideo = { selected = it })
                else -> SettingsScreen(app, dark, onDark)
            }
        }
    }
}
