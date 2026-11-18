# Supjav Desktop (branch `pc`)

A Windows-first desktop application based on the latest Android `main` source. The Android app is left unchanged.

## What works in this first implementation

- Kotlin / Compose Desktop application, with a responsive Windows layout.
- Reuses **the exact pure Kotlin source files** from `../app/src/main/java` (no parser fork): listing, detail, CD-part grouping, mirror/line detection and the existing playback resolver.
- Home, category directory, search, day/week/month rankings, infinite feed paging, posters with code, a right-hand poster playlist, previous/next video.
- Local favorites and watching history with atomic JSON files, including migration from legacy Java Preferences (avoids the 8 KB per-value limit).
- VLCJ native playback for playable HLS/MP4 responses, including the selected HTTP Referer. A desktop-only diagnostics adapter redacts links and tokens.
- Single-touch/AWT gesture prototype: tap show/hide controls, double tap left/right -/+10s, hold side for temporary 2x (restore prior speed), horizontal swipe for timeline preview/commit, vertical left/right for video brightness/audio level with stable gesture baselines.
- Windows installer job; no dependence on Android SDK for this desktop Gradle project.
- A playback state driven by real VLC native events (loading, buffering, playing, paused, finished, error).
- Independent page and playlist pagination, cached feed navigation and per-feed scroll position, stale async request cancellation.
- Dedicated player-only fullscreen, search-focus-aware keyboard shortcuts, expanded actor/maker/tag directory and redacted disk diagnostics.

## Requirements

- Windows 10/11 x64, JDK 17 for local development, Gradle 9.4.1.
- **64-bit VLC 3.x must be installed separately** for the native player. VLC/libVLC is not bundled in the installer. Install VLC from the official VideoLAN source and restart the desktop app.
- Touchscreen works as single-pointer mouse input in current Compose Desktop/AWT. Multitouch pinch or gesture navigation is not implemented.
- Some server lines are website-only; use **网页兼容播放** to open the real Supjav detail page in the system browser. This initial desktop version does **not** embed WebView2 or auto-bypass website verification.
- VLCJ and libVLC have their own licensing and redistribution conditions. Review before producing a public release that bundles native libraries.

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
    .../desktop/VlcBackend.kt Native VLC events and AWT input adapter
    .../util/Diagnostics.kt Redacted rotating desktop diagnostics
```

The desktop Gradle compile task links selected pure-Kotlin files from `app/`. Both platforms share parsing semantics and fixes; Android-specific Material3 UI, Android persistence, Media3 and SystemUI classes are **not** imported into desktop.

## Current limitations / follow-up

Native playback depends on a locally installed libVLC and the site's authorization/expiry rules; not every streaming host can be played outside its website. Physical Windows touchscreen testing, hardware video brightness behavior and the packaged native player must be verified on the target device. Future upgrades can introduce WebView2 and a native Windows WM_POINTER adapter for multi-finger gestures without rewriting the shared parser or desktop pages.

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
