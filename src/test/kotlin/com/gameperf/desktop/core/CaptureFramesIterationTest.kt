package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.FrameSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [LayerSelector.captureFramesFromCandidates] — the pure extracted helper
 * that drives the FPS-resume-after-ad multi-candidate iteration logic.
 *
 * Background: pre-v4.3.5 captureFrames retried `findLayer` exactly once when
 * `--latency` returned <3 lines. If the second `findLayer` re-elected the same
 * dead zombie layer (which it deterministically did via `firstOrNull()` over
 * the dumpsys output) every retry produced the same null FrameSnapshot.
 *
 * v4.3.5 generalizes the retry to iterate ALL ranked candidates from
 * [LayerSelector]. The first one whose `--latency` query returns enough lines
 * (≥3) wins. The pure helper takes a list of candidates and a function that
 * runs `--latency` for a given layer; it returns either a [FrameSnapshot] from
 * the winning layer (and which one won, so the caller can promote it in the
 * cache) or null if every candidate failed.
 */
class CaptureFramesIterationTest {

    @Test
    fun `returns first candidate result when its latency output has enough lines`() {
        // Happy path: the cached candidate works on the first try, no fallback
        // needed. This must continue to hold so the fix doesn't regress the
        // common case (game running normally, no ad in progress).
        val candidates = listOf("LAYER_A", "LAYER_B")
        val latencyOutputs = mapOf(
            "LAYER_A" to fakeLatency128Frames(),
            "LAYER_B" to "16666666\n",
        )

        val result = LayerSelector.captureFramesFromCandidates(candidates) { layer ->
            latencyOutputs[layer] ?: ""
        }

        assertNotNull(result, "expected a frame snapshot for LAYER_A")
        assertEquals("LAYER_A", result.winningLayer)
        assertNotNull(result.snapshot, "snapshot must not be null when latency yielded enough lines")
    }

    @Test
    fun `falls back to second candidate when first returns insufficient lines`() {
        // The exact resume-after-ad scenario: LAYER_A is the zombie returning
        // only the refresh-rate line, LAYER_B is the freshly-recreated layer
        // returning real frames. Pre-v4.3.5 this returned null because the
        // retry path re-resolved to LAYER_A again.
        val candidates = listOf("LAYER_A_ZOMBIE", "LAYER_B_FRESH")
        val latencyOutputs = mapOf(
            "LAYER_A_ZOMBIE" to "16666666\n",  // 1-line dead-layer output
            "LAYER_B_FRESH" to fakeLatency128Frames(),
        )

        val result = LayerSelector.captureFramesFromCandidates(candidates) { layer ->
            latencyOutputs[layer] ?: ""
        }

        assertNotNull(result, "expected fallback to second candidate to succeed")
        assertEquals("LAYER_B_FRESH", result.winningLayer)
    }

    @Test
    fun `returns null when all candidates have insufficient lines`() {
        // Game backgrounded entirely (no SurfaceView delivering frames). All
        // candidates return the 1-line refresh-rate output. The helper must
        // return null so the caller can keep the HUD on the last-known value.
        val candidates = listOf("LAYER_A", "LAYER_B", "LAYER_C")
        val latencyOutputs = candidates.associateWith { "16666666\n" }

        val result = LayerSelector.captureFramesFromCandidates(candidates) { layer ->
            latencyOutputs[layer] ?: ""
        }

        assertNull(result, "expected null when every candidate is dead")
    }

    @Test
    fun `returns null when candidates list is empty`() {
        val result = LayerSelector.captureFramesFromCandidates(emptyList()) { _ -> "" }
        assertNull(result, "empty candidates → null without invoking latency runner")
    }

    @Test
    fun `does not call latency runner for candidates after a winner`() {
        // Performance/short-circuit: once a candidate wins, the iteration must
        // stop. Otherwise we'd shell out N adb commands per poll on every
        // iteration which is wasteful and slow.
        val candidates = listOf("LAYER_A", "LAYER_B", "LAYER_C")
        val callOrder = mutableListOf<String>()
        val latencyOutputs = mapOf(
            "LAYER_A" to fakeLatency128Frames(),
            "LAYER_B" to fakeLatency128Frames(),
            "LAYER_C" to fakeLatency128Frames(),
        )

        LayerSelector.captureFramesFromCandidates(candidates) { layer ->
            callOrder += layer
            latencyOutputs[layer] ?: ""
        }

        assertEquals(listOf("LAYER_A"), callOrder, "should stop iterating after first winner")
    }

    @Test
    fun `tries every candidate until one succeeds`() {
        // Mid-list winner: A and B are dead, C delivers real frames.
        // Verifies the iteration actually walks the full list, not just the
        // first two.
        val candidates = listOf("DEAD_A", "DEAD_B", "LIVE_C")
        val callOrder = mutableListOf<String>()
        val latencyOutputs = mapOf(
            "DEAD_A" to "16666666\n",
            "DEAD_B" to "",
            "LIVE_C" to fakeLatency128Frames(),
        )

        val result = LayerSelector.captureFramesFromCandidates(candidates) { layer ->
            callOrder += layer
            latencyOutputs[layer] ?: ""
        }

        assertEquals(listOf("DEAD_A", "DEAD_B", "LIVE_C"), callOrder)
        assertNotNull(result)
        assertEquals("LIVE_C", result.winningLayer)
    }

    // ---- helpers ----

    /**
     * Build a fake `dumpsys SurfaceFlinger --latency '<layer>'` output with
     * the standard format: first line is the refresh interval (ignored), then
     * 128 frames of triple-timestamps separated by spaces. Frames are spaced
     * 16,666,666 ns apart (60 fps target) so [AdbBridge.computeFrameSnapshot]
     * can parse them into a non-null FrameSnapshot.
     */
    private fun fakeLatency128Frames(): String {
        val sb = StringBuilder()
        sb.appendLine("16666666")  // refresh interval header line
        var ts = 1_000_000_000L
        repeat(128) {
            // dumpsys --latency emits three timestamps per frame; AdbBridge
            // splits on whitespace and reads index 1 as the GL ready time.
            sb.append(ts).append(' ').append(ts).append(' ').append(ts).append('\n')
            ts += 16_666_666L
        }
        return sb.toString()
    }
}

/**
 * Helper data class for the pure iteration helper's return value. Lives in this
 * test file (and mirrors the production type) so the test compiles before the
 * production type exists — keeps the RED gate honest.
 */
@Suppress("unused")
private fun ignoreReturnTypeMirror(snapshot: FrameSnapshot?, winningLayer: String) = snapshot to winningLayer
