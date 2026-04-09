package com.gameperf.desktop.core.bridge

import com.gameperf.desktop.core.model.*
import com.gameperf.desktop.testing.FakeDeviceBridge
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Contract tests for [CompositeBridge].
 * Verifies routing by platform, merged device lists, single-platform degradation,
 * and isAvailable any-true logic.
 *
 * Uses [FakeDeviceBridge] — a minimal [DeviceBridgeApi] fake with scripted responses.
 */
class CompositeBridgeTest {

    // ===== Merged device list =====

    @Test
    fun `listDevices merges Android and iOS devices`() {
        val android = FakeDeviceBridge(
            devices = listOf(
                Device("pixel-6", "Pixel 6", DevicePlatform.ANDROID),
            ),
        )
        val ios = FakeDeviceBridge(
            devices = listOf(
                Device("udid-iphone15", "iPhone 15", DevicePlatform.IOS),
            ),
        )
        val composite = CompositeBridge(android, ios)
        val all = composite.listDevices()

        assertEquals(2, all.size)
        assertEquals(DevicePlatform.ANDROID, all[0].platform)
        assertEquals(DevicePlatform.IOS, all[1].platform)
    }

    @Test
    fun `listDevices with null iOS bridge returns only Android`() {
        val android = FakeDeviceBridge(
            devices = listOf(
                Device("pixel-6", "Pixel 6", DevicePlatform.ANDROID),
            ),
        )
        val composite = CompositeBridge(android, null)
        val all = composite.listDevices()

        assertEquals(1, all.size)
        assertEquals(DevicePlatform.ANDROID, all[0].platform)
    }

    @Test
    fun `listDevices with empty bridges returns empty list`() {
        val composite = CompositeBridge(FakeDeviceBridge(), null)
        assertTrue(composite.listDevices().isEmpty())
    }

    // ===== isAvailable — any-true logic =====

    @Test
    fun `isAvailable true when both bridges available`() {
        val composite = CompositeBridge(
            FakeDeviceBridge(available = true),
            FakeDeviceBridge(available = true),
        )
        assertTrue(composite.isAvailable())
    }

    @Test
    fun `isAvailable true when only Android available`() {
        val composite = CompositeBridge(
            FakeDeviceBridge(available = true),
            FakeDeviceBridge(available = false),
        )
        assertTrue(composite.isAvailable())
    }

    @Test
    fun `isAvailable true when only iOS available`() {
        val composite = CompositeBridge(
            FakeDeviceBridge(available = false),
            FakeDeviceBridge(available = true),
        )
        assertTrue(composite.isAvailable())
    }

    @Test
    fun `isAvailable true when iOS bridge is null but Android available`() {
        val composite = CompositeBridge(
            FakeDeviceBridge(available = true),
            null,
        )
        assertTrue(composite.isAvailable())
    }

    @Test
    fun `isAvailable false when both unavailable`() {
        val composite = CompositeBridge(
            FakeDeviceBridge(available = false),
            FakeDeviceBridge(available = false),
        )
        assertFalse(composite.isAvailable())
    }

    // ===== Routing by platform =====

    @Test
    fun `captureFrames routes to correct bridge by device platform`() {
        val androidSnap = FrameSnapshot(fps = 60, avgFrameTime = 16.6, jankCount = 1, stutterCount = 0)
        val iosSnap = FrameSnapshot(fps = 59, avgFrameTime = 16.8, jankCount = 2, stutterCount = 1)
        val android = FakeDeviceBridge(
            devices = listOf(Device("pixel", "Pixel", DevicePlatform.ANDROID)),
            frameSnapshot = androidSnap,
        )
        val ios = FakeDeviceBridge(
            devices = listOf(Device("iphone", "iPhone", DevicePlatform.IOS)),
            frameSnapshot = iosSnap,
        )
        val composite = CompositeBridge(android, ios)

        // Force device registration
        composite.listDevices()

        assertEquals(androidSnap, composite.captureFrames("pixel", "com.test"))
        assertEquals(iosSnap, composite.captureFrames("iphone", "com.test"))
    }

    @Test
    fun `captureCpuPercent routes to correct bridge`() {
        val android = FakeDeviceBridge(
            devices = listOf(Device("pixel", "Pixel", DevicePlatform.ANDROID)),
            cpuPercent = 45,
        )
        val ios = FakeDeviceBridge(
            devices = listOf(Device("iphone", "iPhone", DevicePlatform.IOS)),
            cpuPercent = 32,
        )
        val composite = CompositeBridge(android, ios)
        composite.listDevices()

        assertEquals(45, composite.captureCpuPercent("pixel"))
        assertEquals(32, composite.captureCpuPercent("iphone"))
    }

