package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AdbBridge.parseFrameLatencyOutput].
 *
 * Background — v3.1.10 had a critical bug: `captureFrames` counted jank/stutter over the
 * ENTIRE SurfaceFlinger latency buffer (~127 frames ≈ 2 seconds) on every call. The
 * polling loop ran every 500ms, so successive calls overlapped by ~1.5s and the same
 * frames were counted 3-4 times by the running `totalJank += frame.jankCount` accumulator.
 *
 * v3.1.11 fixes this by tracking the last seen timestamp across calls. These tests lock
 * in the de-duplication behavior so a future refactor can't regress.
 *
 * The format of `dumpsys SurfaceFlinger --latency '<layer>'` output is:
 *
 *   <refresh-period-ns>
 *   <appPostedTime> <vsyncTime> <presentTime>
 *   <appPostedTime> <vsyncTime> <presentTime>
 *   ...
 *
 * Our parser uses the SECOND column (vsyncTime) as the frame timestamp.
 */
class FrameLatencyParserTest {

    /**
     * Build synthetic latency output. Each frame is one line with three timestamps;
     * we only care about the middle one (vsync). Times are nanoseconds.
     */
    private fun buildOutput(refreshNs: Long, vsyncTimes: List<Long>): String {
        val sb = StringBuilder()
        sb.append("$refreshNs\n")
        for (ts in vsyncTimes) {
            sb.append("$ts $ts $ts\n")
        }
        return sb.toString()
    }

    private val refresh16ms = 16_666_666L  // ~60 Hz refresh

    /** Generate a sequence of vsync timestamps starting at `start` with the given period. */
    private fun vsyncSeq(start: Long, periodMs: Long, count: Int): List<Long> =
        (0 until count).map { start + it * periodMs * 1_000_000L }

    // ===== First-call behavior =====

    @Test
    fun `first call with steady 60fps returns sensible fps and zero jank`() {
        // 120 frames at 60fps (16.67ms each) = 2 seconds of footage
        val times = vsyncSeq(start = 1_000_000_000L, periodMs = 16, count = 120)
        val output = buildOutput(refresh16ms, times)

        val (snapshot, lastSeen) = AdbBridge.parseFrameLatencyOutput(output, lastSeenTimestampNs = 0L)

        assertNotNull(snapshot)
        // 16ms period → ~62fps but coerced sensibly
        assertTrue(snapshot.fps in 50..70, "expected ~60fps, got ${snapshot.fps}")
        // 16ms < 16.67ms threshold so no jank
        assertEquals(0, snapshot.jankCount)
        assertEquals(0, snapshot.stutterCount)
        assertEquals(times.last(), lastSeen)
    }

    @Test
    fun `first call only counts the last 1-second window of frames not the entire buffer`() {
        // 120 frames at 16ms = 2 seconds. Inject 5 jank frames in the FIRST second of
        // the buffer (frames 5-9, well outside the 1-second window). The first-call
        // path should NOT count those because they're stale (we only count the most
        // recent 1s on first call).
        val times = vsyncSeq(start = 1_000_000_000L, periodMs = 16, count = 120).toMutableList()
        // Replace frames 5..9 with 30ms gaps (jank)
        for (i in 5..9) {
            times[i] = times[i - 1] + 30 * 1_000_000L
        }
        // Re-stretch the rest to keep them monotonic
        for (i in 10 until times.size) {
            times[i] = times[i - 1] + 16 * 1_000_000L
        }
        val output = buildOutput(refresh16ms, times)

        val (snapshot, _) = AdbBridge.parseFrameLatencyOutput(output, lastSeenTimestampNs = 0L)

        assertNotNull(snapshot)
        // The 5 jank frames are in the first ~150ms of the 2s buffer, well outside the
        // last 1s window. First-call path must skip them.
        assertEquals(0, snapshot.jankCount, "first-call path must only count last-1s frames")
    }

    // ===== De-duplication on subsequent calls =====

    @Test
    fun `subsequent call with no new frames returns zero jank`() {
        // Caller already saw all frames. Buffer hasn't been refreshed since.
        val times = vsyncSeq(start = 1_000_000_000L, periodMs = 16, count = 60)
        val output = buildOutput(refresh16ms, times)

        // lastSeen = the very last timestamp in the buffer (we've already processed it)
        val (snapshot, lastSeen) = AdbBridge.parseFrameLatencyOutput(
            output,
            lastSeenTimestampNs = times.last()
        )

        assertNotNull(snapshot)
        assertEquals(0, snapshot.jankCount, "no new frames → no new jank")
        assertEquals(0, snapshot.stutterCount)
        assertEquals(times.last(), lastSeen, "lastSeen should still advance to the buffer end")
    }

