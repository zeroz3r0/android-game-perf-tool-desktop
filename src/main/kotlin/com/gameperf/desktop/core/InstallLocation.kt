package com.gameperf.desktop.core

import java.io.File

/**
 * v4.3.8 — Pure helper that detects whether the running app's install directory
 * requires Windows administrator (UAC) elevation to write to.
 *
 * Used by [AutoUpdater] to decide between two update-apply paths:
 * - **direct write** (current process can replace its own JAR), or
 * - **UAC-elevated helper** (a separate elevated PowerShell process replaces the JAR).
 *
 * Why this is a separate object: the logic is pure (string prefix check) and trivial
 * to unit-test without touching the filesystem or the JVM's protectionDomain. Keeping
 * it out of [AutoUpdater] also avoids bloating that already-large object further (it
 * sits at 1037 lines as of v4.3.7).
 */
object InstallLocation {

    /**
     * Windows directories that require administrator rights to write into.
     * The list is prefix-matched (case-insensitive) against the install dir's
     * absolute path. Prefixes are stored lowercase.
     *
     * NOTE: we deliberately do NOT include user-profile-relative paths (AppData,
     * Documents, etc.) — those are user-writable and must stay on the direct-write
     * path. Only OS-managed locations belong here.
     */
    private val PROTECTED_WINDOWS_PREFIXES: List<String> = listOf(
        """c:\program files""",
        """c:\program files (x86)""",
        """c:\windows""",
        """c:\programdata""",
    )

    /**
     * Returns `true` if [installDir]'s absolute path lives under a Windows-protected
     * location and therefore needs UAC elevation to overwrite the JAR.
     *
     * On non-Windows hosts this always returns `false`: macOS and Linux jpackage
     * installs use sudo / xattr / ownership patterns that are out of scope for the
     * v4.3.8 fix (which is Windows-specific per the user report). If those platforms
     * ever need similar handling, this function gains a separate branch.
     *
     * The match is **case-insensitive prefix** because Windows paths can be reported
     * as `C:\Program Files`, `c:\PROGRAM FILES`, etc. depending on how the JVM was
     * launched. We normalize via `lowercase()` before comparing.
     *
     * @param installDir Absolute install directory. Must be the dir containing the
     *                   running JAR (or a parent equivalent), NOT a file path.
     * @param isWindows  Pass `true` when running on Windows. Threaded explicitly so
     *                   tests can exercise both branches without mocking system props.
     */
    fun requiresAdmin(installDir: File, isWindows: Boolean): Boolean {
        if (!isWindows) return false
        // v4.4.0: use `path` instead of `absolutePath` so the function works
        // identically on Linux CI runners (where `File("C:\\Program Files")`
        // is treated as a relative path and absolutePath would prepend the
        // CWD, breaking the prefix match). When called by production code on
        // Windows, the input File is already absolute so `path == absolutePath`.
        val normalized = installDir.path.replace('/', '\\').lowercase()
        return PROTECTED_WINDOWS_PREFIXES.any { normalized.startsWith(it) }
    }

    /**
     * Detect the directory the running JAR lives in by reading `java.class.path`.
     *
     * jpackage launchers configure the classpath to point at the bundle's main JAR
     * (e.g. `C:\Program Files\GamePerf\app\GamePerf.jar`), so the parent of the
     * first classpath entry is the `app/` subdirectory of the install root — which
     * is exactly where the JAR replacement happens.
     *
     * Returns `null` if:
     * - `java.class.path` is unset or empty, or
     * - the first entry's parent does not exist on disk
     *   (likely we are running from Gradle / IDE classes dirs without a JAR).
     */
    fun currentInstallDir(): File? {
        val classPath = System.getProperty("java.class.path") ?: return null
        val firstEntry = classPath.split(File.pathSeparator).firstOrNull() ?: return null
        if (firstEntry.isBlank()) return null
        return File(firstEntry).parentFile?.takeIf { it.exists() }
    }
}
