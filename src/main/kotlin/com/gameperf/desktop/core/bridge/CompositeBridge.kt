package com.gameperf.desktop.core.bridge

import com.gameperf.desktop.core.model.*
import java.io.File

/**
 * Aggregates multiple [DeviceBridgeApi] implementations (Android + optional iOS)
 * and routes calls to the correct bridge based on [Device.platform].
 *
 * This is the ONLY class that knows about platform routing. [AppViewModel] and all
 * other consumers interact through [DeviceBridgeApi] and never see platform-specific
 * bridge implementations.
 *
 * @param androidBridge Always present — Android support is mandatory.
 * @param iosBridge Optional — null when Python sidecar is unavailable or iOS support disabled.
 */
class CompositeBridge(
    private val androidBridge: DeviceBridgeApi,
    private val iosBridge: DeviceBridgeApi?,
) : DeviceBridgeApi {

    /**
     * Device ID → platform mapping, refreshed on each [listDevices] call.
     * Used by [findBridge] to route per-device calls to the correct bridge.
     * ConcurrentHashMap for thread safety (polling thread vs UI thread).
     */
    private val devicePlatformMap = java.util.concurrent.ConcurrentHashMap<String, DevicePlatform>()

    override fun isAvailable(): Boolean =
        androidBridge.isAvailable() || (iosBridge?.isAvailable() == true)

    override fun listDevices(): List<Device> {
        val androidDevices = androidBridge.listDevices()
        val iosDevices = iosBridge?.listDevices() ?: emptyList()
        val all = androidDevices + iosDevices

        // Refresh platform map
        devicePlatformMap.clear()
        for (device in all) {
            devicePlatformMap[device.id] = device.platform
        }

        return all
    }

    override fun getDeviceInfo(deviceId: String): DeviceInfo =
        findBridge(deviceId)?.getDeviceInfo(deviceId)
            ?: DeviceInfo("Unknown", "Unknown", "Unknown", "Unknown", "0 GB", 0, "0", "0x0", DevicePlatform.ANDROID)

    override fun detectGame(deviceId: String): String? =
        findBridge(deviceId)?.detectGame(deviceId)

    override fun getBatteryLevel(deviceId: String): Int =
        findBridge(deviceId)?.getBatteryLevel(deviceId) ?: -1

    override fun resetSessionState() {
        androidBridge.resetSessionState()
        iosBridge?.resetSessionState()
    }

    override fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? =
        findBridge(deviceId)?.captureFrames(deviceId, pkg)

    override fun captureCpuPercent(deviceId: String): Int =
        findBridge(deviceId)?.captureCpuPercent(deviceId) ?: -1

    override fun captureMemory(deviceId: String, pkg: String): MemSnapshot? =
        findBridge(deviceId)?.captureMemory(deviceId, pkg)

    override fun captureTemperature(deviceId: String): ThermalSnapshot =
        findBridge(deviceId)?.captureTemperature(deviceId)
            ?: ThermalSnapshot(-1.0, -1.0, -1.0, -1.0)

    override fun startScreenCapture(
        deviceId: String,
        sessionId: String,
        config: ScreenCaptureConfig,
    ): ScreenCaptureHandle? =
        findBridge(deviceId)?.startScreenCapture(deviceId, sessionId, config)

    override fun stopScreenCapture(handle: ScreenCaptureHandle) {
        // Dispatch based on handle type — ProcessHandle → Android, SidecarHandle → iOS
        when (handle) {
            is ScreenCaptureHandle.ProcessHandle -> androidBridge.stopScreenCapture(handle)
            is ScreenCaptureHandle.SidecarHandle -> iosBridge?.stopScreenCapture(handle)
        }
    }

    override fun pullRecordings(
        deviceId: String,
        sessionId: String,
        localDir: File,
        maxSegments: Int,
    ): List<File> =
        findBridge(deviceId)?.pullRecordings(deviceId, sessionId, localDir, maxSegments) ?: emptyList()

    override fun cleanRecordings(deviceId: String) {
        findBridge(deviceId)?.cleanRecordings(deviceId)
    }

    override fun concatSegments(segments: List<File>, output: File): File? =
        // Concat is platform-agnostic — use Android bridge (has ffmpeg logic).
        // iOS recordings come as single files from the sidecar, so this mostly applies to Android.
        androidBridge.concatSegments(segments, output)

    override fun isValidVideoFile(file: File): Boolean =
        androidBridge.isValidVideoFile(file)

    /**
     * Find the correct bridge for a device ID based on the platform map.
     * Returns null if the device is unknown (not seen in the last [listDevices] call).
     */
    private fun findBridge(deviceId: String): DeviceBridgeApi? {
        return when (devicePlatformMap[deviceId]) {
            DevicePlatform.ANDROID -> androidBridge
            DevicePlatform.IOS -> iosBridge
            null -> null
        }
    }
}
