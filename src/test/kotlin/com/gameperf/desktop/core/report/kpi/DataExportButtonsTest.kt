package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Band
import com.gameperf.desktop.core.kpi.Category
import com.gameperf.desktop.core.kpi.CategoryScore
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiScore
import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.core.kpi.Phase
import com.gameperf.desktop.core.kpi.PhaseScore
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T4.1 — DataExportButtons renders two `<a download>` anchors with
 * base64-encoded CSV + JSON data URLs.
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: CSV + JSON
 * Download Buttons.
 *
 * Pure: deterministic, no I/O.
 */
class DataExportButtonsTest {

    private val sampleReport: KpiScoreReport = KpiScoreReport(
        sessionScore = 75,
        sessionBand = Band.AMBER,
        phases = listOf(
            PhaseScore(
                phase = Phase.GAMEPLAY,
                score = 75,
                band = Band.AMBER,
                kpiScores = listOf(
                    KpiScore(
                        id = KpiId.FPS_AVG,
                        phase = Phase.GAMEPLAY,
                        rawValue = 55.0,
                        score = 75,
                        delta = -5.0,
                        band = Band.AMBER,
                    ),
                ),
            ),
        ),
        categories = listOf(
            CategoryScore(category = Category.Smoothness, score = 75, band = Band.AMBER),
        ),
    )

    private fun anchorHref(html: String, mime: String): String {
        // Find <a ... href="data:<mime>;base64,XXXX" ...>
        val needle = "data:$mime;base64,"
        val start = html.indexOf(needle)
        require(start >= 0) { "no anchor with $mime found; html:\n$html" }
        val payloadStart = start + needle.length
        // Payload ends at the next `"`
        val end = html.indexOf('"', payloadStart)
        return html.substring(payloadStart, end)
    }

    @Test
    fun `renders both csv and json anchors in a kpi-export-buttons wrapper`() {
        val html = renderExportButtons(sampleReport, pkg = "com.example.game")
        assertTrue("kpi-export-buttons" in html, "wrapper class missing; html:\n$html")
        assertTrue("data:text/csv;base64," in html, "csv data url missing; html:\n$html")
        assertTrue("data:application/json;base64," in html, "json data url missing; html:\n$html")
        assertTrue("""download="kpi_com.example.game.csv"""" in html, "csv download filename missing")
        assertTrue("""download="kpi_com.example.game.json"""" in html, "json download filename missing")
    }

    @Test
    fun `csv anchor decodes back to spec header on first line`() {
        val html = renderExportButtons(sampleReport, pkg = "com.example.game")
        val b64 = anchorHref(html, "text/csv")
        val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
        val firstLine = decoded.lineSequence().first()
        assertEquals("phase,kpi,raw_value,score,delta,band", firstLine)
    }

    @Test
    fun `json anchor round trips back to equal KpiScoreReport`() {
        val html = renderExportButtons(sampleReport, pkg = "com.example.game")
        val b64 = anchorHref(html, "application/json")
        val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
        val parsed = Json.decodeFromString(KpiScoreReport.serializer(), decoded)
        assertEquals(sampleReport, parsed)
    }

    @Test
    fun `empty report still emits both anchors`() {
        val empty = KpiScoreReport(
            sessionScore = 0,
            sessionBand = Band.RED,
            phases = emptyList(),
            categories = emptyList(),
        )
        val html = renderExportButtons(empty, pkg = "x.y")
        assertTrue("data:text/csv;base64," in html)
        assertTrue("data:application/json;base64," in html)
        // CSV at minimum carries the header line.
        val b64 = anchorHref(html, "text/csv")
        val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
        assertTrue(decoded.startsWith("phase,kpi,raw_value,score,delta,band"))
    }
}
