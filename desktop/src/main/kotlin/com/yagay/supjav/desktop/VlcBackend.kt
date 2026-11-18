package com.yagay.supjav.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yagay.supjav.model.PlaybackSource
import com.yagay.supjav.util.Diagnostics
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * libVLC 3 based desktop playback. VLC needs to be installed on Windows
 * separately; do not bundle a native binary with an unknown license/build.
 */
class VlcBackend : AutoCloseable {
    val component: EmbeddedMediaPlayerComponent? = runCatching {
        if (!NativeDiscovery().discover()) null
        else EmbeddedMediaPlayerComponent()
    }.onFailure { Diagnostics.log("DESKTOP_VLC_UNAVAILABLE type=" + it.javaClass.simpleName) }
        .getOrNull()

    val available get() = component != null
    var playing by mutableStateOf(false)
        private set
    var feedback by mutableStateOf("")
    var brightness by mutableStateOf(0.5f)
        private set

    fun play(source: PlaybackSource): Boolean {
        val c = component ?: return false
        if (!source.url.startsWith("https://") ||
            !source.referer.startsWith("https://")) return false
        return runCatching {
            c.mediaPlayer().media().play(
                source.url,
                ":http-referrer=" + source.referer,
                ":http-user-agent=Mozilla/5.0"
            )
            playing = true
            Diagnostics.log("DESKTOP_PLAY type=" + source.mimeType)
            true
        }.getOrElse {
            Diagnostics.log("DESKTOP_PLAY_FAILED type=" + it.javaClass.simpleName)
            false
        }
    }
    fun toggle() {
        val player = component?.mediaPlayer() ?: return
        runCatching {
            if (playing) player.controls().pause() else player.controls().play()
            playing = !playing
        }
    }
    fun seek(deltaMs: Long) {
        runCatching { component?.mediaPlayer()?.controls()?.skip(deltaMs) }
    }
    fun position() = runCatching {
        component?.mediaPlayer()?.status()?.time() ?: 0L
    }.getOrDefault(0L)
    fun duration() = runCatching {
        component?.mediaPlayer()?.status()?.length() ?: 0L
    }.getOrDefault(0L)
    fun seekTo(ms: Long) {
        runCatching { component?.mediaPlayer()?.controls()?.setTime(ms.coerceAtLeast(0L)) }
    }
    fun speed(rate: Float) {
        runCatching { component?.mediaPlayer()?.controls()?.setRate(rate) }
    }
    fun volumeChange(delta: Float) {
        val player = component?.mediaPlayer() ?: return
        runCatching {
            val current = player.audio().volume().coerceAtLeast(0)
            player.audio().setVolume((current + delta * 100f).roundToInt().coerceIn(0, 100))
            feedback = "音量 " + player.audio().volume() + "%"
        }
    }
    fun brightnessChange(delta: Float) {
        val player = component?.mediaPlayer() ?: return
        brightness = (brightness + delta).coerceIn(0.1f, 1f)
        runCatching {
            player.video().setAdjustVideo(true)
            player.video().setBrightness((brightness * 2f).coerceIn(0.2f, 2f))
            feedback = "画面亮度 " + (brightness * 100).roundToInt() + "%"
        }
    }
    override fun close() {
        runCatching { component?.release() }
    }
}

/**
 * Windows maps one-finger touch to mouse events on the VLC AWT surface.
 * Keep this adapter separate from the desktop view so it can later be
 * replaced by WM_POINTER multi-touch support without changing playback.
 */
class DesktopTouchGestures(
    private val host: Component,
    private val player: VlcBackend,
    private val toggleChrome: () -> Unit
) : MouseAdapter() {
    private var startX = 0f
    private var startY = 0f
    private var dragged = false
    private var side = 0
    private var seekStart = 0L
    private var seekTarget = 0L
    private var lastTap = 0L
    private var lastSide = 0
    private var longPress = false
    private var priorRate = 1f
    private var pendingTap: Timer? = null
    private val holdTimer = Timer(420) {
        if (!dragged && side != 0) {
            longPress = true
            priorRate = 1f
            player.speed(2f)
            player.feedback = "2× 临时倍速"
        }
    }.apply { isRepeats = false }

    private fun coordinates(e: MouseEvent) =
        SwingUtilities.convertPoint(e.component, e.point, host)

    override fun mousePressed(e: MouseEvent) {
        if (e.button != MouseEvent.BUTTON1) return
        val pt = coordinates(e)
        startX = pt.x.toFloat()
        startY = pt.y.toFloat()
        seekStart = player.position()
        seekTarget = seekStart
        dragged = false
        longPress = false
        side = when {
            startX < host.width * 0.38f -> -1
            startX > host.width * 0.62f -> 1
            else -> 0
        }
        if (side != 0) holdTimer.restart()
    }
    override fun mouseDragged(e: MouseEvent) {
        val pt = coordinates(e)
        val dx = pt.x - startX
        val dy = pt.y - startY
        if (abs(dx) + abs(dy) < 12 || longPress) return
        dragged = true
        holdTimer.stop()
        if (abs(dx) > abs(dy) * 1.25) {
            val length = player.duration()
            if (length > 0) {
                seekTarget = (seekStart + dx / host.width.coerceAtLeast(1) * length)
                    .toLong().coerceIn(0, length)
                player.feedback = "进度 " + seekTarget / 1000 + "s"
            }
        } else if (abs(dy) > abs(dx) * 1.25) {
            val change = -dy / host.height.coerceAtLeast(1) * 0.025f
            if (startX < host.width / 2f) player.brightnessChange(change)
            else player.volumeChange(change)
        }
    }
    override fun mouseReleased(e: MouseEvent) {
        holdTimer.stop()
        if (longPress) {
            player.speed(priorRate)
            player.feedback = ""
        } else if (dragged) {
            if (seekTarget != seekStart) player.seekTo(seekTarget)
        } else {
            val now = System.currentTimeMillis()
            if (side != 0 && side == lastSide && now - lastTap < 340) {
                pendingTap?.stop()
                player.seek(side * 10_000L)
                player.feedback = if (side < 0) "−10s" else "+10s"
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
        longPress = false
    }
    fun install() {
        fun attach(node: Component) {
            node.addMouseListener(this)
            node.addMouseMotionListener(this)
            if (node is Container) node.components.forEach(::attach)
        }
        attach(host)
    }
    fun dispose() {
        holdTimer.stop()
        pendingTap?.stop()
    }
}
