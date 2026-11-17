package com.yagay.supjav.player

import com.yagay.supjav.model.VideoServer
import org.junit.Assert.*
import org.junit.Test

class VideoPartsTest {
    private fun source(label: String, url: String, part: String? = null) =
        VideoServer(url, label, url, "https://supjav.com/12345.html", part = part)

    @Test fun recognizesOnlyExplicitDiscLabels() {
        assertEquals("CD1", VideoParts.normalize("CD 01"))
        assertEquals("CD2", VideoParts.normalize("part_2 LUC"))
        assertEquals("CD3", VideoParts.normalize("EVS Disc3"))
        assertNull(VideoParts.normalize("EVS"))
        assertNull(VideoParts.normalize("Server 2"))
    }

    @Test fun selectablePartsAreOrderedByNumericValue() {
        val entries = listOf(
            source("LUC", "a", "CD10"),
            source("EVS", "b", "CD2"),
            source("LUC", "c", "CD1"),
            source("FST", "d", "CD2")
        )
        assertEquals(listOf("CD1", "CD2", "CD10"), VideoParts.available(entries))
        assertEquals(listOf("c", "a", "b", "d"),
            VideoParts.firstPartFirst(entries).map { it.id })
    }

    @Test fun switchingCdKeepsTheCurrentLineWhenAvailable() {
        val entries = listOf(
            source("EVS", "cd1-evs", "CD1"),
            source("LUC", "cd1-luc", "CD1"),
            source("EVS", "cd2-evs", "CD2"),
            source("LUC", "cd2-luc", "CD2")
        )
        assertEquals("cd2-luc",
            VideoParts.forPart(entries, "CD2", entries[1])?.id)
        assertEquals("cd2-evs",
            VideoParts.forPart(entries, "CD2", source("ST", "other"))?.id)
    }

    @Test fun multipleMirrorsWithoutCdAreNotFakeParts() {
        val entries = listOf(source("EVS", "one"), source("LUC", "two"))
        assertTrue(VideoParts.available(entries).isEmpty())
    }
}
