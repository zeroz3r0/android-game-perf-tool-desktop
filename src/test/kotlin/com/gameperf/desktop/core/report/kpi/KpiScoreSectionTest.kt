package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Band
import com.gameperf.desktop.core.kpi.Category
import com.gameperf.desktop.core.kpi.CategoryScore
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiScore
import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.core.kpi.Phase
import com.gameperf.desktop.core.kpi.PhaseScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T3.1 — KPI score section ([renderKpiScoreSection]).
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: KPI Score
 * Section (overall + phases + categories cards). Band CSS classes delegate
 * to [KpiBandColors.cssClassFor] (single-source).
 *
 * Pure: deterministic, no I/O.
 */
class KpiScoreSectionTest {

    private fun amberReport() = KpiScoreReport(
        sessionScore = 72,
        sessionBand = Band.AMBER,
        phases = listOf(
            PhaseScore(
                phase = Phase.GAMEPLAY, score = 72, band = Band.AMBER,
                kpiScores = listOf(
                    KpiScore(KpiId.FPS_AVG, Phase.GAMEPLAY, 50.0, 70, -10.0, Band.AMBER),
                ),
            ),
        ),
        categories = listOf(
            CategoryScore(Category.Smoothness, 72, Band.AMBER),
        ),
    )

    @Test
    fun `renders section anchor`() {
        val html = renderKpiScoreSection(amberReport())
        assertTrue(
            "id=\"sec-kpi-scoring\"" in html,
            "expected section anchor; got:\n$html",
        )
    }

    @Test
    fun `renders overall score in X over 100 form`() {
        val html = renderKpiScoreSection(amberReport())
        assertTrue("72/100" in html, "expected '72/100' in overall card; got:\n$html")
    }

    @Test
    fun `renders overall band css class`() {
        val html = renderKpiScoreSection(amberReport())
        assertTrue(
            "kpi-band-amber" in html,
            "expected 'kpi-band-amber' on the overall card; got:\n$html",
        )
    }

    @Test
    fun `three green phases render three rows with green band class`() {
        val report = KpiScoreReport(
            sessionScore = 92,
            sessionBand = Band.GREEN,
            phases = listOf(
                PhaseScore(Phase.APP_STARTUP, 95, Band.GREEN, emptyList()),
                PhaseScore(Phase.GAMEPLAY, 90, Band.GREEN, emptyList()),
                PhaseScore(Phase.INTERSTITIAL_AD, 92, Band.GREEN, emptyList()),
            ),
            categories = emptyList(),
        )
        val html = renderKpiScoreSection(report)
        // count occurrences of kpi-band-green (overall + 3 phases = 4)
        val count = "kpi-band-green".toRegex().findAll(html).count()
        assertTrue(count >= 4, "expected at least 4 occurrences of kpi-band-green (overall + 3 phases); got $count\n$html")
    }

    @Test
    fun `every category produces a card`() {
        val report = KpiScoreReport(
            sessionScore = 80,
            sessionBand = Band.GREEN,
            phases = emptyList(),
            categories = listOf(
                CategoryScore(Category.Smoothness, 90, Band.GREEN),
                CategoryScore(Category.Resource, 85, Band.GREEN),
                CategoryScore(Category.Thermal, 70, Band.AMBER),
                CategoryScore(Category.Stability, 75, Band.AMBER),
                CategoryScore(Category.Responsiveness, 50, Band.RED),
            ),
        )
        val html = renderKpiScoreSection(report)
        for (cat in Category.values()) {
            assertTrue(cat.name in html, "expected category '${cat.name}' in cards; got:\n$html")
        }
        // Match `kpi-category-card` but NOT `kpi-category-cards` (parent wrapper).
        assertEquals(
            5,
            Regex("kpi-category-card(?!s)").findAll(html).count(),
            "expected exactly 5 category cards; got:\n$html",
        )
    }
}
