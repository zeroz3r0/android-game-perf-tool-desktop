package com.gameperf.desktop.core.bridge

import com.gameperf.desktop.core.*
import com.gameperf.desktop.core.model.*
import com.gameperf.desktop.core.model.Device
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.FrameSnapshot
import com.gameperf.desktop.core.model.MemSnapshot
import com.gameperf.desktop.core.model.ThermalSnapshot
import java.io.File

/**
 * Android implementation of [DeviceBridgeApi].
 *
 * Wraps the existing [AdbBridgeApi] (backed by [AdbBridge] singleton or [FakeAdbBridge]
 * in tests) and translates between Android-specific types and the shared `core/model/` types.
 *
 * Android-specific methods that are NOT on [DeviceBridgeApi] are exposed directly
 * on this class so callers that need them (e.g. HomeScreen WiFi panel) can safe-cast:
 * `(bridge as? AndroidBridge)?.switchToWifi(...)`.
 *
 * @param adb The underlying Android bridge — [RealAdbBridge] in production, [FakeAdbBridge] in tests.
 */
class AndroidBridge(private val adb: AdbBridgeApi) : DeviceBridgeApi {

    override fun isAvailable(): Boolean = adb.isAvailable()

    override fun listDevices(): List<Device> =
        adb.listDevices().map { it.toSharedDevice() }

    override fun getDeviceInfo(deviceId: String): DeviceInfo =
        adb.getDeviceInfo(deviceId).toSharedDeviceInfo()

    override fun detectGame(deviceId: String): String? =
        adb.detectGame(deviceId)

    override fun getBatteryLevel(deviceId: String): Int =
        adb.getBatteryLevel(deviceId)

    override fun resetSessionState() =
        adb.resetSessionState()

    override fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? =
        adb.captureFrames(deviceId, pkg)?.toSharedFrameSnapshot()

    override fun captureCpuPercent(deviceId: String): Int =
        adb.captureCpuPercent(deviceId)

    override fun captureMemory(deviceId: String, pkg: String): MemSnapshot? =
        adb.captureMemory(deviceId, pkg)?.toSharedMemSnapshot()

    override fun captureTemperature(deviceId: String): ThermalSnapshot =
        adb.captureTemperature(deviceId).toSharedThermalSnapshot()

    override fun startScreenCapture(
        deviceId: String,
        sessionId: String,
        config: ScreenCaptureConfig,
    ): ScreenCaptureHandle? {
        // Map ScreenCaptureConfig to the nearest AdbBridge.ScreenRecordProfile
        val profile = config.toScreenRecordProfile()
        val process = adb.startScreenRecord(deviceId, sessionId, profile = profile) ?: return null
        return ScreenCaptureHandle.ProcessHandle(process)
    }

    override fun stopScreenCapture(handle: ScreenCaptureHandle) {
        when (handle) {
            is ScreenCaptureHandle.ProcessHandle -> adb.stopScreenRecord(handle.process)
            is ScreenCaptureHandle.SidecarHandle -> {
                // This shouldn't happen — Android bridge doesn't produce SidecarHandles.
                // Defensive no-op.
            }
        }
    }

    override fun pullRecordings(
        deviceId: String,
        sessionId: String,
        localDir: File,
        maxSegments: Int,
    ): List<File> = adb.pullRecordings(deviceId, sessionId, localDir, maxSegments)

    override fun cleanRecordings(deviceId: String) = adb.cleanRecordings(deviceId)

    override fun concatSegments(segments: List<File>, output: File): File? =
        adb.concatSegments(segments, output)

    override fun isValidVideoFile(file: File): Boolean =
        adb.isValidVideoFile(file)

    // ===== Android-specific methods (NOT on DeviceBridgeApi) =====

    /** Switch device to WiFi ADB. Returns "ip:port" on success, null on failure. */
    fun switchToWifi(usbDeviceId: String, port: Int = 5555): String? =
        adb.switchToWifi(usbDeviceId, port)

    /** Get missed frame count from SurfaceFlinger. */
    fun getMissedFrames(deviceId: String): Int =
        adb.getMissedFrames(deviceId)

    /** Disable battery charging via `dumpsys battery unplug`. */
    fun disableCharging(deviceId: String): String =
        adb.disableCharging(deviceId)

    /** Restore battery charging via `dumpsys battery reset`. */
    fun restoreCharging(deviceId: String): String =
        adb.restoreCharging(deviceId)

    /** Run `adb pair`. */
    fun pair(ip: String, port: Int, code: String): PairResult =
        adb.pair(ip, port, code)

    /** Run `adb connect`. */
    fun connectWireless(ip: String, port: Int): ConnectResult =
        adb.connectWireless(ip, port)

    /** Snapshot mDNS services. */
    fun mdnsServices(): List<MdnsService> =
        adb.mdnsServices()

    /** Disconnect a device. */
    fun disconnect(id: String): Boolean =
        adb.disconnect(id)

    /** Get adb version. */
    fun getAdbVersion(): AdbVersion? =
        adb.getAdbVersion()

    // ===== Conversion helpers =====

    companion object {
        /** Convert AdbBridge.Device → core.model.Device */
        internal fun AdbBridge.Device.toSharedDevice(): Device =
            Device(
                id = id,
                model = model,
                platform = DevicePlatform.ANDROID,
                isWifi = isWifi,
            )

        /** Convert AdbBridge.DeviceInfo → core.model.DeviceInfo */
        internal fun AdbBridge.DeviceInfo.toSharedDeviceInfo(): DeviceInfo =
            DeviceInfo(
                model = model,
                manufacturer = manufacturer,
                cpu = cpu,
                gpu = gpu,
                ram = ram,
                cores = cores,
                osVersion = sdk.toString(),
                resolution = resolution,
                platform = DevicePlatform.ANDROID,
            )

        /** Convert AdbBridge.FrameSnapshot → core.model.FrameSnapshot */
        internal fun AdbBridge.FrameSnapshot.toSharedFrameSnapshot(): FrameSnapshot =
            FrameSnapshot(
                fps = fps,
                avgFrameTime = avgFrameTime,
                jankCount = jankCount,
                stutterCount = stutterCount,
            )

        /** Convert AdbBridge.MemSnapshot → core.model.MemSnapshot */
        internal fun AdbBridge.MemSnapshot.toSharedMemSnapshot(): MemSnapshot =
            MemSnapshot(
                totalMb = totalMb,
                nativeMb = nativeMb,
                javaMb = javaMb,
            )

        /** Convert AdbBridge.ThermalSnapshot → core.model.ThermalSnapshot */
        internal fun AdbBridge.ThermalSnapshot.toSharedThermalSnapshot(): ThermalSnapshot =
            ThermalSnapshot(
                cpu = cpu,
                gpu = gpu,
                battery = battery,
                skin = skin,
            )

        /** Map ScreenCaptureConfig → closest AdbBridge.ScreenRecordProfile. */
        internal fun ScreenCaptureConfig.toScreenRecordProfile(): AdbBridge.ScreenRecordProfile =
            if (width <= 540 || height <= 960) {
                AdbBridge.ScreenRecordProfile.COMPACT
            } else {
                AdbBridge.ScreenRecordProfile.STANDARD
            }
    }
}
