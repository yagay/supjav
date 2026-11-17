package com.yagay.supjav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.model.VideoItem

/**
 * Lightweight in-process LRU page cache inspired by Hongguo's navigation policy:
 * switching tabs or opening details must not trigger redundant network loads.
 * Keep a bounded number of feeds; saved history/favorites remain in SQLite.
 */
class FeedCache {
    private data class Key(val kind: FeedKind, val value: String, val epoch: Int)
    private val cache = LinkedHashMap<Key, FeedPageState>(12, 0.75f, true)

    @Synchronized
    fun getOrCreate(kind: FeedKind, value: String, epoch: Int): FeedPageState {
        val key = Key(kind, value, epoch)
        cache[key]?.let { return it }
        if (cache.size >= 12) {
            val oldest = cache.keys.firstOrNull()
            if (oldest != null) cache.remove(oldest)
        }
        return FeedPageState().also { cache[key] = it }
    }
}

class FeedPageState {
    val items = mutableStateListOf<VideoItem>()
    var page by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var pendingRefresh by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var nextPageUrl by mutableStateOf<String?>(null)
    var hasPager by mutableStateOf(false)
    var lastRefreshKey by mutableIntStateOf(0)
    var endReached by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var duplicatePageStreak by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var firstVisibleIndex = 0
    var firstVisibleOffset = 0
}
