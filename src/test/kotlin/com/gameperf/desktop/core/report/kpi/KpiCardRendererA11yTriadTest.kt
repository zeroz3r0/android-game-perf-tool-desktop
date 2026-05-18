package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Band
import com.gameperf.desktop.core.kpi.DeviceTier
import com.gameperf.desktop.core.kpi.KpiCatalog
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.report.kpi.i18n.ReportStrings
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 2.4 — A11y triad assertion: every RAG band MUST encode severity
 * across THREE independent channels (color CSS class, shape glyph, text
 * label) so users with color-vision differences (and grayscale renders)
 * still receive the signal.
 *
 * Spec coverage: RAG-002 "Banda no-color-only (a11y)".
 *
 * The test is structured as one method per band, each asserting all three
 * triad channels separately so a regression in any single channel produces
 * an unambiguous failure message.
 *
 * @since v4.7 (html-report-rag-bands)
 */
class KpiCardRendererA11yTriadTest {

    private val fpsAvg = KpiCatalog.byId(KpiId.FPS_AVG) // HIGHER_IS_BETTER, MID target=45 floor=24

    @Test
    fun `GREEN band exposes color css class AND shape AND text`() {
        val html = KpiCardRenderer.render(kpi = fpsAvg, value = 60.0, tier = DeviceTier.MID)
        // (1) Color channel
        assertTrue(
            html.contains(KpiBandColors.cssClassFor(Band.GREEN)),
            "GREEN: missing color css class in: $html",
        )
        // (2) Shape channel
        assertTrue(html.contains("●"), "GREEN: missing shape ● in: $html")
        // (3) Text channel
        assertTrue(
            html.contains(ReportStrings.BAND_GREEN),
            "GREEN: missing label '${ReportStrings.BAND_GREEN}' in: $html",
        )
    }

    @Test
    fun `AMBER band exposes color css class AND shape AND text`() {
        // mid-zone value → AMBER (score in [60,80))
        val html = KpiCardRenderer.render(kpi = fpsAvg, value = 38.0, tier = DeviceTier.MID)
        assertTrue(
            html.contains(KpiBandColors.cssClassFor(Band.AMBER)),
            "AMBER: missing color css class in: $html",
        )
        assertTrue(html.contains("▲"), "AMBER: missing shape ▲ in: $html")
        assertTrue(
            html.contains(ReportStrings.BAND_AMBER),
            "AMBER: missing label '${ReportStrings.BAND_AMBER}' in: $html",
        )
    }

    @Test
    fun `RED band exposes color css class AND shape AND text`() {
        val html = KpiCardRenderer.render(kpi = fpsAvg, value = 10.0, tier = DeviceTier.MID)
        assertTrue(
            html.contains(KpiBandColors.cssClassFor(Band.RED)),
            "RED: missing color css class in: $html",
        )
        assertTrue(html.contains("■"), "RED: missing shape ■ in: $html")
        assertTrue(
            html.contains(ReportStrings.BAND_RED),
            "RED: missing label '${ReportStrings.BAND_RED}' in: $html",
        )
    }
}
