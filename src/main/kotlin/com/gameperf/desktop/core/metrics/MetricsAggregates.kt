package com.gameperf.desktop.core.metrics

import kotlinx.serialization.Serializable

/**
 * Aggregated metrics computed over a (possibly filtered) sample set.
 *
 * This data class holds all the statistical values computed by [FilteredMetricsCalculator]
 * for both filtered (excluding event windows) and raw (full session) views.
 *
 * @property avgFps Average FPS across the sample set.
 * @property minFps Minimum FPS observed.
 * @property maxFps Maximum FPS observed.
 * @property p1 1st percentile FPS (worst 1% of samples).
 * @property p5 5th percentile FPS.
 * @property p50 50th percentile FPS (median).
 * @property p90 90th percentile FPS.
 * @property p99 99th percentile FPS (best 1% of samples).
 * @property avgFrameTime Average frame time in milliseconds.
 * @property p99FrameTime 99th percentile frame time in milliseconds.
 * @property peakMem Peak memory usage in MB (PSS App Summary).
 * @property avgCpu Average CPU usage percentage (game process).
 * @property maxCpu Maximum CPU usage percentage observed.
 * @property maxTempCpu Maximum user-facing CPU temperature (skin if available, else die).
 * @property maxTempGpu Maximum GPU temperature in Celsius.
 * @property maxTempSkin Maximum skin/case temperature in Celsius.
 * @property maxTempDieCpu Maximum CPU die (silicon junction) temperature in Celsius.
 * @property totalJank Total number of jank frames (render time > 1.5x target).
 * @property totalStutter Total number of stutter frames (render time > 100ms).
 * @property sampleCount Number of samples that contributed to these aggregates.
 *   Used for the excessive-filter fallback check (if filtered.sampleCount / raw.sampleCount < 0.30).
 *
 * @since v4.4.0
 */
@Serializable
data class MetricsAggregates(
    val avgFps: Int,
    val minFps: Int,
    val maxFps: Int,
    val p1: Int,
    val p5: Int,
    val p50: Int,
    val p90: Int,
    val p99: Int,
    val avgFrameTime: Double,
    val p99FrameTime: Double,
    val peakMem: Long,
    val avgCpu: Int,
    val maxCpu: Int,
    val maxTempCpu: Double,
    val maxTempGpu: Double,
    val maxTempSkin: Double,
    val maxTempDieCpu: Double,
    val totalJank: Long,
    val totalStutter: Int,
    val sampleCount: Int,
) {
    companion object {
        /**
         * Empty aggregates representing no samples.
         * Used as a fallback when the filtered set is empty.
         */
        val EMPTY = MetricsAggregates(
            avgFps = 0,
            minFps = 0,
            maxFps = 0,
            p1 = 0,
            p5 = 0,
            p50 = 0,
            p90 = 0,
            p99 = 0,
            avgFrameTime = 0.0,
            p99FrameTime = 0.0,
            peakMem = 0L,
            avgCpu = 0,
            maxCpu = 0,
            maxTempCpu = 0.0,
            maxTempGpu = 0.0,
            maxTempSkin = 0.0,
            maxTempDieCpu = 0.0,
            totalJank = 0L,
            totalStutter = 0,
            sampleCount = 0,
        )
    }
}
