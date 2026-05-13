package com.gameperf.desktop.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * RED-first tests for [UpdateFallbackState] + [UpdateFallbackReason].
 *
 * UpdateFallbackReason is a UI-facing enum mapped from [UpdateOutcome] via
 * [UpdateFallbackState.from] per design §6 mapping table:
 *   - Success                  -> N/A (factory not called)
 *   - FailedUacDenied          -> USER_CANCELLED_UAC
 *   - FailedWatchdogTimeout    -> USER_CANCELLED_UAC (dominant cause per design §6)
 *   - FailedDownload           -> DOWNLOAD_FAILED
 *   - FailedHelperCrash        -> HELPER_CRASHED
 *   - FailedUnknown            -> UNKNOWN
 */
class UpdateFallbackStateTest {

    private val attemptedVersion = "4.4.1"

    // ===== Reason mapping per outcome =====

    @Test
    fun `from FailedUacDenied maps to USER_CANCELLED_UAC reason`() {
        val state = UpdateFallbackState.from(
            outcome = UpdateOutcome.FailedUacDenied,
            attemptedVersion = attemptedVersion,
            helperLogTail = null,
        )
        assertEquals(UpdateFallbackReason.USER_CANCELLED_UAC, state.reason)
        assertEquals(attemptedVersion, state.attemptedVersion)
    }

    @Test
    fun `from FailedWatchdogTimeout maps to USER_CANCELLED_UAC per design dominant cause rule`() {
        // Design §6: when watchdog times out and outer PS exit was clean, the dominant
        // cause is UAC denial. Reason field uses USER_CANCELLED_UAC for UI clarity.
        val state = UpdateFallbackState.from(
            outcome = UpdateOutcome.FailedWatchdogTimeout,
            attemptedVersion = attemptedVersion,
            helperLogTail = null,
        )
        assertEquals(UpdateFallbackReason.USER_CANCELLED_UAC, state.reason)
    }

    @Test
    fun `from FailedDownload maps to DOWNLOAD_FAILED reason`() {
        val state = UpdateFallbackState.from(
            outcome = UpdateOutcome.FailedDownload(httpStatus = 404, message = "not found"),
            attemptedVersion = attemptedVersion,
            helperLogTail = null,
        )
        assertEquals(UpdateFallbackReason.DOWNLOAD_FAILED, state.reason)
    }

    @Test
    fun `from FailedHelperCrash maps to HELPER_CRASHED reason`() {
        val state = UpdateFallbackState.from(
            outcome = UpdateOutcome.FailedHelperCrash(exitCode = 1),
            attemptedVersion = attemptedVersion,
            helperLogTail = "[JVM] launching",
        )
        assertEquals(UpdateFallbackReason.HELPER_CRASHED, state.reason)
    }

    @Test
    fun `from FailedUnknown maps to UNKNOWN reason`() {
        val state = UpdateFallbackState.from(
            outcome = UpdateOutcome.FailedUnknown("???"),
            attemptedVersion = attemptedVersion,
            helperLogTail = null,
        )
        assertEquals(UpdateFallbackReason.UNKNOWN, state.reason)
    }

    @Test
    fun `from Success throws because Success has no fallback state`() {
        // Success outcomes never produce a fallback panel, so calling the factory
        // with Success is a programmer error.
        try {
            UpdateFallbackState.from(
                outcome = UpdateOutcome.Success,
                attemptedVersion = attemptedVersion,
                helperLogTail = null,
            )
            error("expected IllegalArgumentException for Success outcome")
        } catch (e: IllegalArgumentException) {
            // expected
            assertEquals(true, e.message?.contains("Success") ?: false)
        }
    }

    // ===== Field semantics =====

    @Test
    fun `from preserves attemptedVersion verbatim`() {
        val state = UpdateFallbackState.from(
            outcome = UpdateOutcome.FailedUacDenied,
            attemptedVersion = "9.9.9",
            helperLogTail = null,
        )
        assertEquals("9.9.9", state.attemptedVersion)
    }

    @Test
    fun `from sets non-empty downloadUrl pointing at GitHub releases`() {
        val state = UpdateFallbackState.from(
            outcome = UpdateOutcome.FailedUacDenied,
            attemptedVersion = "4.4.1",
            helperLogTail = null,
        )
        assertEquals(true, state.downloadUrl.isNotEmpty())
        assertEquals(true, state.downloadUrl.contains("4.4.1"))
        assertEquals(true, state.downloadUrl.contains("github.com"))
    }

    @Test
    fun `from sets non-empty installGuideUrl`() {
        val state = UpdateFallbackState.from(
            outcome = UpdateOutcome.FailedUacDenied,
            attemptedVersion = "4.4.1",
            helperLogTail = null,
        )
        assertEquals(true, state.installGuideUrl.isNotEmpty())
    }

    @Test
    fun `from tolerates null helperLogTail`() {
        val state = UpdateFallbackState.from(
            outcome = UpdateOutcome.FailedUacDenied,
            attemptedVersion = "4.4.1",
            helperLogTail = null,
        )
        assertNull(state.diagnosticTail)
    }

    @Test
    fun `from preserves non-null helperLogTail as diagnosticTail`() {
        val tail = "[JVM 1700000000000] outer PS launching\n===== UAC update helper started ====="
        val state = UpdateFallbackState.from(
            outcome = UpdateOutcome.FailedHelperCrash(exitCode = 1),
            attemptedVersion = "4.4.1",
            helperLogTail = tail,
        )
        assertEquals(tail, state.diagnosticTail)
    }

    // ===== Copy semantics =====

    @Test
    fun `data class copy with overridden reason produces a different instance`() {
        val a = UpdateFallbackState.from(
            outcome = UpdateOutcome.FailedUacDenied,
            attemptedVersion = "4.4.1",
            helperLogTail = null,
        )
        val b = a.copy(reason = UpdateFallbackReason.UNKNOWN)
        assertNotEquals(a, b)
        assertEquals(UpdateFallbackReason.UNKNOWN, b.reason)
        assertEquals(a.attemptedVersion, b.attemptedVersion)
    }

    @Test
    fun `data class copy without overrides produces an equal instance`() {
        val a = UpdateFallbackState.from(
            outcome = UpdateOutcome.FailedDownload(500, "boom"),
            attemptedVersion = "4.4.1",
            helperLogTail = "tail",
        )
        val b = a.copy()
        assertEquals(a, b)
    }
}
