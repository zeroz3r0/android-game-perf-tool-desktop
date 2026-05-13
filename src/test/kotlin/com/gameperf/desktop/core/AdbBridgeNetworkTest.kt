package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.NetworkUnavailableReason
import com.gameperf.desktop.testing.FakeAdbBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v4.6.x — Network bandwidth probe-once-then-cache lifecycle tests.
 *
 * Spec coverage:
 *  - NET-007 — probe-once cold tick walks the catalog; steady-state cache hit
 *    issues exactly ONE shell call.
 *  - NET-008 — sticky failure short-circuits subsequent ticks (zero new shell).
 *  - NET-009 — `captureNetworkBandwidth` body wrapped in try/catch yields
 *    `CAPTURE_THREW` diagnostic; no exception propagation.
 *  - NET-010 — implausible byte values (negative or >100GB) cached as
 *    `IMPLAUSIBLE_VALUE` terminal diagnostic.
 *
 * Drives [FakeAdbBridge] end-to-end against [FakeAdbBridge.shellResponses].
 * The fake mirrors the production state machine so tests pass against both
 * the fake AND the real bridge (the production code uses the same parser +
 * catalog + state shape).
 *
 * Substring-keyed shellResponses convention: each binder code's transaction
 * shell line is distinguishable by `"netstats <code>"`, the dumpsys fallback
 * by `"netstats detail"`. Tests configure only the substrings they need.
 */
class AdbBridgeNetworkTest {

    private val device = "test-device-network"
    private val device2 = "test-device-network-2"
    private val pkg = "com.gameperf.testgame"
    private val uid = 10234

    private val dumpsysSubstring = "netstats detail"
    private fun binderSubstring(code: Int): String = "netstats $code"

    /**
     * Well-formed `service call netstats` parcel that decodes to
     * `(rx=100, tx=512)` per [NetworkBandwidthParser.parseServiceCallResponse]:
     *   `(00000000 shl 32) or 00000064` = 100
     *   `(00000000 shl 32) or 00000200` = 512
     */
    private val parcel100rx512tx =
        "Result: Parcel(\n  0x00000000: 00000000 00000064 00000000 00000200)"

    /** Make every binder code return empty payload (cannot parse). */
    private fun allBindersEmpty(bridge: FakeAdbBridge) {
        for (candidate in NetworkVendorCatalog.PROBE_CANDIDATES) {
            val code = candidate.binderCode ?: continue
            bridge.shellResponses[binderSubstring(code)] = ""
        }
    }

    // ─────────────────────────────────────────────────────────────
    // NET-007 — probe-once-then-cache
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `cold probe walks binder catalog and caches first winner`() {
        val bridge = FakeAdbBridge().apply {
            // Binder code 11 returns garbage, code 12 returns the well-formed parcel.
            shellResponses[binderSubstring(11)] = ""
            shellResponses[binderSubstring(12)] = parcel100rx512tx
            shellResponses[binderSubstring(14)] = "" // should not be queried after 12 wins
            shellResponses[binderSubstring(15)] = ""
        }

        val snap = bridge.captureNetworkBandwidth(device, pkg, uid)

        assertTrue(snap.networkAvailable, "cold probe with one winning code must surface available")
        assertEquals(100L, snap.rxBytes)
        assertEquals(512L, snap.txBytes)
        assertNull(snap.diagnostic, "happy path has no diagnostic")
        // Code 14 / 15 must NOT have been shelled — first hit wins.
        assertFalse(
            bridge.shellCalls.any { it.second.contains(binderSubstring(14)) },
            "binder code 14 must not be probed after code 12 wins",
        )
    }

