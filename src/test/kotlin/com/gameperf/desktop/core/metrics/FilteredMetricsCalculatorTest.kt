package com.gameperf.desktop.core.metrics

import com.gameperf.desktop.core.events.Confidence
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.events.EventType
import com.gameperf.desktop.viewmodel.TimedSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests for [FilteredMetricsCalculator].
 *
 * No mocks — fixtures are constructed via the public data-class constructors
 * (per CLAUDE.md "tests puros sin mocks" rule).
 *
 * Coverage matrix:
 *   - FLT-002: filtered excludes ad window samples.
 *   - FLT-003: symmetric padding around event start/end.
 *   - FLT-005: >70% exclusion triggers fallback to raw.
 *   - FLT-006: empty events ⇒ filtered ≡ raw.
 *   - FLT-007: overlapping/adjacent ranges are unioned, no double-exclusion.
 */
class FilteredMetricsCalculatorTest {

    // ─────────────────────────────────────────────────────────────────
    // Fixture builders
    // ─────────────────────────────────────────────────────────────────

    /** Capture started at this absolute epoch-ms across most fixtures. */
    private val capStart = 1_000_000L

    /** Generates one TimedSample per second from `0..endSec - 1`. */
    private fun fpsSeries(endSec: Int, value: Int): List<TimedSample> =
        (0 until endSec).map { TimedSample(it, value.toDouble()) }

    /** Mixed FPS series: `outsideFps` outside the event, `insideFps` inside. */
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

