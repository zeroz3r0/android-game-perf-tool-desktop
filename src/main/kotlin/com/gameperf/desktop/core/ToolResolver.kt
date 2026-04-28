package com.gameperf.desktop.core

import java.io.File

/**
 * Cross-platform locator for external command-line tools (ffmpeg, ffprobe, adb, etc.).
 *
 * ## Why this exists
 *
 * Up to v4.2.2 the lookup logic was copy-pasted in at least two places
 * (`AdbBridge.findFfmpegImpl` and `EmbeddedVideoPlayer.findFfmpeg`) with the
 * same Unix-first defects:
 * - Used `which <tool>` (a no-op on Windows — the Windows equivalent is `where`).
 * - Only checked a single hardcoded Windows path (`C:\ffmpeg\bin\<tool>.exe`).
 *   Users who installed ffmpeg with WinGet, Scoop, Chocolatey, or manually in
 *   a non-standard folder got `findFfmpeg() == null` → `AdbBridge.concatSegments`
 *   returned null silently → the session videoPath stayed pointing at the
 *   first ~3-minute segment (`_0.mp4`), capping playback at ~2:56 regardless
 *   of how long the real session was.
 *
 * v4.2.3 consolidates the ffmpeg/ffprobe copies into this object, fixes `where`
 * on Windows, and expands the Windows candidate list to cover the four mainstream
 * package managers. A pure `findInCandidates` function is exposed for unit testing
 * without spawning `where`/`which` processes.
 *
 * v4.2.13 extends the resolver to cover **adb** as well — `AdbBridge.adbPath`
 * used to duplicate the same `which`-on-Windows + single-hardcoded-path bug for
 * adb (`C:\platform-tools\adb.exe` was the only Windows candidate), and
 * `IosBridge.findFfprobe` had its own third copy of the same broken pattern.
 * Both now delegate to [find], which routes per-tool candidates through a
 * dispatch table (see [toolSpecificCandidates]).
 *
 * ## Lookup order
 *
 * 1. **OS-native PATH lookup** — `where <tool>` on Windows, `which <tool>` on
 *    Unix. Honors the user's PATH exactly, so if the tool is available as the
 *    bare name (`ffmpeg`) from a terminal, this step will find it.
 * 2. **Tool-specific well-known locations** — paths that only make sense for a
 *    single tool (e.g. the Android SDK's `platform-tools/adb` under the user
 *    home, iTunes's bundled `AppleApplicationSupport` for ios tooling).
 * 3. **Generic well-known install locations** — a curated list per OS covering:
 *    - Windows: `C:\<tool>\bin\`, Program Files, Chocolatey, Scoop (shim +
 *      current dir), WinGet (globbed dynamic version folders).
 *    - Unix: `/usr/local/bin`, `/opt/homebrew/bin`, `/usr/bin`, `~/.local/bin`.
 *
 * Returns the first absolute path that exists, or null if nothing found.
 */
internal object ToolResolver {

    /**
     * Locate an external tool binary.
     *
     * @param tool The tool name without extension (e.g. "ffmpeg", "ffprobe").
     *             On Windows the `.exe` suffix is appended automatically for
     *             the candidate paths; the PATH lookup uses the bare name
     *             because `where` resolves the extension via PATHEXT.
     * @return The absolute path to the binary, or null if not found.
     */
    fun find(tool: String): String? {
        val isWindows = System.getProperty("os.name").orEmpty()
            .lowercase()
            .contains("win")
        val exeName = if (isWindows) "$tool.exe" else tool

        // Step 0 (v4.2.14): UserToolsDir - user-installed tools take priority
        // to ensure the app works offline with the exact version it bundled or downloaded.
        // This is checked BEFORE PATH to ensure consistent behavior across machines.
        findInCandidates(userToolsDirCandidates(tool, exeName, isWindows))?.let { return it }

        // Step 1: native PATH lookup (respects user PATH, PATHEXT, etc.)
        runPathLookup(tool, isWindows)?.let { return it }

        // Step 2: tool-specific well-known locations (Android SDK for adb, etc.)
        findInCandidates(toolSpecificCandidates(tool, exeName, isWindows))?.let { return it }

        // Step 3: generic candidate list for the platform
        return findInCandidates(candidatesFor(tool, exeName, isWindows))
    }

    /**
     * Build candidate path for UserToolsDir (user-installed tools directory).
     *
     * Pure function - uses injected/testable parameters for OS detection.
     * Returns empty list if UserToolsDir doesn't exist (caller checks existence).
     */
    @Suppress("UNUSED_PARAMETER") // exeName kept for API symmetry with sibling candidate fns; UserToolsDir.tool() resolves the extension internally.
    internal fun userToolsDirCandidates(
        tool: String,
        exeName: String,
        isWindows: Boolean,
        isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac"),
        localAppData: String = System.getenv("LOCALAPPDATA").orEmpty(),
        userHome: String = System.getProperty("user.home").orEmpty()
    ): List<String> {
        if (!UserToolsDir.exists(isWindows, isMac, localAppData, userHome)) {
            return emptyList()
        }
        val baseDir = UserToolsDir.base(isWindows, localAppData, isMac, userHome)
        val toolPath = UserToolsDir.tool(baseDir, tool)
        return listOf(toolPath.absolutePath)
    }

