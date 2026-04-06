package com.gameperf.desktop.report

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cross-platform Chromium-based browser detector. Locates Google Chrome, Chromium,
 * Microsoft Edge, Brave, Vivaldi or Arc by inspecting standard install paths on
 * macOS / Windows and by querying `command -v` on Linux.
 *
 * Designed for [PdfExporter]: a single `detect()` call returns the first matching
 * browser as [DetectedBrowser], or `null` if none is installed. The result is
 * cached in a `@Volatile` pair (`cached`, `cacheInitialized`) so the `null` case
 * also gets cached — repeated lookups are < 1 ms. Thread-safe via double-checked
 * locking on [cacheLock].
 *
 * Detection NEVER throws: any unexpected error returns `null`. Linux uses POSIX
 * `command -v` (NOT `which`) so it works on minimal distros like Alpine.
 *
 * Test hooks (`*Override`) are `internal` so unit tests can inject fake candidate
 * lists or stub the Linux resolver without touching the real filesystem.
 */
object BrowserDetector {

    /** A successful detection: the executable file plus a human-readable name. */
    data class DetectedBrowser(val executable: File, val name: String)

    /**
     * Internal Windows candidate descriptor: a display name, the env var holding the
     * install root (`ProgramFiles`, `ProgramFiles(x86)`, `LOCALAPPDATA`), and the
     * relative path from that root to the browser executable.
     */
    internal data class WindowsCandidate(
        val name: String,
        val envVar: String,
        val relPath: String,
    )

    // ===== Cache (double-checked locking with sentinel) =====
    @Volatile private var cached: DetectedBrowser? = null
    @Volatile private var cacheInitialized: Boolean = false
    private val cacheLock = Any()

    // ===== Test overrides (null = use real candidate lists) =====
    internal var macCandidatesOverride: List<Pair<String, String>>? = null
    internal var windowsCandidatesOverride: List<WindowsCandidate>? = null
    internal var linuxDetectorOverride: ((String) -> File?)? = null

    // ===== Candidate lists (real, hardcoded) =====

    /** macOS bundle paths relative to `/Applications/` (and `~/Applications/`). */
    private val MACOS_CANDIDATES: List<Pair<String, String>> = listOf(
        "Google Chrome"  to "Google Chrome.app/Contents/MacOS/Google Chrome",
        "Chromium"       to "Chromium.app/Contents/MacOS/Chromium",
        "Microsoft Edge" to "Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "Brave Browser"  to "Brave Browser.app/Contents/MacOS/Brave Browser",
        "Vivaldi"        to "Vivaldi.app/Contents/MacOS/Vivaldi",
        "Arc"            to "Arc.app/Contents/MacOS/Arc",
    )

    /**
     * Windows candidates: Chrome × 3 (ProgramFiles, ProgramFiles(x86), LOCALAPPDATA)
     * + Edge × 2 + Brave × 2. Edge is preinstalled on Windows 10/11.
     */
    private val WINDOWS_CANDIDATES: List<WindowsCandidate> = listOf(
        WindowsCandidate("Google Chrome",  "ProgramFiles",      """\Google\Chrome\Application\chrome.exe"""),
        WindowsCandidate("Google Chrome",  "ProgramFiles(x86)", """\Google\Chrome\Application\chrome.exe"""),
        WindowsCandidate("Google Chrome",  "LOCALAPPDATA",      """\Google\Chrome\Application\chrome.exe"""),
        WindowsCandidate("Microsoft Edge", "ProgramFiles",      """\Microsoft\Edge\Application\msedge.exe"""),
        WindowsCandidate("Microsoft Edge", "ProgramFiles(x86)", """\Microsoft\Edge\Application\msedge.exe"""),
        WindowsCandidate("Brave Browser",  "ProgramFiles",      """\BraveSoftware\Brave-Browser\Application\brave.exe"""),
        WindowsCandidate("Brave Browser",  "LOCALAPPDATA",      """\BraveSoftware\Brave-Browser\Application\brave.exe"""),
    )

