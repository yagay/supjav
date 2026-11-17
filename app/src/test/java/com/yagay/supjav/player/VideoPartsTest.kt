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


    @Test fun repeatedFiveProviderSetsBecomeTwoCds() {
        // The diagnostic for SUPJAV 472992 reported ten server tokens,
        // unlike ordinary pages that present five mirror lines.
        val providers = listOf("EVS", "ST", "VOE", "LUC", "VAS")
        val entries = (1..2).flatMap { cd ->
            providers.map { line -> source(line, "cd" + cd + "-" + line) }
        }
        val grouped = VideoParts.groupRepeatedProviderSets(entries)
        assertEquals(listOf("CD1", "CD2"), VideoParts.available(grouped))
        assertEquals(listOf(5, 5),
            VideoParts.available(grouped).map { part ->
                grouped.count { VideoParts.partOf(it) == part }
            })
        assertEquals("cd2-LUC", VideoParts.forPart(grouped, "CD2", grouped[3])?.id)
    }

    @Test fun alternativeOrderInSecondProviderSetStillFormsCd2() {
        val first = listOf("EVS", "ST", "VOE", "LUC", "VAS")
        val second = listOf("VAS", "VOE", "ST", "EVS", "LUC")
        val input = first.mapIndexed { index, line -> source(line, "one-" + index) } +
            second.mapIndexed { index, line -> source(line, "two-" + index) }
        assertEquals(listOf("CD1", "CD2"),
            VideoParts.available(VideoParts.groupRepeatedProviderSets(input)))
    }

    @Test fun oneSetOfFiveMirrorsNeverBecomesFiveCds() {
        val entries = listOf("EVS", "ST", "VOE", "LUC", "VAS")
            .mapIndexed { i, line -> source(line, "server-" + i) }
        assertEquals(entries, VideoParts.groupRepeatedProviderSets(entries))
        assertTrue(VideoParts.available(entries).isEmpty())
    }

    @Test fun twoSetsWithDuplicateTokensOrDifferentProvidersAreNotCds() {
        val providers = listOf("EVS", "ST", "VOE", "LUC", "VAS")
        val entries = providers.map { source(it, "one-" + it) }
        val repeatedSameLinks = entries + entries
        assertEquals(repeatedSameLinks, VideoParts.groupRepeatedProviderSets(repeatedSameLinks))
        val differentProviders = entries + listOf(
            source("EVS", "2a"), source("ST", "2b"),
            source("TV", "2c"), source("LUC", "2d"), source("VAS", "2e")
        )
        assertEquals(differentProviders,
            VideoParts.groupRepeatedProviderSets(differentProviders))
    }

    @Test fun unknownProviderNamesDoNotProduceGuessedParts() {
        val entries = (1..2).flatMap { cd ->
            listOf("server alpha", "server beta", "server gamma")
                .map { name -> source(name, cd.toString() + name) }
        }
        assertTrue(VideoParts.available(VideoParts.groupRepeatedProviderSets(entries)).isEmpty())
    }

    @Test fun multipleMirrorsWithoutCdAreNotFakeParts() {
        val entries = listOf(source("EVS", "one"), source("LUC", "two"))
        assertTrue(VideoParts.available(entries).isEmpty())
    }
}
