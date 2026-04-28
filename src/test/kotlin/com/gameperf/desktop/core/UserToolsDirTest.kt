package com.gameperf.desktop.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [UserToolsDir].
 *
 * Tests the pure path construction functions — [base] and [tool] —
 * to verify correct OS-specific directory resolution.
 */
class UserToolsDirTest {

    // ═══════ base() — OS-specific user-writable tools directory ═══════

    @Test
    fun `base on Windows returns LOCALAPPDATA path`() {
        val result = UserToolsDir.base(isWindows = true, localAppData = """C:\Users\test\AppData\Local""")
        // Use .path (not .absolutePath) so the test does not depend on the runtime CWD.
        // Compare against the expected segments rather than a literal so both `\` and `/`
        // separators are accepted (File normalizes per platform).
        val normalized = result.path.replace('\\', '/')
        assertEquals("C:/Users/test/AppData/Local/GamePerf/tools", normalized)
    }

    @Test
    fun `base on Windows uses LOCALAPPDATA environment variable`() {
        val localAppData = """C:\Users\test\AppData\Local"""
        val result = UserToolsDir.base(isWindows = true, localAppData = localAppData)
        val normalized = result.path.replace('\\', '/')
        assertTrue(normalized.startsWith("C:/Users/test/AppData/Local"))
        assertTrue(normalized.contains("GamePerf"))
    }

    @Test
    fun `base on macOS returns Library Application Support path`() {
        val result = UserToolsDir.base(isWindows = false, isMac = true, userHome = "/Users/test")
        // Normalize: on Windows test runners, File("/Users/test/...") still preserves
        // forward slashes in `path` (only `absolutePath` rewrites them with the drive).
        val normalized = result.path.replace('\\', '/')
        assertEquals("/Users/test/Library/Application Support/GamePerf/tools", normalized)
    }

    @Test
    fun `base on Linux returns local share path`() {
        val result = UserToolsDir.base(isWindows = false, isMac = false, userHome = "/home/test")
        val normalized = result.path.replace('\\', '/')
        assertEquals("/home/test/.local/share/GamePerf/tools", normalized)
    }

    // ═══════ tool() — path to specific tool binary ═══════

    @Test
    fun `tool returns base plus tool name on Windows`() {
        val base = File("""C:\Users\test\AppData\Local\GamePerf\tools""")
        val result = UserToolsDir.tool(base, "adb", isWindows = true)
        val normalized = result.path.replace('\\', '/')
        assertEquals("C:/Users/test/AppData/Local/GamePerf/tools/adb.exe", normalized)
    }

    @Test
    fun `tool returns base plus tool name on Unix`() {
        val base = File("/home/test/.local/share/GamePerf/tools")
        val result = UserToolsDir.tool(base, "ffmpeg", isWindows = false)
        val normalized = result.path.replace('\\', '/')
        assertEquals("/home/test/.local/share/GamePerf/tools/ffmpeg", normalized)
    }

    @Test
    fun `tool on Windows appends exe extension`() {
        val base = File("""C:\tools""")
        val result = UserToolsDir.tool(base, "ffmpeg", isWindows = true)
        assertTrue(result.path.endsWith(".exe"))
    }

    @Test
    fun `tool on Unix does not append extension`() {
        val base = File("/usr/local/bin")
        val result = UserToolsDir.tool(base, "ffmpeg", isWindows = false)
        assertFalse(result.path.endsWith(".exe"))
        assertTrue(result.path.endsWith("ffmpeg"))
    }
}
