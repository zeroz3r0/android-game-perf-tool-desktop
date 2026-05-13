package com.gameperf.desktop.core.report.kpi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * T1.1 — Frame-time percentiles for the "p1 / p0.1 pills" feature.
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: p1 + p0.1
 * Frame-Time Percentile Pills. The renderer shows the p1 pill only when
 * `samples.size >= 100` and the p0.1 pill only when `samples.size >= 1000`;
 * below those thresholds the helper returns `null`.
 *
 * Pure: deterministic, no I/O.
 */
class FrameTimePercentilesTest {

    // ─── p1 sample-size threshold ──────────────────────────────────────────

    @Test
    fun `p1 returns null when fewer than 100 samples`() {
        assertNull(FrameTimePercentiles.p1((1..99).map { it.toDouble() }))
    }

    @Test
    fun `p1 returns non-null with exactly 100 samples`() {
        assertNotNull(FrameTimePercentiles.p1((1..100).map { it.toDouble() }))
    }

    // ─── p1 sorted-list value ──────────────────────────────────────────────

    @Test
    fun `p1 of 1 to 1000 sorted returns 990`() {
        val samples = (1..1000).map { it.toDouble() }
        // p1 = 99th-percentile-from-bottom = top 1% bound. For 1000 samples,
        // the index is `size - size/100 - 1 = 1000 - 10 - 1 = 989` → value 990.0.
        assertEquals(990.0, FrameTimePercentiles.p1(samples))
    }

    @Test
    fun `p1 ignores input order (sorts internally)`() {
        val ascending = (1..1000).map { it.toDouble() }
        val shuffled = ascending.shuffled(java.util.Random(42))
        assertEquals(FrameTimePercentiles.p1(ascending), FrameTimePercentiles.p1(shuffled))
    }

    // ─── p01 sample-size threshold ─────────────────────────────────────────

    @Test
    fun `p01 returns null when fewer than 1000 samples`() {
        assertNull(FrameTimePercentiles.p01((1..999).map { it.toDouble() }))
    }

    @Test
    fun `p01 returns non-null with exactly 1000 samples`() {
        assertNotNull(FrameTimePercentiles.p01((1..1000).map { it.toDouble() }))
    }

    // ─── p01 sorted-list value ─────────────────────────────────────────────

    @Test
    fun `p01 of 1 to 1000 sorted returns 999`() {
        val samples = (1..1000).map { it.toDouble() }
        // p0.1 = 99.9th-percentile bound. For 1000 samples,
        // `size - size/1000 - 1 = 1000 - 1 - 1 = 998` → value 999.0.
        assertEquals(999.0, FrameTimePercentiles.p01(samples))
    }
}
