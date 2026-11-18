package com.yagay.supjav.desktop

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class MpvRuntimeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun detectsLocalAndExplicitRuntimeBeforeSystemSearch() {
        val root = temp.newFolder().toPath()
        val mpv = root.resolve("mpv")
        Files.createDirectories(mpv)
        val bundled = mpv.resolve("libmpv-2.dll")
        Files.write(bundled, byteArrayOf())
        val explicit = root.resolve("custom.dll")
        Files.write(explicit, byteArrayOf())

        val candidates = MpvRuntime.candidates(explicit.toString(), root)
        assertEquals(explicit.toString(), candidates[0])
        assertEquals(bundled.toString(), candidates[1])
        assertTrue(candidates.contains("libmpv-2"))
    }

    @Test fun rejectsNonHttpsOriginAndPreservesHttpsPort() {
        assertEquals("https://example.com",
            MpvRuntime.origin("https://example.com/embed?id=3"))
        assertEquals("https://example.com:8443",
            MpvRuntime.origin("https://example.com:8443/player"))
        assertNull(MpvRuntime.origin("http://example.com/player"))
        assertNull(MpvRuntime.origin("not a valid URL"))
    }

    @Test fun defaultCandidatesExistEvenWithoutInstalledLibrary() {
        val candidates = MpvRuntime.candidates(null, temp.newFolder().toPath())
        assertTrue(candidates.contains("libmpv-2"))
        assertTrue(candidates.contains("mpv-2"))
        assertTrue(candidates.isNotEmpty())
    }
}
