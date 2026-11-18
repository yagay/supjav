package com.yagay.supjav.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.compose.ui.awt.SwingPanel
import coil3.compose.AsyncImage
import com.yagay.supjav.model.*
import com.yagay.supjav.player.VideoParts
import com.yagay.supjav.player.PlaybackQueuePolicy
import com.yagay.supjav.util.Diagnostics
import java.awt.Desktop
import kotlinx.coroutines.delay
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.BorderLayout

private val surface = Color(0xFF111411)
internal val card = Color(0xFF20241E)
internal val green = Color(0xFFBCE6AC)

fun main() = application {
    val state = rememberWindowState(width = 1300.dp, height = 850.dp)
    val engine = remember { VlcBackend() }
    val vm = remember { DesktopController(engine) }
    DisposableEffect(vm) { onDispose { vm.close() } }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Supjav Desktop · PC",
        state = state
    ) {
        MaterialTheme(colorScheme = darkColorScheme(
            primary = green, surface = surface,
            surfaceContainer = card,
            background = Color(0xFF101310)
        )) {
            DesktopScreen(vm,
                fullscreen = state.placement == WindowPlacement.Fullscreen,
                toggleFullscreen = {
                    state.placement = if (state.placement == WindowPlacement.Fullscreen)
                        WindowPlacement.Floating else WindowPlacement.Fullscreen
                }
            )
        }
    }
}

@Composable
private fun DesktopScreen(
    vm: DesktopController,
    fullscreen: Boolean,
    toggleFullscreen: () -> Unit
) {
    val focusColor = MaterialTheme.colorScheme.primary
    var searchFocused by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(surface)
            .onPreviewKeyEvent { event ->
                if (searchFocused || vm.browserPageUrl != null ||
                    event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.Spacebar -> { vm.player.toggle(); true }
                    Key.DirectionLeft -> { vm.player.seek(-10_000); true }
                    Key.DirectionRight -> { vm.player.seek(10_000); true }
                    Key.Escape -> {
                        if (fullscreen) toggleFullscreen()
                        else vm.showQueue = false
                        true
                    }
                    Key.F -> {
                        if (vm.activeVideo != null || vm.playingVideo != null) {
                            toggleFullscreen()
                            true
                        } else false
                    }
                    else -> false
                }
            }
    ) {
        if (fullscreen) {
            PlayerPane(vm, fullscreen = true, toggleFullscreen = toggleFullscreen)
        } else {
        Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(170.dp).fillMaxHeight()
                .background(Color(0xFF171C16)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("SUPJAV", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = focusColor)
            Spacer(Modifier.height(18.dp))
            NavButton("首页", vm.localListTitle == null &&
                vm.section == FeedKind.LATEST && !vm.showCategories) { vm.select(FeedKind.LATEST) }
            NavButton("分类", vm.showCategories) { vm.browseCategories() }
            NavButton("今日热门", vm.section == FeedKind.DAY) { vm.select(FeedKind.DAY) }
            NavButton("周榜", vm.section == FeedKind.WEEK) { vm.select(FeedKind.WEEK) }
            NavButton("月榜", vm.section == FeedKind.MONTH) { vm.select(FeedKind.MONTH) }
            HorizontalDivider(Modifier.padding(vertical = 9.dp))
            NavButton("播放历史", vm.localListTitle == "播放历史") { vm.showLocalList("recent") }
            NavButton("我的收藏", vm.localListTitle == "我的收藏") { vm.showLocalList("favorites") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val logfile = Diagnostics.currentFile()
                if (logfile != null && Desktop.isDesktopSupported()) {
                    runCatching { Desktop.getDesktop().open(logfile.parent.toFile()) }
                }
            }) { Text("打开诊断日志目录") }
            Text("PC · 触屏 / 鼠标", color = Color.LightGray,
                style = MaterialTheme.typography.labelSmall)
            if (!vm.player.available)
                Text("未检测到 VLC 3.x，浏览仍可使用",
                    color = Color(0xFFFFBBA9), style = MaterialTheme.typography.labelSmall)
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = vm.searchQuery,
                    onValueChange = { vm.searchQuery = it },
                    modifier = Modifier.weight(1f)
                        .onFocusChanged { searchFocused = it.isFocused },
                    singleLine = true,
                    placeholder = { Text("搜索作品、番号、演员或标签") },
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search() })
                )
                Button(onClick = { vm.search() }, modifier = Modifier.height(50.dp)) {
                    Text("搜索")
                }
                OutlinedButton(onClick = { vm.showQueue = !vm.showQueue },
                    modifier = Modifier.height(50.dp)) { Text("播放列表 ☷") }
            }
            if (vm.activeVideo != null || vm.playingVideo != null) {
                PlayerPane(vm, fullscreen, toggleFullscreen)
                DetailActions(vm)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vm.displayTitle,
                    style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text(vm.status, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { if (vm.showCategories) vm.browseCategories() else vm.refreshFeed() }) {
                    Text("刷新")
                }
            }
            if (vm.showCategories) {
                CategoryGrid(vm, Modifier.weight(1f))
            } else {
                key(vm.feedScrollKey) {
                    PosterGrid(vm, Modifier.weight(1f))
                }
            }
        }
        if (vm.showQueue) {
            VerticalDivider()
            QueueSidebar(vm, Modifier.width(260.dp).fillMaxHeight())
        }
        }
        }
    }
}

