package com.yagay.supjav.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * Desktop counterpart for the Android-only Diagnostics implementation.
 * Never print signed URLs, cookies or server data-link tokens.
 */
object Diagnostics {
    private val events = ArrayDeque<String>()
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    @Synchronized
    fun log(message: String) {
        val sanitized = message
            .replace(Regex("""(?i)https?://[^\s"'<>]+"""), "[URL]")
            .replace(Regex("""(?i)(?:token|cookie|authorization|password|signature|secret)\s*[:=]\s*\S+"""), "[REDACTED]")
            .replace(Regex("[\r\n\t]"), " ")
            .take(400)
        val line = LocalDateTime.now().format(stamp) + " " + sanitized
        if (events.size >= 250) events.removeFirst()
        events.addLast(line)
    }
    @Synchronized
    fun snapshot(): String = events.joinToString("\n")
}