    @Test
    fun `second call uses cached method and issues exactly one shell`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[binderSubstring(11)] = parcel100rx512tx
        }

        // Tick 1: cold probe + cache.
        bridge.captureNetworkBandwidth(device, pkg, uid)
        val coldCallCount = bridge.shellCalls.size
        assertTrue(coldCallCount >= 1, "cold probe must issue ≥1 shell call")

        // Tick 2: cached steady-state.
        val tick2 = bridge.captureNetworkBandwidth(device, pkg, uid)
        val steadyDelta = bridge.shellCalls.size - coldCallCount

        assertEquals(1, steadyDelta, "NET-007: steady-state must issue exactly 1 shell call")
        assertTrue(tick2.networkAvailable, "cached method continues to return available snapshots")
        assertEquals(100L, tick2.rxBytes)
        assertEquals(512L, tick2.txBytes)
    }

    @Test
    fun `cold probe falls back to dumpsys when all binder codes empty`() {
        val dumpsysOutput = """
            uid=$uid set=DEFAULT tag=0x0
              0x10000000 wlan0 DEFAULT NO 17086802 100 1214969 50
        """.trimIndent()

        val bridge = FakeAdbBridge().apply {
            allBindersEmpty(this)
            shellResponses[dumpsysSubstring] = dumpsysOutput
        }

        val snap = bridge.captureNetworkBandwidth(device, pkg, uid)

        assertTrue(snap.networkAvailable, "dumpsys fallback must succeed when binder fails")
        assertEquals(17086802L, snap.rxBytes)
        assertEquals(1214969L, snap.txBytes)
        // Every binder code WAS attempted before dumpsys (catalog walk).
        for (candidate in NetworkVendorCatalog.PROBE_CANDIDATES) {
            val code = candidate.binderCode ?: continue
            assertTrue(
                bridge.shellCalls.any { it.second.contains(binderSubstring(code)) },
                "binder code $code must be attempted before dumpsys fallback",
            )
        }
        assertTrue(
            bridge.shellCalls.any { it.second.contains(dumpsysSubstring) },
            "dumpsys fallback must have been attempted",
        )
    }

    // ─────────────────────────────────────────────────────────────
    // NET-008 — sticky failure
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `all probes failing caches BINDER_UNAVAILABLE diagnostic`() {
        val bridge = FakeAdbBridge().apply {
            allBindersEmpty(this)
            shellResponses[dumpsysSubstring] = "" // dumpsys also empty
        }

        val snap = bridge.captureNetworkBandwidth(device, pkg, uid)

        assertFalse(snap.networkAvailable)
        assertEquals(-1L, snap.rxBytes)
        assertEquals(-1L, snap.txBytes)
        val diag = assertNotNull(snap.diagnostic)
        // Binder codes were all tried + dumpsys was tried but came back empty.
        // Reason should be BINDER_UNAVAILABLE (binder ran but parsed nothing,
        // dumpsys was empty too — but binder attempts populated failedCodes
        // so we surface the binder reason, not ALL_PROBES_FAILED).
        assertEquals(NetworkUnavailableReason.BINDER_UNAVAILABLE, diag.reason)
        assertEquals(
            NetworkVendorCatalog.PROBE_CANDIDATES.size,
            diag.failedBinderCodes.size,
            "every binder code must be recorded as failed when none parsed",
        )
        assertNull(diag.detectedMethod, "no method survived → detectedMethod=null")
    }

    @Test
    fun `sticky failure short-circuits subsequent ticks with zero new shell calls`() {
        val bridge = FakeAdbBridge().apply {
            allBindersEmpty(this)
            shellResponses[dumpsysSubstring] = ""
        }

        // Tick 1 — probes ALL fail → cache terminal diagnostic.
        bridge.captureNetworkBandwidth(device, pkg, uid)
        val coldCallCount = bridge.shellCalls.size

        // Tick 2 — sticky cache hit; expect ZERO new shell calls.
        val tick2 = bridge.captureNetworkBandwidth(device, pkg, uid)
        val stickyDelta = bridge.shellCalls.size - coldCallCount

        assertEquals(
            0, stickyDelta,
            "NET-008: sticky failure must NOT issue any further shell calls",
        )
        assertFalse(tick2.networkAvailable)
        val diag2 = assertNotNull(tick2.diagnostic)
        assertEquals(
            NetworkUnavailableReason.BINDER_UNAVAILABLE, diag2.reason,
            "cached diagnostic must be returned verbatim on sticky ticks",
        )
    }

    @Test
    fun `dumpsys permission denied yields DUMPSYS_PERMISSION_DENIED reason`() {
        val bridge = FakeAdbBridge().apply {
            allBindersEmpty(this)
            shellResponses[dumpsysSubstring] =
                "Permission denial: cannot access netstats service"
        }

        val snap = bridge.captureNetworkBandwidth(device, pkg, uid)

        assertFalse(snap.networkAvailable)
        val diag = assertNotNull(snap.diagnostic)
        assertEquals(NetworkUnavailableReason.DUMPSYS_PERMISSION_DENIED, diag.reason)
    }

    // ─────────────────────────────────────────────────────────────
    // NET-009 — try/catch resilience
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `thrown exception yields CAPTURE_THREW snapshot without propagation`() {
        val bridge = FakeAdbBridge().apply {
            networkThrowOn[device] = RuntimeException("boom")
        }

        // MUST NOT throw — wrapped by try/catch.
        val snap = bridge.captureNetworkBandwidth(device, pkg, uid)

        assertFalse(snap.networkAvailable)
        assertEquals(-1L, snap.rxBytes)
        assertEquals(-1L, snap.txBytes)
        val diag = assertNotNull(snap.diagnostic)
        assertEquals(NetworkUnavailableReason.CAPTURE_THREW, diag.reason)
    }

    // ─────────────────────────────────────────────────────────────
    // NET-010 — plausibility rejection
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `implausible value (over 100GB) caches IMPLAUSIBLE_VALUE diagnostic`() {
        // Build a parcel whose decode produces rxBytes > 100 GB.
        // Each high+low pair → (high shl 32) or low. We need rx > 100*1024*1024*1024 = 107_374_182_400.
        // Use high=0x00000040 (64), low=0x00000000 → rx = (64 << 32) = 274_877_906_944 bytes ≈ 256 GB.
        // tx well-formed at 0.
        val parcelImplausible =
            "Result: Parcel(00000040 00000000 00000000 00000000)"

        val bridge = FakeAdbBridge().apply {
            shellResponses[binderSubstring(11)] = parcelImplausible
        }

        val snap = bridge.captureNetworkBandwidth(device, pkg, uid)

        assertFalse(
            snap.networkAvailable,
            "implausible value (>100GB) must yield networkAvailable=false",
        )
        val diag = assertNotNull(snap.diagnostic)
        assertEquals(NetworkUnavailableReason.IMPLAUSIBLE_VALUE, diag.reason)
    }

    // ─────────────────────────────────────────────────────────────
    // Multi-device isolation — each device has its own cache
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `multi-device probe caches are isolated per device`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[binderSubstring(11)] = parcel100rx512tx
        }

        val snap1 = bridge.captureNetworkBandwidth(device, pkg, uid)
        val snap2 = bridge.captureNetworkBandwidth(device2, pkg, uid)

        assertTrue(snap1.networkAvailable)
        assertTrue(snap2.networkAvailable)
        // Both devices probed binder code 11 (each has its own cold probe).
        val binder11Calls = bridge.shellCalls.count { it.second.contains(binderSubstring(11)) }
        assertEquals(
            2, binder11Calls,
            "each device must perform its OWN cold probe",
        )
    }

    @Test
    fun `resetSessionState clears probe cache and forces fresh walk on next call`() {
        val bridge = FakeAdbBridge().apply {
            shellResponses[binderSubstring(11)] = parcel100rx512tx
        }

        // Tick 1: prime the cache.
        bridge.captureNetworkBandwidth(device, pkg, uid)
        val baselineCount = bridge.shellCalls.size

        bridge.resetSessionState()

        // Next tick must perform a fresh cold walk (≥1 shell call again).
        bridge.captureNetworkBandwidth(device, pkg, uid)
        val freshDelta = bridge.shellCalls.size - baselineCount
        assertTrue(
            freshDelta >= 1,
            "resetSessionState must clear cache → next call re-walks the catalog",
        )
    }
}
