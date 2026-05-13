package com.gameperf.desktop.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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

    /**
     * V1.4.1 — Cold-start play. No prior preload, no debounce in flight.
     * Maps to spec MODIFIED Req-1 "Edge — cold start (no prior preload)".
     *
     * The Composable wrapper would receive `preloadJob == null` and
     * `fullResDebounceJob == null`. Cancelling a null Job is a no-op (the
     * Composable handles that with `?.cancel()`); the core itself just
     * needs to seed the cache from idx 0 forward without throwing.
     */
    @Test
    fun `cold-start play seeds PLAYBACK_WINDOW from idx 0 with no prior cache`() = runTest {
        val ffmpeg = FakeFfmpeg(perFrameLatencyMs = 1L)
        val cache = mutableMapOf<Int, ImageBitmap>()
        val totalFrames = 1_000

        // Cold start: no prior preload, no scrub-debounce, cache empty.
        forcePlaybackPreloadCore(
            centerIndex = 0,
            totalFrames = totalFrames,
            window = PreloadStrategy.PLAYBACK_WINDOW,
            isCached = { idx -> cache.containsKey(idx) },
            extractFrame = { idx -> ffmpeg.extractFrame(idx) },
            putFrame = { idx, bmp -> cache[idx] = bmp },
            parallelism = 3,
        )
        advanceUntilIdle()

        // PLAYBACK_WINDOW.forward = 500; centerIndex = 0 so cache must reach 500.
        // backward = 100 but coerceAtLeast(0) clamps it — no negative indices.
        assertTrue(
            cache.containsKey(0),
            "Cache must include centerIndex 0 (cold start)",
        )
        assertTrue(
            cache.containsKey(PreloadStrategy.PLAYBACK_WINDOW.forward),
            "Cache must reach PLAYBACK_WINDOW.forward=${PreloadStrategy.PLAYBACK_WINDOW.forward} " +
                "from cold start; max key was ${cache.keys.max()}",
        )
        // No backward indices spawned — start was clamped to 0.
        val backwardSpawns = ffmpeg.spawnedFrames.filter { it < 0 }
        assertTrue(
            backwardSpawns.isEmpty(),
            "No negative indices may be spawned (cold start at idx=0); got $backwardSpawns",
        )
    }

    /**
     * V1.4.2 — Rapid play/pause toggles must not leak coroutines. Maps to
     * spec MODIFIED Req-2 "Edge — rapid play/pause/play toggle".
     *
     * Each "play" press spawns a fresh preload; each "pause" cancels it.
     * After 5 cycles, no `Job` should remain active. We assert via direct
     * Job tracking (the Composable's wrapper assigns the launch result to
     * `preloadJob`, then cancels it on the next play press).
     */
    @Test
    fun `rapid play-pause toggles 5x leaves no leaked active jobs`() = runTest {
        val ffmpeg = FakeFfmpeg(perFrameLatencyMs = 5L)
        val cache = mutableMapOf<Int, ImageBitmap>()
        val totalFrames = 5_000
        val spawnedJobs = mutableListOf<Job>()

        repeat(5) { cycle ->
            val centerIdx = cycle * 100
            val job = launch {
                forcePlaybackPreloadCore(
                    centerIndex = centerIdx,
                    totalFrames = totalFrames,
                    window = PreloadStrategy.PLAYBACK_WINDOW,
                    isCached = { idx -> cache.containsKey(idx) },
                    extractFrame = { idx -> ffmpeg.extractFrame(idx) },
                    putFrame = { idx, bmp -> cache[idx] = bmp },
                    parallelism = 3,
                )
            }
            spawnedJobs += job
            // Simulate user "pause" (cancel) after a tiny tick of virtual time
            // — the spawn should propagate cancellation.
            advanceTimeBy(2L)
            job.cancel()
        }
        advanceUntilIdle()

        val stillActive = spawnedJobs.count { it.isActive }
        assertEquals(
            0, stillActive,
            "All 5 spawn jobs must be inactive after cancel + advanceUntilIdle; got $stillActive active",
        )
        // At least one frame should have been observed cancelled (cancellation
        // actually propagated through the chunked async loop, not just no-op).
        assertTrue(
            ffmpeg.cancelledFrames.isNotEmpty(),
            "Cancellation must propagate to in-flight extractFrame calls; cancelledFrames was empty",
        )
    }

    /**
     * V1.4.3 — Play with `lastPreloadCenter == idx` (resume after scrub at
     * same position) MUST still seed PLAYBACK_WINDOW. Maps to spec MODIFIED
     * Req-1 "Edge — play resume without seeking".
     *
     * This is the precise regression: in the old code path,
     * `preloadWindow(idx)` with `lastPreloadCenter == idx` returned `false`
     * from `shouldReset` and the extend branch found an active job and
     * no-op'd. The forced helper has no such heuristic — it ALWAYS spawns.
     */
    @Test
    fun `play resume at same idx still spawns PLAYBACK_WINDOW (no shouldReset no-op)`() = runTest {
        val ffmpeg = FakeFfmpeg(perFrameLatencyMs = 1L)
        val cache = mutableMapOf<Int, ImageBitmap>()
        val totalFrames = 5_000
        val idx = 1_000

        // Pre-populate ONLY the scrub center (mimicking a single-frame full-res
        // decode landing in cache from the scrub-debounce path). The forward
        // window is empty.
        cache[idx] = ffmpeg.extractFrame(idx)!!
        ffmpeg.reset()

        forcePlaybackPreloadCore(
            centerIndex = idx,
            totalFrames = totalFrames,
            window = PreloadStrategy.PLAYBACK_WINDOW,
            isCached = { i -> cache.containsKey(i) },
            extractFrame = { i -> ffmpeg.extractFrame(i) },
            putFrame = { i, bmp -> cache[i] = bmp },
            parallelism = 3,
        )
        advanceUntilIdle()

        // Cache must reach idx + PLAYBACK_WINDOW.forward — proves the helper
        // is NOT short-circuiting on "lastCenter == idx".
        val target = idx + PreloadStrategy.PLAYBACK_WINDOW.forward
        assertTrue(
            cache.containsKey(target),
            "Resume-at-same-idx must still seed PLAYBACK_WINDOW; cache max key was ${cache.keys.max()}",
        )
        // Spawn count must be >= forward window minus the 1 already-cached idx.
        // (forward = 500, range [idx..idx+500] inclusive = 501 frames, idx
        // already cached, so 500 fresh spawns.)
        assertTrue(
            ffmpeg.spawnedFrames.size >= PreloadStrategy.PLAYBACK_WINDOW.forward,
            "Spawn count ${ffmpeg.spawnedFrames.size} must be >= ${PreloadStrategy.PLAYBACK_WINDOW.forward}; " +
                "the centred-already-cached idx must NOT prevent fresh forward spawns",
        )
    }

    /**
     * V1.4.4 — Play during active scrub-debounce (debounce pending,
     * NOT yet fired). Asserts `fullResDebounceJob.cancel()` succeeds and no
     * debounce-spawned frames materialise after play-start. Maps to spec
     * ADDED Req "No stale debounce job after play-start".
     *
     * We model the debounce as a `launch { delay(250); ffmpeg.extractFrame(scrubIdx) }`
     * and the play-start as `cancel + forcePlaybackPreloadCore`. After
     * advancing past 250ms, the scrubIdx must NOT appear in spawnedFrames
     * (because it was cancelled before the delay completed).
     */
    @Test
    fun `play during pending scrub-debounce cancels debounce before spawn`() = runTest {
        val ffmpeg = FakeFfmpeg(perFrameLatencyMs = 1L)
        val cache = mutableMapOf<Int, ImageBitmap>()
        val totalFrames = 5_000
        val scrubIdx = 4_242 // sentinel — distinctive index so we can detect it

        // Stage scrub-debounce at scrubIdx.
        val fullResDebounceJob = launch {
            delay(250)
            ffmpeg.extractFrame(scrubIdx)
        }

        // Advance only PART of the debounce window — debounce still pending.
        advanceTimeBy(100L)
        assertTrue(fullResDebounceJob.isActive, "debounce must still be pending at t=100ms")

        // PLAY: cancel debounce FIRST (ADR 2 ordering), then spawn forced preload
        // at a DIFFERENT idx (the playhead is somewhere else).
        fullResDebounceJob.cancel()
        forcePlaybackPreloadCore(
            centerIndex = 100,
            totalFrames = totalFrames,
            window = PreloadStrategy.PLAYBACK_WINDOW,
            isCached = { i -> cache.containsKey(i) },
            extractFrame = { i -> ffmpeg.extractFrame(i) },
            putFrame = { i, bmp -> cache[i] = bmp },
            parallelism = 3,
        )
        advanceUntilIdle()

        // The debounce sentinel idx must NEVER appear in spawnedFrames — proving
        // cancellation happened before the 250ms timer elapsed.
        assertFalse(
            ffmpeg.spawnedFrames.contains(scrubIdx),
            "Stale debounce idx $scrubIdx must NOT have spawned; it was cancelled before its delay completed. " +
                "spawnedFrames=${ffmpeg.spawnedFrames.size} entries (max=${ffmpeg.spawnedFrames.maxOrNull()})",
        )
        assertFalse(fullResDebounceJob.isActive, "debounce job must be cancelled / completed")
    }

    /**
     * V1.4.5 — Play after scrub-cache-hit (cached forward frames
     * pre-populated). Asserts `forcePlaybackPreloadCore` does NOT re-extract
     * cached indices; only the missing ones get spawned. Maps to design
     * §"Testing Strategy" row 6.
     */
    @Test
    fun `play after cache-hit scrub does not re-extract already-cached frames`() = runTest {
        val ffmpeg = FakeFfmpeg(perFrameLatencyMs = 1L)
        val cache = mutableMapOf<Int, ImageBitmap>()
        val totalFrames = 5_000
        val idx = 1_000

        // Pre-populate the cache for the WHOLE forward range [idx..idx+200] —
        // simulating a previous large preload that already covered part of
        // the playback window.
        val preCached = (idx..idx + 200).toSet()
        for (i in preCached) cache[i] = ffmpeg.extractFrame(i)!!
        ffmpeg.reset()

        forcePlaybackPreloadCore(
            centerIndex = idx,
            totalFrames = totalFrames,
            window = PreloadStrategy.PLAYBACK_WINDOW,
            isCached = { i -> cache.containsKey(i) },
            extractFrame = { i -> ffmpeg.extractFrame(i) },
            putFrame = { i, bmp -> cache[i] = bmp },
            parallelism = 3,
        )
        advanceUntilIdle()

        // No spawned idx may be in the pre-cached set — the helper must respect
        // the `isCached` predicate and skip re-extraction.
        val redundantSpawns = ffmpeg.spawnedFrames.toSet().intersect(preCached)
        assertTrue(
            redundantSpawns.isEmpty(),
            "No cached idx may be re-extracted; redundant spawns: $redundantSpawns",
        )
        // Cache must still reach idx + PLAYBACK_WINDOW.forward — the 201..500
        // tail beyond the pre-cached range gets spawned.
        val target = idx + PreloadStrategy.PLAYBACK_WINDOW.forward
        assertTrue(
            cache.containsKey(target),
            "Cache must reach idx + PLAYBACK_WINDOW.forward = $target; max was ${cache.keys.max()}",
        )
    }
}
