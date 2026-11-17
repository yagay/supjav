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
import com.yagay.supjav.player.PlaybackDefaults
import com.yagay.supjav.player.VideoParts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)
    private var playerVisible by mutableStateOf(false)
    // User-initiated picture-in-picture is the only background transition
    // which is permitted to keep its own visible playback surface.
    private var manualPipRequested = false

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
            manualPipRequested = runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9)).build())
            }.getOrDefault(false)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val app = application as SupjavApp
        if (!app.preferences.getBoolean("pause_on_background", true) &&
            playerVisible && app.preferences.getBoolean("auto_pip", false) &&
            app.playerEngine.player.isPlaying) requestPip()
    }

    override fun onPictureInPictureModeChanged(mode: Boolean, config: Configuration) {
        super.onPictureInPictureModeChanged(mode, config)
        inPip = mode
        if (!mode) manualPipRequested = false
    }

    private fun pauseWhenLeavingApp() {
        if (isInPictureInPictureMode || manualPipRequested) return
        val app = application as SupjavApp
        if (!app.preferences.getBoolean("pause_on_background", true)) return
        // Pause, do not stop, clear the media or navigate away. Returning to
        // the task resumes the same Compose page and paused player position.
        app.playerEngine.player.pause()
        app.playerEngine.savePosition()
        app.webPlayback.pauseForBackground()
        Diagnostics.log("APP_BACKGROUND_PAUSE native=true web=true")
    }

    override fun onPause() {
        pauseWhenLeavingApp()
        super.onPause()
    }

    override fun onStop() {
        pauseWhenLeavingApp()
        (application as SupjavApp).playerEngine.savePosition()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        manualPipRequested = false
        // Restore WebView interaction, not playback. The user taps Play.
        (application as SupjavApp).webPlayback.resumeAfterBackground()
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
    var tab by rememberSaveable {
        mutableStateOf(
            SupjavTab.valueOf(
                PlaybackDefaults.startup(
                    app.preferences.getString(PlaybackDefaults.START_TAB, "RECOMMEND")
                )
            )
        )
    }
    val routes = remember(app) {
        mutableStateListOf<VideoItem>().apply {
            // OS process death restores the last detail page without
            // auto-playing a stale signed URL.
            app.recentRoute.restore()?.let { add(it) }
        }
    }
    LaunchedEffect(routes.toList()) {
        app.recentRoute.save(routes.lastOrNull())
    }
    val activeVideo = routes.lastOrNull()
    var showPlayer by remember { mutableStateOf(false) }
    var webServer by remember { mutableStateOf<VideoServer?>(null) }
    var embeddedHlsActive by remember { mutableStateOf(false) }
    var webFallback by remember { mutableStateOf<VideoServer?>(null) }
    var availableServers by remember { mutableStateOf<List<VideoServer>>(emptyList()) }
    var selectedServer by remember { mutableStateOf<VideoServer?>(null) }
    var linePickerOpen by remember { mutableStateOf(false) }
    var partPickerOpen by remember { mutableStateOf(false) }
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
    // Search is an inline part of each page; it never changes the main tab.
    var searchInput by rememberSaveable { mutableStateOf("") }
    var searchKind by rememberSaveable { mutableStateOf(FeedKind.SEARCH) }
    var activeSearchValue by rememberSaveable { mutableStateOf("") }
    var activeSearchKind by rememberSaveable { mutableStateOf(FeedKind.SEARCH) }
    var searchRevision by remember { mutableIntStateOf(0) }
    var recentSearches by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(app) {
        recentSearches = withContext(Dispatchers.IO) { app.searchStore.recent() }
    }
    fun clearSearch() {
        activeSearchValue = ""
        searchInput = ""
    }
    fun submitSearch(raw: String, kind: FeedKind) {
        val term = raw.trim()
        if (term.isEmpty()) return
        searchInput = term
        searchKind = kind
        if (kind == FeedKind.SEARCH) {
            activeSearchValue = term
            activeSearchKind = kind
            searchRevision++
            recentSearches = (listOf(term) +
                recentSearches.filterNot { it.equals(term, ignoreCase = true) }).take(20)
            scope.launch(Dispatchers.IO) { app.searchStore.add(term) }
        } else {
            // Site search is full-text only. Taxonomy selection must resolve
            // an observed path, never fabricate /cast/<typed-name>/.
            activeSearchValue = ""
            activeSearchKind = kind
            targetCategoryKind = kind
            targetCategoryPath = ""
            tab = SupjavTab.CATEGORY
        }
    }

    SideEffect { onPlayerVisibility(showPlayer) }
    val onOpen: (VideoItem) -> Unit = { video ->
        if (!DetailRoutePolicy.shouldPush(routes, video)) {
            Diagnostics.log("NAV_OPEN_DUPLICATE_IGNORED video=" + video.id +
                " depth=" + routes.size)
        } else {
        lineSwitchJob?.cancel()
        lineSwitchJob = null
        switchingLine = null
        linePickerOpen = false
        partPickerOpen = false
        lineSwitchError = null
        availableServers = emptyList()
        selectedServer = null
        autoOpenWebHls = false
        app.webPlayback.close()
        routes.add(video)
        Diagnostics.log("NAV_OPEN_DETAIL video=" + video.id +
            " depth=" + routes.size)
        embeddedHlsActive = false
        webServer = null
        webFallback = null
        showPlayer = false
        showSettings = false
        }
    }
    // A Back press must always change the top-most visible UI state.
    // The pending resolver is cancellable; never swallow Back while switching.
    fun cancelPendingSourceSwitch() {
        if (switchingLine != null) {
            Diagnostics.log("NAV_BACK_CANCEL_SOURCE_SWITCH")
            lineSwitchJob?.cancel()
            lineSwitchJob = null
            switchingLine = null
            lineSwitchError = null
        }
    }
    val back: () -> Unit = {
        // A hidden/stale picker is not a visible navigation destination.
        // In that case continue to the real page in the same Back press.
        if (activeVideo == null) {
            partPickerOpen = false
            linePickerOpen = false
        }
        when {
            partPickerOpen && activeVideo != null -> {
                cancelPendingSourceSwitch()
                partPickerOpen = false
                Diagnostics.log("NAV_BACK_CLOSE_PART_PICKER")
            }
            linePickerOpen && activeVideo != null -> {
                cancelPendingSourceSwitch()
                linePickerOpen = false
                Diagnostics.log("NAV_BACK_CLOSE_LINE_PICKER")
            }
            showSettings -> {
                showSettings = false
                Diagnostics.log("NAV_BACK_CLOSE_SETTINGS")
            }
            webServer != null -> {
                app.webPlayback.close()
                embeddedHlsActive = false
                webServer = null
                Diagnostics.log("NAV_BACK_LEAVE_WEB_PLAYER")
            }
            showPlayer -> {
                // Returning from the video screen must pause the Media3 engine;
                // removing its Compose surface alone does not stop audio.
                // Keep the media item and position for the mini-player / resume.
                app.playerEngine.player.pause()
                app.playerEngine.savePosition()
                Diagnostics.log("PLAYER_NAVIGATE_BACK_PAUSE")
                (context as? ComponentActivity)?.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                showPlayer = false
            }
            routes.isNotEmpty() -> {
                val from = routes.lastOrNull()?.id.orEmpty()
                val removed = DetailRoutePolicy.popVisible(routes)
                libraryRevision++
                Diagnostics.log("NAV_BACK_LEAVE_DETAIL video=" + from +
                    " popped=" + removed + " remaining=" + routes.size)
            }
            activeSearchValue.isNotBlank() -> {
                clearSearch()
                Diagnostics.log("NAV_BACK_CLEAR_SEARCH")
            }
            tab != SupjavTab.RECOMMEND -> {
                clearSearch()
                tab = SupjavTab.RECOMMEND
                Diagnostics.log("NAV_BACK_TO_RECOMMEND")
            }
        }
    }

    // The same switch transaction is used by playback-line and CD selectors.
    // CD changes restart at 0; switching mirrors of the same CD keeps progress.
    fun switchServer(candidate: VideoServer) {
        val video = activeVideo ?: return
        if (switchingLine != null) return
        val currentPart = selectedServer?.let(VideoParts::partOf)
        val nextPart = VideoParts.partOf(candidate)
        val changingPart = nextPart != null && nextPart != currentPart
        switchingLine = candidate.id
        lineSwitchError = null
        Diagnostics.log("PLAYER_SOURCE_SWITCH_BEGIN video=" + video.id +
            " line=" + candidate.name + " part=" + (nextPart ?: "none"))
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
                            resume = !changingPart && webPosition <= 0L
                        )
                        if (!changingPart && webPosition > 0L)
                            app.playerEngine.player.seekTo(webPosition)
                        selectedServer = candidate
                        webFallback = candidate
                        webServer = null
                        autoOpenWebHls = false
                        embeddedHlsActive = false
                        showPlayer = true
                        partPickerOpen = false
                        linePickerOpen = false
                        Diagnostics.log("PLAYER_SOURCE_SWITCH_NATIVE line=" + candidate.name)
                    }
                    is ResolveResult.Unavailable -> {
                        val resumeMs = if (changingPart) 0L else if (webServer != null)
                            app.webPlayback.positionMs
                        else app.playerEngine.player.currentPosition.coerceAtLeast(0L)
                        // Keep old player state intact until the destination resolves.
                        app.playerEngine.player.pause()
                        app.playerEngine.savePosition()
                        app.webPlayback.switchLine(video, resumeMs)
                        selectedServer = candidate
                        webFallback = candidate
                        autoOpenWebHls = SupjavLine.of(candidate.name) in
                            setOf(SupjavLine.EVS, SupjavLine.LUC)
                        embeddedHlsActive = false
                        webServer = candidate.copy(
                            verifiedPlayerUrl = resolved.verifiedPlayerUrl
                        )
                        showPlayer = false
                        partPickerOpen = false
                        linePickerOpen = false
                        Diagnostics.log("PLAYER_SOURCE_SWITCH_WEB line=" + candidate.name)
                    }
                    ResolveResult.VerificationRequired -> {
                        lineSwitchError =
                            "此线路需要人工验证；当前视频保持不变，请使用其他播放线路。"
                        Diagnostics.log("PLAYER_SOURCE_SWITCH_VERIFICATION " + candidate.name)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lineSwitchError = "切换失败：" + error.javaClass.simpleName + "，原线路保持不变"
                Diagnostics.log("PLAYER_SOURCE_SWITCH_FAILED type=" +
                    error.javaClass.simpleName)
            } finally {
                switchingLine = null
                lineSwitchJob = null
            }
        }
    }

    BackHandler(
        enabled = ((partPickerOpen || linePickerOpen) && activeVideo != null) ||
            showSettings || webServer != null || showPlayer ||
            routes.isNotEmpty() || activeSearchValue.isNotBlank() ||
            tab != SupjavTab.RECOMMEND
    ) {
        Diagnostics.log("NAV_BACK_SYSTEM tab=" + tab.name +
            " detailDepth=" + routes.size +
            " detail=" + (activeVideo?.id ?: "none") +
            " player=" + showPlayer + " web=" + (webServer != null) +
            " lineSheet=" + (linePickerOpen && activeVideo != null) +
            " partSheet=" + (partPickerOpen && activeVideo != null) +
            " settings=" + showSettings)
        back()
    }

    if (inPip) {
        PlayerScreen(app, true, onPip, { showPlayer = false })
        return
    }

    val holder = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                SupjavHeader(tab.title, actions = {
                    if (activeSearchValue.isNotBlank() ||
                        tab == SupjavTab.RECOMMEND) {
                        IconButton(onClick = {
                            // A search owns its own feed revision: updating only
                            // the home revision previously did not refresh it.
                            if (activeSearchValue.isNotBlank()) searchRevision++
                            else refreshKey++
                        }) {
                            Icon(Icons.Outlined.Refresh, "刷新当前列表")
                        }
                    }
                })
            },
            bottomBar = {
                SupjavPrimaryNavigation(tab) { selected ->
                    if (tab != selected) {
                        clearSearch()
                        searchKind = FeedKind.SEARCH
                        tab = selected
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                key(tab) {
                    InlineSearchBar(
                        query = searchInput,
                        onQueryChange = { searchInput = it },
                        kind = searchKind,
                        onKindChange = { searchKind = it },
                        activeValue = activeSearchValue,
                        activeKind = activeSearchKind,
                        recent = recentSearches,
                        onSubmit = ::submitSearch,
                        onClear = ::clearSearch,
                        onRecent = { submitSearch(it, FeedKind.SEARCH) },
                        onClearRecent = {
                            recentSearches = emptyList()
                            scope.launch(Dispatchers.IO) { app.searchStore.clear() }
                        }
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (activeSearchValue.isNotBlank()) {
                        FeedScreen(
                            app, activeSearchKind, activeSearchValue, onOpen,
                            refreshKey = searchRevision
                        )
                    } else {
                        holder.SaveableStateProvider(tab.name) {
                            when (tab) {
                                SupjavTab.RECOMMEND -> RecommendPage(
                                    app, onOpen,
                                    onSearch = {
                                        clearSearch()
                                        targetCategoryKind = null
                                        targetCategoryPath = ""
                                        tab = SupjavTab.CATEGORY
                                    },
                                    onBrowse = { kind, path ->
                                        clearSearch()
                                        targetCategoryKind = kind
                                        targetCategoryPath = path
                                        tab = SupjavTab.CATEGORY
                                    },
                                    refreshKey = refreshKey
                                )
                                SupjavTab.CATEGORY -> CategoryPage(
                                    app = app, onVideo = onOpen,
                                    initialKind = targetCategoryKind,
                                    initialPath = targetCategoryPath,
                                    directoryFilter = searchInput,
                                    backEnabled = routes.isEmpty() && !showPlayer &&
                                        webServer == null && !showSettings &&
                                        !((linePickerOpen || partPickerOpen) && activeVideo != null)
                                )
                                SupjavTab.RANK -> RankPage(app, onOpen)
                                SupjavTab.MINE -> MinePage(
                                    app, onOpen,
                                    onSettings = { showSettings = true },
                                    revision = libraryRevision
                                )
                            }
                        }
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
                Column(
                    Modifier.fillMaxSize().windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                        )
                    )
                ) {
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
                        onShowPartPicker = {
                            lineSwitchError = null
                            partPickerOpen = true
                        },
                        availableParts = VideoParts.available(availableServers),
                        activePart = selectedServer?.let(VideoParts::partOf),
                        onWebFallback = webFallback?.let { candidate ->
                            {
                                val resumeMs = app.playerEngine.player.currentPosition
                                    .coerceAtLeast(0L)
                                app.playerEngine.stop()
                                activeVideo?.let { app.webPlayback.begin(it, resumeMs) }
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
                Column(
                    Modifier.fillMaxSize().then(
                        if (embeddedHlsActive) Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                        else Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                            )
                        )
                    )
                ) {
                    if (!embeddedHlsActive) SupjavHeader(
                        "网页兼容播放器", onBack = back,
                        actions = {
                            if (VideoParts.available(availableServers).size > 1) {
                                TextButton(onClick = {
                                    lineSwitchError = null
                                    partPickerOpen = true
                                }) {
                                    Text(selectedServer?.let(VideoParts::partOf) ?: "CD")
                                }
                            }
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

        if (partPickerOpen && activeVideo != null) {
            SupjavModalSheet(
                "选择视频分段",
                onDismiss = {
                    cancelPendingSourceSwitch()
                    partPickerOpen = false
                    Diagnostics.log("NAV_BACK_DISMISS_PART_SHEET")
                }
            ) {
                val parts = VideoParts.available(availableServers)
                if (parts.size < 2) {
                    Text("当前作品未检测到多个可播放分段。")
                } else {
                    parts.forEach { part ->
                        val source = VideoParts.forPart(availableServers, part, selectedServer)
                        val selected = selectedServer?.let(VideoParts::partOf) == part
                        FilledTonalButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = switchingLine == null && !selected && source != null,
                            onClick = { source?.let(::switchServer) }
                        ) {
                            if (selected) {
                                Icon(Icons.Outlined.CheckCircle, null)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(part + if (selected) " · 正在播放" else "")
                            if (switchingLine == source?.id) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(Modifier.size(18.dp),
                                    strokeWidth = 2.dp)
                            }
                        }
                    }
                }
                lineSwitchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        // One line picker for Media3, the website player and HLS.js.
        // Keep the current playback running until the replacement is ready.
        if (linePickerOpen && activeVideo != null) {
            SupjavModalSheet(
                "切换播放线路",
                onDismiss = {
                    cancelPendingSourceSwitch()
                    linePickerOpen = false
                    Diagnostics.log("NAV_BACK_DISMISS_LINE_SHEET")
                }
            ) {
                if (availableServers.isEmpty()) {
                    Text("当前作品还没有加载到播放线路，请返回详情刷新。")
                } else {
                    val currentPart = selectedServer?.let(VideoParts::partOf)
                    val parts = VideoParts.available(availableServers)
                    val candidates = if (parts.size > 1 && currentPart != null)
                        availableServers.filter { VideoParts.partOf(it) == currentPart }
                    else availableServers
                    candidates.forEach { candidate ->
                        val active = selectedServer?.id == candidate.id &&
                            selectedServer?.link == candidate.link
                        FilledTonalButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = switchingLine == null && !active,
                            onClick = { switchServer(candidate) }
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
                Column(
                    Modifier.fillMaxSize().windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                        )
                    )
                ) {
                    SettingsScreen(app, dark, onDark, onExit = { showSettings = false })
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
