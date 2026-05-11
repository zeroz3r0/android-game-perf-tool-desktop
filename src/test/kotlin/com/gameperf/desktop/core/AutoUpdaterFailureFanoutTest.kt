package com.gameperf.desktop.core

import com.gameperf.desktop.core.update.UpdateOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v4.4.1 — Tests for [AutoUpdater] failure fan-out helpers.
 *
 * Spec auto-updater REQ "Update failure surface area" (scenarios E1..E4):
 * every terminal failure mode MUST produce an [com.gameperf.desktop.core.update.UpdateOutcome]
 * carried via [AutoUpdater.UpdateResult.outcome] so [com.gameperf.desktop.viewmodel.UpdateDelegate]
 * can fan failures out to `updateFallback` StateFlow + `UpdateHistoryStore.append`.
 *
 * The helpers are pure (no I/O, no coroutines) and are tested in isolation here.
 * Integration with the real download / spawn paths is verified by the existing
 * end-to-end tests + manual Windows QA documented in CHANGELOG v4.4.1.
 */
class AutoUpdaterFailureFanoutTest {

    @Test
    fun `buildDownloadFailureResult emits FailedDownload with errorMessage and httpStatus`() {
        // Spec scenario E1: HTTP 404 on the asset URL.
        val result = AutoUpdater.buildDownloadFailureResult(
            errorMessage = "HTTP 404 desde GitHub al descargar el JAR",
            httpStatus = 404,
        )
        assertFalse(result.success, "download failure must not be success")
        assertEquals("HTTP 404 desde GitHub al descargar el JAR", result.message)
        val outcome = assertNotNull(result.outcome, "outcome must be populated for E1")
        assertTrue(outcome is UpdateOutcome.FailedDownload, "must be FailedDownload variant")
        assertEquals(404, outcome.httpStatus)
        assertEquals("HTTP 404 desde GitHub al descargar el JAR", outcome.message)
    }

    @Test
    fun `buildDownloadFailureResult tolerates null httpStatus for connection-level failures`() {
        // Triangulation: connection refused / DNS failure — no HTTP exchange so no status.
        val result = AutoUpdater.buildDownloadFailureResult(
            errorMessage = "ConnectException: connection refused",
        )
        val outcome = assertNotNull(result.outcome)
        assertTrue(outcome is UpdateOutcome.FailedDownload)
        assertNull(outcome.httpStatus, "connection-level failures have no HTTP status")
        assertEquals("ConnectException: connection refused", outcome.message)
    }

    @Test
    fun `buildWatchdogTimeoutResult emits FailedWatchdogTimeout outcome`() {
        // Spec scenario E2 / U2: watchdog timeout returns to caller, NO exitProcess.
        val result = AutoUpdater.buildWatchdogTimeoutResult(
            message = "Watchdog timeout — helper canary never observed",
        )
        assertFalse(result.success, "timeout is a failure, not success")
        assertFalse(
            result.pendingElevatedExit,
            "watchdog timeout must NOT signal pending exit (spec U2)"
        )
        val outcome = assertNotNull(result.outcome)
        assertEquals(
            UpdateOutcome.FailedWatchdogTimeout,
            outcome,
            "outcome must be the singleton FailedWatchdogTimeout"
        )
    }

    @Test
    fun `buildUnknownFailureResult emits FailedUnknown carrying the diagnostic message`() {
        // Spec scenario U3: PowerShell spawn IOException (PS missing, exec bit, etc.).
        val result = AutoUpdater.buildUnknownFailureResult(
            message = "IOException: powershell.exe not found on PATH",
        )
        assertFalse(result.success)
        val outcome = assertNotNull(result.outcome)
        assertTrue(outcome is UpdateOutcome.FailedUnknown)
        assertEquals(
            "IOException: powershell.exe not found on PATH",
            outcome.message,
            "message must roundtrip through outcome for diagnostic capture"
        )
    }

    @Test
    fun `buildUnknownFailureResult also covers the no-Windows-JAR-asset case`() {
        // Triangulation: same helper used for "asset selection: no Windows JAR" (spec error matrix row 2).
        val result = AutoUpdater.buildUnknownFailureResult(
            message = "no JAR asset",
        )
        val outcome = assertNotNull(result.outcome)
        assertTrue(outcome is UpdateOutcome.FailedUnknown)
        assertEquals("no JAR asset", outcome.message)
    }

    @Test
    fun `buildElevatedSuccessResult emits Success outcome and pendingElevatedExit`() {
        // Spec scenario U1 / E4: happy path — canary observed within timeout.
        val result = AutoUpdater.buildElevatedSuccessResult(
            updatedJarPath = "C:\\Program Files\\GamePerf\\app\\GamePerf.jar",
            message = "Update applied; helper has taken over.",
        )
        assertTrue(result.success, "happy path is success")
        assertTrue(
            result.pendingElevatedExit,
            "elevated path armed → caller must exit so helper can finish"
        )
        assertEquals(
            "C:\\Program Files\\GamePerf\\app\\GamePerf.jar",
            result.updatedJarPath
        )
        assertEquals(
            UpdateOutcome.Success,
            result.outcome,
            "outcome must be the singleton Success"
        )
    }

    @Test
    fun `UpdateResult outcome defaults to null for backward compatibility`() {
        // The pre-v4.4.1 call sites construct UpdateResult without naming `outcome`.
        // Default MUST be null so existing tests + macOS / Linux / fat-jar code
        // paths continue to compile and behave unchanged.
        val legacy = AutoUpdater.UpdateResult(success = true)
        assertNull(
            legacy.outcome,
            "outcome must default to null so v4.3.8 callers are unaffected"
        )
    }
}
