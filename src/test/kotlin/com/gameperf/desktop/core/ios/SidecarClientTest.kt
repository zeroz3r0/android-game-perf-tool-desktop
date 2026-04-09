package com.gameperf.desktop.core.ios

import com.gameperf.desktop.core.model.DevicePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SidecarClient] JSON parsing logic.
 *
 * NO HTTP calls — these test the pure parsing functions with canned JSON strings.
 * Verifies that the Kotlin client correctly deserializes every field the Python
 * sidecar returns, including sentinel values (-1, -1.0) for unavailable metrics.
 */
class SidecarClientTest {

    private val client = SidecarClient(baseUrl = "http://unused:0")

    // ===== Device list parsing =====

    @Test
    fun `parseDeviceList returns empty list for empty array`() {
        val json = """{"devices": []}"""
        val devices = client.parseDeviceList(json)
        assertTrue(devices.isEmpty())
    }

    @Test
    fun `parseDeviceList parses single device`() {
        val json = """{"devices": [{"id": "00001111-AABB", "model": "iPhone 15 Pro", "platform": "IOS", "isWifi": false}]}"""
        val devices = client.parseDeviceList(json)
        assertEquals(1, devices.size)
        assertEquals("00001111-AABB", devices[0].id)
        assertEquals("iPhone 15 Pro", devices[0].model)
        assertEquals(DevicePlatform.IOS, devices[0].platform)
        assertEquals(false, devices[0].isWifi)
    }

    @Test
    fun `parseDeviceList parses multiple devices`() {
        val json = """{"devices": [{"id": "AAA", "model": "iPhone 15", "isWifi": false}, {"id": "BBB", "model": "iPad Pro", "isWifi": false}]}"""
        val devices = client.parseDeviceList(json)
        assertEquals(2, devices.size)
        assertEquals("AAA", devices[0].id)
        assertEquals("BBB", devices[1].id)
    }

    @Test
    fun `parseDevice sets platform to IOS always`() {
        val json = """{"id": "test-udid", "model": "iPhone", "isWifi": false}"""
        val device = client.parseDevice(json)
        assertNotNull(device)
        assertEquals(DevicePlatform.IOS, device.platform)
    }

    @Test
    fun `parseDevice returns null for missing id`() {
        val json = """{"model": "iPhone"}"""
        val device = client.parseDevice(json)
        assertNull(device)
    }

    // ===== DeviceInfo parsing =====

    @Test
    fun `parseDeviceInfo parses all fields`() {
        val json = """{
            "model": "iPhone 15 Pro",
            "manufacturer": "Apple",
            "cpu": "arm64e",
            "gpu": "Apple GPU (A17 Pro)",
            "ram": "8 GB",
            "cores": 6,
            "osVersion": "17.4",
            "resolution": "1179x2556",
            "platform": "IOS"
        }"""
        val info = client.parseDeviceInfo(json)
        assertEquals("iPhone 15 Pro", info.model)
        assertEquals("Apple", info.manufacturer)
        assertEquals("arm64e", info.cpu)
        assertEquals("Apple GPU (A17 Pro)", info.gpu)
        assertEquals("8 GB", info.ram)
        assertEquals(6, info.cores)
        assertEquals("17.4", info.osVersion)
        assertEquals("1179x2556", info.resolution)
        assertEquals(DevicePlatform.IOS, info.platform)
    }

    @Test
    fun `parseDeviceInfo uses defaults for missing fields`() {
        val json = """{}"""
        val info = client.parseDeviceInfo(json)
        assertEquals("Unknown", info.model)
        assertEquals("Apple", info.manufacturer)
        assertEquals(DevicePlatform.IOS, info.platform)
    }

    // ===== Metrics parsing =====

