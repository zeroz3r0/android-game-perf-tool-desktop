package com.gameperf.desktop.core.bridge

import com.gameperf.desktop.core.*
import com.gameperf.desktop.core.model.*
import java.io.File

/**
 * Android implementation of [DeviceBridgeApi].
 *
 * v4.1.0: AdbBridgeApi now returns core.model.* types directly, so
 * this class is mostly passthrough — no type conversion needed.
 *
 * @param adb The underlying Android bridge — [RealAdbBridge] in production, FakeAdbBridge in tests.
 */
class AndroidBridge(private val adb: AdbBridgeApi) : DeviceBridgeApi {

    override fun isAvailable(): Boolean = adb.isAvailable()

    override fun listDevices(): List<Device> = adb.listDevices()

    override fun getDeviceInfo(deviceId: String): DeviceInfo =
        adb.getDeviceInfo(deviceId)

    override fun detectGame(deviceId: String): String? =
        adb.detectGame(deviceId)

    override fun getBatteryLevel(deviceId: String): Int =
        adb.getBatteryLevel(deviceId)

    override fun resetSessionState() =
        adb.resetSessionState()

    override fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? =
        adb.captureFrames(deviceId, pkg)

    override fun captureCpuPercent(deviceId: String): Int =
        adb.captureCpuPercent(deviceId)

    override fun captureMemory(deviceId: String, pkg: String): MemSnapshot? =
        adb.captureMemory(deviceId, pkg)

    override fun captureTemperature(deviceId: String): ThermalSnapshot =
        adb.captureTemperature(deviceId)

    override fun startScreenCapture(
        deviceId: String,
        sessionId: String,
        config: ScreenCaptureConfig,
    ): ScreenCaptureHandle? {
        val profile = config.toScreenRecordProfile()
        val process = adb.startScreenRecord(deviceId, sessionId, profile = profile) ?: return null
        return ScreenCaptureHandle.ProcessHandle(process)
    }

    override fun stopScreenCapture(handle: ScreenCaptureHandle) {
        when (handle) {
            is ScreenCaptureHandle.ProcessHandle -> adb.stopScreenRecord(handle.process)
            is ScreenCaptureHandle.SidecarHandle -> { /* no-op: Android doesn't use sidecar */ }
        }
    }

    override fun pullRecordings(
        deviceId: String, sessionId: String, localDir: File, maxSegments: Int,
    ): List<File> = adb.pullRecordings(deviceId, sessionId, localDir, maxSegments)

    override fun cleanRecordings(deviceId: String) = adb.cleanRecordings(deviceId)

    override fun concatSegments(segments: List<File>, output: File): File? =
        adb.concatSegments(segments, output)

    override fun isValidVideoFile(file: File): Boolean =
        adb.isValidVideoFile(file)

    // ===== Android-specific methods (NOT on DeviceBridgeApi) =====

    fun switchToWifi(usbDeviceId: String, port: Int = 5555): String? =
        adb.switchToWifi(usbDeviceId, port)

    fun getMissedFrames(deviceId: String): Int =
        adb.getMissedFrames(deviceId)

    fun disableCharging(deviceId: String): String =
        adb.disableCharging(deviceId)

    fun restoreCharging(deviceId: String): String =
        adb.restoreCharging(deviceId)

    fun pair(ip: String, port: Int, code: String): PairResult =
        adb.pair(ip, port, code)

    fun connectWireless(ip: String, port: Int): ConnectResult =
        adb.connectWireless(ip, port)

    fun mdnsServices(): List<MdnsService> =
        adb.mdnsServices()

    fun disconnect(id: String): Boolean =
        adb.disconnect(id)

    fun getAdbVersion(): AdbVersion? =
        adb.getAdbVersion()

    companion object {
        /** Map ScreenCaptureConfig → closest AdbBridge.ScreenRecordProfile. */
        internal fun ScreenCaptureConfig.toScreenRecordProfile(): AdbBridge.ScreenRecordProfile =
            if (width <= 540 || height <= 960) {
                AdbBridge.ScreenRecordProfile.COMPACT
            } else {
                AdbBridge.ScreenRecordProfile.STANDARD
            }
    }
}
