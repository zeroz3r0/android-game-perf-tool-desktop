package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.GpuUnavailableReason
import com.gameperf.desktop.testing.FakeAdbBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v4.5.0 — Adreno perfcounter enable/disable lifecycle tests (Batch 3,
 * spec GPU-007.1..GPU-007.4 + GPU-014).
 *
 * Drives the bridge end-to-end against [FakeAdbBridge.shellResponses]. Each
 * test exercises a single concern: enable-success warm-up, enable-failure
 * sticky terminal state, `resetSessionState()` echo-0 best-effort issuance,
 * multi-device isolation.
 *
 * No mocks. The Adreno enable command issued is:
 *  `echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter 2>&1; echo rc=$?`
 * The bridge inspects stdout for `rc=0` AND absence of "denied"/"Permission"
 * to decide success vs failure (SELinux EACCES emits "Permission denied"
 * on stderr which `2>&1` redirects).
 */
class AdbBridgeGpuLifecycleTest {

    private val device = "test-device-gpu-lc"
    private val dev2 = "test-device-gpu-lc-2"

    private val maliUtilizationPath = "/sys/class/misc/mali0/device/utilization"
    private val maliUtilityPath = "/sys/class/misc/mali0/device/utility"
    private val maliPlatformBusPath = "/sys/devices/platform/mali/utilization"
    private val adrenoPercentPath = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
    private val adrenoGpuBusyPath = "/sys/class/kgsl/kgsl-3d0/gpubusy"
    private val powerVrPath = "/proc/mtk_mali/utilization"
    private val perfcounterNode = GpuVendorCatalog.ADRENO_PERFCOUNTER_NODE

    private fun allProbesEmpty(bridge: FakeAdbBridge) {
        bridge.shellResponses[maliUtilizationPath] = ""
        bridge.shellResponses[maliUtilityPath] = ""
        bridge.shellResponses[maliPlatformBusPath] = ""
        bridge.shellResponses[adrenoPercentPath] = ""
        bridge.shellResponses[adrenoGpuBusyPath] = ""
        bridge.shellResponses[powerVrPath] = ""
    }

    // ── Test 1: Both probes empty + echo succeeds → warm-up ────────────────

    @Test
    fun `enable success returns unavailable warm-up sets perfcounterEnabledByUs`() {
        val bridge = FakeAdbBridge().apply {
            allProbesEmpty(this)
            // echo 1 > perfcounter succeeds (rc=0, no denied).
            shellResponses["echo 1 > $perfcounterNode"] = "rc=0"
        }

        val snap = bridge.captureGpuUsage(device)

        assertFalse(snap.gpuAvailable, "enable just happened — must return UNAVAILABLE warm-up tick")
        assertEquals(-1, snap.usagePct)
        val diag = assertNotNull(snap.diagnostic)
        assertEquals(GpuUnavailableReason.ALL_PROBES_FAILED, diag.reason)
        assertEquals("ADRENO", diag.detectedVendor)
        // Echo enable was attempted.
        assertTrue(
            bridge.shellCalls.any { it.second.contains("echo 1 > $perfcounterNode") },
            "must have attempted the perfcounter enable shell",
        )
    }

    // ── Test 2: Enable success then later read succeeds ────────────────────

    @Test
    fun `enable success then second tick after gpubusy returns delta`() {
        val bridge = FakeAdbBridge().apply {
            allProbesEmpty(this)
            shellResponses["echo 1 > $perfcounterNode"] = "rc=0"
        }

        // Tick 1: enable attempted, UNAVAILABLE warm-up returned.
        bridge.captureGpuUsage(device)

        // Update the shellResponses so the next probe finds gpubusy populated.
        bridge.shellResponses[adrenoGpuBusyPath] = "1000 10000"

        // Tick 2: re-probe → gpubusy hit → baseline stored → UNAVAILABLE.
        val tick2 = bridge.captureGpuUsage(device)
        assertFalse(tick2.gpuAvailable, "gpubusy baseline tick is UNAVAILABLE")

        // Tick 3: counters advanced → delta computed.
        bridge.shellResponses[adrenoGpuBusyPath] = "2500 30000"
        val tick3 = bridge.captureGpuUsage(device)

        assertEquals(7, tick3.usagePct)
        assertTrue(tick3.gpuAvailable)
    }

    // ── Test 3: Echo fails → ADRENO_PERFCOUNTER_DISABLED sticky ────────────

    @Test
    fun `enable failure rc-non-zero returns ADRENO_PERFCOUNTER_DISABLED sticky`() {
        val bridge = FakeAdbBridge().apply {
            allProbesEmpty(this)
            shellResponses["echo 1 > $perfcounterNode"] = "Permission denied\nrc=1"
        }

        val tick1 = bridge.captureGpuUsage(device)

        assertFalse(tick1.gpuAvailable)
        val diag = assertNotNull(tick1.diagnostic)
        assertEquals(GpuUnavailableReason.ADRENO_PERFCOUNTER_DISABLED, diag.reason)
        assertNotNull(diag.failedEnableCommand)
        assertTrue(
            diag.failedEnableCommand!!.contains(perfcounterNode),
            "failedEnableCommand must include the perfcounter node path",
        )
        // Sticky: subsequent ticks must NOT re-shell.
        val shellCountAfterFirst = bridge.shellCalls.size
        repeat(3) { bridge.captureGpuUsage(device) }
        assertEquals(
            shellCountAfterFirst,
            bridge.shellCalls.size,
            "ADRENO_PERFCOUNTER_DISABLED is sticky — no more shells",
        )
    }

