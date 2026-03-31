import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.compose") version "1.6.1"
}

val appVersion: String by project

group = "com.gameperf"
version = appVersion

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
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

compose.desktop {
    application {
        mainClass = "com.gameperf.desktop.MainKt"

        jvmArgs += listOf(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
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
