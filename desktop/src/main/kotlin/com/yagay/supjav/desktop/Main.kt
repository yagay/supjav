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
import java.nio.file.Files
import kotlinx.coroutines.delay
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.BorderLayout

private val surface = Color(0xFF111411)
internal val card = Color(0xFF20241E)
internal val green = Color(0xFFBCE6AC)

fun main() = application {
    val state = rememberWindowState(width = 1300.dp, height = 850.dp)
    val engine = remember { DesktopPlayerGroup() }
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
                .background(Color(0xFF171C16))
                .verticalScroll(rememberScrollState()).padding(14.dp),
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
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                val logfile = Diagnostics.currentFile()
                if (logfile != null && Desktop.isDesktopSupported()) {
                    runCatching { Desktop.getDesktop().open(logfile.parent.toFile()) }
                }
            }) { Text("打开诊断日志目录") }
            val nativeGroup = vm.player as? DesktopPlayerGroup
            if (nativeGroup != null) {
                Text("原生内核：" + nativeGroup.availableEngines,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = {
                    val dir = DesktopVideoStore.defaultDirectory().resolve("mpv")
                    runCatching {
                        Files.createDirectories(dir)
                        if (Desktop.isDesktopSupported())
                            Desktop.getDesktop().open(dir.toFile())
                    }
                }) { Text("打开 MPV 运行库目录") }
            }
            Text("PC · 触屏 / 鼠标", color = Color.LightGray,
                style = MaterialTheme.typography.labelSmall)
            if (!vm.player.available)
                Text("未检测到 MPV 或 VLC，网页播放仍可使用",
                    color = Color(0xFFFFBBA9), style = MaterialTheme.typography.labelSmall)
        }
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .verticalScroll(rememberScrollState()).padding(15.dp),
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
                CategoryGrid(vm, Modifier.fillMaxWidth().height(620.dp))
            } else {
                key(vm.feedScrollKey) {
                    PosterGrid(vm, Modifier.fillMaxWidth().height(650.dp))
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
    var volume by remember { mutableFloatStateOf(50f) }
    var volumeDragging by remember { mutableStateOf(false) }
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
    LaunchedEffect(vm.activeVideo?.id, browserUrl, component) {
        while (true) {
            if (browserUrl == null) {
                time = vm.player.position().coerceAtLeast(0L)
                length = vm.player.duration().coerceAtLeast(0L)
                if (!dragging && length > 0L) {
                    seek = (time.toFloat() / length).coerceIn(0f, 1f)
                }
                if (!volumeDragging) volume = vm.player.volumePercent().toFloat()
            }
            delay(350)
        }
    }
    val canPrevious = !vm.switching && PlaybackQueuePolicy.adjacent(
        vm.playlistItems.toList(), (vm.playingVideo ?: vm.activeVideo)?.id, -1
    ) != null
    val canNext = !vm.switching && (
        PlaybackQueuePolicy.adjacent(
            vm.playlistItems.toList(), (vm.playingVideo ?: vm.activeVideo)?.id, 1
        ) != null || vm.canLoadQueueMore
    )

    Column(
        (if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF080908))
    ) {
        Box(
            modifier = if (fullscreen) Modifier.fillMaxWidth().weight(1f)
                       else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            contentAlignment = Alignment.Center
        ) {
            if (browserUrl != null) {
                key(browserSession) {
                    DesktopBrowserPlayer(
                        browserUrl, vm.activeServer?.link, Modifier.fillMaxSize()
                    )
                }
            } else if (component == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("原生播放器不可用", color = Color.White)
                    Text("请安装 64 位 VLC 或正确配置 MPV",
                        color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                SwingPanel(
                    modifier = Modifier.fillMaxSize(),
                    factory = { component },
                    background = Color.Black
                )
            }
            if (browserUrl == null &&
                vm.player.phase in setOf(PlayerPhase.OPENING, PlayerPhase.BUFFERING)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = green, strokeWidth = 2.dp
                )
            }
        }

        // A native stream has independent seek/play/volume controls.
        // WebView2 cannot be controlled through VLC, so do not show inert controls.
        if (vm.showControls || browserUrl != null) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF151715))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                if (browserUrl == null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(clock(if (dragging) (seek * length).toLong() else time),
                            color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(
                            modifier = Modifier.weight(1f).height(28.dp)
                                .padding(horizontal = 8.dp),
                            value = seek,
                            enabled = length > 0L,
                            onValueChange = { seek = it; dragging = true },
                            onValueChangeFinished = {
                                if (length > 0L) vm.player.seekTo((seek * length).toLong())
                                dragging = false
                            }
                        )
                        Text(clock(length), color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    PlayerButton("⏮", "上一部", canPrevious) { vm.adjacent(-1) }
                    if (browserUrl == null) {
                        PlayerButton("↶10", "后退10秒") { vm.player.seek(-10_000) }
                        PlayerButton(
                            if (vm.player.playing) "Ⅱ" else "▶",
                            if (vm.player.playing) "暂停" else "播放",
                            prominent = true
                        ) { vm.player.toggle() }
                        PlayerButton("10↷", "前进10秒") { vm.player.seek(10_000) }
                    }
                    PlayerButton("⏭", "下一部", canNext) { vm.adjacent(1) }
                    if (browserUrl == null) {
                        Spacer(Modifier.width(8.dp))
                        PlayerButton(
                            if (volume <= 0f) "◀̸" else "♪", "静音切换"
                        ) {
                            if (volume > 0f) {
                                vm.player.setVolumePercent(0)
                                volume = 0f
                            } else {
                                vm.player.setVolumePercent(50)
                                volume = 50f
                            }
                        }
                        Slider(
                            value = volume, onValueChange = {
                                volume = it
                                volumeDragging = true
                                vm.player.setVolumePercent(it.toInt())
                            },
                            onValueChangeFinished = { volumeDragging = false },
                            valueRange = 0f..100f,
                            modifier = Modifier.width(92.dp).height(28.dp)
                        )
                    } else {
                        Text("网页模式 · 播放和进度请在网页画面中操作",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 6.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    PlayerButton("☷", "播放列表") { vm.showQueue = !vm.showQueue }
                    PlayerButton(
                        if (fullscreen) "⤡" else "⛶",
                        if (fullscreen) "退出全屏" else "全屏"
                    ) { toggleFullscreen() }
                }
            }
        }
    }
    if (vm.player.feedback.isNotBlank() && browserUrl == null) {
        Text(vm.player.feedback, style = MaterialTheme.typography.labelSmall, color = green)
    }
    if (browserUrl == null) {
        vm.player.errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun clock(ms: Long): String {
    if (ms <= 0) return "00:00"
    val seconds = ms / 1000
    val hours = seconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, seconds / 60 % 60, seconds % 60)
           else "%02d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun PlayerButton(
    symbol: String,
    description: String,
    enabled: Boolean = true,
    prominent: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (!enabled) Color.Gray else if (prominent) green else Color.White
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(38.dp).width(if (prominent) 48.dp else 43.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(symbol, color = color,
            style = if (prominent) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1)
    }
}
