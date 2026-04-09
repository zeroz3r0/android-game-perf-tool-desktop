package com.gameperf.desktop.core.model

/**
 * Platform-agnostic screen capture configuration.
 * Replaces `AdbBridge.ScreenRecordProfile` for the shared interface.
 *
 * @property fps Target frame rate for recording. Default 30.
 */
data class ScreenCaptureConfig(
    val width: Int,
    val height: Int,
    val bitRate: Int,
    val fps: Int = 30,
)

/**
 * Handle to an active screen capture session. Sealed so consumers can
 * dispatch stop/pull logic polymorphically without knowing the platform.
 *
 * - [ProcessHandle]: wraps an `adb screenrecord` [Process] (Android).
 * - [SidecarHandle]: wraps a sidecar session ID (iOS).
 */
sealed class ScreenCaptureHandle {
    /** Android: wraps the `adb screenrecord` subprocess. */
    data class ProcessHandle(val process: Process) : ScreenCaptureHandle()

    /** iOS: wraps the sidecar-managed capture session identifier. */
    data class SidecarHandle(val captureId: String) : ScreenCaptureHandle()
}
