package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Band
import com.gameperf.desktop.core.kpi.Category
import com.gameperf.desktop.core.kpi.CategoryScore
import com.gameperf.desktop.core.kpi.DeviceTier
import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.core.kpi.Phase
import com.gameperf.desktop.core.kpi.PhaseScore
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2.2 / 2.4 — Integration: KPI score section emits RAG band pills with
 * the a11y triad (color CSS class + shape glyph + castellano label).
 *
 * Also verifies the additive `deviceTier` field on [KpiScoreReport] preserves
 * backward compatibility for legacy `.gameperf` JSON payloads (kotlinx-serialization
 * fills the default when the field is missing).
 *
 * Spec coverage: RAG-001, RAG-002.
 *
 * @since v4.7 (html-report-rag-bands)
 */
class KpiScoreSectionRagIntegrationTest {

    private fun greenReport(tier: DeviceTier = DeviceTier.MID) = KpiScoreReport(
        sessionScore = 92,
        sessionBand = Band.GREEN,
        phases = listOf(
            PhaseScore(Phase.GAMEPLAY, 95, Band.GREEN, emptyList()),
        ),
        categories = listOf(
            CategoryScore(Category.Smoothness, 90, Band.GREEN),
        ),
        deviceTier = tier,
    )

    @Test
    fun `green session renders kpi-card-band with kpi-band-green somewhere`() {
        val html = renderKpiScoreSection(greenReport())
        assertTrue(
            html.contains("kpi-card-band"),
            "expected kpi-card-band pill in section; got:\n$html",
        )
        assertTrue(
            html.contains("kpi-band-green"),
            "expected kpi-band-green class in section; got:\n$html",
        )
        // A11y triad — shape glyph for GREEN must be present.
        assertTrue(
            html.contains("●"),
            "expected GREEN shape ● in section; got:\n$html",
        )
        // A11y triad — castellano label must be present.
        assertTrue(
            html.contains("Bien"),
            "expected 'Bien' label in section; got:\n$html",
        )
    }

    @Test
    fun `red category card renders red triad`() {
        val report = KpiScoreReport(
            sessionScore = 30,
            sessionBand = Band.RED,
            phases = emptyList(),
            categories = listOf(
                CategoryScore(Category.Resource, 25, Band.RED),
            ),
            deviceTier = DeviceTier.MID,
        )
        val html = renderKpiScoreSection(report)
        assertTrue(html.contains("kpi-band-red"), "expected kpi-band-red in section; got:\n$html")
        assertTrue(html.contains("■"), "expected RED shape ■ in section; got:\n$html")
        assertTrue(html.contains("Mal"), "expected 'Mal' label in section; got:\n$html")
    }

    @Test
    fun `KpiScoreReport round-trips with deviceTier field`() {
        val json = Json { encodeDefaults = true }
        val original = greenReport(tier = DeviceTier.TOP)
        val encoded = json.encodeToString(KpiScoreReport.serializer(), original)
        val decoded = json.decodeFromString(KpiScoreReport.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals(DeviceTier.TOP, decoded.deviceTier)
        assertTrue(encoded.contains("TOP"), "expected encoded JSON to contain TOP tier; got: $encoded")
    }

    @Test
    fun `legacy JSON without deviceTier decodes with default MID`() {
        // Simulate a `.gameperf` payload produced before v4.7 — no `deviceTier` field.
        val legacy = """
            {
              "sessionScore": 80,
              "sessionBand": "GREEN",
              "phases": [],
              "categories": []
            }
        """.trimIndent()
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(KpiScoreReport.serializer(), legacy)
        assertEquals(DeviceTier.MID, decoded.deviceTier)
        assertEquals(80, decoded.sessionScore)
        assertEquals(Band.GREEN, decoded.sessionBand)
    }
}
