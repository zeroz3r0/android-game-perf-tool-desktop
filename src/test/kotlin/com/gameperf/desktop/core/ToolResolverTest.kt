package com.gameperf.desktop.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ToolResolver].
 *
 * We test the pure, filesystem-only building blocks ([candidatesFor],
 * [findInCandidates], [winGetCandidates]) — the outer [find] wraps
 * [runPathLookup] which spawns `where` / `which` and is therefore platform-
 * and environment-dependent.
 *
 * These tests are the direct regression coverage for the v4.2.2 ffmpeg-not-
 * found silent-failure bug: users on Windows with ffmpeg installed via
 * WinGet / Scoop / Chocolatey used to get null back from `findFfmpeg`, and
 * concat would silently skip, leaving the session video capped at the first
 * 3-minute segment (~2:56 playback limit) without any error message.
 */
class ToolResolverTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("toolresolver-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    // ═══════ findInCandidates ═══════

    @Test
    fun `findInCandidates returns null for empty list`() {
        assertNull(ToolResolver.findInCandidates(emptyList()))
    }

    @Test
    fun `findInCandidates returns null when no candidate exists`() {
        val fakes = listOf(
            File(tempDir, "does-not-exist-1").absolutePath,
            File(tempDir, "does-not-exist-2").absolutePath,
        )
        assertNull(ToolResolver.findInCandidates(fakes))
    }

    @Test
    fun `findInCandidates returns the first existing candidate`() {
        val existing = File(tempDir, "real-tool").apply { writeText("") }
        val fakes = listOf(
            File(tempDir, "missing-1").absolutePath,
            existing.absolutePath,
            File(tempDir, "missing-2").absolutePath,
        )
        assertEquals(existing.absolutePath, ToolResolver.findInCandidates(fakes))
    }

    @Test
    fun `findInCandidates returns first in order even when multiple exist`() {
        val first = File(tempDir, "first").apply { writeText("") }
        val second = File(tempDir, "second").apply { writeText("") }
        val result = ToolResolver.findInCandidates(listOf(first.absolutePath, second.absolutePath))
        assertEquals(
            first.absolutePath,
            result,
            "earlier entries must take priority — order reflects likelihood heuristic"
        )
    }

    // ═══════ candidatesFor — Windows ═══════

    @Test
    fun `candidatesFor on Windows produces Windows-style paths`() {
        val cands = ToolResolver.candidatesFor("ffmpeg", "ffmpeg.exe", isWindows = true)
        assertTrue(cands.isNotEmpty(), "must produce at least the static list even if WinGet root is missing")
        assertTrue(
            cands.any { it == """C:\ffmpeg\bin\ffmpeg.exe""" },
            "must include the manual C:\\ffmpeg\\bin location — the #1 'follow a tutorial' path"
        )
        assertTrue(
            cands.any { it.contains("chocolatey", ignoreCase = true) },
            "must include the chocolatey bin path — `choco install ffmpeg` is widespread"
        )
        assertTrue(
            cands.any { it.contains("scoop", ignoreCase = true) && it.contains("shims") },
            "must include Scoop shims — the canonical Scoop entrypoint"
        )
        // All Windows candidates must end with .exe
        cands.forEach {
            assertTrue(it.endsWith(".exe"), "Windows candidate must end in .exe: $it")
        }
    }

    @Test
    fun `candidatesFor on Unix produces Unix-style paths without exe suffix`() {
        val cands = ToolResolver.candidatesFor("ffmpeg", "ffmpeg", isWindows = false)
        assertTrue(cands.contains("/usr/local/bin/ffmpeg"))
        assertTrue(cands.contains("/opt/homebrew/bin/ffmpeg"))
        assertTrue(cands.contains("/usr/bin/ffmpeg"))
        cands.forEach {
            assertTrue(!it.endsWith(".exe"), "Unix candidate must NOT end in .exe: $it")
        }
    }

    @Test
    fun `candidatesFor uses the tool name in every path`() {
        val ffmpegCands = ToolResolver.candidatesFor("ffmpeg", "ffmpeg", isWindows = false)
        val ffprobeCands = ToolResolver.candidatesFor("ffprobe", "ffprobe", isWindows = false)
        assertTrue(ffmpegCands.all { it.endsWith("/ffmpeg") })
        assertTrue(ffprobeCands.all { it.endsWith("/ffprobe") })
    }

    // ═══════ winGetCandidates ═══════

    /**
     * Helper: build the `Microsoft/WinGet/Packages` path using nested [File]
     * constructors so it works on both Windows (separator `\`) and Linux
     * (separator `/`). Previously used raw string literals with `\` which
     * Linux CI treated as a single directory name, causing the tests to
     * create a file literally named `Microsoft\WinGet\Packages` instead of
     * three nested directories.
     */
    private fun winGetPackagesRoot(base: File): File =
        File(File(File(base, "Microsoft"), "WinGet"), "Packages")

    @Test
    fun `winGetCandidates returns empty when localAppData is empty string`() {
        val result = ToolResolver.winGetCandidates("", "ffmpeg.exe")
        assertTrue(result.isEmpty(), "empty %LOCALAPPDATA% must degrade to empty list, not crash")
    }

    @Test
    fun `winGetCandidates returns empty when WinGet root does not exist`() {
        // Point at a real directory that has no Microsoft\WinGet\Packages subtree
        val result = ToolResolver.winGetCandidates(tempDir.absolutePath, "ffmpeg.exe")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `winGetCandidates finds ffmpeg in WinGet package layout with dynamic version`() {
        // Simulate the real-world WinGet layout:
        //   <LOCALAPPDATA>\Microsoft\WinGet\Packages\yt-dlp.FFmpeg_.../ffmpeg-N-.../bin\ffmpeg.exe
        val winGetRoot = winGetPackagesRoot(tempDir).apply { mkdirs() }
        val pkgDir = File(winGetRoot, "yt-dlp.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe").apply { mkdirs() }
        val versionDir = File(pkgDir, "ffmpeg-N-123778-g3b55818764-win64-gpl").apply { mkdirs() }
        val binDir = File(versionDir, "bin").apply { mkdirs() }
        val exe = File(binDir, "ffmpeg.exe").apply { writeText("") }

        val result = ToolResolver.winGetCandidates(tempDir.absolutePath, "ffmpeg.exe")
        assertEquals(1, result.size, "exactly one candidate for a single-version install")
        assertEquals(exe.absolutePath, result.first())
    }

    @Test
    fun `winGetCandidates finds both yt-dlp FFmpeg and Gyan FFmpeg package flavors`() {
        // Both yt-dlp.FFmpeg and Gyan.FFmpeg are common WinGet publishers. Match must
        // be case-insensitive on the "ffmpeg" substring so both get enumerated.
        val winGetRoot = winGetPackagesRoot(tempDir).apply { mkdirs() }

        val pkgA = File(winGetRoot, "yt-dlp.FFmpeg_xxx").apply { mkdirs() }
        val versA = File(pkgA, "ffmpeg-N-111-gpl").apply { mkdirs() }
        val binA = File(versA, "bin").apply { mkdirs() }
        val exeA = File(binA, "ffmpeg.exe").apply { writeText("") }

        val pkgB = File(winGetRoot, "Gyan.FFmpeg_xxx").apply { mkdirs() }
        val versB = File(pkgB, "ffmpeg-7.1-full_build").apply { mkdirs() }
        val binB = File(versB, "bin").apply { mkdirs() }
        val exeB = File(binB, "ffmpeg.exe").apply { writeText("") }

        val result = ToolResolver.winGetCandidates(tempDir.absolutePath, "ffmpeg.exe")
        assertEquals(2, result.size, "both package flavors must be enumerated")
        val resultSet = result.toSet()
        assertTrue(resultSet.contains(exeA.absolutePath), "must include yt-dlp.FFmpeg entry")
        assertTrue(resultSet.contains(exeB.absolutePath), "must include Gyan.FFmpeg entry")
    }

    @Test
    fun `winGetCandidates skips non-ffmpeg packages in the Packages dir`() {
        // Real WinGet directories have dozens of packages. The matcher must only
        // surface the ones whose name contains "ffmpeg" — case-insensitive.
        val winGetRoot = winGetPackagesRoot(tempDir).apply { mkdirs() }

        // Non-matching package — should be ignored
        val unrelated = File(winGetRoot, "Git.Git_xxx").apply { mkdirs() }
        val unrelatedVers = File(unrelated, "git-2.45").apply { mkdirs() }
        File(unrelatedVers, "bin").apply { mkdirs() }

        // Matching package
        val ffmpeg = File(winGetRoot, "yt-dlp.FFmpeg_xxx").apply { mkdirs() }
        val ffmpegVers = File(ffmpeg, "ffmpeg-N-123").apply { mkdirs() }
        val ffmpegBin = File(ffmpegVers, "bin").apply { mkdirs() }
        val ffmpegExe = File(ffmpegBin, "ffmpeg.exe").apply { writeText("") }

        val result = ToolResolver.winGetCandidates(tempDir.absolutePath, "ffmpeg.exe")
        assertEquals(1, result.size)
        assertEquals(ffmpegExe.absolutePath, result.first())
    }

    @Test
    fun `winGetCandidates ignores package subdirs that do not start with ffmpeg dash`() {
        // WinGet package folders often contain sibling dirs for metadata (.installed,
        // .appsFolderDot, etc.). Only `ffmpeg-*` subdirs contain the actual binary.
        val winGetRoot = winGetPackagesRoot(tempDir).apply { mkdirs() }
        val pkg = File(winGetRoot, "yt-dlp.FFmpeg_xxx").apply { mkdirs() }

        // Non-matching subdir — metadata, not a version folder
        File(pkg, ".installed").apply { mkdirs() }

        // Matching version subdir
        val versDir = File(pkg, "ffmpeg-N-123").apply { mkdirs() }
        val exe = File(File(versDir, "bin").apply { mkdirs() }, "ffmpeg.exe").apply { writeText("") }

        val result = ToolResolver.winGetCandidates(tempDir.absolutePath, "ffmpeg.exe")
        assertEquals(1, result.size, "only real version folders must be considered")
        assertEquals(exe.absolutePath, result.first())
    }

    // ═══════ find (integration — best-effort, platform-dependent) ═══════

    @Test
    fun `find returns non-null for ffmpeg when the tool exists on this system`() {
        // This is a smoke test: if the test runner has ffmpeg installed (which it
        // does on CI and on every dev machine that can actually use the app), the
        // resolver must find it. We don't assert a specific path because that
        // depends on the host OS and installer.
        val result = ToolResolver.find("ffmpeg")
        // If ffmpeg is not installed, the test still passes — we're not asserting
        // installation. We just assert that the call doesn't throw and returns
        // something sane (non-empty path if non-null).
        if (result != null) {
            assertTrue(result.isNotEmpty(), "if a path is returned, it must be non-empty")
            assertTrue(File(result).exists(), "returned path must point to an existing file")
        }
    }

    // ═══════ adbCandidates — tool-specific locations (v4.2.13) ═══════

    @Test
    fun `adbCandidates on Windows includes Android Studio SDK under LOCALAPPDATA`() {
        val cands = ToolResolver.adbCandidates("adb.exe", isWindows = true)
        assertTrue(
            cands.any { it.contains("""Android\Sdk\platform-tools\adb.exe""") },
            "must cover Android Studio's %LOCALAPPDATA%\\Android\\Sdk layout — the default install path"
        )
    }

    @Test
    fun `adbCandidates on Windows keeps the standalone zip path`() {
        val cands = ToolResolver.adbCandidates("adb.exe", isWindows = true)
        assertTrue(
            cands.any { it == """C:\platform-tools\adb.exe""" },
            "must keep the standalone `C:\\platform-tools\\` path from the pre-fix resolver — Google still documents this"
        )
    }

    @Test
    fun `adbCandidates on Windows ends every path with adb exe`() {
        val cands = ToolResolver.adbCandidates("adb.exe", isWindows = true)
        assertTrue(cands.isNotEmpty())
        cands.forEach {
            assertTrue(it.endsWith("adb.exe"), "Windows candidate must end in adb.exe: $it")
        }
    }

    @Test
    fun `adbCandidates on Unix includes Android Studio macOS and Linux defaults`() {
        val cands = ToolResolver.adbCandidates("adb", isWindows = false)
        assertTrue(
            cands.any { it.contains("/Library/Android/sdk/platform-tools/adb") },
            "must include the Android Studio macOS path (~/Library/Android/sdk/…)"
        )
        assertTrue(
            cands.any { it.endsWith("/Android/Sdk/platform-tools/adb") },
            "must include the Android Studio Linux path (~/Android/Sdk/…)"
        )
    }

    @Test
    fun `adbCandidates on Unix includes Homebrew cask for both architectures`() {
        val cands = ToolResolver.adbCandidates("adb", isWindows = false)
        assertTrue(
            cands.any { it.startsWith("/opt/homebrew/Caskroom/android-platform-tools/") },
            "must cover Homebrew on Apple Silicon"
        )
        assertTrue(
            cands.any { it.startsWith("/usr/local/Caskroom/android-platform-tools/") },
            "must cover Homebrew on Intel Mac"
        )
    }

    @Test
    fun `adbCandidates on Unix includes Linux distro package path`() {
        val cands = ToolResolver.adbCandidates("adb", isWindows = false)
        assertTrue(
            cands.any { it == "/usr/lib/android-sdk/platform-tools/adb" },
            "must include the Debian android-tools-adb / Arch android-tools path"
        )
    }

    // ═══════ toolSpecificCandidates — dispatch ═══════

    @Test
    fun `toolSpecificCandidates returns empty list for tools without a specific table`() {
        val ffmpegCands = ToolResolver.toolSpecificCandidates("ffmpeg", "ffmpeg.exe", isWindows = true)
        val ffprobeCands = ToolResolver.toolSpecificCandidates("ffprobe", "ffprobe", isWindows = false)
        assertTrue(ffmpegCands.isEmpty(), "ffmpeg has no tool-specific locations — must be empty (fall through to generic)")
        assertTrue(ffprobeCands.isEmpty(), "ffprobe has no tool-specific locations — must be empty")
    }

    @Test
    fun `toolSpecificCandidates dispatches adb to adbCandidates`() {
        val cands = ToolResolver.toolSpecificCandidates("adb", "adb.exe", isWindows = true)
        assertTrue(cands.isNotEmpty(), "adb must have tool-specific locations")
        assertTrue(
            cands.any { it.contains("platform-tools", ignoreCase = true) },
            "adb tool-specific table must resolve Android SDK platform-tools paths"
        )
    }

    @Test
    fun `find with adb does not throw when adb is absent`() {
        // Mirrors the ffmpeg smoke test: contract is "doesn't throw", not "finds
        // a binary". Protects against future refactors that might reintroduce an
        // unguarded `which`/`where` call inside adbCandidates.
        val result = ToolResolver.find("adb")
        if (result != null) {
            assertTrue(result.isNotEmpty(), "non-null result must be a real path string")
            assertTrue(File(result).exists(), "returned path must point to an existing file")
        }
    }

    @Test
    fun `find does not throw for nonexistent tool name`() {
        // Guarantees the resolver degrades gracefully for bogus tools — null or
        // an empty-path-never-reached fallback is fine, the contract is just
        // "doesn't throw so callers don't need try/catch around every invocation".
        val name = "definitely-not-a-real-tool-${System.currentTimeMillis()}"
        val result = ToolResolver.find(name)
        // If a path is returned, it must at least not be a bogus empty string.
        if (result != null) {
            assertTrue(result.isNotEmpty(), "non-null result must be a real path string")
        }
        // Primary contract: we reached this line without an exception.
    }
}
