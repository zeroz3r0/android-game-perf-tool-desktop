package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.KpiScoreReport

/**
 * Renders a per-phase breakdown table with drill-down into the underlying
 * `KpiScore.id` names. Phases are sorted by `Phase.ordinal` so the output
 * is deterministic regardless of insertion order in the report.
 *
 * Returns `""` (empty string) when `report.phases` is empty so the caller
 * can append unconditionally without polluting the legacy template.
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: Phase
 * Breakdown.
 *
 * Pure: deterministic, no I/O.
 *
 * @since v4.6 (shareable-html-report Block F)
 */
internal fun renderPhaseBreakdown(report: KpiScoreReport): String {
    if (report.phases.isEmpty()) return ""
    val sortedPhases = report.phases.sortedBy { it.phase.ordinal }
    return buildString {
        append("<section id=\"sec-phase-breakdown\" class=\"kpi-phase-breakdown\">")
        append("<h2>Detalle por fase</h2>")
        append("<table class=\"kpi-phase-breakdown-table\">")
        append("<thead><tr><th>Fase</th><th>Score</th><th>Banda</th><th>KPIs</th></tr></thead>")
        append("<tbody>")
        for (phase in sortedPhases) {
            val cls = KpiBandColors.cssClassFor(phase.band)
            append("<tr class=\"$cls\">")
            append("<td>${phase.phase.name}</td>")
            append("<td>${phase.score}</td>")
            append("<td>${phase.band.name}</td>")
            append("<td><ul class=\"kpi-phase-drilldown\">")
            for (score in phase.kpiScores) {
                append("<li>${score.id.name} (${score.score}/100, ${score.band.name})</li>")
            }
            append("</ul></td>")
            append("</tr>")
        }
        append("</tbody></table>")
        append("</section>")
    }
}