    @Test
    fun `captureMemory routes to correct bridge`() {
        val androidMem = MemSnapshot(totalMb = 280, nativeMb = 120, javaMb = 80)
        val iosMem = MemSnapshot(totalMb = 350, nativeMb = 0, javaMb = 0)
        val android = FakeDeviceBridge(
            devices = listOf(Device("pixel", "Pixel", DevicePlatform.ANDROID)),
            memSnapshot = androidMem,
        )
        val ios = FakeDeviceBridge(
            devices = listOf(Device("iphone", "iPhone", DevicePlatform.IOS)),
            memSnapshot = iosMem,
        )
        val composite = CompositeBridge(android, ios)
        composite.listDevices()

        assertEquals(androidMem, composite.captureMemory("pixel", "com.test"))
        assertEquals(iosMem, composite.captureMemory("iphone", "com.test"))
    }

    @Test
    fun `captureTemperature routes to correct bridge`() {
        val androidTemp = ThermalSnapshot(45.0, 42.0, 35.0, 33.0)
        val iosTemp = ThermalSnapshot(38.5, -1.0, 32.1, -1.0)
        val android = FakeDeviceBridge(
            devices = listOf(Device("pixel", "Pixel", DevicePlatform.ANDROID)),
            thermalSnapshot = androidTemp,
        )
        val ios = FakeDeviceBridge(
            devices = listOf(Device("iphone", "iPhone", DevicePlatform.IOS)),
            thermalSnapshot = iosTemp,
        )
        val composite = CompositeBridge(android, ios)
        composite.listDevices()

        assertEquals(androidTemp, composite.captureTemperature("pixel"))
        assertEquals(iosTemp, composite.captureTemperature("iphone"))
    }

    @Test
    fun `getDeviceInfo routes to correct bridge`() {
        val androidInfo = DeviceInfo("Pixel", "Google", "Tensor", "Mali", "8 GB", 8, "33", "1080x2400", DevicePlatform.ANDROID)
        val iosInfo = DeviceInfo("iPhone 15", "Apple", "A16", "Apple GPU", "6 GB", 6, "17.4", "1179x2556", DevicePlatform.IOS)
        val android = FakeDeviceBridge(
            devices = listOf(Device("pixel", "Pixel", DevicePlatform.ANDROID)),
            deviceInfo = androidInfo,
        )
        val ios = FakeDeviceBridge(
            devices = listOf(Device("iphone", "iPhone", DevicePlatform.IOS)),
            deviceInfo = iosInfo,
        )
        val composite = CompositeBridge(android, ios)
        composite.listDevices()

        assertEquals(androidInfo, composite.getDeviceInfo("pixel"))
        assertEquals(iosInfo, composite.getDeviceInfo("iphone"))
    }

    @Test
    fun `getBatteryLevel routes to correct bridge`() {
        val android = FakeDeviceBridge(
            devices = listOf(Device("pixel", "Pixel", DevicePlatform.ANDROID)),
            batteryLevel = 85,
        )
        val ios = FakeDeviceBridge(
            devices = listOf(Device("iphone", "iPhone", DevicePlatform.IOS)),
            batteryLevel = 72,
        )
        val composite = CompositeBridge(android, ios)
        composite.listDevices()

        assertEquals(85, composite.getBatteryLevel("pixel"))
        assertEquals(72, composite.getBatteryLevel("iphone"))
    }

    @Test
    fun `detectGame routes to correct bridge`() {
        val android = FakeDeviceBridge(
            devices = listOf(Device("pixel", "Pixel", DevicePlatform.ANDROID)),
            detectedGame = "com.android.game",
        )
        val ios = FakeDeviceBridge(
            devices = listOf(Device("iphone", "iPhone", DevicePlatform.IOS)),
            detectedGame = "com.ios.game",
        )
        val composite = CompositeBridge(android, ios)
        composite.listDevices()

        assertEquals("com.android.game", composite.detectGame("pixel"))
        assertEquals("com.ios.game", composite.detectGame("iphone"))
    }

    @Test
    fun `resetSessionState delegates to all bridges`() {
        val android = FakeDeviceBridge()
        val ios = FakeDeviceBridge()
        val composite = CompositeBridge(android, ios)

        composite.resetSessionState()

        assertTrue(android.resetCalled)
        assertTrue(ios.resetCalled)
    }

    @Test
    fun `resetSessionState with null iOS bridge only resets Android`() {
        val android = FakeDeviceBridge()
        val composite = CompositeBridge(android, null)

        composite.resetSessionState()

        assertTrue(android.resetCalled)
    }

    // ===== Single-platform degradation =====

    @Test
    fun `routing with unknown device ID returns null for nullable methods`() {
        val composite = CompositeBridge(FakeDeviceBridge(), null)
        composite.listDevices()

        assertNull(composite.captureFrames("unknown-device", "com.test"))
        assertNull(composite.captureMemory("unknown-device", "com.test"))
        assertNull(composite.detectGame("unknown-device"))
    }
}

// FakeDeviceBridge is now in com.gameperf.desktop.testing.FakeDeviceBridge
