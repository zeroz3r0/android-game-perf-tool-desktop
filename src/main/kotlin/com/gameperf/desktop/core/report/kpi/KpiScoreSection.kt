package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.KpiScoreReport

/**
 * Renders the main KPI scoring section: a big overall-score card on top,
 * one row per phase in a "phases" table, and one card per category below.
 *
 * Band CSS classes are always taken from [KpiBandColors.cssClassFor] — DO
 * NOT hardcode `kpi-band-*` literals anywhere else in this renderer.
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: KPI Score
 * Section.
 *
 * Pure: deterministic, no I/O.
 *
 * @since v4.6 (shareable-html-report Block F)
 */
internal fun renderKpiScoreSection(report: KpiScoreReport): String = buildString {
    append("<section id=\"sec-kpi-scoring\" class=\"kpi-scoring\">")
    append("<h2>Puntaje KPI</h2>")

    // Overall card
    val overallClass = KpiBandColors.cssClassFor(report.sessionBand)
    append("<div class=\"kpi-overall-card $overallClass\">")
    append("<span class=\"kpi-overall-score\">${report.sessionScore}/100</span>")
    append("<span class=\"kpi-overall-band\">${report.sessionBand.name}</span>")
    append("</div>")

    // Phases table
    if (report.phases.isNotEmpty()) {
        append("<table class=\"kpi-phases-table\">")
        append("<thead><tr><th>Fase</th><th>Score</th><th>Banda</th></tr></thead>")
        append("<tbody>")
        for (phase in report.phases) {
            val cls = KpiBandColors.cssClassFor(phase.band)
            append("<tr class=\"$cls\">")
            append("<td>${phase.phase.name}</td>")
            append("<td>${phase.score}</td>")
            append("<td>${phase.band.name}</td>")
            append("</tr>")
        }
        append("</tbody></table>")
    }

    // Category cards
    if (report.categories.isNotEmpty()) {
        append("<div class=\"kpi-category-cards\">")
        for (cat in report.categories) {
            val cls = KpiBandColors.cssClassFor(cat.band)
            append("<div class=\"kpi-category-card $cls\">")
            append("<span class=\"kpi-category-name\">${cat.category.name}</span>")
            append("<span class=\"kpi-category-score\">${cat.score}/100</span>")
            append("</div>")
        }
        append("</div>")
    }

    append("</section>")
}
