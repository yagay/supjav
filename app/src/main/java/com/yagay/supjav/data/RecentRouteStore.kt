package com.yagay.supjav.data

import android.content.Context
import com.yagay.supjav.model.VideoItem
import java.net.URI
import org.json.JSONObject

/**
 * Persist only public work metadata, never signed media URLs or cookies.
 * After a system kill, reopen the detail page with playback still stopped.
 */
class RecentRouteStore(context: Context) {
    private val prefs = context.getSharedPreferences("recent_route", Context.MODE_PRIVATE)
    private val idPath = Regex("^/(\\d+)\\.html$")

    private fun trusted(video: VideoItem): Boolean = runCatching {
        val uri = URI(video.detailUrl)
        uri.scheme == "https" && uri.host in setOf("supjav.com", "www.supjav.com") &&
            uri.userInfo == null && idPath.matchEntire(uri.path)?.groupValues?.get(1) == video.id
    }.getOrDefault(false)

    fun save(video: VideoItem?) {
        if (video == null) {
            prefs.edit().remove("active").apply()
            return
        }
        if (!trusted(video)) return
        val json = JSONObject().apply {
            put("id", video.id)
            put("title", video.title.take(250))
            put("cover", video.coverUrl.take(2000))
            put("url", video.detailUrl)
            put("code", video.code.take(48))
        }
        prefs.edit().putString("active", json.toString()).apply()
    }

    fun restore(): VideoItem? = runCatching {
        val text = prefs.getString("active", null) ?: return@runCatching null
        val json = JSONObject(text)
        val video = VideoItem(
            id = json.getString("id"),
            title = json.getString("title"),
            coverUrl = json.optString("cover"),
            detailUrl = json.getString("url"),
            code = json.optString("code")
        )
        video.takeIf(::trusted)
    }.getOrNull()
}
