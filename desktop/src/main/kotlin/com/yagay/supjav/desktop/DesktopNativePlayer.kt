package com.yagay.supjav.desktop

import com.yagay.supjav.model.PlaybackSource
import java.awt.Component

/** Same controls regardless of whether libmpv or VLCJ owns the video surface. */
interface DesktopNativePlayer : AutoCloseable {
    val component: Component?
    val available: Boolean
    val engineName: String
    val phase: PlayerPhase
    val playing: Boolean
    val errorMessage: String?
    var feedback: String
    val brightness: Float
    val playbackRate: Float

    suspend fun playWhenSurfaceReady(source: PlaybackSource): Boolean
    fun stop()
    fun toggle()
    fun seek(deltaMs: Long)
    fun position(): Long
    fun duration(): Long
    fun seekTo(ms: Long)
    fun speed(rate: Float)
    fun volumePercent(): Int
    fun setVolumePercent(value: Int)
    fun updateBrightness(value: Float)
}
