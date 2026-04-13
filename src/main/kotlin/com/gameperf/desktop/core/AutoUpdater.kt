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

/**
 * How the running app is installed on disk. Different installation types need different
 * relaunch strategies after self-updating: a fat JAR can be re-launched with `java -jar`,
 * but a jpackage `.app` bundle MUST be re-launched via its native launcher (`open -n` on
 * macOS) so the bundle's JVM options (`-Dskiko.library.path`, `-Dcompose.application.resources.dir`,
 * `-Xdock:name`, etc.) from `Contents/app/<name>.cfg` are honored. Without those options
 * Compose Desktop crashes with `UnsatisfiedLinkError` on Skiko's native libs.
 */
enum class InstallationType {
    /** Standalone fat JAR — works with `java -jar X.jar`. Typical: `~/GamePerf/GamePerf.jar`. */
    FAT_JAR_STANDALONE,
    /** macOS jpackage `.app` bundle — needs `open -n /Applications/X.app`. */
    MACOS_APP_BUNDLE,
    /** Windows jpackage installer — needs `start "" "<launcher.exe>"`. */
    WINDOWS_APP_BUNDLE,
    /** Linux jpackage `/opt/X` or `/usr/lib/X` install — needs the native launcher. */
    LINUX_NATIVE_PACKAGE,
    /** Running from Gradle / IDE — no JAR to replace, save update for manual install. */
    DEV_MODE
}

/**
 * Snapshot of how the current process was launched. Computed by [AutoUpdater.detectInstallation].
 *
 * @property type   The installation type (see [InstallationType]).
 * @property currentJar The JAR file that should be replaced on update. `null` for [InstallationType.DEV_MODE].
 * @property bundleRoot For bundles, the root directory (e.g. `/Applications/X.app` or `C:\Program Files\X`).
 *                     `null` for [InstallationType.FAT_JAR_STANDALONE] and [InstallationType.DEV_MODE].
 * @property launcher The native launcher binary used to relaunch the app after update.
 *                    `null` for fat JAR / dev mode (those use `java -jar` directly).
 */
