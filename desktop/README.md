# Supjav Desktop (branch `pc`)

A Windows-first desktop application based on the latest Android `main` source. The Android app is left unchanged.

## What works in this first implementation

- Kotlin / Compose Desktop application, with a responsive Windows layout.
- Reuses **the exact pure Kotlin source files** from `../app/src/main/java` (no parser fork): listing, detail, CD-part grouping, mirror/line detection and the existing playback resolver.
- Home, category directory, search, day/week/month rankings, infinite feed paging, posters with code, a right-hand poster playlist, previous/next video.
- Local favorites and watching history with atomic JSON files, including migration from legacy Java Preferences (avoids the 8 KB per-value limit).
- Three playback paths: **embedded MPV** (preferred when installed), VLCJ fallback for verified HLS/MP4, and Edge WebView2 for JavaScript-only EVS/LUC lines. The MPV Java/JNA HWND embed approach follows official mpv-player/mpv-examples, and works with the existing Compose controls/gestures. Browser fallback preserves legitimate site verification and does not bypass access controls.
- Single-touch/AWT gesture prototype: tap show/hide controls, double tap left/right -/+10s, hold side for temporary 2x (restore prior speed), horizontal swipe for timeline preview/commit, vertical left/right for video brightness/audio level with stable gesture baselines.
- Windows installer job; no dependence on Android SDK for this desktop Gradle project.
- A playback state driven by MPV or VLC native events (loading, buffering, playing, paused, finished, error), with automatic native-engine fallback.
- Independent page and playlist pagination, cached feed navigation and per-feed scroll position, stale async request cancellation.
- Dedicated player-only fullscreen, search-focus-aware keyboard shortcuts, expanded actor/maker/tag directory and redacted disk diagnostics.

## Requirements

- Windows 10/11 x64, JDK 17 for local development, Gradle 9.4.1.
- **MPV (recommended)**: Install the official **LGPL x86_64 libmpv** release for Windows, placing `libmpv-2.dll` (and any DLL dependencies included with its release) in `%LOCALAPPDATA%\SupjavDesktop\mpv\`. The app checks this folder at launch, so restart it after copying. Official release: https://github.com/mpv-player/mpv/releases/tag/v0.42.0 (under **libmpv Windows → x86_64 LGPL**). Alternatively set the `SUPJAV_MPV_DLL` environment variable to the DLL's absolute path.
- **64-bit VLC 3.x**: Fallback native engine; must be installed separately if you want VLC fallback. VLC/libVLC is not bundled.
- **Microsoft Edge WebView2 Runtime** is required for integrated website playback. It normally ships with current Windows 11; on Windows 10 install Microsoft's Evergreen WebView2 Runtime. This app bundles the small Java-to-native integration, not a second Chromium engine.
- Touchscreen works as single-pointer mouse input in current Compose Desktop/AWT. Multitouch pinch or gesture navigation is not implemented.
- EVS and LUC default to **in-player WebView2 mode**, because their video URL may be generated dynamically in JavaScript. Other lines try the VLC resolver, then fall back to in-player WebView2 if no native URL can be used. The **外部浏览器** button remains available.
- Browser mode loads the selected **Supjav detail page** to preserve the legitimate iframe and referrer chain. You may need to click Play in the embedded page and complete ordinary site verification. Website-controlled behavior/advertising cannot be guaranteed or bypassed.
- mpv official LGPL binaries are LGPLv3; VLC/libVLC also have their own redistribution terms. **This source currently does not package libmpv or libVLC DLLs into MSI**. Review legal notices/source availability before distributing native binaries.

## Build / run

The `desktop/` directory is an **independent Gradle build**, keeping Android `main` and its build settings unchanged.

```powershell
gradle -p desktop test
gradle -p desktop run
gradle -p desktop packageMsi
```

GitHub Actions workflow: `.github/workflows/pc.yml` on branch `pc`; the MSI installer is uploaded as `supjav-desktop-windows` after a successful Windows build.

## Windows MSI in-place upgrades

Starting with the `0.2.x` installer series, Windows MSI packages have the
**same permanent UpgradeCode** and a **newer ProductVersion on every CI run**.
Download a newer MSI and install it over the existing `0.2.x` application.
Close Supjav Desktop before installing. The application name, publisher and
installation identity do not change, and there is no need to delete history
or favorites.

GitHub Actions passes the unique `github.run_number` to Gradle and checks
the generated MSI's ProductVersion and UpgradeCode before uploading it.
For an installer built locally, increment the number yourself:

```powershell
gradle -p desktop -PdesktopBuildNumber=123 test packageMsi
```

Important: the original `0.1.0` preview MSIs did **not** configure a stable
Windows UpgradeCode. Their automatically generated identity may differ from
the new installer. **Only once**, if the first `0.2.x` MSI does not replace
your older preview, close the app and uninstall `SupjavDesktop 0.1.0` from
Windows Settings > Apps > Installed apps, then install the new `0.2.x`
MSI. Do not delete `%LOCALAPPDATA%\SupjavDesktop`: the favorites, history
and diagnostics are stored separately and survive an ordinary uninstall.
Subsequent `0.2.x` installs can upgrade in place without that step.


## Architecture

```text
app/                         Android version (no changes)
desktop/
  build.gradle.kts            Standalone JVM/Compose desktop Gradle build
  src/main/kotlin/
    .../desktop/Main.kt           Window/navigation/fullscreen/player controls
    .../desktop/DesktopLibraryUi.kt Detail, poster browser and playlist panel
    .../desktop/DesktopController.kt  Feed, queue, cancellation and switching
    .../desktop/DesktopGateway.kt Testable site/resolve boundary
    .../desktop/DesktopVideoStore.kt Atomic JSON history and favorites
    .../desktop/DesktopGestureMath.kt Pure gesture calculations
    .../desktop/DesktopPlayerGroup.kt MPV → VLC selection and fallback
    .../desktop/DesktopNativePlayer.kt Shared native controls contract
    .../desktop/MpvBackend.kt JNA libmpv Windows HWND player
    .../desktop/VlcBackend.kt VLC events and touch input adapter
    .../util/Diagnostics.kt Redacted rotating desktop diagnostics
