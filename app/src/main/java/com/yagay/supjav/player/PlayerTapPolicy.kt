package com.yagay.supjav.player

/**
 * Single-tap behavior shared by playback surfaces: a playing fullscreen
 * video reveals its controls first, before another tap may pause.
 * Explicit play/pause buttons bypass this policy.
 */
internal object PlayerTapPolicy {
    enum class Action { REVEAL, PAUSE, RESUME }

    fun onSurfaceTap(controlsVisible: Boolean, playWhenReady: Boolean): Action =
        when {
            !playWhenReady -> Action.RESUME
            !controlsVisible -> Action.REVEAL
            else -> Action.PAUSE
        }
}
