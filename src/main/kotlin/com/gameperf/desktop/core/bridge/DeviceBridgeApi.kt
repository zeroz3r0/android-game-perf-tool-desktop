package com.gameperf.desktop.core.bridge

import com.gameperf.desktop.core.model.*
import java.io.File

/**
 * Platform-agnostic device bridge interface.
 *
 * Defines the shared surface area that [com.gameperf.desktop.viewmodel.AppViewModel]
 * and other consumers use for device profiling. Platform-specific methods
 * (e.g. Android WiFi ADB, charging control) live on the concrete bridge
 * implementations, NOT here.
 *
 * Implementations:
 * - [AndroidBridge] — wraps existing [com.gameperf.desktop.core.AdbBridge] singleton
 * - IosBridge (Phase 1) — wraps pymobiledevice3 sidecar over HTTP/JSON
 * - [CompositeBridge] — aggregates multiple bridges, routes by [Device.platform]
 *
 * @see com.gameperf.desktop.core.AdbBridgeApi for the Android-specific interface (deprecated)
 */
interface DeviceBridgeApi {

    /** Returns true if the underlying tool/runtime is available (adb, Python sidecar, etc.). */
    fun isAvailable(): Boolean

    /** List all connected devices for this bridge's platform. */
    fun listDevices(): List<Device>

    /** Get hardware/software info for a device. */
    fun getDeviceInfo(deviceId: String): DeviceInfo

    /** Detect the foreground game/app package on a device. Returns null if none detected. */
    fun detectGame(deviceId: String): String?

    /** Get battery level (0-100). Returns -1 if unavailable. */
    fun getBatteryLevel(deviceId: String): Int

    /** Reset session-scoped state (CPU deltas, layer cache, etc.) for a fresh capture. */
    fun resetSessionState()

    /** Capture frame timing snapshot. Returns null if layer/metric unavailable. */
    fun captureFrames(deviceId: String, pkg: String): FrameSnapshot?

    /** Capture overall CPU percentage (0-100). Returns -1 on first call or if unavailable. */
    fun captureCpuPercent(deviceId: String): Int

    /** Capture memory snapshot. Returns null if unavailable. */
    fun captureMemory(deviceId: String, pkg: String): MemSnapshot?

    /** Capture thermal snapshot. Unavailable metrics use -1.0 sentinel. */
    fun captureTemperature(deviceId: String): ThermalSnapshot

    /**
     * Start screen capture. Returns a platform-specific handle or null on failure.
     * Android: starts `adb screenrecord`, returns [ScreenCaptureHandle.ProcessHandle].
     * iOS: starts sidecar capture, returns [ScreenCaptureHandle.SidecarHandle].
     */
    fun startScreenCapture(
        deviceId: String,
        sessionId: String,
        config: ScreenCaptureConfig,
    ): ScreenCaptureHandle?

    /** Stop an active screen capture using the handle from [startScreenCapture]. */
    fun stopScreenCapture(handle: ScreenCaptureHandle)

    /** Pull recorded segments from device to local directory. */
    fun pullRecordings(
        deviceId: String,
        sessionId: String,
        localDir: File,
        maxSegments: Int = 20,
    ): List<File>

    /** Clean up recordings on the device. */
    fun cleanRecordings(deviceId: String)

    /** Concatenate video segments into a single file. Returns null on failure. */
    fun concatSegments(segments: List<File>, output: File): File?

    /** Validate that a video file is playable. */
    fun isValidVideoFile(file: File): Boolean
}
