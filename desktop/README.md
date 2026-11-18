# Supjav Desktop (branch `pc`)

A Windows-first desktop application based on the latest Android `main` source. The Android app is left unchanged.

## What works in this first implementation

- Kotlin / Compose Desktop application, with a responsive Windows layout.
- Reuses **the exact pure Kotlin source files** from `../app/src/main/java` (no parser fork): listing, detail, CD-part grouping, mirror/line detection and the existing playback resolver.
- Home, category directory, search, day/week/month rankings, infinite feed paging, posters with code, a right-hand poster playlist, previous/next video.
- Local favorites and watching history (Java Preferences).
- VLCJ native playback for playable HLS/MP4 responses, including the selected HTTP Referer. A desktop-only diagnostics adapter redacts links and tokens.
- Single-touch/AWT gesture prototype: tap show/hide controls, double tap left/right -/+10s, hold side for temporary 2x, horizontal swipe for timeline preview/commit, vertical left/right for video brightness/audio level.
- Windows installer job; no dependence on Android SDK for this desktop Gradle project.

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

## Architecture

```text
app/                         Android version (no changes)
desktop/
  build.gradle.kts            Standalone JVM/Compose desktop Gradle build
  src/main/kotlin/
    .../desktop/Main.kt       Windows navigation, poster browser and controls
    .../desktop/DesktopController.kt  Feed/queue/pagination/resolve logic
    .../desktop/VlcBackend.kt         Native video + one-finger gestures
    .../util/Diagnostics.kt   Desktop-specific safe diagnostics
```

The desktop Gradle compile task links selected pure-Kotlin files from `app/`. Both platforms share parsing semantics and fixes; Android-specific Material3 UI, Android persistence, Media3 and SystemUI classes are **not** imported into desktop.

## Current limitations / follow-up

Native playback depends on a locally installed libVLC and the site's authorization/expiry rules; not every streaming host can be played outside its website. Physical Windows touchscreen testing, hardware video brightness behavior and the packaged native player must be verified on the target device. Future upgrades can introduce WebView2 and a native Windows WM_POINTER adapter for multi-finger gestures without rewriting the shared parser or desktop pages.
