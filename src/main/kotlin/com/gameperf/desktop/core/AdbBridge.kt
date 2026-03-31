package com.gameperf.desktop.core

import java.util.Calendar
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Minimal ADB bridge for the desktop app.
 * Extracts device info, metrics, and logs from ADB commands.
 */
object AdbBridge {

    /**
     * Resolve ADB path. macOS packaged apps don't inherit terminal PATH,
     * so we check common locations.
     */
    private val adbPath: String by lazy {
        // 1. Try PATH first (works from terminal / ./gradlew run)
        try {
            val p = ProcessBuilder("which", "adb").start()
            val result = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (result.isNotEmpty() && java.io.File(result).exists()) return@lazy result
        } catch (_: Exception) {}

        // 2. Check common install locations
        val candidates = listOf(
            "/usr/local/bin/adb",
            "/opt/homebrew/bin/adb",
            "${System.getProperty("user.home")}/Library/Android/sdk/platform-tools/adb",
            "/usr/bin/adb",
            "C:\\platform-tools\\adb.exe",
            "${System.getenv("LOCALAPPDATA") ?: ""}\\Android\\Sdk\\platform-tools\\adb.exe"
        )
        candidates.firstOrNull { java.io.File(it).exists() } ?: "adb"
    }

    fun exec(vararg args: String, timeoutMs: Long = 5000): String {
        return try {
            // Replace "adb" with resolved path
            val resolvedArgs = args.toMutableList()
            if (resolvedArgs.firstOrNull() == "adb") resolvedArgs[0] = adbPath
            val pb = ProcessBuilder(*resolvedArgs.toTypedArray())
            pb.redirectErrorStream(true)
            val process = pb.start()
            val outputFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().readText()
            }
            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                return try { outputFuture.get(500, TimeUnit.MILLISECONDS) } catch (_: Exception) { "" }
            }
            try { outputFuture.get(1000, TimeUnit.MILLISECONDS) } catch (_: Exception) { "" }
        } catch (_: Exception) { "" }
    }

    fun shell(deviceId: String, cmd: String, timeoutMs: Long = 5000): String =
        exec("adb", "-s", deviceId, "shell", cmd, timeoutMs = timeoutMs)

    fun isAvailable(): Boolean = exec("adb", "version").isNotEmpty()

    // ===== Devices =====

    data class Device(val id: String, val model: String, val isWifi: Boolean)

    fun listDevices(): List<Device> {
        val output = exec("adb", "devices", "-l")
        if (output.isBlank()) return emptyList()
        return output.lines()
            .filter { it.contains("device") && !it.contains("List") && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 2 && parts[1] == "device") {
                    val id = parts[0]
                    val model = Regex("model:(\\S+)").find(line)?.groupValues?.get(1) ?: "Unknown"
                    Device(id, model, id.contains(":"))
                } else null
            }
    }

    fun switchToWifi(usbDeviceId: String, port: Int = 5555): String? {
        val ipOutput = shell(usbDeviceId, "ip addr show wlan0")
        val ip = Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(ipOutput)?.groupValues?.get(1) ?: return null
        exec("adb", "-s", usbDeviceId, "tcpip", "$port", timeoutMs = 5000)
        Thread.sleep(2000)
        val connectOutput = exec("adb", "connect", "$ip:$port", timeoutMs = 5000)
        return if (connectOutput.contains("connected")) "$ip:$port" else null
    }

    data class DeviceInfo(
        val model: String, val manufacturer: String, val cpu: String,
        val gpu: String, val ram: String, val cores: Int,
        val sdk: Int, val resolution: String
    )

    fun getDeviceInfo(deviceId: String): DeviceInfo {
        val model = shell(deviceId, "getprop ro.product.model").trim()
        val mfr = shell(deviceId, "getprop ro.product.manufacturer").trim()
        val hw = shell(deviceId, "getprop ro.hardware").trim()
        val plat = shell(deviceId, "getprop ro.board.platform").trim()
        val sdk = shell(deviceId, "getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0
        val res = shell(deviceId, "wm size").trim()
        val ramKb = Regex("MemTotal:\\s+(\\d+)").find(shell(deviceId, "cat /proc/meminfo"))
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val ramGb = "%.1f GB".format(ramKb * 1024.0 / (1024 * 1024 * 1024))
        val cores = Regex("processor\\s*:\\s*(\\d+)").findAll(shell(deviceId, "cat /proc/cpuinfo")).count().let { if (it > 0) it else 4 }
        val sf = shell(deviceId, "dumpsys SurfaceFlinger", timeoutMs = 3000)
        val gpu = Regex("GLES:\\s*(.+)").find(sf)?.groupValues?.get(1)?.trim()?.take(60) ?: shell(deviceId, "getprop ro.hardware.egl").trim().ifEmpty { "Unknown" }
        return DeviceInfo(model.ifEmpty { "Unknown" }, mfr.ifEmpty { "Unknown" }, "$hw $plat".trim().ifEmpty { "Unknown" }, gpu, ramGb, cores, sdk, res.ifEmpty { "Unknown" })
    }

    fun getBatteryLevel(deviceId: String): Int {
        val output = shell(deviceId, "dumpsys battery")
        return Regex("level: (\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    fun getBatteryTemp(deviceId: String): Float {
        val output = shell(deviceId, "dumpsys battery")
        return Regex("temperature: (\\d+)").find(output)?.groupValues?.get(1)?.toFloatOrNull()?.div(10f) ?: 0f
    }

    // ===== Game Detection =====

    fun detectGame(deviceId: String): String? {
        val output = shell(deviceId, "dumpsys activity activities")
        val systemPrefixes = listOf("com.android.", "com.google.android.", "android.", "com.motorola.", "com.samsung.", "com.huawei.", "com.xiaomi.", "com.oppo.", "com.bbk.", "com.coloros.", "com.miui.")
        val systemKeywords = listOf("launcher", "systemui", "settings", "keyboard", "inputmethod")
        for (pattern in listOf(Regex("packageName=([\\w.]+)"), Regex("cmp=([\\w.]+)/"))) {
            for (match in pattern.findAll(output)) {
                val pkg = match.groupValues[1]
                if (pkg.contains(".") && systemPrefixes.none { pkg.startsWith(it) } && systemKeywords.none { pkg.contains(it, true) })
                    return pkg
            }
        }
        return null
    }

    // ===== Metrics =====

    private var cachedLayer: Pair<String, String>? = null
    private var prevCpuBusy: Long = 0
    private var prevCpuTotal: Long = 0
    private var prevCpuInitialized: Boolean = false

    fun findLayer(deviceId: String, pkg: String): String? {
        cachedLayer?.let { (p, l) -> if (p == pkg) return l }
        val output = exec("adb", "-s", deviceId, "shell", "dumpsys", "SurfaceFlinger", "--list")
        val layers = output.lines().filter { it.contains(pkg) }
        val extracted = layers.map { line ->
            val m = Regex("RequestedLayerState\\{(.+?)\\s+parentId=").find(line)
            m?.groupValues?.get(1)?.trim() ?: line.trim()
        }.filter { it.isNotBlank() }
        val found = extracted.find { it.contains("SurfaceView") && it.contains("BLAST") }
            ?: extracted.find { it.contains("SurfaceView") && !it.contains("Background") }
            ?: extracted.firstOrNull()
        if (found != null) cachedLayer = pkg to found
        return found
    }

    data class FrameSnapshot(val fps: Int, val avgFrameTime: Double, val jankCount: Int, val stutterCount: Int)

    fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? {
        val layer = findLayer(deviceId, pkg) ?: return null
        val output = shell(deviceId, "dumpsys SurfaceFlinger --latency '$layer'")
        val lines = output.lines()
        if (lines.size < 3) return null
        val times = mutableListOf<Long>()
        for (i in 1 until lines.size) {
            val parts = lines[i].trim().split("\\s+".toRegex())
            if (parts.size >= 2) {
                val ts = parts[1].toLongOrNull() ?: continue
                if (ts > 0 && ts < Long.MAX_VALUE / 2 && (times.isEmpty() || ts >= times.last())) times.add(ts)
            }
        }
        if (times.size < 2) return null
        val frameTimes = (1 until times.size).mapNotNull { i ->
            val d = (times[i] - times[i - 1]) / 1_000_000.0
            if (d in 0.1..1000.0) d else null
        }
        if (frameTimes.isEmpty()) return null
        val windowNs = 1_000_000_000L
        val windowed = times.filter { it >= times.last() - windowNs }
        val fps = if (windowed.size >= 2) {
            val delta = (windowed.last() - windowed.first()) / 1_000_000_000.0
            if (delta > 0) ((windowed.size - 1) / delta).toInt().coerceIn(1, 144) else 0
        } else 0
        return FrameSnapshot(fps, frameTimes.average(), frameTimes.count { it > 16.67 }, frameTimes.count { it > 100.0 })
    }

    data class MemSnapshot(val totalMb: Long, val nativeMb: Long, val javaMb: Long)

    fun captureMemory(deviceId: String, pkg: String): MemSnapshot? {
        val output = shell(deviceId, "dumpsys meminfo $pkg", timeoutMs = 8000)
        val total = (Regex("TOTAL PSS:\\s+(\\d+)").find(output) ?: Regex("TOTAL\\s+(\\d+)").find(output))
            ?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val native = Regex("Native Heap\\s+(\\d+)").find(output)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val java = Regex("(?:Dalvik|Java) Heap\\s+(\\d+)").find(output)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return MemSnapshot(total / 1024, native / 1024, java / 1024)
    }

    fun captureCpuPercent(deviceId: String): Int {
        val output = shell(deviceId, "cat /proc/stat")
        val line = output.lines().firstOrNull { it.startsWith("cpu ") } ?: return -1
        val p = line.trim().split("\\s+".toRegex())
        if (p.size < 8) return -1
        val busy = (1..3).sumOf { p[it].toLongOrNull() ?: 0L } + (6..7).sumOf { p[it].toLongOrNull() ?: 0L }
        val total = (1..7).sumOf { p[it].toLongOrNull() ?: 0L }
        if (!prevCpuInitialized) {
            prevCpuBusy = busy
            prevCpuTotal = total
            prevCpuInitialized = true
            return -1
        }
        val deltaBusy = busy - prevCpuBusy
        val deltaTotal = total - prevCpuTotal
        prevCpuBusy = busy
        prevCpuTotal = total
        return if (deltaTotal > 0) (deltaBusy * 100 / deltaTotal).toInt().coerceIn(0, 100) else -1
    }

    data class ThermalSnapshot(val cpu: Double, val gpu: Double, val battery: Double, val skin: Double)

    fun captureTemperature(deviceId: String): ThermalSnapshot {
        var cpu = -1.0; var gpu = -1.0; var battery = -1.0; var skin = -1.0
        // Try sysfs first
        val zones = shell(deviceId, "for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null):\$(cat \$z/temp 2>/dev/null)\"; done", timeoutMs = 3000)
        for (line in zones.lines()) {
            val parts = line.split(":"); if (parts.size != 2) continue
            val type = parts[0].lowercase(); val raw = parts[1].trim().toLongOrNull() ?: continue
            val temp = if (raw > 1000) raw / 1000.0 else raw.toDouble()
            when {
                (type.contains("cpu") || type.contains("tsens") || type.contains("soc")) && cpu < 0 -> cpu = temp
                type.contains("gpu") && gpu < 0 -> gpu = temp
                (type.contains("battery") || type.contains("batt")) && battery < 0 -> battery = temp
                (type.contains("skin") || type.contains("quiet")) && skin < 0 -> skin = temp
            }
        }
        // Fallback: thermalservice
        if (cpu < 0 || gpu < 0) {
            val dump = shell(deviceId, "dumpsys thermalservice", timeoutMs = 3000)
            for (m in Regex("Temperature\\{mValue=([\\d.]+),\\s*mType=\\d+,\\s*mName=([^,]+),").findAll(dump)) {
                val v = m.groupValues[1].toDoubleOrNull() ?: continue
                val n = m.groupValues[2].trim().lowercase()
                when {
                    (n == "big" || n == "little" || n == "mid" || n.contains("cpu")) && cpu < 0 -> cpu = v
                    (n == "g3d" || n.contains("gpu")) && gpu < 0 -> gpu = v
                    n == "battery" && battery < 0 -> battery = v
                    (n.contains("skin") || n.contains("quiet") || n == "virtual-skin") && skin < 0 -> skin = v
                }
            }
        }
        return ThermalSnapshot(cpu, gpu, battery, skin)
    }

    fun getMissedFrames(deviceId: String): Int {
        val output = shell(deviceId, "dumpsys SurfaceFlinger", timeoutMs = 3000)
        return Regex("Total missed frame count:\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun disableCharging(deviceId: String) = shell(deviceId, "dumpsys battery unplug")
    fun restoreCharging(deviceId: String) = shell(deviceId, "dumpsys battery reset")

    // ===== Screen Recording =====

    /**
     * Start screen recording on device.
     * adb screenrecord has 3-min limit per file, so we chain segments.
     * Uses 720p and 4Mbps to keep it lightweight (hardware encoder on SoC, no game GPU impact).
     * sessionId is used to create unique filenames per session.
     */
    fun startScreenRecord(deviceId: String, sessionId: String, segment: Int = 0): Process? {
        return try {
            val remotePath = "/sdcard/gp_${sessionId}_$segment.mp4"
            val pb = ProcessBuilder(adbPath, "-s", deviceId, "shell", "screenrecord", "--size", "720x1280", "--bit-rate", "4000000", "--time-limit", "180", remotePath)
            pb.redirectErrorStream(true)
            pb.start()
        } catch (_: Exception) { null }
    }

    fun stopScreenRecord(process: Process?) {
        try { process?.destroyForcibly() } catch (_: Exception) {}
    }

    /**
     * Pull all recorded segments for a session from device to local dir, then delete from device.
     */
    fun pullRecordings(deviceId: String, sessionId: String, localDir: java.io.File, maxSegments: Int = 20): List<java.io.File> {
        val files = mutableListOf<java.io.File>()
        for (i in 0..maxSegments) {
            val remotePath = "/sdcard/gp_${sessionId}_$i.mp4"
            val check = shell(deviceId, "ls $remotePath 2>/dev/null")
            if (check.isBlank() || check.contains("No such file")) break
            val localFile = java.io.File(localDir, "video_${sessionId}_$i.mp4")
            exec(adbPath, "-s", deviceId, "pull", remotePath, localFile.absolutePath, timeoutMs = 60000)
            if (localFile.exists() && localFile.length() > 0) files.add(localFile)
            shell(deviceId, "rm $remotePath")
        }
        return files
    }

    fun cleanRecordings(deviceId: String) {
        shell(deviceId, "rm -f /sdcard/gp_*.mp4")
    }
}
