package com.gameperf.desktop.core.kpi

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T0.1 — Ensures every public KPI data class is `@Serializable` so the
 * upcoming `DataExportButtons` / `KpiCsvSerializer` can serialize a
 * [KpiScoreReport] via kotlinx-serialization without runtime errors.
 *
 * Pure unit test: round-trips through [Json] and compares structural
 * equality (`==`), which exercises both `encodeToString` and `decodeFromString`.
 * No I/O, no mocks (CLAUDE.md "tests puros sin mocks").
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: CSV + JSON
 * Download Buttons, Scenario "JSON download is valid kotlinx-serialization
 * round-trip" (which downstream tests depend on).
 */
class KpiMetadataSerializationTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `KpiScoreReport round-trips through kotlinx-serialization`() {
        val input = KpiScoreReport(
            sessionScore = 72,
            sessionBand = Band.AMBER,
            phases = listOf(
                PhaseScore(
                    phase = Phase.GAMEPLAY,
                    score = 80,
                    band = Band.GREEN,
                    kpiScores = listOf(
                        KpiScore(
                            id = KpiId.FPS_AVG,
                            phase = Phase.GAMEPLAY,
                            rawValue = 55.0,
                            score = 78,
                            delta = -5.0,
                            band = Band.AMBER,
                        ),
                        KpiScore(
                            id = KpiId.GPU_AVG,
                            phase = Phase.GAMEPLAY,
                            rawValue = null,
                            score = 0,
                            delta = 0.0,
                            band = Band.RED,
                        ),
                    ),
                ),
            ),
            categories = listOf(
                CategoryScore(category = Category.Smoothness, score = 85, band = Band.GREEN),
                CategoryScore(category = Category.Resource, score = 60, band = Band.AMBER),
            ),
        )

        val encoded = json.encodeToString(input)
        val decoded = json.decodeFromString<KpiScoreReport>(encoded)

        assertEquals(input, decoded)
        // Sanity: the JSON contains the band name as a string (default enum encoding).
        assertTrue(
            encoded.contains("AMBER"),
            "expected sessionBand enum to encode as its name; got: $encoded",
        )
    }
}
