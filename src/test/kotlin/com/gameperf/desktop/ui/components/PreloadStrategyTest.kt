package com.gameperf.desktop.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [PreloadStrategy].
 *
 * Background — these tests lock in the fix for the bug where the playback
 * loop in [EmbeddedVideoPlayer] called `preloadWindow(idx)` every 50 frames,
 * which unconditionally called `killActiveFrameProcesses()` and murdered the
 * very ffmpeg jobs the background preloader had just spawned. The cache
 * never warmed → every frame became a cold extract (~80-200ms) → effective
 * playback rate ~5-7fps instead of 30 → user-reported "video plays at ~25%
 * speed".
 *
 * The fix splits the decision "reset (kill + restart)" vs "extend (let
 * in-flight finish)" out of [EmbeddedVideoPlayer] into this pure object so
 * it can be tested without spawning ffmpeg, mocking processes, or rendering
 * Compose UI. Per project convention (CLAUDE.md → "Tests puros sin mocks"),
 * complex logic gets extracted to a pure function and tested directly.
 */
class PreloadStrategyTest {

    // ===== shouldReset: first call =====

    @Test
    fun `shouldReset returns true on first call when lastCenter is null`() {
        // First preload trigger after init — no prior center to compare → must reset
        // (which here is a no-op since there are no in-flight jobs yet, but the
        // strategy must still report "reset" so the caller takes the scrub-window
        // branch and seeds the cache).
        assertTrue(PreloadStrategy.shouldReset(center = 0, lastCenter = null))
        assertTrue(PreloadStrategy.shouldReset(center = 1234, lastCenter = null))
    }

    // ===== shouldReset: backward jumps =====

    @Test
    fun `shouldReset returns true on backward jump`() {
        // User scrubbed backward — old window is now mostly behind the new playhead,
        // and we need a fresh window centered on the new position.
        assertTrue(PreloadStrategy.shouldReset(center = 100, lastCenter = 500))
        assertTrue(PreloadStrategy.shouldReset(center = 0, lastCenter = 1))
    }

    // ===== shouldReset: large forward jumps =====

    @Test
    fun `shouldReset returns true on huge forward jump beyond maxStepForExtend`() {
        // Forward delta > maxStepForExtend (default 200) → user scrubbed forward,
        // not steady playback. Reset and re-seed the new window.
        assertTrue(PreloadStrategy.shouldReset(center = 1000, lastCenter = 500)) // delta 500
        assertTrue(PreloadStrategy.shouldReset(center = 250, lastCenter = 49))   // delta 201
    }

    @Test
    fun `shouldReset honors custom maxStepForExtend`() {
        // Caller can tune the threshold per scenario (e.g. higher fps videos).
        assertTrue(
            PreloadStrategy.shouldReset(
                center = 110,
                lastCenter = 0,
                maxStepForExtend = 100,
            )
        )
        assertFalse(
            PreloadStrategy.shouldReset(
                center = 90,
                lastCenter = 0,
                maxStepForExtend = 100,
            )
        )
    }

    // ===== shouldReset: small forward steps (the bug case) =====

    @Test
    fun `shouldReset returns false on small forward step within window`() {
        // The exact case the bug created: playback advanced 50 frames since the
        // last preload trigger. delta=50, well within maxStepForExtend=200 →
        // EXTEND, do NOT kill the in-flight ffmpegs.
        assertFalse(PreloadStrategy.shouldReset(center = 50, lastCenter = 0))
        assertFalse(PreloadStrategy.shouldReset(center = 100, lastCenter = 50))
        assertFalse(PreloadStrategy.shouldReset(center = 150, lastCenter = 100))
    }

    @Test
    fun `shouldReset returns false at exactly maxStepForExtend boundary`() {
        // Boundary: delta == maxStepForExtend → still considered steady playback
        // (extend), only delta > maxStepForExtend triggers reset.
        assertFalse(PreloadStrategy.shouldReset(center = 200, lastCenter = 0))
    }

    // ===== shouldReset: zero delta =====

    @Test
    fun `shouldReset returns false on zero delta same center recompute`() {
        // Same center re-fired (e.g. due to recomposition) — no need to kill.
        assertFalse(PreloadStrategy.shouldReset(center = 500, lastCenter = 500))
    }

    @Test
    fun `shouldReset returns false on single frame advance`() {
        // delta = 1 → tightest possible steady-playback case. Must extend.
        assertFalse(PreloadStrategy.shouldReset(center = 501, lastCenter = 500))
    }

    // ===== Window =====

    @Test
    fun `Window total sums backward and forward`() {
        val w = PreloadStrategy.Window(backward = 100, forward = 500)
        assertEquals(600, w.total)
    }

    @Test
    fun `PLAYBACK_WINDOW prefers forward buffer over backward`() {
        // The whole point of the asymmetric window: during steady playback the
        // playhead is moving forward, so we want the bulk of the cache budget
        // ahead of it, not behind.
        val w = PreloadStrategy.PLAYBACK_WINDOW
        assertEquals(100, w.backward)
        assertEquals(500, w.forward)
        assertEquals(600, w.total)
        assertTrue(w.forward > w.backward)
    }

    @Test
    fun `SCRUB_WINDOW uses symmetric buffer for unpredictable direction`() {
        // After a scrub the user might immediately scrub again in either
        // direction — symmetric window absorbs both cases.
        val w = PreloadStrategy.SCRUB_WINDOW
        assertEquals(300, w.backward)
        assertEquals(300, w.forward)
        assertEquals(600, w.total)
    }

    @Test
    fun `both predefined windows fit within FrameCache capacity of 600`() {
        // Defense-in-depth assertion: if anyone bumps the constants above
        // FrameCache(600), the cache will start evicting frames the playhead
        // is about to need — exact regression vector for the v4.3.2 bug
        // (cache cap 600, oversized window 1500). Lock the invariant in.
        assertTrue(
            PreloadStrategy.PLAYBACK_WINDOW.total <= 600,
            "PLAYBACK_WINDOW.total must fit FrameCache(600)"
        )
        assertTrue(
            PreloadStrategy.SCRUB_WINDOW.total <= 600,
            "SCRUB_WINDOW.total must fit FrameCache(600)"
        )
    }
}
