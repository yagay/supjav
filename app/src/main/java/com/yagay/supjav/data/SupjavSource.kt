package com.yagay.supjav.data

import com.yagay.supjav.model.*
import com.yagay.supjav.player.VideoParts
import com.yagay.supjav.util.Diagnostics
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SupjavSource {
    companion object {
        const val BASE = "https://supjav.com"
        const val UA = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        // Site redirects must be validated hop by hop before proceeding.
        .followRedirects(false).followSslRedirects(false).build()
    private fun isSiteUrl(url: String): Boolean =
        MediaSourceDetector.origin(url) in setOf(BASE, "https://www.supjav.com")
    private val videoPattern = Regex("/(\\d+)\\.html(?:[?#].*)?$")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    internal fun pageUrl(kind: FeedKind, value: String, page: Int): String {
        require(page >= 1) { "Invalid page" }
        if (kind in setOf(FeedKind.CATEGORY, FeedKind.ACTOR, FeedKind.MAKER, FeedKind.TAG) &&
            value.startsWith("/")) {
            val path = value.substringBefore('?').trimEnd('/')
            require(Regex("^/(?:[A-Za-z]{2}/)?(?:category|maker|cast|tag|genre)/[A-Za-z0-9_%/-]+$").matches(path) &&
                !path.contains("..") && !path.contains("//")) { "Invalid category path" }
            return BASE + path + "/" + if (page == 1) "" else "page/" + page + "/"
        }
        val prefix = if (page <= 1) "/" else "/page/" + page + "/"
        return when (kind) {
            FeedKind.LATEST -> BASE + prefix
            FeedKind.DAY, FeedKind.WEEK, FeedKind.MONTH -> {
                val term = when(kind) { FeedKind.DAY -> "day"; FeedKind.WEEK -> "week"; else -> "month" }
                BASE + (if (page <= 1) "/popular" else "/popular/page/" + page + "/") + "?sort=" + term
            }
            FeedKind.SEARCH -> BASE + prefix + "?s=" + encode(value)
            else -> {
                val path = when(kind) {
                    FeedKind.CATEGORY -> "category"
                    FeedKind.ACTOR -> "cast"
                    FeedKind.MAKER -> "maker"
                    FeedKind.TAG -> "tag"
                    else -> "category"
                }
                BASE + "/" + path + "/" + encode(value) + "/" +
                    (if (page <= 1) "" else "page/" + page + "/")
            }
        }
    }

    fun fetch(url: String, referer: String = BASE + "/"): String {
        require(isSiteUrl(url)) { "Only known Supjav HTTPS site routes are allowed" }
        var target = url
        repeat(5) { hop ->
            if (!isSiteUrl(target)) throw IOException("Unexpected site redirect target")
            val req = Request.Builder().url(target).header("User-Agent", UA)
                .header("Referer", referer).build()
            Diagnostics.log("HTTP_REQUEST host=" + URI(target).host +
                " hop=" + hop + " type=" +
                if (target.contains(".html")) "detail" else "page")
            http.newCall(req).execute().use { response ->
                val status = response.code
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location")
                        ?: throw IOException("Redirect without Location")
                    val resolved = URI(target).resolve(location).toString()
                    if (!isSiteUrl(resolved)) throw IOException("Untrusted site redirect")
                    Diagnostics.log("HTTP_REDIRECT status=" + status +
                        " toHost=" + URI(resolved).host)
                    target = resolved
                    return@use
                }
                Diagnostics.log("HTTP_RESULT status=" + status +
                    " host=" + URI(target).host)
                if (!response.isSuccessful)
                    throw IOException("HTTP " + status + " from " + URI(target).host)
                return response.body?.string()?.take(3_000_000)
                    ?: throw IOException("Empty response")
            }
        }
        throw IOException("Too many site redirects")
    }

    fun isPublicHttps(url: String): Boolean { return try {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return false
        val privateHost = host == "localhost" || host == "::1" || host == "127.0.0.1" ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
        uri.scheme == "https" && !privateHost && uri.userInfo == null
    } catch (_: Exception) { false } }

    data class ListingPage(
        val items: List<VideoItem>,
        val nextUrl: String?,
        /** True when the response contains site pagination controls. */
        val hasPager: Boolean
    )

    /**
     * Follow the site's actual next-page anchor instead of assuming every
     * category/search/list uses the same URL convention. The fallback pageUrl
     * is used only when the website supplies no usable link.
     */
    suspend fun listPage(
        kind: FeedKind, value: String = "", page: Int = 1,
        nextUrl: String? = null
    ): ListingPage = withContext(Dispatchers.IO) {
        val url = nextUrl?.takeIf { isSiteUrl(it) } ?: pageUrl(kind, value, page)
        val html = fetch(url)
        // A 200 response can still contain a browser verification page.
        // Do not mistake that for the end of the site's catalogue.
        if (isAccessChallenge(html)) {
            Diagnostics.log("FEED_CHALLENGE kind=${kind.name} page=$page")
            throw IOException("网站返回访问验证页面，暂时无法读取视频列表")
        }
        val result = parseListingPage(html, url, page)
        Diagnostics.log(
            "FEED_FETCH kind=${kind.name} page=$page items=${result.items.size}" +
                " next=${result.nextUrl != null} pager=${result.hasPager}" +
                " urlPath=${URI(url).path}"
        )
        result
    }

    suspend fun list(kind: FeedKind, value: String = "", page: Int = 1): List<VideoItem> =
        listPage(kind, value, page).items

    private fun isAccessChallenge(html: String): Boolean {
        val sample = html.take(100_000).lowercase()
        return "cf-chl-" in sample ||
            "challenges.cloudflare.com" in sample ||
            ("cloudflare" in sample && ("just a moment" in sample ||
                "verify you are human" in sample || "checking your browser" in sample))
    }

    /**
     * Supjav uses more than one pager template. Prefer a validated next-page
     * link and otherwise let the feed request the site's normal /page/N route.
     * A visible pager without a recognized 'next' link is NOT proof that
     * the feed has reached its last page.
     */
    fun parseListingPage(html: String, base: String = BASE, page: Int = 1): ListingPage {
        val doc = Jsoup.parse(html, base)
        val pager = doc.select(
            ".pagination, .wp-pagenavi, .nav-links, nav.navigation.pagination, " +
                "ul.page-numbers, .paging-navigation, .pager, .page-nav, " +
                ".pagination-container, nav[aria-label*=Pagination]"
        )
        val explicitNext = doc.select(
            "link[rel=next][href], a[rel=next][href], " +
                ".pagination a.next[href], .pagination .next a[href], " +
                "a.nextpostslink[href], a.next.page-numbers[href], " +
                ".nav-links a.next[href], .pager a.next[href], " +
                "a[aria-label=Next][href], a[aria-label='Next page'][href]"
        )
        val pageLinks = pager.select("a[href]").filter { link ->
            val label = link.text().trim()
            val aria = link.attr("aria-label").trim().lowercase()
            val name = link.className().lowercase()
            label.toIntOrNull() == page + 1 ||
                label in setOf("›", "»", "→", "下一页", "下一頁", "Next", "Next →", "Next »") ||
                aria.startsWith("next") || "next" in name.split(' ', '-') ||
                label.lowercase().startsWith("next")
        }
        val next = (explicitNext + pageLinks).asSequence()
            .mapNotNull { link -> link.absUrl("href").takeIf { it.isNotBlank() } }
            .filter(::isSiteUrl)
            .filter { it.substringBefore('#') != base.substringBefore('#') }
            .firstOrNull()
        return ListingPage(
            items = parseList(html, base),
            nextUrl = next,
            hasPager = pager.isNotEmpty() || explicitNext.isNotEmpty()
        )
    }

    fun parseList(html: String, base: String = BASE): List<VideoItem> {
        val doc = Jsoup.parse(html, base)
        // Some pages use one <article> to wrap an entire grid, while others
        // render every item as .post or a linked thumbnail. Enumerate real
        // detail anchors instead of taking only the first link per wrapper.
        val main = doc.selectFirst(".posts, #posts, .post-list, .video-list, main")
            ?: doc.body()
        val selected = main.select("a[href]")
            .ifEmpty { doc.select("a[href]") }
        return selected.mapNotNull { anchor ->
            val url = anchor.absUrl("href").ifBlank {
                resolveUrl(base, anchor.attr("href"))
            }
            if (!isSiteUrl(url)) return@mapNotNull null
            val id = videoPattern.find(url)?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null
            val card = anchor.closest(".post, article, .video-item, .item, li")
                ?: anchor.parent() ?: anchor
            val image = anchor.selectFirst("img") ?: card.selectFirst("img")
            val cover = image?.let {
                val raw = it.attr("data-original").ifBlank {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }.substringBefore("!")
                resolveUrl(base, raw)
            }.orEmpty()
            val title = anchor.attr("title").ifBlank {
                card.selectFirst("h3, .post-title")?.text().orEmpty()
            }.ifBlank { image?.attr("alt").orEmpty() }
                .ifBlank { anchor.text() }.ifBlank { id }
            val published = card.selectFirst(
                "time[datetime], .post-date, .published, .date"
            )?.let { el -> el.attr("datetime").ifBlank { el.text() } }
                .orEmpty().trim().take(48)
            val duration = card.selectFirst(".duration, .video-duration, .length")
                ?.text().orEmpty().trim().take(24)
            VideoItem(id = id, title = title, coverUrl = cover,
                detailUrl = url, code = Regex("[A-Za-z]{2,8}[- ]?\\d{2,8}")
                    .find(title)?.value.orEmpty(),
                publishedAt = published, durationLabel = duration)
        }.distinctBy { it.id }
    }

    suspend fun detail(video: VideoItem): VideoDetail = withContext(Dispatchers.IO) {
        val html = fetch(video.detailUrl)
        val doc = Jsoup.parse(html, video.detailUrl)
        val title = doc.selectFirst(".post-title h1, h1.post-title, .entry-title, h1")?.text()
            ?.takeIf { it.isNotBlank() } ?: video.title
        val coverElement = doc.selectFirst(".post img, .entry-content img, meta[property=og:image]")
        val cover = coverElement?.let {
            if (it.tagName() == "meta") it.attr("content")
            else it.attr("data-original").ifBlank { it.attr("src") }
        }?.let { resolveUrl(video.detailUrl, it) }.orEmpty().ifBlank { video.coverUrl }
        val tags = doc.select("a[href*=/tag/]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val actors = doc.select("a[href*=/category/cast/], a[href*=/cast/]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val makers = doc.select("a[href*=/category/maker/], a[href*=/maker/]").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct()
        val categories = doc.select("a[href*=/category/]")
            .filterNot { it.attr("href").contains("/category/cast/") ||
                it.attr("href").contains("/category/maker/") }
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val servers = parseServers(html, video.detailUrl)
        val relatedRoot = doc.selectFirst(".related, .related-posts, #related")
        val related = relatedRoot?.let { parseList(it.outerHtml(), video.detailUrl) }.orEmpty()
            .filterNot { it.id == video.id }
        val description = doc.selectFirst(".post-content, .entry-content, .post-meta")?.text()
            .orEmpty().take(3000)
        Diagnostics.log("DETAIL id=" + video.id + " servers=" + servers.size)
        val taxonomy = parseBrowseOptions(html, video.detailUrl)
            .map { BrowseLink(it.kind, it.label, it.path) }
        val published = doc.selectFirst("time[datetime], .post-date, .published")
            ?.let { it.attr("datetime").ifBlank { it.text() } }
            .orEmpty().trim().take(48).ifBlank { video.publishedAt }
        val duration = doc.selectFirst(".video-duration, .duration")
            ?.text().orEmpty().trim().take(24).ifBlank { video.durationLabel }
        VideoDetail(video.copy(title = title, coverUrl = cover, tags = tags, actors = actors,
            publishedAt = published, durationLabel = duration),
            description, tags, actors, servers, related, categories, makers, taxonomy)
    }


    /**
     * Extract only recognized player selectors. This stays independent of
     * resolver / WebView, so all playback paths receive the same line list.
     */
    fun parseServers(html: String, detailUrl: String): List<VideoServer> {
        val doc = Jsoup.parse(html, detailUrl)
        // Open-source SUPJAV extractors also observe .btnst a[data-link],
        // which the older app parser did not include.
        val entries = doc.select(
            ".btn-server[data-link], .btnst a[data-link], .btns a[data-link], " +
                "#server-list [data-link], .server-list [data-link], " +
                ".servers [data-link], [data-server][data-link]"
        ).mapNotNull { el ->
            val link = el.attr("data-link").trim()
            if (link.isBlank() || link.length > 2048) return@mapNotNull null
            val label = el.text().trim().ifBlank {
                el.attr("data-server").trim()
            }.ifBlank { "线路" }.take(64)
            val part = inferServerPart(el)
            Triple(label, link, part)
        }.distinctBy { it.second }
        if (entries.isNotEmpty()) return entries.mapIndexed { index, (label, link, part) ->
            VideoServer(index.toString(), label, link, detailUrl, part = part)
        }
        return doc.select(
            "iframe#video[src], #player-wrap iframe[src], iframe#player[src]"
        ).mapNotNull { el ->
            val link = el.absUrl("src")
            link.takeIf { it.startsWith("https://") }
        }.distinct().mapIndexed { index, link ->
            VideoServer(index.toString(), "Player \${index + 1}", link, detailUrl)
        }
    }

    /**
     * Infer CD only from the selected source's own metadata/label or an
     * explicitly labelled neighboring part section. Never infer CD count from
     * player mirrors, page title, or an unrelated navigation link.
     */
    private fun inferServerPart(el: org.jsoup.nodes.Element): String? {
        val own = listOf(el.attr("data-part"), el.attr("data-cd"),
            el.attr("data-disc"), el.attr("title"), el.text())
        own.firstNotNullOfOrNull(VideoParts::normalize)?.let { return it }
        val group = el.closest(
            "[data-part], [data-cd], [data-disc], .cd-group, " +
                ".part-group, .disc-group, .episode-group"
        )
        if (group != null) {
            listOf(group.attr("data-part"), group.attr("data-cd"), group.attr("data-disc"))
                .firstNotNullOfOrNull(VideoParts::normalize)?.let { return it }
            group.children().firstOrNull { child ->
                child.tagName() in setOf("h2", "h3", "h4", "h5", "legend") ||
                    child.classNames().any { it in setOf("part-title", "cd-title", "episode-title") }
            }?.text()?.takeIf { it.length <= 32 }?.let(VideoParts::normalize)
                ?.let { return it }
        }
        // Common site markup: <h4>CD2</h4><div class=btnst>...</div>.
        val parent = el.parent()
        for (candidate in listOfNotNull(parent?.previousElementSibling(),
                parent?.parent()?.previousElementSibling())) {
            val label = candidate.text().trim()
            if (label.length <= 20 && Regex(
                    """(?i)^(?:cd|disc|part)[\s_-]*0?[1-9][0-9]?$"""
                ).matches(label)) {
                VideoParts.normalize(label)?.let { return it }
            }
        }
        return null
    }

    data class BrowseOption(
        val kind: FeedKind,
        val value: String,
        val label: String,
        val path: String = value
    )

    data class DirectoryScan(
        val options: List<BrowseOption>,
        val hasMore: Boolean,
        val sourceCount: Int,
        val failedSourceCount: Int
    )

    /**
     * Collect directory links from the homepage and the site's own taxonomy
     * indexes. A home menu only exposes a small featured subset, so it cannot
     * stand in for the actor/maker/tag directories.
     *
     * The index URLs are discovery entry points, not fabricated category items.
     * Each item is admitted only after observing an actual same-site anchor.
     * Scan depth is expandable by the directory UI for large indexes.
     */
    suspend fun browseDirectory(maxPages: Int = 2): DirectoryScan =
        withContext(Dispatchers.IO) {
            require(maxPages in 1..50)
            val roots = listOf(
                "/", "/category/", "/tag/", "/cast/", "/maker/",
                "/category/cast/", "/category/maker/"
            )
            data class SourceScan(
                val items: List<BrowseOption>,
                val more: Boolean,
                val success: Boolean
            )
            val scans = coroutineScope {
                val throttle = Semaphore(3)
                roots.map { root ->
                    async {
                        throttle.withPermit {
                            val found = LinkedHashMap<String, BrowseOption>()
                            val visited = HashSet<String>()
                            var cursor: String? = BASE + root
                            var pages = 0
                            var success = false
                            while (cursor != null && pages < (if (root == "/") 1 else maxPages)) {
                                val url = cursor
                                if (!visited.add(url)) break
                                try {
                                    val html = fetch(url)
                                    if (isAccessChallenge(html)) {
                                        throw IOException("Access verification page")
                                    }
                                    success = true
                                    parseBrowseOptions(html, url).forEach { found[it.path] = it }
                                    pages++
                                    val candidate = if (root == "/") null
                                        else parseListingPage(html, url, pages).nextUrl
                                    // Follow the index's pager only; never jump from
                                    // the taxonomy index to an unrelated video feed.
                                    cursor = candidate?.takeIf { next ->
                                        val parent = root.trimEnd('/')
                                        val nextPath = URI(next).path.trimEnd('/')
                                        val query = URI(next).rawQuery.orEmpty()
                                        nextPath.startsWith("$parent/page/") ||
                                            (nextPath == parent && Regex("(?i)(?:^|&)paged=\\d+").containsMatchIn(query))
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Diagnostics.log("DIRECTORY_SOURCE_FAILED root=$root page=$pages reason=${e.javaClass.simpleName}")
                                    cursor = null
                                }
                            }
                            Diagnostics.log("DIRECTORY_SOURCE root=$root count=${found.size} pages=$pages more=${cursor != null}")
                            SourceScan(found.values.toList(), cursor != null, success)
                        }
                    }
                }.awaitAll()
            }
            if (scans.none { it.success }) {
                throw IOException("网站目录页面均无法访问，已保留本地分类缓存")
            }
            val merged = scans.flatMap { it.items }.distinctBy { it.path }
            Diagnostics.log("DIRECTORY_SYNC items=${merged.size} successes=${scans.count { it.success }} failures=${scans.count { !it.success }} more=${scans.any { it.more }}")
            DirectoryScan(
                merged, scans.any { it.more },
                scans.count { it.success }, scans.count { !it.success }
            )
        }

    suspend fun browseOptions(): List<BrowseOption> = browseDirectory().options

    fun parseBrowseOptions(html: String, base: String = BASE): List<BrowseOption> {
        val doc = Jsoup.parse(html, base)
        // Directory items may be in grids, list bodies or pagination pages,
        // not necessarily inside nav/header/sidebars.
        return doc.select("a[href]").mapNotNull { element ->
            val uri = runCatching { URI(element.absUrl("href")) }.getOrNull()
                ?: return@mapNotNull null
            if (uri.scheme != "https" ||
                uri.host !in setOf("supjav.com", "www.supjav.com")) return@mapNotNull null
            val original = uri.rawPath?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val segments = original.trim('/').split('/').filter { it.isNotBlank() }
            val off = if (segments.firstOrNull() in setOf("zh", "en", "ja")) 1 else 0
            val root = segments.getOrNull(off)?.lowercase()
            val type: FeedKind
            val valueIndex: Int
            when {
                root == "category" && segments.getOrNull(off + 1) == "cast" -> {
                    type = FeedKind.ACTOR; valueIndex = off + 2
                }
                root == "category" && segments.getOrNull(off + 1) == "maker" -> {
                    type = FeedKind.MAKER; valueIndex = off + 2
                }
                root == "category" || root == "genre" -> {
                    type = FeedKind.CATEGORY; valueIndex = off + 1
                }
                root == "cast" -> { type = FeedKind.ACTOR; valueIndex = off + 1 }
                root == "maker" -> { type = FeedKind.MAKER; valueIndex = off + 1 }
                root == "tag" -> { type = FeedKind.TAG; valueIndex = off + 1 }
                else -> return@mapNotNull null
            }
            if (segments.size != valueIndex + 1) return@mapNotNull null
            val slug = segments[valueIndex]
            if (slug == "page" || slug == "." || slug == "..") return@mapNotNull null
            val label = element.text().trim().take(45).ifBlank {
                runCatching { URLDecoder.decode(slug, "UTF-8") }.getOrDefault(slug)
            }
            BrowseOption(type, slug, label, original)
        }.distinctBy { it.path }
    }

    fun resolveUrl(base: String, raw: String): String = try {
        if (raw.isBlank() || raw.startsWith("data:")) "" else URI(base).resolve(raw.trim()).toString()
    } catch (_: Exception) { "" }
}
