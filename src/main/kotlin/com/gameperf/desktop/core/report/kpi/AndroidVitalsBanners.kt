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
 *  - ANR rate ≥ catalog `ANR_RATE_USERS` floor (MID) — computed as
 *    `(count / durationSec) * 100`. v4.7 (#460) — read from catalog, was
 *    hardcoded local const.
 *  - Slow frames > 25% — docs §3.1 Vitals "excessive slow frames" bad
 *    behavior threshold. (Catalog `SLOW_FRAMES` floor is 50% — that's the
 *    score-floor where the linear scoring saturates to 0, not the Vitals
 *    "bad" anchor users see in Play Console.)
 *  - Frozen frames > 0.1% — docs §3.1 Vitals "excessive frozen frames".
 *    (Catalog `FROZEN_FRAMES` floor IS 0.1% — see [KpiCatalog].)
 *  - Wake locks ≥ catalog `WAKE_LOCKS_RATE` floor (MID) converted hours→ms
 *    via `floor * 3_600_000L` (multiply BEFORE `.toLong()` to keep
 *    sub-hour precision). v4.7 (#460) — read from catalog, was hardcoded
 *    local const.
 *  - Crash rate ≥ catalog `CRASH_RATE_USERS` floor (MID). v4.7 (#460) —
 *    read from catalog, was hardcoded local const.
 *
 * Boundary semantics: the breach gate is INCLUSIVE at the catalog floor,
 * mirroring `LinearScoring.bandFor` which maps `value == floor` to score 0
 * → `Band.RED`. A reading exactly at the floor renders the banner line.
 *
 * Returns `""` (empty string) when no breaches are detected so the caller
 * can append unconditionally without polluting the legacy template.
 *
 * Pure: deterministic, no I/O.
 *
 * @since v4.6 (shareable-html-report Block F); refactored v4.7
 *   (html-report-rag-bands — closes engram followup #460).
 */
internal fun renderVitalsBanner(
    report: KpiScoreReport,
    durationSec: Int,
    wakeLocksScreenOffMs: Long = -1L,
): String {
    val breaches = collectBreaches(report, durationSec, wakeLocksScreenOffMs)
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

/** Slow-frames rate threshold (Vitals) — docs §3.1. */
private const val SLOW_FRAMES_BAD_PCT: Double = 25.0

/** Frozen-frames rate threshold (Vitals) — docs §3.1. Also matches catalog floor. */
private const val FROZEN_FRAMES_BAD_PCT: Double = 0.1

/**
 * Wake-locks "bad behavior" gate in ms, sourced from
 * `KpiCatalog.byId(WAKE_LOCKS_RATE).thresholds[MID].floor` (hours)
 * times 3_600_000L (hours→ms). Multiply BEFORE `.toLong()` to avoid
 * losing sub-hour precision. Single-source: KpiCatalog (closes #460).
 */
private val WAKE_LOCKS_MS_FLOOR: Long =
    (KpiCatalog.byId(KpiId.WAKE_LOCKS_RATE).thresholds[DeviceTier.MID]!!.floor * 3_600_000L).toLong()

/**
 * Crash-rate "bad behavior" floor in %, sourced from
 * `KpiCatalog.byId(CRASH_RATE_USERS).thresholds[MID].floor`.
 * Single-source: KpiCatalog (closes #460).
 */
private val CRASH_RATE_USERS_FLOOR_PCT: Double =
    KpiCatalog.byId(KpiId.CRASH_RATE_USERS).thresholds[DeviceTier.MID]!!.floor

/**
 * ANR-rate "bad behavior" floor in %, sourced from
 * `KpiCatalog.byId(ANR_RATE_USERS).thresholds[MID].floor`.
 * Single-source: KpiCatalog (closes #460).
 */
private val ANR_RATE_USERS_FLOOR_PCT: Double =
    KpiCatalog.byId(KpiId.ANR_RATE_USERS).thresholds[DeviceTier.MID]!!.floor

private fun collectBreaches(
    report: KpiScoreReport,
    durationSec: Int,
    wakeLocksScreenOffMs: Long,
): List<String> {
    val out = mutableListOf<String>()
    val byId = flattenByKpiId(report)

    // Cold start — threshold from catalog (single source).
    val coldStartFloor = KpiCatalog.byId(KpiId.COLD_START_MS).thresholds[DeviceTier.MID]?.floor
    val coldStart = byId[KpiId.COLD_START_MS]
    if (coldStart != null && coldStartFloor != null && coldStart >= coldStartFloor) {
        out += "Cold start lento (\u22655s)"
    }

    // ANR rate — count / durationSec → percent. Floor from KpiCatalog (single source).
    val anrCount = byId[KpiId.ANR_COUNT]
    val anrFloorPctStr = formatPct(ANR_RATE_USERS_FLOOR_PCT)
    if (anrCount != null && durationSec > 0) {
        val ratePct = (anrCount / durationSec) * 100.0
        if (ratePct >= ANR_RATE_USERS_FLOOR_PCT) {
            out += "ANR \u2265$anrFloorPctStr%"
        }
    }

    // v4.6.0 — CRASH_RATE_USERS single-session proxy: any CRASH_COUNT > 0 is
    // an early alert. Floor citation read from KpiCatalog so the user sees the
    // official source of truth (single-source — closes #460).
    if (anrCount != null && anrCount > 0.0) {
        out += "ANR rate users \u2265$anrFloorPctStr% (Vitals v1 proxy — esta sesión registró ANR)"
    }
    val crashCount = byId[KpiId.CRASH_COUNT]
    val crashFloorPctStr = formatPct(CRASH_RATE_USERS_FLOOR_PCT)
    if (crashCount != null && crashCount > 0.0) {
        out += "Crash rate users \u2265$crashFloorPctStr% (Vitals v1 proxy — esta sesión registró crashes)"
    }

    val slow = byId[KpiId.SLOW_FRAMES]
    if (slow != null && slow > SLOW_FRAMES_BAD_PCT) {
        out += "Slow frames >25%"
    }

    val frozen = byId[KpiId.FROZEN_FRAMES]
    if (frozen != null && frozen > FROZEN_FRAMES_BAD_PCT) {
        out += "Frozen frames >0.1%"
    }

    // v4.6.0 — Wake locks (vitals-rate-and-wakelocks). Reads directly from
    // SessionResult-level ms field (NOT from kpiScores map — wake locks are
    // session-scoped not phase-scoped per design D3). Gate computed from
    // KpiCatalog.WAKE_LOCKS_RATE floor (single-source — closes #460).
    if (wakeLocksScreenOffMs >= WAKE_LOCKS_MS_FLOOR) {
        out += "Wake locks \u22652h en pantalla apagada (Vitals 2024 — penalización de descubrimiento)"
    }

    return out
}

/**
 * Formats a percentage value for the banner copy with a locale-independent
 * dot as the decimal separator (Windows ES locale would otherwise emit
 * `0,47` instead of `0.47` and break the banner regex contract). Drops
 * trailing zeros but keeps up to 2 decimals.
 * Examples: 0.47 → "0.47", 1.09 → "1.09", 2.0 → "2".
 */
private fun formatPct(value: Double): String {
    val formatted = String.format(java.util.Locale.ROOT, "%.2f", value)
    return formatted.trimEnd('0').trimEnd('.')
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
