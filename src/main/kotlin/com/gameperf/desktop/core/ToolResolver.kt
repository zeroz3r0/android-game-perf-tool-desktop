package com.gameperf.desktop.core

import java.io.File

/**
 * Cross-platform locator for external command-line tools (ffmpeg, ffprobe, etc.).
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
 * v4.2.3 consolidates both copies into this object, fixes `where` on Windows,
 * and expands the Windows candidate list to cover the four mainstream package
 * managers. A pure `findInCandidates` function is exposed for unit testing
 * without spawning `where`/`which` processes.
 *
 * ## Lookup order
 *
 * 1. **OS-native PATH lookup** — `where <tool>` on Windows, `which <tool>` on
 *    Unix. Honors the user's PATH exactly, so if the tool is available as the
 *    bare name (`ffmpeg`) from a terminal, this step will find it.
 * 2. **Well-known install locations** — a curated list per OS covering:
 *    - Windows: `C:\ffmpeg\bin\`, Program Files, Chocolatey, Scoop (shim +
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

        // Step 1: native PATH lookup (respects user PATH, PATHEXT, etc.)
        runPathLookup(tool, isWindows)?.let { return it }

        // Step 2: curated candidate list for the platform
        return findInCandidates(candidatesFor(tool, exeName, isWindows))
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
     * Build the candidate path list for the given tool and platform. Pure —
     * consults only env vars and system properties, no filesystem I/O or
     * subprocess spawning. Unit-testable.
     *
     * Order matters: the first candidate that exists wins. We put the most
     * common install locations first (C:\ffmpeg\bin\, /usr/local/bin/) so the
     * average user gets the fast path.
     */
    internal fun candidatesFor(tool: String, exeName: String, isWindows: Boolean): List<String> =
        if (isWindows) windowsCandidates(tool, exeName) else unixCandidates(tool)

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
    private fun windowsCandidates(tool: String, exeName: String): List<String> {
        val userHome = System.getProperty("user.home").orEmpty()
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        val staticPaths = listOf(
            """C:\ffmpeg\bin\$exeName""",
            """C:\Program Files\ffmpeg\bin\$exeName""",
            """C:\ProgramData\chocolatey\bin\$exeName""",
            """$userHome\scoop\shims\$exeName""",
            """$userHome\scoop\apps\ffmpeg\current\bin\$exeName""",
        )
        return staticPaths + winGetCandidates(localAppData, tool, exeName)
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
     */
    internal fun winGetCandidates(localAppData: String, tool: String, exeName: String): List<String> {
        if (localAppData.isEmpty()) return emptyList()
        return try {
            val root = File("""$localAppData\Microsoft\WinGet\Packages""")
            if (!root.isDirectory) return emptyList()
            root.listFiles { f -> f.isDirectory && f.name.contains("ffmpeg", ignoreCase = true) }
                ?.flatMap { pkgDir ->
                    pkgDir.listFiles { f -> f.isDirectory && f.name.startsWith("ffmpeg-") }
                        ?.map { File(it, """bin\$exeName""").absolutePath }
                        ?: emptyList()
                }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
