package com.yagay.supjav.data

import com.yagay.supjav.model.*
import com.yagay.supjav.util.Diagnostics
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Dedicated MissAV catalogue adapter. No Supjav selectors, server tokens or
 * untrusted mirror redirects are reused. Parsing is static and bounded.
 */
internal class MissavSource {
    companion object {
        const val BASE = "https://missav.ws"
        const val UA = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).followRedirects(false)
        .followSslRedirects(false).build()
    private val hosts = setOf("missav.ws", "www.missav.ws")
    private fun site(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.host?.lowercase() in hosts &&
            u.userInfo == null && u.port == -1
    } catch (_: Exception) { false }
    private fun absolute(base: String, raw: String): String =
        runCatching { URI(base).resolve(raw.trim()).toString() }.getOrDefault("")
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun slug(raw: String): String =
        raw.trim().trim('/').takeIf { it.matches(Regex("[A-Za-z0-9_%.-]{1,120}")) }
            ?: throw IllegalArgumentException("Invalid MissAV category")
    private fun route(kind: FeedKind): String = when (kind) {
        FeedKind.CATEGORY -> "genres"
        FeedKind.TAG -> "tags"
        FeedKind.ACTOR -> "actresses"
        FeedKind.MAKER -> "makers"
        else -> "genres"
    }
    private fun validTaxonomyPath(path: String): Boolean =
        Regex("^/(?:dm[0-9]+/)?(?:(?:en|ja|cn|ko|zh|tw)/)?(?:genres|genre|tags|tag|actresses|actress|actors|actor|makers|maker|series)/[A-Za-z0-9_%.-]+$").matches(path)

    private fun validMenuPath(path: String): Boolean =
        Regex("^/(?:dm[0-9]+/)?(?:(?:en|ja|cn|ko|zh|tw)/)?[A-Za-z0-9_-]{2,100}/?$").matches(path)

    /**
     * MissAV top navigation uses # for group headings. Each group's children
     * have real links and are the actual navigable categories.
     */
    fun parseMenuOptions(html: String, base: String = BASE): List<SupjavSource.BrowseOption> {
        val groups = setOf("Watch JAV", "Amateur", "Uncensored", "Asia AV")
        val doc = Jsoup.parse(html, base)
        val result = LinkedHashMap<String, SupjavSource.BrowseOption>()
        var group: String? = null
        for (anchor in doc.select("a[href]")) {
            val label = anchor.text().trim().replace(Regex("\\s+"), " ").take(80)
            if (label in groups && anchor.attr("href").trim().endsWith("#")) {
                group = label
                continue
            }
            if (group == null) continue
            if (label in setOf("My collection", "More sites", "Menu")) break
            val link = anchor.absUrl("href")
            if (!site(link) || label.isBlank()) continue
            val uri = URI(link)
            val path = uri.path.trimEnd('/')
            if (!validMenuPath(path) && !validTaxonomyPath(path)) continue
            // Keep verified website routes: do not construct /dmN prefixes.
            val option = SupjavSource.BrowseOption(FeedKind.CATEGORY,
                "menu:" + group, label, path)
            result.putIfAbsent(group + "|" + path, option)
        }
        return result.values.toList()
    }

