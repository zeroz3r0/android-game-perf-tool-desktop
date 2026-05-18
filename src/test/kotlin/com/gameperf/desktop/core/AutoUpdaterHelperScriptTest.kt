package com.gameperf.desktop.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v4.6.1 — String-content tests for the [AutoUpdater.UAC_HELPER_PS1] PowerShell
 * helper template. The const is private, so we exercise it through the side
 * effect of [AutoUpdater.planElevatedUpdate] writing it to disk verbatim.
 *
 * Why this test file exists separate from [AutoUpdaterElevationTest]:
 *   - That test asserts on the BEHAVIOR of the planner (return values, args).
 *   - This test asserts on the CONTENT of the helper script body. The two
 *     concerns evolve independently (the planner's contract is stable but
 *     the helper script can change its wait/retry semantics).
 *
 * Bug context (engram obs #474, repro 2026-05-18): the helper aborted after
 * 30 s because Compose/Skiko cleanup needs more headroom, AND the process
 * filter matched ANY java.exe under the install dir (too broad). v4.6.1
 * bumps the timeout to 120 s, narrows the filter to the bundle's specific
 * launcher .exe + the runtime/bin/java.exe, and adds a diagnostic log line
 * listing the surviving PIDs before `exit 1`.
 */
class AutoUpdaterHelperScriptTest {

    private lateinit var tempDir: File
    private lateinit var helperScript: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("autoupdater-helper-script-").toFile()
        helperScript = writeHelperScript()
    }

    @AfterTest
    fun tearDown() {
        tempDir.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    /**
     * Drive [AutoUpdater.planElevatedUpdate] with valid fake inputs so the
     * template is written verbatim to a known path, then read it back.
     */
    private fun writeHelperScript(): File {
        val installRoot = File(tempDir, "Install").apply { mkdirs() }
        val appDir = File(installRoot, "app").apply { mkdirs() }
        val oldJar = File(appDir, "old.jar").apply { writeBytes(ByteArray(20)) }
        val appExe = File(installRoot, "GamePerf.exe").apply { writeBytes(ByteArray(4)) }
        val newJar = File(tempDir, "new.jar").apply { writeBytes(ByteArray(60_000_000)) }
        val helperDir = File(tempDir, "helper").apply { mkdirs() }
        AutoUpdater.planElevatedUpdate(
            newJar = newJar,
            oldJar = oldJar,
            installDir = installRoot,
            appExe = appExe,
            helperDir = helperDir,
        )
        return File(helperDir, "update-helper.ps1")
    }

    // ═══════ Fix 1: timeout 30 s → 120 s ═══════

    @Test
    fun `helper script timeout is 120 seconds, not 30`() {
        val body = helperScript.readText()
        // Real GREEN: the literal must be 120. We assert on the assignment line
        // explicitly so a stray `120` in a comment elsewhere doesn't fake a pass.
        assertTrue(
            body.contains("\$timeoutSec = 120"),
            "helper must use \$timeoutSec = 120 (was 30 in v4.6.0 — see bug #474). " +
                "Actual body slice: '${body.lines().firstOrNull { it.contains("timeoutSec") }}'"
        )
        // Triangulation: the old value must be gone. If 30 is still present in
        // the timeout context the bug is not fixed.
        assertFalse(
            body.contains("\$timeoutSec = 30"),
            "helper must NOT still declare 30s timeout (regression from v4.6.0)"
        )
    }

    // ═══════ Fix 2: narrow process filter ═══════

    @Test
    fun `helper script narrows process filter to launcher exe + bundled JVM`() {
        val body = helperScript.readText()
        // The new filter must compute the launcher's basename + the bundled JVM
        // path so the Where-Object only matches THOSE two processes, not any
        // java.exe under InstallDir (bug #474).
        assertTrue(
            body.contains("\$launcherName"),
            "filter must derive launcher basename from \$AppExe (regression check)"
        )
        assertTrue(
            body.contains("\$bundledJvmPath"),
            "filter must reference the bundled runtime\\bin\\java.exe path explicitly"
        )
        assertTrue(
            body.contains("runtime\\bin\\java.exe"),
            "bundledJvmPath must point at the bundle's own JVM, not any java.exe"
        )
    }

    @Test
    fun `helper script filter no longer uses the broad InstallDir-prefix match`() {
        val body = helperScript.readText()
        // Triangulation: the old broad filter (matched ANY process under
        // InstallDir — including unrelated java.exe spawned from other apps
        // that happen to share the install dir's drive) must be gone.
        // We assert on the specific old expression substring, not the words
        // "Path" or "StartsWith" individually (those may legitimately appear
        // elsewhere if other helper logic uses them).
        assertFalse(
            body.contains("\$_.Path.StartsWith(\$InstallDir, [System.StringComparison]::OrdinalIgnoreCase)"),
            "helper must NOT keep the old broad InstallDir-prefix filter (bug #474)"
        )
    }

    // ═══════ Fix 4: diagnostic log line before exit 1 ═══════

    @Test
    fun `helper script logs surviving process names and PIDs before timing out`() {
        val body = helperScript.readText()
        // When the timeout elapses, the user opens last-update.log and must see
        // WHICH processes refused to die. A bare "App did not exit. Aborting."
        // is useless for diagnosis.
        assertTrue(
            body.contains("Processes still alive"),
            "helper must log the diagnostic phrase 'Processes still alive' before exit 1 " +
                "(bug #474 mitigation — gives the user evidence to attach to a report)"
        )
        // Triangulation: the PID must appear in the formatting string so users
        // can correlate with Task Manager. We accept either `PID ' + $_.Id` or
        // similar concatenations — the substring `PID ` is the signal.
        assertTrue(
            body.contains("PID "),
            "diagnostic log line must include 'PID ' so users can identify the offender"
        )
    }

    // ═══════ Sanity: the script still does the three things the spec calls for ═══════

    @Test
    fun `helper script still performs wait + copy + relaunch (regression guard)`() {
        // This is the safety net: the bigger refactor mustn't have removed any
        // of the three essential operations. Same pattern as AutoUpdaterElevationTest
        // but anchored here so a future tweak to the timeout/filter doesn't
        // accidentally delete the actual update logic.
        val body = helperScript.readText()
        assertTrue(body.contains("Get-Process"), "helper must still wait for processes via Get-Process")
        assertTrue(body.contains("Copy-Item"), "helper must still copy the new JAR over the old one")
        assertTrue(body.contains("Start-Process"), "helper must still relaunch the app exe")
    }
}
