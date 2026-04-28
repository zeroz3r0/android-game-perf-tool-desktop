package com.gameperf.desktop.ui.components

/**
 * Pure decision logic for whether the playback preloader should fully reset
 * (kill in-flight ffmpeg jobs and start over) or just extend the existing
 * window forward.
 *
 * Reset is correct when the playhead jumps (scrub, seek). It is WRONG during
 * steady forward playback because it murders the very preload jobs that were
 * about to feed the playhead — exact root cause of the v4.3.x "video plays
 * at ~25% speed" bug, where the playback loop fired `preloadWindow(idx)`
 * every 50 frames and each call killed the in-flight ffmpegs the previous
 * call had just spawned. Cache never warmed → every frame became a cold
 * extract (~80-200ms) → effective playback ~5-7fps instead of 30.
 *
 * Extracted as a pure object so it can be unit-tested without spawning
 * ffmpeg or rendering Compose UI. Per project convention (CLAUDE.md →
 * "Tests puros sin mocks"): complex logic gets a pure-function version that
 * tests can drive directly. See [PreloadStrategyTest].
 */
object PreloadStrategy {

    /**
     * Whether the preloader should reset (kill + restart) versus extend
     * (let in-flight ffmpegs finish).
     *
     * @param center new center index (current frame at preload-trigger time)
     * @param lastCenter last center we preloaded around, or null if first call
     * @param maxStepForExtend max forward delta that still counts as steady
     *        playback. Defaults to 200 — the playback loop fires preload
     *        every 50 frames, so a delta of 50 must clearly extend; 200
     *        gives 4x headroom against jittery recompositions before
     *        falling back to reset. Above this threshold the playhead
     *        moved far enough that the existing window is mostly behind it.
     */
    fun shouldReset(center: Int, lastCenter: Int?, maxStepForExtend: Int = 200): Boolean {
        if (lastCenter == null) return true
        val delta = center - lastCenter
        // Backward jump → user scrubbed; the existing window is mostly ahead
        // of the new playhead and irrelevant. Reset.
        if (delta < 0) return true
        // Huge forward jump → also a scrub (user dragged the timeline far).
        // Reset.
        if (delta > maxStepForExtend) return true
        // Small forward delta (in [0..maxStepForExtend]) → steady playback.
        // EXTEND — do NOT touch in-flight ffmpegs.
        return false
    }

    /**
     * Asymmetric or symmetric preload window. Backward = frames behind the
     * playhead (recent rewind buffer). Forward = frames ahead of the playhead
     * (anticipated playback). Sum stays under [FrameCache] capacity (600) so
     * that no in-window frame is evicted by another in-window frame —
     * exact regression vector for the v4.3.2 bug (cache cap 600, oversized
     * window 1500).
     */
    data class Window(val backward: Int, val forward: Int) {
        val total: Int get() = backward + forward
    }

    /**
     * Window used during steady playback. Heavy forward bias because the
     * playhead is moving forward — most of the cache budget should be
     * frames the playhead is about to consume, not frames behind it.
     */
    val PLAYBACK_WINDOW = Window(backward = 100, forward = 500)

    /**
     * Window used right after a scrub. Symmetric because the user might
     * immediately scrub again in either direction; we don't know yet which
     * way the next move will go.
     */
    val SCRUB_WINDOW = Window(backward = 300, forward = 300)
}
