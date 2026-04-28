package com.gameperf.desktop.core

import java.io.File

/**
 * User-writable tools directory for in-app dependency bootstrap.
 *
 * Provides platform-specific paths for user-installed tools (adb, ffmpeg, etc.).
 * This directory takes priority over system PATH to ensure the app works offline
 * with the exact version it bundled or downloaded.
 *
 * ## OS-specific paths
 *
 * - **Windows**: `%LOCALAPPDATA%\GamePerf\tools\` (`%LOCALAPPDATA%` = `C:\Users\<user>\AppData\Local`)
 * - **macOS**: `~/Library/Application Support/GamePerf/tools/`
 * - **Linux**: `~/.local/share/GamePerf/tools/`
 */
object UserToolsDir {

    /**
     * Base directory for user-installed tools.
     *
     * @param isWindows Simulates Windows detection for testing.
     * @param localAppData Simulates the `LOCALAPPDATA` env var for testing.
     * @param isMac Simulates macOS detection for testing.
     * @param userHome Simulates the user home directory for testing.
     */
    fun base(
        isWindows: Boolean,
        localAppData: String = System.getenv("LOCALAPPDATA").orEmpty(),
        isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac"),
        userHome: String = System.getProperty("user.home").orEmpty()
    ): File {
        return if (isWindows) {
            File(localAppData, "GamePerf${File.separator}tools")
        } else if (isMac) {
            File(userHome, "Library/Application Support/GamePerf/tools")
        } else {
            // Linux or other Unix-like
            File(userHome, ".local/share/GamePerf/tools")
        }
    }

    /**
     * Path to a specific tool binary inside [baseDir].
     *
     * @param baseDir The base directory from [base].
     * @param toolName The tool name without extension (e.g. "adb", "ffmpeg").
     * @param isWindows Simulates Windows detection for testing. Defaults to the
     *                  current OS. On Windows, `.exe` is appended automatically.
     */
    fun tool(
        baseDir: File,
        toolName: String,
        isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win"),
    ): File {
        val exeName = if (isWindows) "$toolName.exe" else toolName
        return File(baseDir, exeName)
    }

    /**
     * Check if UserToolsDir exists on this system.
     *
     * @param isWindows Simulates Windows detection for testing.
     * @param isMac Simulates macOS detection for testing.
     * @param localAppData Simulates the LOCALAPPDATA env var for testing.
     * @param userHome Simulates the user home directory for testing.
     */
    fun exists(
        isWindows: Boolean,
        isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac"),
        localAppData: String = System.getenv("LOCALAPPDATA").orEmpty(),
        userHome: String = System.getProperty("user.home").orEmpty()
    ): Boolean {
        return base(isWindows, localAppData, isMac, userHome).exists()
    }
}
