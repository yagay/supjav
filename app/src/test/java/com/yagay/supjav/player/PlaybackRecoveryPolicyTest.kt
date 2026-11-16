package com.yagay.supjav.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test fun onlyRetriesTransientNetworkOrServerErrors() {
        assertTrue(PlaybackRecoveryPolicy.mayRetry(2001))
        assertTrue(PlaybackRecoveryPolicy.mayRetry(2002))
        assertTrue(PlaybackRecoveryPolicy.mayRetry(2004, 502))
        assertFalse(PlaybackRecoveryPolicy.mayRetry(2004, 403))
        assertFalse(PlaybackRecoveryPolicy.mayRetry(2004, 404))
        assertFalse(PlaybackRecoveryPolicy.mayRetry(4000))
    }
    @Test fun boundedProgressiveBackoff() {
        assertEquals(4, PlaybackRecoveryPolicy.MAX_RETRIES)
        assertEquals(2500L, PlaybackRecoveryPolicy.delayMs(1))
        assertEquals(16000L, PlaybackRecoveryPolicy.delayMs(4))
    }
}
