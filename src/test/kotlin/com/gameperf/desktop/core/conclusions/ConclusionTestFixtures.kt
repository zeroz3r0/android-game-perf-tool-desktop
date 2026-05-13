package com.gameperf.desktop.core.conclusions

import com.gameperf.desktop.core.HardwareScoring
import com.gameperf.desktop.core.events.Confidence
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.events.EventType
import com.gameperf.desktop.core.metrics.MetricsAggregates
import com.gameperf.desktop.viewmodel.TimedSample

/**
 * Shared fixture builders for rule and engine tests.
 *
 * Pure: every helper here is a deterministic constructor over plain values.
 * Tests prefer the named-argument copy idiom — `aggregates(p50 = 18)` — so
 * each test file only spells out the fields it actually exercises.
 */
internal object ConclusionTestFixtures {

    /**
     * A "perfect" baseline session: 60 fps stable, 30% CPU, cool device,
     * no jank, no events. Every rule's predicate evaluates to false on it.
     * Tests override the few fields they care about.
     */
    fun aggregates(
        avgFps: Int = 60,
        minFps: Int = 58,
        maxFps: Int = 60,
        p1: Int = 58,
        p5: Int = 58,
        p50: Int = 60,
        p90: Int = 60,
        p99: Int = 60,
        avgFrameTime: Double = 16.6,
        p99FrameTime: Double = 16.6,
        peakMem: Long = 200L,
        avgCpu: Int = 30,
        maxCpu: Int = 45,
        maxTempCpu: Double = 35.0,
        maxTempGpu: Double = 35.0,
        maxTempSkin: Double = 32.0,
        maxTempDieCpu: Double = 40.0,
        totalJank: Long = 0L,
        totalStutter: Int = 0,
        sampleCount: Int = 120,
    ): MetricsAggregates = MetricsAggregates(
        avgFps = avgFps,
        minFps = minFps,
        maxFps = maxFps,
        p1 = p1,
        p5 = p5,
        p50 = p50,
        p90 = p90,
        p99 = p99,
        avgFrameTime = avgFrameTime,
        p99FrameTime = p99FrameTime,
        peakMem = peakMem,
        avgCpu = avgCpu,
        maxCpu = maxCpu,
        maxTempCpu = maxTempCpu,
        maxTempGpu = maxTempGpu,
        maxTempSkin = maxTempSkin,
        maxTempDieCpu = maxTempDieCpu,
        totalJank = totalJank,
        totalStutter = totalStutter,
        sampleCount = sampleCount,
    )

    /** Default ConclusionInput where no rule fires. Tests override the fields they need. */
    fun input(
        filtered: MetricsAggregates = aggregates(),
        raw: MetricsAggregates = filtered,
        targetFps: Int = 60,
        deviceTier: HardwareScoring.DeviceTier = HardwareScoring.DeviceTier.MID,
        events: List<DetectedEvent> = emptyList(),
        sessionDurationS: Int = 120,
        memTimedFiltered: List<TimedSample> = emptyList(),
        tempCpuTimedFiltered: List<TimedSample> = emptyList(),
        fpsTimedFiltered: List<TimedSample> = emptyList(),
        thermalAvailable: Boolean = true,
    ): ConclusionInput = ConclusionInput(
        filtered = filtered,
        raw = raw,
        targetFps = targetFps,
        deviceTier = deviceTier,
        events = events,
        sessionDurationS = sessionDurationS,
        memTimedFiltered = memTimedFiltered,
        tempCpuTimedFiltered = tempCpuTimedFiltered,
        fpsTimedFiltered = fpsTimedFiltered,
        thermalAvailable = thermalAvailable,
    )

    /** Builds a closed event with sensible defaults. */
    fun event(
        type: EventType = EventType.INTERSTITIAL,
        sdkSource: String = "AdMob",
        startMs: Long = 1_000L,
        endMs: Long? = 6_000L,
        confidence: Confidence = Confidence.HIGH,
        signatureMatched: String = "test",
    ): DetectedEvent = DetectedEvent(
        type = type,
        sdkSource = sdkSource,
        startMs = startMs,
        endMs = endMs,
        confidence = confidence,
        signatureMatched = signatureMatched,
    )

    /** Builds a memory series that grows linearly at `slopeMbPerS`. */
    fun memSeries(
        durationS: Int,
        startMb: Double,
        slopeMbPerS: Double,
    ): List<TimedSample> = (0 until durationS).map { sec ->
        TimedSample(sec, startMb + slopeMbPerS * sec)
    }

    /** Builds a flat (no-growth) memory series. */
    fun flatMemSeries(durationS: Int, valueMb: Double): List<TimedSample> =
        (0 until durationS).map { sec -> TimedSample(sec, valueMb) }
}
