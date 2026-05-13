package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.GpuUnavailableReason
import com.gameperf.desktop.testing.FakeAdbBridge
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v4.5.0 — Wired probe-flow tests for `captureGpuUsage` (Batch 3, spec
 * GPU-001..GPU-006, GPU-022).
 *
 * Drives the bridge end-to-end against [FakeAdbBridge.shellResponses]. Each
 * test exercises a single concern: Mali first-hit, Adreno percent first-hit,
 * Adreno gpubusy two-tick delta, PowerVR placeholder match, sticky-failure
 * short-circuit, exception resilience.
 *
 * No mocks. Substring matching of [FakeAdbBridge.shellResponses] is the
 * single channel — keys are catalog path substrings, asserted unique by
 * [GpuVendorCatalogTest].
 */
class AdbBridgeGpuTest {

    private val device = "test-device-gpu"

    private val maliUtilizationPath = "/sys/class/misc/mali0/device/utilization"
    private val maliUtilityPath = "/sys/class/misc/mali0/device/utility"
    private val maliPlatformBusPath = "/sys/devices/platform/mali/utilization"
    private val adrenoPercentPath = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
    private val adrenoGpuBusyPath = "/sys/class/kgsl/kgsl-3d0/gpubusy"
    private val powerVrPath = "/proc/mtk_mali/utilization"

    // ── Test 1: Mali first-hit (spec GPU-001 + GPU-003) ────────────────────