```

The desktop Gradle compile task links selected pure-Kotlin files from `app/`. Both platforms share parsing semantics and fixes; Android-specific Material3 UI, Android persistence, Media3 and SystemUI classes are **not** imported into desktop.

## Current limitations / follow-up

Native playback needs either official libmpv or installed libVLC. A successful HLS probe is not proof that a renderer can play the media; some streams enforce Origin/Referer or expiry. Physical Windows touch and packaged libmpv must be verified on a target device. The current embed uses AWT HWND (`wid`); a future GPU render API can be added for full Compose-native video overlays.

## Data and diagnostics

History and favorites are stored in `%LOCALAPPDATA%\SupjavDesktop\recent.json` and
`favorites.json`. They are local files and not synced with the Android app.
Open the diagnostics directory from the application's left navigation;
the log rotates between `events.log` and `events.previous.log`. Logged URLs
and secrets are redacted. If the site sends a verification challenge or
native VLC cannot send the same browser headers, try another server or
the explicit browser fallback. No anti-verification bypass is attempted.

## Validation

The PC CI runs shared parser tests, persistence tests, gesture mapping tests
and a fake-site test for stale pagination and playlist isolation before MSI
packaging. Native video playback and physical touchscreen controls must still
be checked on a real Windows device.

### Playback diagnostics

When reporting a black screen, open the left sidebar's diagnostics folder and
attach `events.log`. Native attempts include `DESKTOP_MPV_PLAY_REQUEST`, `DESKTOP_ENGINE_ATTEMPT`, `DESKTOP_PLAY_REQUEST` or
`DESKTOP_SOURCE_UNAVAILABLE`; browser fallback emits `DESKTOP_BROWSER_FALLBACK`
and initialization emits `DESKTOP_WEBVIEW_CREATED` or
`DESKTOP_WEBVIEW_INIT_FAILED`. These records contain no media token or signed URL.

## Upstream player references

- [mpv-player/mpv-examples](https://github.com/mpv-player/mpv-examples/tree/master/libmpv/java): Java/JNA native HWND embedding technique (the native binding is adapted, not copied as an external application).
- [AreChen/OpenPlayer](https://github.com/AreChen/OpenPlayer): separate video surface from UI overlays.
- [jellyfin/jellyfin-desktop](https://github.com/jellyfin/jellyfin-desktop): libmpv playback inside a desktop application.
- [numq/compose-desktop-media-player](https://github.com/numq/compose-desktop-media-player): multi-engine and Kotlin Compose player design.

When testing a playable native line (e.g. VAS), select **原生优先播放**.
Use the new **播放内核** choice to pick automatic / MPV / VLC. Only
a detected native engine is attempted. With neither installed, use
the original internal WebView2 path. EVS/LUC remain website-based until
a direct playable media URL is legitimately resolved.
