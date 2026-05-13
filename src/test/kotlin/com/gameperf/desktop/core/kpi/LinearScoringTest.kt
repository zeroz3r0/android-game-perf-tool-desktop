package com.gameperf.desktop.core.kpi

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 3 — Pure linear scoring (KPI-002).
 *
 * Spec coverage: `sdd/kpi-scoring-framework/spec` — Requirement: Linear Scoring.
 * Scenarios (per design §LinearScoring + spec):
 *   - value == target → 100 (both directions)
 *   - value == floor  → 0   (both directions)
 *   - midpoint        → 50  (both directions)
 *   - value beyond target (better than target) → clamped to 100
 *   - value beyond floor  (worse than floor)   → clamped to 0
 *   - NaN / Infinity → 0
 *
 * Pure: zero I/O, deterministic.
 */
class LinearScoringTest {

    // ── HIGHER_IS_BETTER (e.g. FPS) — target > floor ────────────────────────

    @Test
    fun `higher-is-better value equal to target scores 100`() {
        // FPS target 60, floor 30 — exactly hitting target is perfect.
        val s = scoreLinear(value = 60.0, target = 60.0, floor = 30.0, direction = Direction.HIGHER_IS_BETTER)
        assertEquals(100, s)
    }

    @Test
    fun `higher-is-better value equal to floor scores 0`() {
        val s = scoreLinear(value = 30.0, target = 60.0, floor = 30.0, direction = Direction.HIGHER_IS_BETTER)
        assertEquals(0, s)
    }

    @Test
    fun `higher-is-better midpoint scores 50`() {
        // (60+30)/2 = 45 → halfway between floor and target → 50.
        val s = scoreLinear(value = 45.0, target = 60.0, floor = 30.0, direction = Direction.HIGHER_IS_BETTER)
        assertEquals(50, s)
    }

    @Test
    fun `higher-is-better value above target is clamped to 100`() {
        // 90 fps when target is 60 → still 100, not 150.
        val s = scoreLinear(value = 90.0, target = 60.0, floor = 30.0, direction = Direction.HIGHER_IS_BETTER)
        assertEquals(100, s)
    }

    @Test
    fun `higher-is-better value below floor is clamped to 0`() {
        // 10 fps when floor is 30 → 0, not negative.
        val s = scoreLinear(value = 10.0, target = 60.0, floor = 30.0, direction = Direction.HIGHER_IS_BETTER)
        assertEquals(0, s)
    }

    // ── LOWER_IS_BETTER (e.g. frame time, cold start) — target < floor ──────

    @Test
    fun `lower-is-better value equal to target scores 100`() {
        // cold-start target 2000 ms, floor 5000 ms — 2000 is perfect.
        val s = scoreLinear(value = 2000.0, target = 2000.0, floor = 5000.0, direction = Direction.LOWER_IS_BETTER)
        assertEquals(100, s)
    }

    @Test
    fun `lower-is-better value equal to floor scores 0`() {
        val s = scoreLinear(value = 5000.0, target = 2000.0, floor = 5000.0, direction = Direction.LOWER_IS_BETTER)
        assertEquals(0, s)
    }

    @Test
    fun `lower-is-better midpoint scores 50`() {
        // (2000+5000)/2 = 3500 → halfway → 50.
        val s = scoreLinear(value = 3500.0, target = 2000.0, floor = 5000.0, direction = Direction.LOWER_IS_BETTER)
        assertEquals(50, s)
    }

    @Test
    fun `lower-is-better value below target is clamped to 100`() {
        // 500 ms cold start when target is 2000 → 100, not 200.
        val s = scoreLinear(value = 500.0, target = 2000.0, floor = 5000.0, direction = Direction.LOWER_IS_BETTER)
        assertEquals(100, s)
    }

    @Test
    fun `lower-is-better value above floor is clamped to 0`() {
        // 9000 ms cold start when floor is 5000 → 0, not negative.
        val s = scoreLinear(value = 9000.0, target = 2000.0, floor = 5000.0, direction = Direction.LOWER_IS_BETTER)
        assertEquals(0, s)
    }

    // ── Non-finite inputs → 0 (defensive; aggregators treat null differently) ─

    @Test
    fun `NaN value scores 0 for higher-is-better`() {
        val s = scoreLinear(value = Double.NaN, target = 60.0, floor = 30.0, direction = Direction.HIGHER_IS_BETTER)
        assertEquals(0, s)
    }

    @Test
    fun `NaN value scores 0 for lower-is-better`() {
        val s = scoreLinear(value = Double.NaN, target = 2000.0, floor = 5000.0, direction = Direction.LOWER_IS_BETTER)
        assertEquals(0, s)
    }

    @Test
    fun `positive infinity value clamped per direction`() {
        // Higher-is-better: +∞ is "better than target" → 100.
        val hi = scoreLinear(value = Double.POSITIVE_INFINITY, target = 60.0, floor = 30.0, direction = Direction.HIGHER_IS_BETTER)
        assertEquals(100, hi)
        // Lower-is-better: +∞ is "worse than floor" → 0.
        val lo = scoreLinear(value = Double.POSITIVE_INFINITY, target = 2000.0, floor = 5000.0, direction = Direction.LOWER_IS_BETTER)
        assertEquals(0, lo)
    }

    @Test
    fun `negative infinity value clamped per direction`() {
        // Higher-is-better: -∞ is "worse than floor" → 0.
        val hi = scoreLinear(value = Double.NEGATIVE_INFINITY, target = 60.0, floor = 30.0, direction = Direction.HIGHER_IS_BETTER)
        assertEquals(0, hi)
        // Lower-is-better: -∞ is "better than target" → 100.
        val lo = scoreLinear(value = Double.NEGATIVE_INFINITY, target = 2000.0, floor = 5000.0, direction = Direction.LOWER_IS_BETTER)
        assertEquals(100, lo)
    }
}
