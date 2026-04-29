package com.gameperf.desktop.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the v4.3.8 UAC self-elevation flow in [AutoUpdater].
 *
 * The elevation path runs only on Windows installs in protected directories
 * (Program Files, ProgramData, etc.). On non-Windows or user-writable installs
 * the existing direct-write path is used unchanged.
 *
 * These tests exercise the **planning** side of elevation (helper script
 * generation, command construction, return values) without actually spawning
 * an elevated process — that requires a real Windows host with UAC consent
 * and is impractical to automate. The smoke test for the full UAC handshake
 * is documented in `CHANGELOG.md` v4.3.8 for manual QA.
 *
 * Style mirrors `AutoUpdaterDetectionTest`: temp dir + no mocking framework.
 */
class AutoUpdaterElevationTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("autoupdater-elevation-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    // ═══════ planElevatedUpdate — happy path ═══════

    @Test
    fun `planElevatedUpdate returns PendingExit and writes a non-empty PowerShell helper script`() {
        // Build a fake Windows-bundle install layout in tempDir:
        //   <tempDir>/GamePerf/app/android-game-perf-tool-desktop-4.3.7-fake.jar
        //   <tempDir>/GamePerf/GamePerf.exe
        val installRoot = File(tempDir, "GamePerf").apply { mkdirs() }
        val appDir = File(installRoot, "app").apply { mkdirs() }
        val oldJar = File(appDir, "android-game-perf-tool-desktop-4.3.7-fake.jar")
            .apply { writeBytes(ByteArray(20)) }
        val appExe = File(installRoot, "GamePerf.exe").apply { writeBytes(ByteArray(4)) }
        // Pretend we already downloaded the new JAR somewhere in tempDir.
        val newJar = File(tempDir, "android-game-perf-tool-desktop-4.3.8-fake.jar")
            .apply { writeBytes(ByteArray(60_000_000)) } // > MIN_UBER_JAR_BYTES
        val helperDir = File(tempDir, "elevated-helper").apply { mkdirs() }

        val result = AutoUpdater.planElevatedUpdate(
            newJar = newJar,
            oldJar = oldJar,
            installDir = installRoot,
            appExe = appExe,
            helperDir = helperDir
        )

        // The plan must return a "pending exit" signal so the caller knows to
        // shut the app down so the elevated helper can finish replacing the JAR.
        assertTrue(result.success, "plan must succeed when all inputs are valid")
        assertTrue(
            result.pendingElevatedExit,
            "elevated path MUST set pendingElevatedExit so the caller exits the app"
        )
        // Direct-write path flags should NOT be set on the elevated branch.
        assertFalse(
            result.needsManualRestart,
            "elevated path must not be confused with dev-mode manual restart"
        )

        // The helper script must exist on disk so PowerShell can read it via -File.
        val helperScript = File(helperDir, "update-helper.ps1")
        assertTrue(helperScript.exists(), "helper script file must be written to helperDir")
        val script = helperScript.readText()
        assertTrue(script.length > 100, "helper script must contain real PowerShell, not a stub")

        // Sanity-check: the script must accept the exact parameter names the launcher passes.
        assertContains(script, "param(", message = "helper script must declare parameters")
        assertContains(script, "OldJar", message = "helper must accept -OldJar")
        assertContains(script, "NewJar", message = "helper must accept -NewJar")
        assertContains(script, "InstallDir", message = "helper must accept -InstallDir")
        assertContains(script, "AppExe", message = "helper must accept -AppExe")
        assertContains(script, "LogPath", message = "helper must accept -LogPath")

        // The script must do the three things the spec calls for: wait for app exit,
        // copy the JAR, and relaunch the .exe.
        assertContains(script, "Get-Process", message = "helper must wait for the app to exit")
        assertContains(script, "Copy-Item", message = "helper must overwrite the JAR")
        assertContains(script, "Start-Process", message = "helper must relaunch the app")
    }

    @Test
    fun `planElevatedUpdate writes the same helper script regardless of input paths`() {
        // The helper script body is a constant template — the per-update paths are
        // passed as PowerShell parameters at launch time, NOT baked into the script.
        // This test triangulates the previous one: even with completely different
        // inputs, the on-disk script must be identical (proving the template is
        // genuinely constant, not generated from input paths).
        val installRoot1 = File(tempDir, "InstallA").apply { mkdirs() }
        val appDir1 = File(installRoot1, "app").apply { mkdirs() }
        val oldJar1 = File(appDir1, "old-A.jar").apply { writeBytes(ByteArray(20)) }
        val exe1 = File(installRoot1, "A.exe").apply { writeBytes(ByteArray(4)) }
        val newJar1 = File(tempDir, "new-A.jar").apply { writeBytes(ByteArray(60_000_000)) }
        val helperDir1 = File(tempDir, "helper-A").apply { mkdirs() }

        val installRoot2 = File(tempDir, "InstallB").apply { mkdirs() }
        val appDir2 = File(installRoot2, "app").apply { mkdirs() }
        val oldJar2 = File(appDir2, "old-B.jar").apply { writeBytes(ByteArray(20)) }
        val exe2 = File(installRoot2, "B.exe").apply { writeBytes(ByteArray(4)) }
        val newJar2 = File(tempDir, "new-B.jar").apply { writeBytes(ByteArray(60_000_000)) }
        val helperDir2 = File(tempDir, "helper-B").apply { mkdirs() }

        AutoUpdater.planElevatedUpdate(newJar1, oldJar1, installRoot1, exe1, helperDir1)
        AutoUpdater.planElevatedUpdate(newJar2, oldJar2, installRoot2, exe2, helperDir2)

        val script1 = File(helperDir1, "update-helper.ps1").readText()
        val script2 = File(helperDir2, "update-helper.ps1").readText()
        assertEquals(
            script1,
            script2,
            "helper script must be a constant template — paths are passed as PS arguments at launch"
        )
        // And the constant must NOT contain any of the per-call paths (this is the real
        // assertion that catches the "oops we baked the path in" mistake).
        assertFalse(script1.contains(oldJar1.absolutePath), "old jar path must NOT appear in template")
        assertFalse(script1.contains(newJar1.absolutePath), "new jar path must NOT appear in template")
        assertFalse(script1.contains(exe1.absolutePath), "app exe path must NOT appear in template")
    }

    @Test
    fun `planElevatedUpdate fails cleanly when the new JAR is missing`() {
        val installRoot = File(tempDir, "GamePerf").apply { mkdirs() }
        val appDir = File(installRoot, "app").apply { mkdirs() }
        val oldJar = File(appDir, "old.jar").apply { writeBytes(ByteArray(20)) }
        val appExe = File(installRoot, "GamePerf.exe").apply { writeBytes(ByteArray(4)) }
        val helperDir = File(tempDir, "helper").apply { mkdirs() }
        // newJar deliberately not created.
        val newJar = File(tempDir, "missing-new.jar")

        val result = AutoUpdater.planElevatedUpdate(
            newJar = newJar,
            oldJar = oldJar,
            installDir = installRoot,
            appExe = appExe,
            helperDir = helperDir
        )

        assertFalse(result.success, "missing new JAR must yield success=false")
        assertFalse(result.pendingElevatedExit, "must not signal pending exit on failure")
        assertNotNull(result.message)
        assertTrue(result.message.isNotBlank(), "must provide a user-facing reason")
    }

    @Test
    fun `planElevatedUpdate fails cleanly when the new JAR is too small to be an uber-JAR`() {
        // Same uber-JAR safeguard the bundle paths already enforce — replacing a bundle's
        // fat JAR with a thin one would crash the relaunch with NoClassDefFoundError on Skiko.
        val installRoot = File(tempDir, "GamePerf").apply { mkdirs() }
        val appDir = File(installRoot, "app").apply { mkdirs() }
        val oldJar = File(appDir, "old.jar").apply { writeBytes(ByteArray(20)) }
        val appExe = File(installRoot, "GamePerf.exe").apply { writeBytes(ByteArray(4)) }
        val helperDir = File(tempDir, "helper").apply { mkdirs() }
        val newJar = File(tempDir, "thin.jar").apply { writeBytes(ByteArray(1024)) }

        val result = AutoUpdater.planElevatedUpdate(
            newJar = newJar,
            oldJar = oldJar,
            installDir = installRoot,
            appExe = appExe,
            helperDir = helperDir
        )

        assertFalse(result.success, "thin JAR must be rejected exactly like other bundle paths")
        assertFalse(result.pendingElevatedExit)
    }

    @Test
    fun `buildElevatedLaunchArgs constructs a powershell Start-Process RunAs invocation`() {
        // Independent of any filesystem setup — pure command-line plumbing.
        val helper = File(tempDir, "update-helper.ps1").apply { writeText("# stub") }
        val oldJar = File(tempDir, "old.jar").apply { writeBytes(ByteArray(20)) }
        val newJar = File(tempDir, "new.jar").apply { writeBytes(ByteArray(60_000_000)) }
        val installDir = File(tempDir, "Install").apply { mkdirs() }
        val appExe = File(tempDir, "GamePerf.exe").apply { writeBytes(ByteArray(4)) }
        val logPath = File(tempDir, "last-update.log")

        val args = AutoUpdater.buildElevatedLaunchArgs(
            helperScript = helper,
            oldJar = oldJar,
            newJar = newJar,
            installDir = installDir,
            appExe = appExe,
            logPath = logPath,
        )

        // The first arg must invoke powershell.exe — anything else means we lost RunAs.
        assertEquals("powershell.exe", args.first(), "must invoke powershell.exe")

        // The command body must request elevation (-Verb RunAs) and execute the helper
        // with all the path parameters the helper script declares.
        val joined = args.joinToString("\u0001")
        assertContains(joined, "-Verb", message = "must request UAC elevation")
        assertContains(joined, "RunAs", message = "RunAs is what triggers the consent dialog")
        assertContains(joined, helper.absolutePath, message = "must reference the helper script")
        assertContains(joined, oldJar.absolutePath, message = "must pass -OldJar value")
        assertContains(joined, newJar.absolutePath, message = "must pass -NewJar value")
        assertContains(joined, installDir.absolutePath, message = "must pass -InstallDir value")
        assertContains(joined, appExe.absolutePath, message = "must pass -AppExe value")
        assertContains(joined, logPath.absolutePath, message = "must pass -LogPath value")
        assertContains(
            joined,
            "ExecutionPolicy",
            message = "Bypass execution policy so unsigned helper runs"
        )
    }

    @Test
    fun `buildElevatedLaunchArgs is independent across different inputs (triangulation)`() {
        // Triangulate buildElevatedLaunchArgs to prove it is not returning a constant.
        val helper = File(tempDir, "h.ps1").apply { writeText("") }
        val installDir = File(tempDir, "Inst").apply { mkdirs() }
        val argsA = AutoUpdater.buildElevatedLaunchArgs(
            helperScript = helper,
            oldJar = File(tempDir, "alpha-old.jar"),
            newJar = File(tempDir, "alpha-new.jar"),
            installDir = installDir,
            appExe = File(tempDir, "Alpha.exe"),
            logPath = File(tempDir, "alpha.log"),
        )
        val argsB = AutoUpdater.buildElevatedLaunchArgs(
            helperScript = helper,
            oldJar = File(tempDir, "beta-old.jar"),
            newJar = File(tempDir, "beta-new.jar"),
            installDir = installDir,
            appExe = File(tempDir, "Beta.exe"),
            logPath = File(tempDir, "beta.log"),
        )
        val joinedA = argsA.joinToString("\u0001")
        val joinedB = argsB.joinToString("\u0001")
        assertContains(joinedA, "alpha-old.jar")
        assertContains(joinedB, "beta-old.jar")
        assertFalse(joinedA.contains("beta-old.jar"), "args for A must not leak B's paths")
        assertFalse(joinedB.contains("alpha-old.jar"), "args for B must not leak A's paths")
    }

    // ═══════ UpdateResult shape ═══════

    @Test
    fun `UpdateResult exposes pendingElevatedExit defaulting to false for backward compatibility`() {
        // Existing call sites that build UpdateResult without naming the new field must
        // continue to compile and behave as before — the new flag must default to false.
        val legacy = AutoUpdater.UpdateResult(success = true)
        assertFalse(
            legacy.pendingElevatedExit,
            "default must be false so the macOS / Linux / fat-jar code paths are unaffected"
        )
    }
}
