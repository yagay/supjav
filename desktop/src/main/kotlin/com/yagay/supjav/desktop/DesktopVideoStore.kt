package com.yagay.supjav.desktop

import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.util.Diagnostics
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.prefs.Preferences
import org.json.JSONArray
import org.json.JSONObject

/** Atomic local JSON persistence with one-time migration from Preferences. */
class DesktopVideoStore(
    val root: Path = defaultDirectory()
) {
    companion object {
        fun defaultDirectory(): Path {
            val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            return (if (local != null) Path.of(local)
                else Path.of(System.getProperty("user.home"), ".supjav-desktop"))
                .resolve("SupjavDesktop")
        }
    }

    private fun filename(key: String): Path {
        require(key in setOf("recent", "favorites")) { "Unsupported store" }
        return root.resolve("${key}.json")
    }

    fun read(key: String): List<VideoItem> {
        val file = filename(key)
        if (!Files.exists(file)) return readLegacy(key)
        return runCatching {
            decode(Files.readString(file, StandardCharsets.UTF_8))
        }.getOrElse {
            Diagnostics.log("DESKTOP_STORE_READ_FAILED key=$key type=${it.javaClass.simpleName}")
            emptyList()
        }
    }

    fun write(key: String, videos: List<VideoItem>) {
        val target = filename(key)
        Files.createDirectories(root)
        val data = encode(videos)
        val tmp = Files.createTempFile(root, key, ".tmp")
        try {
            Files.writeString(tmp, data, StandardCharsets.UTF_8)
            try {
                Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun readLegacy(key: String): List<VideoItem> = runCatching {
        val prefs = Preferences.userNodeForPackage(DesktopController::class.java)
        val raw = prefs.get(key, null) ?: return@runCatching emptyList()
        val migrated = decode(raw)
        write(key, migrated)
        prefs.remove(key)
        Diagnostics.log("DESKTOP_STORE_MIGRATED key=$key count=${migrated.size}")
        migrated
    }.getOrElse {
        Diagnostics.log("DESKTOP_STORE_MIGRATE_FAILED key=$key type=${it.javaClass.simpleName}")
        emptyList()
    }

    internal fun encode(items: List<VideoItem>): String = JSONArray().apply {
        items.distinctBy { it.id }.forEach { v ->
            put(JSONObject().apply {
                put("id", v.id)
                put("title", v.title)
                put("cover", v.coverUrl)
                put("url", v.detailUrl)
                put("code", v.code)
            })
        }
    }.toString()

    internal fun decode(raw: String): List<VideoItem> {
        val json = JSONArray(raw)
        return (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val url = item.optString("url")
            if (id.isBlank() || !url.startsWith("https://")) null
            else VideoItem(
                id = id,
                title = item.optString("title"),
                coverUrl = item.optString("cover"),
                detailUrl = url,
                code = item.optString("code")
            )
        }.distinctBy { it.id }
    }
}
