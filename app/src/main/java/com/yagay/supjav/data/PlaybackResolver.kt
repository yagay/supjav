package com.yagay.supjav.data

import com.yagay.supjav.model.*
import com.yagay.supjav.util.Diagnostics
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class PlaybackResolver(private val source: SupjavSource) {
    private val mediaPattern = Regex("""(?i)https?://[^"'\s<>]+?(?:\.m3u8|\.mp4|master\.txt)(?:\?[^"'\s<>]*)?""")

    suspend fun resolve(server: VideoServer): ResolveResult = withContext(Dispatchers.IO) {
        try {
            val entryType = when {
                server.link.startsWith("https://") -> "https"
                server.link.startsWith("//") -> "protocol-relative"
                server.link.startsWith("/") -> "relative"
                server.link.isBlank() -> "empty"
                else -> "script-token"
            }
            Diagnostics.log("RESOLVE_START server=" + server.name + " entry=" +
                entryType + " chars=" + server.link.length)
            val start = startingUrl(server)
            if (start == null) {
                Diagnostics.log("RESOLVE_UNSUPPORTED_ENTRY server=" + server.name +
                    " entry=" + entryType + " chars=" + server.link.length)
                return@withContext ResolveResult.Unavailable(
                    "该线路仅提供网页脚本入口，尚未找到原生播放地址")
            }
            if (!source.isPublicHttps(start)) {
                Diagnostics.log("RESOLVE_REJECTED_URL server=" + server.name)
                return@withContext ResolveResult.Unavailable("不允许访问非安全媒体地址")
            }
            var url = start
            var referer = server.detailUrl
            val visited = mutableSetOf<String>()
            repeat(5) { hop ->
                if (!visited.add(url)) {
                    Diagnostics.log("RESOLVE_CYCLE hop=" + hop)
                    return@withContext ResolveResult.Unavailable("播放器循环跳转")
                }
                Diagnostics.log("RESOLVE_HOP hop=" + hop + " server=" + server.name +
                    " host=" + URI(url).host)
                if (looksLikeMedia(url)) {
                    Diagnostics.log("RESOLVE_DIRECT_FOUND type=" +
                        if (url.contains("m3u8") || url.contains("master.txt")) "hls" else "mp4")
                    return@withContext ResolveResult.Playable(media(url, referer))
                }
                val html = source.fetch(url, referer)
                if (html.trimStart().startsWith("#EXTM3U")) {
                    Diagnostics.log("RESOLVE_HLS_MANIFEST hop=" + hop)
                    return@withContext ResolveResult.Playable(media(url, referer))
                }
                if (Regex("(?i)captcha|robot verification|cloudflare challenge").containsMatchIn(html.take(10000))) {
                    Diagnostics.log("RESOLVE_VERIFICATION_REQUIRED hop=" + hop)
                    return@withContext ResolveResult.VerificationRequired
                }
                val doc = Jsoup.parse(html, url)
                val direct = doc.selectFirst("video source[src], video[src], source[src]")
                    ?.let { it.absUrl("src") }?.takeIf { looksLikeMedia(it) }
                    ?: mediaPattern.find(html.replace("\\/", "/").replace("&amp;", "&"))?.value
                if (direct != null && source.isPublicHttps(direct)) {
                    Diagnostics.log("RESOLVE_MEDIA_FOUND hop=" + hop +
                        " type=" + if (direct.contains(".mp4", true)) "mp4" else "hls")
                    return@withContext ResolveResult.Playable(media(direct, url))
                }
                val frame = doc.selectFirst("iframe#video[src], #player-wrap iframe[src], iframe[src]")
                    ?.absUrl("src")?.takeIf { source.isPublicHttps(it) }
                if (frame == null) {
                    Diagnostics.log("RESOLVE_NO_DIRECT_MEDIA_OR_IFRAME hop=" + hop +
                        " htmlLength=" + html.length)
                    return@withContext ResolveResult.Unavailable(
                        "需要 JavaScript 或第三方播放器验证，无法直接原生解析")
                }
                referer = url
                url = frame
            }
            Diagnostics.log("RESOLVE_TOO_MANY_HOPS")
            ResolveResult.Unavailable("播放器中转层数过多")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.log("RESOLVE_FAILED type=" + e.javaClass.simpleName +
                " reason=" + e.message.orEmpty())
            ResolveResult.Unavailable(e.message?.take(140) ?: "无法读取线路")
        }
    }

    private fun startingUrl(server: VideoServer): String? {
        val link = server.link.trim()
        if (link.startsWith("https://")) return link
        if (link.startsWith("//")) return "https:" + link
        if (link.startsWith("/")) return source.resolveUrl(server.detailUrl, link)
        // Historic TV button supplies a reversed token to the site's own player wrapper.
        if (server.name.equals("TV", ignoreCase = true) && link.matches(Regex("[A-Za-z0-9_-]{3,150}"))) {
            return "https://lk1.supremejav.com/supjav.php?c=" + link.reversed()
        }
        return null
    }

    private fun looksLikeMedia(url: String): Boolean =
        Regex("(?i)(\\.m3u8|\\.mp4|master\\.txt)(\\?|$)").containsMatchIn(url)

    private fun media(url: String, referer: String) = PlaybackSource(url,
        if (url.contains(".m3u8", true) || url.contains("master.txt", true))
            "application/x-mpegURL" else "video/mp4", referer)
}
