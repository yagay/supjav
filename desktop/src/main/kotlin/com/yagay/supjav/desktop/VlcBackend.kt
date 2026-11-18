package com.yagay.supjav.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yagay.supjav.model.PlaybackSource
import com.yagay.supjav.util.Diagnostics
import java.awt.Component
import java.awt.Container
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PlayerPhase {
    IDLE, OPENING, BUFFERING, PLAYING, PAUSED, FINISHED, ERROR, STOPPED
}

/** VLCJ player with real native playback events, never optimistic playing flags. */
class VlcBackend(private val discoverNative: Boolean = true) : AutoCloseable {
    val component: EmbeddedMediaPlayerComponent? = runCatching {
        if (!discoverNative || !NativeDiscovery().discover()) null
        else EmbeddedMediaPlayerComponent()
    }.onFailure {
        Diagnostics.log("DESKTOP_VLC_UNAVAILABLE type=" + it.javaClass.simpleName)
    }.getOrNull()

    val available get() = component != null
    var phase by mutableStateOf(PlayerPhase.IDLE)
        private set
    val playing get() = phase == PlayerPhase.PLAYING || phase == PlayerPhase.BUFFERING
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var feedback by mutableStateOf("")
    var brightness by mutableStateOf(0.5f)
        private set
    var playbackRate by mutableStateOf(1f)
        private set

    private fun dispatch(update: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) update()
        else SwingUtilities.invokeLater(update)
    }

    private val listener = object : MediaPlayerEventAdapter() {
        override fun opening(mediaPlayer: MediaPlayer) {
            dispatch { phase = PlayerPhase.OPENING }
        }
        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            dispatch {
                if (phase != PlayerPhase.ERROR && phase != PlayerPhase.PAUSED) {
                    phase = if (newCache < 99.9f) PlayerPhase.BUFFERING
                        else PlayerPhase.PLAYING
                }
            }
        }
        override fun playing(mediaPlayer: MediaPlayer) {
            dispatch {
                phase = PlayerPhase.PLAYING
                errorMessage = null
            }
        }
        override fun paused(mediaPlayer: MediaPlayer) {
            dispatch { phase = PlayerPhase.PAUSED }
        }
        override fun finished(mediaPlayer: MediaPlayer) {
            dispatch { phase = PlayerPhase.FINISHED }
        }
        override fun stopped(mediaPlayer: MediaPlayer) {
            dispatch { phase = PlayerPhase.STOPPED }
        }
        override fun error(mediaPlayer: MediaPlayer) {
            dispatch {
                phase = PlayerPhase.ERROR
                errorMessage = "VLC 播放失败。该线路可能需要浏览器环境，请尝试其他线路。"
                Diagnostics.log("DESKTOP_VLC_NATIVE_ERROR")
            }
        }
    }

    init {
        component?.mediaPlayer()?.events()?.addMediaPlayerEventListener(listener)
    }

    /**
     * A successful call means VLC accepted the play request, NOT that the
     * network media began playing. Native events will report the real state.
     * LibVLC's standard HTTP input does not expose arbitrary custom headers;
     * browser-only providers must use a browser fallback.
     */
    fun play(source: PlaybackSource): Boolean {
        val c = component ?: run {
            errorMessage = "没有检测到 64 位 VLC 3.x"
            return false
        }
        if (!source.url.startsWith("https://") ||
            !source.referer.startsWith("https://")) return false
        return runCatching {
            errorMessage = null
            phase = PlayerPhase.OPENING
            val accepted = c.mediaPlayer().media().play(
                source.url,
                ":http-referrer=" + source.referer,
                ":http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            if (!accepted) {
                phase = PlayerPhase.ERROR
                errorMessage = "VLC 未接受此播放源"
            }
            Diagnostics.log("DESKTOP_PLAY_REQUEST type=" + source.mimeType +
                " accepted=" + accepted + " originHint=" + source.includeOrigin)
            accepted
        }.getOrElse {
            phase = PlayerPhase.ERROR
            errorMessage = "打开播放源失败：" + it.javaClass.simpleName
            Diagnostics.log("DESKTOP_PLAY_FAILED type=" + it.javaClass.simpleName)
            false
        }
    }

    fun toggle() {
        val media = component?.mediaPlayer() ?: return
        runCatching {
            if (phase == PlayerPhase.PLAYING || phase == PlayerPhase.BUFFERING)
                media.controls().pause()
            else media.controls().play()
            // The event listener, not this click, owns the phase.
        }
    }
    fun seek(deltaMs: Long) {
        runCatching { component?.mediaPlayer()?.controls()?.skipTime(deltaMs) }
    }
    fun position(): Long = runCatching {
        component?.mediaPlayer()?.status()?.time() ?: 0L
    }.getOrDefault(0L)
    fun duration(): Long = runCatching {
        component?.mediaPlayer()?.status()?.length() ?: 0L
    }.getOrDefault(0L)
    fun seekTo(ms: Long) {
        runCatching { component?.mediaPlayer()?.controls()?.setTime(ms.coerceAtLeast(0L)) }
    }
    fun speed(rate: Float) {
        if (!rate.isFinite() || rate !in 0.5f..4f) return
        runCatching {
            if (component?.mediaPlayer()?.controls()?.setRate(rate) == true)
                playbackRate = rate
        }
    }
    fun volumePercent(): Int = runCatching {
        component?.mediaPlayer()?.audio()?.volume()?.coerceIn(0, 100) ?: 50
    }.getOrDefault(50)
    fun setVolumePercent(value: Int) {
        runCatching {
            component?.mediaPlayer()?.audio()?.setVolume(value.coerceIn(0, 100))
            feedback = "音量 " + volumePercent() + "%"
        }
    }
    fun updateBrightness(value: Float) {
        val normalized = value.coerceIn(0.1f, 1f)
        runCatching {
            val video = component?.mediaPlayer()?.video() ?: return
            video.setAdjustVideo(true)
            video.setBrightness(normalized * 2f)
            brightness = normalized
            feedback = "画面亮度 " + (brightness * 100).roundToInt() + "%"
        }
    }
    fun volumeChange(delta: Float) {
        setVolumePercent((volumePercent() + delta * 100).roundToInt())
    }
    fun brightnessChange(delta: Float) {
        updateBrightness(brightness + delta)
    }
    override fun close() {
        runCatching {
            component?.mediaPlayer()?.events()?.removeMediaPlayerEventListener(listener)
            component?.release()
        }
    }
}

