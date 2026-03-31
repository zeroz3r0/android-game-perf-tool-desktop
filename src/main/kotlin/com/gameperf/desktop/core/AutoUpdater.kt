package com.gameperf.desktop.core

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

const val GITHUB_OWNER = "zeroz3r0"
const val GITHUB_REPO = "android-game-perf-tool-desktop"
const val CURRENT_VERSION = AppVersion.NAME

object AutoUpdater {

    data class ReleaseInfo(
        val tagName: String,
        val version: String,
        val name: String,
        val body: String,
        val publishedAt: String,
        val jarUrl: String?,
        val htmlUrl: String
    )

    /**
     * Check GitHub API for latest release.
     * Returns null if no update is available or on any error.
     */
    fun checkForUpdate(): ReleaseInfo? {
        return try {
            val url = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "GamePerfDesktop/${AppVersion.NAME}")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) return null

            val json = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()

            val tagName = extractJsonString(json, "tag_name") ?: return null
            val version = tagName.removePrefix("v").removePrefix("V")
            val name = extractJsonString(json, "name") ?: tagName
            val body = extractJsonString(json, "body") ?: ""
            val publishedAt = extractJsonString(json, "published_at") ?: ""
            val htmlUrl = extractJsonString(json, "html_url") ?: ""

            // Find JAR asset URL — look for .jar in the assets array
            val jarUrl = extractJarAssetUrl(json)

            ReleaseInfo(tagName, version, name, body, publishedAt, jarUrl, htmlUrl)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compare semantic versions: returns true if [remote] is newer than [current].
     * Supports "major.minor.patch" format.
     */
    fun isNewer(remote: String, current: String): Boolean {
        val r = remote.removePrefix("v").removePrefix("V").split(".").mapNotNull { it.toIntOrNull() }
        val c = current.removePrefix("v").removePrefix("V").split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }

    /**
     * Download JAR from GitHub release assets to a temp file.
     * Calls [onProgress] with values from 0.0 to 1.0.
     * Returns the downloaded File or null on error.
     */
    fun downloadUpdate(url: String, onProgress: (Float) -> Unit): File? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "GamePerfDesktop/${AppVersion.NAME}")
            conn.setRequestProperty("Accept", "application/octet-stream")
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }

            val totalSize = conn.contentLengthLong
            val tempFile = File.createTempFile("gameperf-update-", ".jar")
            tempFile.deleteOnExit()

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0) {
                            onProgress((downloaded.toFloat() / totalSize).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            conn.disconnect()
            onProgress(1f)
            tempFile
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Result of applying an update.
     * [success] = true if the update was applied (or staged for manual replacement).
     * [needsManualRestart] = true if the app couldn't auto-restart (e.g., running from Gradle).
     * [updatedJarPath] = path to the new JAR file (for showing to user).
     * [message] = human-readable status message.
     */
    data class UpdateResult(
        val success: Boolean,
        val needsManualRestart: Boolean = false,
        val updatedJarPath: String = "",
        val message: String = ""
    )

    /**
     * Apply the downloaded update.
     *
     * If running from a JAR (production): replaces the JAR via a shell script and restarts.
     * If running from Gradle/IDE (development): saves the JAR to ~/GamePerf Reports/updates/
     * and tells the user where to find it.
     */
    fun applyUpdate(downloadedFile: File): UpdateResult {
        return try {
            val currentJar = detectCurrentJar()

            if (currentJar != null) {
                // === PRODUCTION MODE: running from JAR — auto-replace and restart ===
                val jarDir = currentJar.parentFile ?: return UpdateResult(false, message = "No se pudo determinar el directorio del JAR")
                val newJar = File(jarDir, currentJar.name + ".new")
                val bakJar = File(jarDir, currentJar.name + ".bak")

                downloadedFile.copyTo(newJar, overwrite = true)

                val os = System.getProperty("os.name").lowercase()
                if (os.contains("win")) {
                    createWindowsUpdateScript(currentJar, newJar, bakJar)
                } else {
                    createUnixUpdateScript(currentJar, newJar, bakJar)
                }

                System.exit(0)
                UpdateResult(true) // unreachable
            } else {
                // === DEVELOPMENT MODE: running from Gradle/IDE — save JAR for manual use ===
                val updatesDir = File(System.getProperty("user.home"), "GamePerf Reports/updates")
                updatesDir.mkdirs()
                val targetFile = File(updatesDir, "GamePerf-latest.jar")
                downloadedFile.copyTo(targetFile, overwrite = true)

                // Try to open the folder for the user
                try {
                    val os = System.getProperty("os.name").lowercase()
                    when {
                        os.contains("mac") -> ProcessBuilder("open", updatesDir.absolutePath).start()
                        os.contains("win") -> ProcessBuilder("explorer", updatesDir.absolutePath).start()
                        else -> ProcessBuilder("xdg-open", updatesDir.absolutePath).start()
                    }
                } catch (_: Exception) { /* ignore */ }

                UpdateResult(
                    success = true,
                    needsManualRestart = true,
                    updatedJarPath = targetFile.absolutePath,
                    message = "JAR descargado en: ${targetFile.absolutePath}\nCierra la app y ejecútalo con: java -jar \"${targetFile.absolutePath}\""
                )
            }
        } catch (e: Exception) {
            UpdateResult(false, message = "Error al aplicar actualización: ${e.message}")
        }
    }

    // ═══════ Private Helpers ═══════

    private fun detectCurrentJar(): File? {
        return try {
            val location = AutoUpdater::class.java.protectionDomain?.codeSource?.location
            if (location != null) {
                val file = File(location.toURI())
                if (file.isFile && file.name.endsWith(".jar")) file else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun createUnixUpdateScript(currentJar: File, newJar: File, bakJar: File) {
        // Use the exact Java binary that's running this process (not just "java" from PATH)
        val javaHome = System.getProperty("java.home")
        val javaBin = File(javaHome, "bin/java").absolutePath

        val script = File(currentJar.parentFile, ".gameperf-update.sh")
        script.writeText("""#!/bin/bash
sleep 2
if [ -f "${bakJar.absolutePath}" ]; then rm -f "${bakJar.absolutePath}"; fi
mv "${currentJar.absolutePath}" "${bakJar.absolutePath}"
mv "${newJar.absolutePath}" "${currentJar.absolutePath}"
nohup "$javaBin" -jar "${currentJar.absolutePath}" > /dev/null 2>&1 &
sleep 1
rm -f "${script.absolutePath}"
""")
        script.setExecutable(true)
        ProcessBuilder("bash", script.absolutePath)
            .directory(currentJar.parentFile)
            .redirectErrorStream(true)
            .start()
    }

    private fun createWindowsUpdateScript(currentJar: File, newJar: File, bakJar: File) {
        val javaHome = System.getProperty("java.home")
        val javaBin = File(javaHome, "bin/java.exe").absolutePath

        val script = File(currentJar.parentFile, "gameperf-update.bat")
        script.writeText("""@echo off
timeout /t 2 /nobreak >nul
if exist "${bakJar.absolutePath}" del /f "${bakJar.absolutePath}"
move /y "${currentJar.absolutePath}" "${bakJar.absolutePath}"
move /y "${newJar.absolutePath}" "${currentJar.absolutePath}"
start "" "$javaBin" -jar "${currentJar.absolutePath}"
timeout /t 1 /nobreak >nul
del /f "${script.absolutePath}"
""")
        ProcessBuilder("cmd", "/c", "start", "/min", "", script.absolutePath)
            .directory(currentJar.parentFile)
            .redirectErrorStream(true)
            .start()
    }

    /** Extract a simple top-level string value from JSON without a parser. */
    private fun extractJsonString(json: String, key: String): String? {
        // Match "key" : "value" handling escaped quotes in value
        val pattern = """"$key"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.replace("\\n", "\n")
            ?.replace("\\t", "\t")
    }

    /**
     * Extract the first .jar asset's browser_download_url from the GitHub release JSON.
     * The assets array contains objects with "browser_download_url" fields.
     */
    private fun extractJarAssetUrl(json: String): String? {
        // Find all browser_download_url values
        val pattern = """"browser_download_url"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        for (match in pattern.findAll(json)) {
            val url = match.groupValues[1]
            if (url.endsWith(".jar")) return url
        }
        return null
    }
}
