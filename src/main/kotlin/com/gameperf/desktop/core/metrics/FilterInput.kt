package com.gameperf.desktop.core.metrics

import com.gameperf.desktop.viewmodel.TimedSample

/**
 * Input container for [FilteredMetricsCalculator.compute].
 *
 * Holds all timestamped metric histories collected during a capture session.
 * Each `*Timed` field is a list of [TimedSample] where `second` is the relative
 * timestamp in seconds (from [captureStartTime]) and `value` is the metric value.
 *
 * @property fpsTimed FPS samples with timestamps.
 * @property cpuTimed CPU percentage samples with timestamps.
 * @property memTimed Total memory (MB) samples with timestamps.
 * @property nativeTimed Native memory (MB) samples with timestamps.
 * @property javaTimed Java/Dalvik memory (MB) samples with timestamps.
 * @property tempCpuTimed CPU temperature samples with timestamps.
 * @property tempGpuTimed GPU temperature samples with timestamps.
 * @property tempSkinTimed Skin temperature samples with timestamps.
 * @property tempDieCpuTimed CPU die temperature samples with timestamps.
 * @property frameTimeTimed Average frame time (ms) samples with timestamps.
 * @property jankTimed Cumulative jank count samples with timestamps.
 * @property stutterTimed Cumulative stutter count samples with timestamps.
 * @property captureStartTime Absolute wall-clock time when capture started (epoch millis).
 * @property sessionEndMs Relative end time of the session in milliseconds.
 *
 * @since v4.4.0
 */
data class FilterInput(
    val fpsTimed: List<TimedSample>,
    val cpuTimed: List<TimedSample>,
    val memTimed: List<TimedSample>,
    val nativeTimed: List<TimedSample>,
    val javaTimed: List<TimedSample>,
    val tempCpuTimed: List<TimedSample>,
    val tempGpuTimed: List<TimedSample>,
    val tempSkinTimed: List<TimedSample>,
    val tempDieCpuTimed: List<TimedSample>,
    val frameTimeTimed: List<TimedSample>,
    val jankTimed: List<TimedSample>,
    val stutterTimed: List<TimedSample>,
    val captureStartTime: Long,
    val sessionEndMs: Long,
)
