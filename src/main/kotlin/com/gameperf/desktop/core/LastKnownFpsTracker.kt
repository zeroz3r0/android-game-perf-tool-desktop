package com.gameperf.desktop.core

/**
 * Pure last-known-FPS fallback used by the live capture HUD.
 *
 * v4.3.5 — FPS resume after ad / interstitial: when an ad SDK destroys and
 * recreates the host SurfaceView, [AdbBridge.captureFrames] returns null for
 * 1-3 polls until the layer cache is invalidated and re-resolved. During that
 * gap the LIVE HUD used to flicker to "--" every iteration. This tracker
 * keeps the previous valid FPS sticky for [windowMs] milliseconds so the
 * transition is seamless. After the window expires we fall back to 0 (HUD
 * shows "--") so the user still sees a real stall.
 *
 * History/report values intentionally do NOT use this class — only the live
 * UI emission. Persisted data must stay truthful.
 *
 * Pure: no time source dependency (callers pass `nowMs`), no I/O. Trivially
 * unit-testable, and the same instance can be re-used for the lifetime of
 * a capture session.
 */
internal class LastKnownFpsTracker(private val windowMs: Long) {
    private var lastFps: Int = 0
    private var lastTimestampMs: Long = 0L

    /**
     * Push a new raw reading and return the value the HUD should display.
     *
     * @param rawFps The FrameSnapshot.fps from the latest poll, or 0 for null.
     * @param nowMs The wall-clock timestamp in milliseconds.
     */
    fun update(rawFps: Int, nowMs: Long): Int = when {
        rawFps > 0 -> {
            lastFps = rawFps
            lastTimestampMs = nowMs
            rawFps
        }
        lastFps > 0 && (nowMs - lastTimestampMs) < windowMs -> lastFps
        else -> 0
    }
}
