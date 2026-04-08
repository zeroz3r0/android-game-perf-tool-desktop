package com.gameperf.desktop.core

import java.io.File

/**
 * v3.1.14 — Interface extracted from [AdbBridge] so callers that need to be
 * unit-tested (primarily [com.gameperf.desktop.viewmodel.AppViewModel]) can be
 * exercised against a fake implementation instead of the live `adb` binary.
 *
 * **Scope discipline**: only the surface area consumed by `AppViewModel` is
 * included here. `AdbBridge` still exposes more helpers (e.g. `exec`, `shell`,
 * `findLayer`, `parseSurfaceFlingerListOutput`, `getBatteryTemp`) that other
 * callers and tests use directly — those remain on the `object AdbBridge` and
 * are NOT part of this interface. The goal is "make AppViewModel testable",
 * not "refactor AdbBridge end-to-end".
 *
 * **Nested types** (`Device`, `DeviceInfo`, `FrameSnapshot`, `MemSnapshot`,
 * `ThermalSnapshot`, `ScreenRecordProfile`) continue to live on the
 * `AdbBridge` object. The interface methods reference them directly so that
 * no existing call-site (`ReportGenerator`, `HomeScreen`, `AppViewModel`
 * field declarations) has to move its imports around.
 *
 * The production implementation is [RealAdbBridge], a thin delegating class
 * that forwards every call to the existing `object AdbBridge` singleton.
 * That way the underlying state (resolved adb path, CPU deltas, layer cache)
 * is still global — the interface is a seam for testing, NOT a refactor of
 * AdbBridge's internal state model.
 */
interface AdbBridgeApi {
    fun isAvailable(): Boolean

    fun listDevices(): List<AdbBridge.Device>
    fun getDeviceInfo(deviceId: String): AdbBridge.DeviceInfo
    fun detectGame(deviceId: String): String?
    fun switchToWifi(usbDeviceId: String, port: Int = 5555): String?

    fun getBatteryLevel(deviceId: String): Int
    fun getMissedFrames(deviceId: String): Int
    fun disableCharging(deviceId: String): String
    fun restoreCharging(deviceId: String): String

    fun resetSessionState()

    fun captureFrames(deviceId: String, pkg: String): AdbBridge.FrameSnapshot?
    fun captureCpuPercent(deviceId: String): Int
    fun captureMemory(deviceId: String, pkg: String): AdbBridge.MemSnapshot?
    fun captureTemperature(deviceId: String): AdbBridge.ThermalSnapshot

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
}

/**
 * Default production implementation. Every method delegates 1:1 to the
 * existing [AdbBridge] singleton so behavior is identical to v3.1.13.
 *
 * A single shared instance is fine — the underlying `object AdbBridge` holds
 * the real state (cachedLayer, prevCpuBusy/Total, adbPath lazy) and this
 * class is stateless. Constructing multiple `RealAdbBridge()` instances just
 * gives you multiple handles pointing at the same underlying singleton,
 * which is exactly what we want for the "constructor-default injection"
 * pattern in AppViewModel.
 */
class RealAdbBridge : AdbBridgeApi {
    override fun isAvailable(): Boolean = AdbBridge.isAvailable()

    override fun listDevices(): List<AdbBridge.Device> = AdbBridge.listDevices()
    override fun getDeviceInfo(deviceId: String): AdbBridge.DeviceInfo =
        AdbBridge.getDeviceInfo(deviceId)
    override fun detectGame(deviceId: String): String? = AdbBridge.detectGame(deviceId)
    override fun switchToWifi(usbDeviceId: String, port: Int): String? =
        AdbBridge.switchToWifi(usbDeviceId, port)

    override fun getBatteryLevel(deviceId: String): Int = AdbBridge.getBatteryLevel(deviceId)
    override fun getMissedFrames(deviceId: String): Int = AdbBridge.getMissedFrames(deviceId)
    override fun disableCharging(deviceId: String): String = AdbBridge.disableCharging(deviceId)
    override fun restoreCharging(deviceId: String): String = AdbBridge.restoreCharging(deviceId)

    override fun resetSessionState() = AdbBridge.resetSessionState()

    override fun captureFrames(deviceId: String, pkg: String): AdbBridge.FrameSnapshot? =
        AdbBridge.captureFrames(deviceId, pkg)
    override fun captureCpuPercent(deviceId: String): Int = AdbBridge.captureCpuPercent(deviceId)
    override fun captureMemory(deviceId: String, pkg: String): AdbBridge.MemSnapshot? =
        AdbBridge.captureMemory(deviceId, pkg)
    override fun captureTemperature(deviceId: String): AdbBridge.ThermalSnapshot =
        AdbBridge.captureTemperature(deviceId)

    override fun startScreenRecord(
        deviceId: String,
        sessionId: String,
        segment: Int,
        profile: AdbBridge.ScreenRecordProfile,
    ): Process? = AdbBridge.startScreenRecord(deviceId, sessionId, segment, profile)

    override fun stopScreenRecord(process: Process?) = AdbBridge.stopScreenRecord(process)

    override fun pullRecordings(
        deviceId: String,
        sessionId: String,
        localDir: File,
        maxSegments: Int,
    ): List<File> = AdbBridge.pullRecordings(deviceId, sessionId, localDir, maxSegments)

    override fun cleanRecordings(deviceId: String) = AdbBridge.cleanRecordings(deviceId)

    override fun concatSegments(segments: List<File>, output: File): File? =
        AdbBridge.concatSegments(segments, output)

    override fun isValidVideoFile(file: File): Boolean = AdbBridge.isValidVideoFile(file)
}
