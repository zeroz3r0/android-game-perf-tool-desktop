// SINGLE SOURCE OF TRUTH for SessionResult → KpiInput mapping.
// Do NOT define alternate mapping logic elsewhere.
package com.gameperf.desktop.core.kpi.adapter

import com.gameperf.desktop.core.events.EventType
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiInput
import com.gameperf.desktop.core.kpi.Phase
import com.gameperf.desktop.viewmodel.SessionResult

/** Window (phase, [startSec, endSec)) carved out of the session timeline. */
private data class PhaseWindow(val phase: Phase, val startSec: Int, val endSec: Int)

/**
 * EventType → Phase mapping table (single source of truth).
 *
 * Unmapped types (IAP, FOREGROUND_LOSS, SDK_INIT, ANR, INSTRUMENTED,
 * VR_SESSION, VR_RETURN_TRANSITION, RATE_US, UNKNOWN) DO NOT produce a
 * dedicated phase — but their time window IS still subtracted from
 * GAMEPLAY (per design D4).
 */
private val eventTypeToPhase: Map<EventType, Phase> = mapOf(
    EventType.APP_STARTUP to Phase.APP_STARTUP,
    EventType.LOADING to Phase.LEVEL_LOADING,
    EventType.SCREEN_TRANSITION to Phase.SCREEN_NAV,
    EventType.INTERSTITIAL to Phase.INTERSTITIAL_AD,
    EventType.REWARDED_VIDEO to Phase.REWARDED_AD,
)

/**
 * Pure mapping: captured [SessionResult] → scoring [KpiInput].
 *
 * EventType → Phase table (single source of truth):
 *  - APP_STARTUP        → APP_STARTUP
 *  - LOADING            → LEVEL_LOADING
 *  - SCREEN_TRANSITION  → SCREEN_NAV
 *  - INTERSTITIAL       → INTERSTITIAL_AD
 *  - REWARDED_VIDEO     → REWARDED_AD
 *  - others             → not mapped to a Phase; time still excluded from GAMEPLAY
 *
 * KPI source rules (see spec):
 *  - FPS_AVG       → session.avgFps (when > 0)
 *  - FPS_P1        → session.p1Fps (when > 0)
 *  - FRAME_TIME_P99 → session.p99FrameTime (when > 0)
 *  - JANK_COUNT    → session.totalJank (always emitted)
 *  - CPU_AVG_NORMALIZED → session.avgCpu (when > 0)
 *  - CPU_MAX       → session.maxCpu (when > 0)
 *  - RAM_AVG, RAM_MAX → session.peakMemMb (when > 0)
 *  - TEMP_MAX      → session.maxTempCpu (when > 0 → thermal available)
 *  - FPOWER        → session.fpowerAvg (when session.fpowerAvailable && history non-empty)
 *  - BATTERY_DRAIN → session.batteryDrain (when > 0)
 *
 * @since v4.5 (kpi-session-adapter)
 */
fun toKpiInput(session: SessionResult): KpiInput {
    val rawByPhase: Map<Phase, Map<KpiId, Double>> = buildRawByPhase(session)
    return KpiInput(deviceModel = session.deviceModel, rawByPhase = rawByPhase)
}

private const val MS_PER_SEC = 1000

private fun buildRawByPhase(session: SessionResult): Map<Phase, Map<KpiId, Double>> {
    val (mappedWindows, unmappedWindows) = buildEventWindows(session)
    val gameplay = computeGameplayWindows(session, mappedWindows, unmappedWindows)

    val byPhase: Map<Phase, List<PhaseWindow>> =
        (mappedWindows + gameplay).groupBy { it.phase }

    val out = LinkedHashMap<Phase, Map<KpiId, Double>>()
    for ((phase, windows) in byPhase) {
        if (windows.isEmpty()) continue
        val kpis = kpisForPhase(session, windows)
        if (kpis.isNotEmpty()) out[phase] = kpis
    }
    return out
}

