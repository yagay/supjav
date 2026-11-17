package com.yagay.supjav.data

import com.yagay.supjav.model.*
import com.yagay.supjav.util.Diagnostics
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Native-first Supjav resolver.
 *
 * Each click resolves precisely one Server through its own adapter. The gateway
 * handles safe redirects; candidates are accepted only after bounded media probe.
 * A browser fallback is explicit when a CDN rejects native HTTP or site scripts
 * are required. Never retry verification/paywall pages or accept arbitrary ad frames.
 */
class PlaybackResolver(private val source: SupjavSource) {
    internal var gateway: MediaGateway = OkHttpMediaGateway()

    suspend fun resolve(server: VideoServer): ResolveResult = withContext(Dispatchers.IO) {
        val line = SupjavLine.of(server.name)
        val raw = server.link.trim()
        val entryType = when {
            raw.startsWith("https://") -> "https"
            raw.startsWith("//") -> "protocol-relative"
            raw.startsWith("/") -> "relative"
            raw.isBlank() -> "empty"
            else -> "script-token"
        }
        Diagnostics.log("RESOLVE_START server=" + line.name + " entry=" + entryType +
            " chars=" + raw.length)
        val start = SupjavServerAdapters.startingUrl(server)
        if (start == null) {
            Diagnostics.log("RESOLVE_UNSUPPORTED_ENTRY server=" + line.name)
            return@withContext ResolveResult.Unavailable(
                "需要 JavaScript 或浏览器播放：线路入口格式未知")
        }

        // Breadth-first, bounded; never traverse arbitrary iframes outside
        // the selected server's gateway / player route.
        val pending = ArrayDeque<Pair<String, String>>()
        val visited = HashSet<String>()
        pending.add(start to server.detailUrl)
        var browserReason = "没有识别到有效媒体"
        var httpBlocked = false
        var pages = 0
        var probes = 0
        try {
            while (pending.isNotEmpty() && pages < 8 && probes < 16) {
                val (url, referer) = pending.removeFirst()
                if (!visited.add(url)) continue
                pages++
                if (MediaSourceDetector.origin(url) == null ||
                    MediaSourceDetector.origin(referer) == null) {
                    Diagnostics.log("RESOLVE_INVALID_ROUTE line=" + line.name)
                    continue
                }
                Diagnostics.log("RESOLVE_HOP line=" + line.name + " hop=" + pages +
                    " host=" + URI(url).host)

                val direct = SupjavServerAdapters.classify(url, referer)
                if (direct != null) {
                    val check = gateway.probe(direct.url, direct.referer,
                        direct.mimeType == "application/x-mpegURL")
                    probes++
                    Diagnostics.log("RESOLVE_PROBE line=" + line.name +
                        " status=" + check.status + " kind=" + check.reason)
                    if (check.playable) {
                        Diagnostics.log("RESOLVE_NATIVE_READY line=" + line.name +
                            " type=" + direct.mimeType)
                        return@withContext ResolveResult.Playable(
                            direct.copy(url = check.finalUrl ?: direct.url))
                    }
                    if (check.status in listOf(403, 429)) httpBlocked = true
                    browserReason = "媒体请求 " + (check.status?.toString() ?: check.reason)
                    continue
                }

                val page = try {
                    gateway.page(url, referer)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    browserReason = e.javaClass.simpleName
                    Diagnostics.log("RESOLVE_PAGE_FAILED line=" + line.name +
                        " type=" + e.javaClass.simpleName)
                    continue
                }
                Diagnostics.log("RESOLVE_PAGE line=" + line.name +
                    " status=" + page.status +
                    " host=" + URI(page.url).host + " bytes=" + page.body.length)
                if (page.status in listOf(403, 429)) {
                    httpBlocked = true
                    browserReason = "访问受限 HTTP " + page.status
                    continue
                }
                if (page.status !in 200..299) {
                    browserReason = "中转页面 HTTP " + page.status
                    continue
                }
                if (Regex("""(?i)(?:captcha|robot verification|cloudflare challenge|verify you are human)""")
                        .containsMatchIn(page.body.take(15_000))) {
                    Diagnostics.log("RESOLVE_VERIFICATION_REQUIRED line=" + line.name)
                    return@withContext ResolveResult.VerificationRequired
                }
                val result = SupjavServerAdapters.extract(line, page.body, page.url)
                val candidates = result.candidates.toMutableList()

                // VAS exposes a same-origin public player API, not a prebuilt HLS link.
                if (line == SupjavLine.VAS && result.vasApi != null) {
                    val api = SupjavServerAdapters.vasApiUrl(result.vasApi)
                    val fileCode = SupjavServerAdapters.vasFileCode(result.vasApi)
                    if (api != null && fileCode != null) {
                        try {
                            val requestBody = JSONObject()
                                .put("filecode", fileCode)
                                .put("device", "web")
                                .put("codecs", org.json.JSONArray()).toString()
                            val apiPage = gateway.postJson(api, page.url, requestBody)
                            Diagnostics.log("RESOLVE_VAS_API status=" + apiPage.status)
                            if (apiPage.status in listOf(403, 429)) httpBlocked = true
                            if (apiPage.status in 200..299) {
                                val mediaUrl = JSONObject(apiPage.body).optString("streaming_url")
                                SupjavServerAdapters.classify(mediaUrl, page.url)?.let {
                                    candidates.add(0, it)
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Diagnostics.log("RESOLVE_VAS_API_FAILED type=" +
                                e.javaClass.simpleName)
                        }
                    }
                }
                Diagnostics.log("RESOLVE_ADAPTER line=" + line.name +
                    " candidates=" + candidates.size + " frames=" + result.nextPages.size +
                    " hop=" + pages)
                for (candidate in candidates.distinctBy { it.url }.take(6)) {
                    if (probes >= 16) break
                    val check = gateway.probe(candidate.url, candidate.referer,
                        candidate.mimeType == "application/x-mpegURL")
                    probes++
                    Diagnostics.log("RESOLVE_PROBE line=" + line.name +
                        " status=" + check.status + " kind=" + check.reason +
                        " host=" + URI(candidate.url).host)
                    if (check.playable) {
                        Diagnostics.log("RESOLVE_NATIVE_READY line=" + line.name +
                            " type=" + candidate.mimeType +
                            " via=adapter")
                        return@withContext ResolveResult.Playable(
                            candidate.copy(url = check.finalUrl ?: candidate.url))
                    }
                    if (check.status in listOf(403, 429)) httpBlocked = true
                    browserReason = check.reason + " " + (check.status ?: "")
                }
                result.nextPages.forEach { next ->
                    if (pending.size < 8 && next !in visited) {
                        pending.add(next to page.url)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            browserReason = e.javaClass.simpleName
            Diagnostics.log("RESOLVE_FAILED line=" + line.name +
                " type=" + e.javaClass.simpleName)
        }
        val detail = if (httpBlocked) "源站限制原生网络请求" else browserReason.take(55)
        Diagnostics.log("RESOLVE_BROWSER_FALLBACK line=" + line.name +
            " pages=" + pages + " probes=" + probes +
            " restricted=" + httpBlocked)
        ResolveResult.Unavailable("需要 JavaScript 或浏览器播放：" + detail)
    }
}
