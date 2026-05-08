package com.gameperf.desktop.core.metrics

import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.viewmodel.TimedSample

/**
 * Computes [MetricsAggregates] with optional time-range exclusion.
 *
 * Pure object — no I/O, no side effects, deterministic output.
 *
 * Filtering strategy:
 *  - Each [DetectedEvent] with both [DetectedEvent.startMs] and [DetectedEvent.endMs]
 *    becomes a [TimeRange].
 *  - Symmetric padding [PADDING_MS] (default 500ms) is applied via [TimeRange.withPadding].
 *  - Overlapping ranges are unioned via [TimeRange.union] so a sample is never
 *    counted-out twice.
 *  - Each timed sample falls either INSIDE the padded union (excluded) or OUTSIDE
 *    (kept). Aggregates are computed over the kept set only.
 *
 * Excessive-filter guardrail (FLT-005):
 *  - If filtering excludes more than [EXCESSIVE_FILTER_RATIO] of samples
 *    (default 70%), callers should fall back to raw and surface a warning.
 *  - The calculator itself never throws — it always returns valid aggregates.
 *  - [computeWithFallback] applies the fallback automatically and reports which
 *    path was used via [FilteredResult.excessiveFiltering].
 *
 * Coordinate convention:
 *  - [DetectedEvent.startMs] / [DetectedEvent.endMs] are absolute epoch-millis
 *    (mirrors `LogLine.tsMs`).
 *  - [TimedSample.second] is capture-relative seconds since
 *    [FilterInput.captureStartTime].
 *  - [compute] converts excluded ranges to capture-relative milliseconds once
 *    upfront, then checks each sample's `second * 1000L` against them.
 *
 * @since v4.4.0
 */
object FilteredMetricsCalculator {

    /** Symmetric padding applied to each event range to absorb logcat lag. */
    const val PADDING_MS = 500L

    /**
     * If the kept-fraction drops below `1 - EXCESSIVE_FILTER_RATIO` (i.e., more
     * than 70% of samples were excluded), [computeWithFallback] swaps the
     * filtered result for raw and flags `excessiveFiltering = true`.
     */
    const val EXCESSIVE_FILTER_RATIO = 0.70

