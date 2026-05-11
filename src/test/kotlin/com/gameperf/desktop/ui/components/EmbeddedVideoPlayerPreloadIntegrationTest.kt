package com.gameperf.desktop.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the v4.4.1 hotfix `forcePlaybackPreloadCore` —
 * pure helper extracted from [EmbeddedVideoPlayer] that drives the
 * play-after-scrub preload sequence without spawning real ffmpeg or
 * rendering Compose UI.
 *
 * Bug background (see explore #260, spec, design):
 * After a scrub, the in-flight preload is a `SCRUB_WINDOW` (300/300
 * symmetric). When the user pressed Play immediately, the play-init at
 * `EmbeddedVideoPlayer.kt:698` called `preloadWindow(idx)` → routed
 * through `PreloadStrategy.shouldReset(idx, idx)` → `false` → the
 * extend branch found an active job → no-op. Forward buffer stayed at
 * 300 frames (~10s @30fps with HALF burned on backward) → cold ffmpeg
 * seeks → ~5-7fps playback.
 *
 * The fix introduces an explicit `forcePlaybackPreloadCore` that
 * UNCONDITIONALLY seeds `PLAYBACK_WINDOW` (500 forward, 100 backward),
 * bypassing the heuristic. Tests below lock that behavior in.
 *
 * Test purity: `runTest` + virtual time + [FakeFfmpeg]. Zero real
 * ffmpeg processes, zero wall-clock delays. Project convention from
 * CLAUDE.md ("Tests puros sin mocks") + FakeAdbBridge pattern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddedVideoPlayerPreloadIntegrationTest {

    /**
     * V0.2 / RED test: scrub→play sequence MUST seed PLAYBACK_WINDOW.forward
     * frames (500), NOT SCRUB_WINDOW.forward (300). Maps to spec ADDED Req
     * "Forward buffer reaches PLAYBACK_WINDOW after scrub→play".
     *
     * GUARANTEED RED until V1.1 lands `forcePlaybackPreloadCore` — the
     * symbol does not exist on the current main, so this file fails to
     * compile. That IS the failing-first contract.
     */
    @Test
    fun `play after scrub seeds PLAYBACK_WINDOW forward 500 not SCRUB_WINDOW forward 300`() = runTest {
        val ffmpeg = FakeFfmpeg(perFrameLatencyMs = 1L)
        val cache = mutableMapOf<Int, ImageBitmap>()
        val totalFrames = 10_000
        val scrubTarget = 200

        // PHASE 1: simulate scrub — manually pre-populate cache around scrub
        // target with SCRUB_WINDOW(300/300) entries so the next call is
        // identical to "play started after a scrub already seeded the cache".
        // Mirror what scrub debounce would have done: forward indices in
        // [scrubTarget..scrubTarget+SCRUB_WINDOW.forward).
        val scrubWindow = PreloadStrategy.SCRUB_WINDOW
        for (i in scrubTarget until (scrubTarget + scrubWindow.forward).coerceAtMost(totalFrames)) {
            cache[i] = ffmpeg.extractFrame(i)!!
        }
        ffmpeg.reset() // clear scrub-phase recordings

        // PHASE 2: user clicks play at the same idx where scrub landed.
        forcePlaybackPreloadCore(
            centerIndex = scrubTarget,
            totalFrames = totalFrames,
            window = PreloadStrategy.PLAYBACK_WINDOW,
            isCached = { idx -> cache.containsKey(idx) },
            extractFrame = { idx -> ffmpeg.extractFrame(idx) },
            putFrame = { idx, bmp -> cache[idx] = bmp },
            parallelism = 3,
        )
        advanceUntilIdle()

        // ASSERT: cache must now contain frames up to scrubTarget +
        // PLAYBACK_WINDOW.forward (500), NOT just up to scrubTarget +
        // SCRUB_WINDOW.forward (300). The 200 frames in
        // (scrubTarget+300 .. scrubTarget+500] are the bug delta.
        val playbackWindow = PreloadStrategy.PLAYBACK_WINDOW
        val maxExpected = scrubTarget + playbackWindow.forward
        val maxScrubOnly = scrubTarget + scrubWindow.forward
        assertTrue(
            cache.containsKey(maxExpected),
            "Cache must reach scrubTarget+PLAYBACK_WINDOW.forward=$maxExpected after play-start; " +
                "found max key ${cache.keys.max()}. Bug regression: only SCRUB_WINDOW seeded.",
        )
        // Triangulation guard: spawn list must include indices ABOVE the
        // scrub-window boundary (i.e. the 300..500 range that was missing).
        val spawnedAboveScrubBoundary = ffmpeg.spawnedFrames.filter { it > maxScrubOnly }
        assertTrue(
            spawnedAboveScrubBoundary.isNotEmpty(),
            "forcePlaybackPreloadCore MUST spawn frames beyond scrub-window boundary " +
                "($maxScrubOnly); got spawned=${ffmpeg.spawnedFrames.size}, none above boundary.",
        )
    }
}
