package com.yagay.supjav.desktop

import com.yagay.supjav.model.VideoItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopVideoStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun movie(id: Int): VideoItem = VideoItem(
        id = id.toString(),
        title = "作品 $id 测试 Español",
        coverUrl = "https://example.com/poster/$id.jpg",
        detailUrl = "https://supjav.com/$id.html",
        code = "TEST-$id"
    )

    @Test fun savesMoreThanPreferencesLimitWithoutTruncation() {
        val directory = temporaryFolder.newFolder().toPath()
        val store = DesktopVideoStore(directory)
        val entries = (1..350).map(::movie)
        store.write("recent", entries)
        assertEquals(entries, store.read("recent"))
        assertTrue(directory.resolve("recent.json").toFile().length() > 8192)
    }

    @Test fun retainsDistinctHistoryAndFavoritesAndPreservesUnicode() {
        val store = DesktopVideoStore(temporaryFolder.newFolder().toPath())
        store.write("recent", listOf(movie(1), movie(1), movie(2)))
        store.write("favorites", listOf(movie(3)))
        assertEquals(listOf("1", "2"), store.read("recent").map { it.id })
        assertEquals(listOf("3"), store.read("favorites").map { it.id })
        assertEquals(movie(1).title, store.read("recent").first().title)
    }

    @Test fun corruptFileDoesNotCrashStartupAndCanBeRepaired() {
        val directory = temporaryFolder.newFolder().toPath()
        directory.resolve("favorites.json").toFile().writeText("not-json")
        val store = DesktopVideoStore(directory)
        assertTrue(store.read("favorites").isEmpty())
        store.write("favorites", listOf(movie(4)))
        assertEquals(listOf("4"), store.read("favorites").map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun arbitraryPathIsNotAllowed() {
        DesktopVideoStore(temporaryFolder.newFolder().toPath())
            .read("../outside")
    }
}
