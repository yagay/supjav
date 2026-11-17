package com.yagay.supjav

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.yagay.supjav.data.LibraryStore
import com.yagay.supjav.data.PlaybackResolver
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.player.PlaybackEngine
import com.yagay.supjav.player.WebPlaybackSession
import com.yagay.supjav.data.FeedCache
import com.yagay.supjav.data.SearchStore
import com.yagay.supjav.data.CategoryStore
import com.yagay.supjav.data.SiteCatalogRepository
import com.yagay.supjav.data.RecentRouteStore
import com.yagay.supjav.player.VideoQualityPolicy
import com.yagay.supjav.util.Diagnostics

class SupjavApp : Application() {
    val source by lazy { SupjavSource() }
    val resolver by lazy { PlaybackResolver(source) }
    val library by lazy { LibraryStore(this) }
    val feedCache by lazy { FeedCache() }
    val searchStore by lazy { SearchStore(this) }
    val categoryStore by lazy { CategoryStore(this) }
    val catalog by lazy { SiteCatalogRepository(source, categoryStore) }
    val recentRoute by lazy { RecentRouteStore(this) }
    var videoQuality by androidx.compose.runtime.mutableStateOf(VideoQualityPolicy.DEFAULT)
        private set

    fun setVideoQuality(preference: String) {
        val normalized = VideoQualityPolicy.normalize(preference)
        videoQuality = normalized
        preferences.edit().putString("video_quality", normalized).apply()
        playerEngine.applyVideoQuality(normalized)
    }
    var posterColumns by androidx.compose.runtime.mutableIntStateOf(-1)
        private set
    fun updatePosterColumns(columns: Int) {
        require(columns in listOf(0, 2, 3, 4))
        posterColumns = columns
        preferences.edit().putInt("poster_columns", columns).apply()
    }
    val playerEngine by lazy { PlaybackEngine(this, library) }
    val webPlayback by lazy { WebPlaybackSession(this, library) }
    val preferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(this)
        posterColumns = preferences.getInt("poster_columns", 0)
        videoQuality = VideoQualityPolicy.normalize(preferences.getString("video_quality", null))
    }
}
