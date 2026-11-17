package com.yagay.supjav.data

import com.yagay.supjav.model.*
import com.yagay.supjav.util.Diagnostics
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
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
        .readTimeout(25, TimeUnit.SECONDS).followRedirects(true).build()
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
        require(isPublicHttps(url)) { "Only public HTTPS hosts are allowed" }
        val req = Request.Builder().url(url).header("User-Agent", UA)
            .header("Referer", referer).build()
        Diagnostics.log("HTTP_REQUEST host=" + URI(url).host + " type=" +
            if (url.contains(".html")) "detail" else "page-or-player")
        http.newCall(req).execute().use { response ->
            Diagnostics.log("HTTP_RESULT status=" + response.code + " host=" + URI(url).host)
            if (!response.isSuccessful) throw IOException("HTTP " + response.code + " from " + URI(url).host)
            return response.body?.string()?.take(3_000_000) ?: throw IOException("Empty response")
        }
    }

    fun isPublicHttps(url: String): Boolean { return try {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return false
        val privateHost = host == "localhost" || host == "::1" || host == "127.0.0.1" ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
        uri.scheme == "https" && !privateHost && uri.userInfo == null
    } catch (_: Exception) { false } }

    suspend fun list(kind: FeedKind, value: String = "", page: Int = 1): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val url = pageUrl(kind, value, page)
            parseList(fetch(url), url)
        }

    fun parseList(html: String, base: String = BASE): List<VideoItem> {
        val doc = Jsoup.parse(html, base)
        val cards = doc.select(".posts .post, .post, article")
        val elements: List<Element> = if (cards.isEmpty()) doc.select("a[href$=.html]") else cards
        return elements.mapNotNull { card ->
            val anchor = if (card.tagName() == "a") card
                else card.selectFirst("a.img[href], h3 a[href], .post-title a[href], a[href$=.html]")
            anchor ?: return@mapNotNull null
            val url = anchor.absUrl("href").ifBlank { resolveUrl(base, anchor.attr("href")) }
            val id = videoPattern.find(url)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val image = card.selectFirst("img") ?: anchor.selectFirst("img")
            val cover = image?.let {
                val raw = it.attr("data-original").ifBlank {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }.substringBefore("!")
                resolveUrl(base, raw)
            }.orEmpty()
            val title = anchor.attr("title").ifBlank {
                card.selectFirst("h3, .post-title")?.text().orEmpty()
            }.ifBlank { image?.attr("alt").orEmpty() }.ifBlank { anchor.text() }.ifBlank { id }
            VideoItem(id = id, title = title, coverUrl = cover, detailUrl = url,
                code = Regex("[A-Za-z]{2,8}[- ]?\\d{2,8}").find(title)?.value.orEmpty())
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
        val actors = doc.select("a[href*=/category/cast/]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val makers = doc.select("a[href*=/category/maker/]").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct()
        val categories = doc.select("a[href*=/category/]")
            .filterNot { it.attr("href").contains("/category/cast/") ||
                it.attr("href").contains("/category/maker/") }
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val servers = doc.select(".btn-server[data-link]").mapIndexed { index, el ->
            VideoServer(index.toString(), el.text().ifBlank { "Server " + (index + 1) },
                el.attr("data-link"), video.detailUrl)
        }.ifEmpty {
            doc.select("iframe#video[src], #player-wrap iframe[src]").mapIndexed { index, el ->
                VideoServer(index.toString(), "Player " + (index + 1), el.absUrl("src"), video.detailUrl)
            }
        }
        val relatedRoot = doc.selectFirst(".related, .related-posts, #related")
        val related = relatedRoot?.let { parseList(it.outerHtml(), video.detailUrl) }.orEmpty()
            .filterNot { it.id == video.id }
        val description = doc.selectFirst(".post-content, .entry-content, .post-meta")?.text()
            .orEmpty().take(1200)
        Diagnostics.log("DETAIL id=" + video.id + " servers=" + servers.size)
        val taxonomy = parseBrowseOptions(html, video.detailUrl)
            .map { BrowseLink(it.kind, it.label, it.path) }
        VideoDetail(video.copy(title = title, coverUrl = cover, tags = tags, actors = actors),
            description, tags, actors, servers, related, categories, makers, taxonomy)
    }


    data class BrowseOption(
        val kind: FeedKind,
        val value: String,
        val label: String,
        val path: String = value
    )

    /** No fixed hardcoded category names: read only actual same-site links. */
    suspend fun browseOptions(): List<BrowseOption> = withContext(Dispatchers.IO) {
        parseBrowseOptions(fetch(BASE + "/"))
    }

    fun parseBrowseOptions(html: String, base: String = BASE): List<BrowseOption> {
        val doc = Jsoup.parse(html, base)
        val selector = listOf(
            "nav a[href]", "header a[href]", ".menu a[href]", ".categories a[href]",
            ".category-list a[href]", ".sidebar a[href]",
            "a[href*='/category/']", "a[href*='/tag/']",
            "a[href*='/maker/']", "a[href*='/cast/']"
        ).joinToString(", ")
        return doc.select(selector).mapNotNull { element ->
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
        }.distinctBy { it.path }.take(300)
    }

    fun resolveUrl(base: String, raw: String): String = try {
        if (raw.isBlank() || raw.startsWith("data:")) "" else URI(base).resolve(raw.trim()).toString()
    } catch (_: Exception) { "" }
}
