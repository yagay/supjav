package com.yagay.supjav.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yagay.supjav.model.PlaybackSource
import com.yagay.supjav.util.Diagnostics
import java.awt.Component

enum class DesktopEngineChoice(val title: String) {
    AUTO("自动"), MPV("MPV"), VLC("VLC")
}

/**
 * Keep the native engine independent from the browse/page/queue UI.
 * MPV is preferred when its runtime is installed. VLCJ is the secondary
 * native path; unsupported website-only providers remain WebView2.
 */
class DesktopPlayerGroup(
    private val mpv: MpvBackend = MpvBackend(),
    private val vlc: VlcBackend = VlcBackend()
) : DesktopNativePlayer {
    var preference by mutableStateOf(DesktopEngineChoice.AUTO)
        private set
    private var selected by mutableStateOf<DesktopNativePlayer>(
        if (mpv.available) mpv else vlc
    )
    override val component: Component? get() = selected.component
    override val available get() = mpv.available || vlc.available
    override val engineName get() = selected.engineName
    override val phase get() = selected.phase
    override val playing get() = selected.playing
    override val errorMessage get() = selected.errorMessage
    override var feedback: String
        get() = selected.feedback
        set(value) { selected.feedback = value }
    override val brightness get() = selected.brightness
    override val playbackRate get() = selected.playbackRate

    val availableEngines: String get() = buildList {
        if (mpv.available) add("MPV")
        if (vlc.available) add("VLC")
    }.joinToString(" / ").ifBlank { "未检测到原生播放内核" }

    fun choose(choice: DesktopEngineChoice) {
        preference = choice
        Diagnostics.log("DESKTOP_ENGINE_PREFERENCE value=" + choice.name)
    }

    private fun candidates(): List<DesktopNativePlayer> {
        val ordered = when (preference) {
            DesktopEngineChoice.AUTO, DesktopEngineChoice.MPV -> listOf(mpv, vlc)
            DesktopEngineChoice.VLC -> listOf(vlc, mpv)
        }
        return ordered.filter { it.available }
    }

    override suspend fun playWhenSurfaceReady(source: PlaybackSource): Boolean {
        val engines = candidates()
        for (engine in engines) {
            if (selected !== engine) {
                selected.stop()
                selected = engine
            }
            Diagnostics.log("DESKTOP_ENGINE_ATTEMPT engine=" + engine.engineName)
            if (engine.playWhenSurfaceReady(source)) {
                Diagnostics.log("DESKTOP_ENGINE_ACCEPTED engine=" + engine.engineName)
                return true
            }
            Diagnostics.log("DESKTOP_ENGINE_REJECTED engine=" + engine.engineName)
        }
        return false
    }
    override fun stop() {
        mpv.stop()
        vlc.stop()
    }
    override fun toggle() = selected.toggle()
    override fun seek(deltaMs: Long) = selected.seek(deltaMs)
    override fun position(): Long = selected.position()
    override fun duration(): Long = selected.duration()
    override fun seekTo(ms: Long) = selected.seekTo(ms)
    override fun speed(rate: Float) = selected.speed(rate)
    override fun volumePercent(): Int = selected.volumePercent()
    override fun setVolumePercent(value: Int) = selected.setVolumePercent(value)
    override fun updateBrightness(value: Float) = selected.updateBrightness(value)
    override fun close() {
        mpv.close()
        vlc.close()
    }
}