    /**
     * Converts a list of [DetectedEvent]s with both endpoints into a list of
     * non-overlapping [TimeRange]s with symmetric padding applied.
     *
     * Events with `endMs == null` are skipped (still-open at session end).
     * The result is sorted ascending by `startMs` and contains zero overlapping
     * ranges (adjacent / overlapping inputs are merged via [TimeRange.union]).
     *
     * Padding may push `startMs` below zero on early events; that is left as-is
     * because the membership check against capture-relative samples will never
     * see a negative timestamp anyway.
     *
     * @param events Events to convert.
     * @param paddingMs Symmetric padding applied via [TimeRange.withPadding].
     * @return Sorted, merged list of padded ranges.
     */
    fun unionRanges(
        events: List<DetectedEvent>,
        paddingMs: Long = PADDING_MS,
    ): List<TimeRange> {
        if (events.isEmpty()) return emptyList()
        val padded = events.mapNotNull { ev ->
            val end = ev.endMs ?: return@mapNotNull null
            // Guard against malformed events where endMs < startMs — TimeRange
            // would throw on construction. Skip them so the calculator stays
            // robust to upstream bugs.
            if (end < ev.startMs) return@mapNotNull null
            TimeRange(ev.startMs, end).withPadding(paddingMs)
        }
        if (padded.isEmpty()) return emptyList()
        val sorted = padded.sortedBy { it.startMs }
        val merged = mutableListOf<TimeRange>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last != null && last.overlaps(range)) {
                merged[merged.size - 1] = last.union(range)
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    /**
     * Computes aggregates over [input], excluding samples whose absolute
     * timestamp falls inside any of [excludedRanges].
     *
     * Pure: same input → same output, no global state, no I/O.
     *
     * @param input Timestamped metric histories collected during capture.
     * @param excludedRanges Padded, unioned event windows in absolute epoch-ms.
     *   Pass `emptyList()` to compute a raw whole-session view.
     */
    fun compute(
        input: FilterInput,
        excludedRanges: List<TimeRange> = emptyList(),
    ): MetricsAggregates {
        val relativeRanges = excludedRanges.mapNotNull { range ->
            val relStart = (range.startMs - input.captureStartTime).coerceAtLeast(0L)
            val relEnd = (range.endMs - input.captureStartTime).coerceAtLeast(0L)
            if (relEnd <= 0L) null else TimeRange(relStart, relEnd)
        }

        fun List<TimedSample>.kept(): List<TimedSample> =
            if (relativeRanges.isEmpty()) this
            else filter { sample ->
                val ms = sample.second * 1000L
                relativeRanges.none { it.contains(ms) }
            }

        val fpsKept = input.fpsTimed.kept()
        val cpuKept = input.cpuTimed.kept()
        val memKept = input.memTimed.kept()
        val tempCpuKept = input.tempCpuTimed.kept()
        val tempGpuKept = input.tempGpuTimed.kept()
        val tempSkinKept = input.tempSkinTimed.kept()
        val tempDieCpuKept = input.tempDieCpuTimed.kept()
        val frameTimeKept = input.frameTimeTimed.kept()
        val jankKept = input.jankTimed.kept()
        val stutterKept = input.stutterTimed.kept()

        if (fpsKept.isEmpty()) return MetricsAggregates.EMPTY

        val fpsValues = fpsKept.map { it.value }
        val cpuValues = cpuKept.map { it.value }
        val frameTimeValues = frameTimeKept.map { it.value }

        return MetricsAggregates(
            avgFps = fpsValues.average().toInt(),
            minFps = fpsValues.min().toInt(),
            maxFps = fpsValues.max().toInt(),
            p1 = fpsValues.percentile(1.0).toInt(),
            p5 = fpsValues.percentile(5.0).toInt(),
            p50 = fpsValues.percentile(50.0).toInt(),
            p90 = fpsValues.percentile(90.0).toInt(),
            p99 = fpsValues.percentile(99.0).toInt(),
            avgFrameTime = if (frameTimeValues.isNotEmpty()) frameTimeValues.average() else 0.0,
            p99FrameTime = if (frameTimeValues.isNotEmpty()) frameTimeValues.percentile(99.0) else 0.0,
            peakMem = memKept.maxOfOrNull { it.value }?.toLong() ?: 0L,
            avgCpu = if (cpuValues.isNotEmpty()) cpuValues.average().toInt() else 0,
            maxCpu = cpuValues.maxOrNull()?.toInt() ?: 0,
            maxTempCpu = tempCpuKept.maxOfOrNull { it.value } ?: 0.0,
            maxTempGpu = tempGpuKept.maxOfOrNull { it.value } ?: 0.0,
            maxTempSkin = tempSkinKept.maxOfOrNull { it.value } ?: 0.0,
            maxTempDieCpu = tempDieCpuKept.maxOfOrNull { it.value } ?: 0.0,
            // jank/stutter are cumulative counters in the timed series.
            // Total = (last kept value) - (first kept value preceding the kept window)
            // is fragile under filtering; instead use last - first of the kept set.
            // For a session with no exclusions this matches the legacy behavior of
            // taking the final cumulative count.
            totalJank = (jankKept.lastOrNull()?.value?.toLong() ?: 0L) -
                (jankKept.firstOrNull()?.value?.toLong() ?: 0L),
            totalStutter = ((stutterKept.lastOrNull()?.value?.toInt() ?: 0) -
                (stutterKept.firstOrNull()?.value?.toInt() ?: 0)),
            sampleCount = fpsKept.size,
        )
    }

    /**
     * Computes both filtered and raw aggregates in one call. If filtering would
     * exclude more than [EXCESSIVE_FILTER_RATIO] of samples, the filtered field
     * is set equal to raw and `excessiveFiltering = true` so the caller can
     * surface a warning.
     */
    fun computeWithFallback(
        input: FilterInput,
        events: List<DetectedEvent>,
    ): FilteredResult {
        val ranges = unionRanges(events)
        val raw = compute(input, emptyList())
        if (ranges.isEmpty()) {
            return FilteredResult(
                filtered = raw,
                raw = raw,
                excessiveFiltering = false,
                excludedRangeCount = 0,
            )
        }
        val filtered = compute(input, ranges)
        val keptRatio = filtered.sampleCount.toDouble() /
            raw.sampleCount.coerceAtLeast(1).toDouble()
        val excessive = keptRatio < (1.0 - EXCESSIVE_FILTER_RATIO)
        return FilteredResult(
            filtered = if (excessive) raw else filtered,
            raw = raw,
            excessiveFiltering = excessive,
            excludedRangeCount = ranges.size,
        )
    }

    /**
     * Linear-interpolation percentile. `p` is in `[0, 100]`.
     * For an empty list, returns `0.0`. For `p == 0` returns the min; for
     * `p == 100` returns the max.
     */
    private fun List<Double>.percentile(p: Double): Double {
        if (isEmpty()) return 0.0
        val sorted = this.sorted()
        val rank = (p / 100.0) * (sorted.size - 1)
        val lower = rank.toInt()
        val upper = lower + 1
        if (upper >= sorted.size) return sorted.last()
        val fraction = rank - lower
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower])
    }
}

/**
 * Result of [FilteredMetricsCalculator.computeWithFallback].
 *
 * @property filtered Aggregates over the kept (non-excluded) samples. When
 *   [excessiveFiltering] is true, this field is set equal to [raw] so the
 *   final score remains based on a meaningful sample.
 * @property raw Aggregates over the full session (no exclusion). Always populated.
 * @property excessiveFiltering True when filtering would have removed more than
 *   [FilteredMetricsCalculator.EXCESSIVE_FILTER_RATIO] of samples; UI should
 *   surface a warning when set.
 * @property excludedRangeCount Number of (unioned) ranges actually applied.
 *
 * @since v4.4.0
 */
data class FilteredResult(
    val filtered: MetricsAggregates,
    val raw: MetricsAggregates,
    val excessiveFiltering: Boolean,
    val excludedRangeCount: Int,
)
