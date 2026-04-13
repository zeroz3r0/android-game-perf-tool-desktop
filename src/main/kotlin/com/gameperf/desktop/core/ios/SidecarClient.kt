package com.gameperf.desktop.core.ios

import com.gameperf.desktop.core.model.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for the pymobiledevice3 FastAPI sidecar.
 *
 * Uses JDK HttpURLConnection (zero external deps) to communicate with
 * the sidecar running on localhost. All methods are blocking — callers
 * should invoke from a coroutine on Dispatchers.IO.
 *
 * JSON parsing is manual (no kotlinx.serialization needed) to keep the
 * uber JAR size unchanged. The sidecar API is small and stable.
 *
 * @param baseUrl Base URL of the sidecar, e.g. "http://127.0.0.1:8765"
 * @param timeoutMs Connection and read timeout in milliseconds.
 */
open class SidecarClient(
    private val baseUrl: String = "http://127.0.0.1:8765",
    private val timeoutMs: Int = 5000,
) {

    // ===== Health =====

    /** Returns true if the sidecar is healthy. */
    open fun isHealthy(): Boolean {
        return try {
            val json = get("/health")
            json?.contains("\"ok\"") == true
        } catch (_: Exception) {
            false
        }
    }

    // ===== Devices =====

    /** List connected iOS devices. */
    open fun listDevices(): List<Device> {
        val json = get("/devices") ?: return emptyList()
        return parseDeviceList(json)
    }

    /** Get device info. */
    open fun getDeviceInfo(udid: String): DeviceInfo? {
        val json = get("/device/$udid/info") ?: return null
        return parseDeviceInfo(json)
    }

    // ===== Game Detection =====

    /**
     * v4.1.0: Ask the sidecar for the current foreground app bundle ID.
     * Returns null if the sidecar doesn't support this endpoint or returns empty.
     * The sidecar uses SpringBoardServices to detect the frontmost app.
     */
    open fun detectForegroundApp(udid: String): String? {
        val json = get("/device/$udid/foreground-app") ?: return null
        val bundleId = extractString(json, "bundleId")
        // Filter out system apps (like on Android)
        if (bundleId != null) {
            val systemPrefixes = listOf("com.apple.", "com.apple.springboard")
            if (systemPrefixes.any { bundleId.startsWith(it) }) return null
        }
        return bundleId
    }

    // ===== Metrics =====

    /** Get latest metrics snapshot. */
    open fun getMetrics(udid: String): MetricsSnapshot? {
        val json = get("/device/$udid/metrics") ?: return null
        return parseMetrics(json)
    }

    // ===== Screen Capture =====

    /** Take a single screenshot. Returns PNG bytes or null. */
    open fun takeScreenshot(udid: String): ByteArray? {
        return try {
            val conn = openConnection("/device/$udid/screenshot")
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                conn.inputStream.use { it.readBytes() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /** Start screen recording. Returns capture ID or null. */
    open fun startScreenRecord(udid: String, sessionId: String): String? {
        val json = post("/device/$udid/screen-record/start?session_id=$sessionId") ?: return null
        return extractString(json, "captureId")
    }

    /** Stop screen recording. Returns video file path or null. */
    open fun stopScreenRecord(udid: String, captureId: String): String? {
        val json = post("/device/$udid/screen-record/stop?capture_id=$captureId") ?: return null
        return extractString(json, "videoPath")
    }

    /** Request graceful shutdown. */
    open fun shutdown() {
        try { post("/shutdown") } catch (_: Exception) { }
    }

    // ===== HTTP helpers =====

    private fun get(path: String): String? {
        return try {
            val conn = openConnection(path)
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun post(path: String, body: String = ""): String? {
        return try {
            val conn = openConnection(path)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (body.isNotEmpty()) {
                conn.outputStream.use { it.write(body.toByteArray()) }
            } else {
                conn.outputStream.use { it.write(ByteArray(0)) }
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun openConnection(path: String): HttpURLConnection {
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return conn
    }

    // ===== JSON parsing (manual, minimal) =====

    internal fun parseDeviceList(json: String): List<Device> {
        // Parse {"devices": [{...}, ...]}
        val devicesArray = extractArray(json, "devices")
        return devicesArray.mapNotNull { parseDevice(it) }
    }

    internal fun parseDevice(json: String): Device? {
        val id = extractString(json, "id") ?: return null
        val model = extractString(json, "model") ?: "Unknown"
        val isWifi = extractBool(json, "isWifi")
        return Device(id = id, model = model, platform = DevicePlatform.IOS, isWifi = isWifi)
    }

    internal fun parseDeviceInfo(json: String): DeviceInfo {
        return DeviceInfo(
            model = extractString(json, "model") ?: "Unknown",
            manufacturer = extractString(json, "manufacturer") ?: "Apple",
            cpu = extractString(json, "cpu") ?: "Unknown",
            gpu = extractString(json, "gpu") ?: "Apple GPU",
            ram = extractString(json, "ram") ?: "Unknown",
            cores = extractInt(json, "cores"),
            osVersion = extractString(json, "osVersion") ?: "Unknown",
            resolution = extractString(json, "resolution") ?: "Unknown",
            platform = DevicePlatform.IOS,
        )
    }

    internal fun parseMetrics(json: String): MetricsSnapshot {
        return MetricsSnapshot(
            fps = extractInt(json, "fps", -1),
            avgFrameTime = extractDouble(json, "avgFrameTime", -1.0),
            jankCount = extractInt(json, "jankCount", 0),
            stutterCount = extractInt(json, "stutterCount", 0),
            cpuPercent = extractInt(json, "cpuPercent", -1),
            memoryMb = extractLong(json, "memoryMb", -1),
            nativeMb = extractLong(json, "nativeMb", 0),
            javaMb = extractLong(json, "javaMb", 0),
            tempCpu = extractDouble(json, "tempCpu", -1.0),
            tempGpu = extractDouble(json, "tempGpu", -1.0),
            tempBattery = extractDouble(json, "tempBattery", -1.0),
            tempSkin = extractDouble(json, "tempSkin", -1.0),
            batteryLevel = extractInt(json, "batteryLevel", -1),
        )
    }

    /** Intermediate type for sidecar metrics response. */
    data class MetricsSnapshot(
        val fps: Int,
        val avgFrameTime: Double,
        val jankCount: Int,
        val stutterCount: Int,
        val cpuPercent: Int,
        val memoryMb: Long,
        val nativeMb: Long,
        val javaMb: Long,
        val tempCpu: Double,
        val tempGpu: Double,
        val tempBattery: Double,
        val tempSkin: Double,
        val batteryLevel: Int,
    )

    // ===== Primitive JSON extractors (no library needed) =====

    companion object {
        internal fun extractString(json: String, key: String): String? {
            val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\""
            return Regex(pattern).find(json)?.groupValues?.get(1)
        }

        internal fun extractInt(json: String, key: String, default: Int = 0): Int {
            val pattern = "\"$key\"\\s*:\\s*(-?\\d+)"
            return Regex(pattern).find(json)?.groupValues?.get(1)?.toIntOrNull() ?: default
        }

        internal fun extractLong(json: String, key: String, default: Long = 0): Long {
            val pattern = "\"$key\"\\s*:\\s*(-?\\d+)"
            return Regex(pattern).find(json)?.groupValues?.get(1)?.toLongOrNull() ?: default
        }

        internal fun extractDouble(json: String, key: String, default: Double = 0.0): Double {
            val pattern = "\"$key\"\\s*:\\s*(-?[\\d.]+)"
            return Regex(pattern).find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: default
        }

        internal fun extractBool(json: String, key: String, default: Boolean = false): Boolean {
            val pattern = "\"$key\"\\s*:\\s*(true|false)"
            return Regex(pattern).find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: default
        }

        internal fun extractArray(json: String, key: String): List<String> {
            // Find "key": [...] and split by },{ to get individual objects
            val arrayPattern = "\"$key\"\\s*:\\s*\\[([^\\]]*)]"
            val arrayContent = Regex(arrayPattern).find(json)?.groupValues?.get(1)?.trim()
                ?: return emptyList()
            if (arrayContent.isEmpty()) return emptyList()

            // Split by },{ but keep the braces
            val objects = mutableListOf<String>()
            var depth = 0
            var start = 0
            for (i in arrayContent.indices) {
                when (arrayContent[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            objects.add(arrayContent.substring(start, i + 1).trim())
                            start = i + 1
                        }
                    }
                    ',' -> if (depth == 0) start = i + 1
                }
            }
            return objects
        }
    }
}
