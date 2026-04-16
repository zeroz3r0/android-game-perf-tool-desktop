package com.gameperf.desktop.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [AppViewModel.Companion.inferGameTargetFps].
 *
 * v4.2.6 reliability test. Pre-v4.2.6 the FPS grading thresholds were hardcoded
 * to a 60fps target. A game intentionally capped at 30fps (Pokemon Unite, casual
 * games, battery-saver mode) landed at p50=30 and got -35 grade points despite
 * being on-target. Same class of bug we fixed for jank in v4.2.5.
 *
 * The inference uses both `avgFps` and `maxFps` because:
 * - `avg` alone misleads when the game has long loading screens at 0 fps
 * - `max` alone misleads when there's a brief frame-time spike at startup
 * - `max(avg, 0.95 * max)` rejects the noise and gets close to the intended cap
 */
class AppViewModelGradingTest {

    @Test
    fun `inferGameTargetFps maps a stable 60fps stream to 60`() {
        // Steady 60fps game: avg=60, max=60.
        assertEquals(60, AppViewModel.inferGameTargetFps(avgFps = 60, maxFps = 60))
    }

    @Test
    fun `inferGameTargetFps maps a stable 30fps stream to 30 -- THE v4_2_6 bug fix`() {
        // Pokemon Unite, casual mobile, intentional 30fps cap. Pre-v4.2.6 the
        // grading thresholds compared p50=30 against a 60fps reference and
        // penalized -35 points. With the new inference, target=30 → grading
        // ratio is 30/30=1.0 → no penalty, correct grade A.
        assertEquals(30, AppViewModel.inferGameTargetFps(avgFps = 30, maxFps = 31))
        assertEquals(30, AppViewModel.inferGameTargetFps(avgFps = 29, maxFps = 30))
    }

    @Test
    fun `inferGameTargetFps maps a stable 90fps stream to 90`() {
        // Genshin Impact "60+" mode on flagship, OnePlus's 90Hz adaptive games.
        assertEquals(90, AppViewModel.inferGameTargetFps(avgFps = 88, maxFps = 91))
    }

    @Test
    fun `inferGameTargetFps maps a stable 120fps stream to 120`() {
        // Competitive shooters on high-refresh phones (CoD Mobile 120fps mode).
        assertEquals(120, AppViewModel.inferGameTargetFps(avgFps = 118, maxFps = 122))
    }

    @Test
    fun `inferGameTargetFps maps a 45fps stream to 45 (Unity Auto)`() {
        // Unity's "Auto" frame rate on mid-range often lands here.
        assertEquals(45, AppViewModel.inferGameTargetFps(avgFps = 42, maxFps = 46))
    }

    @Test
    fun `inferGameTargetFps prefers max when avg is dragged down by loading screens`() {
        // Long loading screen drops avg, but the game itself targets 60.
        // avg=35 (because of loading), max=62 → 0.95 * 62 = 58.9 → indicator=58.9 → bucket 60.
        assertEquals(60, AppViewModel.inferGameTargetFps(avgFps = 35, maxFps = 62))
    }

    @Test
    fun `inferGameTargetFps does not over-classify thanks to 0_95 multiplier on max`() {
        // A 30fps-capped game with a brief spike to 35 (e.g. menu rendering)
        // should still classify as 30, not as 45. 0.95 * 35 = 33.25, which is
        // below the 38 threshold, so we land at the 30 bucket.
        assertEquals(30, AppViewModel.inferGameTargetFps(avgFps = 30, maxFps = 35))
    }

    @Test
    fun `inferGameTargetFps falls back to 30 for clearly broken sessions`() {
        // Game that never broke 25fps — either broken hardware or extreme
        // throttling. Treat as 30 so the grading isn't insanely harsh.
        assertEquals(30, AppViewModel.inferGameTargetFps(avgFps = 18, maxFps = 25))
    }

    @Test
    fun `inferGameTargetFps handles zero gracefully`() {
        // No frames captured at all (capture-side bug, not user fault).
        // Returns 30 as a safe fallback so downstream division doesn't crash.
        assertEquals(30, AppViewModel.inferGameTargetFps(avgFps = 0, maxFps = 0))
    }
}
