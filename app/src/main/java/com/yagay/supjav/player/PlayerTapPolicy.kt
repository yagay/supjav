package com.yagay.supjav.player

/**
 * A single tap on the video surface only toggles controller visibility.
 * Playback state is exclusively changed by explicit controls or other gestures.
 */
internal object PlayerTapPolicy {
    enum class Action { SHOW, HIDE }

    fun onSurfaceTap(controlsVisible: Boolean): Action =
        if (controlsVisible) Action.HIDE else Action.SHOW
}
