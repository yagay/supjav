package com.yagay.supjav.data

import org.junit.Assert.*
import org.junit.Test

class PlaybackChallengeDetectorTest {
    @Test fun scriptsAndHiddenPlayerFlagsDoNotTriggerFalseChallenge() {
        val html = """
            <html><head><title>Streamtape Video</title>
            <script>const captchaEnabled = true; const text = 'robot verification';</script>
            </head><body><div id="robotlink"></div>
            <script src="https://cdn.example.com/captcha.js"></script></body></html>
        """.trimIndent()
        assertFalse(PlaybackChallengeDetector.requiresHuman(html))
    }

    @Test fun realChallengePageStillRequiresManualVerification() {
        assertTrue(PlaybackChallengeDetector.requiresHuman(
            "<html><title>Verify you are human</title><body>Check</body></html>"))
        assertTrue(PlaybackChallengeDetector.requiresHuman(
            "<html><body><form id='challenge-form'>Challenge</form></body></html>"))
        assertTrue(PlaybackChallengeDetector.requiresHuman(
            "<html><body><div class='cf-turnstile'></div></body></html>"))
        assertTrue(PlaybackChallengeDetector.requiresHuman(
            "<html><body>Please complete the CAPTCHA</body></html>"))
    }

    @Test fun playerCanContainCaptchaScriptWithoutBeingBlocked() {
        assertFalse(PlaybackChallengeDetector.requiresHuman(
            "<html><body><div id='robotlink'></div>" +
            "<script>var challenge = 'verify you are human';</script></body></html>"))
    }
}
