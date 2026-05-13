package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Band
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiScore
import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.core.kpi.Phase
import com.gameperf.desktop.core.kpi.PhaseScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T3.3 — Per-phase breakdown ([renderPhaseBreakdown]).
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: Phase
 * Breakdown. Phases render in `Phase.ordinal` order regardless of insertion
 * order in the report; empty report renders `""` exactly.
 *
 * Pure: deterministic, no I/O.
 */
class PhaseBreakdownTest {

    private fun phase(p: Phase, vararg ids: KpiId): PhaseScore =
        PhaseScore(
            phase = p, score = 80, band = Band.GREEN,
            kpiScores = ids.map {
                KpiScore(it, p, 60.0, 80, 0.0, Band.GREEN)
            },
        )

    @Test
    fun `empty phases returns empty string exactly`() {
        val report = KpiScoreReport(0, Band.RED, emptyList(), emptyList())
        assertEquals("", renderPhaseBreakdown(report))
    }

    @Test
    fun `phases render in Phase enum declaration order`() {
        // Insert out-of-order: GAMEPLAY, APP_STARTUP, INTERSTITIAL_AD
        val report = KpiScoreReport(
            sessionScore = 0, sessionBand = Band.GREEN,
            phases = listOf(
                phase(Phase.GAMEPLAY, KpiId.FPS_AVG),
                phase(Phase.APP_STARTUP, KpiId.COLD_START_MS),
                phase(Phase.INTERSTITIAL_AD, KpiId.FROZEN_FRAMES),
            ),
            categories = emptyList(),
        )
        val html = renderPhaseBreakdown(report)
        val iStartup = html.indexOf("APP_STARTUP")
        val iAd = html.indexOf("INTERSTITIAL_AD")
        val iGame = html.indexOf("GAMEPLAY")
        // Phase enum order: APP_STARTUP(0) … INTERSTITIAL_AD(5) … GAMEPLAY(7)
        assertTrue(iStartup in 0..iAd, "APP_STARTUP must appear before INTERSTITIAL_AD; html:\n$html")
        assertTrue(iAd in 0..iGame, "INTERSTITIAL_AD must appear before GAMEPLAY; html:\n$html")
    }

    @Test
    fun `each phase row drills down into KpiScore id names`() {
        val report = KpiScoreReport(
            0, Band.GREEN,
            phases = listOf(
                phase(Phase.APP_STARTUP, KpiId.COLD_START_MS, KpiId.WARM_START_MS),
            ),
            categories = emptyList(),
        )
        val html = renderPhaseBreakdown(report)
        assertTrue("COLD_START_MS" in html, "expected drill-down COLD_START_MS; got:\n$html")
        assertTrue("WARM_START_MS" in html, "expected drill-down WARM_START_MS; got:\n$html")
    }

    @Test
    fun `output is wrapped in a phase-breakdown section`() {
        val report = KpiScoreReport(
            0, Band.GREEN,
            phases = listOf(phase(Phase.GAMEPLAY, KpiId.FPS_AVG)),
            categories = emptyList(),
        )
        val html = renderPhaseBreakdown(report)
        assertTrue("id=\"sec-phase-breakdown\"" in html, "expected section anchor; got:\n$html")
    }
}
