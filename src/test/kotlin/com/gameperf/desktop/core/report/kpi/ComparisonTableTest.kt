package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Band
import com.gameperf.desktop.core.kpi.Category
import com.gameperf.desktop.core.kpi.DeviceTier
import com.gameperf.desktop.core.kpi.Direction
import com.gameperf.desktop.core.kpi.Kpi
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiScore
import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.core.kpi.Phase
import com.gameperf.desktop.core.kpi.PhaseScore
import com.gameperf.desktop.core.kpi.Threshold
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T3.2 — Comparison table ([renderComparisonTable]).
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: Comparison
 * Table. Columns: `KPI / Actual / Target / Delta / Band`. Delta sign comes
 * from `ComparisonEngine.delta` so POSITIVE is always "better than target"
 * regardless of [Direction].
 *
 * Pure: deterministic, no I/O.
 */
class ComparisonTableTest {

    private fun synthCatalog(vararg entries: Kpi): List<Kpi> = entries.toList()

    private fun reportWith(vararg scores: KpiScore): KpiScoreReport {
        val byPhase = scores.groupBy { it.phase }
        return KpiScoreReport(
            sessionScore = 0,
            sessionBand = Band.RED,
            phases = byPhase.map { (phase, list) ->
                PhaseScore(phase, 0, Band.RED, list)
            },
            categories = emptyList(),
        )
    }

    @Test
    fun `HIGHER_IS_BETTER row shows actual target and negative delta when below target`() {
        val kpi = Kpi(
            id = KpiId.FPS_AVG,
            unit = "fps",
            category = Category.Smoothness,
            direction = Direction.HIGHER_IS_BETTER,
            thresholds = mapOf(DeviceTier.MID to Threshold(target = 60.0, floor = 30.0)),
            sourceCitation = "test",
        )
        val report = reportWith(
            KpiScore(KpiId.FPS_AVG, Phase.GAMEPLAY, 55.0, 80, -5.0, Band.AMBER),
        )
        val html = renderComparisonTable(report, synthCatalog(kpi), DeviceTier.MID)
        assertTrue("FPS_AVG" in html, "expected FPS_AVG row label; got:\n$html")
        assertTrue("55" in html, "expected actual value 55; got:\n$html")
        assertTrue("60" in html, "expected target value 60; got:\n$html")
        assertTrue("-5" in html, "expected delta '-5'; got:\n$html")
        assertTrue("kpi-band-amber" in html, "expected band CSS class; got:\n$html")
    }

    @Test
    fun `LOWER_IS_BETTER row shows direction-aware delta sign`() {
        val kpi = Kpi(
            id = KpiId.FRAME_TIME_P99,
            unit = "ms",
            category = Category.Smoothness,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(DeviceTier.MID to Threshold(target = 16.67, floor = 33.3)),
            sourceCitation = "test",
        )
        val report = reportWith(
            KpiScore(KpiId.FRAME_TIME_P99, Phase.GAMEPLAY, 20.0, 60, -3.33, Band.AMBER),
        )
        val html = renderComparisonTable(report, synthCatalog(kpi), DeviceTier.MID)
        assertTrue("FRAME_TIME_P99" in html)
        assertTrue("20" in html, "expected actual; got:\n$html")
        // delta = 16.67 - 20.0 = -3.33 (LOWER_IS_BETTER: positive means better)
        assertTrue(
            "-3.33" in html,
            "expected LOWER_IS_BETTER delta '-3.33'; got:\n$html",
        )
    }

    @Test
    fun `null rawValue renders N over D and kpi-na class with no band`() {
        val kpi = Kpi(
            id = KpiId.GPU_AVG,
            unit = "%",
            category = Category.Resource,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(DeviceTier.MID to Threshold(target = 50.0, floor = 90.0)),
            sourceCitation = "test",
        )
        val report = reportWith(
            KpiScore(KpiId.GPU_AVG, Phase.GAMEPLAY, null, 0, 0.0, Band.RED),
        )
        val html = renderComparisonTable(report, synthCatalog(kpi), DeviceTier.MID)
        assertTrue("N/D" in html, "expected 'N/D' placeholder; got:\n$html")
        assertTrue("kpi-na" in html, "expected 'kpi-na' class; got:\n$html")
        // No band class should decorate a missing-data row
        assertFalse(
            "kpi-band-red" in html,
            "missing-data row must not carry a band CSS class; got:\n$html",
        )
    }

    @Test
    fun `tier fallback uses MID when requested tier is absent`() {
        val kpi = Kpi(
            id = KpiId.FPS_AVG,
            unit = "fps",
            category = Category.Smoothness,
            direction = Direction.HIGHER_IS_BETTER,
            thresholds = mapOf(DeviceTier.MID to Threshold(target = 45.0, floor = 24.0)),
            sourceCitation = "test",
        )
        val report = reportWith(
            KpiScore(KpiId.FPS_AVG, Phase.GAMEPLAY, 40.0, 80, -5.0, Band.AMBER),
        )
        val html = renderComparisonTable(report, synthCatalog(kpi), DeviceTier.TOP)
        // TOP missing → MID target 45 used
        assertTrue("45" in html, "expected MID target 45 used as fallback; got:\n$html")
    }

    @Test
    fun `empty report still renders a table header`() {
        val empty = KpiScoreReport(0, Band.RED, emptyList(), emptyList())
        val html = renderComparisonTable(empty, emptyList(), DeviceTier.MID)
        assertTrue("<table" in html, "expected a <table> wrapper even when empty; got:\n$html")
    }
}
