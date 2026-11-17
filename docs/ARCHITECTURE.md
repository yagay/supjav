# Supjav Android - architecture and safety boundaries

Last reviewed: 2026-11-17 (main).

## Main components

- SupjavSource: site-scoped HTTPS requests with validated redirects; Jsoup list/detail/category parsing.
- PlaybackResolver: bounded attempts to resolve signed HLS/MP4 with source-specific adapters. Never fabricates signed URLs.
- MediaGateway: validated public HTTPS/DNS access and explicit media probing. No CAPTCHA, DRM or access-control bypass.
- SelectedMediaSession / ScriptPlayerScreen: verify the selected iframe chain, discover HLS and offer native or browser-compatible playback.
- PlaybackEngine: app-wide Media3 ExoPlayer and PlaybackService MediaSession; handles system media controls, background audio and PiP.
- WebPlaybackSession: separate browser-HLS session, maintaining the actual video identity and persisting position/history without pretending to be Media3.
- EmbeddedHlsPlayer / BrowserHlsDocument: isolated WebView with HLS.js and in-video mobile controls. Its browser network context is retained for EVS/LUC.
- LibraryStore: SQLite favorites, watch history and resume position.
- FeedCache: bounded in-process feed states with retained scrolling, duplicate pagination limits.
- Diagnostics: redacted, asynchronous application events, ZIP export.

## Playback handoff

1. Select a real source from a detail page.
2. PlaybackResolver attempts native media candidates and reports blocked or unsupported sources.
3. On Media3 success: clear any Web HLS session and start PlaybackService as normal.
4. On browser fallback: stop the old Media3 item (so no stale media session or mini-player remains), then create a WebPlaybackSession.
5. If a browser-discovered HLS URL fails native probing, users can choose the video-only WebView/HLS.js playback. Progress events (time/duration/playing only) are saved to the same local history database.
6. Switching away from Web HLS pauses the WebView and saves position. If background playback is disabled, Activity.onStop also pauses browser HLS.
7. Native 401/403/404 media failures are not blindly retried; Web HLS only retries bounded recoverable network/decoder errors.

## Current, verified limits

- Media3 has Android MediaSession, system notification and system PiP. Browser HLS does not yet have a MediaSession adapter or a retained PiP surface. Do not show native media controls or PiP for browser HLS until those features are implemented and tested on device.
- The HLS.js script currently comes from a fixed external CDN release. Bundle it in the APK in a later hardening step.
- A successful HLS manifest probe does not guarantee every segment is accessible.
- Long-running browser playback, process death, orientation changes, UI accessibility and network switch recovery require on-device verification.

## Test policy

- CI runs assembleDebug and testDebugUnitTest only.
- Keep EVS/LUC hls2 -> hls4 -> hls3 candidate extraction; do not replace working browser HLS playback just to make every provider use Media3.
- Add sanitized parser fixtures for genuine player changes; unit tests do not emulate live CDN access.
- Restore real stable debug signing before relying on APK-over-APK updates across different CI runners.

## Pending engineering

- Add MediaSession/PiP integration for browser HLS through a lifecycle-safe adapter, not by misrepresenting the Media3 player.
- Consolidate UI navigation into a single route/state model rather than overlapping booleans.
- Bundle and integrity-pin the HLS.js runtime; replace JavaScript-in-Kotlin with assets.
- Move final ZIP compression and all remaining SQLite reads off the main thread.
- Add instrumentation coverage for WebView lifecycle, media continuity, and Android 12-17 window/insets changes.
