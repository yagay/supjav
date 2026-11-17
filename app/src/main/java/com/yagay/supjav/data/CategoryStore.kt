package com.yagay.supjav.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.yagay.supjav.model.FeedKind

/** Offline snapshot of categories actually discovered on Supjav and user-pinned shortcuts. */
class CategoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("category_directory", Context.MODE_PRIVATE)

    @Synchronized fun snapshot(): List<SupjavSource.BrowseOption> {
        val list = runCatching { JSONArray(prefs.getString("observed_categories", "[]")) }
            .getOrElse { JSONArray() }
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val kind = runCatching { FeedKind.valueOf(item.optString("kind")) }.getOrNull()
                ?: return@mapNotNull null
            if (kind !in setOf(FeedKind.CATEGORY, FeedKind.TAG, FeedKind.ACTOR, FeedKind.MAKER))
                return@mapNotNull null
            val path = item.optString("path")
            if (!path.startsWith("/") || path.length > 250) return@mapNotNull null
            SupjavSource.BrowseOption(
                kind = kind,
                value = item.optString("value"),
                label = item.optString("label").take(45),
                path = path
            )
        }
    }

    @Synchronized fun save(options: List<SupjavSource.BrowseOption>) {
        if (options.isEmpty()) return
        val array = JSONArray()
        (options + snapshot()).distinctBy { it.path }.take(500).forEach { item ->
            array.put(JSONObject().apply {
                put("kind", item.kind.name)
                put("value", item.value)
                put("label", item.label)
                put("path", item.path)
            })
        }
        prefs.edit().putString("observed_categories", array.toString()).apply()
    }

    private fun strings(key: String): List<String> {
        val array = runCatching { JSONArray(prefs.getString(key, "[]")) }
            .getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            array.optString(i).takeIf { it.startsWith("/") }
        }
    }
    fun pins(): List<String> = strings("pins")
    fun recent(): List<String> = strings("recent")

    @Synchronized fun togglePin(path: String): Boolean {
        val items = pins()
        val pinned = !items.contains(path)
        val next = if (pinned) (listOf(path) + items).distinct().take(35)
            else items.filterNot { it == path }
        prefs.edit().putString("pins", JSONArray(next).toString()).apply()
        return pinned
    }

    @Synchronized fun markVisited(path: String) {
        val list = (listOf(path) + recent()).distinct().take(20)
        prefs.edit().putString("recent", JSONArray(list).toString()).apply()
    }
}
