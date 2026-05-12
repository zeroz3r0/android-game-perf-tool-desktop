package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.FPowerSnapshot
import com.gameperf.desktop.core.model.FPowerUnavailableReason
import com.gameperf.desktop.testing.FakeAdbBridge
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v4.5.0 — Tests for the FPower bridge wiring (Batch 3, spec FPW-001 + FPW-006).
 *
 * Uses [FakeAdbBridge.shellResponses] for end-to-end cold-probe simulation and
 * [FakeAdbBridge.setFPower] for lazy override scenarios. Each test exercises a
 * single concern: cold probe, cache hit, vendor fallback, plausibility, sign
 * convention, FPS_ZERO, cache reset, or fixture override.
 *
 * Bridge contract under test: `captureFPower(deviceId, currentFps): FPowerSnapshot`.
 *
 * Probe algorithm (design ADR-6 + ADR-7):
 *  - Cache hit  -> 2 shell reads (current + voltage of cached tuple).
 *  - Cold probe -> walk ORDERED_PATHS top-down, cache first winner.
 *  - Cold probe all-fail -> cache `firstProbeFailed=true`, return
 *    BATTERY_PATH_MISSING with NO further shell calls on subsequent ticks.
 *  - `resetSessionState()` clears the cache so a new session re-probes.
 */
class AdbBridgeFPowerTest {

    private val device = "test-device"
    private val aospTuple = FPowerVendorCatalog.ORDERED_PATHS[0]
    private val samsungTuple = FPowerVendorCatalog.ORDERED_PATHS[1]

    // ── Cold probe — AOSP wins (most common case) ──────────────────────────

