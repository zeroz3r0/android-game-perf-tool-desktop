package com.gameperf.desktop.core.ios

import com.gameperf.desktop.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for [IosBridge].
 *
 * Uses [FakeSidecarClient] to avoid HTTP calls. Verifies that IosBridge
 * correctly maps sidecar responses to shared model types.
 */
class IosBridgeTest {

    // ===== Device listing =====

    @Test
    fun `listDevices returns iOS devices from sidecar`() {
        val fake = FakeSidecarClient(
            devices = listOf(
                Device("udid-1", "iPhone 15", DevicePlatform.IOS),
                Device("udid-2", "iPad Pro", DevicePlatform.IOS),
            ),
        )
        val bridge = IosBridge(fake)
        val devices = bridge.listDevices()
        assertEquals(2, devices.size)
        assertEquals(DevicePlatform.IOS, devices[0].platform)
        assertEquals("iPhone 15", devices[0].model)
    }

    @Test
    fun `listDevices returns empty when sidecar has no devices`() {
        val bridge = IosBridge(FakeSidecarClient())
        assertTrue(bridge.listDevices().isEmpty())
    }

    // ===== Device info =====

    @Test
    fun `getDeviceInfo returns iOS info from sidecar`() {
        val fake = FakeSidecarClient(
            deviceInfo = DeviceInfo("iPhone 15 Pro", "Apple", "arm64e", "Apple GPU (A17 Pro)", "8 GB", 6, "17.4", "1179x2556", DevicePlatform.IOS),
        )
        val bridge = IosBridge(fake)
        val info = bridge.getDeviceInfo("udid-1")
        assertEquals("iPhone 15 Pro", info.model)
        assertEquals("Apple", info.manufacturer)
        assertEquals(DevicePlatform.IOS, info.platform)
    }

    // ===== Metrics =====

    @Test
    fun `captureFrames returns FrameSnapshot from sidecar metrics`() {
        val fake = FakeSidecarClient(
            metrics = SidecarClient.MetricsSnapshot(
                fps = 59, avgFrameTime = 16.9, jankCount = 2, stutterCount = 1,
                cpuPercent = 42, memoryMb = 350, nativeMb = 0, javaMb = 0,
                tempCpu = 38.5, tempGpu = 36.2, tempBattery = 32.1, tempSkin = -1.0,
                batteryLevel = 85,
            ),
        )
        val bridge = IosBridge(fake)
        val frame = bridge.captureFrames("udid", "com.game")
        assertNotNull(frame)
        assertEquals(59, frame.fps)
        assertEquals(16.9, frame.avgFrameTime, 0.01)
        assertEquals(2, frame.jankCount)
    }

    @Test
    fun `captureFrames returns null when fps is sentinel`() {
        val fake = FakeSidecarClient(
            metrics = SidecarClient.MetricsSnapshot(
                fps = -1, avgFrameTime = -1.0, jankCount = 0, stutterCount = 0,
                cpuPercent = -1, memoryMb = -1, nativeMb = 0, javaMb = 0,
                tempCpu = -1.0, tempGpu = -1.0, tempBattery = -1.0, tempSkin = -1.0,
                batteryLevel = -1,
            ),
        )
        val bridge = IosBridge(fake)
        assertNull(bridge.captureFrames("udid", "com.game"))
    }

    @Test
    fun `captureCpuPercent returns value from sidecar`() {
        val fake = FakeSidecarClient(
            metrics = SidecarClient.MetricsSnapshot(
                fps = 60, avgFrameTime = 16.6, jankCount = 0, stutterCount = 0,
                cpuPercent = 42, memoryMb = 300, nativeMb = 0, javaMb = 0,
                tempCpu = 38.0, tempGpu = 35.0, tempBattery = 31.0, tempSkin = -1.0,
                batteryLevel = 90,
            ),
        )
        val bridge = IosBridge(fake)
        assertEquals(42, bridge.captureCpuPercent("udid"))
    }

