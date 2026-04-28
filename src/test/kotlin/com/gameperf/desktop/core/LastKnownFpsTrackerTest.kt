package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [LastKnownFpsTracker] — pure last-known-FPS fallback logic.
 *
 * Background — the FPS-resume-after-ad bug (v4.3.5): when an ad SDK destroys
 * and recreates the host SurfaceView, the AdbBridge layer cache transiently
 * locks onto a zombie layer. captureFrames returns null for 1-3 polls until
 * the cache is invalidated and a fresh dumpsys --list re-discovers the new
 * layer. During those 0.5-1.5 seconds the live HUD used to flicker between
 * the real FPS and "--", which made the user think capture itself had broken.
 *
 * The fix: while we have a recent (≤[LastKnownFpsTracker.windowMs]) valid
 * reading, re-emit it instead of zero. After the window closes we go back to
 * zero so a long-term stall surfaces as "--" — the user MUST see when
 * something is genuinely wrong vs. a transient ad-close blip.
 *
 * Pure function tests because the tracker has no side effects and no async
 * behavior — just `(rawFps, nowMs) -> displayFps` with internal state.
 */
class LastKnownFpsTrackerTest {

    @Test
    fun `passes through positive raw value`() {
        val t = LastKnownFpsTracker(windowMs = 1500)
        assertEquals(60, t.update(rawFps = 60, nowMs = 1000L))
    }

    @Test
    fun `emits last known when raw is zero within window`() {
        val t = LastKnownFpsTracker(windowMs = 1500)
        assertEquals(30, t.update(rawFps = 30, nowMs = 1000L))
        // 500ms later — well within window — null/zero raw must be replaced.
        assertEquals(30, t.update(rawFps = 0, nowMs = 1500L))
    }

    @Test
    fun `emits zero when raw is zero outside window`() {
        val t = LastKnownFpsTracker(windowMs = 1500)
        assertEquals(45, t.update(rawFps = 45, nowMs = 0L))
        // 2s later — past the 1.5s window — fall back to zero so the HUD
        // shows "--". This guarantees the user sees long-term stalls.
        assertEquals(0, t.update(rawFps = 0, nowMs = 2000L))
    }

    @Test
    fun `boundary equals window expires the cache`() {
        // Strict less-than comparison: at exactly windowMs we expire. This
        // matches the production constant of 1500ms with the 500ms poll
        // cadence — the third null poll lands at 1500ms exactly, which is
        // when we want to give up the sticky value.
        val t = LastKnownFpsTracker(windowMs = 1500)
        assertEquals(60, t.update(rawFps = 60, nowMs = 0L))
        assertEquals(0, t.update(rawFps = 0, nowMs = 1500L), "boundary must expire")
    }

    @Test
    fun `recovery refreshes the window`() {
        // Real ad-close flow: 30, null, null (within window so still 30),
        // 29 (recovery, refreshes the window), 29.
        val t = LastKnownFpsTracker(windowMs = 1500)
        assertEquals(30, t.update(rawFps = 30, nowMs = 0L))
        assertEquals(30, t.update(rawFps = 0, nowMs = 500L))
        assertEquals(30, t.update(rawFps = 0, nowMs = 1000L))
        assertEquals(29, t.update(rawFps = 29, nowMs = 1500L))
        assertEquals(29, t.update(rawFps = 29, nowMs = 2000L))
    }

    @Test
    fun `initial zero before any positive reading stays zero`() {
        // Cold start: no previous value yet, raw is null/zero. Tracker must
        // NOT invent a value out of thin air.
        val t = LastKnownFpsTracker(windowMs = 1500)
        assertEquals(0, t.update(rawFps = 0, nowMs = 0L))
        assertEquals(0, t.update(rawFps = 0, nowMs = 800L))
    }

    @Test
    fun `negative raw is treated as zero`() {
        // Defensive: shouldn't happen (FrameSnapshot.fps is coerceIn(1, 240))
        // but a negative input must NOT corrupt the tracker. Treat as null.
        val t = LastKnownFpsTracker(windowMs = 1500)
        assertEquals(60, t.update(rawFps = 60, nowMs = 0L))
        assertEquals(60, t.update(rawFps = -1, nowMs = 500L))
    }

    @Test
    fun `independent trackers do not share state`() {
        // Sanity check that there's no static state leaking between instances.
        val a = LastKnownFpsTracker(windowMs = 1500)
        val b = LastKnownFpsTracker(windowMs = 1500)
        assertEquals(60, a.update(rawFps = 60, nowMs = 0L))
        assertEquals(0, b.update(rawFps = 0, nowMs = 0L))
    }
}
