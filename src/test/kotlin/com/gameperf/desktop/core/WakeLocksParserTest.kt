package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.WakeLocksUnavailableReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-parser tests for [WakeLocksParser]
 * (sdd/vitals-rate-and-wakelocks, design §4 + spec scenarios).
 *
 * Loads three real-world-shaped fixtures (Pixel 8 Pro, Galaxy S23, Tab A8)
 * from `src/test/resources/wake-locks-fixtures/` via the test classloader.
 * No mocks, no I/O beyond fixture file reads.
 *
 * Scenarios covered:
 *  - 2.4a: Pixel 8 happy path — `wakeLocksAvailable=true`, totals > 0
 *  - 2.4b: Galaxy S23 (Samsung One UI variant) — same invariants
 *  - 2.4c: Tab A8 (low-tier Android 11) — same invariants
 *  - 2.4d: Package not in output → `PKG_NOT_FOUND`
 *  - 2.4e: Empty / malformed output → `PARSE_FAILED`
 *  - 2.4f: Out-of-range duration (>24h) → `OUT_OF_RANGE_VALUE`, entry dropped
 *  - 2.4g: Other-package entries ignored (negative match)
 */
class WakeLocksParserTest {

    private val pkg = "com.example.game"

    private fun loadFixture(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("wake-locks-fixtures/$name")
            ?: error("Fixture not found: wake-locks-fixtures/$name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    // ─────────────────── 2.4a — Pixel 8 happy path ───────────────────

    @Test
    fun `parses pixel8 fixture — wakeLocksAvailable=true with non-zero screen-off ms`() {
        val raw = loadFixture("wake-locks-pixel8.log")
        val snap = WakeLocksParser.parse(raw, pkg)
        assertTrue(snap.wakeLocksAvailable, "pixel8 fixture must produce an available snapshot")
        assertNull(snap.diagnostic, "happy path: diagnostic must be null")
        assertTrue(
            snap.totalScreenOffMs > 0L,
            "pixel8 must accumulate > 0 ms; got ${snap.totalScreenOffMs}",
        )
        assertTrue(
            snap.partialLockCount >= 3,
            "pixel8 fixture mentions 3 partial wake locks for $pkg; got ${snap.partialLockCount}",
        )
    }

    @Test
    fun `parses pixel8 fixture — total ms matches 1h22m17s + 23m8s + 7m45s sum`() {
        val raw = loadFixture("wake-locks-pixel8.log")
        val snap = WakeLocksParser.parse(raw, pkg)
        // 1h22m17s = 4937s ; 23m8s = 1388s ; 7m45s = 465s ; total = 6790s = 6_790_000 ms
        val expectedMs = 6_790_000L
        assertEquals(
            expectedMs,
            snap.totalScreenOffMs,
            "pixel8 totalScreenOffMs must equal exact sum of 3 matching wake locks",
        )
    }

    // ─────────────────── 2.4b — Galaxy S23 (Samsung) ───────────────────

    @Test
    fun `parses galaxy-s23 fixture — wakeLocksAvailable=true with three matching entries`() {
        val raw = loadFixture("wake-locks-galaxy-s23.log")
        val snap = WakeLocksParser.parse(raw, pkg)
        assertTrue(snap.wakeLocksAvailable, "galaxy-s23 fixture must produce an available snapshot")
        // 45m22s + 18m33s + 11m4s = 4499s = 4_499_000 ms
        assertEquals(4_499_000L, snap.totalScreenOffMs)
        assertEquals(3, snap.partialLockCount)
        assertNull(snap.diagnostic)
    }

    // ─────────────────── 2.4c — Tab A8 (low-tier) ───────────────────

    @Test
    fun `parses tab-a8 fixture — wakeLocksAvailable=true with two matching entries`() {
        val raw = loadFixture("wake-locks-tab-a8.log")
        val snap = WakeLocksParser.parse(raw, pkg)
        assertTrue(snap.wakeLocksAvailable)
        // 15m30s + 8m12s = 1422s = 1_422_000 ms
        assertEquals(1_422_000L, snap.totalScreenOffMs)
        assertEquals(2, snap.partialLockCount)
    }

    // ─────────────────── 2.4d — Package not present ───────────────────

    @Test
    fun `returns PKG_NOT_FOUND when section exists but no entry mentions the package`() {
        val raw = loadFixture("wake-locks-pixel8.log")
        val snap = WakeLocksParser.parse(raw, "com.totally.absent.package")
        assertFalse(snap.wakeLocksAvailable)
        assertEquals(-1L, snap.totalScreenOffMs)
        assertEquals(-1L, snap.totalScreenOnMs)
        assertEquals(0, snap.partialLockCount)
        assertNotNull(snap.diagnostic)
        assertEquals(WakeLocksUnavailableReason.PKG_NOT_FOUND, snap.diagnostic!!.reason)
    }

    // ─────────────────── 2.4e — Empty / malformed ───────────────────

    @Test
    fun `returns PARSE_FAILED when output is empty`() {
        val snap = WakeLocksParser.parse("", pkg)
        assertFalse(snap.wakeLocksAvailable)
        assertNotNull(snap.diagnostic)
        assertEquals(WakeLocksUnavailableReason.PARSE_FAILED, snap.diagnostic!!.reason)
    }

    @Test
    fun `returns PARSE_FAILED when output lacks the wake-locks section header`() {
        val raw = """
            Battery History (...)
            Some other dumpsys output without the partial wake locks header.
            Statistics since last charge:
              Time on battery: 5m 0s
        """.trimIndent()
        val snap = WakeLocksParser.parse(raw, pkg)
        assertFalse(snap.wakeLocksAvailable)
        assertEquals(WakeLocksUnavailableReason.PARSE_FAILED, snap.diagnostic?.reason)
    }

    @Test
    fun `returns PARSE_FAILED when permission denied marker appears`() {
        val raw = "Permission Denial: dumpsys batterystats requires android.permission.DUMP"
        val snap = WakeLocksParser.parse(raw, pkg)
        assertFalse(snap.wakeLocksAvailable)
        assertEquals(WakeLocksUnavailableReason.PARSE_FAILED, snap.diagnostic?.reason)
    }

    // ─────────────────── 2.4f — Out-of-range plausibility ───────────────────

    @Test
    fun `drops entries with duration greater than 24 hours and returns OUT_OF_RANGE_VALUE diagnostic`() {
        // Build a fixture where one entry is implausibly large (25 hours).
        // Plausibility window per design §4: 0 ≤ ms ≤ 24*3600*1000.
        // The 25h entry is dropped; the remaining 30m entry stays; the snapshot
        // is marked available with a diagnostic flag explaining the drop.
        val raw = """
            All partial wake locks:
            Wake lock 10001 com.example.game:BogusLock: 25h 0m 0s partial realtime (1 times)
            Wake lock 10001 com.example.game:RealLock: 30m 0s partial realtime (2 times)
        """.trimIndent()
        val snap = WakeLocksParser.parse(raw, pkg)
        // The valid 30m entry survives ⇒ snapshot is available.
        assertTrue(snap.wakeLocksAvailable, "valid entries survive even when others are dropped")
        assertEquals(1_800_000L, snap.totalScreenOffMs, "30m entry contributes 1_800_000 ms")
        assertEquals(1, snap.partialLockCount, "one entry counted, the 25h outlier dropped")
        // Diagnostic flags the out-of-range drop so the report can warn the user.
        assertNotNull(snap.diagnostic, "OUT_OF_RANGE_VALUE diagnostic surfaces dropped entries")
        assertEquals(WakeLocksUnavailableReason.OUT_OF_RANGE_VALUE, snap.diagnostic!!.reason)
    }

    @Test
    fun `returns PKG_NOT_FOUND when every matching entry is dropped by plausibility window`() {
        // Edge case: every entry for the package is implausible. Snapshot is
        // unavailable; the diagnostic should be OUT_OF_RANGE_VALUE not
        // PKG_NOT_FOUND (the package WAS present, the data was just bad).
        val raw = """
            All partial wake locks:
            Wake lock 10001 com.example.game:BogusLock: 25h 0m 0s partial realtime
            Wake lock 10001 com.example.game:AnotherBogus: 99h 0m 0s partial realtime
        """.trimIndent()
        val snap = WakeLocksParser.parse(raw, pkg)
        assertFalse(snap.wakeLocksAvailable, "no surviving entries ⇒ unavailable")
        assertEquals(0, snap.partialLockCount)
        assertNotNull(snap.diagnostic)
        assertEquals(WakeLocksUnavailableReason.OUT_OF_RANGE_VALUE, snap.diagnostic!!.reason)
    }

    // ─────────────────── 2.4g — Negative match (other packages) ───────────────────

    @Test
    fun `ignores wake locks belonging to unrelated packages`() {
        // The pixel8 fixture also has entries for com.unrelated.app and
        // com.another.app — those must NOT count toward com.example.game's total.
        val raw = loadFixture("wake-locks-pixel8.log")
        val snap = WakeLocksParser.parse(raw, pkg)
        // If unrelated entries leaked in, the total would exceed the expected sum.
        assertEquals(6_790_000L, snap.totalScreenOffMs)
        assertEquals(3, snap.partialLockCount)
    }

    @Test
    fun `does not match the pkg substring inside an unrelated longer package name`() {
        // Negative guard: "com.example.game" must not match "com.example.gameworld".
        val raw = """
            All partial wake locks:
            Wake lock 10001 com.example.gameworld:OtherLock: 5h 0m 0s partial realtime
            Wake lock 10002 com.example.game:RealLock: 1h 0m 0s partial realtime
        """.trimIndent()
        val snap = WakeLocksParser.parse(raw, pkg)
        assertTrue(snap.wakeLocksAvailable)
        assertEquals(3_600_000L, snap.totalScreenOffMs, "only the exact-package entry counts")
        assertEquals(1, snap.partialLockCount)
    }
}
