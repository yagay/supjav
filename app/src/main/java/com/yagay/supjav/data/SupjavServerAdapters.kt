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
            val normalized = name.trim().uppercase(Locale.US)
                .replace(Regex("""\b(?:CD|DISC|PART)[ _-]*0?[1-9][0-9]?\b"""), "")
                .trim().trimStart { !it.isLetterOrDigit() }.substringBefore(' ')
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
    val vasApi: String? = null,
    /** Signed URLs remain internal; only these benign tags appear in logs. */
    val candidateTags: Map<String, String> = emptyMap()
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
        val candidateTags = LinkedHashMap<String, String>()
        fun add(value: String?, tag: String? = null) {
            val raw = value?.takeIf { it.isNotBlank() } ?: return
            val cleaned = clean(raw)
            val absolute = runCatching { URI(documentUrl).resolve(cleaned).toString() }.getOrNull()
            if (absolute != null && classify(absolute, documentUrl) != null) {
                urls.add(absolute)
                if (tag != null) candidateTags.putIfAbsent(absolute, tag)
            }
        }
        val declared = when (line) {
            SupjavLine.VOE -> voeDeclaredSource(doc)
            SupjavLine.ST -> streamtapeDeclaredSource(html)
            else -> null
        }
        val scripts = doc.select("script:not([src])")
            .map { it.data().ifBlank { it.html() } }
            .filter { it.isNotBlank() }.take(100)
        val scriptText = scripts.joinToString("\n").take(400_000)
        // A page may contain multiple independent packers. Only unpacking the
        // first one silently misses later actual player configurations.
        val unpackedScripts = scripts.mapNotNull { unpackPacker(it) }
        val scriptsToScan = (scripts + unpackedScripts).take(140)
        if (declared != null) {
            // VOE and ST often include valid-looking advertisement/decoy URLs.
            // A recognized player configuration must not fall back to those.
            add(declared)
        } else {
            // EarnVids / LuluStream store several signed renditions in their
            // own player config. Try hls2/master.m3u8 ahead of hls3/master.txt.
            // Never invent a URL by changing its path or signature.
            if (line == SupjavLine.EVS || line == SupjavLine.LUC) {
                val variants = rankedHlsCandidates(scriptsToScan, documentUrl)
                variants.forEach { (tag, url) -> add(url, tag) }
            }
            doc.select("video[src], video source[src], source[type*=video][src]").forEach {
                add(it.absUrl("src"))
            }
            if (line == SupjavLine.FST) fstDeclaredSource(html, documentUrl)?.let(::add)
            for (text in scriptsToScan) {
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
            api,
            candidateTags
        )
    }

    /**
     * Vidhide/EarnVids player data commonly exposes hls2, hls4 and hls3.
     * Unlike an arbitrary HTML URL scan, these are explicit player-config
     * entries and must be tried in source-specific order.
     *
     * The URLs are used verbatim after bounded HTTPS validation: changing
     * their CDN paths would invalidate the server's signed token.
     */
    internal fun rankedHlsCandidates(
        scripts: List<String>, documentUrl: String
    ): List<Pair<String, String>> {
        if (MediaSourceDetector.origin(documentUrl) == null) return emptyList()
        val recognized = linkedMapOf<String, String>()
        val declarations = Regex(
            """(?i)(?:^|[,{\s])["']?(hls[234])["']?\s*:\s*["']([^"'<>]{1,3000})["']"""
        )
        for (script in scripts.take(140)) {
            // Player script only. Avoid arbitrary ad-side HLS strings.
            if (!script.contains("hls2", true) &&
                !script.contains("hls3", true) &&
                !script.contains("hls4", true)) continue
            for (entry in declarations.findAll(script.take(100_000))) {
                val tag = entry.groupValues[1].lowercase(Locale.US)
                if (tag in recognized) continue
                val raw = clean(entry.groupValues[2])
                val url = runCatching { URI(documentUrl).resolve(raw).toString() }.getOrNull()
                    ?: continue
                val source = classify(url, documentUrl) ?: continue
                if (source.mimeType != "application/x-mpegURL") continue
                recognized[tag] = source.url
            }
        }
        return listOf("hls2", "hls4", "hls3")
            .mapNotNull { tag -> recognized[tag]?.let { tag to it } }
            .distinctBy { it.second }
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
            """(?is)(?:getElementById\(\s*['"]robotlink['"]\s*\)|querySelector\(\s*['"]#robotlink['"]\s*\))\.innerHTML\s*=\s*['"]([^'"]*)['"]\s*\+\s*(?:['"]{2}\s*\+\s*)?\(\s*['"]([^'"]*)['"]\s*\)((?:\s*\.(?:substring|substr|slice)\(\s*\d+\s*\))+)"""
        ).find(html) ?: return null
        var suffix = match.groupValues[2]
        val operations = Regex("""\.(?:substring|substr|slice)\(\s*(\d+)\s*\)""")
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

    /**
     * Dean Edwards packer uses base 2..62 names. Decode only its literal
     * token dictionary; never execute remote JavaScript. Bounded input and
     * replacement count keep provider content from hanging a parser thread.
     */
    internal fun unpackPacker(script: String): String? {
        val regex = Regex(
            """(?s)eval\(function\(p,a,c,k,e,d\)\{.*?\}\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\s*\.split\('\|'\)"""
        )
        val match = regex.find(script.take(200_000)) ?: return null
        val base = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        if (base !in 2..62 || count !in 1..3000) return null
        val keys = match.groupValues[4].split('|')
        val payload = match.groupValues[1]
            .replace("\\'", "'").replace("\\\\", "\\")
        if (payload.length > 120_000) return null

        fun tokenIndex(token: String): Int? {
            var result = 0
            for (ch in token) {
                val digit = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'z' -> ch - 'a' + 10
                    in 'A'..'Z' -> ch - 'A' + 36
                    else -> return null
                }
                if (digit >= base) return null
                result = result * base + digit
                if (result >= count) return null
            }
            return result
        }
        return Regex("""\b[a-zA-Z0-9_]+\b""").replace(payload) {
            val idx = tokenIndex(it.value)
            if (idx != null && idx < keys.size && keys[idx].isNotBlank())
                keys[idx] else it.value
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
