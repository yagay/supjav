package com.yagay.supjav.data

import com.yagay.supjav.util.Diagnostics
import java.io.IOException
import java.net.URI
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A bounded, testable network boundary used only by native source extraction. */
internal data class GatewayPage(
    val url: String,
    val status: Int,
    val type: String,
    val body: String
)

internal data class MediaProbe(
    val playable: Boolean,
    val status: Int?,
    val finalUrl: String?,
    val reason: String,
    // Forward the working request profile into Media3 instead of probing
    // without Origin and then incorrectly adding Origin during playback.
    val includeOrigin: Boolean = true
)

internal interface MediaGateway {
    fun page(url: String, referer: String): GatewayPage
    fun postJson(url: String, referer: String, json: String): GatewayPage
    fun probe(url: String, referer: String, hls: Boolean): MediaProbe
}

/**
 * Security: validate each redirect before requesting it and check resolved IPs
 * against private/loopback/link-local addresses. No root-site cookies are forwarded
 * to unrelated providers, and raw signed URLs never enter diagnostic logs.
 */
internal class OkHttpMediaGateway : MediaGateway {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (MediaSourceDetector.origin("https://" + hostname) == null) {
                    throw IOException("Unsafe network hostname")
                }
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.isEmpty() || addresses.any {
                        it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress ||
                            it.isSiteLocalAddress || it.isMulticastAddress ||
                            isCarrierGradeNat(it.address)
                    }) throw IOException("Non-public network destination")
                return addresses
            }
        }).build()

    private fun isCarrierGradeNat(bytes: ByteArray): Boolean {
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 255
            val second = bytes[1].toInt() and 255
            // IPv6 ULA and link-local allocations must never be contacted.
            return (first and 0xfe) == 0xfc || first == 0xfe && (second and 0xc0) == 0x80
        }
        if (bytes.size != 4) return false
        val a = bytes[0].toInt() and 255
        val b = bytes[1].toInt() and 255
        return (a == 100 && b in 64..127) || a == 0 || a == 127 ||
            a >= 224 || (a == 169 && b == 254) || (a == 192 && b == 0)
    }

    private fun request(
        url: String, referer: String, range: Boolean = false,
        includeOrigin: Boolean = true
    ): Request.Builder {
        if (MediaSourceDetector.origin(url) == null ||
            MediaSourceDetector.origin(referer) == null) throw IOException("Unsafe HTTPS URL")
        val builder = Request.Builder().url(url)
            .header("User-Agent", SupjavSource.UA)
            .header("Accept", "*/*")
            .header("Referer", referer)
        if (range) builder.header("Range", "bytes=0-8191")
        val origin = MediaSourceDetector.origin(referer)
        if (includeOrigin && origin != null) builder.header("Origin", origin)
        return builder
    }

    private fun readLimited(response: okhttp3.Response, maxBytes: Int): ByteArray {
        val stream = response.body?.byteStream() ?: return ByteArray(0)
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(4096)
        while (out.size() < maxBytes) {
            val read = stream.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun exchange(
        initial: String,
        originalReferer: String,
        method: String,
        json: String? = null,
        maxBytes: Int = 1_000_000,
        range: Boolean = false,
        includeOrigin: Boolean = true
    ): Triple<GatewayPage, ByteArray, String> {
        var url = initial
        var referer = originalReferer
        val seen = HashSet<String>()
        repeat(6) { hop ->
            if (!seen.add(url)) throw IOException("Redirect loop")
            val req = request(url, referer, range, includeOrigin).apply {
                if (method == "POST" && hop == 0 && json != null) {
                    post(json.toRequestBody("application/json".toMediaType()))
                } else get()
            }.build()
            val call = client.newCall(req)
            call.timeout().timeout(
                if (range) 8L else if (method == "POST") 12L else 15L,
                TimeUnit.SECONDS
            )
            call.execute().use { response ->
                Diagnostics.log("MEDIA_HTTP method=" + method +
                    " hop=" + hop + " host=" + URI(url).host +
                    " status=" + response.code)
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location")
                        ?: throw IOException("Redirect without Location")
                    val resolved = URI(url).resolve(location).toString()
                    if (MediaSourceDetector.origin(resolved) == null)
                        throw IOException("Unsafe redirect destination")
                    referer = url
                    url = resolved
                    return@use
                }
                val bytes = readLimited(response, maxBytes)
                val type = response.header("Content-Type").orEmpty().lowercase()
                return Triple(
                    GatewayPage(response.request.url.toString(), response.code,
                        type, bytes.toString(Charsets.UTF_8)),
                    bytes,
                    response.request.url.toString()
                )
            }
        }
        throw IOException("Too many redirects")
    }

    override fun page(url: String, referer: String): GatewayPage =
        exchange(url, referer, "GET").first

    override fun postJson(url: String, referer: String, json: String): GatewayPage {
        val uri = URI(url)
        require(uri.path == "/api/stream" && uri.host == URI(referer).host &&
            MediaSourceDetector.origin(url) != null) { "Invalid player API" }
        return exchange(url, referer, "POST", json, maxBytes = 65_536).first
    }

    /**
     * A 200/206 HLS manifest is not proof the actual segment is accessible.
     * Inspect at most one variant playlist and one segment, using the same
     * request profile selected for the manifest. No retry storm or URL logging.
     */
    private fun hlsMediaReachable(
        manifest: String, manifestUrl: String, referer: String,
        includeOrigin: Boolean
    ): MediaProbe? {
        var content = manifest
        var base = manifestUrl
        repeat(2) { depth ->
            val lines = content.lineSequence().map { it.trim() }.toList()
            val path = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return null // A minimal/incomplete manifest cannot be assessed.
            val nextUrl = runCatching { URI(base).resolve(path).toString() }.getOrNull()
                ?: return MediaProbe(false, null, null, "invalid-hls-child", includeOrigin)
            if (MediaSourceDetector.origin(nextUrl) == null) {
                return MediaProbe(false, null, null, "unsafe-hls-child", includeOrigin)
            }
            val variant = lines.any { it.startsWith("#EXT-X-STREAM-INF", true) } &&
                depth == 0
            val (page, bytes, destination) = try {
                exchange(nextUrl, referer, "HLS_CHILD",
                    maxBytes = if (variant) 8192 else 1024,
                    // Media3 normally loads HLS fragments with an ordinary GET;
                    // a 206 on a tiny Range probe can hide a 403 on playback.
                    range = false, includeOrigin = includeOrigin)
            } catch (e: Exception) {
                Diagnostics.log("MEDIA_HLS_CHILD_ERROR type=" + e.javaClass.simpleName)
                return MediaProbe(false, null, null, "hls-child-error", includeOrigin)
            }
            Diagnostics.log("MEDIA_HLS_CHILD_CHECK depth=" + depth +
                " kind=" + (if (variant) "variant" else "segment") +
                " status=" + page.status + " host=" + URI(destination).host)
            if (page.status !in listOf(200, 206)) {
                return MediaProbe(false, page.status, null,
                    "hls-child-http", includeOrigin)
            }
            if (bytes.isEmpty() || page.type.contains("text/html") ||
                page.type.contains("application/json")) {
                return MediaProbe(false, page.status, null,
                    "hls-child-nonmedia", includeOrigin)
            }
            if (!variant) return null
            content = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n')
            if (!content.startsWith("#EXTM3U")) {
                return MediaProbe(false, page.status, null,
                    "invalid-hls-variant", includeOrigin)
            }
            base = destination
        }
        return null
    }

    override fun probe(url: String, referer: String, hls: Boolean): MediaProbe {
        fun check(range: Boolean, includeOrigin: Boolean, profile: String): MediaProbe {
            return try {
                val (page, bytes, finalUrl) = exchange(
                    url, referer, "PROBE", maxBytes = 8192,
                    range = range, includeOrigin = includeOrigin
                )
                Diagnostics.log("MEDIA_PROBE_PROFILE host=" + URI(finalUrl).host +
                    " profile=" + profile + " status=" + page.status)
                val status = page.status
                if (status !in listOf(200, 206)) {
                    MediaProbe(false, status, null, "http", includeOrigin)
                } else if (page.type.contains("text/html") ||
                    page.type.contains("application/json")) {
                    MediaProbe(false, status, null, "non-media-response", includeOrigin)
                } else if (bytes.isEmpty()) {
                    MediaProbe(false, status, null, "empty-response", includeOrigin)
                } else if (hls) {
                    val text = bytes.toString(Charsets.UTF_8)
                        .trimStart('\uFEFF', ' ', '\r', '\n')
                    val valid = text.startsWith("#EXTM3U")
                    val childResult = if (valid) hlsMediaReachable(
                        text, finalUrl, referer, includeOrigin
                    ) else null
                    if (childResult != null) childResult else MediaProbe(
                        valid, status, finalUrl.takeIf { valid },
                        if (valid) "hls-ok" else "invalid-hls", includeOrigin
                    )
                } else {
                    val mp4Magic = bytes.size >= 8 &&
                        bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())
                    val looksVideo = page.type.startsWith("video/") ||
                        page.type.contains("application/octet-stream") && mp4Magic ||
                        mp4Magic
                    MediaProbe(looksVideo, status, finalUrl.takeIf { looksVideo },
                        if (looksVideo) "mp4-ok" else "invalid-mp4", includeOrigin)
                }
            } catch (error: Exception) {
                Diagnostics.log("MEDIA_PROBE_ERROR profile=" + profile +
                    " type=" + error.javaClass.simpleName)
                MediaProbe(false, null, null, error.javaClass.simpleName, includeOrigin)
            }
        }

        // Range can be rejected even when a manifest accepts a normal GET.
        // Test alternative ordinary GET semantics only for HLS and 403/416;
        // do not attempt automated CAPTCHA handling, challenge retries or
        // aggressive header permutations.
        val initial = check(range = true, includeOrigin = true, profile = "range-origin")
        if (!hls || initial.playable || initial.status !in listOf(403, 416)) {
            return initial
        }
        val noRange = check(range = false, includeOrigin = true, profile = "get-origin")
        if (noRange.playable || noRange.status !in listOf(403, 416)) {
            return noRange
        }
        return check(range = false, includeOrigin = false, profile = "get-referer")
    }
}