    @Test
    fun `enable failure permission-denied without rc-suffix is also recognised`() {
        val bridge = FakeAdbBridge().apply {
            allProbesEmpty(this)
            // No rc=0 → matches as failure regardless of denied substring.
            shellResponses["echo 1 > $perfcounterNode"] = "tee: cannot write: read-only fs"
        }

        val snap = bridge.captureGpuUsage(device)
        val diag = assertNotNull(snap.diagnostic)
        assertEquals(GpuUnavailableReason.ADRENO_PERFCOUNTER_DISABLED, diag.reason)
    }

    // ── Test 4: resetSessionState() issues echo 0 for enabled devices ──────

    @Test
    fun `resetSessionState issues echo 0 only for devices we enabled`() {
        val bridge = FakeAdbBridge().apply {
            allProbesEmpty(this)
            shellResponses["echo 1 > $perfcounterNode"] = "rc=0"
        }
        // Drive device 1 through the enable path → perfcounterEnabledByUs=true.
        bridge.captureGpuUsage(device)
        val shellCountBeforeReset = bridge.shellCalls.size

        bridge.resetSessionState()

        // Reset must have issued exactly ONE echo 0 shell, for `device`.
        val echo0Calls = bridge.shellCalls
            .drop(shellCountBeforeReset)
            .filter { it.second.contains("echo 0 > $perfcounterNode") }
        assertEquals(1, echo0Calls.size, "reset must echo 0 once for the enabled device")
        assertEquals(device, echo0Calls.first().first, "echo 0 must target the enabled device")

        // After reset, the map is clear — a new capture re-probes from scratch.
        val nextProbe = bridge.captureGpuUsage(device)
        assertFalse(nextProbe.gpuAvailable)  // still all-empty
    }

    // ── Test 5: resetSessionState() does NOT echo 0 when we never enabled ──

    @Test
    fun `resetSessionState skips echo 0 for Mali device we never enabled`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[maliUtilizationPath] = "42"
        }
        bridge.captureGpuUsage(device)  // Mali path wins — no enable issued.
        val shellCountBeforeReset = bridge.shellCalls.size

        bridge.resetSessionState()

        val echo0Calls = bridge.shellCalls
            .drop(shellCountBeforeReset)
            .filter { it.second.contains("echo 0 > $perfcounterNode") }
        assertTrue(echo0Calls.isEmpty(), "reset must NOT echo 0 for non-enabled device")
    }

    // ── Test 6: Multi-device isolation ─────────────────────────────────────

    @Test
    fun `enabled device 1 does not affect device 2 state`() {
        val bridge = FakeAdbBridge().apply {
            allProbesEmpty(this)
            shellResponses["echo 1 > $perfcounterNode"] = "rc=0"
        }
        bridge.captureGpuUsage(device)  // device 1: enable success path.
        bridge.captureGpuUsage(dev2)    // device 2: also enable (separate state).

        val shellCountBeforeReset = bridge.shellCalls.size
        bridge.resetSessionState()

        // BOTH devices got the echo 0 (both have perfcounterEnabledByUs=true).
        val echo0Calls = bridge.shellCalls
            .drop(shellCountBeforeReset)
            .filter { it.second.contains("echo 0 > $perfcounterNode") }
        assertEquals(2, echo0Calls.size, "both enabled devices get echo 0")
        val targets = echo0Calls.map { it.first }.toSet()
        assertEquals(setOf(device, dev2), targets, "each device gets its own echo 0")
    }

    @Test
    fun `mixed-vendor reset issues echo 0 only for the enabled device`() {
        // Two FakeAdbBridges share no state (shellResponses is per-instance).
        // We simulate "device 1 = Adreno enable-success, device 2 = Mali OK"
        // with TWO bridges, then prove reset on the Adreno bridge issues
        // exactly one echo 0 (its only enabled device), and reset on the
        // Mali bridge issues none.
        val adrenoBridge = FakeAdbBridge().apply {
            allProbesEmpty(this)
            shellResponses["echo 1 > $perfcounterNode"] = "rc=0"
        }
        adrenoBridge.captureGpuUsage(device)
        val countBeforeReset1 = adrenoBridge.shellCalls.size
        adrenoBridge.resetSessionState()
        val adrenoEcho0 = adrenoBridge.shellCalls
            .drop(countBeforeReset1)
            .count { it.second.contains("echo 0 > $perfcounterNode") }
        assertEquals(1, adrenoEcho0, "Adreno-enabled device gets 1 echo 0 on reset")

        val maliBridge = FakeAdbBridge().apply {
            shellResponses[maliUtilizationPath] = "55"
        }
        maliBridge.captureGpuUsage(dev2)
        val countBeforeReset2 = maliBridge.shellCalls.size
        maliBridge.resetSessionState()
        val maliEcho0 = maliBridge.shellCalls
            .drop(countBeforeReset2)
            .count { it.second.contains("echo 0 > $perfcounterNode") }
        assertEquals(0, maliEcho0, "Mali-only device gets 0 echo 0 on reset")
    }
}
