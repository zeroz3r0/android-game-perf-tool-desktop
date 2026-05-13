package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Band

/**
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║  SINGLE SOURCE OF TRUTH for KPI [Band] → hex color and CSS class.      ║
 * ║                                                                        ║
 * ║  DO NOT define band hex values (`#22c55e`, `#f59e0b`, `#dc2626`,       ║
 * ║  `#10b981`, `#ef4444`, etc.) ANYWHERE ELSE in `core/report/kpi/`.      ║
 * ║  Every renderer in this package MUST call [forBand] or                 ║
 * ║  [cssClassFor] and let the CSS bundle (`KpiReportCss`) reference the   ║
 * ║  three `kpi-band-*` classes.                                           ║
 * ║                                                                        ║
 * ║  This mirrors the anti-duplication rule learned the hard way for       ║
 * ║  `KpiCatalog`, `SdkSignatureCatalog.ALL` and `ToolResolver`            ║
 * ║  (see CLAUDE.md v4.2.13 and v4.4.0). When this rule has been broken    ║
 * ║  in the past, the same bug recurred three releases in a row.           ║
 * ║                                                                        ║
 * ║  Test `KpiBandColorsSingleSourceTest` (Phase 6) greps                  ║
 * ║  `core/report/kpi/` for hex values and asserts zero hits outside       ║
 * ║  this file.                                                            ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * @since v4.6 (shareable-html-report Block F)
 */
object KpiBandColors {

    /** Tailwind-ish `green-500`. */
    private const val HEX_GREEN: String = "#22c55e"

    /** Tailwind-ish `amber-500`. */
    private const val HEX_AMBER: String = "#f59e0b"

    /** Tailwind-ish `red-600` (slightly darker than `red-500` for contrast on white background). */
    private const val HEX_RED: String = "#dc2626"

    /**
     * Foreground hex for [band]. Use to color text or chart strokes. For
     * background fills with alpha, compose with the CSS `${hex}20` trick
     * already in use by the rest of the report template.
     */
    fun forBand(band: Band): String = when (band) {
        Band.GREEN -> HEX_GREEN
        Band.AMBER -> HEX_AMBER
        Band.RED -> HEX_RED
    }

    /**
     * Stable CSS class name for [band]. The KPI CSS bundle defines
     * `.kpi-band-green`, `.kpi-band-amber`, `.kpi-band-red` once and
     * renderers should always reference these instead of inlining `style="..."`.
     */
    fun cssClassFor(band: Band): String = when (band) {
        Band.GREEN -> "kpi-band-green"
        Band.AMBER -> "kpi-band-amber"
        Band.RED -> "kpi-band-red"
    }
}
