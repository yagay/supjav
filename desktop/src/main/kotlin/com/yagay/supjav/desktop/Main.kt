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
import kotlinx.coroutines.delay
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.BorderLayout

private val surface = Color(0xFF111411)
private val card = Color(0xFF20241E)
private val green = Color(0xFFBCE6AC)

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
    Row(
        Modifier.fillMaxSize().background(surface)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.Spacebar -> { vm.player.toggle(); true }
                    Key.DirectionLeft -> { vm.player.seek(-10_000); true }
                    Key.DirectionRight -> { vm.player.seek(10_000); true }
                    Key.Escape -> {
                        if (fullscreen) toggleFullscreen()
                        else vm.showQueue = false
                        true
                    }
                    Key.F -> { toggleFullscreen(); true }
                    else -> false
                }
            }
    ) {
        Column(
            Modifier.width(170.dp).fillMaxHeight()
                .background(Color(0xFF171C16)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("SUPJAV", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = focusColor)
            Spacer(Modifier.height(18.dp))
            NavButton("首页", vm.section == FeedKind.LATEST) { vm.select(FeedKind.LATEST) }
            NavButton("分类", vm.showCategories) { vm.browseCategories() }
            NavButton("今日热门", vm.section == FeedKind.DAY) { vm.select(FeedKind.DAY) }
            NavButton("周榜", vm.section == FeedKind.WEEK) { vm.select(FeedKind.WEEK) }
            NavButton("月榜", vm.section == FeedKind.MONTH) { vm.select(FeedKind.MONTH) }
            HorizontalDivider(Modifier.padding(vertical = 9.dp))
            NavButton("播放历史", false) { vm.showLocalList("recent") }
            NavButton("我的收藏", false) { vm.showLocalList("favorites") }
            Spacer(Modifier.weight(1f))
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
                    modifier = Modifier.weight(1f),
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
            if (vm.activeVideo != null) {
                PlayerPane(vm, fullscreen, toggleFullscreen)
                DetailActions(vm)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (vm.showCategories) "分类目录"
                    else if (vm.section == FeedKind.SEARCH) "搜索结果 · " + vm.sectionValue
                    else vm.section.title,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.weight(1f))
                Text(vm.status, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { if (vm.showCategories) vm.browseCategories() else vm.loadFeed() }) {
                    Text("刷新")
                }
            }
            if (vm.showCategories) {
                CategoryGrid(vm, Modifier.weight(1f))
            } else {
                PosterGrid(vm, Modifier.weight(1f))
            }
        }
        if (vm.showQueue) {
            VerticalDivider()
            QueueSidebar(vm, Modifier.width(260.dp).fillMaxHeight())
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
    val gestures = remember(component) {
        if (component == null) null else DesktopTouchGestures(component, vm.player) {
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

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(Color.Black)) {
        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
            if (component == null) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Control("⏮ 上一部", vm.activeVideo != null) { vm.adjacent(-1) }
                    Control("−10s") { vm.player.seek(-10_000) }
                    Control(if (vm.player.playing) "暂停" else "播放") { vm.player.toggle() }
                    Control("+10s") { vm.player.seek(10_000) }
                    Control("下一部 ⏭", vm.activeVideo != null) { vm.adjacent(1) }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailActions(vm: DesktopController) {
    val detail = vm.detail
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(vm.activeVideo?.title.orEmpty(), Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = vm::toggleFavorite) {
                Text(if (vm.activeVideo?.id?.let(vm::isFavorite) == true)
                    "取消收藏" else "收藏")
            }
            TextButton(onClick = vm::openOfficialPage) { Text("网页兼容播放") }
        }
        if (detail == null) {
            Text(vm.status, color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall)
        } else {
            val parts = VideoParts.available(detail.servers)
            if (parts.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("视频分段", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(10.dp))
                    parts.forEach { part ->
                        FilterChip(
                            selected = VideoParts.partOf(vm.activeServer ?: detail.servers.first()) == part,
                            onClick = { vm.changePart(part) },
                            label = { Text(part) },
                            modifier = Modifier.padding(end = 7.dp),
                            enabled = !vm.switching
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("线路", style = MaterialTheme.typography.labelMedium)
                val currentPart = vm.activeServer?.let(VideoParts::partOf) ?: parts.firstOrNull()
                detail.servers.filter { server ->
                    parts.size < 2 || VideoParts.partOf(server) == currentPart
                }.forEach { line ->
                    FilterChip(
                        selected = line.id == vm.activeServer?.id,
                        onClick = { vm.play(line) },
                        label = { Text(line.name) },
                        enabled = !vm.switching
                    )
                }
            }
            if (vm.switching) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PosterGrid(vm: DesktopController, modifier: Modifier) {
    val grid = rememberLazyGridState()
    val last by remember { derivedStateOf {
        grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    } }
    LaunchedEffect(last, vm.videos.size, vm.pageLoading, vm.reachedEnd) {
        if (last >= vm.videos.size - 5 && vm.videos.isNotEmpty() &&
            !vm.pageLoading && !vm.reachedEnd) vm.loadMore()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(142.dp),
        state = grid,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        items(vm.videos, key = { it.id }) { item ->
            Poster(item, active = vm.activeVideo?.id == item.id) { vm.open(item) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                if (vm.pageLoading) CircularProgressIndicator(Modifier.size(23.dp))
                else if (!vm.reachedEnd) TextButton(onClick = vm::loadMore) { Text("加载更多") }
            }
        }
    }
}

@Composable
private fun Poster(item: VideoItem, active: Boolean, click: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .clickable(onClick = click),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(9.dp))
                .background(card)
                .border(
                    if (active) 2.dp else 0.dp,
                    if (active) green else Color.Transparent,
                    RoundedCornerShape(9.dp)
                )
        ) {
            AsyncImage(
                model = item.coverUrl.ifBlank { null },
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (item.code.isNotBlank())
                Text(item.code, modifier = Modifier.align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.77f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall)
        }
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CategoryGrid(vm: DesktopController, modifier: Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(200.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(vm.categories, key = { it.path }) { category ->
            OutlinedButton(
                onClick = { vm.select(category.kind, category.path) },
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text(category.label, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun QueueSidebar(vm: DesktopController, modifier: Modifier) {
    val grid = rememberLazyGridState()
    val idx = vm.videos.indexOfFirst { it.id == vm.activeVideo?.id }
    LaunchedEffect(vm.activeVideo?.id) {
        if (idx >= 0) grid.scrollToItem((idx / 2) * 2)
    }
    val last by remember { derivedStateOf {
        grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    } }
    LaunchedEffect(last, vm.videos.size, vm.pageLoading, vm.reachedEnd) {
        if (last >= vm.videos.size - 3 && vm.videos.isNotEmpty() &&
            !vm.pageLoading && !vm.reachedEnd) vm.loadMore()
    }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("播放列表", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(vm.videos.size.toString(), style = MaterialTheme.typography.labelSmall)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = grid,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(vm.videos, key = { it.id }) { item ->
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.72f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(card)
                        .clickable { vm.open(item, autoplay = true) }
                        .border(
                            if (item.id == vm.activeVideo?.id) 2.dp else 0.dp,
                            if (item.id == vm.activeVideo?.id) green else Color.Transparent,
                            RoundedCornerShape(7.dp)
                        )
                ) {
                    AsyncImage(
                        model = item.coverUrl.ifBlank { null },
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (item.code.isNotBlank())
                        Text(item.code, modifier = Modifier.align(Alignment.BottomStart)
                            .background(Color.Black.copy(alpha = .75f))
                            .padding(4.dp), color = Color.White,
                            maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (!vm.reachedEnd) TextButton(
            onClick = vm::loadMore, enabled = !vm.pageLoading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (vm.pageLoading) "正在加载..." else "加载更多") }
    }
}