@Composable
private fun NavButton(label: String, active: Boolean, onClick: () -> Unit) {
    val color = if (active) green.copy(alpha = 0.22f) else Color.Transparent
    Surface(color = color, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(label, modifier = Modifier.padding(horizontal = 13.dp, vertical = 13.dp),
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PlayerPane(vm: DesktopController, fullscreen: Boolean, toggleFullscreen: () -> Unit) {
    var time by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var seek by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val component = vm.player.component
    val browserUrl = vm.browserPageUrl
    val browserSession = vm.browserSession
    val gestures = remember(component, browserUrl) {
        if (component == null || browserUrl != null) null
        else DesktopTouchGestures(component, vm.player) {
            vm.showControls = !vm.showControls
        }
    }
    DisposableEffect(gestures) {
        gestures?.install()
        onDispose { gestures?.dispose() }
    }
    LaunchedEffect(vm.activeVideo?.id) {
        while (true) {
            time = vm.player.position()
            length = vm.player.duration()
            if (!dragging && length > 0) seek = (time.toFloat() / length).coerceIn(0f, 1f)
            delay(450)
        }
    }

    Column(
        (if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
    ) {
        Box(
            if (fullscreen) Modifier.fillMaxWidth().weight(1f)
            else Modifier.fillMaxWidth().height(
                if (browserUrl != null) 460.dp else 300.dp
            ),
            contentAlignment = Alignment.Center
        ) {
            if (browserUrl != null) {
                key(browserSession) {
                    DesktopBrowserPlayer(
                        browserUrl,
                        vm.activeServer?.link,
                        Modifier.fillMaxSize()
                    )
                }
            } else if (component == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("VLC 3.x 未安装 / 无法加载", color = Color.White)
                    Text("安装 64 位 VLC 后可在此播放 MP4 / HLS",
                        color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                SwingPanel(
                    modifier = Modifier.fillMaxSize(),
                    factory = { component },
                    background = Color.Black
                )
            }
        }
        if (vm.showControls) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (browserUrl == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(time), color = Color.White,
                        style = MaterialTheme.typography.labelSmall)
                    Slider(
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        value = seek,
                        onValueChange = { seek = it; dragging = true },
                        onValueChangeFinished = {
                            if (length > 0) vm.player.seekTo((seek * length).toLong())
                            dragging = false
                        }
                    )
                    Text(clock(length), color = Color.White,
                        style = MaterialTheme.typography.labelSmall)
                }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Control(
                        "⏮ 上一部",
                        !vm.switching && PlaybackQueuePolicy.adjacent(
                            vm.playlistItems.toList(),
                            (vm.playingVideo ?: vm.activeVideo)?.id, -1
                        ) != null
                    ) { vm.adjacent(-1) }
                    if (browserUrl == null) {
                        Control("−10s") { vm.player.seek(-10_000) }
                        Control(if (vm.player.playing) "暂停" else "播放") { vm.player.toggle() }
                        Control("+10s") { vm.player.seek(10_000) }
                    } else {
                        Text("网页模式 · 在画面内操作播放和进度",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Control(
                        "下一部 ⏭",
                        !vm.switching && (
                            PlaybackQueuePolicy.adjacent(
                                vm.playlistItems.toList(),
                                (vm.playingVideo ?: vm.activeVideo)?.id, 1
                            ) != null || vm.canLoadQueueMore
                        )
                    ) { vm.adjacent(1) }
                    Spacer(Modifier.weight(1f))
                    Control("列表") { vm.showQueue = !vm.showQueue }
                    Control(if (fullscreen) "退出全屏" else "全屏") { toggleFullscreen() }
                }
            }
        }
    }
    if (vm.player.feedback.isNotBlank()) {
        Text(vm.player.feedback, style = MaterialTheme.typography.labelSmall,
            color = green)
    }
    if (browserUrl == null) {
        vm.player.errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall)
        }
    }
    if (browserUrl == null &&
        vm.player.phase in setOf(PlayerPhase.OPENING, PlayerPhase.BUFFERING)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private fun clock(ms: Long): String {
    if (ms <= 0) return "00:00"
    val seconds = ms / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun Control(label: String, enabled: Boolean = true, click: () -> Unit) {
    TextButton(
        enabled = enabled,
        onClick = click,
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

