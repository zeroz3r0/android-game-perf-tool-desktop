package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.events.Confidence
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.events.EventType
import com.gameperf.desktop.core.metrics.FilterInput
import com.gameperf.desktop.core.metrics.FilteredMetricsCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end aggregation test mirroring what
 * [com.gameperf.desktop.viewmodel.AppViewModel.startCapture] does post-loop.
 *
 * NOT driving the real ViewModel through a fake capture — that requires
 * orchestrating logcat fixtures + dumpsys + ScreenRecord, which is heavy and
 * brittle. Per the SDD task plan (T3.7 fallback option), we replay the exact
 * sequence of calls the orchestrator makes (`FilteredMetricsCalculator.computeWithFallback`
 * → `GradingInput` → `SessionResult`) against a programmatic FilterInput and
 * assert the contracts:
 *
 *  - When events are present and FPS-inside-event differs from FPS-outside,
 *    `filtered.avgFps != raw.avgFps`.
 *  - `_result.filteredAggregates` and `_result.rawAggregates` are both populated.
 *  - `_result.events` carries the detected events.
 *  - When no events exist, filtered ≡ raw (back-compat with pre-v4.4.0 behavior).
 *
 * Spec coverage: FLT-004 scenario "filtered.avgFps != raw.avgFps for sessions
 * containing events".
 */
class AppViewModelAggregationTest {

    private val capStart = 1_000_000L

    private fun fpsMixed(
        endSec: Int,
        eventStartSec: Int,
        eventEndSec: Int,
        outsideFps: Int,
        insideFps: Int,
    ): List<TimedSample> = (0 until endSec).map { sec ->
        val v = if (sec in eventStartSec..eventEndSec) insideFps else outsideFps
        TimedSample(sec, v.toDouble())
    }

    private fun input(fps: List<TimedSample>): FilterInput = FilterInput(
        fpsTimed = fps,
        cpuTimed = fps.map { TimedSample(it.second, 50.0) },
        memTimed = fps.map { TimedSample(it.second, 1000.0) },
        nativeTimed = emptyList(),
        javaTimed = emptyList(),
        tempCpuTimed = emptyList(),
        tempGpuTimed = emptyList(),
        tempSkinTimed = emptyList(),
        tempDieCpuTimed = emptyList(),
        frameTimeTimed = emptyList(),
        jankTimed = emptyList(),
        stutterTimed = emptyList(),
        captureStartTime = capStart,
        sessionEndMs = (fps.size * 1000L),
    )

    private fun event(startSec: Int, endSec: Int): DetectedEvent = DetectedEvent(
        type = EventType.INTERSTITIAL,
        sdkSource = "AdMob",
        startMs = capStart + startSec * 1000L,
        endMs = capStart + endSec * 1000L,
        confidence = Confidence.HIGH,
        signatureMatched = "test-fixture",
    )

    @Test
    fun `session with detected ad - filtered avgFps differs from raw`() {
        // Game runs at 60 fps EXCEPT during an ad [20..30] where the FPS HUD
        // measures 200 fps (ad WebView spike). Filtered should drop the spike.
        val fps = fpsMixed(60, 20, 30, outsideFps = 60, insideFps = 200)
        val events = listOf(event(20, 30))

        val result = FilteredMetricsCalculator.computeWithFallback(input(fps), events)

        // Both views populated
        assertNotEquals(0, result.raw.sampleCount)
        assertNotEquals(0, result.filtered.sampleCount)
        // FLT-004 scenario: filtered ≠ raw on a session with detected events
        assertNotEquals(result.raw.avgFps, result.filtered.avgFps)
        assertTrue(result.filtered.avgFps < result.raw.avgFps)
        assertFalse(result.excessiveFiltering)
        assertEquals(1, result.excludedRangeCount)
    }

    @Test
    fun `clean session with no events - filtered equals raw`() {
        val fps = fpsMixed(60, 0, -1, outsideFps = 60, insideFps = 0)
        val result = FilteredMetricsCalculator.computeWithFallback(input(fps), emptyList())

        assertEquals(result.raw.avgFps, result.filtered.avgFps)
        assertEquals(result.raw.sampleCount, result.filtered.sampleCount)
        assertEquals(0, result.excludedRangeCount)
        assertFalse(result.excessiveFiltering)
    }

    @Test
    fun `SessionResult shape - both aggregates and events flow through`() {
        // Mirror the SessionResult construction in AppViewModel post-loop.
        val fps = fpsMixed(60, 20, 30, outsideFps = 60, insideFps = 200)
        val events = listOf(event(20, 30))
        val result = FilteredMetricsCalculator.computeWithFallback(input(fps), events)

        val sessionResult = SessionResult(
            gamePackage = "com.example.game",
            duration = 60,
            events = events,
            rawAggregates = result.raw,
            filteredAggregates = result.filtered,
        )

        // The fields exist and carry the expected shape — this is a compile-time
        // contract test that catches Phase 1 schema regressions.
        assertTrue(sessionResult.events.isNotEmpty())
        assertTrue(sessionResult.rawAggregates != null)
        assertTrue(sessionResult.filteredAggregates != null)
        assertNotEquals(
            sessionResult.rawAggregates!!.avgFps,
            sessionResult.filteredAggregates!!.avgFps,
        )
    }
}
