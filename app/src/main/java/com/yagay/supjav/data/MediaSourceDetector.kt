package com.yagay.supjav.data

import com.yagay.supjav.model.PlaybackSource
import java.net.URI
import java.util.Locale

/**
 * Shared streaming source classifier for the website probe and a future download module.
 * Identifying an HLS/MP4 URL never implies it will be usable outside WebView.
 */
object MediaSourceDetector {
    fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase(Locale.US) ?: return@runCatching null
        val bad = host == "localhost" || host == "0.0.0.0" ||
            host == "127.0.0.1" || host == "::1" || host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host) ||
            host.endsWith(".local") || host.endsWith(".internal")
        if (uri.scheme?.lowercase(Locale.US) != "https" || uri.userInfo != null ||
            host.isBlank() || bad || (uri.port != -1 && uri.port != 443)) null
        else "https://" + host
    }.getOrNull()

    private fun mediaType(url: String): String? = runCatching {
        val uri = URI(url)
        val path = uri.path.orEmpty().lowercase(Locale.US)
        if (Regex("/(?:ads?|advert|preroll|vast)(?:/|[._-]|$)").containsMatchIn(path)) {
            return@runCatching null
        }
        when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> "application/x-mpegURL"
            Regex("/hls\\d*/").containsMatchIn(path) &&
                Regex("/(?:master|index|playlist|iframes)(?:-[a-z0-9]+)*\\.txt$").containsMatchIn(path) ->
                "application/x-mpegURL"
            path.endsWith(".mp4") -> "video/mp4"
            (uri.host?.lowercase(Locale.US) in setOf("streamtape.com", "streamtape.to")) &&
                path == "/get_video" && !uri.rawQuery.isNullOrBlank() -> "video/mp4"
            else -> null
        }
    }.getOrNull()

    fun fromMediaUrl(url: String, referer: String): PlaybackSource? {
        if (origin(url) == null || origin(referer) == null) return null
        val mime = mediaType(url) ?: return null
        return PlaybackSource(url, mime, referer)
    }
}

/**
 * Links a detected video to the currently selected Supjav server.
 * No arbitrary frame may submit a media source: parent chain must originate
 * at Supjav, and each embedded player is admitted by an already trusted parent.
 */
class SelectedMediaSession(sitePage: String) {
    private val siteOrigin = MediaSourceDetector.origin(sitePage)
    private val trustedOrigins = mutableSetOf<String>()

    init {
        if (siteOrigin in setOf("https://supjav.com", "https://www.supjav.com")) {
            trustedOrigins.add(requireNotNull(siteOrigin))
        }
    }

    @Synchronized
    fun trustFrame(sourcePage: String, frame: String): Boolean {
        val sourceOrigin = MediaSourceDetector.origin(sourcePage) ?: return false
        val frameOrigin = MediaSourceDetector.origin(frame) ?: return false
        if (sourceOrigin !in trustedOrigins) return false
        if (frameOrigin == sourceOrigin) return true
        val path = runCatching { URI(frame).path.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        val recognized = sourceOrigin == siteOrigin ||
            Regex("^/(?:e|embed|player|v|supjav\\.php)(?:/|$)").containsMatchIn(path)
        if (!recognized || Regex("(?:advert|banner|popunder|preroll)").containsMatchIn(frame.lowercase(Locale.US))) {
            return false
        }
        trustedOrigins.add(frameOrigin)
        return true
    }

    @Synchronized
    fun fromPageReport(
        reportedOrigin: String,
        documentUrl: String,
        mediaUrl: String
    ): PlaybackSource? {
        val documentOrigin = MediaSourceDetector.origin(documentUrl) ?: return null
        if (documentOrigin != MediaSourceDetector.origin(reportedOrigin) ||
            documentOrigin !in trustedOrigins) return null
        return MediaSourceDetector.fromMediaUrl(mediaUrl, documentUrl)
    }

    @Synchronized
    fun fromNetworkRequest(mediaUrl: String, requestReferer: String?): PlaybackSource? {
        val ref = requestReferer ?: return null
        if (MediaSourceDetector.origin(ref) !in trustedOrigins) return null
        return MediaSourceDetector.fromMediaUrl(mediaUrl, ref)
    }
}