/**
 * Translate `session.events` into:
 *  - mappedWindows: phase windows for [eventTypeToPhase]-known events
 *  - unmappedWindows: raw `[startSec, endSec)` intervals for other event types
 *    (so the GAMEPLAY computation can still carve them out — design D4)
 */
private fun buildEventWindows(session: SessionResult): Pair<List<PhaseWindow>, List<IntRange>> {
    val durationMs = (session.duration * MS_PER_SEC).toLong()
    val mapped = mutableListOf<PhaseWindow>()
    val unmapped = mutableListOf<IntRange>()
    for (e in session.events) {
        val startMs = e.startMs.coerceAtLeast(0L)
        val endMs = (e.endMs ?: durationMs).coerceAtMost(durationMs).coerceAtLeast(startMs)
        val startSec = (startMs / MS_PER_SEC).toInt()
        val endSec = (endMs / MS_PER_SEC).toInt()
        if (endSec <= startSec) continue
        val phase = eventTypeToPhase[e.type]
        if (phase != null) {
            mapped += PhaseWindow(phase, startSec, endSec)
        } else {
            unmapped += startSec until endSec
        }
    }
    return mapped to unmapped
}

/**
 * Invert the union of all event windows (mapped + unmapped) over
 * `[0, session.duration)` to produce GAMEPLAY windows.
 */
private fun computeGameplayWindows(
    session: SessionResult,
    mappedWindows: List<PhaseWindow>,
    unmappedWindows: List<IntRange>,
): List<PhaseWindow> {
    if (session.duration <= 0) return emptyList()
    val occupied = BooleanArray(session.duration)
    for (w in mappedWindows) {
        for (s in w.startSec until w.endSec.coerceAtMost(session.duration)) occupied[s] = true
    }
    for (r in unmappedWindows) {
        for (s in r.first..r.last) {
            if (s in 0 until session.duration) occupied[s] = true
        }
    }
    val result = mutableListOf<PhaseWindow>()
    var i = 0
    while (i < session.duration) {
        if (occupied[i]) {
            i++
            continue
        }
        val start = i
        while (i < session.duration && !occupied[i]) i++
        result += PhaseWindow(Phase.GAMEPLAY, start, i)
    }
    return result
}

/**
 * Compute the [KpiId] → raw-value map for [windows] (one phase).
 *
 * KPIs missing source data are SKIPPED (not emitted as 0.0). The aggregator
 * renormalizes per kpi-scoring D4.
 */
@Suppress("UnusedParameter") // [windows] reserved for per-window slicing (task 2.3+).
private fun kpisForPhase(
    session: SessionResult,
    windows: List<PhaseWindow>,
): Map<KpiId, Double> {
    val out = LinkedHashMap<KpiId, Double>()
    if (session.avgFps > 0) out[KpiId.FPS_AVG] = session.avgFps.toDouble()
    if (session.p1Fps > 0) out[KpiId.FPS_P1] = session.p1Fps.toDouble()
    if (session.p99FrameTime > 0.0) out[KpiId.FRAME_TIME_P99] = session.p99FrameTime
    // JANK_COUNT: always emit (0 is a valid measurement, not "missing")
    out[KpiId.JANK_COUNT] = session.totalJank.toDouble()
    if (session.avgCpu > 0) out[KpiId.CPU_AVG_NORMALIZED] = session.avgCpu.toDouble()
    if (session.maxCpu > 0) out[KpiId.CPU_MAX] = session.maxCpu.toDouble()
    if (session.peakMemMb > 0) {
        out[KpiId.RAM_AVG] = session.peakMemMb.toDouble()
        out[KpiId.RAM_MAX] = session.peakMemMb.toDouble()
    }
    if (session.maxTempCpu > 0.0) out[KpiId.TEMP_MAX] = session.maxTempCpu
    if (session.fpowerAvailable && session.fpowerHistory.isNotEmpty()) {
        out[KpiId.FPOWER] = session.fpowerAvg
    }
    if (session.batteryDrain > 0) out[KpiId.BATTERY_DRAIN] = session.batteryDrain.toDouble()
    return out
}
