package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Unit tests for [TierSchedule].
 *
 * The schedule is the heart of the v3.1.11 round-2 capture overhead fix. The tests lock
 * in the critical invariants:
 *
 *   1. Iter 0 fires ONLY the fast tier (no startup burst)
 *   2. Slow and Compositor NEVER coincide on the same iteration (the round-1 critical bug)
 *   3. Slow and Medium NEVER coincide
 *   4. Each tier fires at the expected cadence (~2s, ~5s, ~7s respectively)
 *
 * If a future contributor changes the phase numbers in a way that re-introduces a
 * coincidence, these tests catch it immediately.
 */
class TierScheduleTest {

    private val s = TierSchedule()

    // ===== Iter 0 isolation (the regression test for v3.1.10's first-iteration burst) =====

    @Test
    fun `iteration 0 fires no heavy tier`() {
        assertFalse(s.runMedium(0), "iter 0 must NOT run medium tier (startup burst regression)")
        assertFalse(s.runSlow(0), "iter 0 must NOT run slow tier (startup burst regression)")
        assertFalse(s.runCompositor(0), "iter 0 must NOT run compositor tier (startup burst regression)")
        assertFalse(s.runAnyHeavy(0))
    }

    // ===== Coincidence invariants =====

    @Test
    fun `slow and compositor never fire on the same iteration in the first 200 iters`() {
        // Round-1 critical bug: slow + compositor in same iter caused 1300ms hiccups.
        // Round-2 fix: phase Slow at % 10 == 6 and Compositor at % 14 == 3 — by CRT
        // analysis no integer satisfies both. Verify empirically up to iter 200.
        for (i in 0..200) {
            if (s.runSlow(i) && s.runCompositor(i)) {
                fail("iter $i fires BOTH slow AND compositor — this re-introduces the v3.1.11-r1 hiccup bug")
            }
        }
    }

    @Test
    fun `slow and medium never fire on the same iteration in the first 200 iters`() {
        for (i in 0..200) {
            if (s.runSlow(i) && s.runMedium(i)) {
                fail("iter $i fires BOTH slow AND medium — this re-introduces the v3.1.11-r1 grouped-burst issue")
            }
        }
    }

    // ===== Cadence verification =====

    @Test
    fun `medium fires every 4 iterations starting at iter 1`() {
        val firings = (0..40).filter { s.runMedium(it) }
        // Phase 1, period 4 → expect 1, 5, 9, 13, 17, 21, 25, 29, 33, 37
        assertEquals(listOf(1, 5, 9, 13, 17, 21, 25, 29, 33, 37), firings)
    }

    @Test
    fun `slow fires every 10 iterations starting at iter 6`() {
        val firings = (0..60).filter { s.runSlow(it) }
        // Phase 6, period 10 → expect 6, 16, 26, 36, 46, 56
        assertEquals(listOf(6, 16, 26, 36, 46, 56), firings)
    }

    @Test
    fun `compositor fires every 14 iterations starting at iter 3`() {
        val firings = (0..60).filter { s.runCompositor(it) }
        // Phase 3, period 14 → expect 3, 17, 31, 45, 59
        assertEquals(listOf(3, 17, 31, 45, 59), firings)
    }

    // ===== First 30 iterations — full schedule trace =====

    @Test
    fun `first 30 iterations have the expected tier firing pattern`() {
        // Build a string like "F", "F+M", "F+S", "F+C", "F+M+C" for each iter and compare
        val expected = (0..29).map { i ->
            val parts = mutableListOf("F")
            if (s.runMedium(i)) parts.add("M")
            if (s.runSlow(i)) parts.add("S")
            if (s.runCompositor(i)) parts.add("C")
            parts.joinToString("+")
        }
        val golden = listOf(
            /*  0 */ "F",        // iter 0: fast only — no startup burst
            /*  1 */ "F+M",      // first medium
            /*  2 */ "F",
            /*  3 */ "F+C",      // first compositor
            /*  4 */ "F",
            /*  5 */ "F+M",
            /*  6 */ "F+S",      // first slow
            /*  7 */ "F",
            /*  8 */ "F",
            /*  9 */ "F+M",
            /* 10 */ "F",
            /* 11 */ "F",
            /* 12 */ "F",
            /* 13 */ "F+M",
            /* 14 */ "F",
            /* 15 */ "F",
            /* 16 */ "F+S",
            /* 17 */ "F+M+C",    // medium + compositor coincide here (every 28 iters)
            /* 18 */ "F",
            /* 19 */ "F",
            /* 20 */ "F",
            /* 21 */ "F+M",
            /* 22 */ "F",
            /* 23 */ "F",
            /* 24 */ "F",
            /* 25 */ "F+M",
            /* 26 */ "F+S",
            /* 27 */ "F",
            /* 28 */ "F",
            /* 29 */ "F+M"
        )
        assertEquals(golden, expected)
    }

    // ===== Worst-case cost bound =====

    @Test
    fun `no iteration fires more than two heavy tiers simultaneously`() {
        // Even when tiers coincide, AT MOST two should fire together (medium + compositor at
        // iter 17). Three tiers in one iter would re-introduce the v3.1.10 burst class.
        for (i in 0..200) {
            val count = listOf(s.runMedium(i), s.runSlow(i), s.runCompositor(i)).count { it }
            assertTrue(
                count <= 2,
                "iter $i fires $count heavy tiers simultaneously (must be ≤ 2 to bound worst-case cost)"
            )
        }
    }

    // ===== Phase validation =====

    @Test
    fun `constructor rejects phase 0 to prevent iter-0 burst regression`() {
        // Defensive: a future contributor who tries `mediumPhase = 0` would re-introduce
        // the v3.1.10 first-iteration burst. The require() in the constructor blocks it.
        var threw = false
        try {
            TierSchedule(mediumPhase = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "constructor must reject mediumPhase = 0")

        threw = false
        try {
            TierSchedule(slowPhase = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "constructor must reject slowPhase = 0")

        threw = false
        try {
            TierSchedule(compositorPhase = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "constructor must reject compositorPhase = 0")
    }
}
