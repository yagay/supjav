package com.yagay.supjav.data

import com.yagay.supjav.model.PlaybackSource
import com.yagay.supjav.model.VideoServer
import java.net.URI
import java.util.Base64
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Independent adapters for selected Supjav Server lines. */
internal enum class SupjavLine {
    EVS, FST, VAS, VOE, LUC, TV, ST, UNKNOWN;
    companion object {
        fun of(name: String): SupjavLine {
            val normalized = name.trim().uppercase(Locale.US).substringBefore(' ')
            return entries.firstOrNull { it.name == normalized } ?: when (normalized) {
                "STREAMTAPE", "STPLAYER" -> ST
                "FAST", "FC2STREAM", "FPLAYER" -> FST
                "FAV", "FAVJAV" -> TV
                else -> UNKNOWN
            }
        }
    }
}

internal data class ServerExtraction(
    val candidates: List<PlaybackSource>,
    val nextPages: List<String>,
    val vasApi: String? = null
)

/** Bounded static parsers. Does not execute JavaScript or bypass a site challenge. */
internal object SupjavServerAdapters {
    private val mediaUrl = Regex(
        """https?://[^\s"'<>\\]+?(?:\.m3u8|\.m3u|\.mp4|/(?:master|index|playlist)(?:-[a-z0-9]+)*\.txt)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val explicitMedia = Regex(
        """(?i)(?:\burlPlay\b|\bstreaming_url\b|\bfile\b|\bsource\b)\s*["']?\s*[:=]\s*["']([^"']{8,2048})["']"""
    )
    private val javascriptRedirect = Regex(
        """(?i)window\.location(?:\.href)?\s*=\s*["'](https://[^"']+)["']"""
    )
    private val knownFramePath = Regex(
        """^/(?:e|embed|player|v|supjav\.php)(?:/|$)""", RegexOption.IGNORE_CASE
    )
    private val noise = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&", "_")

    fun startingUrl(server: VideoServer): String? {
        val token = server.link.trim()
        val raw = when {
            token.startsWith("https://") -> token
            token.startsWith("//") -> "https:" + token
            token.startsWith("/") -> runCatching {
                URI(server.detailUrl).resolve(token).toString()
            }.getOrNull()
            // Supjav's reversed data-link wrapper applies across server labels.
            token.matches(Regex("[A-Za-z0-9_-]{3,512}")) ->
                "https://lk1.supremejav.com/supjav.php?c=" + token.reversed()
            else -> null
        } ?: return null
        return raw.takeIf { MediaSourceDetector.origin(it) != null }
    }

    private fun clean(raw: String): String =
        org.jsoup.parser.Parser.unescapeEntities(raw.replace("\\/", "/"), false)
            .trim().replace("\\u0026", "&")

    fun classify(url: String, referer: String): PlaybackSource? =
        MediaSourceDetector.fromMediaUrl(clean(url), referer)

    fun extract(line: SupjavLine, html: String, documentUrl: String): ServerExtraction {
        if (MediaSourceDetector.origin(documentUrl) == null) {
            return ServerExtraction(emptyList(), emptyList())
        }
        val doc = Jsoup.parse(html.take(1_000_000), documentUrl)
        val urls = LinkedHashSet<String>()
        fun add(value: String?) {
            val raw = value?.takeIf { it.isNotBlank() } ?: return
            val cleaned = clean(raw)
            val absolute = runCatching { URI(documentUrl).resolve(cleaned).toString() }.getOrNull()
            if (absolute != null && classify(absolute, documentUrl) != null) urls.add(absolute)
        }
        val declared = when (line) {
            SupjavLine.VOE -> voeDeclaredSource(doc)
            SupjavLine.ST -> streamtapeDeclaredSource(html)
            else -> null
        }
        val scriptText = doc.select("script:not([src])")
            .joinToString("\n") { it.data().ifBlank { it.html() } }.take(400_000)
        if (declared != null) {
            // VOE and ST often include valid-looking advertisement/decoy URLs.
            // A recognized player configuration must not fall back to those.
            add(declared)
        } else {
            doc.select("video[src], video source[src], source[type*=video][src]").forEach {
                add(it.absUrl("src"))
            }
            if (line == SupjavLine.FST) fstDeclaredSource(html, documentUrl)?.let(::add)
            for (text in listOf(scriptText, unpackPacker(scriptText).orEmpty())) {
                for (match in explicitMedia.findAll(text)) {
                    val value = clean(match.groupValues[1])
                    if (value.startsWith("https://")) add(value)
                }
                if (Regex("""(?i)\b(?:jwplayer|hls|sources|player\.setup|videojs)\b""")
                        .containsMatchIn(text)) {
                    mediaUrl.findAll(text).take(10).forEach { add(it.value) }
                }
            }
        }
        val next = LinkedHashSet<String>()
        fun addNext(raw: String) {
            val url = runCatching { URI(documentUrl).resolve(clean(raw)).toString() }.getOrNull()
                ?: return
            val uri = runCatching { URI(url) }.getOrNull() ?: return
            if (MediaSourceDetector.origin(url) == null ||
                uri.path.isNullOrBlank() || !knownFramePath.containsMatchIn(uri.path) ||
                Regex("""(?i)(?:ads?|banner|preroll|vast|popunder|tracker)""")
                    .containsMatchIn(url)) return
            next.add(url)
        }
        doc.select("iframe[src]").forEach { addNext(it.absUrl("src")) }
        if (line == SupjavLine.VOE) {
            javascriptRedirect.find(scriptText)?.groupValues?.get(1)?.let(::addNext)
        }
        val api = if (line == SupjavLine.VAS &&
            html.contains("/api/stream") && vasFileCode(documentUrl) != null)
                documentUrl else null
        return ServerExtraction(
            urls.mapNotNull { classify(it, documentUrl) }.distinctBy { it.url },
            next.toList().take(5),
            api
        )
    }

