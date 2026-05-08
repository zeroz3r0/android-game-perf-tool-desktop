package com.gameperf.desktop.core.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Orchestrates auto event detection from logcat + dumpsys sources.
 *
 * Lifecycle:
 *  - [start] kicks off the underlying [LogcatCapture] and [DumpsysPoller].
 *  - Events emit on [events] StateFlow as they're detected (open) and updated (close).
 *  - [warnings] flow surfaces detection-quality issues (logcat gaps, dumpsys
 *    failures) for the report header to render.
 *  - [stop] cancels both sources and force-closes any open events with
 *    `endInferred=true`.
 *
 * Implementations are thread-safe. The `events` flow always contains the
 * complete cumulative session list (not a delta stream).
 *
 * @since v4.4.0
 */
interface EventDetector {
    /** Cumulative list of detected events for the current session. */
    val events: StateFlow<List<DetectedEvent>>

    /** Detection-quality warnings (logcat gaps, dumpsys disabled, etc.). */
    val warnings: StateFlow<List<String>>

    /**
     * Starts detection.
     *
     * @param deviceId Target adb device serial.
     * @param gamePackage Package name of the game being tested (used for
     *   foreground guard).
     * @param scope CoroutineScope owning the detection coroutines (typically
     *   the capture scope).
     */
    fun start(deviceId: String, gamePackage: String, scope: CoroutineScope)

    /**
     * Stops detection. Force-closes any open events with the current timestamp
     * and `endInferred=true`. Idempotent.
     */
    fun stop()
}
