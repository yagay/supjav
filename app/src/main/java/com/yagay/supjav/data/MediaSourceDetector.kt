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
        // Only ordinary DNS hosts. Reject IPv4/IPv6 literals, numeric aliases,
        // loopback suffixes, IDN lookalikes, embedded credentials and ports.
        val validDomain = Regex("^[a-z0-9-]+(?:\\.[a-z0-9-]+)+$").matches(host) &&
            host.split('.').none { it.isBlank() || it.startsWith('-') || it.endsWith('-') } &&
            !host.split('.').last().all { it.isDigit() } &&
            !host.endsWith(".local") && !host.endsWith(".localhost") &&
            !host.endsWith(".internal") && !host.endsWith(".lan") &&
            !host.endsWith(".home") && !host.endsWith(".test")
        if (uri.scheme?.lowercase(Locale.US) != "https" || uri.userInfo != null ||
            !validDomain || uri.rawAuthority.orEmpty().contains('%') ||
            (uri.port != -1 && uri.port != 443)) null
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
class SelectedMediaSession(
    sitePage: String,
    selectedWrapperUrl: String? = null,
    verifiedPlayerUrl: String? = null
) {
    private val siteOrigin = MediaSourceDetector.origin(sitePage)
    private val trustedOrigins = mutableSetOf<String>()

    init {
        if (siteOrigin in setOf("https://supjav.com", "https://www.supjav.com")) {
            trustedOrigins.add(requireNotNull(siteOrigin))
            // A different-origin document can use Supjav as its referrer even
            // when it originated through the selected wrapper's HTTP redirect.
            // Trust ONLY the verified redirect from THIS selected wrapper.
            val verifiedOrigin = verifiedPlayerUrl?.let(MediaSourceDetector::origin)
            if (selectedWrapperUrl != null && SitePlayerWrapper.isSiteWrapper(selectedWrapperUrl) &&
                verifiedOrigin != null && verifiedOrigin != siteOrigin &&
                verifiedOrigin != MediaSourceDetector.origin(selectedWrapperUrl) &&
                !Regex("(?i)advert|banner|tracker|popunder|preroll")
                    .containsMatchIn(verifiedPlayerUrl)) {
                trustedOrigins.add(requireNotNull(
                    MediaSourceDetector.origin(selectedWrapperUrl)))
                trustedOrigins.add(verifiedOrigin)
            }
        }
    }

    @Synchronized
    fun trustFrame(sourcePage: String, frame: String): Boolean {
        val sourceOrigin = MediaSourceDetector.origin(sourcePage) ?: return false
        val frameOrigin = MediaSourceDetector.origin(frame) ?: return false
        if (sourceOrigin !in trustedOrigins) return false
        if (frameOrigin == sourceOrigin) return true
        val path = runCatching { URI(frame).path.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        val recognized = if (sourceOrigin == siteOrigin) {
            SitePlayerWrapper.isSiteWrapper(frame)
        } else {
            Regex("^/(?:e|embed|player|v|supjav\\.php)(?:/|$)").containsMatchIn(path)
        }
        if (!recognized || Regex("(?:advert|banner|popunder|preroll)").containsMatchIn(frame.lowercase(Locale.US))) {
            return false
        }
        trustedOrigins.add(frameOrigin)
        return true
    }

    /**
     * Frame redirects may replace the original iframe URL with a new player origin.
     * Accept only a browser-verified document whose referrer is already in the
     * user-selected chain and whose path is a known embedding route.
     */
    @Synchronized
    fun admitDocument(documentUrl: String, documentReferer: String): Boolean {
        val childOrigin = MediaSourceDetector.origin(documentUrl) ?: return false
        val parentOrigin = MediaSourceDetector.origin(documentReferer) ?: return false
        if (parentOrigin !in trustedOrigins) return false
        if (childOrigin in trustedOrigins) return true
        val path = runCatching { URI(documentUrl).path.orEmpty().lowercase(Locale.US) }
            .getOrDefault("")
        val allowed = if (parentOrigin == siteOrigin) {
            SitePlayerWrapper.isSiteWrapper(documentUrl)
        } else {
            Regex("^/(?:e|embed|player|v|supjav\\.php)(?:/|$)").containsMatchIn(path)
        }
        if (!allowed || Regex("advert|banner|popunder|preroll", RegexOption.IGNORE_CASE)
                .containsMatchIn(documentUrl)) return false
        trustedOrigins.add(childOrigin)
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
            documentOrigin !in trustedOrigins || documentOrigin == siteOrigin) return null
        return MediaSourceDetector.fromMediaUrl(mediaUrl, documentUrl)
    }

    @Synchronized
    fun fromNetworkRequest(mediaUrl: String, requestReferer: String?): PlaybackSource? {
        val ref = requestReferer ?: return null
        val refererOrigin = MediaSourceDetector.origin(ref)
        if (refererOrigin !in trustedOrigins || refererOrigin == siteOrigin) return null
        return MediaSourceDetector.fromMediaUrl(mediaUrl, ref)
    }
}
