package com.yagay.supjav.desktop

import kotlin.math.roundToInt

/** Pure pixel-to-control conversion, independent of mouse event frequency. */
object DesktopGestureMath {
    fun seekTarget(startMs: Long, deltaX: Float, width: Int, durationMs: Long): Long {
        if (durationMs <= 0L) return startMs.coerceAtLeast(0L)
        return (startMs + deltaX / width.coerceAtLeast(1) * durationMs)
            .toLong().coerceIn(0L, durationMs)
    }

    fun brightness(start: Float, deltaY: Float, height: Int): Float =
        (start - deltaY / height.coerceAtLeast(1) * 0.9f)
            .coerceIn(0.1f, 1f)

    fun volume(start: Int, deltaY: Float, height: Int): Int =
        (start - deltaY / height.coerceAtLeast(1) * 100f)
            .roundToInt().coerceIn(0, 100)
}
