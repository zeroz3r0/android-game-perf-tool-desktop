package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.DeviceTier
import com.gameperf.desktop.core.kpi.KpiCatalog
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiScoreReport

/**
 * Renders the Android Vitals warning banner that lives at the top of the
 * KPI scoring section when one or more of the user-perceived metrics breach
 * the Google Play "bad behavior" thresholds.
 *
 * Breach rules (docs §3.1 Android Vitals + `KpiCatalog`):
 *  - Cold start ≥ catalog `COLD_START_MS` floor for MID (5000 ms).
 *  - ANR rate ≥ 0.47% — computed as `(count / durationSec) * 100`. The
 *    0.47% literal comes from docs §3.1 (Vitals user-perceived ANR bad
 *    behavior threshold). This is a rate, not a raw count, so it does NOT
 *    come from `KpiCatalog.byId(ANR_COUNT).floor` (which is "1 count per
 *    session" — a per-session bound, not a rate).
 *  - Slow frames > 25% — docs §3.1 Vitals "excessive slow frames" bad
 *    behavior threshold. (Catalog `SLOW_FRAMES` floor is 50% — that's the
 *    score-floor where the linear scoring saturates to 0, not the Vitals
 *    "bad" anchor users see in Play Console.)
 *  - Frozen frames > 0.1% — docs §3.1 Vitals "excessive frozen frames".
 *    (Catalog `FROZEN_FRAMES` floor IS 0.1% — see [KpiCatalog].)
 *
 * Returns `""` (empty string) when no breaches are detected so the caller
 * can append unconditionally without polluting the legacy template.
 *
 * Pure: deterministic, no I/O.
 *
 * @since v4.6 (shareable-html-report Block F)
 */
internal fun renderVitalsBanner(report: KpiScoreReport, durationSec: Int): String {
    val breaches = collectBreaches(report, durationSec)
    if (breaches.isEmpty()) return ""
    return buildString {
        append("<section id=\"sec-vitals-banner\" class=\"kpi-vitals-warn\">")
        append("<h3>Android Vitals — alertas</h3>")
        append("<ul>")
        for (line in breaches) {
            append("<li>")
            append(line)
            append("</li>")
        }
        append("</ul>")
        append("</section>")
    }
}

/** ANR rate threshold (Vitals user-perceived) — docs §3.1. */
private const val ANR_RATE_BAD_PCT: Double = 0.47

/** Slow-frames rate threshold (Vitals) — docs §3.1. */
private const val SLOW_FRAMES_BAD_PCT: Double = 25.0

/** Frozen-frames rate threshold (Vitals) — docs §3.1. Also matches catalog floor. */
private const val FROZEN_FRAMES_BAD_PCT: Double = 0.1

private fun collectBreaches(report: KpiScoreReport, durationSec: Int): List<String> {
    val out = mutableListOf<String>()
    val byId = flattenByKpiId(report)

    // Cold start — threshold from catalog (single source).
    val coldStartFloor = KpiCatalog.byId(KpiId.COLD_START_MS).thresholds[DeviceTier.MID]?.floor
    val coldStart = byId[KpiId.COLD_START_MS]
    if (coldStart != null && coldStartFloor != null && coldStart >= coldStartFloor) {
        out += "Cold start lento (\u22655s)"
    }

    // ANR rate — count / durationSec → percent.
    val anrCount = byId[KpiId.ANR_COUNT]
    if (anrCount != null && durationSec > 0) {
        val ratePct = (anrCount / durationSec) * 100.0
        if (ratePct >= ANR_RATE_BAD_PCT) {
            out += "ANR \u22650.47%"
        }
    }

    val slow = byId[KpiId.SLOW_FRAMES]
    if (slow != null && slow > SLOW_FRAMES_BAD_PCT) {
        out += "Slow frames >25%"
    }

    val frozen = byId[KpiId.FROZEN_FRAMES]
    if (frozen != null && frozen > FROZEN_FRAMES_BAD_PCT) {
        out += "Frozen frames >0.1%"
    }

    return out
}

/**
 * Flattens [report] into a single id-keyed map of raw values. When multiple
 * phases report the same KPI (rare for Vitals metrics — cold start lives in
 * APP_STARTUP, slow/frozen frames live in GAMEPLAY) the first non-null value
 * wins. Phases are visited in declaration order.
 */
private fun flattenByKpiId(report: KpiScoreReport): Map<KpiId, Double> {
    val out = mutableMapOf<KpiId, Double>()
    for (phase in report.phases) {
        for (score in phase.kpiScores) {
            if (score.rawValue != null && score.id !in out) {
                out[score.id] = score.rawValue
            }
        }
    }
    return out
}
