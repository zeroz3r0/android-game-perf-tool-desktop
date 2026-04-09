package com.gameperf.desktop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for shared model types in core/model/.
 * Written RED-first: these tests define the expected shape of the model types
 * before the implementation exists.
 */
class ModelSerializationTest {

    // ===== DevicePlatform enum =====

    @Test
    fun `DevicePlatform has ANDROID and IOS values`() {
        val values = DevicePlatform.values()
        assertEquals(2, values.size)
        assertEquals(DevicePlatform.ANDROID, DevicePlatform.valueOf("ANDROID"))
        assertEquals(DevicePlatform.IOS, DevicePlatform.valueOf("IOS"))
    }

    @Test
    fun `DevicePlatform ANDROID ordinal is 0`() {
        assertEquals(0, DevicePlatform.ANDROID.ordinal)
    }

    @Test
    fun `DevicePlatform IOS ordinal is 1`() {
        assertEquals(1, DevicePlatform.IOS.ordinal)
    }

    // ===== Device data class =====

    @Test
    fun `Device with ANDROID platform`() {
        val device = Device(id = "emulator-5554", model = "Pixel_6", platform = DevicePlatform.ANDROID)
        assertEquals("emulator-5554", device.id)
        assertEquals("Pixel_6", device.model)
        assertEquals(DevicePlatform.ANDROID, device.platform)
        assertFalse(device.isWifi)
    }

    @Test
    fun `Device with IOS platform`() {
        val device = Device(id = "00008101-ABCDEF", model = "iPhone 15", platform = DevicePlatform.IOS)
        assertEquals("00008101-ABCDEF", device.id)
        assertEquals("iPhone 15", device.model)
        assertEquals(DevicePlatform.IOS, device.platform)
        assertFalse(device.isWifi)
    }

    @Test
    fun `Device isWifi defaults to false`() {
        val device = Device("abc", "Model", DevicePlatform.ANDROID)
        assertFalse(device.isWifi)
    }

    @Test
    fun `Device isWifi can be set to true`() {
        val device = Device("192.168.1.5:5555", "Pixel_6", DevicePlatform.ANDROID, isWifi = true)
        assertTrue(device.isWifi)
    }

