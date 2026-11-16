package com.yagay.supjav.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Diagnostics {
    private val events = ArrayDeque<String>()
    @Synchronized fun log(message: String) {
        val clean = message.replace(Regex("(?i)(token|auth|signature|key|cookie)=([^&\\s]+)"), "$1=[REDACTED]")
            .take(600)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        if (events.size >= 1500) events.removeFirst()
        events.addLast(stamp + " " + clean)
    }
    @Synchronized fun snapshot(): String = events.joinToString("\n")
    fun share(context: Context) {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "supjav-diagnostic-" + System.currentTimeMillis() + ".zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("events.txt"))
            zip.write(snapshot().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("device.txt"))
            zip.write(("SDK=" + Build.VERSION.SDK_INT + "\nDEVICE=" + Build.MANUFACTURER +
                " " + Build.MODEL + "\n").toByteArray())
            zip.closeEntry()
        }
        val uri = FileProvider.getUriForFile(context, "com.yagay.supjav.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享诊断日志"))
    }
}
