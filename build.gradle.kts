import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.compose") version "1.6.1"
}

group = "com.gameperf"
version = "2.0.0"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// Detect platform for JavaFX classifier
val javafxVersion = "21.0.2"
val osName: String = System.getProperty("os.name").lowercase()
val osArch: String = System.getProperty("os.arch").lowercase()
val javafxClassifier: String = when {
    osName.contains("mac") && osArch.contains("aarch64") -> "mac-aarch64"
    osName.contains("mac") -> "mac"
    osName.contains("win") -> "win"
    osName.contains("linux") && osArch.contains("aarch64") -> "linux-aarch64"
    else -> "linux"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")

    // JavaFX for embedded video playback (MediaView via SwingPanel)
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxClassifier")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxClassifier")
    implementation("org.openjfx:javafx-media:$javafxVersion:$javafxClassifier")
    implementation("org.openjfx:javafx-swing:$javafxVersion:$javafxClassifier")
}

compose.desktop {
    application {
        mainClass = "com.gameperf.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)

            packageName = "GamePerf"
            packageVersion = "2.0.0"
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
