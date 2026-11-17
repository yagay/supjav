package com.yagay.supjav.data

import org.jsoup.Jsoup

/**
 * Detect a *visible* player verification page, not a keyword embedded in its
 * scripts, comments, third-party bundles, or the player feature flags.
 *
 * False positives previously hid normal Streamtape player pages merely
 * because their HTML contained the word "captcha".
 */
internal object PlaybackChallengeDetector {
    fun requiresHuman(html: String): Boolean {
        val document = Jsoup.parse(html.take(1_000_000))
        val title = document.title().trim().lowercase()
        if (title.contains("just a moment") ||
            title.contains("attention required") ||
            title.contains("verify you are human") ||
            title.contains("robot verification") ||
            title.contains("security check")) return true

        if (document.selectFirst(
                "#challenge-form, #challenge-running, #challenge-stage, " +
                ".cf-browser-verification, .cf-turnstile, " +
                "form[action*=challenge], form[action*=captcha]"
            ) != null) return true

        // A player may legitimately include an invisible CAPTCHA API/script.
        // Only treat visible verification instructions as a blocking challenge.
        val body = document.body() ?: return false
        body.select("script, style, noscript, template, iframe").remove()
        val visibleText = body.text().lowercase().take(4000)
        val playerPresent = document.selectFirst("video, #robotlink, #video, #player") != null
        val challengeText = listOf(
            "verify you are human",
            "robot verification required",
            "complete the captcha",
            "please complete the captcha",
            "confirm you are not a robot",
            "checking your browser"
        ).any { visibleText.contains(it) }
        return challengeText && (!playerPresent ||
            document.selectFirst("input[name*=captcha], [data-sitekey]") != null)
    }
}
