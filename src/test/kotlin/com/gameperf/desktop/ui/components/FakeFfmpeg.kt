package com.gameperf.desktop.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Pure test helper: deterministic, in-memory replacement for the real
 * `extractFrameAtIndex` ffmpeg invocation used by [EmbeddedVideoPlayer].
 *
 * Records every spawn (frame extraction request) so tests can assert which
 * frame indices were touched, in what order, and how many times. No real
 * `ffmpeg` process is spawned, no wall-clock waits — `delay(perFrameLatencyMs)`
 * cooperates with `runTest` virtual time so the entire scrub→play sequence
 * runs deterministically in microseconds.
 *
 * Project pattern: FakeAdbBridge / FakeDeviceBridge — pure fakes over mocking
 * frameworks (CLAUDE.md → "Tests puros sin mocks"). No `mockk`, no `mockito`.
 *
 * @param perFrameLatencyMs simulated decode latency per frame; defaults to 10ms
 *        of virtual time so the playback loop can advance through it without
 *        thousands of real-clock seconds.
 */
internal class FakeFfmpeg(private val perFrameLatencyMs: Long = 10L) {

    /** Frame indices for which [extractFrame] was invoked, in call order. */
    val spawnedFrames: MutableList<Int> = mutableListOf()

    /**
     * Frame indices for which a spawn was requested but the coroutine was
     * cancelled before [extractFrame] could complete (the spawn entry exists
     * in [spawnedFrames] but the bitmap was never returned). Useful to verify
     * cancellation semantics.
     */
    val cancelledFrames: MutableList<Int> = mutableListOf()

    /**
     * Suspend "decode" — records the spawn, waits for [perFrameLatencyMs] of
     * virtual time, then returns a deterministic placeholder bitmap. If the
     * surrounding coroutine is cancelled during the delay, the cancellation
     * propagates and the index is recorded in [cancelledFrames].
     */
    suspend fun extractFrame(idx: Int): ImageBitmap? {
        synchronized(spawnedFrames) { spawnedFrames.add(idx) }
        return try {
            delay(perFrameLatencyMs)
            placeholderBitmap()
        } catch (e: kotlinx.coroutines.CancellationException) {
            synchronized(cancelledFrames) { cancelledFrames.add(idx) }
            throw e
        }
    }

    /** Count of distinct spawns (de-duplicated). */
    fun distinctSpawnCount(): Int = synchronized(spawnedFrames) { spawnedFrames.toSet().size }

    /** Reset all recordings — useful between phases of multi-step tests. */
    fun reset() {
        synchronized(spawnedFrames) { spawnedFrames.clear() }
        synchronized(cancelledFrames) { cancelledFrames.clear() }
    }

    private fun placeholderBitmap(): ImageBitmap {
        // 1x1 placeholder — minimal allocation, deterministic, never null.
        // Tests assert on call recording, not on bitmap content. Built via
        // the same Skia path production uses (Image.makeFromBitmap +
        // toComposeImageBitmap) so we exercise the same conversion code.
        val bmp = Bitmap()
        bmp.allocPixels(ImageInfo.makeN32(1, 1, ColorAlphaType.PREMUL))
        return Image.makeFromBitmap(bmp).toComposeImageBitmap()
    }
}
