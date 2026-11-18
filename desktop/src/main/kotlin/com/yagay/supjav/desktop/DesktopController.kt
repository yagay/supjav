package com.yagay.supjav.desktop

import androidx.compose.runtime.*
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.*
import com.yagay.supjav.player.PlaybackQueuePolicy
import com.yagay.supjav.player.VideoParts
import com.yagay.supjav.util.Diagnostics
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.*

/**
 * Desktop navigation state and network orchestration.
 *
 * Feed, queue, detail and playback operations have independent jobs and
 * revisions. Obsolete responses never replace the visible page or playback.
 */
class DesktopController(
    val player: DesktopNativePlayer,
    private val gateway: DesktopGateway = LiveDesktopGateway(),
    private val storage: DesktopVideoStore = DesktopVideoStore()
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var feedJob: Job? = null
    private var queueJob: Job? = null
    private var categoryJob: Job? = null
    private var detailJob: Job? = null
    private var switchJob: Job? = null
    private var feedRevision = 0
    private var queueRevision = 0
    private var selectionRevision = 0
    private var categoriesRevision = 0
    private var page = 0
    private var cursor: String? = null
    private var duplicatePages = 0
    private var localList: String? = null
    private data class FeedKey(val kind: FeedKind, val value: String)
    private data class FeedSnapshot(
        val items: List<VideoItem>, val page: Int, val cursor: String?,
        val reachedEnd: Boolean, val duplicatePages: Int
    )
    private val feedCache = LinkedHashMap<FeedKey, FeedSnapshot>(12, .75f, true)
    private val feedScroll = HashMap<String, Pair<Int, Int>>()

    val videos = mutableStateListOf<VideoItem>()
    val playlistItems = mutableStateListOf<VideoItem>()
    val recent = mutableStateListOf<VideoItem>()
    val favorites = mutableStateListOf<VideoItem>()
    var section by mutableStateOf(FeedKind.LATEST)
        private set
    var sectionValue by mutableStateOf("")
        private set
    var searchQuery by mutableStateOf("")
    var showCategories by mutableStateOf(false)
        private set
    var categories by mutableStateOf<List<SupjavSource.BrowseOption>>(emptyList())
        private set

    // activeVideo is the currently inspected detail; playingVideo is the
    // actual video accepted by VLC. Never treat a pending selection as playing.
    var activeVideo by mutableStateOf<VideoItem?>(null)
        private set
    var playingVideo by mutableStateOf<VideoItem?>(null)
        private set
    var pendingVideo by mutableStateOf<VideoItem?>(null)
        private set
    var detail by mutableStateOf<VideoDetail?>(null)
        private set
    var activeServer by mutableStateOf<VideoServer?>(null)
        private set
    var source by mutableStateOf<PlaybackSource?>(null)
        private set
    var browserPageUrl by mutableStateOf<String?>(null)
        private set
    var browserSession by mutableIntStateOf(0)
        private set
    var browserVideo by mutableStateOf<VideoItem?>(null)
        private set

    var status by mutableStateOf("就绪")
        private set
    var showQueue by mutableStateOf(true)
    var showControls by mutableStateOf(true)
    var loading by mutableStateOf(false)
        private set
    var pageLoading by mutableStateOf(false)
        private set
    var switching by mutableStateOf(false)
        private set
    var reachedEnd by mutableStateOf(false)
        private set
    var pageError by mutableStateOf<String?>(null)
        private set
    var localListTitle by mutableStateOf<String?>(null)
        private set

    private var queueKind: FeedKind? = null
    private var queueValue = ""
    private var queuePage = 0
    private var queueCursor: String? = null
    private var queueOriginRevision = -1
    private var queueDuplicatePages = 0
    var queueLoading by mutableStateOf(false)
        private set
    var queueEndReached by mutableStateOf(true)
        private set
    var queueError by mutableStateOf<String?>(null)
        private set
    val canLoadQueueMore get() = queueKind != null && !queueEndReached
    val displayTitle get() = localListTitle
        ?: if (showCategories) "分类目录"
        else if (section == FeedKind.SEARCH) "搜索结果 · $sectionValue"
        else section.title

    val feedScrollKey get() = localList?.let { "local:" + it }
        ?: section.name + ":" + sectionValue
    fun savedScroll(key: String): Pair<Int, Int> = feedScroll[key] ?: (0 to 0)
    fun saveScroll(key: String, index: Int, offset: Int) {
        feedScroll[key] = index.coerceAtLeast(0) to offset.coerceAtLeast(0)
    }

    private fun rememberCurrentFeed() {
        if (localList != null || showCategories || videos.isEmpty()) return
        val key = FeedKey(section, sectionValue)
        feedCache[key] = FeedSnapshot(videos.toList(), page, cursor,
            reachedEnd, duplicatePages)
        while (feedCache.size > 12) {
            val oldest = feedCache.keys.firstOrNull() ?: break
            feedCache.remove(oldest)
        }
    }

    init {
        recent.addAll(storage.read("recent"))
        favorites.addAll(storage.read("favorites"))
        loadFeed()
    }

    fun select(kind: FeedKind, value: String = "") {
        if (kind == FeedKind.SEARCH && value.isBlank()) return
        rememberCurrentFeed()
        section = kind
        sectionValue = value
        localList = null
        localListTitle = null
        showCategories = false
        categoryJob?.cancel()
        categoriesRevision++
        loadFeed()
    }

    fun search() {
        val query = searchQuery.trim()
        if (query.isNotEmpty()) select(FeedKind.SEARCH, query)
    }

    fun browseCategories() {
        rememberCurrentFeed()
        feedRevision++
        feedJob?.cancel()
        pageLoading = false
        loading = false
        localListTitle = null
        showCategories = true
        categoryJob?.cancel()
        val token = ++categoriesRevision
        status = "正在扫描分类、演员和制作商..."
        categoryJob = scope.launch {
            try {
                val items = gateway.categories()
                if (token != categoriesRevision || !showCategories) return@launch
                categories = items.filter {
                    it.kind in setOf(FeedKind.CATEGORY, FeedKind.ACTOR,
                        FeedKind.MAKER, FeedKind.TAG)
                }.distinctBy { it.kind to it.path }
                status = if (categories.isEmpty()) "暂无分类，点击刷新重试"
                    else "已载入 ${categories.size} 个分类入口"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (token == categoriesRevision && showCategories)
                    status = "分类加载失败：" + error.javaClass.simpleName
                Diagnostics.log("DESKTOP_CATEGORY_FAILED type=" + error.javaClass.simpleName)
            }
        }
    }

    fun refreshFeed() {
        feedCache.remove(FeedKey(section, sectionValue))
        loadFeed(force = true)
    }

    fun loadFeed(force: Boolean = false) {
        val token = ++feedRevision
        feedJob?.cancel()
        pageLoading = false
        loading = false
        pageError = null
        val cached = if (force) null else feedCache[FeedKey(section, sectionValue)]
        videos.clear()
        if (cached != null) {
            videos.addAll(cached.items)
            page = cached.page
            cursor = cached.cursor
            reachedEnd = cached.reachedEnd
            duplicatePages = cached.duplicatePages
            status = "已恢复 " + videos.size + " 部缓存作品"
        } else {
            page = 0
            cursor = null
            duplicatePages = 0
            reachedEnd = false
            status = "正在加载作品..."
            feedJob = scope.launch { fetchPage(token) }
        }
    }

    fun loadMore() {
        if (pageLoading || reachedEnd || showCategories || localList != null) return
        val token = feedRevision
        feedJob = scope.launch { fetchPage(token) }
    }

    private suspend fun fetchPage(token: Int) {
        if (token != feedRevision || pageLoading) return
        val kind = section
        val value = sectionValue
        val number = page + 1
        val next = cursor
        pageLoading = true
        loading = videos.isEmpty()
        pageError = null
        try {
            val result = gateway.page(kind, value, number, next)
            if (token != feedRevision || kind != section || value != sectionValue) return
            val known = videos.mapTo(HashSet<String>()) { it.id }
            val added = result.items.filter { known.add(it.id) }
            videos.addAll(added)
            page = number
            cursor = result.nextUrl?.takeUnless { it == next }
            duplicatePages = if (result.items.isNotEmpty() && added.isEmpty())
                duplicatePages + 1 else 0
            reachedEnd = result.items.isEmpty() || duplicatePages >= 2
            if (duplicatePages >= 2) pageError =
                "连续两页没有新作品，已停止重复请求"
            status = "已加载 " + videos.size + " 部作品"
            rememberCurrentFeed()
            // When the user is still browsing the originating feed, keep
            // the player queue in sync. After navigation it remains frozen.
            if (queueOriginRevision == token && queueKind == kind && queueValue == value) {
                val queuedIds = playlistItems.mapTo(HashSet<String>()) { it.id }
                playlistItems.addAll(added.filter { queuedIds.add(it.id) })
                queuePage = number
                queueCursor = cursor
                queueEndReached = reachedEnd
            }
            Diagnostics.log("DESKTOP_PAGE kind=" + kind.name +
                " page=" + number + " added=" + added.size)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (token == feedRevision) {
                pageError = error.message?.take(100) ?: "加载失败，请重试"
                status = "列表加载失败：" + error.javaClass.simpleName
            }
            Diagnostics.log("DESKTOP_PAGE_FAILED type=" + error.javaClass.simpleName)
        } finally {
            if (token == feedRevision) {
                pageLoading = false
                loading = false
            }
        }
    }

    private fun captureQueue() {
        queueJob?.cancel()
        queueRevision++
        queueLoading = false
        queueError = null
        playlistItems.clear()
        playlistItems.addAll(PlaybackQueuePolicy.snapshot(videos.toList()))
        queueKind = if (localList == null) section else null
        queueValue = sectionValue
        queuePage = page
        queueCursor = cursor
        queueOriginRevision = feedRevision
        queueDuplicatePages = 0
        queueEndReached = localList != null || reachedEnd
    }

    private fun addQueueAnchor(video: VideoItem) {
        if (playlistItems.none { it.id == video.id }) playlistItems.add(video)
    }

    fun loadQueueMore() {
        if (queueLoading || queueEndReached || queueKind == null) return
        val token = queueRevision
        queueJob = scope.launch { fetchQueuePage(token) }
    }

    private suspend fun fetchQueuePage(token: Int) {
        if (token != queueRevision || queueLoading || queueEndReached) return
        val kind = queueKind ?: return
        val value = queueValue
        val number = queuePage + 1
        val next = queueCursor
        queueLoading = true
        queueError = null
        try {
            val result = gateway.page(kind, value, number, next)
            if (token != queueRevision) return
            val known = playlistItems.mapTo(HashSet<String>()) { it.id }
            val added = result.items.filter { known.add(it.id) }
            playlistItems.addAll(added)
            queuePage = number
            queueCursor = result.nextUrl?.takeUnless { it == next }
            queueDuplicatePages = if (result.items.isNotEmpty() && added.isEmpty())
                queueDuplicatePages + 1 else 0
            queueEndReached = result.items.isEmpty() || queueDuplicatePages >= 2
            if (queueDuplicatePages >= 2)
                queueError = "连续两页没有新作品，停止加载"
            // If the original feed is still on screen, both views must
            // advance the same cursor. Otherwise the feed could download
            // the queue's page again and make Next appear stuck.
            if (queueOriginRevision == feedRevision && localList == null &&
                !showCategories && section == kind && sectionValue == value) {
                val mainKnown = videos.mapTo(HashSet<String>()) { it.id }
                videos.addAll(added.filter { mainKnown.add(it.id) })
                page = maxOf(page, number)
                cursor = queueCursor
                reachedEnd = queueEndReached
                duplicatePages = queueDuplicatePages
                rememberCurrentFeed()
            }
            Diagnostics.log("DESKTOP_QUEUE_PAGE page=" + number +
                " added=" + added.size)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (token == queueRevision)
                queueError = error.message?.take(100) ?: "加载失败，点击重试"
        } finally {
            if (token == queueRevision) queueLoading = false
        }
    }

    /** Open a detail without interrupting whatever VLC is currently playing. */
    fun open(video: VideoItem, autoplay: Boolean = false) {
        val token = ++selectionRevision
        detailJob?.cancel()
        switchJob?.cancel()
        switching = false
        if (!autoplay) {
            captureQueue()
            addQueueAnchor(video)
            activeVideo = video
            detail = null
            pendingVideo = null
            status = "正在读取作品详情..."
            detailJob = scope.launch {
                try {
                    val loaded = gateway.detail(video)
                    if (token != selectionRevision) return@launch
                    detail = loaded
                    status = if (loaded.servers.isEmpty()) "没有找到可用线路"
                        else "请选择播放线路"
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (token == selectionRevision)
                        status = "详情加载失败：" + error.javaClass.simpleName
                }
            }
        } else {
            addQueueAnchor(video)
            pendingVideo = video
            switching = true
            status = "正在切换到 " + video.title.take(42)
            detailJob = scope.launch {
                try {
                    val loaded = gateway.detail(video)
                    if (token != selectionRevision) return@launch
                    val servers = DesktopPlaybackOrder.nativeFirst(
                        PlaybackQueuePolicy.startingServers(loaded.servers, "auto")
                    )
                    if (servers.isEmpty()) {
                        status = "没有找到可用服务器，原视频保持不变"
                    } else if (!attemptPlay(video, loaded, servers.take(5), token)) {
                        if (token == selectionRevision)
                            status = "新视频线路解析失败，原视频保持不变；可尝试网页播放"
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (token == selectionRevision)
                        status = "切换失败：" + error.javaClass.simpleName
                    Diagnostics.log("DESKTOP_AUTOPLAY_FAILED " + error.javaClass.simpleName)
                } finally {
                    if (token == selectionRevision) {
                        pendingVideo = null
                        switching = false
                    }
                }
            }
        }
    }

    fun openEmbeddedBrowserForCurrent() {
        val video = activeVideo ?: playingVideo ?: return
        val loaded = detail ?: return
        val server = activeServer ?: loaded.servers.firstOrNull() ?: return
        launchBrowser(video, loaded, server, "manual")
    }

    private fun launchBrowser(
        video: VideoItem, loaded: VideoDetail, server: VideoServer, reason: String
    ): Boolean {
        val safe = DesktopBrowserPolicy.siteDetailUrl(video) ?: return false
        player.stop()
        browserPageUrl = safe
        browserVideo = video
        browserSession++
        playingVideo = null
        source = null
        activeVideo = video
        detail = loaded
        activeServer = server
        status = "内置 Edge 网页播放 · " + server.name +
            "（请在页面内点击播放）"
        saveRecent(video)
        Diagnostics.log("DESKTOP_BROWSER_FALLBACK video=" + video.id +
            " server=" + server.name + " reason=" + reason)
        return true
    }

    /** Play the current CD with native-capable lines first (MPV, then VLC). */
    fun playAuto() {
        val video = activeVideo ?: return
        val selectedDetail = detail ?: return
        if (switching || selectedDetail.servers.isEmpty()) return
        val token = ++selectionRevision
        switchJob?.cancel()
        detailJob?.cancel()
        pendingVideo = video
        switching = true
        status = "正在尝试原生播放..."
        switchJob = scope.launch {
            try {
                val firstPart = VideoParts.partOf(
                    activeServer ?: selectedDetail.servers.first()
                )
                val candidates = selectedDetail.servers.filter { candidate ->
                    firstPart == null || VideoParts.partOf(candidate) == firstPart
                }
                val ordered = DesktopPlaybackOrder.nativeFirst(candidates)
                if (!attemptPlay(video, selectedDetail, ordered, token) &&
                    token == selectionRevision) {
                    status = "原生播放失败，可使用内置网页播放器"
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (token == selectionRevision)
                    status = "播放器启动失败：" + error.javaClass.simpleName
            } finally {
                if (token == selectionRevision) {
                    pendingVideo = null
                    switching = false
                }
            }
        }
    }

    /** Manual server switch also resolves before replacing the playing item. */
    fun play(server: VideoServer) {
        val target = activeVideo ?: return
        val selectedDetail = detail ?: return
        if (server !in selectedDetail.servers) return
        val token = ++selectionRevision
        switchJob?.cancel()
        detailJob?.cancel()
        pendingVideo = target
        switching = true
        status = "正在解析 " + server.name
        switchJob = scope.launch {
            try {
                if (!attemptPlay(target, selectedDetail, listOf(server), token))
                    if (token == selectionRevision) status = "线路不可播放，原视频保持不变"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (token == selectionRevision)
                    status = "播放源解析失败：" + error.javaClass.simpleName
            } finally {
                if (token == selectionRevision) {
                    switching = false
                    pendingVideo = null
                }
            }
        }
    }

    private suspend fun attemptPlay(
        video: VideoItem,
        selectedDetail: VideoDetail,
        servers: List<VideoServer>,
        token: Int
    ): Boolean {
        for (server in servers) {
            if (token != selectionRevision) return false
            if (DesktopBrowserPolicy.preferBrowser(server) || !player.available) {
                if (servers.size == 1 || !player.available) {
                    if (launchBrowser(video, selectedDetail, server,
                            if (player.available) "javascript-player" else "native-engine-missing"))
                        return true
                }
                // In automatic playback, allow the native-capable lines to
                // run first rather than jumping to EVS/WebView2 immediately.
                continue
            }
            status = "解析线路 " + server.name
            val result = try {
                gateway.resolve(server)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Diagnostics.log("DESKTOP_RESOLVE_FAILED type=" + error.javaClass.simpleName)
                continue
            }
            if (token != selectionRevision) return false
            if (result is ResolveResult.Playable && player.available) {
                // Remove WebView2 and mount the native AWT video canvas first.
                // Otherwise VLCJ throws IllegalStateException (not displayable).
                browserPageUrl = null
                browserVideo = null
                status = "正在初始化 VLC 视频画面 · " + server.name
                Diagnostics.log("DESKTOP_NATIVE_PREPARE line=" + server.name +
                    " originRequired=" + result.source.includeOrigin)
                val accepted = player.playWhenSurfaceReady(result.source)
                if (token != selectionRevision) {
                    if (accepted) player.stop()
                    return false
                }
                if (accepted) {
                    playingVideo = video
                    activeVideo = video
                    detail = selectedDetail
                    activeServer = server
                    source = result.source
                    status = "VLC 已接收播放源 · " + server.name
                    saveRecent(video)
                    Diagnostics.log("DESKTOP_PLAY_SELECTED id=" + video.id +
                        " server=" + server.name)
                    return true
                }
                Diagnostics.log("DESKTOP_NATIVE_START_FAILED line=" + server.name +
                    " phase=" + player.phase.name)
            }
            Diagnostics.log("DESKTOP_SOURCE_UNAVAILABLE line=" + server.name +
                " reason=" + result.javaClass.simpleName)
            if (servers.size == 1 &&
                launchBrowser(video, selectedDetail, server, "no-native-source"))
                return true
        }
        // Automatic playback: only fall back to WebView after native mirrors
        // have been checked, and do so with the exact selected site's page.
        return servers.firstOrNull()?.let { fallback ->
            launchBrowser(video, selectedDetail, fallback, "native-mirrors-exhausted")
        } ?: false
    }

    fun changePart(part: String) {
        val d = detail ?: return
        val selected = VideoParts.forPart(d.servers, part, activeServer) ?: return
        play(selected)
    }

    fun adjacent(direction: Int) {
        if (direction !in listOf(-1, 1) || switching) return
        val active = playingVideo ?: activeVideo ?: return
        val next = PlaybackQueuePolicy.adjacent(playlistItems.toList(), active.id, direction)
        if (next != null) {
            open(next, autoplay = true)
        } else if (direction == 1 && canLoadQueueMore && !queueLoading) {
            val currentQueue = queueRevision
            scope.launch {
                fetchQueuePage(currentQueue)
                if (queueRevision == currentQueue &&
                    (playingVideo ?: activeVideo)?.id == active.id) {
                    PlaybackQueuePolicy.adjacent(playlistItems.toList(), active.id, 1)
                        ?.let { open(it, autoplay = true) }
                }
            }
        }
    }

    fun showLocalList(key: String) {
        require(key in setOf("recent", "favorites"))
        rememberCurrentFeed()
        feedRevision++
        feedJob?.cancel()
        categoryJob?.cancel()
        categoriesRevision++
        pageLoading = false
        loading = false
        localList = key
        localListTitle = if (key == "favorites") "我的收藏" else "播放历史"
        showCategories = false
        videos.clear()
        videos.addAll(if (key == "favorites") favorites else recent)
        reachedEnd = true
        pageError = null
        status = localListTitle.orEmpty()
    }

    fun isFavorite(id: String) = favorites.any { it.id == id }
    fun toggleFavorite() {
        val video = activeVideo ?: return
        if (isFavorite(video.id)) favorites.removeAll { it.id == video.id }
        else favorites.add(0, video)
        runCatching { storage.write("favorites", favorites.toList()) }
            .onFailure { status = "收藏保存失败：" + it.javaClass.simpleName }
    }

    fun openOfficialPage() {
        val url = (pendingVideo ?: activeVideo ?: playingVideo)?.detailUrl ?: return
        val safe = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host in
                setOf("supjav.com", "www.supjav.com") &&
                uri.path.matches(Regex("/[0-9]+\\.html"))
        }.getOrDefault(false)
        if (safe && Desktop.isDesktopSupported())
            runCatching { Desktop.getDesktop().browse(URI(url)) }
                .onFailure { status = "浏览器打开失败" }
    }

    private fun saveRecent(video: VideoItem) {
        recent.removeAll { it.id == video.id }
        recent.add(0, video)
        while (recent.size > 200) recent.removeAt(recent.lastIndex)
        runCatching { storage.write("recent", recent.toList()) }
            .onFailure { status = "历史记录保存失败：" + it.javaClass.simpleName }
    }

    override fun close() {
        scope.cancel()
        player.close()
    }
}
