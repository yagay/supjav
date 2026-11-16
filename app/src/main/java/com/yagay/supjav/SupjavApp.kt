package com.yagay.supjav

import android.app.Application
import com.yagay.supjav.data.LibraryStore
import com.yagay.supjav.data.PlaybackResolver
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.player.PlaybackEngine
import com.yagay.supjav.data.FeedCache

class SupjavApp : Application() {
    val source by lazy { SupjavSource() }
    val resolver by lazy { PlaybackResolver(source) }
    val library by lazy { LibraryStore(this) }
    val feedCache by lazy { FeedCache() }
    val playerEngine by lazy { PlaybackEngine(this, library) }
    val preferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
}
