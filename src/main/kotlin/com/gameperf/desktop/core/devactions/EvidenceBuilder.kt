package com.gameperf.desktop.core.devactions

import com.gameperf.desktop.core.conclusions.ConclusionInput

/**
 * Builds the [DevActionEvidence] block attached to each [DevActionItem].
 *
 * Sprint 0 covers all 8 production ruleIds with stable metric/segment
 * labels plus a small `values` map projecting the most relevant numbers
 * from [ConclusionInput]. Sprint 1 will polish the value keys; Sprint 2+
 * may add per-rule logcat references via DAB-014.
 *
 * Design: `sdd/dev-action-brief/design` — EvidenceBuilder section.
 * Spec: DAB-013 — evidence is structured (`<dl>` ready), not free-prose.
 *
 * @since v4.5.0
 */
internal object EvidenceBuilder {

    private const val SEG_FILTERED = "FILTERED"
    private const val SEG_RAW = "RAW"
    private const val ONE_DECIMAL = "%.1f"
    private const val PERCENT_DELTA_FMT = "%.0f"
    private const val PERCENT_BASE = 100.0

    /**
     * Returns a [DevActionEvidence] record for [ruleId] over [input].
     *
     * Each known ruleId returns a deterministic, non-empty record. Unknown
     * ruleIds fall through to a safe RAW/empty fallback — the renderer can
     * still surface the item without crashing while the catalog is filled.
     */
    fun build(ruleId: String, input: ConclusionInput): DevActionEvidence = when (ruleId) {
        "stable-low-fps-low-cpu" -> DevActionEvidence(
            metric = "fps",
            segment = SEG_FILTERED,
            values = mapOf(
                "p50" to input.filtered.p50.toString(),
                "targetFps" to input.targetFps.toString(),
                "avgCpu" to input.filtered.avgCpu.toString(),
                "maxTempCpu" to ONE_DECIMAL.format(input.filtered.maxTempCpu),
            ),
        )
        "thermal-throttling" -> DevActionEvidence(
            metric = "thermal",
            segment = SEG_FILTERED,
            values = mapOf(
                "maxTempCpu" to ONE_DECIMAL.format(input.filtered.maxTempCpu),
                "maxTempSkin" to ONE_DECIMAL.format(input.filtered.maxTempSkin),
                "p5Fps" to input.filtered.p5.toString(),
                "avgFps" to input.filtered.avgFps.toString(),
            ),
        )
        "memory-leak-suspect" -> DevActionEvidence(
            metric = "memory",
            segment = SEG_FILTERED,
            values = mapOf(
                "peakMem" to input.filtered.peakMem.toString(),
                "sessionDurationS" to input.sessionDurationS.toString(),
                "sampleCount" to input.memTimedFiltered.size.toString(),
            ),
        )
        "jank-with-good-avg" -> DevActionEvidence(
            metric = "fps",
            segment = SEG_FILTERED,
            values = mapOf(
                "avgFps" to input.filtered.avgFps.toString(),
                "totalJank" to input.filtered.totalJank.toString(),
                "sessionDurationS" to input.sessionDurationS.toString(),
            ),
        )
        "fps-cap-suspect" -> DevActionEvidence(
            metric = "fps",
            segment = SEG_FILTERED,
            values = mapOf(
                "p99" to input.filtered.p99.toString(),
                "targetFps" to input.targetFps.toString(),
                "deviceTier" to input.deviceTier.name,
            ),
        )
        "cpu-saturated" -> DevActionEvidence(
            metric = "cpu",
            segment = SEG_FILTERED,
            values = mapOf(
                "avgCpu" to input.filtered.avgCpu.toString(),
                "maxCpu" to input.filtered.maxCpu.toString(),
            ),
        )
        "ad-vs-game-fps-gap" -> DevActionEvidence(
            metric = "fps",
            segment = SEG_RAW,
            values = mapOf(
                "rawAvgFps" to input.raw.avgFps.toString(),
                "filteredAvgFps" to input.filtered.avgFps.toString(),
                "eventCount" to input.events.size.toString(),
                "deltaPct" to deltaPct(input),
            ),
        )
        "loading-thermal-recovery" -> DevActionEvidence(
            metric = "thermal",
            segment = "EVENT_WINDOW",
            values = mapOf(
                "loadingEventCount" to input.events.count {
                    it.type == com.gameperf.desktop.core.events.EventType.LOADING
                }.toString(),
                "maxTempCpu" to ONE_DECIMAL.format(input.filtered.maxTempCpu),
            ),
        )
        else -> DevActionEvidence(metric = "unknown", segment = SEG_RAW)
    }

    private fun deltaPct(input: ConclusionInput): String {
        val raw = input.raw.avgFps.toDouble()
        if (raw <= 0) return "0"
        val pct = ((input.filtered.avgFps - input.raw.avgFps) / raw) * PERCENT_BASE
        return PERCENT_DELTA_FMT.format(pct)
    }
}
