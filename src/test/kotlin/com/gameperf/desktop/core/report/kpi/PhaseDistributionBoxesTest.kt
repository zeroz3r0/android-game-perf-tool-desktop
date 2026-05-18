package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Band
import com.gameperf.desktop.core.kpi.DeviceTier
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiScore
import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.core.kpi.Phase
import com.gameperf.desktop.core.kpi.PhaseScore
import com.gameperf.desktop.core.report.kpi.i18n.ReportStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 4 (sdd/html-report-rag-bands) — per-phase distribution boxes.
 *
 * Verifies `renderPhaseDistributionBoxes(report, tier)`:
 *  - sorts phases by median FPS descending (best first)
 *  - skips phases whose FPS_AVG raw value is null (proxy for `frameCount < 5`
 *    given the existing `PhaseScore` model lacks a per-phase frame counter)
 *  - returns `""` when no eligible phases exist (RAG-010 backward compat)
 *  - colors each box's band class via `LinearScoring.bandFor` on the median
 *    FPS against `KpiCatalog.byId(FPS_AVG).thresholds[tier]`
 *
 * Pure: deterministic, no I/O.
 *
 * @since v4.7 (html-report-rag-bands — RAG-004)
 */
class PhaseDistributionBoxesTest {

    private fun fpsAvg(value: Double?, phase: Phase): KpiScore =
        KpiScore(
            id = KpiId.FPS_AVG,
            phase = phase,
            rawValue = value,
            score = 0,
            delta = 0.0,
            band = Band.RED,
        )

    private fun phaseWithFps(phase: Phase, medianFps: Double?): PhaseScore =
        PhaseScore(
            phase = phase,
            score = if (medianFps == null) 0 else medianFps.toInt(),
            band = Band.AMBER,
            kpiScores = listOf(fpsAvg(medianFps, phase)),
        )

    private fun reportOf(vararg phases: PhaseScore, tier: DeviceTier = DeviceTier.MID): KpiScoreReport =
        KpiScoreReport(
            sessionScore = 0,
            sessionBand = Band.AMBER,
            phases = phases.toList(),
            categories = emptyList(),
            deviceTier = tier,
        )

    @Test
    fun `empty phases returns empty string`() {
        val html = renderPhaseDistributionBoxes(reportOf(), DeviceTier.MID)
        assertEquals("", html, "no phases must produce empty string, not empty section")
    }

    @Test
    fun `phases all with null FPS_AVG are skipped and return empty string`() {
        // Proxy for the spec's `frameCount < 5` rule: phases with no FPS data
        // cannot contribute statistically meaningful boxes.
        val html = renderPhaseDistributionBoxes(
            reportOf(
                phaseWithFps(Phase.CINEMATIC, null),
                phaseWithFps(Phase.GAMEPLAY, null),
            ),
            DeviceTier.MID,
        )
        assertEquals("", html, "all-null phases must collapse to empty string (RAG-010)")
    }

    @Test
    fun `phases sorted by median FPS descending`() {
        val html = renderPhaseDistributionBoxes(
            reportOf(
                phaseWithFps(Phase.CINEMATIC, 30.0),   // worst
                phaseWithFps(Phase.SCREEN_NAV, 60.0),  // best
                phaseWithFps(Phase.GAMEPLAY, 58.0),    // middle
            ),
            DeviceTier.MID,
        )
        assertTrue("Distribucion por fase" in html, "expected section header from ReportStrings")
        // Order: SCREEN_NAV (60) → GAMEPLAY (58) → CINEMATIC (30)
        val idxNav = html.indexOf("SCREEN_NAV")
        val idxGame = html.indexOf("GAMEPLAY")
        val idxCin = html.indexOf("CINEMATIC")
        assertTrue(idxNav in 0 until idxGame, "SCREEN_NAV (60) must precede GAMEPLAY (58); got nav=$idxNav game=$idxGame")
        assertTrue(idxGame in 0 until idxCin, "GAMEPLAY (58) must precede CINEMATIC (30); got game=$idxGame cin=$idxCin")
    }

    @Test
    fun `green phase renders kpi-band-green class`() {
        // FPS_AVG threshold MID = target 60, floor 30 (catalog). Median 60 → GREEN.
        val html = renderPhaseDistributionBoxes(
            reportOf(phaseWithFps(Phase.GAMEPLAY, 60.0)),
            DeviceTier.MID,
        )
        assertTrue("kpi-band-green" in html, "median 60 fps at MID tier must yield kpi-band-green; got:\n$html")
        assertTrue("phase-dist-box" in html, "expected phase-dist-box class")
    }

    @Test
    fun `red phase renders kpi-band-red class`() {
        // Median 25 (well below floor 30) → RED.
        val html = renderPhaseDistributionBoxes(
            reportOf(phaseWithFps(Phase.CINEMATIC, 25.0)),
            DeviceTier.MID,
        )
        assertTrue("kpi-band-red" in html, "median 25 fps at MID tier must yield kpi-band-red; got:\n$html")
    }

    @Test
    fun `section header is castellano single-source label`() {
        val html = renderPhaseDistributionBoxes(
            reportOf(phaseWithFps(Phase.GAMEPLAY, 55.0)),
            DeviceTier.MID,
        )
        assertTrue(
            ReportStrings.PHASE_DIST_TITLE in html,
            "expected PHASE_DIST_TITLE='${ReportStrings.PHASE_DIST_TITLE}'; got:\n$html",
        )
        assertTrue(ReportStrings.BOX_MEDIAN in html, "expected BOX_MEDIAN label")
        assertFalse("Distribución por fase" in html, "must NOT contain accented form (UTF-8 mojibake guard)")
    }

    @Test
    fun `mixed null and non-null phases skips only the null ones`() {
        val html = renderPhaseDistributionBoxes(
            reportOf(
                phaseWithFps(Phase.CINEMATIC, null),     // skipped
                phaseWithFps(Phase.GAMEPLAY, 55.0),      // kept
            ),
            DeviceTier.MID,
        )
        assertTrue("GAMEPLAY" in html, "non-null phase must remain")
        assertFalse("CINEMATIC" in html, "null-FPS phase must be skipped")
    }
}
