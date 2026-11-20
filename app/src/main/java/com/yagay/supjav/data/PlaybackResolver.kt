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
        // A first-party MissAV document is a deliberate browser fallback,
        // not a Supjav server token. Do not feed it to the old gateway parser.
        if (server.name == "MISSAV Web" &&
            MediaSourceDetector.origin(server.link) in
                setOf("https://missav.ws", "https://www.missav.ws") &&
            server.link == server.detailUrl) {
            Diagnostics.log("MISSAV_BROWSER_FALLBACK reason=dynamic-player")
            return@withContext ResolveResult.Unavailable(
                "需要 JavaScript 浏览器播放：原站动态播放器")
        }
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
        var verifiedPlayerUrl: String? = null
        var pages = 0
        var probes = 0
        // Avoid keeping the user stuck on a native spinner when a provider
        // needs its real browser environment.
        val deadlineNs = System.nanoTime() + 30_000_000_000L
        // The same signed manifest can be found by static extraction and
        // the browser/player frame. Reuse the exact probe result per route.
        val probed = HashMap<Triple<String, String, Boolean>, MediaProbe>()
        fun probeOnce(url: String, referer: String, hls: Boolean): MediaProbe {
            val key = Triple(url, referer, hls)
            return probed.getOrPut(key) { gateway.probe(url, referer, hls) }
        }
        try {
            while (pending.isNotEmpty() && pages < 6 && probes < 12 &&
                System.nanoTime() < deadlineNs) {
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
                    val check = probeOnce(direct.url, direct.referer,
                        direct.mimeType == "application/x-mpegURL")
                    probes++
                    Diagnostics.log("RESOLVE_PROBE line=" + line.name +
                        " status=" + check.status + " kind=" + check.reason)
                    if (check.playable) {
                        Diagnostics.log("RESOLVE_NATIVE_READY line=" + line.name +
                            " type=" + direct.mimeType)
                        return@withContext ResolveResult.Playable(
                            direct.copy(url = check.finalUrl ?: direct.url,
                                includeOrigin = check.includeOrigin))
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
                // The selected Supjav gateway itself redirected to this player.
                // Preserve its identity for WebView; the browser's document.referrer
                // can incorrectly show supjav.com instead of the intermediate gateway.
                if (url == start && SitePlayerWrapper.isSiteWrapper(start) &&
                    page.status in 200..299 &&
                    MediaSourceDetector.origin(page.url) !=
                        MediaSourceDetector.origin(start) &&
                    MediaSourceDetector.origin(page.url) != null &&
                    !Regex("(?i)advert|banner|tracker|popunder|preroll")
                        .containsMatchIn(page.url)) {
                    verifiedPlayerUrl = page.url
                    Diagnostics.log("RESOLVE_SELECTED_PLAYER host=" + URI(page.url).host)
                }
                if (page.status in listOf(403, 429)) {
                    httpBlocked = true
                    browserReason = "访问受限 HTTP " + page.status
                    continue
                }
                if (page.status !in 200..299) {
                    browserReason = "中转页面 HTTP " + page.status
                    continue
                }
                // Some HLS endpoints use extensionless paths; the manifest body
                // (not the URL suffix) determines the media format.
                if (page.body.trimStart().startsWith("#EXTM3U")) {
                    val probe = probeOnce(page.url, referer, true)
                    probes++
                    Diagnostics.log("RESOLVE_MANIFEST_PROBE status=" + probe.status +
                        " kind=" + probe.reason)
                    if (probe.playable) {
                        return@withContext ResolveResult.Playable(
                            PlaybackSource(probe.finalUrl ?: page.url,
                                "application/x-mpegURL", referer,
                                includeOrigin = probe.includeOrigin))
                    }
                    if (probe.status in listOf(403, 429)) httpBlocked = true
                    continue
                }
                if (PlaybackChallengeDetector.requiresHuman(page.body)) {
                    Diagnostics.log("RESOLVE_VERIFICATION_REQUIRED line=" + line.name +
                        " evidence=visible-challenge")
                    return@withContext ResolveResult.VerificationRequired
                }
                val result = SupjavServerAdapters.extract(line, page.body, page.url)
                val candidates = result.candidates.toMutableList()
                if (line == SupjavLine.ST) {
                    Diagnostics.log("RESOLVE_ST_PAGE host=" + URI(page.url).host +
                        " candidateCount=" + candidates.size +
                        " embeddedFrames=" + result.nextPages.size +
                        " robotlinkMarkup=" + page.body.contains("robotlink", true) +
                        " playerScriptResolved=" +
                        (SupjavServerAdapters.streamtapeDeclaredSource(page.body) != null))
                }

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
                    " variants=" + result.candidateTags.values.distinct().joinToString(",") +
                    " hop=" + pages)
                for (candidate in candidates.distinctBy { it.url }.take(6)) {
                    if (probes >= 12 || System.nanoTime() >= deadlineNs) break
                    val check = probeOnce(candidate.url, candidate.referer,
                        candidate.mimeType == "application/x-mpegURL")
                    probes++
                    Diagnostics.log("RESOLVE_PROBE line=" + line.name +
                        " variant=" + (result.candidateTags[candidate.url] ?: "other") +
                        " status=" + check.status + " kind=" + check.reason +
                        " host=" + URI(candidate.url).host)
                    if (check.playable) {
                        Diagnostics.log("RESOLVE_NATIVE_READY line=" + line.name +
                            " variant=" + (result.candidateTags[candidate.url] ?: "other") +
                            " type=" + candidate.mimeType +
                            " via=adapter")
                        return@withContext ResolveResult.Playable(
                            candidate.copy(url = check.finalUrl ?: candidate.url,
                                includeOrigin = check.includeOrigin))
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
        if (System.nanoTime() >= deadlineNs) Diagnostics.log(
            "RESOLVE_TIME_BUDGET line=" + line.name + " pages=" + pages)
        // A 403 from the actual media CDN is an access denial, not evidence
        // that JavaScript is required. Switching automatically to WebView
        // merely repeats the same failed request (as diagnostic logs confirm).
        val missavDetail = MediaSourceDetector.origin(server.detailUrl) in
            setOf("https://missav.ws", "https://www.missav.ws")
        if (httpBlocked && missavDetail) {
            Diagnostics.log("RESOLVE_CDN_ACCESS_DENIED line=" + line.name +
                " pages=" + pages + " probes=" + probes)
            ResolveResult.Unavailable(
                "媒体服务器拒绝访问（HTTP 403）；请刷新作品或选择其他线路。")
        } else {
            Diagnostics.log("RESOLVE_BROWSER_FALLBACK line=" + line.name +
                " pages=" + pages + " probes=" + probes +
                " restricted=false")
            ResolveResult.Unavailable(
                "需要 JavaScript 或浏览器播放：" +
                    (if (httpBlocked) "源站限制原生网络请求" else browserReason.take(55)),
                verifiedPlayerUrl = verifiedPlayerUrl)
        }
    }
}