    @Test
    fun `cold probe - AOSP tuple wins on first try - happy path`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[aospTuple.currentPath] = "350000"
            shellResponses[aospTuple.voltagePath] = "3987654"
        }

        val snap = bridge.captureFPower(device, currentFps = 60.0)

        assertTrue(snap.fpowerAvailable, "AOSP tuple should yield available snapshot")
        assertNull(snap.diagnostic, "happy path has no diagnostic")
        // power = abs(350000) * 3987654 / 1e12 = 1.3957 W → fpower = 1.3957*1000/60 = ~23.26
        val expectedPowerW = 350_000.0 * 3_987_654.0 / 1e12
        val expectedFpowerMw = expectedPowerW * 1000.0 / 60.0
        assertEquals(expectedPowerW, snap.powerW, 1e-6)
        assertEquals(expectedFpowerMw, snap.fpowerMwPerFrame, 1e-6)
        assertEquals(350_000.0, snap.currentMicroA)
        assertEquals(3_987_654.0, snap.voltageMicroV)
    }

    // ── Cached-tuple steady-state ──────────────────────────────────────────

    @Test
    fun `second call uses cached tuple - only 2 shell reads on steady state`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[aospTuple.currentPath] = "350000"
            shellResponses[aospTuple.voltagePath] = "3987654"
        }

        // First call: cold probe (AOSP wins on the first tuple => 2 shells).
        bridge.captureFPower(device, currentFps = 60.0)
        val firstCallShellCount = bridge.shellCalls.size

        // Second call: must hit ONLY the cached AOSP tuple.
        bridge.captureFPower(device, currentFps = 60.0)
        val secondCallShellCount = bridge.shellCalls.size - firstCallShellCount

        assertEquals(2, secondCallShellCount, "cached steady state should issue 2 shell reads")
    }

    // ── Cold probe — Samsung wins (AOSP first slot empty, fallback succeeds) ──

    @Test
    fun `cold probe - Samsung tuple wins when AOSP current empty`() {
        // AOSP current empty (so its current_now read returns "") but Samsung
        // current path returns a value. Note the Samsung voltage path equals the
        // AOSP voltage path, so the same shellResponses key works for both.
        val bridge = FakeAdbBridge().apply {
            // AOSP current explicitly empty → probe falls through.
            shellResponses[aospTuple.currentPath] = ""
            shellResponses[samsungTuple.currentPath] = "420000"
            shellResponses[samsungTuple.voltagePath] = "4100000"
        }

        val snap = bridge.captureFPower(device, currentFps = 60.0)

        assertTrue(snap.fpowerAvailable, "Samsung tuple should yield available snapshot")
        val expectedPowerW = 420_000.0 * 4_100_000.0 / 1e12
        assertEquals(expectedPowerW, snap.powerW, 1e-6)
    }

    @Test
    fun `cached Samsung tuple - second call probes only Samsung paths`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[aospTuple.currentPath] = ""
            shellResponses[samsungTuple.currentPath] = "420000"
            shellResponses[samsungTuple.voltagePath] = "4100000"
        }

        bridge.captureFPower(device, currentFps = 60.0)
        val firstCallShellCount = bridge.shellCalls.size

        bridge.captureFPower(device, currentFps = 60.0)
        val secondCallShellCount = bridge.shellCalls.size - firstCallShellCount

        // Steady-state: 2 reads of Samsung tuple (current + voltage).
        assertEquals(2, secondCallShellCount, "cached Samsung steady state should issue 2 shell reads")
    }

    // ── Cold probe — all 5 tuples empty → BATTERY_PATH_MISSING ─────────────

    @Test
    fun `cold probe - all tuples empty - returns BATTERY_PATH_MISSING`() {
        val bridge = FakeAdbBridge() // empty shellResponses → all reads return ""

        val snap = bridge.captureFPower(device, currentFps = 60.0)

        assertFalse(snap.fpowerAvailable, "no tuple should mean unavailable")
        val diag = assertNotNull(snap.diagnostic, "unavailable must carry diagnostic")
        assertEquals(FPowerUnavailableReason.BATTERY_PATH_MISSING, diag.reason)
        assertEquals(-1.0, snap.fpowerMwPerFrame)
        assertEquals(-1.0, snap.powerW)
    }

    @Test
    fun `cold probe failure is cached - second call performs zero new shell reads`() {
        val bridge = FakeAdbBridge() // empty → all-fail

        bridge.captureFPower(device, currentFps = 60.0)
        val firstCallShellCount = bridge.shellCalls.size

        val snap = bridge.captureFPower(device, currentFps = 60.0)
        val secondCallShellCount = bridge.shellCalls.size - firstCallShellCount

        assertEquals(0, secondCallShellCount, "cached failure must not issue any new shell reads")
        assertFalse(snap.fpowerAvailable)
        assertEquals(FPowerUnavailableReason.BATTERY_PATH_MISSING, snap.diagnostic?.reason)
    }

    @Test
    fun `BATTERY_PATH_MISSING diagnostic caps rawPathsTried at 10`() {
        val bridge = FakeAdbBridge() // all empty

        val snap = bridge.captureFPower(device, currentFps = 60.0)

        val diag = assertNotNull(snap.diagnostic)
        assertTrue(diag.rawPathsTried.size <= 10, "rawPathsTried capped at 10, got ${diag.rawPathsTried.size}")
        assertTrue(diag.lastReadout.size <= 10, "lastReadout capped at 10, got ${diag.lastReadout.size}")
    }

    // ── Negative current (discharging kernel sign convention) ──────────────

    @Test
    fun `negative current_now - parser absolutes the sign - available with positive power`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[aospTuple.currentPath] = "-350000"
            shellResponses[aospTuple.voltagePath] = "3987654"
        }

        val snap = bridge.captureFPower(device, currentFps = 60.0)

        assertTrue(snap.fpowerAvailable, "abs() handles negative-on-discharge convention")
        assertTrue(snap.powerW > 0.0, "powerW must be positive")
        val expectedPowerW = abs(-350_000.0) * 3_987_654.0 / 1e12
        assertEquals(expectedPowerW, snap.powerW, 1e-6)
    }

    // ── Implausible power (caps at 30 W) ───────────────────────────────────

    @Test
    fun `implausible powerW above 30W - returns IMPLAUSIBLE_VALUE`() {
        // 9 A * 4 V = 36 W → above the 30 W ceiling per FPW-011.
        val bridge = FakeAdbBridge().apply {
            shellResponses[aospTuple.currentPath] = "9000000"  // 9 A
            shellResponses[aospTuple.voltagePath] = "4000000"  // 4 V
        }

        val snap = bridge.captureFPower(device, currentFps = 60.0)

        assertFalse(snap.fpowerAvailable, "36 W exceeds plausibility cap")
        assertEquals(FPowerUnavailableReason.IMPLAUSIBLE_VALUE, snap.diagnostic?.reason)
        // Intermediates: parser leaves currentMicroA + voltageMicroV populated.
        assertEquals(9_000_000.0, snap.currentMicroA)
        assertEquals(4_000_000.0, snap.voltageMicroV)
    }

    // ── FPS=0 — intermediates still populated ──────────────────────────────

    @Test
    fun `fps zero - returns FPS_ZERO with intermediates populated`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[aospTuple.currentPath] = "350000"
            shellResponses[aospTuple.voltagePath] = "3987654"
        }

        val snap = bridge.captureFPower(device, currentFps = 0.0)

        assertFalse(snap.fpowerAvailable, "FPS=0 means no per-frame divisor")
        assertEquals(FPowerUnavailableReason.FPS_ZERO, snap.diagnostic?.reason)
        // The READ worked → intermediates populated.
        assertEquals(350_000.0, snap.currentMicroA)
        assertEquals(3_987_654.0, snap.voltageMicroV)
        assertTrue(snap.powerW > 0.0, "powerW must be populated when read succeeds")
        assertEquals(-1.0, snap.fpowerMwPerFrame)
    }

    // ── resetSessionState clears the cache ────────────────────────────────

    @Test
    fun `resetSessionState clears the per-device cache - next probe re-walks the catalog`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[aospTuple.currentPath] = "350000"
            shellResponses[aospTuple.voltagePath] = "3987654"
        }

        // Warm the cache on AOSP.
        bridge.captureFPower(device, currentFps = 60.0)

        // Reset state.
        bridge.resetSessionState()

        // Swap the responses so AOSP is empty and only Samsung returns data.
        bridge.shellResponses.clear()
        bridge.shellResponses[aospTuple.currentPath] = ""
        bridge.shellResponses[samsungTuple.currentPath] = "420000"
        bridge.shellResponses[samsungTuple.voltagePath] = "4100000"

        val snap = bridge.captureFPower(device, currentFps = 60.0)

        // If cache wasn't cleared, the bridge would still hit AOSP (now empty)
        // and report BATTERY_PATH_MISSING. With the reset, it must re-probe and
        // find Samsung.
        assertTrue(snap.fpowerAvailable, "after reset, fresh probe should find Samsung")
        val expectedPowerW = 420_000.0 * 4_100_000.0 / 1e12
        assertEquals(expectedPowerW, snap.powerW, 1e-6)
    }

    @Test
    fun `resetSessionState clears cached failure - next probe re-walks the catalog`() {
        val bridge = FakeAdbBridge() // all empty → cached failure

        bridge.captureFPower(device, currentFps = 60.0) // caches firstProbeFailed
        val shellCallsAfterColdFailure = bridge.shellCalls.size

        bridge.resetSessionState()

        // Populate AOSP this time.
        bridge.shellResponses[aospTuple.currentPath] = "350000"
        bridge.shellResponses[aospTuple.voltagePath] = "3987654"

        val snap = bridge.captureFPower(device, currentFps = 60.0)

        assertTrue(snap.fpowerAvailable, "after reset, re-probe should pick up the new responses")
        assertTrue(
            bridge.shellCalls.size > shellCallsAfterColdFailure,
            "after reset, new shell reads must occur (cached failure was cleared)",
        )
    }

    // ── FakeAdbBridge.setFPower override ──────────────────────────────────

    @Test
    fun `setFPower fixture overrides cold probe entirely`() {
        val scripted = FPowerSnapshot(
            fpowerMwPerFrame = 42.0,
            powerW = 2.52,
            currentMicroA = 700_000.0,
            voltageMicroV = 3_600_000.0,
            fpowerAvailable = true,
            diagnostic = null,
        )
        val bridge = FakeAdbBridge().apply {
            // Even with shellResponses pointing to nonsense, the fixture wins.
            shellResponses[aospTuple.currentPath] = "lolnothing"
            setFPower(scripted)
        }

        val snap = bridge.captureFPower(device, currentFps = 60.0)

        assertEquals(scripted, snap)
        assertEquals(0, bridge.shellCalls.size, "fixture path must short-circuit before any shells")
    }

    @Test
    fun `setFPower fixture is returned irrespective of currentFps`() {
        val scripted = FPowerSnapshot(
            fpowerMwPerFrame = 42.0,
            powerW = 2.52,
            currentMicroA = 700_000.0,
            voltageMicroV = 3_600_000.0,
            fpowerAvailable = true,
            diagnostic = null,
        )
        val bridge = FakeAdbBridge().apply { setFPower(scripted) }

        // FPS=0 would normally drive FPS_ZERO, but fixture identity overrides.
        val snap = bridge.captureFPower(device, currentFps = 0.0)

        assertEquals(scripted, snap)
    }
}
