package com.yagay.supjav.data

import java.net.URI
import org.jsoup.Jsoup

/** Options actually advertised by the selected MissAV catalogue page. */
internal object MissavPageNavigation {
    data class Choice(val label: String, val value: String)
    data class Controls(
        val filters: List<Choice> = emptyList(),
        val sorts: List<Choice> = emptyList()
    )

    fun isDirectoryIndex(path: String): Boolean {
        val clean = runCatching { URI(path).path }.getOrDefault(path).trimEnd('/')
        return Regex("^/(?:dm[0-9]+/)?(?:(?:en|ja|cn|ko|zh|tw)/)?(?:genres|makers|actresses(?:/ranking)?|actors|tags|series)$")
            .matches(clean)
    }

    fun parseControls(html: String, pageUrl: String): Controls {
        val current = runCatching { URI(pageUrl) }.getOrNull() ?: return Controls()
        val doc = Jsoup.parse(html, pageUrl)
        fun choices(key: String): List<Choice> {
            val found = LinkedHashMap<String, Choice>()
            for (anchor in doc.select("a[href]")) {
                val target = runCatching { URI(anchor.absUrl("href")) }.getOrNull() ?: continue
                if (target.scheme != "https" || target.host != current.host ||
                    target.path.trimEnd('/') != current.path.trimEnd('/')) continue
                val query = target.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                    val pieces = pair.split('=', limit = 2)
                    if (pieces.size == 2) pieces[0] to pieces[1] else null
                }.toMap()
                val value = query[key] ?: continue
                if (!value.matches(Regex("[a-zA-Z0-9_-]{1,50}"))) continue
                val label = anchor.text().trim().replace(Regex("\\s+"), " ").take(65)
                if (label.isNotBlank()) found.putIfAbsent(value, Choice(label, value))
            }
            return found.values.toList()
        }
        val filters = choices("filters")
        val sorts = choices("sort")
        return Controls(
            if (filters.isEmpty()) emptyList() else listOf(Choice("All", "")) + filters,
            if (sorts.isEmpty()) emptyList() else listOf(Choice("Default", "")) + sorts
        )
    }

    fun queryPath(path: String, filter: String, sort: String): String {
        val pure = path.substringBefore('?').trimEnd('/')
        require(pure.startsWith("/") && !pure.contains("//") && !pure.contains(".."))
        require(filter.isEmpty() || filter.matches(Regex("[a-zA-Z0-9_-]{1,50}")))
        require(sort.isEmpty() || sort.matches(Regex("[a-zA-Z0-9_-]{1,50}")))
        val params = listOfNotNull(
            filter.takeIf { it.isNotEmpty() }?.let { "filters=" + it },
            sort.takeIf { it.isNotEmpty() }?.let { "sort=" + it }
        )
        return pure + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }
}
