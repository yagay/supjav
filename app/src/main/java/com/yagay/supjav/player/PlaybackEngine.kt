package com.yagay.supjav.player

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.yagay.supjav.data.LibraryStore
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.PlaybackSource
import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.util.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlaybackEngine(private val context: Context, private val library: LibraryStore) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    var currentVideo by mutableStateOf<VideoItem?>(null)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkRegistered = false
    private var retryJob: Job? = null
    private var generation = 0L
    private var retries = 0
    private var waitingForConnection = false
    private var lastGoodPosition = 0L
    private var videoQuality = VideoQualityPolicy.DEFAULT

    private data class Variant(
        val group: Tracks.Group, val trackIndex: Int,
        val height: Int, val width: Int, val bitrate: Int
    )

    private fun supportedVariants(): List<Variant> = player.currentTracks.groups
        .asSequence()
        .filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { group ->
            (0 until group.length).asSequence().mapNotNull { index ->
                if (!group.isTrackSupported(index)) return@mapNotNull null
                val format = group.getTrackFormat(index)
                if (format.height <= 0) return@mapNotNull null
                Variant(group, index, format.height, format.width, format.bitrate)
            }
        }.toList()

    fun availableVideoHeights(): List<Int> =
        supportedVariants().map { it.height }.distinct().sortedDescending()

    fun applyVideoQuality(preference: String) {
        videoQuality = VideoQualityPolicy.normalize(preference)
        val variants = supportedVariants()
        val targetHeight = VideoQualityPolicy.targetHeight(
            variants.map { it.height }, videoQuality
        )
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearVideoSizeConstraints()
            .setForceHighestSupportedBitrate(false)
        if (videoQuality == VideoQualityPolicy.HIGHEST && targetHeight == null) {
            // Before the HLS manifest is parsed, prefer the highest bitrate.
            // Once tracks arrive, pin the actual highest supported resolution.
            builder.setForceHighestSupportedBitrate(true)
        } else if (targetHeight != null && variants.map { it.height }.distinct().size > 1) {
            val chosen = variants.filter { it.height == targetHeight }
                .maxWithOrNull(compareBy<Variant>({ it.width }, { it.bitrate }))
            if (chosen != null) builder.setOverrideForType(
                TrackSelectionOverride(chosen.group.mediaTrackGroup, chosen.trackIndex)
            )
        }
        val next = builder.build()
        if (next != player.trackSelectionParameters) player.trackSelectionParameters = next
        Diagnostics.log("VIDEO_QUALITY_NATIVE mode=" + videoQuality +
            " variants=" + variants.map { it.height }.distinct().size +
            " selected=" + (targetHeight ?: 0))
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(net: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                scope.launch {
                    if (waitingForConnection) {
                        waitingForConnection = false
                        scheduleRetry(immediate = true)
                    }
                }
            }
        }
    }

    init {
        videoQuality = VideoQualityPolicy.normalize(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("video_quality", null)
        )
        runCatching {
            network.registerDefaultNetworkCallback(networkCallback)
            networkRegistered = true
        }.onFailure { Diagnostics.log("NETWORK_LISTENER " + it.javaClass.simpleName) }

        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                applyVideoQuality(videoQuality)
            }
            override fun onPlayerError(error: PlaybackException) {
                Diagnostics.log("PLAYER_ERROR " + error.errorCodeName + " " +
                    error.message.orEmpty().take(140))
                val status = generateSequence(error as Throwable?) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode
                val retryable = PlaybackRecoveryPolicy.mayRetry(error.errorCode, status)
                Diagnostics.log("PLAYER_HTTP_RESULT status=" + status +
                    " retryable=" + retryable +
                    " errorCode=" + error.errorCodeName)
                if (retryable) {
                    lastGoodPosition = player.currentPosition.coerceAtLeast(0L)
                    scheduleRetry()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Diagnostics.log("PLAYER_IS_PLAYING " + isPlaying +
                    " positionMs=" + player.currentPosition)
                if (isPlaying) {
                    retries = 0
                    waitingForConnection = false
                    retryJob?.cancel()
                    retryJob = null
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                Diagnostics.log("PLAYER_STATE " + playbackState)
                if (playbackState == Player.STATE_ENDED) savePosition()
            }
        })
    }

    private fun networkValidated(): Boolean = runCatching {
        val active = network.activeNetwork ?: return@runCatching false
        network.getNetworkCapabilities(active)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    private fun scheduleRetry(immediate: Boolean = false) {
        if (currentVideo == null || !player.playWhenReady) return
        if (!networkValidated()) {
            waitingForConnection = true
            Diagnostics.log("PLAYER_WAITING_FOR_NETWORK")
            return
        }
        if (retries >= PlaybackRecoveryPolicy.MAX_RETRIES) {
            Diagnostics.log("PLAYER_RETRY_EXHAUSTED")
            return
        }
        retries++
        val attempt = retries
        val requestGeneration = generation
        retryJob?.cancel()
        retryJob = scope.launch {
            if (!immediate) delay(PlaybackRecoveryPolicy.delayMs(attempt))
            if (requestGeneration != generation || currentVideo == null ||
                !player.playWhenReady) return@launch
            if (!networkValidated()) {
                waitingForConnection = true
                return@launch
            }
            Diagnostics.log("PLAYER_RETRY attempt=" + attempt)
            player.prepare()
            player.seekTo(lastGoodPosition)
            player.play()
        }
    }

    fun play(video: VideoItem, source: PlaybackSource, resume: Boolean = true) {
        generation++
        retryJob?.cancel()
        retryJob = null
        retries = 0
        waitingForConnection = false
        savePosition()
        val origin = runCatching {
            val uri = java.net.URI(source.referer)
            if (uri.scheme == "https" && uri.host != null)
                "https://" + uri.host else null
        }.getOrNull()
        val properties = mutableMapOf(
            "User-Agent" to SupjavSource.UA,
            "Referer" to source.referer
        )
        if (source.includeOrigin && origin != null) properties["Origin"] = origin
        Diagnostics.log("PLAYER_SOURCE host=" + Uri.parse(source.url).host +
            " refererHost=" + Uri.parse(source.referer).host +
            " type=" + source.mimeType +
            " originSet=" + (source.includeOrigin && origin != null))
        val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(properties)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(25000)
        val metadata = MediaMetadata.Builder().setTitle(video.title).apply {
            if (video.coverUrl.startsWith("https://")) setArtworkUri(Uri.parse(video.coverUrl))
        }.build()
        val item = MediaItem.Builder().setUri(source.url).setMimeType(source.mimeType)
            .setMediaId(video.id).setMediaMetadata(metadata).build()
        val resumePosition = if (resume && currentVideo?.id == video.id) player.currentPosition
            else if (resume) library.position(video.id) else 0L
        currentVideo = video
        lastGoodPosition = resumePosition.coerceAtLeast(0L)
        player.setMediaSource(DefaultMediaSourceFactory(factory).createMediaSource(item))
        applyVideoQuality(videoQuality)
        player.prepare()
        if (resumePosition > 0) player.seekTo(resumePosition)
        player.playWhenReady = true
        Diagnostics.log("PLAY id=" + video.id + " mime=" + source.mimeType)
        context.startService(Intent(context, PlaybackService::class.java))
    }

    fun savePosition() {
        val video = currentVideo ?: return
        val position = if (player.duration > 0 &&
            player.currentPosition >= player.duration - 5_000L) 0L
            else player.currentPosition.coerceAtLeast(0L)
        // Avoid synchronous SQLite writes from Player callbacks or Activity.onStop.
        scope.launch(Dispatchers.IO) { library.saveHistory(video, position) }
    }

    fun stop() {
        generation++
        retryJob?.cancel()
        waitingForConnection = false
        savePosition()
        currentVideo = null
        player.stop()
        player.clearMediaItems()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    fun release() {
        generation++
        retryJob?.cancel()
        savePosition()
        if (networkRegistered) runCatching {
            network.unregisterNetworkCallback(networkCallback)
        }
        scope.cancel()
        player.release()
    }
}
