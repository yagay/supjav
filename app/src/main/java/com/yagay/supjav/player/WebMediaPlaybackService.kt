package com.yagay.supjav.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.yagay.supjav.MainActivity
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.util.Diagnostics

/**
 * Android's native media controls for the already-running HLS.js video.
 *
 * A platform session is deliberately separate from PlaybackService's Media3
 * session: we never inject dummy media into ExoPlayer or falsely claim
 * ExoPlayer is controlling the WebView. Both media cards must not be active
 * at once. Only a real video element's progress/playing events start this
 * service. This does not guarantee OS-level background WebView rendering.
 */
class WebMediaPlaybackService : Service() {
    companion object {
        const val ACTION_SYNC = "com.yagay.supjav.webmedia.SYNC"
        private const val ACTION_PLAY = "com.yagay.supjav.webmedia.PLAY"
        private const val ACTION_PAUSE = "com.yagay.supjav.webmedia.PAUSE"
        private const val ACTION_REWIND = "com.yagay.supjav.webmedia.REWIND"
        private const val ACTION_FORWARD = "com.yagay.supjav.webmedia.FORWARD"
        private const val CHANNEL = "supjav_web_hls"
        private const val NOTIFICATION = 2202
    }

    private lateinit var app: SupjavApp
    private lateinit var session: MediaSession
    private lateinit var notifications: NotificationManager
    private var foreground = false
    private var foregroundHandshake = false

    override fun onCreate() {
        super.onCreate()
        app = application as SupjavApp
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Supjav HLS 播放", NotificationManager.IMPORTANCE_LOW)
        )
        session = MediaSession(this, "SupjavWebHls").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = app.webPlayback.playFromSystem()
                override fun onPause() = app.webPlayback.pauseFromSystem()
                override fun onSeekTo(pos: Long) = app.webPlayback.seekFromSystem(pos)
                override fun onFastForward() = app.webPlayback.skipFromSystem(10)
                override fun onRewind() = app.webPlayback.skipFromSystem(-10)
                // STOP must not orphan a still-visible HLS element. A full
                // session close belongs to UI navigation or source handoff.
                override fun onStop() = app.webPlayback.pauseFromSystem()
            }, Handler(Looper.getMainLooper()))
            isActive = true
        }
        Diagnostics.log("WEB_MEDIA_SESSION_CREATED")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> app.webPlayback.playFromSystem()
            ACTION_PAUSE -> app.webPlayback.pauseFromSystem()
            ACTION_REWIND -> app.webPlayback.skipFromSystem(-10)
            ACTION_FORWARD -> app.webPlayback.skipFromSystem(10)
        }
        if (app.webPlayback.currentVideo == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun button(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, WebMediaPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun refresh() {
        val video = app.webPlayback.currentVideo ?: return
        val playing = app.webPlayback.isPlaying
        val position = app.webPlayback.positionMs.coerceAtLeast(0L)
        val length = app.webPlayback.durationMs.coerceAtLeast(0L)
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, video.title.take(150))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Supjav · HLS")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, length)
        if (video.coverUrl.startsWith("https://")) {
            // Expose only the public cover link, never a signed media URL.
            metadata.putString(MediaMetadata.METADATA_KEY_ART_URI, video.coverUrl)
        }
        session.setMetadata(metadata.build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                position, if (playing) 1f else 0f, SystemClock.elapsedRealtime())
            .build())
        val activityIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(video.title.take(100))
            .setContentText(if (playing) "HLS 正在播放" else "HLS 已暂停")
            .setContentIntent(activityIntent)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_rew, "快退10秒", button(ACTION_REWIND, 11)).build())
            .addAction(Notification.Action.Builder(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "播放",
                button(if (playing) ACTION_PAUSE else ACTION_PLAY, 12)).build())
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_ff, "快进10秒", button(ACTION_FORWARD, 13)).build())
            .setStyle(Notification.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .build()
        // startForegroundService() may race with a rapid user pause. On the
        // first command we must fulfil Android's foreground-start contract
        // even if playback has already paused by the time the service runs.
        if (playing || !foregroundHandshake) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION, notification)
            }
            foreground = true
        }
        foregroundHandshake = true
        if (!playing) {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_DETACH)
                foreground = false
            }
            notifications.notify(NOTIFICATION, notification)
        }
    }

    override fun onDestroy() {
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(NOTIFICATION)
        session.isActive = false
        session.release()
        Diagnostics.log("WEB_MEDIA_SESSION_DESTROYED")
        super.onDestroy()
    }
}
