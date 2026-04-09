package com.gameperf.desktop.testing

import com.gameperf.desktop.core.bridge.DeviceBridgeApi
import com.gameperf.desktop.core.model.*
import java.io.File

/**
 * Minimal fake [DeviceBridgeApi] for tests that need a platform-agnostic bridge.
 *
 * All return values are configurable via constructor. Used by:
 * - CompositeBridge routing tests (verifies platform dispatch)
 * - AppViewModel mixed-device tests (Phase 1)
 *
 * For Android-specific testing (pair, switchToWifi, startScreenRecord) use
 * [FakeAdbBridge] instead — it implements [com.gameperf.desktop.core.AdbBridgeApi].
 */
open class FakeDeviceBridge(
    private val devices: List<Device> = emptyList(),
    private val available: Boolean = true,
    private val deviceInfo: DeviceInfo = DeviceInfo("Fake", "Fake", "Fake", "Fake", "0 GB", 1, "0", "0x0", DevicePlatform.ANDROID),
    private val frameSnapshot: FrameSnapshot? = null,
    private val memSnapshot: MemSnapshot? = null,
    private val thermalSnapshot: ThermalSnapshot = ThermalSnapshot(-1.0, -1.0, -1.0, -1.0),
    private val cpuPercent: Int = 0,
    private val batteryLevel: Int = 100,
    private val detectedGame: String? = null,
) : DeviceBridgeApi {
    var resetCalled = false

    override fun isAvailable(): Boolean = available
    override fun listDevices(): List<Device> = devices
    override fun getDeviceInfo(deviceId: String): DeviceInfo = deviceInfo
    override fun detectGame(deviceId: String): String? = detectedGame
    override fun getBatteryLevel(deviceId: String): Int = batteryLevel
    override fun resetSessionState() { resetCalled = true }
    override fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? = frameSnapshot
    override fun captureCpuPercent(deviceId: String): Int = cpuPercent
    override fun captureMemory(deviceId: String, pkg: String): MemSnapshot? = memSnapshot
    override fun captureTemperature(deviceId: String): ThermalSnapshot = thermalSnapshot
    override fun startScreenCapture(deviceId: String, sessionId: String, config: ScreenCaptureConfig): ScreenCaptureHandle? = null
    override fun stopScreenCapture(handle: ScreenCaptureHandle) {}
    override fun pullRecordings(deviceId: String, sessionId: String, localDir: File, maxSegments: Int): List<File> = emptyList()
    override fun cleanRecordings(deviceId: String) {}
    override fun concatSegments(segments: List<File>, output: File): File? = null
    override fun isValidVideoFile(file: File): Boolean = false
}
