import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("org.jetbrains.compose") version "1.6.1"
    // v4.1.0: static analysis — catches common Kotlin issues at build time.
    // Run: ./gradlew detekt (or it runs automatically on `check`)
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

val appVersion: String by project

group = "com.gameperf"
version = appVersion

// Regenerate AppVersion.kt from gradle.properties every build so the runtime
// version always matches the build version. This prevents the v3.0.0 bug
// where AppVersion.NAME was hardcoded and never bumped, causing the
// auto-updater to offer the same version forever.
val generateAppVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/sources/appversion/kotlin")
    outputs.dir(outputDir)
    inputs.property("appVersion", appVersion)
    doLast {
        val pkgDir = outputDir.get().asFile.resolve("com/gameperf/desktop/core")
        pkgDir.mkdirs()
        pkgDir.resolve("AppVersion.kt").writeText(
            """
            package com.gameperf.desktop.core

            // AUTO-GENERATED at build time from gradle.properties.
            // DO NOT EDIT — change `appVersion` in gradle.properties instead.
            object AppVersion {
                const val NAME = "$appVersion"
                const val FULL = "Game Perf Desktop v${'$'}NAME"
            }
            """.trimIndent() + "\n"
        )
    }
}

kotlin {
    sourceSets["main"].kotlin.srcDir(generateAppVersion.map { it.outputs.files })
}

tasks.named("compileKotlin") {
    dependsOn(generateAppVersion)
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
    // v4.1.0: replaces hand-rolled JSON parsing in SessionHistory and SidecarClient.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // v4.2.8: Google Drive API removed. The DriveSync code was 450 LOC of OAuth
    // + Drive-upload/download plumbing that required the user to obtain a
    // credentials.json via Google Cloud Console, enable the Drive API, and
    // maintain a shared team folder ID. Too much friction for the value
    // delivered. Session sharing is now via manual .gameperf file export
    // (see SessionPack) — the user picks a save location, the file is a
    // self-contained ZIP, and they can share it by any means (email, Slack,
    // shared folder, USB). Zero cloud dependencies, zero OAuth.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

compose.desktop {
    application {
        mainClass = "com.gameperf.desktop.MainKt"

        jvmArgs += listOf(
            // v4.2.0: cap heap to prevent the video player from starving the host OS
            // (was using 5+ GB on long sessions, OOM'd Antigravity/Chrome).
            "-Xmx2048m",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=100",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)

            packageName = "GamePerf"
            packageVersion = appVersion
            description = "Android Game Performance Tool"
            vendor = "GamePerf"

            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
                bundleID = "com.gameperf.desktop"
            }

            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "GamePerf"
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// v4.1.0: copy sidecar Python package next to the uber JAR so iOS support
// works in production. The sidecar/ directory ends up beside the .jar files
// in build/compose/jars/sidecar/ — from there, findSidecarDir() picks it up.
tasks.register<Copy>("copySidecarToJars") {
    from("sidecar") {
        include("gameperf_sidecar/**")
        include("pyproject.toml")
        include("requirements.txt")
        include("requirements-lock.txt")
    }
    into(layout.buildDirectory.dir("compose/jars/sidecar"))
}

tasks.matching { it.name.startsWith("packageUberJar") }.configureEach {
    finalizedBy("copySidecarToJars")
}

// v4.1.0: detekt configuration — lenient baseline for an existing project.
// Start with defaults, suppress known high-count rules that require large refactors.
// Tighten thresholds gradually over time.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("detekt.yml"))
    ignoreFailures = false  // v4.1.0+: build fails on detekt findings
}