    @Test
    fun `subsequent call with 10 new frames only counts those 10`() {
        // Buffer has 100 frames. Caller's lastSeen is the timestamp of frame 89.
        // Only frames 90-99 are new. Inject 3 jank frames in 90-99 and 5 jank frames
        // BEFORE 90 (already-seen). Only the 3 should be counted.
        val times = vsyncSeq(start = 1_000_000_000L, periodMs = 16, count = 100).toMutableList()
        // Inject jank in already-seen region (frames 30-34)
        for (i in 30..34) {
            times[i] = times[i - 1] + 50 * 1_000_000L  // 50ms = jank
        }
        for (i in 35 until 90) {
            times[i] = times[i - 1] + 16 * 1_000_000L
        }
        // Inject 3 jank frames in NEW region (frames 90-92)
        for (i in 90..92) {
            times[i] = times[i - 1] + 50 * 1_000_000L  // 50ms = jank
        }
        for (i in 93 until 100) {
            times[i] = times[i - 1] + 16 * 1_000_000L
        }
        val output = buildOutput(refresh16ms, times)

        val lastSeen = times[89]
        val (snapshot, newLastSeen) = AdbBridge.parseFrameLatencyOutput(output, lastSeen)

        assertNotNull(snapshot)
        // Only frames 90-99 (well, 90-92 plus the delta from 89→90) are new and only
        // 90, 91, 92 have jank. Expected: 3 jank.
        assertEquals(3, snapshot.jankCount, "must only count new jank frames, not already-seen ones")
        assertEquals(times.last(), newLastSeen)
    }

    @Test
    fun `polling loop simulation - 4 calls do not over-count the same jank frames`() {
        // This is the regression test for the 4× over-count bug.
        // Build a 200-frame sequence where every frame at index N % 20 == 0 is jank
        // (10 jank frames total in 200). Simulate 4 polling iterations where the
        // buffer "advances" by 30 frames each time, with 90 frames of overlap.
        val totalFrames = 200
        val times = (0 until totalFrames).map { i ->
            // Base 16ms cadence, but every 20th frame is 50ms (jank)
            if (i == 0) 1_000_000_000L
            else {
                // Sum of all previous deltas
                var t = 1_000_000_000L
                for (j in 1..i) t += if (j % 20 == 0) 50_000_000L else 16_000_000L
                t
            }
        }
        // Sanity: count expected jank in the full sequence
        val expectedTotalJank = (0 until totalFrames).count { it != 0 && it % 20 == 0 }

        var lastSeen = 0L
        var totalJank = 0
        // Simulate 4 polling calls, each seeing a "window" of 110 frames that advances
        // by 30 frames per call. So:
        //   Call 1: frames 0..109
        //   Call 2: frames 30..139
        //   Call 3: frames 60..169
        //   Call 4: frames 90..199
        // Without de-dup, the overlapping frames would be counted multiple times.
        for (call in 0..3) {
            val start = call * 30
            val end = (start + 110).coerceAtMost(totalFrames)
            val window = times.subList(start, end)
            val output = buildOutput(refresh16ms, window)
            val (snapshot, newLastSeen) = AdbBridge.parseFrameLatencyOutput(output, lastSeen)
            if (snapshot != null) {
                totalJank += snapshot.jankCount
                lastSeen = newLastSeen
            }
        }

        // The de-duplication should ensure totalJank ≈ expectedTotalJank, NOT 4x that.
        // Allow small slop because the first-call window-limiting may skip 1-2 frames at
        // the start, and the last call may have edge-effects. Strict upper bound: NOT 2x.
        assertTrue(
            totalJank <= expectedTotalJank + 2,
            "expected ~$expectedTotalJank jank frames after de-dup, got $totalJank " +
                "(over-count regression)"
        )
        assertTrue(
            totalJank >= expectedTotalJank - 4,
            "expected ~$expectedTotalJank jank frames after de-dup, got $totalJank " +
                "(under-count — first-call window may be too restrictive)"
        )
    }

    // ===== Edge cases =====

    @Test
    fun `empty output returns null snapshot`() {
        val (snapshot, lastSeen) = AdbBridge.parseFrameLatencyOutput("", 0L)
        assertNull(snapshot)
        assertEquals(0L, lastSeen)
    }

    @Test
    fun `output with only refresh period and no frames returns null`() {
        val (snapshot, _) = AdbBridge.parseFrameLatencyOutput("16666666\n", 0L)
        assertNull(snapshot)
    }

    @Test
    fun `output with garbage timestamps is filtered out`() {
        val output = """
            16666666
            0 0 0
            -1 -1 -1
            ${Long.MAX_VALUE} ${Long.MAX_VALUE} ${Long.MAX_VALUE}
            1000000000 1000000000 1000000000
            1016666666 1016666666 1016666666
            1033333332 1033333332 1033333332
        """.trimIndent()

        val (snapshot, _) = AdbBridge.parseFrameLatencyOutput(output, 0L)
        assertNotNull(snapshot)
        // Only the 3 valid timestamps should be parsed → 2 frame deltas
        assertTrue(snapshot.fps > 0, "expected valid fps from filtered timestamps")
    }
}
