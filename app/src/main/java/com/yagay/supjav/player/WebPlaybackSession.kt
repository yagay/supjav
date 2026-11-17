package com.yagay.supjav.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
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
 * session tracks the actual video, position and pause state.
 * The platform Web media session represents the real HLS.js player, not
 * the (stopped) Media3 ExoPlayer. Only the active engine is published.
 *
 * Only benign progress metadata crosses the WebView bridge; signed media
 * URLs, cookies and player scripts are never sent to persistence or logs.
 */
class WebPlaybackSession(
    private val context: Context,
    private val library: LibraryStore
) {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var view: WeakReference<WebView>? = null
    private var generation: Long = 0
    private var lastSaveTime: Long = 0
    private var lastSystemUpdateMs = 0L
    private var lastSystemPlaying = false
    private var systemSessionStarted = false

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
        val priorPlaying = isPlaying
        isPlaying = playing
        hasProgress = true
        publishSystemMedia(force = priorPlaying != playing)
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
        if (view?.get() === player) {
            view = null
            isPlaying = false
            // The original provider WebView has no controlled media element:
            // do not leave a stale HLS notification when the embedded
            // player has been destroyed or replaced.
            stopSystemMedia()
        }
    }

    private fun publishSystemMedia(force: Boolean = false) {
        if (currentVideo == null || view?.get() == null) return
        val now = SystemClock.elapsedRealtime()
        if (!systemSessionStarted && !isPlaying) return
        if (systemSessionStarted && !force && isPlaying == lastSystemPlaying &&
            now - lastSystemUpdateMs < 5_000L) return
        val intent = Intent(context, WebMediaPlaybackService::class.java)
            .setAction(WebMediaPlaybackService.ACTION_SYNC)
        runCatching {
            if (!systemSessionStarted && Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onSuccess {
            systemSessionStarted = true
            lastSystemUpdateMs = now
            lastSystemPlaying = isPlaying
        }.onFailure {
            Diagnostics.log("WEB_MEDIA_START_FAILED " + it.javaClass.simpleName)
        }
    }

    private fun stopSystemMedia() {
        if (!systemSessionStarted) return
        runCatching {
            context.stopService(Intent(context, WebMediaPlaybackService::class.java))
        }
        systemSessionStarted = false
        lastSystemUpdateMs = 0L
        lastSystemPlaying = false
    }

    private fun javascript(command: String) {
        val player = view?.get() ?: return
        player.post {
            if (view?.get() === player) {
                runCatching { player.evaluateJavascript(command, null) }
            }
        }
    }

    /** Called from the Android system media controls; never passes URLs. */
    fun playFromSystem() = javascript(
        "(function(){var v=document.getElementById('video');if(v)v.play().catch(function(){});})()"
    )
    fun pauseFromSystem() = javascript(
        "(function(){var v=document.getElementById('video');if(v)v.pause();})()"
    )
    fun seekFromSystem(position: Long) {
        val seconds = (position.coerceIn(0L, 86_400_000L) / 1000.0)
        javascript("(function(){var v=document.getElementById('video');" +
            "if(v&&isFinite(v.duration)&&v.duration>0)v.currentTime=" +
            "Math.min(v.duration-0.1,$seconds);})()")
    }
    fun skipFromSystem(seconds: Int) {
        if (seconds !in setOf(-10, 10)) return
        javascript("(function(){var v=document.getElementById('video');" +
            "if(v&&isFinite(v.duration)&&v.duration>0)v.currentTime=" +
            "Math.max(0,Math.min(v.duration-0.1,v.currentTime+$seconds));})()")
    }

    private fun pauseWebView() = pauseFromSystem()

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
        stopSystemMedia()
        Diagnostics.log("WEB_SESSION_END")
    }
}
