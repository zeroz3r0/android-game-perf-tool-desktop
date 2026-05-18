package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Direction
import com.gameperf.desktop.core.kpi.DeviceTier
import com.gameperf.desktop.core.kpi.KpiCatalog
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.core.kpi.bandFor
import com.gameperf.desktop.core.report.kpi.i18n.ReportStrings

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

/**
 * Renders per-phase distribution boxes — a complementary section to
 * [renderPhaseBreakdown] that focuses on the FPS distribution stats
 * (median, p1, and — when available — p99 / min / max) inside the
 * temporal range of each phase.
 *
 * Spec RAG-004 (`sdd/html-report-rag-bands`) requires `median, p1, p99,
 * min, max` of FPS per phase plus a colored band derived from the median
 * FPS vs `KpiCatalog.byId(FPS_AVG).thresholds[tier]`. The current
 * [com.gameperf.desktop.core.kpi.PhaseScore] model only carries the
 * per-KPI `rawValue` (so we can read `FPS_AVG` and `FPS_P1` from
 * `kpiScores`) — it does NOT carry per-phase frame-time distribution
 * percentiles or a `frameCount`. We therefore:
 *
 *  - Use `FPS_AVG.rawValue == null` as the proxy for "phase has fewer
 *    than 5 frames" (the spec's hard skip rule). The KPI scorer already
 *    null-emits when no data is available — that is the natural signal.
 *  - Render `—` placeholders for the percentile cells we cannot derive
 *    (`p99 / min / max`) until a follow-up adds a phase-scoped FPS
 *    aggregator. The median + p1 cells consume real KPI values.
 *
 * Returns `""` (empty string) when no phase has a non-null
 * `FPS_AVG.rawValue` so the caller can append unconditionally (RAG-010
 * backward compat — legacy `.gameperf` reports without phase data
 * keep their pre-v4.7 output).
 *
 * Pure: deterministic, no I/O.
 *
 * @since v4.7 (html-report-rag-bands — RAG-004)
 */
internal fun renderPhaseDistributionBoxes(
    report: KpiScoreReport,
    tier: DeviceTier,
): String {
    val eligible = report.phases
        .mapNotNull { phase ->
            val median = phase.kpiScores.firstOrNull { it.id == KpiId.FPS_AVG }?.rawValue
                ?: return@mapNotNull null
            val p1 = phase.kpiScores.firstOrNull { it.id == KpiId.FPS_P1 }?.rawValue
            Triple(phase, median, p1)
        }
        .sortedByDescending { it.second }
    if (eligible.isEmpty()) return ""

    val fpsAvgKpi = KpiCatalog.byId(KpiId.FPS_AVG)
    val threshold = fpsAvgKpi.thresholds[tier]
    val placeholder = "—"
    return buildString {
        append("<section id=\"sec-phase-distribution\" class=\"kpi-phase-distribution\">")
        append("<h2>${ReportStrings.PHASE_DIST_TITLE}</h2>")
        append("<div class=\"phase-dist-grid\">")
        for ((phaseScore, median, p1) in eligible) {
            val band = if (threshold != null) {
                bandFor(median, threshold, Direction.HIGHER_IS_BETTER)
            } else {
                phaseScore.band
            }
            val cls = KpiBandColors.cssClassFor(band)
            val medianStr = "%.1f".format(median)
            val p1Str = if (p1 != null) "%.1f".format(p1) else placeholder
            append("<div class=\"phase-dist-box $cls\">")
            append("<h3>${phaseScore.phase.name}</h3>")
            append("<div class=\"phase-dist-row\"><span>${ReportStrings.BOX_MEDIAN}</span><span>$medianStr</span></div>")
            append("<div class=\"phase-dist-row\"><span>${ReportStrings.BOX_P1}</span><span>$p1Str</span></div>")
            append("<div class=\"phase-dist-row\"><span>${ReportStrings.BOX_P99}</span><span>$placeholder</span></div>")
            append("<div class=\"phase-dist-row\"><span>${ReportStrings.BOX_MIN}</span><span>$placeholder</span></div>")
            append("<div class=\"phase-dist-row\"><span>${ReportStrings.BOX_MAX}</span><span>$placeholder</span></div>")
            append("</div>")
        }
        append("</div>")
        append("</section>")
    }
}
