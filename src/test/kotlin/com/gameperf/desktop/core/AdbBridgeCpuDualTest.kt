package com.gameperf.desktop.core

import com.gameperf.desktop.testing.FakeAdbBridge
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v4.5.0 — Tests for the dual CPU capture convenience (Sprint 0 of
 * `cpu-total-vs-app-usage`, spec CDU-001).
 *
 * Contract under test: `AdbBridgeApi.captureCpuDual(deviceId, pkg): CpuDualSnapshot`
 * MUST fan out to the two existing `captureCpuPercent` overloads and return both
 * values in a single immutable snapshot. The dual capture MUST preserve sentinels
 * (-1 from either underlying method passes through verbatim — the AppViewModel
 * already gates history append on `> 0` so honest sentinels keep test fakes
 * flexible, per design ADR-2).
 *
 * Why a dedicated test file: the existing `captureCpuPercent` overloads have
 * direct test coverage in `AdbBridgeFPowerTest` neighbors, but the dual
 * convenience adds a NEW contract — it must call BOTH paths and combine.
 * Mirrors the precedent set by `AdbBridgeFPowerTest` (Batch 3 of fpower-metric)
 * per the architecture mandate that capture-bridge contracts get their own
 * fixture-driven unit tests separate from ViewModel integration tests.
 */
class AdbBridgeCpuDualTest {

    private val device = "test-device"
    private val pkg = "com.example.game"

    /**
     * Subclass-of-fake test double that scripts BOTH CPU readouts independently.
     * The base [FakeAdbBridge] returns 0 for both — useless here because we want
     * to assert the dual snapshot pulls from each underlying method in the
     * expected slot (total ← device-wide; app ← pkg-scoped).
     */
    private class ScriptedCpuBridge(
        private val totalDevice: Int,
        private val app: Int,
    ) : FakeAdbBridge() {
        override fun captureCpuPercent(deviceId: String): Int = totalDevice
        override fun captureCpuPercent(deviceId: String, pkg: String): Int = app
    }

    // ── Happy path — both readouts populated, snapshot composes correctly ──

    @Test
    fun `happy path - scripted bridge returns 80 total and 30 app - snapshot fields match`() {
        val bridge = ScriptedCpuBridge(totalDevice = 80, app = 30)

        val snap = bridge.captureCpuDual(device, pkg)

        assertEquals(80, snap.totalDeviceCpuPct, "total CPU pulled from device-wide overload")
        assertEquals(30, snap.appCpuPct, "app CPU pulled from pkg-scoped overload")
    }

    // ── Sentinel preservation — first-tick -1 passes through unchanged ──

    @Test
    fun `sentinel - bridge returns -1 from underlying methods - dual snapshot preserves -1`() {
        val bridge = ScriptedCpuBridge(totalDevice = -1, app = -1)

        val snap = bridge.captureCpuDual(device, pkg)

        assertEquals(-1, snap.totalDeviceCpuPct, "total -1 sentinel preserved (no coercion to 0)")
        assertEquals(-1, snap.appCpuPct, "app -1 sentinel preserved (no coercion to 0)")
    }

    // ── Mixed — total populated but app first-tick sentinel ──

    @Test
    fun `mixed sentinel - total populated and app -1 - snapshot carries both honestly`() {
        val bridge = ScriptedCpuBridge(totalDevice = 50, app = -1)

        val snap = bridge.captureCpuDual(device, pkg)

        assertEquals(50, snap.totalDeviceCpuPct, "real reading on total channel")
        assertEquals(-1, snap.appCpuPct, "honest first-tick sentinel on app channel")
    }
}
