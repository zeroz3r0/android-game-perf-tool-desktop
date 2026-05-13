package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.ComparisonEngine
import com.gameperf.desktop.core.kpi.DeviceTier
import com.gameperf.desktop.core.kpi.Kpi
import com.gameperf.desktop.core.kpi.KpiCatalog
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiScore
import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.core.kpi.Threshold
import com.gameperf.desktop.ui.util.fmtUS

/**
 * Renders the actual-vs-target comparison table.
 *
 * Columns: `KPI / Actual / Target / Delta / Band`. The delta sign comes from
 * [ComparisonEngine.delta] (POSITIVE = better-than-target regardless of the
 * KPI's [com.gameperf.desktop.core.kpi.Direction]).
 *
 * KPIs are sourced from the report (flattened across phases, deduplicated by
 * [KpiId] — first occurrence in phase declaration order wins). Targets are
 * looked up in [catalog] with a tier fallback chain `[tier] → MID → first`.
 *
 * Null `rawValue` renders as `N/D` with the `kpi-na` CSS class and no band
 * decoration on the row.
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: Comparison
 * Table.
 *
 * Pure: deterministic, no I/O.
 *
 * @since v4.6 (shareable-html-report Block F)
 */
internal fun renderComparisonTable(
    report: KpiScoreReport,
    catalog: List<Kpi> = KpiCatalog.ALL,
    tier: DeviceTier = DeviceTier.MID,
): String {
    val catalogById = catalog.associateBy { it.id }
    val seen = LinkedHashMap<KpiId, KpiScore>()
    for (phase in report.phases) {
        for (score in phase.kpiScores) {
            if (score.id !in seen) {
                seen[score.id] = score
            }
        }
    }
    return buildString {
        append("<table class=\"kpi-comparison-table\">")
        append("<thead><tr><th>KPI</th><th>Actual</th><th>Target</th><th>Delta</th><th>Banda</th></tr></thead>")
        append("<tbody>")
        for ((_, score) in seen) {
            renderRow(score, catalogById[score.id], tier)
        }
        append("</tbody></table>")
    }
}

private fun StringBuilder.renderRow(
    score: KpiScore,
    kpi: Kpi?,
    tier: DeviceTier,
) {
    val raw = score.rawValue
    if (raw == null) {
        append("<tr class=\"kpi-na\">")
        append("<td>${score.id.name}</td>")
        append("<td>N/D</td>")
        append("<td>N/D</td>")
        append("<td></td>")
        append("<td></td>")
        append("</tr>")
        return
    }

    val target = kpi?.let { resolveTarget(it, tier) }
    val bandClass = KpiBandColors.cssClassFor(score.band)
    append("<tr class=\"$bandClass\">")
    append("<td>${score.id.name}</td>")
    append("<td>${fmtUS("%.2f", raw)}</td>")
    if (target != null && kpi != null) {
        val delta = ComparisonEngine.delta(raw, target, kpi.direction)
        append("<td>${fmtUS("%.2f", target)}</td>")
        append("<td>${fmtUS("%.2f", delta)}</td>")
    } else {
        append("<td>N/D</td>")
        append("<td></td>")
    }
    append("<td>${score.band.name}</td>")
    append("</tr>")
}

/**
 * Returns the `target` for [kpi] at [tier], falling back to MID and then to
 * the first available threshold if MID is also missing. Returns `null` only
 * when [Kpi.thresholds] is empty (catalog invariant guarantees at least one).
 */
private fun resolveTarget(kpi: Kpi, tier: DeviceTier): Double? {
    val explicit: Threshold? = kpi.thresholds[tier]
    val mid: Threshold? = kpi.thresholds[DeviceTier.MID]
    val firstAvailable: Threshold? = kpi.thresholds.values.firstOrNull()
    val chosen: Threshold? = explicit ?: mid ?: firstAvailable
    return chosen?.target
}