    @Test
    fun `captureMemory returns MemSnapshot with zero native and java on iOS`() {
        val fake = FakeSidecarClient(
            metrics = SidecarClient.MetricsSnapshot(
                fps = 60, avgFrameTime = 16.6, jankCount = 0, stutterCount = 0,
                cpuPercent = 30, memoryMb = 350, nativeMb = 0, javaMb = 0,
                tempCpu = 38.0, tempGpu = 35.0, tempBattery = 31.0, tempSkin = -1.0,
                batteryLevel = 90,
            ),
        )
        val bridge = IosBridge(fake)
        val mem = bridge.captureMemory("udid", "com.game")
        assertNotNull(mem)
        assertEquals(350L, mem.totalMb)
        assertEquals(0L, mem.nativeMb)
        assertEquals(0L, mem.javaMb)
    }

    @Test
    fun `captureTemperature returns ThermalSnapshot with skin always minus one`() {
        val fake = FakeSidecarClient(
            metrics = SidecarClient.MetricsSnapshot(
                fps = 60, avgFrameTime = 16.6, jankCount = 0, stutterCount = 0,
                cpuPercent = 30, memoryMb = 300, nativeMb = 0, javaMb = 0,
                tempCpu = 38.5, tempGpu = 36.2, tempBattery = 32.1, tempSkin = -1.0,
                batteryLevel = 90,
            ),
        )
        val bridge = IosBridge(fake)
        val thermal = bridge.captureTemperature("udid")
        assertEquals(38.5, thermal.cpu, 0.01)
        assertEquals(36.2, thermal.gpu, 0.01)
        assertEquals(32.1, thermal.battery, 0.01)
        assertEquals(-1.0, thermal.skin, 0.01)  // ALWAYS -1.0 on iOS
    }

    @Test
    fun `getBatteryLevel returns value from sidecar`() {
        val fake = FakeSidecarClient(
            metrics = SidecarClient.MetricsSnapshot(
                fps = 60, avgFrameTime = 16.6, jankCount = 0, stutterCount = 0,
                cpuPercent = 30, memoryMb = 300, nativeMb = 0, javaMb = 0,
                tempCpu = 38.0, tempGpu = 35.0, tempBattery = 31.0, tempSkin = -1.0,
                batteryLevel = 85,
            ),
        )
        val bridge = IosBridge(fake)
        assertEquals(85, bridge.getBatteryLevel("udid"))
    }

    // ===== Platform =====

    @Test
    fun `detectGame returns null for iOS`() {
        val bridge = IosBridge(FakeSidecarClient())
        assertNull(bridge.detectGame("udid"))
    }

    @Test
    fun `isAvailable returns sidecar health status`() {
        val healthy = IosBridge(FakeSidecarClient(healthy = true))
        val unhealthy = IosBridge(FakeSidecarClient(healthy = false))
        assertTrue(healthy.isAvailable())
        assertFalse(unhealthy.isAvailable())
    }

    // ===== Screen capture =====

    @Test
    fun `startScreenCapture returns SidecarHandle`() {
        val fake = FakeSidecarClient(captureId = "cap-123")
        val bridge = IosBridge(fake)
        val handle = bridge.startScreenCapture("udid", "session-1", ScreenCaptureConfig(720, 1280, 4_000_000))
        assertNotNull(handle)
        assertTrue(handle is ScreenCaptureHandle.SidecarHandle)
        assertEquals("cap-123", (handle as ScreenCaptureHandle.SidecarHandle).captureId)
    }

    @Test
    fun `startScreenCapture returns null when sidecar fails`() {
        val fake = FakeSidecarClient(captureId = null)
        val bridge = IosBridge(fake)
        assertNull(bridge.startScreenCapture("udid", "session-1", ScreenCaptureConfig(720, 1280, 4_000_000)))
    }
}

/**
 * Fake SidecarClient for testing IosBridge without HTTP.
 */
class FakeSidecarClient(
    private val devices: List<Device> = emptyList(),
    private val deviceInfo: DeviceInfo? = null,
    private val metrics: SidecarClient.MetricsSnapshot? = null,
    private val healthy: Boolean = true,
    private val captureId: String? = null,
) : SidecarClient(baseUrl = "http://unused:0") {

    override fun isHealthy(): Boolean = healthy
    override fun listDevices(): List<Device> = devices
    override fun getDeviceInfo(udid: String): DeviceInfo? = deviceInfo
    override fun getMetrics(udid: String): MetricsSnapshot? = metrics
    override fun startScreenRecord(udid: String, sessionId: String): String? = captureId
    override fun stopScreenRecord(udid: String, captureId: String): String? = null
    override fun takeScreenshot(udid: String): ByteArray? = null
    override fun shutdown() {}
}