    @Test
    fun `Device data class equality`() {
        val a = Device("abc", "Model", DevicePlatform.ANDROID)
        val b = Device("abc", "Model", DevicePlatform.ANDROID)
        val c = Device("abc", "Model", DevicePlatform.IOS)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `Device copy with different platform`() {
        val android = Device("abc", "Model", DevicePlatform.ANDROID)
        val ios = android.copy(platform = DevicePlatform.IOS)
        assertEquals(DevicePlatform.IOS, ios.platform)
        assertEquals(android.id, ios.id)
    }

    // ===== DeviceInfo data class =====

    @Test
    fun `DeviceInfo for Android device`() {
        val info = DeviceInfo(
            model = "Pixel 6",
            manufacturer = "Google",
            cpu = "Tensor G1",
            gpu = "Mali-G78",
            ram = "8.0 GB",
            cores = 8,
            osVersion = "33",
            resolution = "1080x2400",
            platform = DevicePlatform.ANDROID,
        )
        assertEquals("Pixel 6", info.model)
        assertEquals("33", info.osVersion)
        assertEquals(DevicePlatform.ANDROID, info.platform)
    }

    @Test
    fun `DeviceInfo for iOS device`() {
        val info = DeviceInfo(
            model = "iPhone 15 Pro",
            manufacturer = "Apple",
            cpu = "A17 Pro",
            gpu = "Apple GPU",
            ram = "8.0 GB",
            cores = 6,
            osVersion = "17.4",
            resolution = "1179x2556",
            platform = DevicePlatform.IOS,
        )
        assertEquals("17.4", info.osVersion)
        assertEquals(DevicePlatform.IOS, info.platform)
    }

    // ===== FrameSnapshot =====

    @Test
    fun `FrameSnapshot holds frame timing data`() {
        val snap = FrameSnapshot(fps = 60, avgFrameTime = 16.2, jankCount = 2, stutterCount = 0)
        assertEquals(60, snap.fps)
        assertEquals(16.2, snap.avgFrameTime, 0.01)
        assertEquals(2, snap.jankCount)
        assertEquals(0, snap.stutterCount)
    }

    @Test
    fun `FrameSnapshot data class equality`() {
        val a = FrameSnapshot(60, 16.2, 2, 0)
        val b = FrameSnapshot(60, 16.2, 2, 0)
        assertEquals(a, b)
    }

    // ===== MemSnapshot =====

    @Test
    fun `MemSnapshot holds memory data`() {
        val snap = MemSnapshot(totalMb = 280, nativeMb = 120, javaMb = 80)
        assertEquals(280, snap.totalMb)
        assertEquals(120, snap.nativeMb)
        assertEquals(80, snap.javaMb)
    }

    // ===== ThermalSnapshot =====

    @Test
    fun `ThermalSnapshot holds thermal data`() {
        val snap = ThermalSnapshot(cpu = 45.0, gpu = 42.0, battery = 35.0, skin = 33.0)
        assertEquals(45.0, snap.cpu, 0.01)
        assertEquals(42.0, snap.gpu, 0.01)
    }

    @Test
    fun `ThermalSnapshot sentinel defaults for unavailable metrics`() {
        val snap = ThermalSnapshot(cpu = 45.0, gpu = -1.0, battery = 35.0, skin = -1.0)
        assertEquals(-1.0, snap.gpu, 0.01)
        assertEquals(-1.0, snap.skin, 0.01)
    }

    // ===== ScreenCaptureConfig =====

    @Test
    fun `ScreenCaptureConfig with default fps`() {
        val config = ScreenCaptureConfig(width = 720, height = 1280, bitRate = 4_000_000)
        assertEquals(720, config.width)
        assertEquals(1280, config.height)
        assertEquals(4_000_000, config.bitRate)
        assertEquals(30, config.fps)
    }

    @Test
    fun `ScreenCaptureConfig with custom fps`() {
        val config = ScreenCaptureConfig(width = 540, height = 960, bitRate = 2_000_000, fps = 60)
        assertEquals(60, config.fps)
    }

    // ===== ScreenCaptureHandle sealed class =====

    @Test
    fun `ScreenCaptureHandle ProcessHandle wraps Process`() {
        // Use a dummy process (cat /dev/null exits immediately)
        val process = ProcessBuilder("cat", "/dev/null").start()
        val handle = ScreenCaptureHandle.ProcessHandle(process)
        assertTrue(handle is ScreenCaptureHandle)
        assertEquals(process, handle.process)
        process.waitFor()
    }

    @Test
    fun `ScreenCaptureHandle SidecarHandle wraps captureId`() {
        val handle = ScreenCaptureHandle.SidecarHandle(captureId = "session-abc-123")
        assertTrue(handle is ScreenCaptureHandle)
        assertEquals("session-abc-123", handle.captureId)
    }

    @Test
    fun `ScreenCaptureHandle subclasses dispatch correctly`() {
        val process = ProcessBuilder("cat", "/dev/null").start()
        val handles: List<ScreenCaptureHandle> = listOf(
            ScreenCaptureHandle.ProcessHandle(process),
            ScreenCaptureHandle.SidecarHandle("abc"),
        )
        var processCount = 0
        var sidecarCount = 0
        for (h in handles) {
            when (h) {
                is ScreenCaptureHandle.ProcessHandle -> processCount++
                is ScreenCaptureHandle.SidecarHandle -> sidecarCount++
            }
        }
        assertEquals(1, processCount)
        assertEquals(1, sidecarCount)
        process.waitFor()
    }

    // ===== MetricAvailability enum =====

    @Test
    fun `MetricAvailability has three values`() {
        val values = MetricAvailability.values()
        assertEquals(3, values.size)
        assertEquals(MetricAvailability.AVAILABLE, MetricAvailability.valueOf("AVAILABLE"))
        assertEquals(MetricAvailability.PARTIAL, MetricAvailability.valueOf("PARTIAL"))
        assertEquals(MetricAvailability.NOT_AVAILABLE, MetricAvailability.valueOf("NOT_AVAILABLE"))
    }
}
