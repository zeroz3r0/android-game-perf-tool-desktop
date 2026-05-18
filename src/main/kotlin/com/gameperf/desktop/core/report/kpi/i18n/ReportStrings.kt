package com.gameperf.desktop.core.report.kpi.i18n

/**
 * Single source of truth for HTML report castellano labels — mirror
 * SdkSignatureCatalog / ToolResolver / FrameBudgets pattern.
 *
 * Every UI string used by the shareable HTML report (RAG band labels, budget
 * line labels, section headers, distribution box headers) MUST originate from
 * this file. Inline castellano string literals anywhere under
 * `core/report/kpi/` (or any other renderer) are banned and enforced by the
 * `ReportI18nSingleSourceTest` architectural grep.
 *
 * Convention (CLAUDE.md §Convención de idiomas):
 *  - Castellano formal (tuteo), NO voseo.
 *  - NO tildes — historic encoding bug (`v4.2.4` mojibake) led to canonical
 *    tilde-free spellings throughout the in-app UI. Mirror that here.
 *
 * @since v4.7 (html-report-rag-bands — RAG-006)
 */
internal object ReportStrings {

    /** RAG band labels — paired with shape (`●▲■`) and CSS class for a11y triad. */
    const val BAND_GREEN: String = "Bien"
    const val BAND_AMBER: String = "Atencion"
    const val BAND_RED: String = "Mal"

    /** Frame-time budget reference line labels (chart annotations). */
    const val BUDGET_60FPS: String = "Presupuesto 60 fps"
    const val BUDGET_30FPS: String = "Presupuesto 30 fps"
    const val BUDGET_120FPS: String = "Presupuesto 120 fps"

    /** Per-phase distribution section header. */
    const val PHASE_DIST_TITLE: String = "Distribucion por fase"

    /** Per-phase box statistical labels (median + percentiles + min/max). */
    const val BOX_MEDIAN: String = "Mediana"
    const val BOX_P1: String = "P1"
    const val BOX_P99: String = "P99"
    const val BOX_MIN: String = "Min"
    const val BOX_MAX: String = "Max"
}
