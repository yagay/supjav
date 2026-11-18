package com.yagay.supjav.util

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** App-only redacted logs, persisted across a crash for Windows diagnostics. */
object Diagnostics {
    private val events = ArrayDeque<String>()
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SupjavDesktopDiagnostics").apply { isDaemon = true }
    }
    private val directory: Path by lazy {
        val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        (if (local != null) Path.of(local)
         else Path.of(System.getProperty("user.home"), ".supjav-desktop"))
            .resolve("SupjavDesktop").resolve("diagnostics")
    }
    private val file: Path by lazy { directory.resolve("events.log") }

    internal fun redact(message: String): String = message
        .replace(Regex("""(?i)https?://[^\s"'<>]+"""), "[URL]")
        .replace(Regex("""(?i)(?:cookie|authorization|token|password|signature|secret|session|key)\s*[:=]\s*[^\s&,;]+"""), "[REDACTED]")
        .replace(Regex("""(?i)bearer\s+[A-Za-z0-9+/_=.:-]+"""), "[REDACTED]")
        .replace(Regex("[\r\n\t]"), " ")
        .take(400)

    @Synchronized
    fun log(message: String) {
        val line = LocalDateTime.now().format(stamp) + " " + redact(message)
        if (events.size >= 250) events.removeFirst()
        events.addLast(line)
        writer.execute {
            runCatching {
                Files.createDirectories(directory)
                if (Files.exists(file) && Files.size(file) > 400_000L) {
                    Files.move(file, directory.resolve("events.previous.log"),
                        StandardCopyOption.REPLACE_EXISTING)
                }
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
    }

    @Synchronized fun snapshot(): String = events.joinToString("\n")

    /** Returns a redacted, local diagnostics ZIP-free text file for support. */
    fun currentFile(): Path? = runCatching {
        writer.submit {}.get(5, TimeUnit.SECONDS)
        file.takeIf { Files.exists(it) }
    }.getOrNull()
}
