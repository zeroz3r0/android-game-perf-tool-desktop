package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.Device
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.FrameSnapshot
import com.gameperf.desktop.core.model.MemSnapshot
import com.gameperf.desktop.core.model.ThermalSnapshot
import java.io.File

/**
 * v3.1.14 — Interface extracted from [AdbBridge] for testability.
 * v4.1.0 — Return types migrated from deprecated AdbBridge.* nested classes
 *           to platform-agnostic core.model.* types. This eliminates the
 *           deprecated type layer and aligns AdbBridgeApi with DeviceBridgeApi.
 * v4.2.2 — The deprecated types in AdbBridge are now gone (not just deprecated).
 *           [RealAdbBridge] is now a 1-line passthrough for every method.
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

    /**
     * v4.3.5: drop the cached SurfaceFlinger layer list for [pkg] so the next
     * captureFrames forces a fresh `dumpsys --list`. Used by the polling loop
     * after K consecutive null FPS frames to recover from an ad close that
     * swapped the underlying SurfaceView (the FPS-resume-after-ad fix).
     */
    fun invalidateLayerCache(deviceId: String, pkg: String)

    fun captureFrames(deviceId: String, pkg: String): FrameSnapshot?

    /** Device-wide CPU% (legacy — sum of all processes). Pre-v4.2.5 default. */
    fun captureCpuPercent(deviceId: String): Int

    /** v4.2.5: per-process CPU% scoped to [pkg]. Returns the GAME's CPU usage as
     *  a fraction of total device CPU capacity (0-100). The pre-v4.2.5 single-arg
     *  overload still works but reports device-wide CPU which is rarely what the
     *  user wants. New code should always pass [pkg]. */
    fun captureCpuPercent(deviceId: String, pkg: String): Int

    fun captureMemory(deviceId: String, pkg: String): MemSnapshot?
    fun captureTemperature(deviceId: String): ThermalSnapshot

    fun startScreenRecord(
        deviceId: String,
        sessionId: String,
        segment: Int = 0,
        profile: AdbBridge.ScreenRecordProfile = AdbBridge.ScreenRecordProfile.STANDARD,
    ): Process?
    fun stopScreenRecord(process: Process?)

    /**
     * Spawns a long-lived `adb logcat` process for the given device.
     *
     * The caller owns the returned [Process] and is responsible for destroying
     * it (via `process.destroyForcibly()`). The process emits threadtime-format
     * logcat lines on its `inputStream` (UTF-8 encoded).
     *
     * @param deviceId Target adb device serial.
     * @param tagArgs Tag filter args (e.g., `["Ads:D", "AdActivity:D", "*:S"]`).
     * @return The spawned [Process], or null if adb resolution failed.
     *
     * @since v4.4.0
     */
    fun startLogcat(deviceId: String, tagArgs: List<String>): Process?

    /**
     * Run an arbitrary `adb shell` command on a device and return stdout.
     *
     * Used by [com.gameperf.desktop.core.events.DumpsysPoller] for
     * `dumpsys activity activities` polling. Output is bounded by [timeoutMs];
     * on timeout or any failure, returns the empty string.
     *
     * @param deviceId Target adb device serial.
     * @param cmd Command to execute remotely (e.g., "dumpsys activity activities").
     * @param timeoutMs Maximum wait for the command to complete, in milliseconds.
     * @return Stdout from the device shell, or "" on timeout/failure.
     *
     * @since v4.4.0
     */
    fun shell(deviceId: String, cmd: String, timeoutMs: Long = 5_000L): String
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
 * Production implementation. Thin wrapper over the [AdbBridge] singleton.
 *
 * v4.2.2: AdbBridge now returns core.model.* types directly (the deprecated
 * nested data classes were removed), so every method here is a 1-line
 * passthrough. No type conversion needed.
 */
class RealAdbBridge : AdbBridgeApi {
    override fun isAvailable(): Boolean = AdbBridge.isAvailable()

    override fun listDevices(): List<Device> = AdbBridge.listDevices()

    override fun getDeviceInfo(deviceId: String): DeviceInfo = AdbBridge.getDeviceInfo(deviceId)

    override fun detectGame(deviceId: String): String? = AdbBridge.detectGame(deviceId)
    override fun switchToWifi(usbDeviceId: String, port: Int): String? =
        AdbBridge.switchToWifi(usbDeviceId, port)

    override fun getBatteryLevel(deviceId: String): Int = AdbBridge.getBatteryLevel(deviceId)
    override fun getMissedFrames(deviceId: String): Int = AdbBridge.getMissedFrames(deviceId)
    override fun disableCharging(deviceId: String): String = AdbBridge.disableCharging(deviceId)
    override fun restoreCharging(deviceId: String): String = AdbBridge.restoreCharging(deviceId)

    override fun resetSessionState() = AdbBridge.resetSessionState()

    override fun invalidateLayerCache(deviceId: String, pkg: String) =
        AdbBridge.invalidateLayerCache(deviceId, pkg)

    override fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? =
        AdbBridge.captureFrames(deviceId, pkg)

    override fun captureCpuPercent(deviceId: String): Int = AdbBridge.captureCpuPercent(deviceId)
    override fun captureCpuPercent(deviceId: String, pkg: String): Int =
        AdbBridge.captureCpuPercent(deviceId, pkg)

    override fun captureMemory(deviceId: String, pkg: String): MemSnapshot? =
        AdbBridge.captureMemory(deviceId, pkg)

    override fun captureTemperature(deviceId: String): ThermalSnapshot =
        AdbBridge.captureTemperature(deviceId)

    override fun startScreenRecord(
        deviceId: String, sessionId: String, segment: Int,
        profile: AdbBridge.ScreenRecordProfile,
    ): Process? = AdbBridge.startScreenRecord(deviceId, sessionId, segment, profile)

    override fun stopScreenRecord(process: Process?) = AdbBridge.stopScreenRecord(process)

    override fun startLogcat(deviceId: String, tagArgs: List<String>): Process? =
        AdbBridge.startLogcat(deviceId, tagArgs)

    override fun shell(deviceId: String, cmd: String, timeoutMs: Long): String =
        AdbBridge.shell(deviceId, cmd, timeoutMs)

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
