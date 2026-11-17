package com.yagay.supjav.player

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yagay.supjav.data.LibraryStore
import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.util.Diagnostics
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Web HLS playback has its own renderer/network stack, but one app-level
 * session tracks the actual video, position and pause state. It MUST NOT
 * pretend to be the Media3 player or claim an Android media notification.
 *
 * Only benign progress metadata crosses the WebView bridge; signed media
 * URLs, cookies and player scripts are never sent to persistence or logs.
 */
class WebPlaybackSession(private val library: LibraryStore) {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var view: WeakReference<WebView>? = null
    private var generation: Long = 0
    private var lastSaveTime: Long = 0

    var currentVideo by mutableStateOf<VideoItem?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    private var hasProgress = false

    fun begin(video: VideoItem, startPositionMs: Long = 0L) {
        if (currentVideo?.id == video.id) return
        saveProgress()
        generation++
        currentVideo = video
        isPlaying = false
        positionMs = startPositionMs.coerceIn(0L, 86_400_000L)
        durationMs = 0
        hasProgress = positionMs > 0L
        lastSaveTime = 0
        Diagnostics.log("WEB_SESSION_BEGIN video=" + video.id)
    }

    suspend fun restorePosition(): Long {
        val video = currentVideo ?: return 0
        val currentGeneration = generation
        if (hasProgress) return positionMs
        val saved = withContext(Dispatchers.IO) { library.position(video.id) }
        return if (currentGeneration == generation) saved else 0
    }

    fun update(position: Long, duration: Long, playing: Boolean) {
        val video = currentVideo ?: return
        if (position < 0 || duration < 0) return
        val validPosition = if (duration > 0) position.coerceIn(0, duration) else position
        positionMs = validPosition
        durationMs = duration
        isPlaying = playing
        hasProgress = true
        if (android.os.SystemClock.elapsedRealtime() - lastSaveTime >= 10_000L) {
            lastSaveTime = android.os.SystemClock.elapsedRealtime()
            val snapshot = if (duration > 0 && duration - validPosition < 5_000L) 0L
                else validPosition
            val currentGeneration = generation
            io.launch {
                if (generation == currentGeneration) library.saveHistory(video, snapshot)
            }
        }
    }

    fun attach(player: WebView) {
        view = WeakReference(player)
    }

    fun detach(player: WebView) {
        if (view?.get() === player) view = null
    }

    private fun pauseWebView() {
        val webView = view?.get() ?: return
        webView.post {
            runCatching {
                webView.evaluateJavascript(
                    "(function(){var v=document.getElementById('video');if(v)v.pause();})()",
                    null
                )
            }
        }
    }

    fun pauseForBackground() {
        if (isPlaying) pauseWebView()
    }

    fun saveProgress() {
        val video = currentVideo ?: return
        if (!hasProgress) return
        val snapshot = if (durationMs > 0 && durationMs - positionMs < 5_000L) 0L
            else positionMs
        io.launch { library.saveHistory(video, snapshot) }
    }

    fun close() {
        pauseWebView()
        saveProgress()
        generation++
        currentVideo = null
        isPlaying = false
        hasProgress = false
        positionMs = 0
        durationMs = 0
        view = null
        Diagnostics.log("WEB_SESSION_END")
    }
}