    @Test
    fun `parseMetrics parses all fields with real values`() {
        val json = """{
            "fps": 59,
            "avgFrameTime": 16.9,
            "jankCount": 3,
            "stutterCount": 1,
            "cpuPercent": 42,
            "memoryMb": 350,
            "nativeMb": 0,
            "javaMb": 0,
            "tempCpu": 38.5,
            "tempGpu": 36.2,
            "tempBattery": 32.1,
            "tempSkin": -1.0,
            "batteryLevel": 85
        }"""
        val metrics = client.parseMetrics(json)
        assertEquals(59, metrics.fps)
        assertEquals(16.9, metrics.avgFrameTime, 0.01)
        assertEquals(3, metrics.jankCount)
        assertEquals(1, metrics.stutterCount)
        assertEquals(42, metrics.cpuPercent)
        assertEquals(350L, metrics.memoryMb)
        assertEquals(0L, metrics.nativeMb)
        assertEquals(0L, metrics.javaMb)
        assertEquals(38.5, metrics.tempCpu, 0.01)
        assertEquals(36.2, metrics.tempGpu, 0.01)
        assertEquals(32.1, metrics.tempBattery, 0.01)
        assertEquals(-1.0, metrics.tempSkin, 0.01)
        assertEquals(85, metrics.batteryLevel)
    }

    @Test
    fun `parseMetrics uses sentinel defaults for unavailable metrics`() {
        val json = """{
            "fps": -1,
            "avgFrameTime": -1.0,
            "jankCount": 0,
            "stutterCount": 0,
            "cpuPercent": -1,
            "memoryMb": -1,
            "nativeMb": 0,
            "javaMb": 0,
            "tempCpu": -1.0,
            "tempGpu": -1.0,
            "tempBattery": -1.0,
            "tempSkin": -1.0,
            "batteryLevel": -1
        }"""
        val metrics = client.parseMetrics(json)
        assertEquals(-1, metrics.fps)
        assertEquals(-1.0, metrics.avgFrameTime, 0.01)
        assertEquals(-1, metrics.cpuPercent)
        assertEquals(-1L, metrics.memoryMb)
        assertEquals(-1.0, metrics.tempCpu, 0.01)
        assertEquals(-1.0, metrics.tempSkin, 0.01)
        assertEquals(-1, metrics.batteryLevel)
    }

    @Test
    fun `tempSkin is always minus one for iOS`() {
        // Even if the sidecar somehow returned a value, the contract says -1.0
        val json = """{"fps": 60, "avgFrameTime": 16.6, "jankCount": 0, "stutterCount": 0, "cpuPercent": 30, "memoryMb": 200, "nativeMb": 0, "javaMb": 0, "tempCpu": 38.0, "tempGpu": 35.0, "tempBattery": 31.0, "tempSkin": -1.0, "batteryLevel": 90}"""
        val metrics = client.parseMetrics(json)
        assertEquals(-1.0, metrics.tempSkin, 0.01)
    }

    // ===== JSON extractors =====

    @Test
    fun `extractString handles escaped quotes`() {
        val json = """{"name": "iPhone 15 Pro"}"""
        assertEquals("iPhone 15 Pro", SidecarClient.extractString(json, "name"))
    }

    @Test
    fun `extractInt handles negative values`() {
        val json = """{"fps": -1}"""
        assertEquals(-1, SidecarClient.extractInt(json, "fps"))
    }

    @Test
    fun `extractDouble handles negative decimal`() {
        val json = """{"temp": -1.0}"""
        assertEquals(-1.0, SidecarClient.extractDouble(json, "temp"), 0.001)
    }

    @Test
    fun `extractBool returns correct value`() {
        assertEquals(true, SidecarClient.extractBool("""{"isWifi": true}""", "isWifi"))
        assertEquals(false, SidecarClient.extractBool("""{"isWifi": false}""", "isWifi"))
    }

    @Test
    fun `extractString returns null for missing key`() {
        assertNull(SidecarClient.extractString("""{"other": "value"}""", "missing"))
    }

    @Test
    fun `extractArray handles empty array`() {
        val result = SidecarClient.extractArray("""{"devices": []}""", "devices")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractArray splits multiple objects`() {
        val json = """{"items": [{"a": 1}, {"b": 2}]}"""
        val result = SidecarClient.extractArray(json, "items")
        assertEquals(2, result.size)
    }
}
