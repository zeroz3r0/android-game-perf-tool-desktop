package com.gameperf.desktop.core.kpi

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 1.2 — Single-source frame-time budget constants.
 *
 * Spec coverage: `sdd/html-report-rag-bands/spec` — Requirement RAG-005.
 * Constants are mathematical laws (`1000 / targetFps`) and live ONLY in
 * `core/kpi/FrameBudgets.kt`. Architectural guard:
 * `FrameBudgetsSingleSourceTest` (Phase 1.4).
 *
 * @since v4.7 (html-report-rag-bands)
 */
class FrameBudgetsTest {

    @Test
    fun `FPS_60_MS equals 16 dot 6`() {
        assertEquals(16.6, FrameBudgets.FPS_60_MS, 0.0001)
    }

    @Test
    fun `FPS_30_MS equals 33 dot 3`() {
        assertEquals(33.3, FrameBudgets.FPS_30_MS, 0.0001)
    }

    @Test
    fun `FPS_120_MS equals 8 dot 3`() {
        assertEquals(8.3, FrameBudgets.FPS_120_MS, 0.0001)
    }

    @Test
    fun `lineFor 60 is approx canonical 60 fps budget`() {
        // 1000/60 = 16.666... — assert within 0.1 of 16.6
        assertTrue(
            abs(FrameBudgets.lineFor(60) - 16.6) < 0.1,
            "lineFor(60) was ${FrameBudgets.lineFor(60)}, expected near 16.6",
        )
    }

    @Test
    fun `lineFor 90 is approx 11 dot 11`() {
        assertEquals(11.11, FrameBudgets.lineFor(90), 0.01)
    }

    @Test
    fun `lineFor 144 is approx 6 dot 94`() {
        assertEquals(6.944, FrameBudgets.lineFor(144), 0.01)
    }
}
