package com.gameperf.desktop.core.kpi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5 — `ComparisonEngine` invariants (KPI-007).
 *
 * Spec coverage: `sdd/kpi-scoring-framework/spec` — Requirement: Comparison
 * Engine with Color Bands + signed delta vs target per [Direction].
 *
 * Bands: `GREEN ≥ 80`, `AMBER 60..79`, `RED < 60` (Phase 4 `bandOf` promoted
 * to public API here, single source of truth).
 *
 * Pure: deterministic, no I/O.
 */
class ComparisonEngineTest {

    // ─── band(score) ───────────────────────────────────────────────────────

    @Test
    fun `band returns GREEN for score 90`() {
        assertEquals(Band.GREEN, ComparisonEngine.band(90))
    }

    @Test
    fun `band returns AMBER for score 65`() {
        assertEquals(Band.AMBER, ComparisonEngine.band(65))
    }

    @Test
    fun `band returns RED for score 40`() {
        assertEquals(Band.RED, ComparisonEngine.band(40))
    }

    @Test
    fun `band returns GREEN at boundary 80 (inclusive)`() {
        assertEquals(Band.GREEN, ComparisonEngine.band(80))
    }

    @Test
    fun `band returns AMBER at boundary 79 (just below GREEN)`() {
        assertEquals(Band.AMBER, ComparisonEngine.band(79))
    }

    @Test
    fun `band returns AMBER at boundary 60 (inclusive)`() {
        assertEquals(Band.AMBER, ComparisonEngine.band(60))
    }

    @Test
    fun `band returns RED at boundary 59 (just below AMBER)`() {
        assertEquals(Band.RED, ComparisonEngine.band(59))
    }

    @Test
    fun `band returns GREEN at 100`() {
        assertEquals(Band.GREEN, ComparisonEngine.band(100))
    }

    @Test
    fun `band returns RED at 0`() {
        assertEquals(Band.RED, ComparisonEngine.band(0))
    }

    // ─── delta(actual, target, direction) ──────────────────────────────────

    @Test
    fun `delta for HIGHER_IS_BETTER returns actual minus target`() {
        // FPS: actual 55, target 60 → -5 (worse than target)
        val d = ComparisonEngine.delta(actual = 55.0, target = 60.0, direction = Direction.HIGHER_IS_BETTER)
        assertEquals(-5.0, d, 1e-9)
    }

    @Test
    fun `delta for HIGHER_IS_BETTER positive when actual exceeds target`() {
        // FPS: actual 65, target 60 → +5 (better than target)
        val d = ComparisonEngine.delta(actual = 65.0, target = 60.0, direction = Direction.HIGHER_IS_BETTER)
        assertEquals(5.0, d, 1e-9)
    }

    @Test
    fun `delta for LOWER_IS_BETTER inverts sign (lower actual is positive delta)`() {
        // Cold start: actual 4000ms, target 5000ms → +1000 (better, lower than target)
        val d = ComparisonEngine.delta(actual = 4000.0, target = 5000.0, direction = Direction.LOWER_IS_BETTER)
        assertEquals(1000.0, d, 1e-9)
    }

    @Test
    fun `delta for LOWER_IS_BETTER negative when actual exceeds target (worse)`() {
        // Cold start: actual 6000ms, target 5000ms → -1000 (worse)
        val d = ComparisonEngine.delta(actual = 6000.0, target = 5000.0, direction = Direction.LOWER_IS_BETTER)
        assertEquals(-1000.0, d, 1e-9)
    }

    @Test
    fun `delta at target equals zero regardless of direction`() {
        assertEquals(0.0, ComparisonEngine.delta(60.0, 60.0, Direction.HIGHER_IS_BETTER), 1e-9)
        assertEquals(0.0, ComparisonEngine.delta(60.0, 60.0, Direction.LOWER_IS_BETTER), 1e-9)
    }

    @Test
    fun `delta sign convention positive means better than target`() {
        // Both directions: positive delta = better. This is the user-facing
        // convention so UI can render +Δ green / -Δ red without checking
        // direction.
        val higher = ComparisonEngine.delta(70.0, 60.0, Direction.HIGHER_IS_BETTER)
        val lower = ComparisonEngine.delta(50.0, 60.0, Direction.LOWER_IS_BETTER)
        assertTrue(higher > 0)
        assertTrue(lower > 0)
    }
}
