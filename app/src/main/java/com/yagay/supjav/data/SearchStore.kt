package com.yagay.supjav.data

import android.content.Context
import org.json.JSONArray

/** User-owned local search history; no inferred site-side hot-search API. */
class SearchStore(context: Context) {
    private val preferences = context.getSharedPreferences("supjav_search", Context.MODE_PRIVATE)

    @Synchronized fun recent(): List<String> {
        val json = runCatching { JSONArray(preferences.getString("terms", "[]")) }
            .getOrElse { JSONArray() }
        return (0 until json.length()).mapNotNull { index ->
            json.optString(index).trim().takeIf { it.isNotEmpty() }
        }
    }

    @Synchronized fun add(text: String) {
        val term = text.trim().take(100)
        if (term.isBlank()) return
        val terms = (listOf(term) + recent().filterNot { it.equals(term, ignoreCase = true) })
            .take(20)
        preferences.edit().putString("terms", JSONArray(terms).toString()).apply()
    }

    @Synchronized fun remove(text: String) {
        preferences.edit().putString("terms", JSONArray(recent().filterNot { it == text }).toString()).apply()
    }

    @Synchronized fun clear() {
        preferences.edit().remove("terms").apply()
    }
}