    fun pageUrl(kind: FeedKind, value: String, page: Int): String {
        require(page in 1..10000)
        val path = value.substringBefore('?')
        val args = value.substringAfter('?', "")
        val query = args.split('&').filter { it.isNotBlank() }.map { parameter ->
            val pair = parameter.split('=', limit = 2)
            require(pair.size == 2 && pair[0] in setOf("filters", "sort") &&
                pair[1].matches(Regex("[a-zA-Z0-9_-]{1,50}")))
            parameter
        }.distinct()
        val base = when(kind) {
            FeedKind.LATEST -> "$BASE/en/new"
            FeedKind.DAY -> "$BASE/en/ranking/daily"
            FeedKind.WEEK -> "$BASE/en/ranking/weekly"
            FeedKind.MONTH -> "$BASE/en/ranking/monthly"
            FeedKind.SEARCH -> "$BASE/en/search/" + encode(value)
            else -> if (path.startsWith("/")) {
                require(validTaxonomyPath(path.trimEnd('/')) ||
                    validMenuPath(path.trimEnd('/')))
                BASE + path.trimEnd('/')
            } else "$BASE/en/" + route(kind) + "/" + slug(path)
        }
        val all = query + if (page == 1) emptyList() else listOf("page=$page")
        return base + if (all.isEmpty()) "" else "?" + all.joinToString("&")
    }
    fun fetch(url: String, referer: String = "$BASE/"): String {
        require(site(url)) { "Only MissAV HTTPS pages are allowed" }
        var current = url
        repeat(5) {
            val req = Request.Builder().url(current).header("User-Agent", UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", referer.takeIf(::site) ?: "$BASE/").build()
            http.newCall(req).execute().use { response ->
                if (response.code in setOf(301,302,303,307,308)) {
                    val next = absolute(current, response.header("Location") ?: "")
                    if (!site(next)) throw IOException("Untrusted MissAV redirect")
                    current = next
                } else {
                    if (!response.isSuccessful) throw IOException("MissAV HTTP ${response.code}")
                    val html = response.body?.string()?.take(3_000_000)
                        ?: throw IOException("Empty MissAV response")
                    if (html.contains("cf-chl-", true) ||
                        html.contains("challenges.cloudflare.com", true))
                        throw IOException("MissAV requires browser verification")
                    return html
                }
            }
        }
        throw IOException("Too many MissAV redirects")
    }
    private fun videoUrl(url: String): Boolean {
        if (!site(url)) return false
        val parts = URI(url).path.trim('/').split('/')
            .dropWhile { it.matches(Regex("dm[0-9]+")) }
            .dropWhile { it in setOf("en", "ja", "cn", "ko", "zh", "tw") }
        val name = parts.singleOrNull() ?: return false
        return name.matches(Regex("(?i)[a-z0-9]+(?:-[a-z0-9]+)+")) &&
            name.any { it.isDigit() }
    }
    fun parseList(html: String, base: String = BASE): List<VideoItem> {
        val doc = Jsoup.parse(html, base)
        return doc.select(".thumbnail, .video-item").mapNotNull { tile ->
            // Cards contain multiple anchors (image, title and ads).
            val anchor = tile.select("a[href]").firstOrNull { videoUrl(it.absUrl("href")) }
                ?: return@mapNotNull null
            val link = anchor.absUrl("href")
            val image = tile.selectFirst("img")
            val coverRaw = image?.attr("data-src").orEmpty().ifBlank {
                image?.attr("data-original").orEmpty().ifBlank { image?.attr("src").orEmpty() }
            }
            val title = tile.select("a[href]").firstOrNull {
                it.absUrl("href") == link && it.text().isNotBlank()
            }?.text().orEmpty().ifBlank { anchor.attr("title") }
                .ifBlank { image?.attr("alt").orEmpty() }
                .ifBlank { tile.text() }.trim().take(250)
            val id = URI(link).path.trimEnd('/').substringAfterLast('/')
            VideoItem(id = id, title = title.ifBlank { id },
                coverUrl = absolute(base, coverRaw), detailUrl = link,
                code = id.uppercase())
        }.distinctBy { it.detailUrl }
    }
    fun parseListingPage(html: String, base: String = BASE, page: Int = 1): SupjavSource.ListingPage {
        val doc = Jsoup.parse(html, base)
        val next = doc.select("a[rel=next][href], a.next[href], .pagination a[href], nav a[href]")
            .mapNotNull { a ->
                val label = a.text().trim().lowercase()
                if (a.attr("rel") == "next" || label in setOf("next", "›", "»", (page+1).toString())) {
                    a.absUrl("href").takeIf(::site)
                } else null
            }.firstOrNull()
        return SupjavSource.ListingPage(parseList(html, base), next,
            doc.select(".pagination, nav[aria-label*=Pagination], a[rel=next]").isNotEmpty())
    }
    suspend fun listPage(kind: FeedKind, value: String, page: Int, nextUrl: String?): SupjavSource.ListingPage =
        withContext(Dispatchers.IO) {
            val url = nextUrl?.takeIf(::site) ?: pageUrl(kind, value, page)
            val listing = parseListingPage(fetch(url), url, page)
            Diagnostics.log("MISSAV_FEED kind=${kind.name} page=$page count=${listing.items.size}")
            listing
        }
    fun parseBrowseOptions(html: String, base: String = BASE): List<SupjavSource.BrowseOption> {
        val doc = Jsoup.parse(html, base)
        return doc.select("a[href]").mapNotNull { anchor ->
            val link = anchor.absUrl("href")
            if (!site(link)) return@mapNotNull null
            val path = URI(link).path.trimEnd('/')
            if (!validTaxonomyPath(path)) return@mapNotNull null
            val segments = path.trim('/').split('/')
            val group = segments[segments.size - 2]
            val kind = when (group) {
                "actresses", "actress", "actors", "actor" -> FeedKind.ACTOR
                "makers", "maker" -> FeedKind.MAKER
                "tags", "tag" -> FeedKind.TAG
                else -> FeedKind.CATEGORY
            }
            val slug = segments.last()
            val label = anchor.text().trim().ifBlank {
                runCatching { java.net.URLDecoder.decode(slug, "UTF-8") }
                    .getOrDefault(slug).replace('-', ' ')
            }.take(80)
            SupjavSource.BrowseOption(kind, slug, label, path)
        }.distinctBy { it.path }
    }
    private fun nextDirectoryPage(html: String, current: String, page: Int): String? {
        val doc = Jsoup.parse(html, current)
        val currentPath = URI(current).path.trimEnd('/')
        val parentPath = currentPath.replace(Regex("/page/[0-9]+$"), "")
        return doc.select("link[rel=next][href], a[rel=next][href], .pagination a[href], a.next[href], a[aria-label=Next][href]")
            .mapNotNull { a ->
                val label = a.text().trim().lowercase()
                val next = a.absUrl("href")
                val path = runCatching { URI(next).path.trimEnd('/') }.getOrNull()
                if (site(next) && path != null &&
                    (path == parentPath || path.startsWith(parentPath + "/page/")) &&
                    (a.attr("rel") == "next" || label in setOf("next", "›", "»", (page + 1).toString()) ||
                        a.hasClass("next") || a.attr("aria-label").contains("next", true)) &&
                    next != current) next else null
            }.firstOrNull()
    }
    suspend fun browseDirectory(depth: Int): SupjavSource.DirectoryScan = withContext(Dispatchers.IO) {
        require(depth in 1..50)
        val roots = listOf("/", "/genres", "/en/genres", "/tags", "/en/tags",
            "/actresses", "/en/actresses", "/makers", "/en/makers",
            "/series", "/en/series")
        val found = LinkedHashMap<String, SupjavSource.BrowseOption>()
        val menus = LinkedHashMap<String, SupjavSource.BrowseOption>()
        val seen = HashSet<String>()
        var successes = 0
        var failures = 0
        var hasMore = false
        for (root in roots) {
            var current: String? = BASE + root
            var page = 1
            var rootSuccess = false
            while (current != null && page <= depth) {
                val url = current
                if (!seen.add(url)) break
                try {
                    val html = fetch(url)
                    rootSuccess = true
                    if (root == "/") parseMenuOptions(html, url).forEach {
                        menus[it.value + "|" + it.path] = it
                    }
                    parseBrowseOptions(html, url).forEach { found[it.path] = it }
                    val next = nextDirectoryPage(html, url, page)
                    if (next != null && page == depth) hasMore = true
                    current = next
                    page++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diagnostics.log("MISSAV_DIRECTORY_FAILED root=" + root +
                        " page=" + page + " reason=" + e.javaClass.simpleName)
                    current = null
                }
            }
            if (rootSuccess) successes++ else failures++
        }
        if (successes == 0) throw IOException("MissAV directory unavailable")
        Diagnostics.log("MISSAV_DIRECTORY entries=" + found.size +
            " sources=" + successes + " failures=" + failures + " more=" + hasMore)
        SupjavSource.DirectoryScan(menus.values.toList() + found.values.toList(), hasMore, successes, failures)
    }
    fun parseServers(html: String, detailUrl: String): List<VideoServer> {
        val doc = Jsoup.parse(html, detailUrl)
        val direct = linkedSetOf<String>()
        doc.select("video[src], video source[src], source[type*=video][src]").forEach {
            val url = it.absUrl("src")
            if (MediaSourceDetector.fromMediaUrl(url, detailUrl) != null) direct.add(url)
        }
        // Actual player declarations, including bounded unpacked player scripts.
        // No URLs are fabricated from thumbnail identifiers.
        val expressions = Regex("""(?i)(?:file|source|src|hlsUrl|source1280|source842)\s*[:=]\s*["'](https://[^"'<> ]{8,2000}?(?:\.m3u8|\.mp4)(?:\?[^"'<> ]*)?)["']""")
        val scripts = doc.select("script:not([src])").map {
            it.data().ifBlank { it.html() }
        }.take(100)
        val candidates = listOf(html.take(500_000)) + scripts +
            scripts.mapNotNull { SupjavServerAdapters.unpackPacker(it) }
        // Raw triple-quoted Kotlin regex: use single backslashes. The old
        // expression used \\s / \\. and silently missed HLS URLs in scripts.
        val playerUrl = Regex(
            """https://[^\s"'<>\\]{8,2000}?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        for (candidate in candidates.take(150)) {
            val decoded = candidate.replace("\\/", "/")
            for (match in expressions.findAll(decoded.take(500_000)).take(20)) {
                val url = match.groupValues[1]
                if (MediaSourceDetector.fromMediaUrl(url, detailUrl) != null) direct.add(url)
            }
            // Packed players may use unnamed URL constants instead of file/source.
            // Only scan scripts declaring a video player; never scrape media-looking
            // URLs from the complete page or untrusted advertising markup.
            if (candidate !== candidates.first() &&
                listOf("surrit", "jwplayer", "videojs", "player.setup", "source1280", "source842")
                    .any { decoded.contains(it, ignoreCase = true) }) {
                for (match in playerUrl.findAll(decoded.take(200_000)).take(12)) {
                    val url = match.value
                    if (MediaSourceDetector.fromMediaUrl(url, detailUrl) != null) direct.add(url)
                }
            }
        }
        Diagnostics.log("MISSAV_MEDIA_CANDIDATES count=" + direct.size)
        if (direct.isNotEmpty()) return direct.sortedWith(
            compareByDescending<String> { it.contains("1080") || it.contains("1280") }
                .thenByDescending { it.contains("720") || it.contains("842") }
        ).mapIndexed { i, url ->
            VideoServer(i.toString(), "MISSAV ${i + 1}", url, detailUrl)
        }
        return doc.select("iframe[src]").mapNotNull { element ->
            val link = element.absUrl("src")
            if (MediaSourceDetector.origin(link) == null ||
                Regex("(?i)advert|banner|popunder|tracker|doubleclick|googleads")
                    .containsMatchIn(link)) null else link
        }.distinct().take(3).mapIndexed { i, url ->
            VideoServer(i.toString(), "MISSAV Player ${i + 1}", url, detailUrl)
        }.ifEmpty {
            // Web-only script player: preserve a legitimate playback entry.
            listOf(VideoServer("web", "MISSAV Web", detailUrl, detailUrl))
        }
    }
    suspend fun detail(video: VideoItem): VideoDetail = withContext(Dispatchers.IO) {
        require(site(video.detailUrl))
        val html = fetch(video.detailUrl)
        val doc = Jsoup.parse(html, video.detailUrl)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() } ?: video.title
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { absolute(video.detailUrl, it) }.orEmpty().ifBlank { video.coverUrl }
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            .orEmpty().take(3000)
        val taxonomy = parseBrowseOptions(html, video.detailUrl).map {
            BrowseLink(it.kind, it.label, it.path)
        }
        val tags = taxonomy.filter { it.kind == FeedKind.CATEGORY || it.kind == FeedKind.TAG }
            .map { it.label }.distinct()
        val actors = taxonomy.filter { it.kind == FeedKind.ACTOR }.map { it.label }.distinct()
        val makers = taxonomy.filter { it.kind == FeedKind.MAKER }.map { it.label }.distinct()
        val relatedRoot = doc.selectFirst(".related, .related-videos, #related")
        val related = relatedRoot?.let { parseList(it.outerHtml(), video.detailUrl) }
            .orEmpty().filterNot { it.id == video.id }
        VideoDetail(video.copy(title = title, coverUrl = cover, tags = tags, actors = actors),
            description, tags, actors, parseServers(html, video.detailUrl),
            related, tags, makers, taxonomy)
    }
}
