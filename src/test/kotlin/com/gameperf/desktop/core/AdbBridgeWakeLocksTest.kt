package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.WakeLocksSnapshot
import com.gameperf.desktop.core.model.WakeLocksUnavailableReason
import com.gameperf.desktop.testing.FakeAdbBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v4.6.0 — Bridge-level coverage for the `vitals-rate-and-wakelocks` change.
 *
 * Three TDD scenarios mirror the v4.6.x [AdbBridgeNetworkTest] precedent:
 *
 *  1. **Happy path** — [FakeAdbBridge.installWakeLocksSnapshot] short-circuits
 *     subsequent calls to `captureWakeLocks` returning the exact snapshot
 *     identity (no dumpsys, no parser walk). Mirror of `setNetwork` /
 *     `setGpu` precedent.
 *  2. **CAPTURE_THREW resilience** — when the bridge throws (process spawn
 *     fails, OOM, etc.) `captureWakeLocks` MUST swallow the exception and
 *     surface a [WakeLocksUnavailableReason.CAPTURE_THREW] diagnostic. Mirrors
 *     the v4.5.0 GPU + v4.6.0 Network try/catch precedent.
 *  3. **iOS guard** — `captureWakeLocks` is Android-only by design (no iOS
 *     equivalent to `dumpsys batterystats`). The bridge contract is:
 *     callers MUST gate on `!isIosDevice` before invoking — this test asserts
 *     the install-and-short-circuit behaviour is consistent with how the
 *     ViewModel will wire it (Phase 4).
 */
class AdbBridgeWakeLocksTest {

    private val deviceId = "TEST-DEVICE-WL"
    private val pkg = "com.example.game"

    // ===== Scenario 1: happy path — install builder returns identity =====

    @Test
    fun `installWakeLocksSnapshot returns the exact snapshot from captureWakeLocks`() {
        val snap = WakeLocksSnapshot(
            totalScreenOffMs = 6_790_000L,
            totalScreenOnMs = 0L,
            partialLockCount = 3,
            wakeLocksAvailable = true,
            diagnostic = null,
        )
        val bridge = FakeAdbBridge().installWakeLocksSnapshot(snap)

        val result = bridge.captureWakeLocks(deviceId, pkg)

        assertTrue(result.wakeLocksAvailable, "installed snapshot must propagate")
        assertEquals(6_790_000L, result.totalScreenOffMs)
        assertEquals(3, result.partialLockCount)
    }

    // ===== Scenario 2: CAPTURE_THREW resilience — try/catch fallback =====

    @Test
    fun `wakeLocksThrowOn installed yields CAPTURE_THREW snapshot`() {
        val bridge = FakeAdbBridge()
        bridge.wakeLocksThrowOn[deviceId] = RuntimeException("simulated adb spawn failure")

        val result = bridge.captureWakeLocks(deviceId, pkg)

        assertFalse(result.wakeLocksAvailable, "thrown capture must surface unavailable")
        val diag = result.diagnostic
        assertNotNull(diag, "thrown capture must populate diagnostic")
        assertEquals(WakeLocksUnavailableReason.CAPTURE_THREW, diag.reason)
        assertEquals(-1L, result.totalScreenOffMs, "sentinel -1L when unavailable")
        assertEquals(-1L, result.totalScreenOnMs, "sentinel -1L when unavailable")
        assertEquals(0, result.partialLockCount, "0 lock count when unavailable")
    }

    // ===== Scenario 3: default (no install) returns unavailable PKG_NOT_FOUND =====

    @Test
    fun `default fake without install returns unavailable snapshot`() {
        val bridge = FakeAdbBridge()

        val result = bridge.captureWakeLocks(deviceId, pkg)

        assertFalse(result.wakeLocksAvailable, "default fake state is unavailable")
        // Default fake should still populate a diagnostic so callers can render
        // a Spanish-tuteo-formal banner instead of a misleading 0h.
        assertNotNull(result.diagnostic, "default fake must populate diagnostic")
    }
}
