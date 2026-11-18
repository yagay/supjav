package com.yagay.supjav.desktop

import com.yagay.supjav.model.PlaybackSource
import com.yagay.supjav.util.Diagnostics
import com.sun.net.httpserver.HttpServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Loopback HLS relay for CDN streams whose manifest and segments require
 * the same Referer/Origin headers that succeeded during source verification.
 * The relay never listens on a non-loopback interface.
 */
internal class DesktopHlsRelay(private val source: PlaybackSource) : AutoCloseable {
    private val token = java.util.UUID.randomUUID().toString()
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val result = Dns.SYSTEM.lookup(hostname)
                if (result.isEmpty() || result.any { a ->
                    a.isAnyLocalAddress || a.isLoopbackAddress || a.isSiteLocalAddress ||
                    a.isLinkLocalAddress || a.isMulticastAddress ||
                    (a.address.size == 16 && ((a.address[0].toInt() and 254) == 252)) ||
                    (a.address.size == 4 && (a.address[0].toInt() and 255) == 100 &&
                        (a.address[1].toInt() and 255) in 64..127)
                }) throw IOException("Non-public remote address")
                return result
            }
        }).build()
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "Supjav-HLS-Relay").apply { isDaemon = true }
    }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
    val url: String get() = localUrl(source.url)

    init {
        server.executor = executor
        server.createContext("/hls/$token/") { exchange ->
            try {
                if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD")
                    throw IOException("Unsupported method")
                val encoded = exchange.requestURI.rawPath.substringAfterLast('/')
                val remote = String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                requireSafe(remote)
                var current = remote
                var response: okhttp3.Response? = null
                for (hop in 0..5) {
                    val builder = Request.Builder().url(current)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Referer", source.referer)
                    if (source.includeOrigin) {
                        val referer = URI(source.referer)
                        val origin = referer.scheme + "://" + referer.authority
                        builder.header("Origin", origin)
                    }
                    exchange.requestHeaders.getFirst("Range")?.takeIf {
                        it.matches(Regex("bytes=\\d*-\\d*"))
                    }?.let { builder.header("Range", it) }
                    val next = client.newCall(builder.build()).execute()
                    if (next.code in listOf(301, 302, 303, 307, 308)) {
                        val location = next.header("Location") ?: throw IOException("Redirect missing location")
                        val nextUrl = URI(current).resolve(location).toString()
                        next.close()
                        requireSafe(nextUrl)
                        current = nextUrl
                    } else {
                        response = next
                        break
                    }
                }
                val result = response ?: throw IOException("Too many redirects")
                result.use { upstream ->
                    val body = upstream.body ?: throw IOException("Empty upstream response")
                    val contentType = upstream.header("Content-Type").orEmpty()
                    val isPlaylist = current.substringBefore('?').endsWith(".m3u8", true) ||
                        contentType.contains("mpegurl", true) || contentType.contains("vnd.apple.mpegurl", true)
                    exchange.responseHeaders.add("Content-Type",
                        if (isPlaylist) "application/vnd.apple.mpegurl" else
                            contentType.ifBlank { "application/octet-stream" })
                    exchange.responseHeaders.add("Cache-Control", "no-store")
                    upstream.header("Content-Range")?.let {
                        exchange.responseHeaders.add("Content-Range", it)
                    }
                    upstream.header("Accept-Ranges")?.let {
                        exchange.responseHeaders.add("Accept-Ranges", it)
                    }
                    if (isPlaylist) {
                        val text = body.string()
                        if (!text.trimStart().startsWith("#EXTM3U"))
                            throw IOException("Invalid HLS manifest")
                        val payload = rewrite(text, current).toByteArray(StandardCharsets.UTF_8)
                        exchange.sendResponseHeaders(upstream.code, payload.size.toLong())
                        exchange.responseBody.use { it.write(payload) }
                    } else {
                        // Media segments may be very large. Stream them instead
                        // of loading the whole segment into the Java heap.
                        exchange.sendResponseHeaders(upstream.code, 0)
                        exchange.responseBody.use { output ->
                            body.byteStream().use { input -> input.copyTo(output, 64 * 1024) }
                        }
                    }
                }
            } catch (error: Exception) {
                Diagnostics.log("DESKTOP_HLS_RELAY_ERROR type=" + error.javaClass.simpleName)
                runCatching { exchange.sendResponseHeaders(502, -1) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        Diagnostics.log("DESKTOP_HLS_RELAY_STARTED origin=" + source.includeOrigin)
    }

    private fun requireSafe(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.port == -1 &&
            uri.host != "localhost" && !uri.host.endsWith(".local")) {
            "Invalid HLS remote URL"
        }
    }

    private fun localUrl(remote: String): String {
        requireSafe(remote)
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(remote.toByteArray(StandardCharsets.UTF_8))
        return "http://127.0.0.1:${server.address.port}/hls/$token/$encoded"
    }

    private fun rewrite(text: String, base: String): String =
        text.lineSequence().map { line ->
            if (line.startsWith("#")) {
                Regex("""URI="([^"]+)"""").replace(line) { match ->
                    val absolute = URI(base).resolve(match.groupValues[1]).toString()
                    "URI=" + 34.toChar() + localUrl(absolute) + 34.toChar()
                }
            } else if (line.isBlank()) line
            else localUrl(URI(base).resolve(line.trim()).toString())
        }.joinToString("\n")

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        Diagnostics.log("DESKTOP_HLS_RELAY_STOPPED")
    }
}
