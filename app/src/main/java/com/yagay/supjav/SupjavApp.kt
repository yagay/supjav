package com.yagay.supjav

import android.app.Application
import com.yagay.supjav.data.LibraryStore
import com.yagay.supjav.data.PlaybackResolver
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.player.PlaybackEngine
import com.yagay.supjav.data.FeedCache
import com.yagay.supjav.data.SearchStore
import com.yagay.supjav.data.CategoryStore
import com.yagay.supjav.util.Diagnostics

class SupjavApp : Application() {
    val source by lazy { SupjavSource() }
    val resolver by lazy { PlaybackResolver(source) }
    val library by lazy { LibraryStore(this) }
    val feedCache by lazy { FeedCache() }
    val searchStore by lazy { SearchStore(this) }
    val categoryStore by lazy { CategoryStore(this) }
    val playerEngine by lazy { PlaybackEngine(this, library) }
    val preferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(this)
    }
}
