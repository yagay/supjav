package com.yagay.supjav.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * Session-scoped brightness override, and regular STREAM_MUSIC volume.
 * Does not modify global system brightness or request WRITE_SETTINGS.
 */
internal class PlayerSystemControls(private val activity: Activity) {
    private val originalBrightness = activity.window.attributes.screenBrightness
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var changedBrightness = false

    fun brightness(): Float {
        val windowValue = activity.window.attributes.screenBrightness
        if (windowValue in 0f..1f) return windowValue
        return (Settings.System.getInt(
            activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128
        ) / 255f).coerceIn(0.01f, 1f)
    }

    fun setBrightness(value: Float): Float {
        val safe = value.coerceIn(0.01f, 1f)
        val attributes = activity.window.attributes
        attributes.screenBrightness = safe
        activity.window.attributes = attributes
        changedBrightness = true
        return safe
    }

    fun volume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max)
            .coerceIn(0f, 1f)
    }

    fun setVolume(value: Float): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val safe = value.coerceIn(0f, 1f)
        // Some devices restrict volume changes while Do Not Disturb is active.
        // Leave playback running if Android rejects an adjustment.
        runCatching {
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                (safe * max).roundToInt().coerceIn(0, max),
                0 // Don't display the system volume HUD over the video.
            )
        }
        return volume()
    }

    fun restoreBrightness() {
        if (!changedBrightness) return
        val attributes = activity.window.attributes
        attributes.screenBrightness = originalBrightness
        activity.window.attributes = attributes
        changedBrightness = false
    }
}
