# Supjav native Android — architecture

## Modules

- Single `app` Gradle module (Debug-only GitHub Actions).
- `SupjavSource`: OkHttp HTTP transport + Jsoup HTML list/detail parsing.
- `PlaybackResolver`: a bounded, cancellation-friendly resolver for declared HTTP(S) MP4/HLS sources. No DRM, CAPTCHA, browser fingerprint bypass, JS deobfuscation or site authentication interception.
- `PlaybackEngine`: single app-wide Media3 ExoPlayer with selected-source request headers.
- `PlaybackService`: MediaSessionService for Android media controls and background playback.
- `LibraryStore`: local SQLite favorites, history and resumption.
- `Diagnostics`: in-memory redacted events, ZIP exported via FileProvider.
- `ui`: Jetpack Compose home/feed/search/discovery/detail/player/library/settings.

## Site assumptions — must validate on device

The source has WordPress-like `/?s=term`, `/page/N/`, `/ID.html`,
`/category/slug/`, `/tag/slug/` and `/popular?sort=day` routes.
HTML selectors are inferred from older open-source integrations and may drift.
The site may return HTTP 403 and some providers require browser JS/captcha.
Do **not** treat historical server detection as a guarantee of native Media3 playback.

See https://github.com/fekilooo/javbrowser/blob/main/docs/supjav-server-analysis.md
and https://github.com/xbol0/supjavd/blob/main/supjav.ts.

## Future hardening

- Save anonymized HTML fixtures and add parser regression tests.
- Implement server-specific public API adapters as validated.
- Add repository data cache, offline search and more granular player preferences.
- Validate pause/resume/notification/PiP on Android 12–17.
- Verify rights, site terms and jurisdiction-specific distribution restrictions.
