package com.yagay.supjav.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.player.VideoQualityPolicy
import com.yagay.supjav.player.PlaybackDefaults
import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    app: SupjavApp,
    onVideo: (VideoItem) -> Unit,
    initialFavorites: Boolean = true,
    showTabs: Boolean = true
) {
    var favorites by remember(initialFavorites) { mutableStateOf(initialFavorites) }
    var revision by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf(emptyList<VideoItem>()) }
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    LaunchedEffect(favorites, revision) {
        list = withContext(Dispatchers.IO) { app.library.list(favorites) }
    }
    Column {
        if (showTabs) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = favorites, onClick = { favorites = true },
                    label = { Text("收藏") })
                FilterChip(selected = !favorites, onClick = { favorites = false },
                    label = { Text("历史") })
            }
        }
        if (list.isEmpty()) {
            SupjavEmptyState(
                if (favorites) "暂无收藏" else "暂无播放历史",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.id }) { video ->
                    Column {
                        VideoTile(video, { onVideo(video) })
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        if (favorites) app.library.toggleFavorite(video)
                                        else app.library.deleteHistory(video.id)
                                    }
                                    revision++
                                }
                            }
                        ) { Text("移除") }
                    }
                }
            }
        }
    }
}


private enum class SettingsCategory(
    val title: String, val description: String
) {
    HOME("设置", ""),
    QUALITY("视频画质", "默认清晰度和画质选择策略"),
    PLAYBACK("播放默认", "播放速度、画面比例、线路、续播"),
    GESTURES("手势与控制", "双击跳转和长按倍速"),
    BACKGROUND("后台与小窗", "后台暂停、画中画与网页兼容"),
    APPEARANCE("外观与启动", "启动页面、主题与封面列数"),
    DATA("数据与诊断", "导出日志、管理播放历史")
}

private data class SettingsOption(val value: String, val label: String)

