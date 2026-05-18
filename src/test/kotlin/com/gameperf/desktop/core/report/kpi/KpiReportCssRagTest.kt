package com.gameperf.desktop.core.report.kpi

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 2.3 — CSS bundle contains the new RAG band layout classes.
 *
 * Asserts presence of the four classes added for the per-card RAG pill:
 *  - `.kpi-card-band` (base inline-flex layout)
 *  - `.kpi-card-band.kpi-band-green` (background tint variant)
 *  - `.kpi-card-band.kpi-band-amber`
 *  - `.kpi-card-band.kpi-band-red`
 *
 * Background tints MUST reuse `KpiBandColors` hex constants via string
 * interpolation — never inline new hex literals (CLAUDE.md anti-duplication
 * + existing `KpiBandColorsSingleSourceTest` enforces this).
 *
 * @since v4.7 (html-report-rag-bands)
 */
class KpiReportCssRagTest {

    @Test
    fun `bundle contains base kpi-card-band class`() {
        assertTrue(
            KPI_CSS.contains(".kpi-card-band"),
            "expected `.kpi-card-band` base class in KPI_CSS",
        )
    }

    @Test
    fun `bundle contains tinted background variant for each band`() {
        assertTrue(
            KPI_CSS.contains(".kpi-card-band.kpi-band-green"),
            "expected `.kpi-card-band.kpi-band-green` variant in KPI_CSS",
        )
        assertTrue(
            KPI_CSS.contains(".kpi-card-band.kpi-band-amber"),
            "expected `.kpi-card-band.kpi-band-amber` variant in KPI_CSS",
        )
        assertTrue(
            KPI_CSS.contains(".kpi-card-band.kpi-band-red"),
            "expected `.kpi-card-band.kpi-band-red` variant in KPI_CSS",
        )
    }
}
