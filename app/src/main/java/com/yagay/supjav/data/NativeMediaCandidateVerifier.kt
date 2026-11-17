package com.yagay.supjav.data

import com.yagay.supjav.model.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A WebView script can report a perfectly shaped playlist URL that the origin
 * refuses to serve to Media3. Never advertise native playback until the exact
 * candidate has passed a small request with native networking and its Referer.
 *
 * This is only a reachability check (master playlist or MP4 header), not a
 * guarantee that every segment will succeed later.
 */
internal class NativeMediaCandidateVerifier(
    private val gateway: MediaGateway = OkHttpMediaGateway()
) {
    internal sealed interface Result {
        data class Ready(val source: PlaybackSource) : Result
        data class Rejected(val status: Int?, val reason: String) : Result
    }

    suspend fun verify(source: PlaybackSource): Result = withContext(Dispatchers.IO) {
        if (MediaSourceDetector.origin(source.url) == null ||
            MediaSourceDetector.origin(source.referer) == null) {
            return@withContext Result.Rejected(null, "unsafe-source")
        }
        val isHls = source.mimeType == "application/x-mpegURL"
        val probe = gateway.probe(source.url, source.referer, isHls)
        if (probe.playable && probe.finalUrl != null &&
            MediaSourceDetector.origin(probe.finalUrl) != null) {
            Result.Ready(source.copy(url = probe.finalUrl,
                includeOrigin = probe.includeOrigin))
        } else {
            Result.Rejected(probe.status, probe.reason)
        }
    }
}
