package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.Device
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.model.FrameSnapshot
import com.gameperf.desktop.core.model.MemSnapshot
import com.gameperf.desktop.core.model.ThermalSnapshot
import java.io.File

/**
 * v3.1.14 — Interface extracted from [AdbBridge] for testability.
 * v4.1.0 — Return types migrated from deprecated AdbBridge.* nested classes
 *           to platform-agnostic core.model.* types. This eliminates the
 *           deprecated type layer and aligns AdbBridgeApi with DeviceBridgeApi.
 */
interface AdbBridgeApi {
    fun isAvailable(): Boolean

    fun listDevices(): List<Device>
    fun getDeviceInfo(deviceId: String): DeviceInfo
    fun detectGame(deviceId: String): String?
    fun switchToWifi(usbDeviceId: String, port: Int = 5555): String?

    fun getBatteryLevel(deviceId: String): Int
    fun getMissedFrames(deviceId: String): Int
    fun disableCharging(deviceId: String): String
    fun restoreCharging(deviceId: String): String

    fun resetSessionState()

    fun captureFrames(deviceId: String, pkg: String): FrameSnapshot?
    fun captureCpuPercent(deviceId: String): Int
    fun captureMemory(deviceId: String, pkg: String): MemSnapshot?
    fun captureTemperature(deviceId: String): ThermalSnapshot

    fun startScreenRecord(
        deviceId: String,
        sessionId: String,
        segment: Int = 0,
        profile: AdbBridge.ScreenRecordProfile = AdbBridge.ScreenRecordProfile.STANDARD,
    ): Process?
    fun stopScreenRecord(process: Process?)
    fun pullRecordings(
        deviceId: String,
        sessionId: String,
        localDir: File,
        maxSegments: Int = 20,
    ): List<File>
    fun cleanRecordings(deviceId: String)

    fun concatSegments(segments: List<File>, output: File): File?
    fun isValidVideoFile(file: File): Boolean

    // ===== v3.2.0 — Wireless ADB =====

    fun pair(ip: String, port: Int, code: String): PairResult
    fun connectWireless(ip: String, port: Int): ConnectResult
    fun mdnsServices(): List<MdnsService>
    fun disconnect(id: String): Boolean
    fun getAdbVersion(): AdbVersion?
}

/**
 * Production implementation. Delegates to [AdbBridge] singleton and converts
 * the deprecated nested types to core.model types.
 */
class RealAdbBridge : AdbBridgeApi {
    override fun isAvailable(): Boolean = AdbBridge.isAvailable()

    override fun listDevices(): List<Device> = AdbBridge.listDevices().map { d ->
        Device(id = d.id, model = d.model, platform = DevicePlatform.ANDROID, isWifi = d.isWifi)
    }

    override fun getDeviceInfo(deviceId: String): DeviceInfo {
        val d = AdbBridge.getDeviceInfo(deviceId)
        return DeviceInfo(
            model = d.model, manufacturer = d.manufacturer, cpu = d.cpu,
            gpu = d.gpu, ram = d.ram, cores = d.cores,
            osVersion = d.sdk.toString(), resolution = d.resolution,
            platform = DevicePlatform.ANDROID,
        )
    }

    override fun detectGame(deviceId: String): String? = AdbBridge.detectGame(deviceId)
    override fun switchToWifi(usbDeviceId: String, port: Int): String? =
        AdbBridge.switchToWifi(usbDeviceId, port)

    override fun getBatteryLevel(deviceId: String): Int = AdbBridge.getBatteryLevel(deviceId)
    override fun getMissedFrames(deviceId: String): Int = AdbBridge.getMissedFrames(deviceId)
    override fun disableCharging(deviceId: String): String = AdbBridge.disableCharging(deviceId)
    override fun restoreCharging(deviceId: String): String = AdbBridge.restoreCharging(deviceId)

    override fun resetSessionState() = AdbBridge.resetSessionState()

    override fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? {
        val f = AdbBridge.captureFrames(deviceId, pkg) ?: return null
        return FrameSnapshot(fps = f.fps, avgFrameTime = f.avgFrameTime, jankCount = f.jankCount, stutterCount = f.stutterCount)
    }

    override fun captureCpuPercent(deviceId: String): Int = AdbBridge.captureCpuPercent(deviceId)

    override fun captureMemory(deviceId: String, pkg: String): MemSnapshot? {
        val m = AdbBridge.captureMemory(deviceId, pkg) ?: return null
        return MemSnapshot(totalMb = m.totalMb, nativeMb = m.nativeMb, javaMb = m.javaMb)
    }

    override fun captureTemperature(deviceId: String): ThermalSnapshot {
        val t = AdbBridge.captureTemperature(deviceId)
        return ThermalSnapshot(cpu = t.cpu, gpu = t.gpu, battery = t.battery, skin = t.skin)
    }

    override fun startScreenRecord(
        deviceId: String, sessionId: String, segment: Int,
        profile: AdbBridge.ScreenRecordProfile,
    ): Process? = AdbBridge.startScreenRecord(deviceId, sessionId, segment, profile)

    override fun stopScreenRecord(process: Process?) = AdbBridge.stopScreenRecord(process)

    override fun pullRecordings(
        deviceId: String, sessionId: String, localDir: File, maxSegments: Int,
    ): List<File> = AdbBridge.pullRecordings(deviceId, sessionId, localDir, maxSegments)

    override fun cleanRecordings(deviceId: String) = AdbBridge.cleanRecordings(deviceId)

    override fun concatSegments(segments: List<File>, output: File): File? =
        AdbBridge.concatSegments(segments, output)

    override fun isValidVideoFile(file: File): Boolean = AdbBridge.isValidVideoFile(file)

    override fun pair(ip: String, port: Int, code: String): PairResult =
        AdbBridge.pair(ip, port, code)
    override fun connectWireless(ip: String, port: Int): ConnectResult =
        AdbBridge.connectWireless(ip, port)
    override fun mdnsServices(): List<MdnsService> = AdbBridge.mdnsServices()
    override fun disconnect(id: String): Boolean = AdbBridge.disconnect(id)
    override fun getAdbVersion(): AdbVersion? = AdbBridge.getAdbVersion()
}
