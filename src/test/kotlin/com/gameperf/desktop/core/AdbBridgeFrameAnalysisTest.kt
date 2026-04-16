package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AdbBridge.computeFrameSnapshot] and [AdbBridge.inferTargetFrameTime].
 *
 * These are the v4.2.5 reliability tests. The user's complaint was that frame metrics
 * (jank in particular) were systematically wrong: a 30fps-target game showed all of
 * its frames as "jank" because the threshold was hardcoded at 16.67ms instead of
 * scaling with the actual target frame time. These tests pin the new dynamic
 * threshold and verify it produces meaningful numbers across the four common target
 * FPS regimes (120 / 60 / 45 / 30).
 *
 * The functions are pure (take a list of timestamps in nanoseconds, return a
 * FrameSnapshot or null) so they can be exercised without any ADB connection.
 */
class AdbBridgeFrameAnalysisTest {

    // ═══════ inferTargetFrameTime — bucket boundaries ═══════

    @Test
    fun `inferTargetFrameTime maps very fast frames to 120fps target`() {
        // 8.33ms = 120fps target (8.33 * 1.5 = 12.5ms jank threshold)
        assertEquals(8.33, AdbBridge.inferTargetFrameTime(8.0), 0.001)
        assertEquals(8.33, AdbBridge.inferTargetFrameTime(12.0), 0.001) // boundary
    }

    @Test
    fun `inferTargetFrameTime maps 60fps-range frames to 60fps target`() {
        // 16.67ms = 60fps target (16.67 * 1.5 = 25ms jank threshold)
        assertEquals(16.67, AdbBridge.inferTargetFrameTime(13.0), 0.001)
        assertEquals(16.67, AdbBridge.inferTargetFrameTime(16.5), 0.001)
        assertEquals(16.67, AdbBridge.inferTargetFrameTime(18.0), 0.001) // boundary
    }

    @Test
    fun `inferTargetFrameTime maps 45fps-range frames to 45fps target`() {
        // 22.22ms = 45fps target — Unity Auto mode on mid-range hardware lands here
        assertEquals(22.22, AdbBridge.inferTargetFrameTime(19.0), 0.001)
        assertEquals(22.22, AdbBridge.inferTargetFrameTime(28.0), 0.001) // boundary
    }

    @Test
    fun `inferTargetFrameTime maps 30fps-range frames to 30fps target`() {
        // 33.33ms = 30fps target — battery-saver mode, low-end devices, intentional caps
        // THIS IS THE BUG-FIX BUCKET. Pre-v4.2.5, a stable 30fps game had all its
        // frames marked as jank because the threshold was 16.67ms. Now jank threshold
        // for this bucket is 33.33 * 1.5 = 50ms — frames at 33ms are correctly NOT jank.
        assertEquals(33.33, AdbBridge.inferTargetFrameTime(29.0), 0.001)
        assertEquals(33.33, AdbBridge.inferTargetFrameTime(33.0), 0.001)
        assertEquals(33.33, AdbBridge.inferTargetFrameTime(40.0), 0.001) // boundary
    }

    @Test
    fun `inferTargetFrameTime maps very slow frames to fallback bucket`() {
        // >40ms avg = the game is broken / overheated / GC-thrashing. Anything that
        // takes >75ms (50 * 1.5) gets counted as jank — at this point everything is.
        assertEquals(50.0, AdbBridge.inferTargetFrameTime(45.0), 0.001)
        assertEquals(50.0, AdbBridge.inferTargetFrameTime(100.0), 0.001)
        assertEquals(50.0, AdbBridge.inferTargetFrameTime(500.0), 0.001)
    }

    // ═══════ computeFrameSnapshot — happy path ═══════

    @Test
    fun `computeFrameSnapshot returns null for empty input`() {
        assertNull(AdbBridge.computeFrameSnapshot(emptyList()))
    }

    @Test
    fun `computeFrameSnapshot returns null for single timestamp`() {
        assertNull(AdbBridge.computeFrameSnapshot(listOf(1_000_000_000L)))
    }

    @Test
    fun `computeFrameSnapshot computes 60fps correctly from regular timestamps`() {
        // 60 frames spaced exactly 16.67ms apart over 1 second.
        val frameInterval = 16_666_667L // ns
        val timestamps = (0..59).map { it * frameInterval }
        val snap = AdbBridge.computeFrameSnapshot(timestamps)

        assertNotNull(snap)
        // FPS over the last 1s should be exactly 60.
        assertEquals(60, snap.fps, "60fps test must report fps=60")
        // Average frame time ~16.67ms.
        assertTrue(snap.avgFrameTime in 16.5..16.8, "avg frame time must be ~16.67ms, was ${snap.avgFrameTime}")
        // No jank — every frame is exactly on target.
        assertEquals(0, snap.jankCount, "perfectly steady 60fps must have ZERO jank")
        assertEquals(0, snap.stutterCount)
    }

