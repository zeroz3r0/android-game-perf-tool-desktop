package com.gameperf.desktop.core.kpi

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 1.1 — RAG bands: pure `LinearScoring.bandFor(value, threshold, direction)`.
 *
 * Boundary semantics inherit from canonical `ComparisonEngine.band` trichotomy
 * (`score >= 80 → GREEN`, `60 <= score < 80 → AMBER`, `score < 60 → RED`). The
 * scorer (`scoreLinear`) maps `value == target → 100 → GREEN`, `value == floor → 0 → RED`.
 *
 * Pure: zero I/O, deterministic. No null handling here — callers are responsible
 * for filtering null/-1L sentinels BEFORE invoking `bandFor` (matches `scoreLinear`).
 *
 * @since v4.7 (html-report-rag-bands)
 */
class LinearScoringBandForTest {

    // ── LOWER_IS_BETTER (frame-time-style) ───────────────────────────────────

    @Test
    fun `lower-is-better value at target returns GREEN`() {
        // value == target → score 100 → GREEN
        val band = bandFor(
            value = 20.0,
            threshold = Threshold(target = 20.0, floor = 33.3),
            direction = Direction.LOWER_IS_BETTER,
        )
        assertEquals(Band.GREEN, band)
    }

    @Test
    fun `lower-is-better mid-zone returns AMBER`() {
        // (20+33.3)/2 ~= 26.65 → score ~50 → that would be RED.
        // For AMBER (score in [60,80)), pick a value closer to target.
        // span = 13.3. score=70 → (floor-value)/span*100 = 70 → value = floor - 0.7*13.3 = 33.3 - 9.31 = 23.99.
        val band = bandFor(
            value = 24.0,
            threshold = Threshold(target = 20.0, floor = 33.3),
            direction = Direction.LOWER_IS_BETTER,
        )
        assertEquals(Band.AMBER, band)
    }

    @Test
    fun `lower-is-better value at floor returns RED`() {
        // value == floor → score 0 → RED
        val band = bandFor(
            value = 33.3,
            threshold = Threshold(target = 20.0, floor = 33.3),
            direction = Direction.LOWER_IS_BETTER,
        )
        assertEquals(Band.RED, band)
    }

    @Test
    fun `lower-is-better value beyond floor clamps to RED`() {
        val band = bandFor(
            value = 99.0,
            threshold = Threshold(target = 20.0, floor = 33.3),
            direction = Direction.LOWER_IS_BETTER,
        )
        assertEquals(Band.RED, band)
    }

    @Test
    fun `lower-is-better value below target clamps to GREEN`() {
        val band = bandFor(
            value = 5.0,
            threshold = Threshold(target = 20.0, floor = 33.3),
            direction = Direction.LOWER_IS_BETTER,
        )
        assertEquals(Band.GREEN, band)
    }

    // ── HIGHER_IS_BETTER (FPS-style) ─────────────────────────────────────────

    @Test
    fun `higher-is-better value at target returns GREEN`() {
        val band = bandFor(
            value = 60.0,
            threshold = Threshold(target = 60.0, floor = 30.0),
            direction = Direction.HIGHER_IS_BETTER,
        )
        assertEquals(Band.GREEN, band)
    }

    @Test
    fun `higher-is-better mid-zone returns AMBER`() {
        // span = 30. score=70 → (value-floor)/span*100 = 70 → value = floor+0.7*30 = 51.
        val band = bandFor(
            value = 51.0,
            threshold = Threshold(target = 60.0, floor = 30.0),
            direction = Direction.HIGHER_IS_BETTER,
        )
        assertEquals(Band.AMBER, band)
    }

    @Test
    fun `higher-is-better value at floor returns RED`() {
        val band = bandFor(
            value = 30.0,
            threshold = Threshold(target = 60.0, floor = 30.0),
            direction = Direction.HIGHER_IS_BETTER,
        )
        assertEquals(Band.RED, band)
    }

    // ── Defensive: NaN propagates to RED via scoreLinear=0 ───────────────────

    @Test
    fun `NaN value returns RED`() {
        val band = bandFor(
            value = Double.NaN,
            threshold = Threshold(target = 60.0, floor = 30.0),
            direction = Direction.HIGHER_IS_BETTER,
        )
        assertEquals(Band.RED, band)
    }
}
