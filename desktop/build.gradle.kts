import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.9.3"
}

group = "com.yagay.supjav"

// Each Windows CI run gets a strictly increasing MSI version. Keeping the
// same ProductVersion (previously always 0.1.0) prevents a major upgrade.
// For local packages, pass -PdesktopBuildNumber=N; otherwise use 1.
val desktopBuildNumber = providers.gradleProperty("desktopBuildNumber")
    .orElse(providers.environmentVariable("GITHUB_RUN_NUMBER"))
    .getOrElse("1")
    .toIntOrNull()
    ?: error("desktopBuildNumber must be a positive integer")
require(desktopBuildNumber in 1..65535) {
    "Windows MSI build number must be between 1 and 65535"
}
val desktopPackageVersion = "0.2.$desktopBuildNumber"
version = desktopPackageVersion

kotlin {
    jvmToolchain(17)
}

// Compile Android's pure Kotlin source files directly as desktop sources.
// The parsing and CD/queue algorithms stay authoritative in ONE location.
// Android UI, SQLite, Media3, and Android Diagnostics are deliberately excluded.
val sharedKotlin = fileTree("../app/src/main/java") {
    include(
        "com/yagay/supjav/model/VideoModels.kt",
        "com/yagay/supjav/data/SupjavSource.kt",
        "com/yagay/supjav/data/MediaSourceDetector.kt",
        "com/yagay/supjav/data/SupjavServerAdapters.kt",
        "com/yagay/supjav/data/PlaybackResolver.kt",
        "com/yagay/supjav/data/PlaybackChallengeDetector.kt",
        "com/yagay/supjav/data/MediaGateway.kt",
        "com/yagay/supjav/data/SitePlayerWrapper.kt",
        "com/yagay/supjav/player/VideoParts.kt",
        "com/yagay/supjav/player/PlaybackDefaults.kt",
        "com/yagay/supjav/player/PlaybackQueuePolicy.kt"
    )
}
tasks.named<KotlinCompile>("compileKotlin") {
    source(sharedKotlin)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.22.1")
    implementation("org.json:json:20250517")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("uk.co.caprica:vlcj:4.11.0")
    // Windows Edge WebView2 for JS-dependent EVS/LUC/VAS provider pages.
    // No second Chromium bundle: Windows supplies the Evergreen WebView2 runtime.
    implementation("ca.weblite:webview:1.11.2")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.yagay.supjav.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "SupjavDesktop"
            packageVersion = desktopPackageVersion
            description = "Supjav desktop browsing and playback"
            vendor = "YagaY"
            windows {
                // Never change this UUID for future SupjavDesktop releases.
                // All 0.2+ MSI/EXE builds share the same Windows UpgradeCode.
                upgradeUuid = "6980c41e-4740-523c-8ca1-c547c898fc39"
                menuGroup = "Supjav Desktop"
                shortcut = true
            }
        }
    }
}

tasks.test {
    useJUnit()
}
