package com.yagay.supjav.player

/**
 * Derived from the retry boundaries of the user's hongguo-android project.
 * Temporary transport failures only; authentication, missing content, DRM and
 * unsupported media must not be retried indefinitely or bypassed.
 */
object PlaybackRecoveryPolicy {
    private val transportErrors = setOf(2000, 2001, 2002)
    private val serverStatus = setOf(500, 502, 503, 504)
    const val MAX_RETRIES = 4

    fun mayRetry(errorCode: Int, httpStatus: Int? = null): Boolean =
        if (httpStatus != null) httpStatus in serverStatus else errorCode in transportErrors

    fun delayMs(attempt: Int): Long = when (attempt) {
        1 -> 2_500L
        2 -> 4_000L
        3 -> 8_000L
        else -> 16_000L
    }
}
