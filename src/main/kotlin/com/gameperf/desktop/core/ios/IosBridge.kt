package com.gameperf.desktop.core.ios

import com.gameperf.desktop.core.bridge.DeviceBridgeApi
import com.gameperf.desktop.core.model.*
import java.io.File

/**
 * iOS implementation of [DeviceBridgeApi].
 *
 * Wraps [SidecarClient] to communicate with the pymobiledevice3 FastAPI sidecar.
 * Each method maps 1:1 to a sidecar HTTP endpoint.
 *
 * Unavailable metrics use the sentinel convention: -1 for Int, -1.0 for Double.
 * [ScreenCaptureHandle.SidecarHandle] wraps the sidecar's capture session ID.
 *
 * @param client The HTTP client configured to talk to the sidecar.
 */
class IosBridge(
    private val client: SidecarClient,
) : DeviceBridgeApi {

    /**
     * Cached metrics snapshot per device to avoid making 4+ HTTP calls per poll cycle.
     * The sidecar's metrics endpoint returns ALL metrics in one response — we should
     * call it ONCE per cycle and serve captureFrames/captureCpu/captureMemory/captureTemperature
     * from the cache.
     *
     * Cache is invalidated on each captureFrames() call (which is always the first
     * metric the VM's poll loop requests).
     */
    @Volatile
    private var cachedMetrics: Pair<String, SidecarClient.MetricsSnapshot>? = null
    private val cacheLock = Any()

    /** Fetch metrics from sidecar, caching the result for the current poll cycle. */
    private fun getMetricsCached(deviceId: String): SidecarClient.MetricsSnapshot? {
        synchronized(cacheLock) {
            val cached = cachedMetrics
            if (cached != null && cached.first == deviceId) {
                return cached.second
            }
        }
        val fresh = client.getMetrics(deviceId) ?: return null
        synchronized(cacheLock) {
            cachedMetrics = deviceId to fresh
        }
        return fresh
    }

    /** Invalidate the cache — called at the start of each poll cycle. */
    private fun invalidateCache() {
        synchronized(cacheLock) {
            cachedMetrics = null
        }
    }

    /** True if the sidecar is running and healthy. */
    override fun isAvailable(): Boolean = client.isHealthy()

    override fun listDevices(): List<Device> = client.listDevices()

    override fun getDeviceInfo(deviceId: String): DeviceInfo =
        client.getDeviceInfo(deviceId)
            ?: DeviceInfo("Unknown", "Apple", "Unknown", "Apple GPU", "Unknown", 0, "Unknown", "Unknown", DevicePlatform.IOS)

    override fun detectGame(deviceId: String): String? {
        // v4.1.0: ask the sidecar for the frontmost app bundle ID.
        // The sidecar uses SpringBoardServices or instruments to detect it.
        // Falls back to null if the sidecar doesn't support it or returns empty.
        return client.detectForegroundApp(deviceId)
    }

    override fun getBatteryLevel(deviceId: String): Int {
        val metrics = getMetricsCached(deviceId)
        return metrics?.batteryLevel ?: -1
    }

    override fun resetSessionState() {
        invalidateCache()
    }

    override fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? {
        // captureFrames is always the FIRST metric called in the poll loop —
        // invalidate cache so we fetch fresh data from the sidecar
        invalidateCache()
        val metrics = getMetricsCached(deviceId) ?: return null
        if (metrics.fps < 0) return null
        return FrameSnapshot(
            fps = metrics.fps,
            avgFrameTime = metrics.avgFrameTime,
            jankCount = metrics.jankCount,
            stutterCount = metrics.stutterCount,
        )
    }

    override fun captureCpuPercent(deviceId: String): Int {
        val metrics = getMetricsCached(deviceId)
        return metrics?.cpuPercent ?: -1
    }

    override fun captureMemory(deviceId: String, pkg: String): MemSnapshot? {
        val metrics = getMetricsCached(deviceId) ?: return null
        if (metrics.memoryMb < 0) return null
        return MemSnapshot(
            totalMb = metrics.memoryMb,
            nativeMb = metrics.nativeMb,  // Always 0 on iOS
            javaMb = metrics.javaMb,      // Always 0 on iOS
        )
    }

    override fun captureTemperature(deviceId: String): ThermalSnapshot {
        val metrics = getMetricsCached(deviceId)
            ?: return ThermalSnapshot(-1.0, -1.0, -1.0, -1.0)
        return ThermalSnapshot(
            cpu = metrics.tempCpu,
            gpu = metrics.tempGpu,
            battery = metrics.tempBattery,
            skin = metrics.tempSkin,  // Always -1.0 on iOS
        )
    }

    override fun startScreenCapture(
        deviceId: String,
        sessionId: String,
        config: ScreenCaptureConfig,
    ): ScreenCaptureHandle? {
        val captureId = client.startScreenRecord(deviceId, sessionId) ?: return null
        return ScreenCaptureHandle.SidecarHandle(captureId)
    }

    override fun stopScreenCapture(handle: ScreenCaptureHandle) {
        when (handle) {
            is ScreenCaptureHandle.SidecarHandle -> {
                // The sidecar handles stopping via the capture ID
                // We need the device ID too — stored in the handle isn't ideal
                // but for now the sidecar uses the capture_id to find the session
                client.stopScreenRecord("_", handle.captureId)
            }
            is ScreenCaptureHandle.ProcessHandle -> {
                // This shouldn't happen — iOS bridge doesn't produce ProcessHandles
            }
        }
    }

    override fun pullRecordings(
        deviceId: String,
        sessionId: String,
        localDir: File,
        maxSegments: Int,
    ): List<File> {
        // iOS recordings are already local (sidecar saves to ~/GamePerf Reports/)
        // Look for the file by session ID
        val videoFile = File(localDir, "ios_video_$sessionId.mp4")
        return if (videoFile.exists()) listOf(videoFile) else emptyList()
    }

    override fun cleanRecordings(deviceId: String) {
        // No device-side cleanup needed — iOS recordings are local
    }

    override fun concatSegments(segments: List<File>, output: File): File? {
        // iOS recordings are single files (no segmenting like Android's screenrecord)
        return if (segments.size == 1 && segments[0].exists()) {
            segments[0].copyTo(output, overwrite = true)
            output
        } else null
    }

    /**
     * v4.1.0: improved validation — now uses ffprobe (same as Android path) when
     * available, falling back to size check only when ffprobe is missing.
     * This catches corrupt MP4s that have non-zero size but broken moov atoms.
     */
    override fun isValidVideoFile(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        // Try ffprobe validation (mirrors AdbBridge.isValidVideoFile behavior)
        return try {
            val ffprobe = findFfprobe() ?: return file.length() > 1024  // degraded: size check
            val pb = ProcessBuilder(
                ffprobe, "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.absolutePath
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val finished = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) { proc.destroyForcibly(); return false }
            if (proc.exitValue() != 0) return false
            val duration = out.lines().firstOrNull()?.toDoubleOrNull()
            duration != null && duration > 0.0
        } catch (_: Exception) {
            file.length() > 1024  // degraded fallback
        }
    }

    private fun findFfprobe(): String? {
        try {
            val p = ProcessBuilder("which", "ffprobe").start()
            val result = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (result.isNotEmpty() && java.io.File(result).exists()) return result
        } catch (_: Exception) {}
        val candidates = listOf("/usr/local/bin/ffprobe", "/opt/homebrew/bin/ffprobe", "/usr/bin/ffprobe")
        return candidates.firstOrNull { java.io.File(it).exists() }
    }
}