    /** Build a FilterInput where every metric series mirrors the FPS series. */
    private fun input(
        fps: List<TimedSample>,
        cpu: List<TimedSample> = fps.map { TimedSample(it.second, 50.0) },
        mem: List<TimedSample> = fps.map { TimedSample(it.second, 1000.0) },
    ): FilterInput = FilterInput(
        fpsTimed = fps,
        cpuTimed = cpu,
        memTimed = mem,
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

    /** Build an INTERSTITIAL DetectedEvent at `[startSec, endSec]` in absolute epoch. */
    private fun event(startSec: Int, endSec: Int?): DetectedEvent = DetectedEvent(
        type = EventType.INTERSTITIAL,
        sdkSource = "Test",
        startMs = capStart + startSec * 1000L,
        endMs = endSec?.let { capStart + it * 1000L },
        confidence = Confidence.HIGH,
        signatureMatched = "test",
    )

    // ─────────────────────────────────────────────────────────────────
    // FLT-002: filtered excludes ad-window samples
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `filter fires - 60s session with one ad in middle drops avgFps inside event`() {
        // 60s session at 60fps EXCEPT seconds [20..30] which spiked to 200fps
        // (an ad WebView spike). Without filtering avg is inflated; with
        // filtering it should equal 60 exactly because every spike sample is
        // excluded.
        val fps = fpsMixed(60, 20, 30, outsideFps = 60, insideFps = 200)
        val ev = event(20, 30) // padded to [19.5s, 30.5s] ⇒ window in ms [19500..30500]

        val filtered = FilteredMetricsCalculator.compute(
            input(fps),
            FilteredMetricsCalculator.unionRanges(listOf(ev)),
        )
        val raw = FilteredMetricsCalculator.compute(input(fps), emptyList())

        assertEquals(60, filtered.avgFps, "filtered avg should equal the outside-event value")
        assertTrue(raw.avgFps > 60, "raw avg should be inflated by the spike")
        // Per-second granularity: sec*1000 must fall in [19500..30500] to be excluded.
        // sec=19 → 19000 (out, kept). sec=20 → 20000 (in). … sec=30 → 30000 (in).
        // sec=31 → 31000 (out, kept). So 11 excluded, 49 kept.
        assertEquals(49, filtered.sampleCount)
        assertEquals(60, raw.sampleCount)
    }

    // ─────────────────────────────────────────────────────────────────
    // FLT-006: no-op when no events
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `no events - filtered equals raw`() {
        val fps = fpsSeries(60, value = 60)
        val result = FilteredMetricsCalculator.computeWithFallback(input(fps), emptyList())
        assertFalse(result.excessiveFiltering)
        assertEquals(0, result.excludedRangeCount)
        assertEquals(result.raw.avgFps, result.filtered.avgFps)
        assertEquals(result.raw.sampleCount, result.filtered.sampleCount)
    }

    // ─────────────────────────────────────────────────────────────────
    // FLT-003: symmetric padding
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `padding - 500ms symmetric expands the excluded window`() {
        // Event at [10s, 15s] ⇒ padded to [9.5s, 15.5s].
        // contains() is inclusive on both sides ⇒ second*1000 in {9500..15500}.
        // Sample at second=9 ⇒ 9000ms, NOT in [9500..15500] → KEPT.
        // Sample at second=10 ⇒ 10000ms, in window → EXCLUDED.
        // Sample at second=15 ⇒ 15000ms, in window → EXCLUDED.
        // Sample at second=16 ⇒ 16000ms, NOT in window → KEPT.
        val fps = fpsSeries(20, value = 60)
        val ranges = FilteredMetricsCalculator.unionRanges(listOf(event(10, 15)))
        assertEquals(1, ranges.size)
        assertEquals(9_500L + capStart, ranges[0].startMs)
        assertEquals(15_500L + capStart, ranges[0].endMs)

        val filtered = FilteredMetricsCalculator.compute(input(fps), ranges)
        // Excluded: secs 10..15 → 6 excluded, 14 kept.
        assertEquals(14, filtered.sampleCount)
    }

    // ─────────────────────────────────────────────────────────────────
    // FLT-007: overlapping events are unioned
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `overlap - two adjacent events are unioned into one range`() {
        // [10..14] and [13..16] ⇒ padded to [9.5..14.5] and [12.5..16.5]
        // ⇒ they overlap ⇒ unioned to [9.5..16.5].
        val ranges = FilteredMetricsCalculator.unionRanges(
            listOf(event(10, 14), event(13, 16)),
        )
        assertEquals(1, ranges.size, "overlapping ranges must be unioned")
        assertEquals(9_500L + capStart, ranges[0].startMs)
        assertEquals(16_500L + capStart, ranges[0].endMs)
    }

    @Test
    fun `non-overlapping - two disjoint events stay separate`() {
        // [10..12] and [20..22] ⇒ padded to [9.5..12.5] and [19.5..22.5]
        // ⇒ disjoint ⇒ 2 ranges.
        val ranges = FilteredMetricsCalculator.unionRanges(
            listOf(event(10, 12), event(20, 22)),
        )
        assertEquals(2, ranges.size)
        // sorted by startMs ascending
        assertTrue(ranges[0].startMs < ranges[1].startMs)
    }

    // ─────────────────────────────────────────────────────────────────
    // Boundary semantics — TimeRange.contains is inclusive on both ends
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `boundary - sample at exact padded endMs is excluded`() {
        // Event at [10s, 15s] ⇒ padded to [9.5s, 15.5s].
        // Inject a sample EXACTLY at 15500ms (i.e., 15.5 seconds — but TimedSample.second
        // is Int so we need integer-second granularity). Use second=15 ⇒ 15000ms,
        // which is well within the padded window. Then verify second=16 ⇒ 16000ms is OUT.
        val fps = listOf(TimedSample(15, 60.0), TimedSample(16, 60.0))
        val ranges = FilteredMetricsCalculator.unionRanges(listOf(event(10, 15)))
        val filtered = FilteredMetricsCalculator.compute(input(fps), ranges)
        // sec 15 excluded (in [9500, 15500]); sec 16 kept (16000 > 15500).
        assertEquals(1, filtered.sampleCount)
    }

    // ─────────────────────────────────────────────────────────────────
    // FLT-005: excessive filtering falls back to raw
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `excessive - 80 percent exclusion triggers fallback`() {
        // 60s session, single event covering [5s..55s] ⇒ padded [4.5s..55.5s]
        // ⇒ excludes secs 5..55 → 51 excluded, 9 kept ⇒ 85% excluded.
        val fps = fpsSeries(60, value = 60)
        val result = FilteredMetricsCalculator.computeWithFallback(
            input(fps),
            listOf(event(5, 55)),
        )
        assertTrue(result.excessiveFiltering, "85% exclusion must flip the flag")
        // filtered should be swapped to raw
        assertEquals(result.raw.sampleCount, result.filtered.sampleCount)
        assertEquals(result.raw.avgFps, result.filtered.avgFps)
        assertEquals(1, result.excludedRangeCount)
    }

    @Test
    fun `non-excessive - 50 percent exclusion still returns filtered`() {
        // 60s session, event [15s..44s] padded to [14.5..44.5] excludes secs 15..44
        // → 30 excluded, 30 kept = 50% excluded — UNDER the 70% threshold.
        // Use mixed FPS so filtered≠raw and we can verify which branch was taken.
        val fps = fpsMixed(60, 15, 44, outsideFps = 60, insideFps = 200)
        val result = FilteredMetricsCalculator.computeWithFallback(
            input(fps),
            listOf(event(15, 44)),
        )
        assertFalse(result.excessiveFiltering)
        assertEquals(60, result.filtered.avgFps)
        assertTrue(result.raw.avgFps > 60)
    }

    // ─────────────────────────────────────────────────────────────────
    // EVT-005 edge: open events (endMs == null) are skipped, not exclude-everything
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `open event with no endMs is skipped from filtering`() {
        val fps = fpsSeries(60, value = 60)
        val openEvent = event(10, null)
        val ranges = FilteredMetricsCalculator.unionRanges(listOf(openEvent))
        assertTrue(ranges.isEmpty(), "events with endMs == null must produce zero ranges")

        val result = FilteredMetricsCalculator.computeWithFallback(input(fps), listOf(openEvent))
        assertFalse(result.excessiveFiltering)
        assertEquals(0, result.excludedRangeCount)
        assertEquals(result.raw.avgFps, result.filtered.avgFps)
    }

    // ─────────────────────────────────────────────────────────────────
    // Defensive: events outside the capture window
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `event ending before captureStart produces no real exclusion`() {
        // Build an event at absolute [capStart-10s, capStart-5s] ⇒ entirely before capture.
        val before = DetectedEvent(
            type = EventType.INTERSTITIAL,
            sdkSource = "Test",
            startMs = capStart - 10_000L,
            endMs = capStart - 5_000L,
            confidence = Confidence.HIGH,
            signatureMatched = "test",
        )
        val fps = fpsSeries(60, value = 60)
        val ranges = FilteredMetricsCalculator.unionRanges(listOf(before))
        // Padded range ends at capStart - 4500 (still negative when made relative).
        val filtered = FilteredMetricsCalculator.compute(input(fps), ranges)
        assertEquals(60, filtered.sampleCount, "no real samples should be excluded")
    }

    // ─────────────────────────────────────────────────────────────────
    // Empty input edge cases
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `empty fps series returns EMPTY aggregates`() {
        val emptyInput = input(emptyList())
        val result = FilteredMetricsCalculator.compute(emptyInput, emptyList())
        assertEquals(MetricsAggregates.EMPTY, result)
    }

    @Test
    fun `unionRanges on empty list returns empty`() {
        assertTrue(FilteredMetricsCalculator.unionRanges(emptyList()).isEmpty())
    }

    @Test
    fun `unionRanges drops events with endMs less than startMs defensively`() {
        val malformed = DetectedEvent(
            type = EventType.INTERSTITIAL,
            sdkSource = "Test",
            startMs = capStart + 10_000L,
            endMs = capStart + 5_000L,  // end before start — malformed
            confidence = Confidence.HIGH,
            signatureMatched = "test",
        )
        val ranges = FilteredMetricsCalculator.unionRanges(listOf(malformed))
        assertTrue(ranges.isEmpty(), "malformed events must be skipped, not throw")
    }

    // ─────────────────────────────────────────────────────────────────
    // Percentile sanity: implementation correctness
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `percentile via fps series - p50 of 1 to 5 is 3`() {
        // [1, 2, 3, 4, 5] → linear interp p50: rank = 0.5*4 = 2.0 ⇒ sorted[2] = 3.
        val fps = listOf(1, 2, 3, 4, 5).mapIndexed { i, v -> TimedSample(i, v.toDouble()) }
        val agg = FilteredMetricsCalculator.compute(input(fps), emptyList())
        assertEquals(3, agg.p50)
        assertEquals(1, agg.p1)   // rank≈0.04 ⇒ ~1.04 → toInt=1
        // p99: rank = 0.99*4 = 3.96 ⇒ sorted[3]=4 + 0.96*(5-4) = 4.96 ⇒ toInt=4.
        // (See `percentile - p99 of 1 to 5 truncates to 4` for the full derivation.)
        assertEquals(4, agg.p99)
    }

    @Test
    fun `percentile - p99 of 1 to 5 truncates to 4`() {
        // rank = 0.99 * 4 = 3.96 ⇒ sorted[3] + 0.96 * (5 - 4) = 4 + 0.96 = 4.96 ⇒ toInt = 4.
        val fps = listOf(1, 2, 3, 4, 5).mapIndexed { i, v -> TimedSample(i, v.toDouble()) }
        val agg = FilteredMetricsCalculator.compute(input(fps), emptyList())
        assertEquals(4, agg.p99)
    }

    // ─────────────────────────────────────────────────────────────────
    // unionRanges — explicit return contract
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `unionRanges - already sorted disjoint inputs preserved`() {
        val ranges = FilteredMetricsCalculator.unionRanges(
            listOf(event(10, 11), event(30, 31), event(50, 51)),
        )
        assertEquals(3, ranges.size)
        assertTrue(ranges[0].startMs < ranges[1].startMs)
        assertTrue(ranges[1].startMs < ranges[2].startMs)
    }

    @Test
    fun `unionRanges - reversed inputs are sorted before merging`() {
        val ranges = FilteredMetricsCalculator.unionRanges(
            listOf(event(50, 51), event(10, 11)),
        )
        assertEquals(2, ranges.size)
        assertTrue(ranges[0].startMs < ranges[1].startMs)
    }
}
