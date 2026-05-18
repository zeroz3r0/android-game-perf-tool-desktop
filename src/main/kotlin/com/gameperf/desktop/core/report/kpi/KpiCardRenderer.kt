package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Band
import com.gameperf.desktop.core.kpi.DeviceTier
import com.gameperf.desktop.core.kpi.Kpi
import com.gameperf.desktop.core.kpi.bandFor
import com.gameperf.desktop.core.report.kpi.i18n.ReportStrings

/**
 * Phase 2 — Per-KPI RAG band card renderer.
 *
 * Spec coverage: `sdd/html-report-rag-bands/spec` — RAG-001 + RAG-002.
 *
 * Renders a single `<div class="kpi-card-band kpi-band-X">{shape} {label}</div>`
 * using the a11y triad (color CSS class + shape glyph + castellano text label).
 * NEVER color-only — every band carries all three channels.
 *
 * Sources:
 *  - Band decision: `LinearScoring.bandFor(value, threshold, kpi.direction)`
 *  - CSS class: `KpiBandColors.cssClassFor(band)`
 *  - Label text: `ReportStrings.BAND_*`
 *  - Shape glyph: hardcoded `●▲■` (Unicode geometric shapes — a11y triad)
 *
 * Pure: deterministic, no I/O. CCN ≤ 8.
 *
 * @since v4.7 (html-report-rag-bands)
 */
internal object KpiCardRenderer {

    /** Em-dash placeholder used when the measured value is `null` or no
     *  threshold is configured for the resolved [DeviceTier]. */
    private const val NA_GLYPH: String = "—"

    /**
     * Render a single RAG band card for [kpi] at the measured raw [value]
     * scored against the tier-specific threshold for [tier].
     *
     * Returns an em-dash placeholder card (`kpi-na`) when:
     *   - [value] is `null` (the KPI has no measurement for the session), OR
     *   - the catalog entry is missing a threshold for [tier] (defensive —
     *     `KpiCatalog` invariants guarantee all three tiers, but we keep this
     *     branch so a malformed entry never crashes the report renderer).
     */
    fun render(kpi: Kpi, value: Double?, tier: DeviceTier): String {
        if (value == null) {
            return naCard()
        }
        val threshold = kpi.thresholds[tier] ?: return naCard()
        val band = bandFor(value, threshold, kpi.direction)
        val cssClass = KpiBandColors.cssClassFor(band)
        val shape = shapeFor(band)
        val label = labelFor(band)
        return "<div class=\"kpi-card-band $cssClass\">$shape $label</div>"
    }

    private fun naCard(): String = "<div class=\"kpi-card-band kpi-na\">$NA_GLYPH</div>"

    /**
     * Render a standalone band pill for a pre-computed [band] (no [Kpi] or
     * raw value context). Used by aggregate cards (`kpi-overall-card`,
     * `kpi-category-card`) whose `Band` is already determined upstream by
     * the aggregator pipeline — they don't have a single [Kpi] / raw value
     * pair to drive [render].
     *
     * Same a11y triad as [render]: CSS class + shape glyph + castellano label.
     */
    fun renderBandPill(band: Band): String {
        val cssClass = KpiBandColors.cssClassFor(band)
        val shape = shapeFor(band)
        val label = labelFor(band)
        return "<div class=\"kpi-card-band $cssClass\">$shape $label</div>"
    }

    private fun shapeFor(band: Band): String = when (band) {
        Band.GREEN -> "●"
        Band.AMBER -> "▲"
        Band.RED -> "■"
    }

    private fun labelFor(band: Band): String = when (band) {
        Band.GREEN -> ReportStrings.BAND_GREEN
        Band.AMBER -> ReportStrings.BAND_AMBER
        Band.RED -> ReportStrings.BAND_RED
    }
}