/**
 * Single pointer input shared by Windows touchscreen and AWT mouse surface.
 * Gesture values are computed from press-time baselines, never accumulated
 * per callback. A host-specific WM_POINTER adapter can replace this later.
 */
class DesktopTouchGestures(
    private val host: Component,
    private val player: VlcBackend,
    private val toggleChrome: () -> Unit
) : MouseAdapter() {
    private enum class Gesture { NONE, SEEK, VOLUME, BRIGHTNESS }
    private var mode = Gesture.NONE
    private var startX = 0f
    private var startY = 0f
    private var initialBrightness = 0.5f
    private var initialVolume = 50
    private var seekStart = 0L
    private var seekTarget = 0L
    private var side = 0
    private var lastTap = 0L
    private var lastSide = 0
    private var holding = false
    private var beforeHoldRate = 1f
    private var pressed = false
    private var pendingTap: Timer? = null
    private var feedbackTimer: Timer? = null
    private val registered = mutableListOf<Component>()
    private val containers = mutableListOf<Container>()

    private val holdTimer = Timer(420) {
        if (pressed && mode == Gesture.NONE && side != 0) {
            holding = true
            beforeHoldRate = player.playbackRate
            player.speed(2f)
            player.feedback = "2× 临时倍速"
        }
    }.apply { isRepeats = false }

    private val containerListener = object : ContainerAdapter() {
        override fun componentAdded(e: ContainerEvent) {
            attach(e.child)
        }
    }

    private fun coordinates(e: MouseEvent) =
        SwingUtilities.convertPoint(e.component, e.point, host)

    override fun mousePressed(e: MouseEvent) {
        if (e.button != MouseEvent.BUTTON1) return
        pressed = true
        holding = false
        mode = Gesture.NONE
        val pt = coordinates(e)
        startX = pt.x.toFloat()
        startY = pt.y.toFloat()
        initialBrightness = player.brightness
        initialVolume = player.volumePercent()
        seekStart = player.position()
        seekTarget = seekStart
        side = when {
            startX < host.width * .38f -> -1
            startX > host.width * .62f -> 1
            else -> 0
        }
        holdTimer.stop()
        if (side != 0) holdTimer.restart()
    }

    override fun mouseDragged(e: MouseEvent) {
        if (!pressed || holding) return
        val pt = coordinates(e)
        val dx = (pt.x - startX).toFloat()
        val dy = (pt.y - startY).toFloat()
        if (mode == Gesture.NONE) {
            if (abs(dx) + abs(dy) < 12f) return
            holdTimer.stop()
            mode = if (abs(dx) > abs(dy) * 1.25f) Gesture.SEEK
                else if (abs(dy) > abs(dx) * 1.25f) {
                    if (startX < host.width / 2f) Gesture.BRIGHTNESS
                    else Gesture.VOLUME
                } else return
        }
        when (mode) {
            Gesture.SEEK -> {
                val duration = player.duration()
                if (duration > 0) {
                    seekTarget = DesktopGestureMath.seekTarget(
                        seekStart, dx, host.width, duration
                    )
                    player.feedback = "进度 " + seekTarget / 1000 + "s"
                }
            }
            Gesture.BRIGHTNESS -> {
                player.updateBrightness(DesktopGestureMath.brightness(
                    initialBrightness, dy, host.height
                ))
            }
            Gesture.VOLUME -> {
                player.setVolumePercent(DesktopGestureMath.volume(
                    initialVolume, dy, host.height
                ))
            }
            Gesture.NONE -> Unit
        }
    }

    private fun stopHolding() {
        if (holding) {
            player.speed(beforeHoldRate)
            holding = false
        }
    }

    override fun mouseReleased(e: MouseEvent) {
        if (!pressed) return
        pressed = false
        holdTimer.stop()
        val wasHolding = holding
        stopHolding()
        when {
            wasHolding -> player.feedback = ""
            mode == Gesture.SEEK -> {
                if (seekTarget != seekStart) player.seekTo(seekTarget)
                scheduleFeedbackClear()
            }
            mode != Gesture.NONE -> scheduleFeedbackClear()
            else -> {
                val now = System.currentTimeMillis()
                if (side != 0 && side == lastSide && now - lastTap < 340) {
                    pendingTap?.stop()
                    player.seek(side * 10_000L)
                    player.feedback = if (side < 0) "−10s" else "+10s"
                    scheduleFeedbackClear()
                    lastTap = 0
                } else {
                    lastTap = now
                    lastSide = side
                    pendingTap?.stop()
                    pendingTap = Timer(330) { toggleChrome() }.apply {
                        isRepeats = false
                        start()
                    }
                }
            }
        }
        mode = Gesture.NONE
    }
    private fun scheduleFeedbackClear() {
        feedbackTimer?.stop()
        feedbackTimer = Timer(900) { player.feedback = "" }.apply {
            isRepeats = false
            start()
        }
    }

    private fun attach(node: Component) {
        if (registered.any { it === node }) return
        node.addMouseListener(this)
        node.addMouseMotionListener(this)
        registered.add(node)
        if (node is Container) {
            node.addContainerListener(containerListener)
            containers.add(node)
            node.components.forEach(::attach)
        }
    }
    fun install() = attach(host)
    fun dispose() {
        pressed = false
        holdTimer.stop()
        pendingTap?.stop()
        feedbackTimer?.stop()
        stopHolding()
        registered.forEach {
            it.removeMouseListener(this)
            it.removeMouseMotionListener(this)
        }
        containers.forEach { it.removeContainerListener(containerListener) }
        registered.clear()
        containers.clear()
    }
}
