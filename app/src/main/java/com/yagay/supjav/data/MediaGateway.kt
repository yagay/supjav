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
    val reason: String
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
        .callTimeout(25, TimeUnit.SECONDS)
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
        if (bytes.size != 4) return false
        val a = bytes[0].toInt() and 255
        val b = bytes[1].toInt() and 255
        return (a == 100 && b in 64..127) || a == 0 || a == 127 ||
            a >= 224 || (a == 169 && b == 254) || (a == 192 && b == 0)
    }

    private fun request(url: String, referer: String, range: Boolean = false): Request.Builder {
        if (MediaSourceDetector.origin(url) == null ||
            MediaSourceDetector.origin(referer) == null) throw IOException("Unsafe HTTPS URL")
        val builder = Request.Builder().url(url)
            .header("User-Agent", SupjavSource.UA)
            .header("Accept", "*/*")
            .header("Referer", referer)
        if (range) builder.header("Range", "bytes=0-8191")
        val origin = MediaSourceDetector.origin(referer)
        if (origin != null) builder.header("Origin", origin)
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
        range: Boolean = false
    ): Triple<GatewayPage, ByteArray, String> {
        var url = initial
        var referer = originalReferer
        val seen = HashSet<String>()
        repeat(6) { hop ->
            if (!seen.add(url)) throw IOException("Redirect loop")
            val req = request(url, referer, range).apply {
                if (method == "POST" && hop == 0 && json != null) {
                    post(json.toRequestBody("application/json".toMediaType()))
                } else get()
            }.build()
            client.newCall(req).execute().use { response ->
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

    override fun probe(url: String, referer: String, hls: Boolean): MediaProbe {
        return try {
            val (page, bytes, finalUrl) =
                exchange(url, referer, "PROBE", maxBytes = 8192, range = true)
            if (page.status !in listOf(200, 206)) {
                MediaProbe(false, page.status, null, "http")
            } else if (page.type.contains("text/html") ||
                page.type.contains("application/json")) {
                MediaProbe(false, page.status, null, "non-media-response")
            } else if (bytes.isEmpty()) {
                MediaProbe(false, page.status, null, "empty-response")
            } else if (hls) {
                val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n')
                MediaProbe(text.startsWith("#EXTM3U"), page.status,
                    finalUrl.takeIf { text.startsWith("#EXTM3U") },
                    if (text.startsWith("#EXTM3U")) "hls-ok" else "invalid-hls")
            } else {
                val mp4Magic = bytes.size >= 8 &&
                    bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())
                val looksVideo = page.type.startsWith("video/") ||
                    page.type.contains("application/octet-stream") && mp4Magic || mp4Magic
                MediaProbe(looksVideo, page.status,
                    finalUrl.takeIf { looksVideo },
                    if (looksVideo) "mp4-ok" else "invalid-mp4")
            }
        } catch (error: Exception) {
            Diagnostics.log("MEDIA_PROBE_ERROR type=" + error.javaClass.simpleName)
            MediaProbe(false, null, null, error.javaClass.simpleName)
        }
    }
}
