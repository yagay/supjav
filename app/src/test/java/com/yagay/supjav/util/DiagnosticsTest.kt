package com.yagay.supjav.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test fun stripsMediaURLsAndCredentialsBeforePersistence() {
        val message = "RESOLVE_FAILED https://example.test/hls/master.m3u8?token=secret123 " +
            "Authorization: Bearer abc123 Cookie=sessionhidden"
        val clean = Diagnostics.sanitize(message)
        assertFalse(clean.contains("secret123"))
        assertFalse(clean.contains("master.m3u8"))
        assertFalse(clean.contains("abc123"))
        assertFalse(clean.contains("sessionhidden"))
        assertTrue(clean.contains("[URL_REDACTED]"))
    }

    @Test fun keepsNonSensitiveFailureStages() {
        val message = "RESOLVE_UNSUPPORTED_ENTRY server=EVS entry=script-token chars=56"
        val clean = Diagnostics.sanitize(message)
        assertTrue(clean.contains("RESOLVE_UNSUPPORTED_ENTRY"))
        assertTrue(clean.contains("server=EVS"))
        assertTrue(clean.contains("chars=56"))
    }
}
