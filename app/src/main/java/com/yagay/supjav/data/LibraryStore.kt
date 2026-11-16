package com.yagay.supjav.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.yagay.supjav.model.VideoItem

class LibraryStore(context: Context) : SQLiteOpenHelper(context, "supjav_library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE favorites(id TEXT PRIMARY KEY,title TEXT NOT NULL,cover TEXT NOT NULL,url TEXT NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history(id TEXT PRIMARY KEY,title TEXT NOT NULL,cover TEXT NOT NULL,url TEXT NOT NULL,position_ms INTEGER NOT NULL,updated INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun values(v: VideoItem) = ContentValues().apply {
        put("id", v.id); put("title", v.title); put("cover", v.coverUrl)
        put("url", v.detailUrl); put("updated", System.currentTimeMillis())
    }

    @Synchronized fun isFavorite(id: String): Boolean =
        readableDatabase.rawQuery("SELECT id FROM favorites WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    @Synchronized fun toggleFavorite(video: VideoItem): Boolean {
        if (isFavorite(video.id)) {
            writableDatabase.delete("favorites", "id=?", arrayOf(video.id)); return false
        }
        writableDatabase.insertWithOnConflict("favorites", null, values(video),
            SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    @Synchronized fun saveHistory(video: VideoItem, positionMs: Long) {
        val content = values(video).apply { put("position_ms", positionMs.coerceAtLeast(0)) }
        writableDatabase.insertWithOnConflict("history", null, content, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun position(id: String): Long =
        readableDatabase.rawQuery("SELECT position_ms FROM history WHERE id=?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    @Synchronized fun list(favorites: Boolean): List<VideoItem> {
        val table = if (favorites) "favorites" else "history"
        return readableDatabase.rawQuery(
            "SELECT id,title,cover,url FROM " + table + " ORDER BY updated DESC LIMIT 500", null
        ).use { c ->
            val items = mutableListOf<VideoItem>()
            while (c.moveToNext()) items.add(VideoItem(c.getString(0), c.getString(1),
                c.getString(2), c.getString(3)))
            items
        }
    }

    @Synchronized fun deleteHistory(id: String) {
        writableDatabase.delete("history", "id=?", arrayOf(id))
    }
    @Synchronized fun clearHistory() { writableDatabase.delete("history", null, null) }
}