    @Test
    fun `Mali first-hit returns usagePct 42 with gpuAvailable true`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[maliUtilizationPath] = "42"
            shellResponses[adrenoPercentPath] = ""
            shellResponses[adrenoGpuBusyPath] = ""
            shellResponses[powerVrPath] = ""
        }

        val snap = bridge.captureGpuUsage(device)

        assertEquals(42, snap.usagePct)
        assertTrue(snap.gpuAvailable, "Mali happy path should be available")
        assertNull(snap.diagnostic, "Mali happy path has no diagnostic")
    }

    @Test
    fun `Mali cached steady-state issues single cat shell on second call`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[maliUtilizationPath] = "42"
            shellResponses[adrenoPercentPath] = ""
            shellResponses[adrenoGpuBusyPath] = ""
            shellResponses[powerVrPath] = ""
        }

        // First call: probes all candidates (up to 4 shells before Mali hits).
        bridge.captureGpuUsage(device)
        val firstCallShellCount = bridge.shellCalls.size

        // Second call MUST hit only the cached Mali path.
        val snap = bridge.captureGpuUsage(device)
        val secondCallShellCount = bridge.shellCalls.size - firstCallShellCount

        assertEquals(42, snap.usagePct)
        assertEquals(
            1,
            secondCallShellCount,
            "cached Mali steady state must issue exactly 1 shell read",
        )
    }

    // ── Test 2: Adreno gpu_busy_percentage first-hit (spec GPU-005) ────────

    @Test
    fun `Adreno gpu_busy_percentage first-hit returns 55 percent`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[maliUtilizationPath] = ""
            shellResponses[maliUtilityPath] = ""
            shellResponses[maliPlatformBusPath] = ""
            shellResponses[adrenoPercentPath] = "55"
            shellResponses[adrenoGpuBusyPath] = ""
            shellResponses[powerVrPath] = ""
        }

        val snap = bridge.captureGpuUsage(device)

        assertEquals(55, snap.usagePct)
        assertTrue(snap.gpuAvailable, "Adreno percent happy path should be available")
        assertNull(snap.diagnostic)
    }

    @Test
    fun `Adreno gpu_busy_percentage handles trailing percent sign`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[maliUtilizationPath] = ""
            shellResponses[maliUtilityPath] = ""
            shellResponses[maliPlatformBusPath] = ""
            shellResponses[adrenoPercentPath] = "67%"
            shellResponses[adrenoGpuBusyPath] = ""
            shellResponses[powerVrPath] = ""
        }

        val snap = bridge.captureGpuUsage(device)

        assertEquals(67, snap.usagePct)
        assertTrue(snap.gpuAvailable)
    }

    // ── Test 3: Adreno gpubusy two-tick delta (spec GPU-006) ───────────────

    @Test
    fun `Adreno gpubusy first tick stores baseline returns unavailable second tick computes delta`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[maliUtilizationPath] = ""
            shellResponses[maliUtilityPath] = ""
            shellResponses[maliPlatformBusPath] = ""
            shellResponses[adrenoPercentPath] = ""
            shellResponses[adrenoGpuBusyPath] = "1000 10000"
            shellResponses[powerVrPath] = ""
        }

        // Tick 1: baseline established — unavailable.
        val tick1 = bridge.captureGpuUsage(device)
        assertFalse(tick1.gpuAvailable, "first gpubusy tick is baseline only (warm-up)")
        assertEquals(-1, tick1.usagePct)

        // Tick 2: update counters, expect delta computed.
        bridge.shellResponses[adrenoGpuBusyPath] = "2500 30000"
        val tick2 = bridge.captureGpuUsage(device)

        // (2500-1000)*100 / (30000-10000) = 1500*100/20000 = 7
        assertEquals(7, tick2.usagePct)
        assertTrue(tick2.gpuAvailable)
        assertNull(tick2.diagnostic)
    }

    @Test
    fun `Adreno gpubusy zero delta total returns unavailable`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[maliUtilizationPath] = ""
            shellResponses[maliUtilityPath] = ""
            shellResponses[maliPlatformBusPath] = ""
            shellResponses[adrenoPercentPath] = ""
            shellResponses[adrenoGpuBusyPath] = "1000 10000"
            shellResponses[powerVrPath] = ""
        }
        bridge.captureGpuUsage(device)  // baseline
        // Same values → deltaTotal=0 → unavailable.
        val tick2 = bridge.captureGpuUsage(device)
        assertFalse(tick2.gpuAvailable)
        assertEquals(-1, tick2.usagePct)
    }

    // ── Test 4: PowerVR placeholder match → POWERVR_UNSUPPORTED ────────────

    @Test
    fun `PowerVR path returns non-empty placeholder triggers POWERVR_UNSUPPORTED sticky`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[maliUtilizationPath] = ""
            shellResponses[maliUtilityPath] = ""
            shellResponses[maliPlatformBusPath] = ""
            shellResponses[adrenoPercentPath] = ""
            shellResponses[adrenoGpuBusyPath] = ""
            // PowerVR placeholder responds with a value → vendor inferred.
            shellResponses[powerVrPath] = "0"
        }

        val snap = bridge.captureGpuUsage(device)

        assertFalse(snap.gpuAvailable)
        assertEquals(-1, snap.usagePct)
        val diag = assertNotNull(snap.diagnostic)
        assertEquals(GpuUnavailableReason.POWERVR_UNSUPPORTED, diag.reason)
        assertEquals("POWERVR", diag.detectedVendor)
        assertTrue(
            diag.probedPaths.isNotEmpty(),
            "PowerVR diagnostic should list probed paths for crowdsource",
        )
        assertTrue(
            diag.probedPaths.size <= 10,
            "diagnostic paths capped at 10 (spec GPU-011)",
        )
    }

    // ── Test 5: Sticky failure — terminal devices never re-probe (GPU-002) ─

    @Test
    fun `terminal-unavailable device skips re-shelling on subsequent ticks`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[maliUtilizationPath] = ""
            shellResponses[maliUtilityPath] = ""
            shellResponses[maliPlatformBusPath] = ""
            shellResponses[adrenoPercentPath] = ""
            shellResponses[adrenoGpuBusyPath] = ""
            shellResponses[powerVrPath] = "0"
        }
        bridge.captureGpuUsage(device)  // first call — PowerVR detected, sticky.
        val firstCallShellCount = bridge.shellCalls.size

        // Subsequent ticks must short-circuit with NO additional shell calls.
        repeat(3) { bridge.captureGpuUsage(device) }
        val totalShellCount = bridge.shellCalls.size

        assertEquals(
            firstCallShellCount,
            totalShellCount,
            "sticky failure must not re-shell on subsequent ticks",
        )
    }

    // ── Exception resilience (spec GPU-022) ────────────────────────────────

    @Test
    fun `captureGpuUsage swallows IOException and returns CAPTURE_THREW`() {
        val bridge = FakeAdbBridge().apply {
            gpuThrowOn[device] = IOException("adb pipe broken")
        }

        val snap = bridge.captureGpuUsage(device)

        assertFalse(snap.gpuAvailable)
        assertEquals(-1, snap.usagePct)
        val diag = assertNotNull(snap.diagnostic)
        assertEquals(GpuUnavailableReason.CAPTURE_THREW, diag.reason)
    }
}
