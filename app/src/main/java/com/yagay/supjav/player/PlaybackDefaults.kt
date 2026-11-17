package com.yagay.supjav.player

import com.yagay.supjav.data.SupjavLine
import com.yagay.supjav.model.VideoServer
import kotlin.math.abs

/**
 * Validated preferences shared by the native and embedded HLS renderers.
 * Defaults are deliberately conservative where controls affect navigation.
 */
object PlaybackDefaults {
    const val SPEED = "default_playback_speed"
    const val SEEK = "skip_seconds"
    const val HOLD = "hold_speed"
    const val LINE = "preferred_line"
    const val AUTOPLAY = "autoplay_video"
    const val RESUME = "resume_playback"
    const val FIT = "fill_video"
    const val START_TAB = "start_tab"

    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    val holdSpeeds = listOf(1.5f, 2f, 3f)
    val seekSeconds = listOf(5, 10, 15, 30)
    val lineOptions = listOf("auto", "EVS", "LUC", "FST", "VAS", "VOE", "TV", "ST")
    val startupOptions = listOf("RECOMMEND", "CATEGORY", "RANK", "MINE")

    fun speed(input: Float): Float =
        speeds.firstOrNull { abs(it - input) < 0.01f } ?: 1f

    fun holdSpeed(input: Float): Float =
        holdSpeeds.firstOrNull { abs(it - input) < 0.01f } ?: 2f

    fun skip(input: Int): Int = if (input in seekSeconds) input else 10

    fun line(input: String?): String {
        val candidate = input?.trim()?.uppercase().orEmpty()
        return if (candidate in lineOptions) candidate else "auto"
    }

    fun startup(input: String?): String =
        input?.takeIf { it in startupOptions } ?: "RECOMMEND"

    fun preferredServer(
        available: List<VideoServer>, preference: String?
    ): VideoServer? {
        val preferred = line(preference)
        if (preferred == "auto") return available.firstOrNull()
        return available.firstOrNull {
            SupjavLine.of(it.name).name == preferred
        } ?: available.firstOrNull()
    }

    fun speedLabel(value: Float): String =
        value.toString().removeSuffix(".0") + "×"
}
