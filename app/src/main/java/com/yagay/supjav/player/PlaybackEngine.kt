package com.yagay.supjav.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.yagay.supjav.data.LibraryStore
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.PlaybackSource
import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.util.Diagnostics

class PlaybackEngine(private val context: Context, private val library: LibraryStore) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    var currentVideo: VideoItem? = null
        private set

    fun play(video: VideoItem, source: PlaybackSource, resume: Boolean = true) {
        savePosition()
        val properties = mapOf("User-Agent" to SupjavSource.UA, "Referer" to source.referer)
        val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(properties)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(25000)
        val item = MediaItem.Builder().setUri(source.url).setMimeType(source.mimeType)
            .setMediaId(video.id).build()
        val oldPosition = if (resume && currentVideo?.id == video.id) player.currentPosition
            else if (resume) library.position(video.id) else 0L
        currentVideo = video
        player.setMediaSource(DefaultMediaSourceFactory(factory).createMediaSource(item))
        player.prepare()
        if (oldPosition > 0) player.seekTo(oldPosition)
        player.playWhenReady = true
        Diagnostics.log("PLAY id=" + video.id + " mime=" + source.mimeType)
        context.startService(Intent(context, PlaybackService::class.java))
    }

    fun savePosition() {
        val video = currentVideo ?: return
        val position = if (player.duration > 0 && player.currentPosition >= player.duration - 5000) 0L
            else player.currentPosition.coerceAtLeast(0L)
        library.saveHistory(video, position)
    }

    fun stop() { savePosition(); player.stop() }
    fun release() { savePosition(); player.release() }
}