    /** Linux binary names in order of preference. Resolved via `command -v $name`. */
    private val LINUX_BINARY_NAMES: List<Pair<String, String>> = listOf(
        "Google Chrome"  to "google-chrome",
        "Google Chrome"  to "google-chrome-stable",
        "Chromium"       to "chromium",
        "Chromium"       to "chromium-browser",
        "Microsoft Edge" to "microsoft-edge",
        "Microsoft Edge" to "microsoft-edge-stable",
        "Brave Browser"  to "brave-browser",
        "Brave Browser"  to "brave",
    )

    /**
     * Returns the first Chromium-based browser detected on the current OS, or
     * `null` if none is installed. Result is cached after the first call (including
     * the `null` case). Never throws.
     */
    fun detect(): DetectedBrowser? {
        // Fast path: read the volatile sentinel without taking the lock.
        if (cacheInitialized) return cached
        synchronized(cacheLock) {
            if (cacheInitialized) return cached
            val os = System.getProperty("os.name").lowercase()
            val result = when {
                os.contains("mac") -> detectMacOS()
                os.contains("win") -> detectWindows()
                else               -> detectLinux()
            }
            cached = result
            cacheInitialized = true
            return result
        }
    }

    /** Test-only: clears the cache so the next [detect] call re-runs the scan. */
    internal fun resetCacheForTests() {
        synchronized(cacheLock) {
            cached = null
            cacheInitialized = false
        }
    }

    // ===== Per-OS detection =====

    private fun detectMacOS(): DetectedBrowser? {
        val override = macCandidatesOverride
        if (override != null) {
            // Tests inject already-absolute paths so we skip the /Applications prefix.
            for ((name, absolutePath) in override) {
                val file = File(absolutePath)
                if (file.exists() && file.canExecute()) {
                    return DetectedBrowser(file, name)
                }
            }
            return null
        }
        val userHome = System.getProperty("user.home") ?: ""
        val roots = listOfNotNull(
            "/Applications",
            if (userHome.isNotEmpty()) "$userHome/Applications" else null,
        )
        for ((name, relPath) in MACOS_CANDIDATES) {
            for (root in roots) {
                val candidate = File("$root/$relPath")
                if (candidate.exists() && candidate.canExecute()) {
                    return DetectedBrowser(candidate, name)
                }
            }
        }
        return null
    }

    private fun detectWindows(): DetectedBrowser? {
        val candidates = windowsCandidatesOverride ?: WINDOWS_CANDIDATES
        for (cand in candidates) {
            // Use System.getenv (NOT System.getProperty) — critical for ProgramFiles(x86).
            val root = System.getenv(cand.envVar) ?: continue
            if (root.isEmpty()) continue
            val file = File(root + cand.relPath)
            if (file.exists() && file.canExecute()) {
                return DetectedBrowser(file, cand.name)
            }
        }
        return null
    }

    private fun detectLinux(): DetectedBrowser? {
        val resolver = linuxDetectorOverride ?: ::resolveLinuxBinary
        for ((name, binary) in LINUX_BINARY_NAMES) {
            val file = resolver(binary)
            if (file != null) {
                return DetectedBrowser(file, name)
            }
        }
        return null
    }

    /**
     * Resolves a Linux binary name to its absolute path via `command -v` (POSIX
     * builtin, more portable than `which` — works on Alpine/BusyBox). Returns
     * `null` if the binary is not found, the lookup times out, or any error
     * occurs. Never throws.
     */
    private fun resolveLinuxBinary(name: String): File? {
        return try {
            val process = ProcessBuilder("/bin/sh", "-c", "command -v $name 2>/dev/null")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            val output = process.inputStream.bufferedReader().readText().trim()
            if (output.isEmpty()) return null
            val file = File(output)
            if (file.exists() && file.canExecute()) file else null
        } catch (t: Throwable) {
            System.err.println("BrowserDetector.resolveLinuxBinary($name) failed: ${t.message}")
            null
        }
    }
}