@Composable
private fun SettingsCategoryRow(
    title: String, subtitle: String, icon: ImageVector,
    summary: String, onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(subtitle, maxLines = 1) },
        leadingContent = {
            Icon(icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(summary, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SettingsValueRow(
    title: String, value: String, subtitle: String = "",
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle.isBlank()) null else {
            { Text(subtitle, style = MaterialTheme.typography.bodySmall) }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value, color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                    modifier = Modifier.size(18.dp))
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(SupjavMetrics.cardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun SettingsScreen(
    app: SupjavApp, dark: Boolean, onDark: (Boolean) -> Unit,
    onExit: () -> Unit
) {
    val prefs = app.preferences
    var category by rememberSaveable { mutableStateOf(SettingsCategory.HOME) }
    var picker by remember { mutableStateOf<String?>(null) }
    var pauseOnBackground by remember {
        mutableStateOf(prefs.getBoolean("pause_on_background", true))
    }
    var autoPip by remember { mutableStateOf(prefs.getBoolean("auto_pip", false)) }
    var showPip by remember { mutableStateOf(prefs.getBoolean("manual_pip", true)) }
    var autoWebFallback by remember {
        mutableStateOf(prefs.getBoolean("auto_web_fallback", true))
    }
    val posterColumns = app.posterColumns
    val qualityPreference = app.videoQuality
    var defaultSpeed by remember {
        mutableFloatStateOf(PlaybackDefaults.speed(
            prefs.getFloat(PlaybackDefaults.SPEED, 1f)))
    }
    var holdSpeed by remember {
        mutableFloatStateOf(PlaybackDefaults.holdSpeed(
            prefs.getFloat(PlaybackDefaults.HOLD, 2f)))
    }
    var skipSeconds by remember {
        mutableIntStateOf(PlaybackDefaults.skip(
            prefs.getInt(PlaybackDefaults.SEEK, 10)))
    }
    var defaultFill by remember {
        mutableStateOf(prefs.getBoolean(PlaybackDefaults.FIT, false))
    }
    var preferredLine by remember {
        mutableStateOf(PlaybackDefaults.line(
            prefs.getString(PlaybackDefaults.LINE, "auto")))
    }
    var autoplay by remember {
        mutableStateOf(prefs.getBoolean(PlaybackDefaults.AUTOPLAY, true))
    }
    var resumePlayback by remember {
        mutableStateOf(prefs.getBoolean(PlaybackDefaults.RESUME, true))
    }
    var startTab by remember {
        mutableStateOf(PlaybackDefaults.startup(
            prefs.getString(PlaybackDefaults.START_TAB, "RECOMMEND")))
    }
    var confirmClear by remember { mutableStateOf(false) }
    var logError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    BackHandler(enabled = category != SettingsCategory.HOME && picker == null) {
        category = SettingsCategory.HOME
    }
    fun resetPlayback() {
        defaultSpeed = 1f
        holdSpeed = 2f
        skipSeconds = 10
        defaultFill = false
        preferredLine = "auto"
        autoplay = true
        resumePlayback = true
        prefs.edit()
            .remove(PlaybackDefaults.SPEED)
            .remove(PlaybackDefaults.HOLD)
            .remove(PlaybackDefaults.SEEK)
            .remove(PlaybackDefaults.FIT)
            .remove(PlaybackDefaults.LINE)
            .remove(PlaybackDefaults.AUTOPLAY)
            .remove(PlaybackDefaults.RESUME)
            .apply()
        app.updateVideoQuality(VideoQualityPolicy.DEFAULT)
    }

    val pickerOptions = when (picker) {
        "quality" -> listOf("highest", "auto", "p1080", "p720", "p480").map {
            SettingsOption(it, VideoQualityPolicy.label(it))
        }
        "speed" -> PlaybackDefaults.speeds.map {
            SettingsOption(it.toString(), PlaybackDefaults.speedLabel(it))
        }
        "fill" -> listOf(SettingsOption("fit", "适应画面"), SettingsOption("zoom", "铺满画面"))
        "line" -> PlaybackDefaults.lineOptions.map {
            SettingsOption(it, if (it == "auto") "网站首条" else it)
        }
        "seek" -> PlaybackDefaults.seekSeconds.map {
            SettingsOption(it.toString(), it.toString() + " 秒")
        }
        "hold" -> PlaybackDefaults.holdSpeeds.map {
            SettingsOption(it.toString(), PlaybackDefaults.speedLabel(it))
        }
        "startup" -> listOf(
            SettingsOption("RECOMMEND", "首页"),
            SettingsOption("CATEGORY", "分类"),
            SettingsOption("RANK", "排行"),
            SettingsOption("MINE", "我的")
        )
        "columns" -> listOf(
            SettingsOption("0", "自动"), SettingsOption("2", "2 列"),
            SettingsOption("3", "3 列"), SettingsOption("4", "4 列")
        )
        else -> emptyList()
    }
    val pickerSelected = when (picker) {
        "quality" -> qualityPreference
        "speed" -> defaultSpeed.toString()
        "fill" -> if (defaultFill) "zoom" else "fit"
        "line" -> preferredLine
        "seek" -> skipSeconds.toString()
        "hold" -> holdSpeed.toString()
        "startup" -> startTab
        "columns" -> posterColumns.toString()
        else -> ""
    }
    val pickerTitle = when (picker) {
        "quality" -> "默认视频画质"
        "speed" -> "默认播放速度"
        "fill" -> "默认画面比例"
        "line" -> "首选播放线路"
        "seek" -> "双击快进／快退"
        "hold" -> "长按两侧倍速"
        "startup" -> "应用默认打开页面"
        "columns" -> "视频封面列数"
        else -> ""
    }

    Column(Modifier.fillMaxSize()) {
        SupjavHeader(category.title, onBack = {
            when {
                picker != null -> picker = null
                category != SettingsCategory.HOME -> category = SettingsCategory.HOME
                else -> onExit()
            }
        })
        key(category) {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (category) {
                    SettingsCategory.HOME -> {
                        Text("按类别管理默认设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp))
                        SettingsCard {
                            SettingsCategoryRow(
                                "视频画质", "清晰度优先级",
                                Icons.Outlined.PlayCircleOutline,
                                VideoQualityPolicy.label(qualityPreference)
                            ) { category = SettingsCategory.QUALITY }
                            HorizontalDivider()
                            SettingsCategoryRow(
                                "播放默认", "速度、比例、线路、自动播放与续播",
                                Icons.Outlined.Tune,
                                PlaybackDefaults.speedLabel(defaultSpeed)
                            ) { category = SettingsCategory.PLAYBACK }
                            HorizontalDivider()
                            SettingsCategoryRow(
                                "手势与控制", "快进快退、长按倍速",
                                Icons.Outlined.TouchApp,
                                skipSeconds.toString() + " 秒"
                            ) { category = SettingsCategory.GESTURES }
                            HorizontalDivider()
                            SettingsCategoryRow(
                                "后台与小窗", "后台暂停、画中画、线路兼容",
                                Icons.Outlined.PictureInPictureAlt,
                                if (pauseOnBackground) "自动暂停" else "保持播放"
                            ) { category = SettingsCategory.BACKGROUND }
                            HorizontalDivider()
                            SettingsCategoryRow(
                                "外观与启动", "主题、默认页面、封面排列",
                                Icons.Outlined.Palette,
                                if (dark) "深色" else "浅色"
                            ) { category = SettingsCategory.APPEARANCE }
                            HorizontalDivider()
                            SettingsCategoryRow(
                                "数据与诊断", "导出日志、清空播放历史",
                                Icons.Outlined.Storage, ""
                            ) { category = SettingsCategory.DATA }
                        }
                        Text("设置自动保存；进入二级页面即可修改。播放器内的临时调整不覆盖默认播放配置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp))
                    }

                    SettingsCategory.QUALITY -> {
                        SettingsCard {
                            SettingsValueRow(
                                "默认视频画质", VideoQualityPolicy.label(qualityPreference),
                                "选择最高可用、自动或指定分辨率"
                            ) { picker = "quality" }
                        }
                        Text("最高可用仅表示当前视频源实际提供的最高分辨率。原生与内置 HLS 可以切换；第三方网页播放器仍由网站控制。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp))
                    }

                    SettingsCategory.PLAYBACK -> {
                        SettingsCard {
                            SettingsValueRow(
                                "默认播放速度", PlaybackDefaults.speedLabel(defaultSpeed)
                            ) { picker = "speed" }
                            HorizontalDivider()
                            SettingsValueRow(
                                "默认画面比例", if (defaultFill) "铺满" else "适应"
                            ) { picker = "fill" }
                            HorizontalDivider()
                            SettingsValueRow(
                                "首选播放线路",
                                if (preferredLine == "auto") "网站首条" else preferredLine,
                                "优先尝试首选线路；不可用则回退到网站首条"
                            ) { picker = "line" }
                            HorizontalDivider()
                            SupjavSettingToggle(
                                "自动开始播放",
                                "原生与内置 HLS 生效；第三方网页可能自行播放",
                                autoplay
                            ) {
                                autoplay = it
                                prefs.edit().putBoolean(PlaybackDefaults.AUTOPLAY, it).apply()
                            }
                            HorizontalDivider()
                            SupjavSettingToggle(
                                "恢复上次播放进度",
                                "重新打开视频时从历史进度继续",
                                resumePlayback
                            ) {
                                resumePlayback = it
                                prefs.edit().putBoolean(PlaybackDefaults.RESUME, it).apply()
                            }
                        }
                        OutlinedButton(
                            onClick = { resetPlayback() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("恢复全部播放默认值") }
                        Text("仅重置画质、速度、比例、线路、手势与续播设置；不删除收藏和历史。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp))
                    }

                    SettingsCategory.GESTURES -> {
                        SettingsCard {
                            SettingsValueRow(
                                "双击快进／快退", skipSeconds.toString() + " 秒",
                                "左右两侧双击跳转时间"
                            ) { picker = "seek" }
                            HorizontalDivider()
                            SettingsValueRow(
                                "长按两侧播放倍速",
                                PlaybackDefaults.speedLabel(holdSpeed),
                                "松手自动恢复原速度"
                            ) { picker = "hold" }
                        }
                    }

                    SettingsCategory.BACKGROUND -> {
                        SettingsCard {
                            SupjavSettingToggle(
                                "网页脚本线路自动兼容",
                                "原生不能处理时，尝试网站播放与 MP4/HLS 识别",
                                autoWebFallback
                            ) {
                                autoWebFallback = it
                                prefs.edit().putBoolean("auto_web_fallback", it).apply()
                            }
                            HorizontalDivider()
                            SupjavSettingToggle(
                                "离开应用自动暂停",
                                "切换应用、回桌面或锁屏时暂停视频",
                                pauseOnBackground
                            ) {
                                pauseOnBackground = it
                                val editor = prefs.edit().putBoolean("pause_on_background", it)
                                if (it) {
                                    autoPip = false
                                    editor.putBoolean("auto_pip", false)
                                }
                                editor.apply()
                            }
                            HorizontalDivider()
                            SupjavSettingToggle(
                                "自动画中画",
                                if (pauseOnBackground) "先关闭离开应用自动暂停"
                                else "离开应用时尝试进入系统画中画",
                                autoPip && !pauseOnBackground
                            ) {
                                if (!pauseOnBackground) {
                                    autoPip = it
                                    prefs.edit().putBoolean("auto_pip", it).apply()
                                }
                            }
                            HorizontalDivider()
                            SupjavSettingToggle(
                                "显示手动画中画按钮",
                                "播放器内保留手动进入系统画中画的入口",
                                showPip
                            ) {
                                showPip = it
                                prefs.edit().putBoolean("manual_pip", it).apply()
                            }
                        }
                    }

                    SettingsCategory.APPEARANCE -> {
                        SettingsCard {
                            SettingsValueRow(
                                "默认打开页面",
                                when (startTab) {
                                    "CATEGORY" -> "分类"
                                    "RANK" -> "排行"
                                    "MINE" -> "我的"
                                    else -> "首页"
                                },
                                "下次重新启动 App 生效"
                            ) { picker = "startup" }
                            HorizontalDivider()
                            SupjavSettingToggle(
                                "深色模式", "全应用统一主题",
                                dark, onDark
                            )
                            HorizontalDivider()
                            SettingsValueRow(
                                "视频封面列数",
                                if (posterColumns == 0) "自动" else posterColumns.toString() + " 列",
                                "首页、分类和排行的列表密度"
                            ) { picker = "columns" }
                        }
                    }

                    SettingsCategory.DATA -> {
                        SettingsCard {
                            ListItem(
                                headlineContent = { Text("导出诊断 ZIP") },
                                supportingContent = { Text("播放或网络异常时导出日志供分析") },
                                leadingContent = { Icon(Icons.Outlined.BugReport, null) },
                                trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                                modifier = Modifier.fillMaxWidth().clickable {
                                    logError = null
                                    runCatching { Diagnostics.share(context) }
                                        .onFailure {
                                            logError = it.message ?: "导出日志失败"
                                        }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                            HorizontalDivider()
                            ListItem(
                                headlineContent = { Text("清空播放历史") },
                                supportingContent = { Text("不会删除收藏记录") },
                                leadingContent = { Icon(Icons.Outlined.DeleteOutline, null) },
                                trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                                modifier = Modifier.fillMaxWidth().clickable {
                                    confirmClear = true
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        if (logError != null) {
                            Text(logError.orEmpty(), color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 6.dp))
                        }
                        Text("Supjav Native · Android / Compose / Media3",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("不会绕过网站的验证或访问限制。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (picker != null) {
        SupjavModalSheet(pickerTitle, onDismiss = { picker = null }) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                pickerOptions.forEach { option ->
                    val selected = pickerSelected == option.value
                    ListItem(
                        headlineContent = { Text(option.label) },
                        trailingContent = {
                            if (selected) Icon(Icons.Outlined.Check, "当前选择",
                                tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.fillMaxWidth().clickable {
                            when (picker) {
                                "quality" -> app.updateVideoQuality(option.value)
                                "speed" -> {
                                    defaultSpeed = PlaybackDefaults.speed(
                                        option.value.toFloatOrNull() ?: 1f)
                                    prefs.edit().putFloat(
                                        PlaybackDefaults.SPEED, defaultSpeed).apply()
                                }
                                "fill" -> {
                                    defaultFill = option.value == "zoom"
                                    prefs.edit().putBoolean(
                                        PlaybackDefaults.FIT, defaultFill).apply()
                                }
                                "line" -> {
                                    preferredLine = PlaybackDefaults.line(option.value)
                                    prefs.edit().putString(
                                        PlaybackDefaults.LINE, preferredLine).apply()
                                }
                                "seek" -> {
                                    skipSeconds = PlaybackDefaults.skip(
                                        option.value.toIntOrNull() ?: 10)
                                    prefs.edit().putInt(
                                        PlaybackDefaults.SEEK, skipSeconds).apply()
                                }
                                "hold" -> {
                                    holdSpeed = PlaybackDefaults.holdSpeed(
                                        option.value.toFloatOrNull() ?: 2f)
                                    prefs.edit().putFloat(
                                        PlaybackDefaults.HOLD, holdSpeed).apply()
                                }
                                "startup" -> {
                                    startTab = PlaybackDefaults.startup(option.value)
                                    prefs.edit().putString(
                                        PlaybackDefaults.START_TAB, startTab).apply()
                                }
                                "columns" -> {
                                    app.updatePosterColumns(
                                        option.value.toIntOrNull() ?: 0)
                                }
                            }
                            picker = null
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空所有播放历史？") },
        text = { Text("收藏不会被清空。") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                scope.launch(Dispatchers.IO) { app.library.clearHistory() }
            }) { Text("清空") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
    )
}
