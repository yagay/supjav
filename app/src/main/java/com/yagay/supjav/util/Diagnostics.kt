package com.yagay.supjav.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * App-local diagnostic events, persisted across process restarts.
 * No root, logcat, cookies, authorization headers or signed media URLs are exported.
 */
object Diagnostics {
    private const val MAX_RECENT = 300
    private const val MAX_FILE_BYTES = 450_000L
    private val events = ArrayDeque<String>()
    private var application: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (application != null) return
        application = context.applicationContext
        log("APP_START sdk=" + Build.VERSION.SDK_INT)
    }

    /** Remove URLs, credentials and signed query parameters before they reach disk. */
    internal fun sanitize(raw: String): String {
        return raw
            .replace(Regex("""(?i)\b(?:https?|rtsp|file)://[^\s"'<>]+"""), "[URL_REDACTED]")
            .replace(Regex("""(?i)\b(?:cookie|set-cookie|authorization|proxy-authorization)\s*[:=]\s*[^\r\n,;]+"""), "[HEADER_REDACTED]")
            .replace(Regex("""(?i)\b(?:token|auth|key|signature|sign|secret|password|session|cookie|expires|hash|c)\s*=\s*[^&\s]+"""), "[PARAM_REDACTED]")
            .replace(Regex("""(?i)\b(?:bearer|basic)\s+[A-Za-z0-9+/=._-]+"""), "[CREDENTIAL_REDACTED]")
            .replace(Regex("[\r\n\t]"), " ")
            .take(500)
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date())
    }

    private fun logDir(context: Context) = File(context.filesDir, "diagnostics")
    private fun logFile(context: Context) = File(logDir(context), "events.log")

    @Synchronized
    fun log(message: String) {
        val event = timestamp() + " " + sanitize(message)
        if (events.size >= MAX_RECENT) events.removeFirst()
        events.addLast(event)
        val ctx = application ?: return
        runCatching {
            val dir = logDir(ctx).apply { mkdirs() }
            val file = logFile(ctx)
            if (file.length() >= MAX_FILE_BYTES) {
                val previous = File(dir, "events.previous.log")
                previous.delete()
                file.renameTo(previous)
            }
            file.appendText(event + "\n", Charsets.UTF_8)
        }
    }

    @Synchronized
    fun snapshot(): String = events.joinToString("\n")

    @Synchronized
    fun preview(limit: Int = 120): String {
        val ctx = application
        val stored = ctx?.let { context ->
            val file = logFile(context)
            runCatching { if (file.exists()) file.readLines().takeLast(limit).joinToString("\n") else "" }
                .getOrDefault("")
        }.orEmpty()
        return stored.ifEmpty { snapshot() }.ifBlank { "暂无应用诊断记录" }
    }

    @Synchronized
    fun clear() {
        events.clear()
        application?.let { ctx ->
            logFile(ctx).delete()
            File(logDir(ctx), "events.previous.log").delete()
        }
        log("DIAGNOSTICS_CLEARED")
    }

    private fun networkSummary(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null) "Network: unavailable\n" else buildString {
            append("Internet: ").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).append('\n')
            append("Validated: ").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).append('\n')
            append("WiFi: ").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).append('\n')
            append("Cellular: ").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).append('\n')
            append("VPN: ").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).append('\n')
        }
    }.getOrElse { "Network summary unavailable: " + it.javaClass.simpleName + "\n" }

    private fun deviceSummary(context: Context) = buildString {
        append("Generated: ").append(timestamp()).append('\n')
        append("App package: ").append(context.packageName).append('\n')
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        append("App version: ").append(pkg?.versionName ?: "unknown").append('\n')
        append("Android SDK: ").append(Build.VERSION.SDK_INT).append('\n')
        append("Android release: ").append(Build.VERSION.RELEASE).append('\n')
        append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append('\n')
        append("ABIs: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        append(networkSummary(context))
    }

    @Synchronized
    fun export(context: Context): File {
        val ctx = context.applicationContext
        if (application == null) initialize(ctx)
        log("DIAGNOSTICS_EXPORT")
        val dir = File(ctx.cacheDir, "diagnostics").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.endsWith(".zip") && System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000 }
            ?.forEach { it.delete() }
        val zip = File(dir, "supjav-diagnostic-" + System.currentTimeMillis() + ".zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun add(name: String, content: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
            add("README.txt", """
                Supjav Android diagnostic package
                Files contain application event logs and device/network capabilities.
                They do NOT contain system logcat or private authentication data.
                Playback URL, cookies and signed query values are intentionally excluded.
                Reproduce the issue and export again after a failure if necessary.
            """.trimIndent() + "\n")
            add("device.txt", deviceSummary(ctx))
            val previous = File(logDir(ctx), "events.previous.log")
            if (previous.exists()) add("events-previous.log", previous.readText().takeLast(500_000))
            val current = logFile(ctx)
            add("events.log", if (current.exists()) current.readText().takeLast(500_000) else snapshot())
        }
        return zip
    }

    fun share(context: Context) {
        val zip = export(context)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", zip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Supjav diagnostic ZIP", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出 Supjav 诊断日志"))
    }
}
