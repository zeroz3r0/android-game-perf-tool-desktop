package com.gameperf.desktop.core.ios

import com.gameperf.desktop.core.bridge.DeviceBridgeApi
import com.gameperf.desktop.core.model.*
import java.io.File

/**
 * iOS implementation of [DeviceBridgeApi].
 *
 * Wraps [SidecarClient] to communicate with the pymobiledevice3 FastAPI sidecar.
 * Each method maps 1:1 to a sidecar HTTP endpoint.
 *
 * Unavailable metrics use the sentinel convention: -1 for Int, -1.0 for Double.
 * [ScreenCaptureHandle.SidecarHandle] wraps the sidecar's capture session ID.
 *
 * @param client The HTTP client configured to talk to the sidecar.
 */
class IosBridge(
    private val client: SidecarClient,
) : DeviceBridgeApi {

    /** True if the sidecar is running and healthy. */
    override fun isAvailable(): Boolean = client.isHealthy()

    override fun listDevices(): List<Device> = client.listDevices()

    override fun getDeviceInfo(deviceId: String): DeviceInfo =
        client.getDeviceInfo(deviceId)
            ?: DeviceInfo("Unknown", "Apple", "Unknown", "Apple GPU", "Unknown", 0, "Unknown", "Unknown", DevicePlatform.IOS)

    override fun detectGame(deviceId: String): String? {
        // iOS doesn't have a direct equivalent to Android's `dumpsys window`.
        // For now, return null — the user manually selects the game.
        // TODO Phase 3: Use DVT to detect frontmost app bundle ID.
        return null
    }

    override fun getBatteryLevel(deviceId: String): Int {
        val metrics = client.getMetrics(deviceId)
        return metrics?.batteryLevel ?: -1
    }

    override fun resetSessionState() {
        // No persistent state to reset on the iOS side.
        // The sidecar manages sessions independently.
    }

    override fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? {
        val metrics = client.getMetrics(deviceId) ?: return null
        if (metrics.fps < 0) return null
        return FrameSnapshot(
            fps = metrics.fps,
            avgFrameTime = metrics.avgFrameTime,
            jankCount = metrics.jankCount,
            stutterCount = metrics.stutterCount,
        )
    }

    override fun captureCpuPercent(deviceId: String): Int {
        val metrics = client.getMetrics(deviceId)
        return metrics?.cpuPercent ?: -1
    }

    override fun captureMemory(deviceId: String, pkg: String): MemSnapshot? {
        val metrics = client.getMetrics(deviceId) ?: return null
        if (metrics.memoryMb < 0) return null
        return MemSnapshot(
            totalMb = metrics.memoryMb,
            nativeMb = metrics.nativeMb,  // Always 0 on iOS
            javaMb = metrics.javaMb,      // Always 0 on iOS
        )
    }

    override fun captureTemperature(deviceId: String): ThermalSnapshot {
        val metrics = client.getMetrics(deviceId)
            ?: return ThermalSnapshot(-1.0, -1.0, -1.0, -1.0)
        return ThermalSnapshot(
            cpu = metrics.tempCpu,
            gpu = metrics.tempGpu,
            battery = metrics.tempBattery,
            skin = metrics.tempSkin,  // Always -1.0 on iOS
        )
    }

    override fun startScreenCapture(
        deviceId: String,
        sessionId: String,
        config: ScreenCaptureConfig,
    ): ScreenCaptureHandle? {
        val captureId = client.startScreenRecord(deviceId, sessionId) ?: return null
        return ScreenCaptureHandle.SidecarHandle(captureId)
    }

    override fun stopScreenCapture(handle: ScreenCaptureHandle) {
        when (handle) {
            is ScreenCaptureHandle.SidecarHandle -> {
                // The sidecar handles stopping via the capture ID
                // We need the device ID too — stored in the handle isn't ideal
                // but for now the sidecar uses the capture_id to find the session
                client.stopScreenRecord("_", handle.captureId)
            }
            is ScreenCaptureHandle.ProcessHandle -> {
                // This shouldn't happen — iOS bridge doesn't produce ProcessHandles
            }
        }
    }

    override fun pullRecordings(
        deviceId: String,
        sessionId: String,
        localDir: File,
        maxSegments: Int,
    ): List<File> {
        // iOS recordings are already local (sidecar saves to ~/GamePerf Reports/)
        // Look for the file by session ID
        val videoFile = File(localDir, "ios_video_$sessionId.mp4")
        return if (videoFile.exists()) listOf(videoFile) else emptyList()
    }

    override fun cleanRecordings(deviceId: String) {
        // No device-side cleanup needed — iOS recordings are local
    }

    override fun concatSegments(segments: List<File>, output: File): File? {
        // iOS recordings are single files (no segmenting like Android's screenrecord)
        return if (segments.size == 1 && segments[0].exists()) {
            segments[0].copyTo(output, overwrite = true)
            output
        } else null
    }

    override fun isValidVideoFile(file: File): Boolean {
        return file.exists() && file.length() > 1024
    }
}
