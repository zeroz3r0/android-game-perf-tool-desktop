package com.gameperf.desktop.core

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

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
            // v4.1.0: use /releases (list all) instead of /releases/latest.
            // GitHub's "latest" endpoint picks by published_at timestamp, NOT by
            // semver. If an older release gets re-published (edited), it becomes
            // "latest" even though a newer semver release exists. This caused
            // v3.1.3 (re-published) to shadow v4.0.0 and block all update banners.
            //
            // Now we fetch the first page of releases (up to 30, sorted newest first)
            // and pick the one with the highest semver tag.
            val url = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=10")
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

            // v4.2.4: force UTF-8. Without the explicit charset, InputStreamReader
            // falls back to Charset.defaultCharset() — which on a Windows machine
            // with a Spanish locale is Windows-1252 (Cp1252). GitHub's REST API
            // always responds with Content-Type: application/json; charset=utf-8,
            // so reading those bytes as Cp1252 mis-decodes every multi-byte UTF-8
            // character: em-dash "—" (bytes E2 80 94) renders as "â€"", tildes
            // become "Ã¡" / "Ã©" / "Ã±", etc. The release banner parses `body`
            // from this JSON, so the user was seeing mojibake for every non-ASCII
            // character in the release notes.
            val json = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
            conn.disconnect()

            // Parse the JSON array and find the release with the highest semver tag.
            // The array format is: [ { "tag_name": "v4.0.0", ... }, { "tag_name": "v3.2.1", ... }, ... ]
            val tagNames = extractAllJsonStrings(json, "tag_name")
            if (tagNames.isEmpty()) {
                lastCheckError = "No releases found"
                return null
            }

            // v4.2.10: iterate releases from highest semver downward until we find
            // one that actually has a JAR asset published for our platform.
            //
            // This closes a recurring bug ("No hay JAR disponible para tu plataforma")
            // that happened in v4.2.3, v4.2.4, and v4.2.9: `gh release create`
            // creates the release immediately, but the .github/workflows/release.yml
            // workflow needs 6-7 minutes to compile + package + upload binaries.
            // During that window the release exists in GitHub but has no assets,
            // and the previous version of this code exposed it to the banner
            // anyway. Pressing "Actualizar" then failed with the red error text
            // because extractJarAssetUrl returned null.
            //
            // Now we skip any release without matching assets. If the highest
            // release hasn't finished building, we fall back to the next one.
            // If none of the newer releases have assets yet, we return null
            // (no banner) — "no update visible yet" is a better UX than
            // "update that doesn't work".
            //
            // See CLAUDE.md "Patrón de bug recurrente" section for the lesson.
            //
            // v4.2.13: extracted to selectFirstReleaseWithAsset() for testability.
            selectFirstReleaseWithAsset(
                tags = tagNames,
                currentVersion = AppVersion.NAME,
                fetchReleaseJson = ::fetchReleaseJson,
                extractJarAssetUrl = ::extractJarAssetUrl
            )
        } catch (t: Throwable) {
            lastCheckError = "${t.javaClass.simpleName}: ${t.message ?: "no details"}"
            null
        }
    }

    /**
     * Fetch the full JSON of a single release by tag. Returns null on any HTTP /
     * network failure so callers can skip the tag and try the next one.
     *
     * v4.2.10: extracted so [checkForUpdate] can iterate through multiple tags
     * looking for one with assets. Uses the same UTF-8 encoding + user agent as
     * the rest of the updater.
     */
    private fun fetchReleaseJson(tag: String): String? = try {
        val releaseUrl = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/tags/$tag")
        val releaseConn = releaseUrl.openConnection() as HttpURLConnection
        releaseConn.requestMethod = "GET"
        releaseConn.setRequestProperty("Accept", "application/vnd.github+json")
        releaseConn.setRequestProperty("User-Agent", "GamePerfDesktop/${AppVersion.NAME}")
        releaseConn.connectTimeout = 10_000
        releaseConn.readTimeout = 10_000

        if (releaseConn.responseCode != 200) {
            releaseConn.disconnect()
            null
        } else {
            val text = BufferedReader(InputStreamReader(releaseConn.inputStream, StandardCharsets.UTF_8))
                .use { it.readText() }
            releaseConn.disconnect()
            text
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Compare two version strings numerically: "4.0.0" > "3.2.1" > "3.1.14".
     * Returns negative if a < b, zero if equal, positive if a > b.
     */
    internal fun compareVersions(a: String, b: String): Int {
        val ap = a.split(".").mapNotNull { it.toIntOrNull() }
        val bp = b.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val av = ap.getOrElse(i) { 0 }
            val bv = bp.getOrElse(i) { 0 }
            if (av != bv) return av - bv
        }
        return 0
    }

    /**
     * Pure function that iterates releases by semver (descending), fetches each one,
     * and returns the first release that has a JAR asset for the given platform.
     *
     * This logic was extracted from [checkForUpdate] to make it testable without
     * network I/O. The function stops as soon as it finds a release with:
     * 1. A version newer than [currentVersion]
     * 2. A non-null JAR asset URL (from [extractJarAssetUrl])
     *
     * If no such release exists (all newer releases lack assets, or all are older/equal),
     * returns null.
     *
     * @param tags List of tag names from the releases API (e.g., ["v4.2.12", "v4.2.11", ...])
     * @param currentVersion The currently running version (without 'v' prefix)
     * @param fetchReleaseJson Function that fetches the full JSON for a given tag; returns null on failure
     * @param extractJarAssetUrl Function that extracts the JAR asset URL from a release JSON; returns null if no matching asset
     * @return [ReleaseInfo] for the first valid release, or null if none found
     *
     * v4.2.13: extracted for testability — closes the "release sin JAR assets" testing debt.
     */
    internal fun selectFirstReleaseWithAsset(
        tags: List<String>,
        currentVersion: String,
        fetchReleaseJson: (String) -> String?,
        extractJarAssetUrl: (String) -> String?
    ): ReleaseInfo? {
        // Sort tags by semver descending (highest version first)
        val sortedTagsDescending = tags.sortedWith { a, b ->
            val av = a.removePrefix("v").removePrefix("V")
            val bv = b.removePrefix("v").removePrefix("V")
            compareVersions(bv, av) // b before a => descending
        }

        for (tag in sortedTagsDescending) {
            val tagVersion = tag.removePrefix("v").removePrefix("V")
            // Stop as soon as we reach the version we're running — everything
            // below is older or equal, so no update.
            if (compareVersions(tagVersion, currentVersion) <= 0) {
                return null
            }

            val releaseJson = fetchReleaseJson(tag) ?: continue // network error → skip this tag
            val jarUrl = extractJarAssetUrl(releaseJson)

            if (jarUrl == null) {
                // Release exists but its binaries haven't been uploaded yet
                // (workflow still building, or workflow failed). Try the
                // next lower tag to avoid showing a broken "Update" button.
                continue
            }

            val name = extractJsonString(releaseJson, "name") ?: tag
            val body = extractJsonString(releaseJson, "body") ?: ""
            val publishedAt = extractJsonString(releaseJson, "published_at") ?: ""
            val htmlUrl = extractJsonString(releaseJson, "html_url") ?: ""
            return ReleaseInfo(tag, tagVersion, name, body, publishedAt, jarUrl, htmlUrl)
        }

        // Exhausted the tag list without finding any newer release that has binaries.
        return null
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
     * [pendingElevatedExit] = v4.3.8: true when an UAC-elevated helper has been spawned and the
     *   caller MUST exit the app cleanly so the helper can replace the JAR. Defaults to false
     *   for backward compatibility with the existing macOS / Linux / fat-jar paths.
     */
    data class UpdateResult(
        val success: Boolean,
        val needsManualRestart: Boolean = false,
        val updatedJarPath: String = "",
        val message: String = "",
        val pendingElevatedExit: Boolean = false,
        /**
         * v4.4.1 (spec auto-updater REQ "Update failure surface area"):
         * structured outcome so [com.gameperf.desktop.viewmodel.UpdateDelegate]
         * can fan failures out to `UpdateFallbackState` and `UpdateHistoryStore`.
         * `null` only when an existing call site predates the fan-out (backward
         * compat); new failure paths MUST populate it.
         */
        val outcome: com.gameperf.desktop.core.update.UpdateOutcome? = null,
    )

    /** Minimum size (bytes) for a JAR to be considered an "uber JAR" with all deps embedded.
     *  jpackage `.app` bundles ship a single fat JAR with all Compose Desktop / Skiko deps;
     *  if the downloaded update is smaller than this, replacing the bundle JAR with a thin
     *  JAR would crash the bundle on next launch (NoClassDefFoundError everywhere). */
    private const val MIN_UBER_JAR_BYTES = 50_000_000L

    /** Canonical app name baked into staged JAR filenames. */
    internal const val STAGED_JAR_APP_NAME: String = "android-game-perf-tool-desktop"

    // ═══════ v4.4.1 — Failure fan-out helpers (pure, testable in isolation) ═══════

    /**
     * Build an [UpdateResult] for an HTTP / network download failure.
     *
     * Spec auto-updater REQ "Update failure surface area" (scenarios E1):
     * download/asset failures MUST emit a [com.gameperf.desktop.core.update.UpdateOutcome.FailedDownload]
     * outcome. The [errorMessage] surfaces the raw cause; [httpStatus] is `null`
     * for connection-level failures (no HTTP exchange happened).
     */
    internal fun buildDownloadFailureResult(
        errorMessage: String,
        httpStatus: Int? = null,
    ): UpdateResult = UpdateResult(
        success = false,
        message = errorMessage,
        outcome = com.gameperf.desktop.core.update.UpdateOutcome.FailedDownload(
            httpStatus = httpStatus,
            message = errorMessage,
        ),
    )

    /**
     * Build an [UpdateResult] for a watchdog timeout (no canary observed).
     *
     * Spec auto-updater REQ U2: when `HelperLogWatcher.awaitCanary` returns
     * `TimedOut`, the JVM MUST NOT call `exitProcess` and MUST emit
     * [com.gameperf.desktop.core.update.UpdateOutcome.FailedWatchdogTimeout].
     */
    internal fun buildWatchdogTimeoutResult(message: String): UpdateResult = UpdateResult(
        success = false,
        message = message,
        outcome = com.gameperf.desktop.core.update.UpdateOutcome.FailedWatchdogTimeout,
    )

    /**
     * Build an [UpdateResult] for any catch-all terminal failure that does
     * not fit the [com.gameperf.desktop.core.update.UpdateOutcome.FailedDownload],
     * [com.gameperf.desktop.core.update.UpdateOutcome.FailedWatchdogTimeout], or
     * [com.gameperf.desktop.core.update.UpdateOutcome.FailedHelperCrash] cases.
     *
     * Used for: missing JAR asset on the release, PowerShell spawn IOException,
     * unexpected exceptions during planning. Spec scenario U3 (spawn throws).
     */
    internal fun buildUnknownFailureResult(message: String): UpdateResult = UpdateResult(
        success = false,
        message = message,
        outcome = com.gameperf.desktop.core.update.UpdateOutcome.FailedUnknown(message),
    )

    /**
     * Build a successful, elevated-exit-pending [UpdateResult].
     *
     * Spec auto-updater REQ U1: canary observed within timeout → return
     * `success=true, pendingElevatedExit=true, outcome=Success` so the caller
     * (UpdateDelegate) can run its existing 1.5s-then-`exitProcess(0)` path.
     */
    internal fun buildElevatedSuccessResult(updatedJarPath: String, message: String): UpdateResult =
        UpdateResult(
            success = true,
            pendingElevatedExit = true,
            updatedJarPath = updatedJarPath,
            message = message,
            outcome = com.gameperf.desktop.core.update.UpdateOutcome.Success,
        )

    /**
     * Build the staged JAR filename for a download targeting [targetVersion].
     *
     * v4.4.1 (spec N1/N2): the filename MUST derive from the release's
     * `targetVersion` rather than the running [AppVersion.NAME], so the
     * on-disk artifact accurately names the version that will be installed.
     * Pre-v4.4.1 a v4.3.8 client downloading v4.4.1 produced the misleading
     * `android-game-perf-tool-desktop-4.3.8-staged.jar`.
     *
     * Path-traversing characters (`/`, `\`) in [targetVersion] are sanitized
     * to `-` defensively. Hyphens (legitimate in pre-release tags like
     * `4.4.1-beta`) are preserved.
     */
    internal fun stagedJarFilename(targetVersion: String): String {
        val sanitized = targetVersion.replace('/', '-').replace('\\', '-')
        return "$STAGED_JAR_APP_NAME-$sanitized-staged.jar"
    }

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
    fun applyUpdate(downloadedFile: File): UpdateResult = applyUpdate(downloadedFile, AppVersion.NAME)

    /**
     * v4.4.1 overload that threads the release [targetVersion] through to the
     * Windows-bundle elevated path so the staged JAR filename reflects the
     * version being installed (per spec N1/N2). Other branches ignore
     * [targetVersion] today — the staged filename is only used in the
     * elevated-helper temp dir.
     */
    fun applyUpdate(downloadedFile: File, targetVersion: String): UpdateResult {
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
                    applyUpdateWindowsBundle(downloadedFile, currentJar, launcher, targetVersion)
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

    private fun applyUpdateWindowsBundle(
        downloadedFile: File,
        currentJar: File,
        launcher: File,
        targetVersion: String = AppVersion.NAME,
    ): UpdateResult {
        if (downloadedFile.length() < MIN_UBER_JAR_BYTES) {
            throw IllegalStateException(
                "El JAR descargado (${downloadedFile.length()} bytes) es demasiado pequeño para ser un uber-JAR. " +
                "La actualización para installers nativos requiere un JAR completo con todas las dependencias embebidas."
            )
        }

        val jarDir = currentJar.parentFile
            ?: return UpdateResult(false, message = "No se pudo determinar el directorio del JAR del bundle")
        val installDir = jarDir.parentFile
            ?: return UpdateResult(false, message = "No se pudo determinar el directorio de instalación")

        // v4.3.8: when the install dir lives under Program Files / ProgramData / Windows,
        // the running JVM is unprivileged and can't overwrite its own JAR. Switch to the
        // UAC-elevated helper path: spawn a separate elevated PowerShell process that waits
        // for us to exit, replaces the JAR, and relaunches the .exe. The current process
        // returns a `pendingElevatedExit` signal so the caller (UpdateDelegate) shuts the
        // app down cleanly.
        if (InstallLocation.requiresAdmin(installDir, isWindows = true)) {
            val helperDir = File(System.getProperty("java.io.tmpdir"), "GamePerf-update").apply { mkdirs() }
            // v4.4.1 spec N1/N2: staged filename derives from the release's target version,
            // not the running AppVersion.NAME. See [stagedJarFilename] KDoc for rationale.
            val stagedNewJar = File(helperDir, stagedJarFilename(targetVersion))
            downloadedFile.copyTo(stagedNewJar, overwrite = true)
            return planAndLaunchElevatedUpdate(
                newJar = stagedNewJar,
                oldJar = currentJar,
                installDir = installDir,
                appExe = launcher,
                helperDir = helperDir,
            )
        }

        // Direct-write path (user-writable install) — unchanged from v4.3.7.
        val newJar = File(jarDir, currentJar.name + ".new")
        val bakJar = File(jarDir, currentJar.name + ".bak")

        downloadedFile.copyTo(newJar, overwrite = true)

        // Relaunch via the native launcher .exe so its bundled .cfg is honored.
        val relaunchCommand = """start "" "${launcher.absolutePath}"""" // Windows: double quotes are safe (no $() expansion)
        createWindowsUpdateScript(currentJar, newJar, bakJar, relaunchCommand = relaunchCommand)

        System.exit(0)
        return UpdateResult(true) // unreachable
    }

    // ═══════ v4.3.8 — UAC self-elevation for protected install paths ═══════

    /**
     * PowerShell helper script that runs *elevated* via UAC. It:
     *  1. waits up to 120 seconds for the running app to exit (matched by the bundle's
     *     specific launcher .exe + the bundled runtime\bin\java.exe — NOT any process
     *     under the install dir, which used to misfire on unrelated java.exe in v4.6.0),
     *  2. copies the staged new JAR over the old one in the install dir's `app/` subdir,
     *  3. relaunches the native launcher .exe so the bundle's `.cfg` (Skiko paths, etc.) is honored,
     *  4. logs every step to the path passed via `-LogPath` for post-mortem debugging,
     *     including the surviving process names + PIDs if the timeout is hit so the user
     *     has actionable evidence (closes bug #474 — repro 2026-05-18).
     *
     * The script is a CONSTANT template — per-update paths are passed as PowerShell
     * parameters at launch time (`-OldJar`, `-NewJar`, etc.), NOT baked into the body.
     * This keeps the script identical across every update and makes it trivial to verify.
     *
     * NOTE: kept as a `const` so detekt/binary-cache treats it as unchanging; reformatting
     * touches the apply-progress test (which asserts on the exact body) so any whitespace
     * change is intentional.
     */
    private const val UAC_HELPER_PS1 = """param(
    [Parameter(Mandatory=${'$'}true)] [string]${'$'}OldJar,
    [Parameter(Mandatory=${'$'}true)] [string]${'$'}NewJar,
    [Parameter(Mandatory=${'$'}true)] [string]${'$'}InstallDir,
    [Parameter(Mandatory=${'$'}true)] [string]${'$'}AppExe,
    [Parameter(Mandatory=${'$'}true)] [string]${'$'}LogPath
)

${'$'}ErrorActionPreference = 'Continue'

function Write-Log(${'$'}msg) {
    try {
        ${'$'}stamp = [DateTime]::Now.ToString('HH:mm:ss')
        "${'$'}stamp ${'$'}msg" | Out-File -FilePath ${'$'}LogPath -Append -Encoding UTF8
    } catch { }
}

Write-Log "===== UAC update helper started ====="
Write-Log "OldJar: ${'$'}OldJar"
Write-Log "NewJar: ${'$'}NewJar"
Write-Log "InstallDir: ${'$'}InstallDir"
Write-Log "AppExe: ${'$'}AppExe"

# 1. Wait for the running app to exit (max 120 s — bumped from 30 s in v4.6.1 because
#    Compose/Skiko cleanup on Windows can take 5-15 s after exitProcess and was tripping
#    the abort on real Program Files installs; bug #474).
#    The filter is NARROW: only the bundle's launcher .exe (by basename) and the
#    bundled JVM at <InstallDir>\runtime\bin\java.exe. The previous v4.6.0 filter
#    matched ANY process whose Path started with ${'$'}InstallDir, which captured
#    unrelated java.exe processes that happened to share the install dir's drive
#    and also confused the wait when the user had other Java apps running.
${'$'}timeoutSec = 120
${'$'}launcherName = [System.IO.Path]::GetFileName(${'$'}AppExe)
${'$'}bundledJvmPath = Join-Path ${'$'}InstallDir 'runtime\bin\java.exe'
${'$'}elapsed = 0.0
${'$'}stillRunning = ${'$'}null
while (${'$'}elapsed -lt ${'$'}timeoutSec) {
    ${'$'}stillRunning = Get-Process -ErrorAction SilentlyContinue | Where-Object {
        ${'$'}_.Path -and (
            ((${'$'}_.Name + '.exe') -ieq ${'$'}launcherName) -or
            ${'$'}_.Path.Equals(${'$'}bundledJvmPath, [System.StringComparison]::OrdinalIgnoreCase)
        )
    }
    if (-not ${'$'}stillRunning) {
        Write-Log "App exited."
        break
    }
    Start-Sleep -Milliseconds 500
    ${'$'}elapsed += 0.5
}

if (${'$'}stillRunning) {
    ${'$'}survivors = (${'$'}stillRunning | ForEach-Object { ${'$'}_.Name + ' (PID ' + ${'$'}_.Id + ')' }) -join ', '
    Write-Log "ERROR: App did not exit within ${'$'}timeoutSec seconds. Processes still alive: ${'$'}survivors. Aborting."
    exit 1
}

# 2. Replace the JAR. Copy-Item -Force overwrites; the elevated token grants the
#    write permission that the unprivileged JVM lacked.
try {
    Write-Log "Copying new JAR over old JAR..."
    Copy-Item -Path ${'$'}NewJar -Destination ${'$'}OldJar -Force -ErrorAction Stop
    Write-Log "JAR replaced successfully."
} catch {
    Write-Log "ERROR: Failed to replace JAR: ${'$'}_"
    exit 2
}

# 3. Relaunch via the native launcher so the bundle .cfg (Skiko paths, etc.) is read.
try {
    Write-Log "Relaunching ${'$'}AppExe..."
    Start-Process -FilePath ${'$'}AppExe -WorkingDirectory ${'$'}InstallDir
    Write-Log "Relaunched."
} catch {
    Write-Log "ERROR: Failed to relaunch app: ${'$'}_"
    exit 3
}

Write-Log "===== UAC update helper finished OK ====="
exit 0
"""

    /**
     * Pure planner: validates inputs, writes the helper script to disk, and returns
     * an [UpdateResult] WITHOUT spawning any process. This is the test-friendly half
     * of the elevation flow — production code calls [planAndLaunchElevatedUpdate]
     * which composes this with [buildElevatedLaunchArgs] + actual `ProcessBuilder.start`.
     *
     * Tests live in `AutoUpdaterElevationTest`.
     */
    internal fun planElevatedUpdate(
        newJar: File,
        oldJar: File,
        installDir: File,
        appExe: File,
        helperDir: File,
    ): UpdateResult {
        if (!newJar.exists() || !newJar.isFile) {
            return UpdateResult(false, message = "El JAR descargado no existe: ${newJar.absolutePath}")
        }
        if (newJar.length() < MIN_UBER_JAR_BYTES) {
            return UpdateResult(
                false,
                message = "El JAR descargado (${newJar.length()} bytes) es demasiado pequeño para ser un uber-JAR."
            )
        }
        helperDir.mkdirs()
        val helperScript = File(helperDir, "update-helper.ps1")
        // Use UTF-8 so Spanish log messages survive — same lesson as v4.2.4 (mojibake bug).
        helperScript.writeText(UAC_HELPER_PS1, Charsets.UTF_8)

        // Reference all params so detekt's UnusedParameter rule stays quiet — they ARE
        // used: by the launch step in planAndLaunchElevatedUpdate. We don't bake them
        // into the script (that's deliberate — see UAC_HELPER_PS1 KDoc).
        require(oldJar.absolutePath.isNotBlank())
        require(installDir.absolutePath.isNotBlank())
        require(appExe.absolutePath.isNotBlank())

        return UpdateResult(
            success = true,
            pendingElevatedExit = true,
            updatedJarPath = oldJar.absolutePath,
            message = "Actualización lista. Cerrando GamePerf para aplicarla con permisos de administrador."
        )
    }

    /**
     * Build the `powershell.exe` argument list that triggers the UAC consent dialog
     * and runs [helperScript] elevated with the right named parameters.
     *
     * The outer powershell.exe exits immediately because `Start-Process -Verb RunAs`
     * spawns a SEPARATE elevated process — the JVM doesn't need to wait for it.
     *
     * Tests assert on these args without spawning a process.
     */
    internal fun buildElevatedLaunchArgs(
        helperScript: File,
        oldJar: File,
        newJar: File,
        installDir: File,
        appExe: File,
        logPath: File,
    ): List<String> {
        // PowerShell single-quote escaping: any ' inside a single-quoted string becomes ''.
        // We never expect a single quote in a Windows install path, but we escape defensively.
        fun psQuote(s: String): String = "'" + s.replace("'", "''") + "'"
        val inner = """
            Start-Process powershell.exe -Verb RunAs -ArgumentList @(
              '-NoProfile',
              '-ExecutionPolicy', 'Bypass',
              '-File', ${psQuote(helperScript.absolutePath)},
              '-OldJar', ${psQuote(oldJar.absolutePath)},
              '-NewJar', ${psQuote(newJar.absolutePath)},
              '-InstallDir', ${psQuote(installDir.absolutePath)},
              '-AppExe', ${psQuote(appExe.absolutePath)},
              '-LogPath', ${psQuote(logPath.absolutePath)}
            )
        """.trimIndent()
        return listOf(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-Command",
            inner,
        )
    }

    /**
     * v4.4.1 (ADR-8): single source of truth for the helper-log path used by
     * BOTH the JVM-side breadcrumb writer and HelperLogWatcher's production
     * wrapper. Keeping the resolution in one place prevents the two writers
     * from drifting apart.
     */
    fun lastUpdateLogPath(): java.nio.file.Path =
        File(System.getProperty("user.home"), "GamePerf Reports/updates/last-update.log").toPath()

    /**
     * v4.4.1 — pure orchestrator for the post-spawn watchdog flow.
     *
     * Sequence (per design §1):
     *   1. write JVM-side breadcrumb to `last-update.log` BEFORE spawning the helper
     *      (spec REQ "Pre-spawn JVM breadcrumb" / scenario B1)
     *   2. invoke [spawn] (production wires this to `ProcessBuilder.start()`)
     *   3. if spawn fails → return [buildUnknownFailureResult] (U3); skip watchdog
     *   4. if spawn ok → invoke [awaitCanary] and fan out:
     *      - [WatchdogResult.CanaryFound] → [buildElevatedSuccessResult] (U1)
     *      - [WatchdogResult.Disabled]    → [buildElevatedSuccessResult] (W4 — preserves legacy 1.5s exit)
     *      - [WatchdogResult.TimedOut]    → [buildWatchdogTimeoutResult] (U2 — NO pendingExit)
     *
     * Breadcrumb writer errors are SWALLOWED (spec scenario B3) — a read-only
     * log dir must NOT block the user's update.
     *
     * All collaborators are injected so the integration test can drive every
     * branch deterministically without spawning a real PowerShell.
     */
    @Suppress("LongParameterList")
    internal fun runWatchdogAndBuildResult(
        oldJar: File,
        installDir: File,
        appExe: File,
        logPath: File,
        writeBreadcrumb: () -> Unit,
        spawn: () -> Boolean,
        awaitCanary: () -> com.gameperf.desktop.core.update.WatchdogResult,
    ): UpdateResult {
        // Reference unused params so detekt's UnusedParameter rule stays quiet —
        // they belong to the spawn closure's captured environment in production.
        require(installDir.path.isNotBlank())
        require(appExe.path.isNotBlank())
        require(logPath.path.isNotBlank())

        // 1. JVM-side breadcrumb. Tolerated to fail per spec B3.
        runCatching { writeBreadcrumb() }

        // 2. Spawn the elevated helper. Closure returns false on IOException/etc.
        val launched = runCatching { spawn() }.getOrElse { false }
        if (!launched) {
            return buildUnknownFailureResult(
                "No se pudo lanzar el actualizador con permisos de administrador. " +
                    "Cancelaste la solicitud de UAC o PowerShell no está disponible."
            )
        }

        // 3. Await the helper canary. Watchdog disabled (timeout=0) returns Disabled
        //    so we treat it as Success and let UpdateDelegate run the legacy 1.5s exit.
        return when (awaitCanary()) {
            is com.gameperf.desktop.core.update.WatchdogResult.CanaryFound,
            is com.gameperf.desktop.core.update.WatchdogResult.Disabled -> buildElevatedSuccessResult(
                updatedJarPath = oldJar.absolutePath,
                message = "Actualización lista. Cerrando GamePerf para aplicarla con permisos de administrador.",
            )
            is com.gameperf.desktop.core.update.WatchdogResult.TimedOut -> buildWatchdogTimeoutResult(
                "El actualizador no respondió a tiempo. Probablemente cancelaste la solicitud de Windows " +
                    "o el helper no pudo iniciarse. Probá descargar la nueva versión manualmente."
            )
        }
    }

    /**
     * Production entry point: plan the elevated update, then spawn the `powershell.exe`
     * process that triggers the UAC consent dialog. v4.4.1 wraps the spawn with the
     * pre-spawn breadcrumb + post-spawn canary watchdog (default timeout 8 s per ADR-2).
     *
     * If UAC is denied (user clicks "No"), the outer powershell.exe still exits 0
     * — but the watchdog now catches this as a [com.gameperf.desktop.core.update.WatchdogResult.TimedOut]
     * because the elevated helper never spawned to write the canary line. The result
     * surfaces [com.gameperf.desktop.core.update.UpdateOutcome.FailedWatchdogTimeout]
     * to UpdateDelegate, which renders the fallback panel (spec U2).
     */
    private fun planAndLaunchElevatedUpdate(
        newJar: File,
        oldJar: File,
        installDir: File,
        appExe: File,
        helperDir: File,
    ): UpdateResult {
        val plan = planElevatedUpdate(newJar, oldJar, installDir, appExe, helperDir)
        if (!plan.success) return plan

        val helperScript = File(helperDir, "update-helper.ps1")
        val logPath = lastUpdateLogPath().toFile()
        runCatching { logPath.parentFile?.mkdirs() }

        val args = buildElevatedLaunchArgs(
            helperScript = helperScript,
            oldJar = oldJar,
            newJar = newJar,
            installDir = installDir,
            appExe = appExe,
            logPath = logPath,
        )

        return runWatchdogAndBuildResult(
            oldJar = oldJar,
            installDir = installDir,
            appExe = appExe,
            logPath = logPath,
            writeBreadcrumb = {
                logPath.parentFile?.mkdirs()
                val ts = java.time.Instant.now().toString()
                logPath.appendText(
                    "$ts JVM breadcrumb: outer PS launching helper for v${AppVersion.NAME} " +
                        "→ v${plan.updatedJarPath} (script=${helperScript.absolutePath})\n",
                    Charsets.UTF_8,
                )
            },
            spawn = {
                ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .start()
                true
            },
            awaitCanary = {
                com.gameperf.desktop.core.update.HelperLogWatcher.awaitCanary(
                    logPath = logPath.toPath(),
                    timeout = kotlin.time.Duration.parse("8s"),
                )
            },
        )
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
                // v4.2.1: detect launcher by ANY .exe at install root (not just matching the folder name).
                // Previously looked for "<foldername>.exe", which broke if user renamed the folder
                // (e.g. installed as "GamePerfApp2" but launcher is "GamePerf.exe").
                val exeFiles = installRoot.listFiles { f -> f.isFile && f.extension.equals("exe", ignoreCase = true) }
                val launcher = exeFiles?.firstOrNull { it.nameWithoutExtension.equals(installRoot.name, true) }
                    ?: exeFiles?.firstOrNull()  // fallback: any .exe in install root
                if (launcher != null && launcher.exists()) {
                    return InstallationInfo(
                        type = InstallationType.WINDOWS_APP_BUNDLE,
                        currentJar = jarPath,
                        bundleRoot = installRoot,
                        launcher = launcher
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
    internal fun detectPlatformTag(): String {
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
    internal fun extractJarAssetUrl(json: String): String? {
        val platform = detectPlatformTag()
        val allUrls = extractAllJsonStrings(json, "browser_download_url")
        val jarUrls = allUrls.filter { it.endsWith(".jar") }
        // Prefer exact platform match first
        jarUrls.firstOrNull { it.contains(platform) }?.let { return it }
        // Fallback: first JAR (better than returning null — gives the user *something*)
        return jarUrls.firstOrNull()
    }
}
