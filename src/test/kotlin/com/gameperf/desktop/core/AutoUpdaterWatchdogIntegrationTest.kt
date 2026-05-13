package com.gameperf.desktop.core

import com.gameperf.desktop.core.update.UpdateOutcome
import com.gameperf.desktop.core.update.WatchdogResult
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v4.4.1 — Tests for the post-spawn watchdog integration in [AutoUpdater].
 *
 * Spec auto-updater MODIFIED REQ "Elevated update spawn integrates with watchdog"
 * (scenarios U1 / U2 / U3) and update-resilience REQ "Pre-spawn JVM breadcrumb"
 * (scenarios B1 / B2):
 *
 *   U1: spawn OK + canary < 8 s → Success + pendingElevatedExit
 *   U2: spawn OK + watchdog timeout → FailedWatchdogTimeout, NO exitProcess
 *   U3: spawn IOException → FailedUnknown, history line appended (history wiring lives in UpdateDelegate)
 *   B1: breadcrumb line written to log BEFORE the helper spawn
 *   W4: watchdog disabled (timeout = 0) → preserves legacy 1500ms exit path
 *
 * The integration helper is tested with INJECTED dependencies (spawn closure +
 * watchdog closure + breadcrumb writer) so we never need to spawn a real
 * elevated PowerShell. The full UAC handshake remains a manual QA item per
 * CHANGELOG v4.3.8 + v4.4.1.
 */
class AutoUpdaterWatchdogIntegrationTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("autoupdater-watchdog-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    private fun fakeBundle(): WatchdogIntegrationFixture {
        val installRoot = File(tempDir, "GamePerf").apply { mkdirs() }
        val appDir = File(installRoot, "app").apply { mkdirs() }
        val oldJar = File(appDir, "old.jar").apply { writeBytes(ByteArray(20)) }
        val appExe = File(installRoot, "GamePerf.exe").apply { writeBytes(ByteArray(4)) }
        val newJar = File(tempDir, "new.jar").apply { writeBytes(ByteArray(60_000_000)) }
        val helperDir = File(tempDir, "helper").apply { mkdirs() }
        val logPath = File(tempDir, "last-update.log")
        return WatchdogIntegrationFixture(installRoot, oldJar, appExe, newJar, helperDir, logPath)
    }

    @Test
    fun `runWatchdogAndBuildResult writes breadcrumb BEFORE invoking spawn (spec B1)`() {
        // The breadcrumb MUST appear on disk before Start-Process — the empty-log
        // signal "spawn never happened" only works if we write before spawning.
        val fx = fakeBundle()
        val events = mutableListOf<String>()
        val result = AutoUpdater.runWatchdogAndBuildResult(
            oldJar = fx.oldJar,
            installDir = fx.installRoot,
            appExe = fx.appExe,
            logPath = fx.logPath,
            writeBreadcrumb = { events += "breadcrumb" },
            spawn = { events += "spawn"; true },
            awaitCanary = { events += "watchdog"; WatchdogResult.CanaryFound },
        )
        assertEquals(
            listOf("breadcrumb", "spawn", "watchdog"),
            events,
            "ordering MUST be breadcrumb → spawn → watchdog (spec B1)"
        )
        assertTrue(result.success, "happy path: canary observed → success")
    }

    @Test
    fun `runWatchdogAndBuildResult returns Success + pendingElevatedExit when canary observed (U1)`() {
        // Spec scenario U1: canary appears within timeout.
        val fx = fakeBundle()
        val result = AutoUpdater.runWatchdogAndBuildResult(
            oldJar = fx.oldJar,
            installDir = fx.installRoot,
            appExe = fx.appExe,
            logPath = fx.logPath,
            writeBreadcrumb = { /* noop */ },
            spawn = { true },
            awaitCanary = { WatchdogResult.CanaryFound },
        )
        assertTrue(result.success)
        assertTrue(
            result.pendingElevatedExit,
            "U1: canary found → caller must exit so helper finishes"
        )
        assertEquals(
            UpdateOutcome.Success,
            result.outcome,
            "outcome must be Success on canary observed"
        )
        assertEquals(fx.oldJar.absolutePath, result.updatedJarPath)
    }

    @Test
    fun `runWatchdogAndBuildResult returns FailedWatchdogTimeout WITHOUT pendingExit (U2)`() {
        // Spec scenario U2: canary never appears — JVM stays alive, panel renders.
        val fx = fakeBundle()
        val result = AutoUpdater.runWatchdogAndBuildResult(
            oldJar = fx.oldJar,
            installDir = fx.installRoot,
            appExe = fx.appExe,
            logPath = fx.logPath,
            writeBreadcrumb = { /* noop */ },
            spawn = { true },
            awaitCanary = { WatchdogResult.TimedOut },
        )
        assertFalse(result.success, "timeout is a failure")
        assertFalse(
            result.pendingElevatedExit,
            "U2: timeout MUST NOT trigger exitProcess (the whole point of the watchdog)"
        )
        val outcome = assertNotNull(result.outcome)
        assertEquals(
            UpdateOutcome.FailedWatchdogTimeout,
            outcome,
            "outcome must be FailedWatchdogTimeout for forensic precision"
        )
    }

    @Test
    fun `runWatchdogAndBuildResult preserves legacy path when watchdog returns Disabled (W4)`() {
        // Spec scenario W4: timeout=0 → Disabled → legacy 1500ms exit retained.
        // From the integration helper's perspective: Disabled behaves like Success
        // (return pendingElevatedExit=true) so UpdateDelegate runs the existing
        // delay(1500) + exitProcess(0) flow unchanged.
        val fx = fakeBundle()
        val result = AutoUpdater.runWatchdogAndBuildResult(
            oldJar = fx.oldJar,
            installDir = fx.installRoot,
            appExe = fx.appExe,
            logPath = fx.logPath,
            writeBreadcrumb = { /* noop */ },
            spawn = { true },
            awaitCanary = { WatchdogResult.Disabled },
        )
        assertTrue(result.success, "Disabled preserves legacy success-path semantics (W4)")
        assertTrue(
            result.pendingElevatedExit,
            "Disabled → legacy exit retained → caller's delay(1500)+exitProcess(0) runs"
        )
        assertEquals(
            UpdateOutcome.Success,
            result.outcome,
            "Disabled is treated as Success outcome (no failure to surface)"
        )
    }

    @Test
    fun `runWatchdogAndBuildResult returns FailedUnknown when spawn returns false (U3)`() {
        // Spec scenario U3: ProcessBuilder.start() throws → spawn closure returns false.
        // Watchdog must NOT be invoked (no helper to wait for).
        val fx = fakeBundle()
        var watchdogCalled = false
        val result = AutoUpdater.runWatchdogAndBuildResult(
            oldJar = fx.oldJar,
            installDir = fx.installRoot,
            appExe = fx.appExe,
            logPath = fx.logPath,
            writeBreadcrumb = { /* noop */ },
            spawn = { false },
            awaitCanary = { watchdogCalled = true; WatchdogResult.CanaryFound },
        )
        assertFalse(watchdogCalled, "watchdog must be skipped when spawn fails")
        assertFalse(result.success)
        assertFalse(result.pendingElevatedExit)
        val outcome = assertNotNull(result.outcome)
        assertTrue(
            outcome is UpdateOutcome.FailedUnknown,
            "spawn failure → FailedUnknown (catch-all per spec error matrix)"
        )
    }

    @Test
    fun `runWatchdogAndBuildResult tolerates breadcrumb writer throwing (spec B3)`() {
        // Spec scenario B3: breadcrumb write fails (read-only log dir, etc.) — the
        // update attempt MUST continue. The helper swallows the breadcrumb error
        // and proceeds to spawn so the user's update isn't blocked by an I/O issue
        // on the diagnostic log.
        val fx = fakeBundle()
        var spawnCalled = false
        val result = AutoUpdater.runWatchdogAndBuildResult(
            oldJar = fx.oldJar,
            installDir = fx.installRoot,
            appExe = fx.appExe,
            logPath = fx.logPath,
            writeBreadcrumb = { error("simulated read-only log") },
            spawn = { spawnCalled = true; true },
            awaitCanary = { WatchdogResult.CanaryFound },
        )
        assertTrue(
            spawnCalled,
            "B3: spawn MUST run even when breadcrumb write throws (don't block update)"
        )
        assertTrue(result.success, "happy-path watchdog still applies after swallowed breadcrumb error")
    }

    @Test
    fun `lastUpdateLogPath returns the canonical helper log path under user home`() {
        // T4.8 / ADR-8: single helper used by both the JVM-side breadcrumb writer
        // and HelperLogWatcher's production wrapper, so they cannot drift apart.
        val path = AutoUpdater.lastUpdateLogPath()
        val asString = path.toString().replace('\\', '/')
        assertTrue(
            asString.endsWith("GamePerf Reports/updates/last-update.log"),
            "canonical relative path must end with GamePerf Reports/updates/last-update.log, was: $asString"
        )
        assertTrue(
            asString.contains(System.getProperty("user.home").replace('\\', '/')),
            "must be anchored at user.home (no drift from the v4.3.8 location)"
        )
    }

    private data class WatchdogIntegrationFixture(
        val installRoot: File,
        val oldJar: File,
        val appExe: File,
        val newJar: File,
        val helperDir: File,
        val logPath: File,
    )
}