data class InstallationInfo(
    val type: InstallationType,
    val currentJar: File?,
    val bundleRoot: File?,
    val launcher: File?
)

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
     * Last error encountered by [checkForUpdate], for debugging.
     * Populated on both `Exception` and `Error` failures (including `StackOverflowError`
     * from the old regex-based parser that made the whole check fail silently).
     */
    @Volatile
    var lastCheckError: String? = null
        private set

    /**
     * Check GitHub API for latest release.
     * Returns null if no update is available or on any error. The reason is recorded in
     * [lastCheckError].
     *
     * NOTE: catches `Throwable`, not `Exception`. The old implementation only caught
     * `Exception`, so `StackOverflowError` from catastrophic regex backtracking on long
     * release bodies escaped silently and stopped the update banner from ever appearing.
     */
    fun checkForUpdate(): ReleaseInfo? {
        lastCheckError = null
        return try {
            val url = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "GamePerfDesktop/${AppVersion.NAME}")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) {
                lastCheckError = "HTTP ${conn.responseCode} from GitHub API"
                return null
            }

            val json = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()

            val tagName = extractJsonString(json, "tag_name") ?: run {
                lastCheckError = "Could not extract tag_name from GitHub response"
                return null
            }
            val version = tagName.removePrefix("v").removePrefix("V")
            val name = extractJsonString(json, "name") ?: tagName
            val body = extractJsonString(json, "body") ?: ""
            val publishedAt = extractJsonString(json, "published_at") ?: ""
            val htmlUrl = extractJsonString(json, "html_url") ?: ""

            // Find JAR asset URL — look for .jar in the assets array
            val jarUrl = extractJarAssetUrl(json)

            ReleaseInfo(tagName, version, name, body, publishedAt, jarUrl, htmlUrl)
        } catch (t: Throwable) {
            lastCheckError = "${t.javaClass.simpleName}: ${t.message ?: "no details"}"
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
     *
     * On failure, the reason is recorded in [lastDownloadError] so the UI can surface it
     * instead of the generic "Error al descargar la actualizacion" message that hid every
     * real cause (timeout, 404, redirect to login, disk full, etc.).
     */
    @Volatile
    var lastDownloadError: String? = null
        private set

    fun downloadUpdate(url: String, onProgress: (Float) -> Unit): File? {
        lastDownloadError = null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "GamePerfDesktop/${AppVersion.NAME}")
            conn.setRequestProperty("Accept", "application/octet-stream")
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true

            if (conn.responseCode != 200) {
                lastDownloadError = "HTTP ${conn.responseCode} desde GitHub al descargar el JAR"
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

            // Sanity check: if the file is suspiciously small, treat as failed download.
            // GitHub sometimes serves a 0-byte response under heavy load.
            if (tempFile.length() < 1024) {
                lastDownloadError = "Descarga truncada (${tempFile.length()} bytes). Reintenta en unos segundos."
                tempFile.delete()
                return null
            }
            tempFile
        } catch (e: Exception) {
            lastDownloadError = "${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
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

    /** Minimum size (bytes) for a JAR to be considered an "uber JAR" with all deps embedded.
     *  jpackage `.app` bundles ship a single fat JAR with all Compose Desktop / Skiko deps;
     *  if the downloaded update is smaller than this, replacing the bundle JAR with a thin
     *  JAR would crash the bundle on next launch (NoClassDefFoundError everywhere). */
    private const val MIN_UBER_JAR_BYTES = 50_000_000L

    /**
     * Apply the downloaded update.
     *
     * Branches by installation type (see [InstallationType]):
     *
     * - **FAT_JAR_STANDALONE**: replace JAR + relaunch with `java -jar` (works for users
     *   running `~/GamePerf/GamePerf.jar`).
     * - **MACOS_APP_BUNDLE**: replace JAR inside `.app/Contents/app/` + relaunch with
     *   `open -n <bundleRoot>` so the native launcher reads the bundle's `.cfg` and applies
     *   the JVM options (`-Dskiko.library.path`, etc.). Validates that the downloaded JAR is
     *   a fat uber-JAR (≥ 50 MB) — replacing a bundle JAR with a thin JAR would crash the app.
     * - **WINDOWS_APP_BUNDLE**: replace JAR + relaunch via `start "" "<launcher.exe>"`.
     * - **LINUX_NATIVE_PACKAGE**: replace JAR + relaunch via the native launcher binary
     *   (or `xdg-open` on the bundle root as fallback).
     * - **DEV_MODE**: save the JAR to `~/GamePerf Reports/updates/` for manual install.
     *
     * Every update attempt writes a detailed log to `~/GamePerf Reports/updates/last-update.log`
     * for post-mortem debugging.
     */
    fun applyUpdate(downloadedFile: File): UpdateResult {
        return try {
            val info = detectInstallation()

            when (info.type) {
                InstallationType.DEV_MODE -> applyUpdateDevMode(downloadedFile)

                InstallationType.FAT_JAR_STANDALONE -> {
                    val currentJar = info.currentJar
                        ?: return UpdateResult(false, message = "No se pudo determinar el JAR actual")
                    applyUpdateFatJar(downloadedFile, currentJar)
                }

                InstallationType.MACOS_APP_BUNDLE -> {
                    val currentJar = info.currentJar
                        ?: return UpdateResult(false, message = "No se pudo determinar el JAR del bundle")
                    val bundleRoot = info.bundleRoot
                        ?: return UpdateResult(false, message = "No se pudo determinar el root del .app bundle")
                    applyUpdateMacOSBundle(downloadedFile, currentJar, bundleRoot)
                }

                InstallationType.WINDOWS_APP_BUNDLE -> {
                    val currentJar = info.currentJar
                        ?: return UpdateResult(false, message = "No se pudo determinar el JAR del bundle")
                    val launcher = info.launcher
                        ?: return UpdateResult(false, message = "No se pudo localizar el launcher .exe del bundle")
                    applyUpdateWindowsBundle(downloadedFile, currentJar, launcher)
                }

                InstallationType.LINUX_NATIVE_PACKAGE -> {
                    val currentJar = info.currentJar
                        ?: return UpdateResult(false, message = "No se pudo determinar el JAR del paquete nativo")
                    applyUpdateLinuxPackage(downloadedFile, currentJar, info.launcher, info.bundleRoot)
                }
            }
        } catch (e: Exception) {
            UpdateResult(false, message = "Error al aplicar actualización: ${e.message}")
        }
    }

    // ═══════ Apply Update — per-installation-type implementations ═══════

    private fun applyUpdateDevMode(downloadedFile: File): UpdateResult {
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

        return UpdateResult(
            success = true,
            needsManualRestart = true,
            updatedJarPath = targetFile.absolutePath,
            message = "JAR descargado en: ${targetFile.absolutePath}\nCierra la app y ejecútalo con: java -jar \"${targetFile.absolutePath}\""
        )
    }

    private fun applyUpdateFatJar(downloadedFile: File, currentJar: File): UpdateResult {
        val jarDir = currentJar.parentFile
            ?: return UpdateResult(false, message = "No se pudo determinar el directorio del JAR")
        val newJar = File(jarDir, currentJar.name + ".new")
        val bakJar = File(jarDir, currentJar.name + ".bak")

        downloadedFile.copyTo(newJar, overwrite = true)

        val os = System.getProperty("os.name").lowercase()
        if (os.contains("win")) {
            createWindowsUpdateScript(currentJar, newJar, bakJar, relaunchCommand = winRelaunchJavaJar(currentJar))
        } else {
            createUnixUpdateScript(currentJar, newJar, bakJar, relaunchCommand = unixRelaunchJavaJar(currentJar))
        }

        System.exit(0)
        return UpdateResult(true) // unreachable
    }

    private fun applyUpdateMacOSBundle(downloadedFile: File, currentJar: File, bundleRoot: File): UpdateResult {
        // Validate uber-JAR — a thin JAR would crash the bundle on next launch.
        if (downloadedFile.length() < MIN_UBER_JAR_BYTES) {
            throw IllegalStateException(
                "El JAR descargado (${downloadedFile.length()} bytes) es demasiado pequeño para ser un uber-JAR. " +
                "La actualización para .app bundles requiere un JAR completo con todas las dependencias embebidas."
            )
        }

        val jarDir = currentJar.parentFile
            ?: return UpdateResult(false, message = "No se pudo determinar el directorio del JAR del bundle")
        val newJar = File(jarDir, currentJar.name + ".new")
        val bakJar = File(jarDir, currentJar.name + ".bak")

        downloadedFile.copyTo(newJar, overwrite = true)

        // Relaunch via `open -n` so the native launcher reads Contents/app/*.cfg and applies
        // -Dskiko.library.path / -Dcompose.application.resources.dir / -Xdock:name.
        val relaunchCommand = "open -n ${shellQuote(bundleRoot.absolutePath)}"
        createUnixUpdateScript(currentJar, newJar, bakJar, relaunchCommand = relaunchCommand)

        System.exit(0)
        return UpdateResult(true) // unreachable
    }

    private fun applyUpdateWindowsBundle(downloadedFile: File, currentJar: File, launcher: File): UpdateResult {
        if (downloadedFile.length() < MIN_UBER_JAR_BYTES) {
            throw IllegalStateException(
                "El JAR descargado (${downloadedFile.length()} bytes) es demasiado pequeño para ser un uber-JAR. " +
                "La actualización para installers nativos requiere un JAR completo con todas las dependencias embebidas."
            )
        }

        val jarDir = currentJar.parentFile
            ?: return UpdateResult(false, message = "No se pudo determinar el directorio del JAR del bundle")
        val newJar = File(jarDir, currentJar.name + ".new")
        val bakJar = File(jarDir, currentJar.name + ".bak")

        downloadedFile.copyTo(newJar, overwrite = true)

        // Relaunch via the native launcher .exe so its bundled .cfg is honored.
        val relaunchCommand = """start "" "${launcher.absolutePath}"""" // Windows: double quotes are safe (no $() expansion)
        createWindowsUpdateScript(currentJar, newJar, bakJar, relaunchCommand = relaunchCommand)

        System.exit(0)
        return UpdateResult(true) // unreachable
    }

    private fun applyUpdateLinuxPackage(
        downloadedFile: File,
        currentJar: File,
        launcher: File?,
        bundleRoot: File?
    ): UpdateResult {
        if (downloadedFile.length() < MIN_UBER_JAR_BYTES) {
            throw IllegalStateException(
                "El JAR descargado (${downloadedFile.length()} bytes) es demasiado pequeño para ser un uber-JAR. " +
                "La actualización para paquetes nativos requiere un JAR completo con todas las dependencias embebidas."
            )
        }

        val jarDir = currentJar.parentFile
            ?: return UpdateResult(false, message = "No se pudo determinar el directorio del JAR del paquete")
        val newJar = File(jarDir, currentJar.name + ".new")
        val bakJar = File(jarDir, currentJar.name + ".bak")

        downloadedFile.copyTo(newJar, overwrite = true)

        // Prefer the native launcher; fall back to xdg-open on the bundle root.
        val relaunchCommand = when {
            launcher != null && launcher.canExecute() ->
                "nohup ${shellQuote(launcher.absolutePath)} > /dev/null 2>&1 &"
            bundleRoot != null ->
                "xdg-open ${shellQuote(bundleRoot.absolutePath)}"
            else -> {
                val javaHome = System.getProperty("java.home")
                val javaBin = File(javaHome, "bin/java").absolutePath
                "nohup ${shellQuote(javaBin)} -jar ${shellQuote(currentJar.absolutePath)} > /dev/null 2>&1 &"
            }
        }
        createUnixUpdateScript(currentJar, newJar, bakJar, relaunchCommand = relaunchCommand)

        System.exit(0)
        return UpdateResult(true) // unreachable
    }

    private fun unixRelaunchJavaJar(currentJar: File): String {
        val javaHome = System.getProperty("java.home")
        val javaBin = File(javaHome, "bin/java").absolutePath
        return "nohup ${shellQuote(javaBin)} -jar ${shellQuote(currentJar.absolutePath)} > /dev/null 2>&1 &"
    }

    private fun winRelaunchJavaJar(currentJar: File): String {
        val javaHome = System.getProperty("java.home")
        val javaBin = File(javaHome, "bin/java.exe").absolutePath
        return """start "" "$javaBin" -jar "${currentJar.absolutePath}"""" // Windows: double quotes are safe (no $() expansion)
    }

    // ═══════ Installation Detection ═══════

    /**
     * Detect how the current process was launched. See [InstallationInfo].
     *
     * Detection rules:
     * 1. If `codeSource.location` is null or not a `.jar` file → [InstallationType.DEV_MODE].
     * 2. If the JAR path contains `.app/Contents/app/` (macOS jpackage):
     *    - Walk up to the `*.app` directory.
     *    - Check that `Contents/MacOS/<name>` exists and is executable.
     *    - If yes → [InstallationType.MACOS_APP_BUNDLE]. If no → fall back to fat JAR.
     * 3. If the JAR path contains `\app\` or `/app/` AND has a sibling `<name>.exe`
     *    two levels up (Windows jpackage layout: `MyApp/app/main.jar` + `MyApp/MyApp.exe`)
     *    → [InstallationType.WINDOWS_APP_BUNDLE].
     * 4. If the JAR path is under `/opt/`, `/usr/lib/`, or contains `/lib/app/`
     *    → [InstallationType.LINUX_NATIVE_PACKAGE].
     * 5. Otherwise → [InstallationType.FAT_JAR_STANDALONE].
     */
    fun detectInstallation(): InstallationInfo = detectInstallation(null)

    /**
     * Same as [detectInstallation] but accepts an explicit JAR path for tests.
     * Tests can't easily mock `protectionDomain.codeSource`, so they pass a fake JAR path
     * pointing into a temporary directory tree that mimics the real installation layout.
     */
    internal fun detectInstallation(jarPathOverride: File?): InstallationInfo {
        val jarPath = jarPathOverride ?: detectCurrentJar()

        if (jarPath == null) {
            return InstallationInfo(InstallationType.DEV_MODE, currentJar = null, bundleRoot = null, launcher = null)
        }

        val absPath = jarPath.absolutePath

        // ── macOS jpackage .app bundle ──
        // Layout: <root>/Foo.app/Contents/app/<jar> + <root>/Foo.app/Contents/MacOS/Foo
        if (absPath.contains("${File.separator}Contents${File.separator}app${File.separator}")) {
            val bundleRoot = findAncestorEndingWith(jarPath, ".app")
            if (bundleRoot != null) {
                val bundleName = bundleRoot.name.removeSuffix(".app")
                val launcher = File(bundleRoot, "Contents/MacOS/$bundleName")
                if (launcher.exists() && launcher.canExecute()) {
                    return InstallationInfo(
                        type = InstallationType.MACOS_APP_BUNDLE,
                        currentJar = jarPath,
                        bundleRoot = bundleRoot,
                        launcher = launcher
                    )
                }
                // Bundle layout looks right but launcher is missing/non-exec → fall back.
            }
        }

        // ── Windows jpackage installer ──
        // Layout: <root>\MyApp\app\<jar> + <root>\MyApp\MyApp.exe
        // Detect by: jar path contains "\app\" (or "/app/" on cross-platform paths) AND
        // grandparent directory contains a .exe file with the same basename as the grandparent.
        if (absPath.contains("${File.separator}app${File.separator}") || absPath.contains("/app/")) {
            val appDir = jarPath.parentFile
            val installRoot = appDir?.parentFile
            if (installRoot != null && appDir.name == "app") {
                val expectedExe = File(installRoot, "${installRoot.name}.exe")
                if (expectedExe.exists()) {
                    return InstallationInfo(
                        type = InstallationType.WINDOWS_APP_BUNDLE,
                        currentJar = jarPath,
                        bundleRoot = installRoot,
                        launcher = expectedExe
                    )
                }
            }
        }

        // ── Linux jpackage native package ──
        // Layout: /opt/myapp/lib/app/<jar>  or  /usr/lib/myapp/lib/app/<jar>
        // Native launcher: /opt/myapp/bin/myapp  (or under /usr/bin/myapp)
        val isLinuxPackage = absPath.startsWith("/opt/") ||
            absPath.startsWith("/usr/lib/") ||
            absPath.contains("/lib/app/")
        if (isLinuxPackage) {
            // Walk up from .../<install>/lib/app/<jar> → install root = <install>
            val installRoot = findInstallRootForLinuxPackage(jarPath)
            val launcher = if (installRoot != null) {
                val candidate = File(installRoot, "bin/${installRoot.name}")
                if (candidate.exists() && candidate.canExecute()) candidate else null
            } else null
            return InstallationInfo(
                type = InstallationType.LINUX_NATIVE_PACKAGE,
                currentJar = jarPath,
                bundleRoot = installRoot,
                launcher = launcher
            )
        }

        // ── Default: standalone fat JAR ──
        return InstallationInfo(
            type = InstallationType.FAT_JAR_STANDALONE,
            currentJar = jarPath,
            bundleRoot = null,
            launcher = null
        )
    }

    /** Walk up the parent chain until we find a directory whose name ends with [suffix]. */
    private fun findAncestorEndingWith(start: File, suffix: String): File? {
        var current: File? = start.parentFile
        while (current != null) {
            if (current.name.endsWith(suffix)) return current
            current = current.parentFile
        }
        return null
    }

    /** For a path like `.../<install>/lib/app/main.jar`, return the `<install>` dir. */
    private fun findInstallRootForLinuxPackage(jarPath: File): File? {
        // jarPath = .../<install>/lib/app/main.jar  →  parent.parent.parent = <install>
        val appDir = jarPath.parentFile ?: return null
        if (appDir.name != "app") return null
        val libDir = appDir.parentFile ?: return null
        if (libDir.name != "lib") return null
        return libDir.parentFile
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

    /**
     * Single-quote a string for safe embedding in a bash script. Single quotes prevent
     * ALL shell expansion ($, `, \, etc.). Any literal single quote within the value
     * is escaped as `'\''` (end quote, escaped literal quote, start quote).
     */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Build and launch a defensive bash update script. The script:
     *  - logs every step to `~/GamePerf Reports/updates/last-update.log` for post-mortem,
     *  - validates the new JAR exists and is non-empty,
     *  - backs up the current JAR (replacing any prior `.bak`),
     *  - moves the new JAR into place,
     *  - executes [relaunchCommand] (caller-supplied: `nohup java -jar`, `open -n`, etc.),
     *  - self-deletes via `trap EXIT`,
     *  - uses `set -e` so any unexpected failure aborts cleanly.
     *
     * v3.2.1-security: all embedded file paths use single-quote escaping via [shellQuote]
     * to prevent shell injection from paths containing backticks, $(), etc.
     */
    private fun createUnixUpdateScript(
        currentJar: File,
        newJar: File,
        bakJar: File,
        relaunchCommand: String
    ) {
        val script = File(currentJar.parentFile, ".gameperf-update.sh")
        val logPath = "\$HOME/GamePerf Reports/updates/last-update.log"
        val qScript = shellQuote(script.absolutePath)
        val qCurrent = shellQuote(currentJar.absolutePath)
        val qNew = shellQuote(newJar.absolutePath)
        val qBak = shellQuote(bakJar.absolutePath)
        script.writeText(
            """#!/bin/bash
set -e
LOG="$logPath"
mkdir -p "${'$'}(dirname "${'$'}LOG")" 2>/dev/null || true
exec >> "${'$'}LOG" 2>&1
trap 'rm -f $qScript' EXIT
echo "=== [${'$'}(date '+%Y-%m-%d %H:%M:%S')] Update script started ==="
echo "Current JAR: $qCurrent"
echo "New JAR:     $qNew"
echo "Backup:      $qBak"

# Wait for parent process to die
sleep 2

# Verify new JAR exists and is non-empty
if [ ! -s $qNew ]; then
  echo "FATAL: new JAR does not exist or is empty: $qNew"
  exit 1
fi
NEW_SIZE=${'$'}(stat -f%z $qNew 2>/dev/null || stat -c%s $qNew 2>/dev/null || echo 0)
echo "New JAR size: ${'$'}NEW_SIZE bytes"

# Backup current JAR (remove any prior backup first)
if [ -f $qBak ]; then
  echo "Removing old backup..."
  rm -f $qBak
fi
echo "Backing up current JAR..."
mv -f $qCurrent $qBak

# Install new JAR
echo "Installing new JAR..."
mv -f $qNew $qCurrent

# Relaunch (caller-supplied command — paths already shellQuote'd by callers)
echo "Relaunching: $relaunchCommand"
$relaunchCommand

echo "=== [${'$'}(date '+%Y-%m-%d %H:%M:%S')] Update script done ==="
"""
        )
        script.setExecutable(true)
        ProcessBuilder("bash", script.absolutePath)
            .directory(currentJar.parentFile)
            .redirectErrorStream(true)
            .start()
    }

    /**
     * Windows equivalent of [createUnixUpdateScript]. Uses `.bat` syntax with logging
     * to `%USERPROFILE%\GamePerf Reports\updates\last-update.log`.
     */
    /**
     * v4.1.0-security: Windows paths are now double-quoted consistently to handle
     * paths with spaces (e.g. `C:\Program Files\GamePerf\app\GamePerf.jar`).
     * Previously, paths were embedded without quotes — any space in the path would
     * break the batch script silently.
     */
    private fun createWindowsUpdateScript(
        currentJar: File,
        newJar: File,
        bakJar: File,
        relaunchCommand: String
    ) {
        val script = File(currentJar.parentFile, "gameperf-update.bat")
        // Windows double-quote escaping: batch scripts use `"` for paths with spaces.
        // No backtick/$ expansion risk in cmd.exe, so double quotes are sufficient.
        val qCurrent = "\"${currentJar.absolutePath}\""
        val qNew = "\"${newJar.absolutePath}\""
        val qBak = "\"${bakJar.absolutePath}\""
        val qScript = "\"${script.absolutePath}\""
        script.writeText(
            """@echo off
setlocal
set "LOG=%USERPROFILE%\GamePerf Reports\updates\last-update.log"
if not exist "%USERPROFILE%\GamePerf Reports\updates" mkdir "%USERPROFILE%\GamePerf Reports\updates" 2>nul
echo === [%DATE% %TIME%] Update script started >> "%LOG%"
echo Current JAR: $qCurrent >> "%LOG%"
echo New JAR:     $qNew >> "%LOG%"
echo Backup:      $qBak >> "%LOG%"

timeout /t 2 /nobreak >nul

if not exist $qNew (
  echo FATAL: new JAR does not exist >> "%LOG%"
  exit /b 1
)

if exist $qBak (
  echo Removing old backup... >> "%LOG%"
  del /f $qBak >> "%LOG%" 2>&1
)
echo Backing up current JAR... >> "%LOG%"
move /y $qCurrent $qBak >> "%LOG%" 2>&1

echo Installing new JAR... >> "%LOG%"
move /y $qNew $qCurrent >> "%LOG%" 2>&1

echo Relaunching: $relaunchCommand >> "%LOG%"
$relaunchCommand

timeout /t 1 /nobreak >nul
echo === [%DATE% %TIME%] Update script done >> "%LOG%"
del /f $qScript
"""
        )
        ProcessBuilder("cmd", "/c", "start", "/min", "", script.absolutePath)
            .directory(currentJar.parentFile)
            .redirectErrorStream(true)
            .start()
    }

    /**
     * Extract a top-level string value from JSON without a regex.
     *
     * WHY NOT REGEX: the previous implementation used `"$key"\s*:\s*"((?:[^"\\]|\\.)*)"`
     * which worked for short values but hit `StackOverflowError` on long release bodies
     * (e.g. v3.1.3 with 1827 characters of mixed unicode, escaped quotes, and `\n`). Java's
     * regex engine does recursive backtracking on alternations with `*`, and once the body
     * crosses a certain length with enough escape sequences the stack blows up. The catch
     * block in `checkForUpdate()` only catches `Exception`, not `Error`, so the failure was
     * silent — the update banner simply never appeared.
     *
     * THIS IMPLEMENTATION: linear scan, no recursion. Finds the first occurrence of the key
     * (as a quoted string followed by `:`), then reads the value character by character,
     * honoring JSON escape sequences (`\"`, `\\`, `\n`, `\t`, `\r`, `\b`, `\f`, `\/`, `\uXXXX`).
     * Safe for bodies of arbitrary length.
     *
     * Returns `null` if the key is not found, if its value is not a string, or if the string
     * is malformed.
     */
    internal fun extractJsonString(json: String, key: String): String? {
        val needle = "\"$key\""
        var searchFrom = 0
        while (true) {
            val kIdx = json.indexOf(needle, searchFrom)
            if (kIdx < 0) return null

            // Verify this is actually a key (followed by optional whitespace + `:` + optional whitespace + `"`)
            var after = kIdx + needle.length
            while (after < json.length && json[after].isWhitespace()) after++
            if (after >= json.length || json[after] != ':') {
                // Not a key — could be a value containing the same text. Keep searching.
                searchFrom = kIdx + 1
                continue
            }
            after++
            while (after < json.length && json[after].isWhitespace()) after++
            if (after >= json.length || json[after] != '"') {
                // Value is not a string (number, bool, object, array, null). This extractor
                // only handles strings — bail out.
                return null
            }
            after++

            // Scan the string body, handling escapes, until the closing unescaped `"`.
            val out = StringBuilder()
            while (after < json.length) {
                val c = json[after]
                if (c == '"') return out.toString()
                if (c == '\\') {
                    after++
                    if (after >= json.length) return null
                    when (val esc = json[after]) {
                        '"'  -> out.append('"')
                        '\\' -> out.append('\\')
                        '/'  -> out.append('/')
                        'n'  -> out.append('\n')
                        't'  -> out.append('\t')
                        'r'  -> out.append('\r')
                        'b'  -> out.append('\b')
                        'f'  -> out.append('\u000C')
                        'u'  -> {
                            if (after + 4 >= json.length) return null
                            val hex = json.substring(after + 1, after + 5)
                            val cp = hex.toIntOrNull(16) ?: return null
                            out.append(cp.toChar())
                            after += 4
                        }
                        else -> out.append(esc) // tolerant of unknown escapes
                    }
                    after++
                } else {
                    out.append(c)
                    after++
                }
            }
            return null // unterminated string
        }
    }

    /**
     * Extract all values of a repeating top-level string key from JSON. Used to scan the
     * `assets[].browser_download_url` fields. Same non-regex strategy as [extractJsonString]
     * to avoid the StackOverflowError issue.
     */
    internal fun extractAllJsonStrings(json: String, key: String): List<String> {
        val needle = "\"$key\""
        val results = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val kIdx = json.indexOf(needle, searchFrom)
            if (kIdx < 0) return results

            var after = kIdx + needle.length
            while (after < json.length && json[after].isWhitespace()) after++
            if (after >= json.length || json[after] != ':') {
                searchFrom = kIdx + 1
                continue
            }
            after++
            while (after < json.length && json[after].isWhitespace()) after++
            if (after >= json.length || json[after] != '"') {
                searchFrom = kIdx + 1
                continue
            }
            after++

            val out = StringBuilder()
            var terminated = false
            while (after < json.length) {
                val c = json[after]
                if (c == '"') {
                    results.add(out.toString())
                    terminated = true
                    searchFrom = after + 1
                    break
                }
                if (c == '\\') {
                    after++
                    if (after >= json.length) return results
                    when (val esc = json[after]) {
                        '"'  -> out.append('"')
                        '\\' -> out.append('\\')
                        '/'  -> out.append('/')
                        'n'  -> out.append('\n')
                        't'  -> out.append('\t')
                        'r'  -> out.append('\r')
                        'b'  -> out.append('\b')
                        'f'  -> out.append('\u000C')
                        'u'  -> {
                            if (after + 4 >= json.length) return results
                            val hex = json.substring(after + 1, after + 5)
                            val cp = hex.toIntOrNull(16) ?: return results
                            out.append(cp.toChar())
                            after += 4
                        }
                        else -> out.append(esc)
                    }
                    after++
                } else {
                    out.append(c)
                    after++
                }
            }
            if (!terminated) return results
        }
    }

    /**
     * Detect the current platform identifier matching the CI artifact naming convention.
     * CI produces: GamePerf-macos-arm64-X.Y.Z.jar, GamePerf-linux-x64-X.Y.Z.jar, GamePerf-windows-x64-X.Y.Z.jar
     */
    private fun detectPlatformTag(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("mac") && arch.contains("aarch64") -> "macos-arm64"
            os.contains("mac") -> "macos-x64"
            os.contains("win") -> "windows-x64"
            os.contains("linux") && arch.contains("aarch64") -> "linux-aarch64"
            else -> "linux-x64"
        }
    }

    /**
     * Extract the platform-matching `.jar` asset URL from the GitHub release JSON.
     * Matches by platform tag (e.g. `macos-x64`) in the filename. Falls back to the first
     * `.jar` if no platform match is found.
     *
     * Uses [extractAllJsonStrings] (linear scan) instead of regex to avoid the
     * `StackOverflowError` that the previous regex-based implementation hit on long bodies.
     */
    private fun extractJarAssetUrl(json: String): String? {
        val platform = detectPlatformTag()
        val allUrls = extractAllJsonStrings(json, "browser_download_url")
        val jarUrls = allUrls.filter { it.endsWith(".jar") }
        // Prefer exact platform match first
        jarUrls.firstOrNull { it.contains(platform) }?.let { return it }
        // Fallback: first JAR (better than returning null — gives the user *something*)
        return jarUrls.firstOrNull()
    }
}
