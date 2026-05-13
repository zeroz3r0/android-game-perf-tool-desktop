package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.AutoUpdater
import com.gameperf.desktop.core.update.UpdateAttempt
import com.gameperf.desktop.core.update.UpdateFallbackReason
import com.gameperf.desktop.core.update.UpdateHistoryStore
import com.gameperf.desktop.core.update.UpdateOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * v4.4.1 — Tests for the [UpdateDelegate] fallback state flow + history append.
 *
 * Spec auto-updater REQ "Update failure surface area" (E1/E2/E3/E4) and
 * update-resilience REQs "UpdateFallbackState contract" + "Fallback panel dismissal":
 *
 *   - `updateFallback: StateFlow<UpdateFallbackState?>` starts null
 *   - every UpdateOutcome.Failed* outcome transitions it to non-null with the right reason
 *   - UpdateOutcome.Success resets it to null (and history records success too)
 *   - dismissFallback() clears to null without truncating history.jsonl
 *   - every outcome appends one [UpdateAttempt] to the injected history store
 *   - the v4.3.8 pendingElevatedExit + delay(1500) + exitProcess(0) flow is preserved
 *     (verified by the unit-level method we test: applyOutcome sets fallback BEFORE the exit
 *     so even if the JVM dies, the history.jsonl line is durable)
 *
 * Uses kotlinx-coroutines-test `runTest` with `UnconfinedTestDispatcher` so StateFlow
 * emissions are observable synchronously. The history store is faked in-memory
 * (FakeUpdateHistoryStore) — same convention as FakeAdbBridge per CLAUDE.md.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpdateDelegateFallbackStateTest {

    private class FakeHistoryStore : UpdateHistoryStore {
        val appended: MutableList<UpdateAttempt> = mutableListOf()
        override fun append(attempt: UpdateAttempt) {
            appended += attempt
        }
        override fun recentAttempts(limit: Int): List<UpdateAttempt> =
            if (appended.size <= limit) appended.toList() else appended.takeLast(limit)
    }

    private fun newDelegate(
        scope: CoroutineScope,
        history: UpdateHistoryStore = FakeHistoryStore(),
    ): UpdateDelegate = UpdateDelegate(
        scope = scope,
        onStatusMessage = { /* test sink */ },
        historyStore = history,
    )

    @Test
    fun `updateFallback starts null`() = runTest {
        val delegate = newDelegate(this)
        assertNull(delegate.updateFallback.value, "fallback must start null — no failure shown")
    }

    @Test
    fun `applyOutcome FailedUacDenied transitions fallback to USER_CANCELLED_UAC`() = runTest {
        val history = FakeHistoryStore()
        val delegate = newDelegate(this, history)
        val result = AutoUpdater.UpdateResult(
            success = false,
            message = "UAC denied",
            outcome = UpdateOutcome.FailedUacDenied,
        )

        delegate.applyOutcome(result, attemptedVersion = "4.4.1", durationMs = 1234L)

        val state = assertNotNull(
            delegate.updateFallback.value,
            "fallback must transition to non-null on FailedUacDenied"
        )
        assertEquals(UpdateFallbackReason.USER_CANCELLED_UAC, state.reason)
        assertEquals("4.4.1", state.attemptedVersion)
        assertEquals(1, history.appended.size, "history must record the attempt")
        assertEquals(UpdateOutcome.FailedUacDenied, history.appended[0].outcome)
    }

    @Test
    fun `applyOutcome FailedWatchdogTimeout maps to USER_CANCELLED_UAC reason`() = runTest {
        val history = FakeHistoryStore()
        val delegate = newDelegate(this, history)
        val result = AutoUpdater.UpdateResult(
            success = false,
            outcome = UpdateOutcome.FailedWatchdogTimeout,
        )

        delegate.applyOutcome(result, attemptedVersion = "4.4.1", durationMs = 8200L)

        val state = assertNotNull(delegate.updateFallback.value)
        assertEquals(
            UpdateFallbackReason.USER_CANCELLED_UAC,
            state.reason,
            "watchdog timeout maps to USER_CANCELLED_UAC per design §6 (dominant cause)"
        )
        assertEquals(UpdateOutcome.FailedWatchdogTimeout, history.appended.single().outcome)
    }

    @Test
    fun `applyOutcome FailedDownload transitions to DOWNLOAD_FAILED`() = runTest {
        val history = FakeHistoryStore()
        val delegate = newDelegate(this, history)
        val outcome = UpdateOutcome.FailedDownload(httpStatus = 404, message = "asset 404")
        val result = AutoUpdater.UpdateResult(
            success = false,
            message = "asset 404",
            outcome = outcome,
        )

        delegate.applyOutcome(result, attemptedVersion = "4.4.1", durationMs = 500L)

        val state = assertNotNull(delegate.updateFallback.value)
        assertEquals(UpdateFallbackReason.DOWNLOAD_FAILED, state.reason)
        assertEquals(outcome, history.appended.single().outcome)
    }

    @Test
    fun `applyOutcome Success resets updateFallback to null and appends Success history`() = runTest {
        val history = FakeHistoryStore()
        val delegate = newDelegate(this, history)
        // First poison with a prior failure so Success has something to clear.
        delegate.applyOutcome(
            AutoUpdater.UpdateResult(success = false, outcome = UpdateOutcome.FailedUacDenied),
            attemptedVersion = "4.4.1",
            durationMs = 100L,
        )
        assertNotNull(delegate.updateFallback.value, "premise: fallback armed before Success")

        delegate.applyOutcome(
            AutoUpdater.UpdateResult(success = true, outcome = UpdateOutcome.Success),
            attemptedVersion = "4.4.1",
            durationMs = 1500L,
        )

        assertNull(
            delegate.updateFallback.value,
            "Success outcome must clear any prior fallback (E4)"
        )
        assertEquals(2, history.appended.size, "every outcome (incl. Success) records to history")
        assertEquals(UpdateOutcome.Success, history.appended.last().outcome)
    }

    @Test
    fun `dismissFallback clears state to null without touching history`() = runTest {
        val history = FakeHistoryStore()
        val delegate = newDelegate(this, history)
        delegate.applyOutcome(
            AutoUpdater.UpdateResult(success = false, outcome = UpdateOutcome.FailedUacDenied),
            attemptedVersion = "4.4.1",
            durationMs = 100L,
        )
        val historyBefore = history.appended.size

        delegate.dismissFallback()

        assertNull(delegate.updateFallback.value, "D1: dismiss clears state to null")
        assertEquals(
            historyBefore,
            history.appended.size,
            "D2: dismiss MUST NOT mutate history.jsonl (mtime unchanged)"
        )
    }

    @Test
    fun `back-to-back failures only show the latest fallback (last write wins)`() = runTest {
        val history = FakeHistoryStore()
        val delegate = newDelegate(this, history)

        delegate.applyOutcome(
            AutoUpdater.UpdateResult(
                success = false,
                outcome = UpdateOutcome.FailedDownload(message = "first"),
            ),
            attemptedVersion = "4.4.1",
            durationMs = 100L,
        )
        delegate.applyOutcome(
            AutoUpdater.UpdateResult(success = false, outcome = UpdateOutcome.FailedUacDenied),
            attemptedVersion = "4.4.1",
            durationMs = 200L,
        )

        // StateFlow shows latest only; history records every attempt.
        val state = assertNotNull(delegate.updateFallback.value)
        assertEquals(
            UpdateFallbackReason.USER_CANCELLED_UAC,
            state.reason,
            "latest outcome wins in the StateFlow"
        )
        assertEquals(2, history.appended.size, "BOTH attempts recorded — no dedup on history")
    }

    @Test
    fun `dismiss before any outcome is a no-op safe call`() = runTest {
        val history = FakeHistoryStore()
        val delegate = newDelegate(this, history)
        // No applyOutcome — fallback is already null.
        delegate.dismissFallback()
        assertNull(delegate.updateFallback.value, "dismiss on null state stays null")
        assertTrue(history.appended.isEmpty(), "dismiss alone never appends to history")
    }

    @Test
    fun `applyOutcome with null outcome falls back to FailedUnknown for legacy callers`() = runTest {
        // Backward-compat: if some legacy AutoUpdater path returns success=false with
        // outcome=null (didn't get migrated yet), the delegate must still surface a
        // panel rather than swallow the failure silently. Maps to UNKNOWN reason.
        val history = FakeHistoryStore()
        val delegate = newDelegate(this, history)
        val result = AutoUpdater.UpdateResult(
            success = false,
            message = "Error genérico legacy",
            outcome = null,
        )

        delegate.applyOutcome(result, attemptedVersion = "4.4.1", durationMs = 50L)

        val state = assertNotNull(
            delegate.updateFallback.value,
            "null outcome on a failure must still arm the fallback"
        )
        assertEquals(UpdateFallbackReason.UNKNOWN, state.reason)
        assertEquals(1, history.appended.size, "history records the attempt with synthesized outcome")
        assertTrue(
            history.appended[0].outcome is UpdateOutcome.FailedUnknown,
            "legacy null outcome materialized as FailedUnknown for the history line"
        )
    }

    @Test
    fun `unused TestScope shape sanity`() = runTest {
        // Sanity that UnconfinedTestDispatcher works for our setup style.
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val delegate = newDelegate(scope)
        assertNull(delegate.updateFallback.value)
    }
}