    /** Read FST links map and its declared HLS priority without evaluating scripts. */
    internal fun fstDeclaredSource(html: String, documentUrl: String): String? {
        val linksObject = Regex(
            """(?s)(?:var|let|const)\s+links\s*=\s*\{(.{1,30000}?)\}\s*[;,]"""
        ).find(html)?.groupValues?.get(1) ?: return null
        val linkValues = Regex(
            """(?i)["']?(hls[234])["']?\s*:\s*["']([^"']{1,3000})["']"""
        ).findAll(linksObject).associate {
            it.groupValues[1].lowercase() to clean(it.groupValues[2])
        }
        val orChain = Regex(
            """(?is)(?:file|["']file["'])\s*:\s*(links\.hls[234](?:\s*\|\|\s*links\.hls[234])*)"""
        ).find(html)?.groupValues?.get(1) ?: return null
        for (key in Regex("""links\.(hls[234])""").findAll(orChain)) {
            val value = linkValues[key.groupValues[1]] ?: continue
            val url = runCatching { URI(documentUrl).resolve(value).toString() }.getOrNull()
                ?: continue
            if (MediaSourceDetector.fromMediaUrl(url, documentUrl) != null) return url
        }
        return null
    }

    /** Ignore Streamtape's decoy text; consider only the declared robotlink JS expression. */
    internal fun streamtapeDeclaredSource(html: String): String? {
        val match = Regex(
            """(?is)getElementById\(\s*['"]robotlink['"]\s*\)\.innerHTML\s*=\s*['"]([^'"]*)['"]\s*\+\s*(?:['"]{2}\s*\+\s*)?\(\s*['"]([^'"]*)['"]\s*\)((?:\s*\.substring\(\s*\d+\s*\))+)"""
        ).find(html) ?: return null
        var suffix = match.groupValues[2]
        val operations = Regex("""\.substring\(\s*(\d+)\s*\)""")
            .findAll(match.groupValues[3]).map { it.groupValues[1].toInt() }
        for (index in operations) suffix = suffix.drop(index.coerceAtMost(suffix.length))
        val candidate = (match.groupValues[1] + suffix).trim()
        val full = when {
            candidate.startsWith("//") -> "https:" + candidate
            candidate.startsWith("https://") -> candidate
            else -> return null
        }
        return full.takeIf {
            val uri = runCatching { URI(it) }.getOrNull()
            uri != null && uri.path == "/get_video" &&
                uri.host?.lowercase() in setOf("streamtape.com", "streamtape.to") &&
                MediaSourceDetector.origin(it) != null
        }
    }

    /** Decode VOE's JSON player configuration, not arbitrary script or decoy video URLs. */
    internal fun voeDeclaredSource(doc: Document): String? {
        for (node in doc.select("script[type=application/json]")) {
            val raw = node.data().ifBlank { node.html() }.trim()
            try {
                val input = JSONArray(raw).optString(0)
                if (input.length !in 10..30000) continue
                val withoutNoise = noise.fold(input) { value, marker ->
                    value.replace(marker, "")
                }
                val rot13 = withoutNoise.map { char ->
                    when (char) {
                        in 'a'..'z' -> 'a' + ((char - 'a' + 13) % 26)
                        in 'A'..'Z' -> 'A' + ((char - 'A' + 13) % 26)
                        else -> char
                    }
                }.joinToString("")
                val shifted = Base64.getDecoder().decode(rot13).toString(Charsets.US_ASCII)
                val unshifted = shifted.map { (it.code - 3).toChar() }
                    .joinToString("").reversed()
                val content = Base64.getDecoder().decode(unshifted).toString(Charsets.UTF_8)
                val source = JSONObject(content).optString("source")
                if (source.isNotBlank() && MediaSourceDetector.origin(source) != null)
                    return source
            } catch (_: Exception) { /* Not a recognized player declaration. */ }
        }
        return null
    }

    /** Decode only the packer's literal dictionary, never execute untrusted JS. */
    internal fun unpackPacker(script: String): String? {
        val regex = Regex(
            """(?s)eval\(function\(p,a,c,k,e,d\)\{.*?\}\(\s*'([^']*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']*)'\s*\.split\('\|'\)"""
        )
        val match = regex.find(script.take(200_000)) ?: return null
        val base = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        if (base !in 2..36 || count !in 1..3000) return null
        val keys = match.groupValues[4].split('|')
        return Regex("""\b[a-zA-Z0-9_]+\b""").replace(match.groupValues[1]) {
            val idx = it.value.toIntOrNull(base)
            if (idx != null && idx in 0 until count && idx < keys.size &&
                keys[idx].isNotBlank()) keys[idx] else it.value
        }
    }

    internal fun vasFileCode(url: String): String? = runCatching {
        val uri = URI(url)
        if (MediaSourceDetector.origin(url) == null) return@runCatching null
        Regex("""^/e/([A-Za-z0-9]{2,120})/?$""")
            .find(uri.path.orEmpty())?.groupValues?.get(1)
    }.getOrNull()

    internal fun vasApiUrl(url: String): String? {
        val fileCode = vasFileCode(url) ?: return null
        if (fileCode.isBlank()) return null
        val uri = URI(url)
        val query = uri.rawQuery?.split('&')?.filter {
            it.isNotBlank() && !it.startsWith("filecode=", true)
        }?.joinToString("&").orEmpty()
        val origin = MediaSourceDetector.origin(url) ?: return null
        return origin + "/api/stream" + if (query.isEmpty()) "" else "?" + query
    }
}