    @Test
    fun `computeFrameSnapshot does NOT mark stable 30fps as jank - the v4_2_5 bug fix`() {
        // 30 frames spaced exactly 33.33ms apart over 1 second.
        // PRE-v4.2.5: jank threshold was hardcoded 16.67ms -> 30 jank frames out of 30
        //              (every single frame). Useless metric for 30fps games.
        // POST-v4.2.5: jank threshold is 33.33 * 1.5 = 50ms -> 0 jank, as expected.
        val frameInterval = 33_333_333L // ns
        val timestamps = (0..29).map { it * frameInterval }
        val snap = AdbBridge.computeFrameSnapshot(timestamps)

        assertNotNull(snap)
        assertEquals(30, snap.fps, "stable 30fps must report fps=30")
        assertTrue(snap.avgFrameTime in 33.0..33.5)
        assertEquals(
            0,
            snap.jankCount,
            "REGRESSION GUARD: a stable 30fps game must have ZERO jank. " +
                "Pre-v4.2.5 the hardcoded 16.67ms threshold made every frame count as jank."
        )
        assertEquals(0, snap.stutterCount)
    }

    @Test
    fun `computeFrameSnapshot detects real jank in a 60fps stream with hiccups`() {
        // 30 normal 60fps frames + 5 frames at 30ms (definitely jank for a 60fps game).
        // Jank threshold for 60fps target = 16.67 * 1.5 = 25ms. 30ms > 25ms = jank.
        val timestamps = mutableListOf(0L)
        var t = 0L
        repeat(30) { t += 16_666_667L; timestamps.add(t) }
        repeat(5) { t += 30_000_000L; timestamps.add(t) }
        repeat(30) { t += 16_666_667L; timestamps.add(t) }

        val snap = AdbBridge.computeFrameSnapshot(timestamps)
        assertNotNull(snap)
        assertEquals(5, snap.jankCount, "exactly 5 jank frames (the 30ms ones)")
        assertEquals(0, snap.stutterCount, "30ms is jank but not stutter (>100ms)")
    }

    @Test
    fun `computeFrameSnapshot detects stutter for frames over 100ms`() {
        // 60fps stream with one 200ms freeze.
        val timestamps = mutableListOf(0L)
        var t = 0L
        repeat(20) { t += 16_666_667L; timestamps.add(t) }
        t += 200_000_000L; timestamps.add(t) // single freeze frame
        repeat(20) { t += 16_666_667L; timestamps.add(t) }

        val snap = AdbBridge.computeFrameSnapshot(timestamps)
        assertNotNull(snap)
        assertTrue(snap.jankCount >= 1, "the 200ms freeze must count as jank")
        assertEquals(1, snap.stutterCount, "exactly one stutter (the 200ms freeze)")
    }

    // ═══════ computeFrameSnapshot — boundary / robustness ═══════

    @Test
    fun `computeFrameSnapshot caps fps at MAX_FPS for absurdly fast streams`() {
        // 500 frames in 1 second = 500fps theoretical. Cap is 240.
        val frameInterval = 2_000_000L // 2ms = 500 fps
        val timestamps = (0..500).map { it * frameInterval }
        val snap = AdbBridge.computeFrameSnapshot(timestamps)
        assertNotNull(snap)
        assertTrue(snap.fps <= AdbBridge.MAX_FPS, "fps must be capped at ${AdbBridge.MAX_FPS}, was ${snap.fps}")
    }

    @Test
    fun `computeFrameSnapshot allows fps up to 240Hz for high-refresh phones`() {
        // ~200fps, well within the new 240 cap. Pre-v4.2.5 cap was 144 — would
        // have rounded down silently here, hiding real perf data on Razer/ROG/OnePlus.
        val frameInterval = 5_000_000L // 5ms = 200 fps
        val timestamps = (0..200).map { it * frameInterval }
        val snap = AdbBridge.computeFrameSnapshot(timestamps)
        assertNotNull(snap)
        assertTrue(snap.fps in 195..210, "200fps stream must report ~200fps, not be capped at 144 (was ${snap.fps})")
    }

    @Test
    fun `computeFrameSnapshot retains hangs up to 5 seconds`() {
        // A 4.5-second hang must be retained as a single very-long frame time.
        // PRE-v4.2.5 the cap was 1000ms → this 4500ms frame was silently discarded
        // and the user lost evidence of a multi-second freeze.
        val timestamps = listOf(
            0L,
            16_666_667L,
            16_666_667L + 4_500_000_000L, // +4.5s
            16_666_667L + 4_500_000_000L + 16_666_667L,
        )
        val snap = AdbBridge.computeFrameSnapshot(timestamps)
        assertNotNull(snap)
        assertTrue(snap.stutterCount >= 1, "a 4.5s hang MUST be visible in stutterCount")
    }

    @Test
    fun `computeFrameSnapshot discards bogus negative or zero frame times`() {
        // Real /proc data sometimes has clock-reset artifacts producing 0 or
        // tiny intervals. computeFrameSnapshot should silently drop them.
        val timestamps = listOf(
            0L,
            0L, // 0ms interval — bogus
            16_666_667L, // back to normal
            16_666_667L + 50_000L, // 50us interval — bogus (below 0.1ms floor)
            16_666_667L + 16_666_667L,
        )
        val snap = AdbBridge.computeFrameSnapshot(timestamps)
        assertNotNull(snap)
        // Average should be ~16.67ms (the bogus intervals are filtered out).
        assertTrue(snap.avgFrameTime > 10.0, "bogus intervals must not pull the avg toward zero")
    }
}
