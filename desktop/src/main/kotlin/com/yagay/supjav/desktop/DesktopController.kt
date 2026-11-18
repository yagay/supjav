package com.yagay.supjav.desktop

import androidx.compose.runtime.*
import com.yagay.supjav.data.PlaybackResolver
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.*
import com.yagay.supjav.player.PlaybackQueuePolicy
import com.yagay.supjav.player.VideoParts
import com.yagay.supjav.util.Diagnostics
import java.awt.Desktop
import java.net.URI
import java.util.prefs.Preferences
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class DesktopController(val player: VlcBackend) : AutoCloseable {
    private val site = SupjavSource()
    private val resolver = PlaybackResolver(site)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = Preferences.userNodeForPackage(DesktopController::class.java)
    val videos = mutableStateListOf<VideoItem>()
    val recent = mutableStateListOf<VideoItem>()
    val favorites = mutableStateListOf<VideoItem>()
    var section by mutableStateOf(FeedKind.LATEST)
    var sectionValue by mutableStateOf("")
    var searchQuery by mutableStateOf("")
    var showCategories by mutableStateOf(false)
    var categories by mutableStateOf<List<SupjavSource.BrowseOption>>(emptyList())
    var activeVideo by mutableStateOf<VideoItem?>(null)
    var detail by mutableStateOf<VideoDetail?>(null)
    var activeServer by mutableStateOf<VideoServer?>(null)
    var source by mutableStateOf<PlaybackSource?>(null)
    var status by mutableStateOf("就绪")
    var showQueue by mutableStateOf(true)
    var showControls by mutableStateOf(true)
    var loading by mutableStateOf(false)
    var pageLoading by mutableStateOf(false)
    var switching by mutableStateOf(false)
    var reachedEnd by mutableStateOf(false)
    private var page = 0
    private var nextUrl: String? = null
    private var revision = 0

    init {
        recent.addAll(readVideos("recent"))
        favorites.addAll(readVideos("favorites"))
        loadFeed()
    }

    fun select(kind: FeedKind, value: String = "") {
        if (kind == FeedKind.SEARCH && value.isBlank()) return
        section = kind
        sectionValue = value
        showCategories = false
        loadFeed()
    }

    fun search() {
        if (searchQuery.isNotBlank()) select(FeedKind.SEARCH, searchQuery.trim())
    }

    fun browseCategories() {
        showCategories = true
        status = "正在扫描分类..."
        scope.launch {
            try {
                categories = site.browseDirectory(maxPages = 1).options
                    .filter { it.kind == FeedKind.CATEGORY }.distinctBy { it.path }
                status = if (categories.isEmpty()) "暂时没有分类" else "分类已加载"
            } catch (error: Exception) {
                status = "分类加载失败：" + error.javaClass.simpleName
                Diagnostics.log("DESKTOP_CATEGORY_FAILED " + error.javaClass.simpleName)
            }
        }
    }

    fun loadFeed() {
        revision++
        page = 0
        nextUrl = null
        reachedEnd = false
        videos.clear()
        status = "正在加载..."
        val token = revision
        scope.launch { fetchPage(token) }
    }

    fun loadMore() {
        if (pageLoading || reachedEnd || showCategories) return
        val token = revision
        scope.launch { fetchPage(token) }
    }

    private suspend fun fetchPage(token: Int) {
        if (pageLoading || token != revision) return
        pageLoading = true
        loading = videos.isEmpty()
        try {
            val result = site.listPage(section, sectionValue, page + 1, nextUrl)
            if (token != revision) return
            val known = videos.mapTo(HashSet<String>()) { it.id }
            videos.addAll(result.items.filter { known.add(it.id) })
            page += 1
            nextUrl = result.nextUrl
            reachedEnd = result.items.isEmpty()
            status = "已加载 " + videos.size + " 部"
            Diagnostics.log("DESKTOP_PAGE count=" + result.items.size + " page=" + page)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (token == revision)
                status = "加载失败：" + (error.message?.take(80) ?: error.javaClass.simpleName)
            Diagnostics.log("DESKTOP_PAGE_FAILED " + error.javaClass.simpleName)
        } finally {
            pageLoading = false
            loading = false
        }
    }

    fun open(video: VideoItem, autoplay: Boolean = false) {
        activeVideo = video
        detail = null
        activeServer = null
        status = "正在读取作品详情..."
        scope.launch {
            try {
                val result = site.detail(video)
                if (activeVideo?.id != video.id) return@launch
                detail = result
                status = if (result.servers.isEmpty()) "没有可用播放线路" else "选择播放线路"
                if (autoplay && result.servers.isNotEmpty()) {
                    val first = PlaybackQueuePolicy.startingServers(result.servers, "auto")
                    first.firstOrNull()?.let(::play)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (activeVideo?.id == video.id)
                    status = "详情加载失败：" + error.javaClass.simpleName
            }
        }
    }

    fun play(server: VideoServer) {
        val current = activeVideo ?: return
        if (switching) return
        status = "正在解析 " + server.name
        switching = true
        scope.launch {
            try {
                when (val result = resolver.resolve(server)) {
                    is ResolveResult.Playable -> {
                        if (activeVideo?.id != current.id) return@launch
                        if (player.play(result.source)) {
                            activeServer = server
                            source = result.source
                            status = "正在播放 · " + server.name
                            saveRecent(current)
                        } else status = "未检测到 VLC 或本线路启动失败，请尝试官网播放"
                    }
                    is ResolveResult.Unavailable ->
                        status = "该线路需要网页兼容播放：" + result.reason.take(70)
                    ResolveResult.VerificationRequired ->
                        status = "该线路需要网站验证，请使用官网播放"
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                status = "解析失败：" + error.javaClass.simpleName
                Diagnostics.log("DESKTOP_RESOLVE_FAILED " + error.javaClass.simpleName)
            } finally {
                switching = false
            }
        }
    }

    fun changePart(part: String) {
        val servers = detail?.servers ?: return
        VideoParts.forPart(servers, part, activeServer)?.let(::play)
    }

    fun adjacent(direction: Int) {
        val id = activeVideo?.id ?: return
        PlaybackQueuePolicy.adjacent(videos.toList(), id, direction)?.let {
            open(it, autoplay = true)
            return
        }
        if (direction == 1 && !reachedEnd && !pageLoading) {
            scope.launch {
                val token = revision
                fetchPage(token)
                if (token == revision && activeVideo?.id == id) {
                    PlaybackQueuePolicy.adjacent(videos.toList(), id, 1)
                        ?.let { open(it, autoplay = true) }
                }
            }
        }
    }

    fun showLocalList(key: String) {
        revision++
        pageLoading = false
        videos.clear()
        videos.addAll(if (key == "favorites") favorites else recent)
        reachedEnd = true
        showCategories = false
        status = if (key == "favorites") "收藏" else "历史"
    }
    fun isFavorite(id: String) = favorites.any { it.id == id }
    fun toggleFavorite() {
        val video = activeVideo ?: return
        if (isFavorite(video.id)) favorites.removeAll { it.id == video.id }
        else favorites.add(0, video)
        writeVideos("favorites", favorites)
    }
    fun openOfficialPage() {
        val url = activeVideo?.detailUrl ?: return
        val safe = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host in
                setOf("supjav.com", "www.supjav.com") &&
                uri.path.matches(Regex("/[0-9]+\\.html"))
        }.getOrDefault(false)
        if (safe && Desktop.isDesktopSupported()) {
            runCatching { Desktop.getDesktop().browse(URI(url)) }
                .onFailure { status = "打开浏览器失败" }
        }
    }
    private fun saveRecent(video: VideoItem) {
        recent.removeAll { it.id == video.id }
        recent.add(0, video)
        while (recent.size > 200) recent.removeAt(recent.lastIndex)
        writeVideos("recent", recent)
    }
    private fun writeVideos(key: String, items: List<VideoItem>) {
        val array = JSONArray()
        items.take(200).forEach { v ->
            array.put(JSONObject().apply {
                put("id", v.id); put("title", v.title)
                put("cover", v.coverUrl); put("url", v.detailUrl); put("code", v.code)
            })
        }
        prefs.put(key, array.toString())
    }
    private fun readVideos(key: String) = runCatching {
        val array = JSONArray(prefs.get(key, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val id = o.optString("id")
            val url = o.optString("url")
            if (id.isBlank() || url.isBlank()) null else VideoItem(
                id, o.optString("title"), o.optString("cover"), url,
                o.optString("code")
            )
        }
    }.getOrDefault(emptyList())

    override fun close() {
        scope.cancel()
        player.close()
    }
}