    /**
     * Check if UserToolsDir exists on this system.
     *
     * Pure - parameters allow testing without mocking System properties.
     */
    internal fun userToolsDirExists(
        isWindows: Boolean,
        isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac"),
        localAppData: String = System.getenv("LOCALAPPDATA").orEmpty(),
        userHome: String = System.getProperty("user.home").orEmpty()
    ): Boolean {
        return UserToolsDir.exists(isWindows, isMac, localAppData, userHome)
    }

    /**
     * Run `where <tool>` on Windows, `which <tool>` on Unix. Returns the first
     * non-empty line that resolves to an existing file, or null.
     *
     * Wrapped in a try/catch because on highly restricted systems (Windows S
     * mode, some CI containers) `where`/`which` can be missing or refuse to
     * execute. We treat any failure as "not found" and fall back to Step 2.
     */
    private fun runPathLookup(tool: String, isWindows: Boolean): String? = try {
        val lookupCmd = if (isWindows) "where" else "which"
        val proc = ProcessBuilder(lookupCmd, tool).start()
        val result = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        result.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && File(it).exists() }
    } catch (_: Exception) {
        null
    }

    /**
     * Pure function — given a list of candidate paths, return the first that
     * exists on disk. Factored out of [find] so it can be unit-tested without
     * spawning a `where` / `which` subprocess.
     */
    internal fun findInCandidates(candidates: List<String>): String? =
        candidates.firstOrNull { File(it).exists() }

    /**
     * Tool-specific well-known locations that don't fit the generic
     * "`C:\<tool>\bin\`, `/usr/local/bin`" template. Consulted BEFORE the
     * generic candidate list.
     *
     * Pure — reads only env vars and system properties. Unit-testable.
     *
     * ## Coverage
     *
     * **adb**: the Android SDK ships a single canonical sub-path
     * (`platform-tools/adb`). Users install the SDK either via Android Studio
     * (which drops it under `~/Library/Android/sdk` on macOS,
     * `%LOCALAPPDATA%\Android\Sdk` on Windows, `~/Android/Sdk` on Linux),
     * via `brew install --cask android-platform-tools` on macOS, or as a
     * standalone zip extracted to `C:\platform-tools\` (the pre-SDK-manager
     * path that Google still documents). None of those land in a generic
     * `bin/` directory, so the generic [candidatesFor] list misses all of
     * them. This entry covers every mainstream install vector.
     *
     * Returns empty for any tool that has no tool-specific locations; those
     * fall through to [candidatesFor].
     */
    internal fun toolSpecificCandidates(tool: String, exeName: String, isWindows: Boolean): List<String> {
        return when (tool) {
            "adb" -> adbCandidates(exeName, isWindows)
            else -> emptyList()
        }
    }

    /**
     * Android SDK / platform-tools install locations for `adb`.
     *
     * Order (first match wins):
     * 1. Android Studio SDK under user home (most common — Studio creates this
     *    on first run and 99% of users leave the default).
     * 2. `%LOCALAPPDATA%\Android\Sdk` (Windows Android Studio default).
     * 3. Standalone platform-tools zip extracted to `C:\platform-tools\`
     *    (Windows-only — the path Google's "SDK platform-tools release notes"
     *    page still references for zip-only users).
     * 4. Homebrew casks on macOS (`/usr/local/Caskroom` Intel,
     *    `/opt/homebrew/Caskroom` Apple Silicon).
     * 5. Linux package managers that drop adb under `/usr/lib/android-sdk/`
     *    (Debian `android-tools-adb`, Arch `android-tools`).
     */
    internal fun adbCandidates(exeName: String, isWindows: Boolean): List<String> {
        val userHome = System.getProperty("user.home").orEmpty()
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        return if (isWindows) {
            listOf(
                // Android Studio default on Windows
                if (localAppData.isNotEmpty()) """$localAppData\Android\Sdk\platform-tools\$exeName""" else "",
                // User-scope SDK (some installers pick %USERPROFILE%\AppData\Local\Android\Sdk explicitly,
                // others fall back to %USERPROFILE%\Android\Sdk)
                """$userHome\AppData\Local\Android\Sdk\platform-tools\$exeName""",
                """$userHome\Android\Sdk\platform-tools\$exeName""",
                // Standalone zip install — the "download platform-tools only" path
                """C:\platform-tools\$exeName""",
                """C:\Android\platform-tools\$exeName""",
                """C:\Android\Sdk\platform-tools\$exeName""",
            ).filter { it.isNotEmpty() }
        } else {
            listOf(
                // Android Studio default — macOS
                "$userHome/Library/Android/sdk/platform-tools/adb",
                // Android Studio default — Linux
                "$userHome/Android/Sdk/platform-tools/adb",
                // Homebrew cask — Apple Silicon
                "/opt/homebrew/Caskroom/android-platform-tools/latest/platform-tools/adb",
                // Homebrew cask — Intel Mac
                "/usr/local/Caskroom/android-platform-tools/latest/platform-tools/adb",
                // Linux distro package (Debian android-tools-adb, Arch android-tools)
                "/usr/lib/android-sdk/platform-tools/adb",
            )
        }
    }

    /**
     * Build the candidate path list for the given tool and platform. Pure —
     * consults only env vars and system properties, no filesystem I/O or
     * subprocess spawning. Unit-testable.
     *
     * Order matters: the first candidate that exists wins. We put the most
     * common install locations first (C:\ffmpeg\bin\, /usr/local/bin/) so the
     * average user gets the fast path.
     */
    internal fun candidatesFor(tool: String, exeName: String, isWindows: Boolean): List<String> =
        if (isWindows) windowsCandidates(exeName) else unixCandidates(tool)

    /**
     * Windows install locations, ordered by likelihood:
     *
     * - `C:\ffmpeg\bin\` — manual install (most "follow a tutorial" path).
     * - `C:\Program Files\ffmpeg\bin\` — some MSI installers.
     * - `C:\ProgramData\chocolatey\bin\` — `choco install ffmpeg` (both the
     *   binary and a shim live here).
     * - `%USERPROFILE%\scoop\shims\` — `scoop install ffmpeg` (shim that
     *   resolves to the current version in `apps\ffmpeg\current\bin\`).
     * - `%USERPROFILE%\scoop\apps\ffmpeg\current\bin\` — direct path into the
     *   Scoop apps tree, used if the shim is missing.
     * - WinGet packages — `winget install ffmpeg` or `winget install
     *   yt-dlp.FFmpeg`. The package folder has a dynamic version subdir
     *   (`ffmpeg-N-<hash>`), so we glob via [winGetCandidates].
     */
    private fun windowsCandidates(exeName: String): List<String> {
        val userHome = System.getProperty("user.home").orEmpty()
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        val staticPaths = listOf(
            """C:\ffmpeg\bin\$exeName""",
            """C:\Program Files\ffmpeg\bin\$exeName""",
            """C:\ProgramData\chocolatey\bin\$exeName""",
            """$userHome\scoop\shims\$exeName""",
            """$userHome\scoop\apps\ffmpeg\current\bin\$exeName""",
        )
        return staticPaths + winGetCandidates(localAppData, exeName)
    }

    /**
     * Unix install locations, ordered by likelihood.
     *
     * - `/usr/local/bin/` — Homebrew Intel Mac, manual `make install` on Linux.
     * - `/opt/homebrew/bin/` — Homebrew Apple Silicon.
     * - `/usr/bin/` — system package manager (apt, dnf, pacman).
     * - `~/.local/bin/` — user-scope pip / pipx / cargo installs.
     */
    private fun unixCandidates(tool: String): List<String> {
        val userHome = System.getProperty("user.home").orEmpty()
        return listOf(
            "/usr/local/bin/$tool",
            "/opt/homebrew/bin/$tool",
            "/usr/bin/$tool",
            "$userHome/.local/bin/$tool",
        )
    }

    /**
     * WinGet package paths have dynamic version folders, so we glob them.
     * Layout: `%LOCALAPPDATA%\Microsoft\WinGet\Packages\<publisher>.<pkg>_*\ffmpeg-*\bin\<tool>.exe`
     *
     * Examples seen in the wild:
     *
     *   yt-dlp.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-N-123778-...\bin\
     *   Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-7.1-full_build\bin\
     *
     * Package name match is case-insensitive on "ffmpeg" so both yt-dlp.FFmpeg
     * and Gyan.FFmpeg get picked up. If multiple versions exist we return all
     * of them — [findInCandidates] picks the first existing one. Returns empty
     * list if `%LOCALAPPDATA%` is unset or the WinGet root doesn't exist.
     *
     * v4.2.4: paths built with nested [File] constructors instead of string
     * concatenation with backslashes, so the function is testable on Linux CI
     * (where `\` is a filename character, not a separator).
     */
    internal fun winGetCandidates(localAppData: String, exeName: String): List<String> {
        if (localAppData.isEmpty()) return emptyList()
        return try {
            val root = File(File(File(localAppData, "Microsoft"), "WinGet"), "Packages")
            if (!root.isDirectory) return emptyList()
            root.listFiles { f -> f.isDirectory && f.name.contains("ffmpeg", ignoreCase = true) }
                ?.flatMap { pkgDir ->
                    pkgDir.listFiles { f -> f.isDirectory && f.name.startsWith("ffmpeg-") }
                        ?.map { File(File(it, "bin"), exeName).absolutePath }
                        ?: emptyList()
                }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
