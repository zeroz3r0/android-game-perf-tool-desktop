package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.DeviceTier
import com.gameperf.desktop.core.kpi.KpiCatalog
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.report.kpi.i18n.ReportStrings
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 2.1 — `KpiCardRenderer.render` HTML output assertions.
 *
 * Spec coverage: `sdd/html-report-rag-bands/spec` — Requirement RAG-001 + RAG-002.
 *
 * A11y triad (NEVER color alone): every rendered band MUST contain
 *   (1) the CSS class `kpi-band-{green|amber|red}`,
 *   (2) the shape glyph `●▲■`,
 *   (3) the castellano label from `ReportStrings.BAND_*`.
 *
 * @since v4.7 (html-report-rag-bands)
 */
class KpiCardRendererTest {

    private val fpsAvg = KpiCatalog.byId(KpiId.FPS_AVG) // HIGHER_IS_BETTER, MID target=45 floor=24

    @Test
    fun `value at target renders GREEN with triad shape and label`() {
        val html = KpiCardRenderer.render(kpi = fpsAvg, value = 45.0, tier = DeviceTier.MID)
        assertTrue(html.contains("kpi-band-green"), "expected css class kpi-band-green in: $html")
        assertTrue(html.contains("●"), "expected GREEN shape ● in: $html")
        assertTrue(
            html.contains(ReportStrings.BAND_GREEN),
            "expected label '${ReportStrings.BAND_GREEN}' in: $html",
        )
        assertTrue(html.contains("kpi-card-band"), "expected base class kpi-card-band in: $html")
    }

    @Test
    fun `value in mid-zone renders AMBER with triad shape and label`() {
        // FPS_AVG MID target=45 floor=24, span=21. AMBER scores in [60,80) →
        // value range (24 + 0.6*21, 24 + 0.8*21) = (36.6, 40.8). Pick 38.
        val html = KpiCardRenderer.render(kpi = fpsAvg, value = 38.0, tier = DeviceTier.MID)
        assertTrue(html.contains("kpi-band-amber"), "expected kpi-band-amber in: $html")
        assertTrue(html.contains("▲"), "expected AMBER shape ▲ in: $html")
        assertTrue(html.contains(ReportStrings.BAND_AMBER), "expected '${ReportStrings.BAND_AMBER}' in: $html")
    }

    @Test
    fun `value at floor renders RED with triad shape and label`() {
        val html = KpiCardRenderer.render(kpi = fpsAvg, value = 24.0, tier = DeviceTier.MID)
        assertTrue(html.contains("kpi-band-red"), "expected kpi-band-red in: $html")
        assertTrue(html.contains("■"), "expected RED shape ■ in: $html")
        assertTrue(html.contains(ReportStrings.BAND_RED), "expected '${ReportStrings.BAND_RED}' in: $html")
    }

    @Test
    fun `null value renders kpi-na placeholder`() {
        val html = KpiCardRenderer.render(kpi = fpsAvg, value = null, tier = DeviceTier.MID)
        assertTrue(html.contains("kpi-na"), "expected kpi-na in: $html")
        assertTrue(html.contains("—"), "expected em-dash placeholder in: $html")
    }
}
