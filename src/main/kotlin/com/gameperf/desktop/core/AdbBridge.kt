package com.gameperf.desktop.core

import com.gameperf.desktop.core.model.Device
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.model.FrameSnapshot
import com.gameperf.desktop.core.model.MemSnapshot
import com.gameperf.desktop.core.model.ThermalSnapshot
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Minimal ADB bridge for the desktop app.
 * Extracts device info, metrics, and logs from ADB commands.
 *
 * v4.2.2: Returns `core.model.*` types directly. The previously nested
 * `@Deprecated` data classes (AdbBridge.Device/DeviceInfo/FrameSnapshot/
 * MemSnapshot/ThermalSnapshot) have been removed after the v4.1.0
 * migration period. Consumers already used the `core.model.*` versions via
 * [AdbBridgeApi].
 */
object AdbBridge {

    // ===== Frame analysis tunables (v4.2.5) =====
    //
    // Documented and centralized so they're easy to find / change. Previously
    // these were magic numbers scattered across captureFrames with no docstring.

    /** Cap for valid frame time in ms. Anything bigger is treated as a clock-skew
     *  read or stale data and discarded. v4.2.4 was 1000ms (lost evidence of any
     *  hang > 1 second); v4.2.5 raised to 5000ms to capture multi-second hangs. */
    internal const val MAX_FRAME_TIME_MS = 5000.0

    /** Cap for instantaneous FPS in [computeFrameSnapshot]. v4.2.4 was 144;
     *  v4.2.5 raised to 240 to support Razer Phone, ASUS ROG Phone 8, OnePlus 12
     *  in 240hz mode. */
    internal const val MAX_FPS = 240

    /** Multiplier applied to the inferred target frame time to define "jank".
     *  Industry convention: a frame > 1.5 × target is visibly slow. */
    internal const val JANK_MULTIPLIER = 1.5

    /** Frame time (ms) above which we count a frame as a "stutter" (visible
     *  freeze regardless of target FPS). 100ms = ~10fps, severe enough to be
     *  perceived as a stall by the user even on a 30fps target game. */
    internal const val STUTTER_THRESHOLD_MS = 100.0

    /** Min/max realistic temperature (°C) for a phone sensor. Anything outside
     *  this range is a sensor read error (we've seen 850°C from corrupt zones)
     *  and gets discarded. -40°C lower bound covers cold-boot scenarios. */
    internal const val MIN_REALISTIC_TEMP_C = -40.0
    internal const val MAX_REALISTIC_TEMP_C = 150.0

    // ===== Pre-compiled regex patterns (v4.1.0-perf) =====
    // Moved from inline Regex(...) to top-level compiled constants.
    // Avoids re-compilation on every call in hot paths (captureFrames, captureMemory, etc.).

    private val RE_VALID_PACKAGE = Regex("^[a-zA-Z0-9._]+$")
    private val RE_VALID_DEVICE_ID = Regex("^[a-zA-Z0-9.:_-]+$")
    private val RE_VALID_SESSION_ID = Regex("^[a-zA-Z0-9_-]+$")
    private val RE_DEVICE_LINE = "\\s+".toRegex()
    private val RE_MODEL = Regex("model:(\\S+)")
    private val RE_MEM_TOTAL = Regex("MemTotal:\\s+(\\d+)")
    private val RE_PROCESSOR = Regex("processor\\s*:\\s*(\\d+)")
    private val RE_GLES = Regex("GLES:\\s*(.+)")
    private val RE_BATTERY_LEVEL = Regex("level: (\\d+)")
    private val RE_BATTERY_TEMP = Regex("temperature: (\\d+)")
    private val RE_PACKAGE_NAME = Regex("packageName=([\\w.]+)")
    private val RE_CMP = Regex("cmp=([\\w.]+)/")
    private val RE_INET = Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)")
    private val RE_TOTAL_PSS = Regex("TOTAL PSS:\\s+(\\d+)")
    private val RE_TOTAL_FALLBACK = Regex("TOTAL\\s+(\\d+)")
    private val RE_NATIVE_HEAP = Regex("Native Heap\\s+(\\d+)")
    private val RE_JAVA_HEAP = Regex("(?:Dalvik|Java) Heap\\s+(\\d+)")
    private val RE_MISSED_FRAMES = Regex("Total missed frame count:\\s*(\\d+)")
    private val RE_THERMAL_TEMP = Regex("Temperature\\{mValue=([\\d.]+),\\s*mType=\\d+,\\s*mName=([^,]+),")
    private val RE_SF_MODERN = Regex("RequestedLayerState\\{(.+?)\\s+parentId=")
    private val RE_ROTATION = Regex("mCurrentRotation=ROTATION_(\\d+)")

    // ===== Cached tool paths (v4.1.0-perf) =====
    // findFfmpeg/findFfprobe were called on every concatSegments/isValidVideoFile invocation.
    // Now cached via lazy so the PATH lookup happens at most once per JVM lifetime.

    private val cachedFfmpegPath: String? by lazy { findFfmpegImpl() }
    private val cachedFfprobePath: String? by lazy { findFfprobeImpl() }

    // ===== Input validation (v3.2.1-security) =====

    /** Validates an Android package name: only alphanumeric, dots, and underscores. */
    private fun isValidPackageName(pkg: String): Boolean =
        pkg.isNotEmpty() && pkg.matches(RE_VALID_PACKAGE)

    /** Validates a device ID: alphanumeric, dots, colons, underscores, dashes (covers USB serial + IP:port). */
    private fun isValidDeviceId(id: String): Boolean =
        id.isNotEmpty() && id.matches(RE_VALID_DEVICE_ID)

    /** Validates a session ID: alphanumeric, underscores, dashes. */
    private fun isValidSessionId(id: String): Boolean =
        id.isNotEmpty() && id.matches(RE_VALID_SESSION_ID)

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

    fun shell(deviceId: String, cmd: String, timeoutMs: Long = 5000): String {
        require(isValidDeviceId(deviceId)) { "Invalid device ID: $deviceId" }
        return exec("adb", "-s", deviceId, "shell", cmd, timeoutMs = timeoutMs)
    }

    fun isAvailable(): Boolean = exec("adb", "version").isNotEmpty()

    // ===== Devices =====

    fun listDevices(): List<Device> {
        val output = exec("adb", "devices", "-l")
        if (output.isBlank()) return emptyList()
        return output.lines()
            .filter { it.contains("device") && !it.contains("List") && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split(RE_DEVICE_LINE)
                if (parts.size >= 2 && parts[1] == "device") {
                    val id = parts[0]
                    val rawModel = RE_MODEL.find(line)?.groupValues?.get(1) ?: "Unknown"
                    // v4.2.5: resolve cryptic codenames (SM-S911B) to marketing names
                    // (Samsung Galaxy S23). adb devices -l doesn't expose manufacturer
                    // so we pass empty — the resolver still does prefix matching for
                    // the codename, which covers Samsung/Xiaomi/OnePlus/etc. that use
                    // codename schemes the resolver knows about.
                    val displayModel = DeviceNameResolver.resolve(rawModel)
                    Device(id = id, model = displayModel, platform = DevicePlatform.ANDROID, isWifi = id.contains(":"))
                } else null
            }
    }

    fun switchToWifi(usbDeviceId: String, port: Int = 5555): String? {
        val ipOutput = shell(usbDeviceId, "ip addr show wlan0")
        val ip = RE_INET.find(ipOutput)?.groupValues?.get(1) ?: return null
        exec("adb", "-s", usbDeviceId, "tcpip", "$port", timeoutMs = 5000)
        Thread.sleep(2000)
        val connectOutput = exec("adb", "connect", "$ip:$port", timeoutMs = 5000)
        return if (connectOutput.contains("connected")) "$ip:$port" else null
    }

    fun getDeviceInfo(deviceId: String): DeviceInfo {
        val rawModel = shell(deviceId, "getprop ro.product.model").trim()
        val mfr = shell(deviceId, "getprop ro.product.manufacturer").trim()
        val hw = shell(deviceId, "getprop ro.hardware").trim()
        val plat = shell(deviceId, "getprop ro.board.platform").trim()
        val sdk = shell(deviceId, "getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0
        val res = shell(deviceId, "wm size").trim()
        val ramKb = RE_MEM_TOTAL.find(shell(deviceId, "cat /proc/meminfo"))
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val ramGb = String.format(java.util.Locale.US, "%.1f GB", ramKb * 1024.0 / (1024 * 1024 * 1024))
        val cores = RE_PROCESSOR.findAll(shell(deviceId, "cat /proc/cpuinfo")).count().let { if (it > 0) it else 4 }
        val sf = shell(deviceId, "dumpsys SurfaceFlinger", timeoutMs = 3000)
        val gpu = RE_GLES.find(sf)?.groupValues?.get(1)?.trim()?.take(60) ?: shell(deviceId, "getprop ro.hardware.egl").trim().ifEmpty { "Unknown" }
        // v4.2.5: marketing-name resolution. Here we have the manufacturer, so the
        // fallback for unknown codenames is "<Manufacturer> <Code>" instead of just
        // the bare code.
        val displayModel = DeviceNameResolver.resolve(rawModel, mfr).ifEmpty { "Unknown" }
        return DeviceInfo(
            model = displayModel,
            manufacturer = mfr.ifEmpty { "Unknown" },
            cpu = "$hw $plat".trim().ifEmpty { "Unknown" },
            gpu = gpu,
            ram = ramGb,
            cores = cores,
            osVersion = sdk.toString(),
            resolution = res.ifEmpty { "Unknown" },
            platform = DevicePlatform.ANDROID,
        )
    }

    fun getBatteryLevel(deviceId: String): Int {
        val output = shell(deviceId, "dumpsys battery")
        return RE_BATTERY_LEVEL.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    fun getBatteryTemp(deviceId: String): Float {
        val output = shell(deviceId, "dumpsys battery")
        return RE_BATTERY_TEMP.find(output)?.groupValues?.get(1)?.toFloatOrNull()?.div(10f) ?: 0f
    }

    // ===== Game Detection =====

    fun detectGame(deviceId: String): String? {
        val output = shell(deviceId, "dumpsys activity activities")
        val systemPrefixes = listOf(
            "com.android.", "com.google.android.", "android.",
            "com.motorola.", "com.samsung.", "com.huawei.",
            "com.xiaomi.", "com.oppo.", "com.bbk.", "com.coloros.", "com.miui.",
        )
        val systemKeywords = listOf("launcher", "systemui", "settings", "keyboard", "inputmethod")
        for (pattern in listOf(RE_PACKAGE_NAME, RE_CMP)) {
            for (match in pattern.findAll(output)) {
                val pkg = match.groupValues[1]
                if (pkg.contains(".") && systemPrefixes.none { pkg.startsWith(it) } && systemKeywords.none { pkg.contains(it, true) })
                    return pkg
            }
        }
        return null
    }

    // ===== Metrics =====

    @Volatile
    private var cachedLayer: Pair<String, String>? = null
    private var prevCpuBusy: Long = 0
    private var prevCpuTotal: Long = 0
    private var prevCpuInitialized: Boolean = false
    private val cpuLock = Any()

    // v4.2.5: per-process CPU state for the new pkg-scoped captureCpuPercent.
    // We cache the package -> PID mapping so we don't shell out `pidof` every
    // iteration. PID is invalidated automatically if /proc/<pid>/stat returns
    // empty (process died, package was reopened, etc.) — see captureProcessCpuPercent.
    private val pidCpuLock = Any()
    private val cachedPidByPkg: MutableMap<String, Int> = mutableMapOf()
    private val prevPidProcJiffies: MutableMap<Int, Long> = mutableMapOf()
    private val prevPidSystemJiffies: MutableMap<Int, Long> = mutableMapOf()

    /** Reset session-scoped state so consecutive captures start clean. */
    fun resetSessionState() {
        cachedLayer = null
        synchronized(cpuLock) {
            prevCpuBusy = 0
            prevCpuTotal = 0
            prevCpuInitialized = false
        }
        // v4.2.5: also clear the per-process CPU caches so a new session
        // starts with fresh deltas and the first sample correctly returns -1
        // (insufficient data) rather than a stale value from the previous session.
        synchronized(pidCpuLock) {
            cachedPidByPkg.clear()
            prevPidProcJiffies.clear()
            prevPidSystemJiffies.clear()
        }
    }

    /**
     * Resolve the SurfaceFlinger layer name for a package so that `dumpsys SurfaceFlinger
     * --latency '<layer>'` works. The output format of `dumpsys SurfaceFlinger --list`
     * has changed across Android versions:
     *
     *   - **Android 9 and earlier**: plain layer names, one per line.
     *   - **Android 10 (SDK 29)**: plain layer names, one per line. Example: `SurfaceView[com.touch2goal.soccer/com.unity3d.player.UnityPlayerActivity]@0`
     *   - **Android 11 (SDK 30)**: plain layer names per line, with `- animation-leash` and similar decorations for system layers.
     *   - **Android 12+ (SDK 31+)**: adds `RequestedLayerState{<name>  parentId=<n>}` wrappers around each entry when SurfaceFlinger runs in its newer format.
     *
     * v3.1.9 and earlier only parsed the Android 12+ `RequestedLayerState{...}` format,
     * which meant on Android 10/11 devices (like the Pixel XL running SDK 29) the regex
     * never matched, the code fell back to returning a raw line like `SurfaceView[...]@0`,
     * and then `dumpsys --latency '<raw-line>'` returned empty because the layer name
     * didn't match SurfaceFlinger's internal identifier. Symptom: `fpsHistory` was empty
     * for the entire session despite the game running normally.
     *
     * v3.1.10: handle both formats. See `parseSurfaceFlingerListOutput` for the pure
     * parsing logic — that function is unit-testable because it takes the raw output as
     * a string instead of calling adb.
     */
    fun findLayer(deviceId: String, pkg: String): String? {
        require(isValidPackageName(pkg)) { "Invalid package name: $pkg" }
        cachedLayer?.let { (p, l) -> if (p == pkg) return l }
        val output = exec("adb", "-s", deviceId, "shell", "dumpsys", "SurfaceFlinger", "--list")
        val found = parseSurfaceFlingerListOutput(output, pkg)
        if (found != null) cachedLayer = pkg to found
        return found
    }

    /**
     * Pure parser for `dumpsys SurfaceFlinger --list` output. Extracted from [findLayer]
     * so it can be unit-tested without mocking adb.
     *
     * Handles both the Android 12+ `RequestedLayerState{<name> parentId=<n>}` format
     * and the pre-12 plain-line format. Candidate selection: prefer `SurfaceView[BLAST]`,
     * then `SurfaceView` excluding `Background`, then the first layer containing the
     * package name.
     *
     * Returns null if no candidate line mentions the package.
     */
    internal fun parseSurfaceFlingerListOutput(output: String, pkg: String): String? {
        val layers = output.lines().filter { it.contains(pkg) }
        val extracted = layers.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            // Android 12+ format: `RequestedLayerState{<name>  parentId=<n>}`
            val modern = RE_SF_MODERN.find(trimmed)
            if (modern != null) {
                modern.groupValues[1].trim().takeIf { it.isNotBlank() }
            } else {
                // Pre-Android-12 format (including Android 10 SDK 29 on the Pixel XL):
                // the line IS the layer name, just trimmed. Don't strip anything else —
                // SurfaceFlinger needs the exact string including any `#N` or `@N` suffix.
                trimmed
            }
        }

        return extracted.find { it.contains("SurfaceView") && it.contains("BLAST") }
            ?: extracted.find { it.contains("SurfaceView") && !it.contains("Background") }
            ?: extracted.firstOrNull()
    }

    fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? {
        var layer = findLayer(deviceId, pkg) ?: run {
            // No layer at all — game might be hidden behind an ad.
            // Clear cache so next call does a fresh lookup.
            cachedLayer = null
            return null
        }
        // Shell-quote the layer name: names like "SurfaceView[...](BLAST)#N"
        // contain parentheses that cause /system/bin/sh syntax errors when
        // passed as separate ProcessBuilder args (adb concatenates them into
        // a single shell command).
        var output = shell(deviceId, "dumpsys SurfaceFlinger --latency '$layer'")
        var lines = output.lines()
        // Stale layer detection: ads, scene changes, or Unity recreating its
        // SurfaceView change the layer number suffix (#N). The cached name
        // becomes invalid → --latency returns only the refresh rate (1 line).
        // Always invalidate and re-resolve so that returning from an ad
        // picks up the new layer immediately.
        if (lines.size < 3) {
            cachedLayer = null
            layer = findLayer(deviceId, pkg) ?: return null
            output = shell(deviceId, "dumpsys SurfaceFlinger --latency '$layer'")
            lines = output.lines()
            if (lines.size < 3) return null
        }
        val times = mutableListOf<Long>()
        for (i in 1 until lines.size) {
            val parts = lines[i].trim().split(RE_DEVICE_LINE)
            if (parts.size >= 2) {
                val ts = parts[1].toLongOrNull() ?: continue
                if (ts > 0 && ts < Long.MAX_VALUE / 2 && (times.isEmpty() || ts >= times.last())) times.add(ts)
            }
        }
        return computeFrameSnapshot(times)
    }

    /**
     * Pure helper — calculates [FrameSnapshot] from a list of frame timestamps in
     * nanoseconds. Extracted from [captureFrames] so it can be unit-tested without
     * a real ADB connection.
     *
     * v4.2.5 changes (all motivated by user-visible reliability bugs in v4.2.x):
     *
     * - **Frame time cap raised 1000ms → 5000ms**: any frame slower than 1 second
     *   used to be silently discarded, which lost evidence of multi-second hangs
     *   (e.g. a 2.5s freeze when the GC pauses). 5s is still a reasonable upper
     *   bound to filter clearly bogus reads (negative timestamps, clock skew).
     *
     * - **FPS cap raised 144 → 240**: high-refresh-rate phones (Razer Phone 2,
     *   ASUS ROG Phone 8, OnePlus 12 in 240hz mode) can legitimately render at
     *   165-240 fps. The old 144 cap was rounding their numbers down silently.
     *
     * - **Jank threshold is now dynamic**, scaled by the inferred target frame
     *   time (see [inferTargetFrameTime]). Previously it was hardcoded at 16.67ms,
     *   which made every frame in a 30fps game count as jank (33ms > 16.67ms is
     *   always true) — the metric was effectively useless for non-60fps games.
     *   Now jank = frame > 1.5 × target, so a 30fps game with steady 33ms frames
     *   produces 0 jank, and the same game stuttering to 60ms produces real jank.
     *
     * - **Stutter threshold kept at 100ms** (~10fps): visible to the user as
     *   freeze/dropped-frame regardless of target FPS.
     */
    internal fun computeFrameSnapshot(timestampsNs: List<Long>): FrameSnapshot? {
        if (timestampsNs.size < 2) return null

        val frameTimes = (1 until timestampsNs.size).mapNotNull { i ->
            val d = (timestampsNs[i] - timestampsNs[i - 1]) / 1_000_000.0
            if (d in 0.1..MAX_FRAME_TIME_MS) d else null
        }
        if (frameTimes.isEmpty()) return null

        // FPS over the last 1 second of timestamps (instantaneous FPS).
        // v4.2.5: use Math.round (not truncating toInt) to avoid an off-by-one when
        // the timestamps barely span less than the full 1s window — e.g. 60 frames
        // spaced 16.67ms cover 0.983s, and (60-1) / 0.983 = 59.99999... which the
        // old toInt() truncated to 59. Round-to-nearest gives the correct 60.
        val windowNs = 1_000_000_000L
        val windowed = timestampsNs.filter { it >= timestampsNs.last() - windowNs }
        val fps = if (windowed.size >= 2) {
            val delta = (windowed.last() - windowed.first()) / 1_000_000_000.0
            if (delta > 0) {
                Math.round((windowed.size - 1) / delta).toInt().coerceIn(1, MAX_FPS)
            } else 0
        } else 0

        val avgFrameTime = frameTimes.average()
        val targetFrameTime = inferTargetFrameTime(avgFrameTime)
        val jankThreshold = targetFrameTime * JANK_MULTIPLIER

        return FrameSnapshot(
            fps = fps,
            avgFrameTime = avgFrameTime,
            jankCount = frameTimes.count { it > jankThreshold },
            stutterCount = frameTimes.count { it > STUTTER_THRESHOLD_MS },
        )
    }

    /**
     * Infer the game's target frame time (in ms) from the observed average. Used
     * to scale jank thresholds proportionally so a 30fps game isn't penalized for
     * hitting 33ms frames (which is on-target, not jank).
     *
     * The buckets correspond to the most common mobile-game refresh strategies:
     * - <=12ms  → 120 fps (high-refresh competitive games like CoD Mobile, PUBG 90+)
     * - <=18ms  → 60 fps  (the default for most games)
     * - <=28ms  → 45 fps  (Unity/Unreal "auto" mode on mid-range, some Pokemon Unite settings)
     * - <=40ms  → 30 fps  (intentional cap for battery, or low-end devices struggling)
     * - >40ms   → <20 fps (broken / overheated / GC-thrashing — anything is jank)
     *
     * Pure function, unit-testable.
     */
    internal fun inferTargetFrameTime(avgMs: Double): Double = when {
        avgMs <= 12.0 -> 8.33   // 120 fps target
        avgMs <= 18.0 -> 16.67  // 60 fps target
        avgMs <= 28.0 -> 22.22  // 45 fps target
        avgMs <= 40.0 -> 33.33  // 30 fps target
        else -> 50.0            // <20 fps — anything above 75ms (50*1.5) is jank
    }

    /**
     * Capture memory PSS for the given package.
     *
     * v4.2.5 — improved parser to prefer the App Summary section.
     *
     * `dumpsys meminfo <pkg>` output has TWO different "TOTAL PSS:" lines:
     *
     * 1. The detailed table near the top, where TOTAL is the sum of every
     *    allocation category (Native Heap + Dalvik + Stack + Cursor + Ashmem +
     *    Other dev + ... etc.). On some OEMs this number doesn't match the App
     *    Summary one because of how mmapped regions are accounted.
     *
     * 2. The App Summary section near the bottom (Android 5+), labeled
     *    `App Summary` with a clear "TOTAL PSS: <N>" line. Per the Android
     *    Memory Profiler docs, this is the canonical "memory used by your app"
     *    number — sum of Java + Native + Code + Stack + Graphics + Private Other
     *    + System.
     *
     * Pre-v4.2.5 the regex agreed with whichever line came FIRST, which on
     * Android 12+ was the table version (sometimes wrong by 5-15%). v4.2.5
     * specifically looks INSIDE "App Summary" first, with the table-version
     * regex as fallback for Android <5 (which had no App Summary section).
     *
     * Sanity check: anything above 16384 MB (16 GB) is treated as a parse error
     * — no Android device has more than 16GB RAM in 2025. Anything 0 is
     * impossible for a running process.
     */
    fun captureMemory(deviceId: String, pkg: String): MemSnapshot? {
        require(isValidPackageName(pkg)) { "Invalid package name: $pkg" }
        val output = exec("adb", "-s", deviceId, "shell", "dumpsys", "meminfo", pkg, timeoutMs = 8000)

        // v4.2.5: prefer the App Summary section's TOTAL PSS over the first
        // match, which on Android 12+ is the detailed-table TOTAL (different by
        // 5-15% on some devices).
        val appSummary = output.substringAfter("App Summary", missingDelimiterValue = "")
        val totalKb = (
            if (appSummary.isNotEmpty()) RE_TOTAL_PSS.find(appSummary)?.groupValues?.get(1)?.toLongOrNull()
            else null
        )
            ?: RE_TOTAL_PSS.find(output)?.groupValues?.get(1)?.toLongOrNull()
            ?: RE_TOTAL_FALLBACK.find(output)?.groupValues?.get(1)?.toLongOrNull()
            ?: return null

        val totalMb = totalKb / 1024
        // v4.2.5: sanity range. Phones top out at ~16GB RAM in 2025 and a
        // running process can't be 0MB. Anything outside this is a parse error.
        if (totalMb !in 1L..16384L) return null

        val native = RE_NATIVE_HEAP.find(output)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val java = RE_JAVA_HEAP.find(output)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return MemSnapshot(totalMb, native / 1024, java / 1024)
    }

    /**
     * Capture CPU usage as a percentage.
     *
     * v4.2.5 — overloaded to support per-process measurement:
     *
     * - **`captureCpuPercent(deviceId, pkg)`** (NEW): returns the GAME's CPU usage
     *   as a percentage of the device's total CPU capacity. This is what the user
     *   actually wants to see in the metrics panel — "how hard is the game working
     *   the device". Implementation reads `/proc/<pid>/stat` (utime + stime) and
     *   compares against `/proc/stat` total time.
     *
     * - **`captureCpuPercent(deviceId)`** (legacy, kept for back-compat): returns
     *   the device-wide CPU usage (sum across all processes). Was the only behavior
     *   pre-v4.2.5; many existing call sites would break if we removed it. The bug
     *   it caused: AppViewModel was passing only deviceId, so the "CPU 30%" the
     *   user saw was the entire phone's CPU, not the game's. Fixed in AppViewModel
     *   by passing pkg as well. Direct callers that haven't migrated still work
     *   but get the legacy meaning.
     *
     * Both overloads return -1 on the first call of a session (no delta yet) and
     * on any failure; the AppViewModel filters those out before recording history.
     */
    fun captureCpuPercent(deviceId: String, pkg: String): Int {
        if (!isValidPackageName(pkg)) return captureCpuPercent(deviceId)
        return captureProcessCpuPercent(deviceId, pkg)
    }

    fun captureCpuPercent(deviceId: String): Int {
        val output = shell(deviceId, "cat /proc/stat")
        val line = output.lines().firstOrNull { it.startsWith("cpu ") } ?: return -1
        val p = line.trim().split(RE_DEVICE_LINE)
        if (p.size < 8) return -1
        val busy = (1..3).sumOf { p[it].toLongOrNull() ?: 0L } + (6..7).sumOf { p[it].toLongOrNull() ?: 0L }
        val total = (1..7).sumOf { p[it].toLongOrNull() ?: 0L }
        synchronized(cpuLock) {
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
    }

    /**
     * Per-process CPU% via `/proc/<pid>/stat`. Returns 0-100 representing the
     * fraction of the device's total CPU time consumed by the game process between
     * consecutive samples. Single-threaded games on a quad-core max out around 25%
     * (one core saturated); a fully multi-threaded game can hit 100%.
     *
     * Algorithm:
     * 1. Resolve package -> PID via `pidof` (cached so we only pay once per session).
     * 2. Read /proc/<pid>/stat columns 14 (utime) + 15 (stime) = process CPU jiffies.
     * 3. Read /proc/stat "cpu " line for system total jiffies (sum of all CPU time
     *    fields, which is "all cores combined").
     * 4. (procDelta / systemDelta) * 100 = % of device CPU consumed by the process.
     *
     * Edge cases handled:
     * - `pidof` returns empty (game not running): returns -1, caller doesn't record.
     * - `/proc/<pid>/stat` empty (PID died, process restarted): invalidates the
     *    cached PID + delta state and returns -1 (next call will re-resolve).
     * - First call of a session: no previous delta, returns -1.
     * - Counter rolled back (impossible on real Linux but defensive): returns -1.
     *
     * /proc/<pid>/stat format gotcha: the second field `comm` is the process name
     * wrapped in parens, and CAN CONTAIN SPACES. So we can't split on whitespace
     * blindly. We find the LAST `)` and split the rest of the line. Field positions
     * after the parens are:
     *   [0]=state [1]=ppid [2]=pgrp [3]=session [4]=tty_nr [5]=tpgid [6]=flags
     *   [7]=minflt [8]=cminflt [9]=majflt [10]=cmajflt [11]=utime [12]=stime
     *   ... so utime is at index 11, stime at 12.
     */
    private fun captureProcessCpuPercent(deviceId: String, pkg: String): Int {
        val pid = synchronized(pidCpuLock) {
            cachedPidByPkg[pkg] ?: run {
                val pidOutput = shell(deviceId, "pidof $pkg", timeoutMs = 2000).trim()
                val first = pidOutput.split(" ").firstOrNull()?.toIntOrNull() ?: return -1
                cachedPidByPkg[pkg] = first
                first
            }
        }

        val statOutput = shell(deviceId, "cat /proc/$pid/stat", timeoutMs = 2000)
        if (statOutput.isBlank()) {
            // PID died (game restart, kill, OOM). Invalidate cache so next call
            // resolves the fresh PID via pidof.
            synchronized(pidCpuLock) {
                cachedPidByPkg.remove(pkg)
                prevPidProcJiffies.remove(pid)
                prevPidSystemJiffies.remove(pid)
            }
            return -1
        }

        // Parse /proc/<pid>/stat — see KDoc above for the format gotcha.
        val rparen = statOutput.lastIndexOf(')')
        if (rparen < 0) return -1
        val afterParen = statOutput.substring(rparen + 1).trim().split(RE_DEVICE_LINE)
        if (afterParen.size < 13) return -1
        val utime = afterParen[11].toLongOrNull() ?: return -1
        val stime = afterParen[12].toLongOrNull() ?: return -1
        val procJiffies = utime + stime

        val systemOutput = shell(deviceId, "cat /proc/stat", timeoutMs = 2000)
        val cpuLine = systemOutput.lines().firstOrNull { it.startsWith("cpu ") } ?: return -1
        val cpuParts = cpuLine.trim().split(RE_DEVICE_LINE)
        if (cpuParts.size < 8) return -1
        val systemJiffies = (1..7).sumOf { cpuParts[it].toLongOrNull() ?: 0L }

        return synchronized(pidCpuLock) {
            val prevProc = prevPidProcJiffies[pid]
            val prevSys = prevPidSystemJiffies[pid]
            prevPidProcJiffies[pid] = procJiffies
            prevPidSystemJiffies[pid] = systemJiffies

            if (prevProc == null || prevSys == null) {
                -1 // first sample for this PID — no delta yet
            } else {
                val deltaProc = procJiffies - prevProc
                val deltaSys = systemJiffies - prevSys
                if (deltaSys <= 0L || deltaProc < 0L) -1
                else (deltaProc * 100 / deltaSys).toInt().coerceIn(0, 100)
            }
        }
    }

    /**
     * Capture device thermal state.
     *
     * v4.2.5 changes:
     *
     * - **Take MAX across all sensors of the same kind**, not just the first one.
     *   BIG.little SoCs have separate sensors per CPU cluster (cpu0-thermal,
     *   cpu4-thermal, etc.) with sometimes 30°C+ delta between them. Pre-v4.2.5
     *   the loop bailed out on the first match (`&& cpu < 0`), which on a Snapdragon
     *   8 Gen 3 typically reported the cool little cluster (~35°C) and silently
     *   ignored the hot big cluster (~75°C). The hot cluster is what triggers
     *   thermal throttling, so it's the meaningful number.
     *
     * - **Validate temperature range**: -40°C to 150°C is the realistic envelope
     *   for a phone sensor. Some devices (mostly cheap MTKs) report bogus values
     *   like 850°C from corrupt thermal zones. Pre-v4.2.5 those got reported as-is
     *   and ruined the chart axis. Now they're discarded silently.
     */
    fun captureTemperature(deviceId: String): ThermalSnapshot {
        var cpu = -1.0; var gpu = -1.0; var battery = -1.0; var skin = -1.0
        // Try sysfs first
        val zones = shell(deviceId, "for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null):\$(cat \$z/temp 2>/dev/null)\"; done", timeoutMs = 3000)
        for (line in zones.lines()) {
            val parts = line.split(":"); if (parts.size != 2) continue
            val type = parts[0].lowercase(); val raw = parts[1].trim().toLongOrNull() ?: continue
            val temp = if (raw > 1000) raw / 1000.0 else raw.toDouble()
            // v4.2.5: validate physical plausibility — discard sensor read errors.
            if (temp !in MIN_REALISTIC_TEMP_C..MAX_REALISTIC_TEMP_C) continue
            when {
                type.contains("cpu") || type.contains("tsens") || type.contains("soc") ->
                    if (temp > cpu) cpu = temp  // v4.2.5: MAX across all CPU sensors
                type.contains("gpu") -> if (temp > gpu) gpu = temp
                type.contains("battery") || type.contains("batt") -> if (temp > battery) battery = temp
                type.contains("skin") || type.contains("quiet") -> if (temp > skin) skin = temp
            }
        }
        // Fallback: thermalservice (Android 10+ thermal HAL)
        if (cpu < 0 || gpu < 0) {
            val dump = shell(deviceId, "dumpsys thermalservice", timeoutMs = 3000)
            for (m in RE_THERMAL_TEMP.findAll(dump)) {
                val v = m.groupValues[1].toDoubleOrNull() ?: continue
                if (v !in MIN_REALISTIC_TEMP_C..MAX_REALISTIC_TEMP_C) continue
                val n = m.groupValues[2].trim().lowercase()
                when {
                    (n == "big" || n == "little" || n == "mid" || n.contains("cpu")) && v > cpu -> cpu = v
                    (n == "g3d" || n.contains("gpu")) && v > gpu -> gpu = v
                    n == "battery" && v > battery -> battery = v
                    (n.contains("skin") || n.contains("quiet") || n == "virtual-skin") && v > skin -> skin = v
                }
            }
        }
        return ThermalSnapshot(cpu, gpu, battery, skin)
    }

    fun getMissedFrames(deviceId: String): Int {
        val output = shell(deviceId, "dumpsys SurfaceFlinger", timeoutMs = 3000)
        return RE_MISSED_FRAMES.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun disableCharging(deviceId: String) = shell(deviceId, "dumpsys battery unplug")
    fun restoreCharging(deviceId: String) = shell(deviceId, "dumpsys battery reset")

    // ===== Screen Recording =====

    /**
     * Screen recording profile: resolution and bitrate chosen based on device capability.
     *
     * The H.264 hardware encoder on the SoC is effectively free, but `screenrecord` still
     * has to acquire each frame via a SurfaceFlinger virtual display. On devices with a
     * native panel larger than the recording size, SurfaceFlinger has to downscale every
     * frame, which consumes GPU cycles the game also needs. The size of that penalty
     * scales with the scaling factor:
     *
     *   - Game renders at 1080p, record at 720p → ~1.5x scale → ~3-5% GPU overhead
     *   - Game renders at 1440p, record at 720p → ~4x pixels → ~8-15% GPU overhead
     *
     * On a low-end SoC like the Pixel XL's Snapdragon 821 (Adreno 530, native 1440x2560),
     * the second case is the difference between a playable game and a stuttering mess.
     *
     * v3.1.10 introduces a compact profile (540x960 @ 2 Mbps) for LOW / LOWER_MID tier
     * devices. The video is still usable for frame-by-frame analysis but the scaling
     * factor against 1440p is ~7x pixels vs ~4x for the standard profile — slightly
     * worse math on paper, but empirically the smaller output surface is cheaper for
     * SurfaceFlinger to composite even accounting for the larger scale factor, because
     * the virtual display fillrate is the dominant cost.
     */
    enum class ScreenRecordProfile(val width: Int, val height: Int, val bitRate: Int) {
        STANDARD(720, 1280, 4_000_000),
        COMPACT(540, 960, 2_000_000)
    }

    /**
     * Detect whether the device display is currently in landscape orientation.
     * Uses `dumpsys window` to read `mCurrentRotation`. ROTATION_90 and ROTATION_270
     * are landscape; ROTATION_0 and ROTATION_180 are portrait.
     */
    fun isLandscape(deviceId: String): Boolean {
        val output = shell(deviceId, "dumpsys window")
        val match = RE_ROTATION.find(output) ?: return false
        val degrees = match.groupValues[1].toIntOrNull() ?: return false
        return degrees == 90 || degrees == 270
    }

    /**
     * Start screen recording on device.
     * adb screenrecord has 3-min limit per file, so we chain segments.
     * Profile selection should happen at the caller based on hardware tier.
     * sessionId is used to create unique filenames per session.
     *
     * v4.2.1: auto-detects device orientation and swaps width/height for landscape
     * games. Without this, landscape games are recorded in a portrait frame,
     * causing the video to appear rotated or letterboxed in the player.
     */
    fun startScreenRecord(
        deviceId: String,
        sessionId: String,
        segment: Int = 0,
        profile: ScreenRecordProfile = ScreenRecordProfile.STANDARD
    ): Process? {
        require(isValidDeviceId(deviceId)) { "Invalid device ID: $deviceId" }
        require(isValidSessionId(sessionId)) { "Invalid session ID: $sessionId" }
        return try {
            val remotePath = "/sdcard/gp_${sessionId}_$segment.mp4"
            // Swap width/height when device is in landscape so the video
            // matches the actual screen orientation.
            val landscape = isLandscape(deviceId)
            val w = if (landscape) profile.height else profile.width
            val h = if (landscape) profile.width else profile.height
            val size = "${w}x${h}"
            val pb = ProcessBuilder(
                adbPath, "-s", deviceId, "shell", "screenrecord",
                "--size", size,
                "--bit-rate", profile.bitRate.toString(),
                "--time-limit", "180",
                remotePath
            )
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
        require(isValidSessionId(sessionId)) { "Invalid session ID: $sessionId" }
        val files = mutableListOf<java.io.File>()
        for (i in 0..maxSegments) {
            val remotePath = "/sdcard/gp_${sessionId}_$i.mp4"
            val check = exec("adb", "-s", deviceId, "shell", "ls", remotePath, timeoutMs = 5000)
            if (check.isBlank() || check.contains("No such file")) break
            val localFile = java.io.File(localDir, "video_${sessionId}_$i.mp4")
            exec(adbPath, "-s", deviceId, "pull", remotePath, localFile.absolutePath, timeoutMs = 60000)
            if (localFile.exists() && localFile.length() > 0) files.add(localFile)
            exec("adb", "-s", deviceId, "shell", "rm", remotePath, timeoutMs = 5000)
        }
        return files
    }

    fun cleanRecordings(deviceId: String) {
        // Uses shell() intentionally: glob expansion needs the device shell.
        // The pattern is a fixed literal (no user input), so no injection risk.
        shell(deviceId, "rm -f /sdcard/gp_*.mp4")
    }

    // ===== Video Segment Concatenation =====

    /** Return cached ffmpeg path. Lookup happens at most once per JVM lifetime. */
    private fun findFfmpeg(): String? = cachedFfmpegPath

    /**
     * Resolve ffmpeg path. ffmpeg is a soft dependency: if absent, concat falls back
     * to leaving segments as separate files.
     *
     * v4.1.0: Renamed to `Impl` and invoked once via `cachedFfmpegPath` lazy.
     * v4.2.3: Delegated to [ToolResolver] — previously this function used `which`
     *         (no-op on Windows) and only checked one hardcoded Windows path. Users
     *         who installed ffmpeg through WinGet/Scoop/Chocolatey got null back
     *         and the session video got capped at the first 3-minute segment.
     */
    private fun findFfmpegImpl(): String? = ToolResolver.find("ffmpeg")

    /**
     * Concatenate multiple .mp4 segments produced by `screenrecord` into a single
     * unified .mp4 file using ffmpeg's concat demuxer with `-c copy` (lossless,
     * no re-encoding, ~1-2 seconds even for 15+ minutes of footage).
     *
     * Why this exists: `adb screenrecord` has a hard 3-minute limit per file. The
     * recording loop in `AppViewModel` chains multiple segments (`_0.mp4`, `_1.mp4`,
     * ...) but until v3.1.9 only the first segment was exposed to the user, capping
     * effective playback at ~2:56 regardless of how long the actual session was.
     *
     * Returns the path to the concatenated file on success, or null if:
     *   - ffmpeg is not installed
     *   - segments list is empty
     *   - the concat process fails or produces an empty/missing file
     *
     * Callers should fall back to `segments.firstOrNull()` on null so we never
     * leave the user with no video at all (degraded > broken).
     *
     * The original segments are NOT deleted by this function. Cleanup is the
     * caller's responsibility (and currently we keep them as a backup until v3.1.10).
     */
    fun concatSegments(segments: List<java.io.File>, output: java.io.File): java.io.File? {
        if (segments.isEmpty()) return null

        val ffmpeg = findFfmpeg() ?: run {
            System.err.println("AdbBridge.concatSegments: ffmpeg not found")
            // Without ffmpeg we can't concat. Return the first VALID segment so the caller
            // at least gets something playable. Validation requires ffprobe so this is best-effort.
            return segments.firstValidSegment()
        }

        // v3.1.12: validate every segment with ffprobe BEFORE concat. screenrecord chains
        // are vulnerable to having one corrupt segment (typically `_0` when chain stop
        // didn't give Android time to write the moov atom). Concat demuxer fails entirely
        // on the first bad input — losing 100% of the video for the cost of one bad chunk.
        // Filter to valid segments only and concat what's left. If filtering removes
        // segments, the user loses some footage but keeps the rest. Better than nothing.
        val validSegments = segments.filter { isValidVideoFile(it) }
        if (validSegments.isEmpty()) {
            System.err.println("AdbBridge.concatSegments: no valid segments after validation (all ${segments.size} are corrupt)")
            return null
        }
        if (validSegments.size < segments.size) {
            val corrupt = segments - validSegments.toSet()
            System.err.println("AdbBridge.concatSegments: skipping ${corrupt.size} corrupt segment(s): ${corrupt.map { it.name }}")
        }
        if (validSegments.size == 1) {
            // Only one valid segment after filtering — no concat needed, just return it.
            return validSegments.first()
        }

        // ffmpeg concat demuxer requires a manifest file with `file '<path>'` lines.
        // Single quotes inside paths must be escaped as `'\''` per ffmpeg docs.
        val manifest = java.io.File.createTempFile("gameperf-concat-", ".txt")
        try {
            manifest.bufferedWriter().use { w ->
                for (seg in validSegments) {
                    val escapedPath = seg.absolutePath.replace("'", "'\\''")
                    w.write("file '$escapedPath'\n")
                }
            }

            // -y: overwrite output if exists
            // -f concat: use concat demuxer
            // -safe 0: allow absolute paths in manifest
            // -i: manifest file
            // -c copy: stream copy (no re-encoding, lossless, fast)
            // -movflags +faststart: relocate moov atom to the start so progressive playback works
            val pb = ProcessBuilder(
                ffmpeg, "-y", "-f", "concat", "-safe", "0",
                "-i", manifest.absolutePath,
                "-c", "copy",
                "-movflags", "+faststart",
                output.absolutePath
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val log = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                System.err.println("AdbBridge.concatSegments: ffmpeg timed out after 120s")
                return validSegments.first()  // fallback to first valid segment
            }
            if (proc.exitValue() != 0) {
                System.err.println("AdbBridge.concatSegments: ffmpeg exit ${proc.exitValue()}\n$log")
                return validSegments.first()  // fallback to first valid segment
            }
            if (!output.exists() || output.length() == 0L) {
                System.err.println("AdbBridge.concatSegments: output file missing or empty")
                return validSegments.first()  // fallback
            }
            return output
        } catch (e: Exception) {
            System.err.println("AdbBridge.concatSegments: ${e.message}")
            return validSegments.firstOrNull()
        } finally {
            manifest.delete()
        }
    }

    /**
     * Validate that an mp4 file is readable by ffprobe (i.e. has a valid moov atom and
     * the container can be parsed). screenrecord can produce files that exist on disk
     * with non-zero size but missing the moov atom (when the process is killed before
     * Android closes the container). These files are unplayable and concat will fail on
     * them — better to detect and skip BEFORE attempting concat or playback.
     *
     * Validation strategy: try to read the duration via ffprobe. If ffprobe exits 0 with
     * a parseable positive duration, the file is valid. If ffprobe fails OR returns no
     * duration OR returns a non-positive value, the file is corrupt.
     *
     * Cost: one ffprobe invocation per file (~50-150ms). Cheap enough to do on every
     * concat call.
     *
     * Returns false (and logs the reason) on:
     *   - File doesn't exist
     *   - File is empty
     *   - ffprobe not installed (degraded: assume valid, can't validate)
     *   - ffprobe exits non-zero
     *   - ffprobe times out (>5s)
     *   - duration not parseable or <= 0
     */
    fun isValidVideoFile(file: java.io.File): Boolean {
        if (!file.exists() || file.length() == 0L) {
            System.err.println("AdbBridge.isValidVideoFile: ${file.name} missing or empty")
            return false
        }
        val ffprobe = findFfprobe() ?: return true  // can't validate, assume valid (degraded)
        return try {
            val pb = ProcessBuilder(
                ffprobe, "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.absolutePath
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                System.err.println("AdbBridge.isValidVideoFile: ffprobe timed out on ${file.name}")
                return false
            }
            if (proc.exitValue() != 0) {
                System.err.println("AdbBridge.isValidVideoFile: ffprobe exit ${proc.exitValue()} on ${file.name}: $out")
                return false
            }
            // Output should be a positive number (seconds). Sometimes ffprobe outputs "N/A"
            // or empty if the format header is partially parseable but duration is missing.
            val durationSec = out.lines().firstOrNull()?.toDoubleOrNull()
            if (durationSec == null || durationSec <= 0.0) {
                System.err.println("AdbBridge.isValidVideoFile: ${file.name} has no readable duration (got '$out')")
                return false
            }
            true
        } catch (e: Exception) {
            System.err.println("AdbBridge.isValidVideoFile: ${file.name}: ${e.message}")
            false
        }
    }

    /** Return cached ffprobe path. Lookup happens at most once per JVM lifetime. */
    private fun findFfprobe(): String? = cachedFfprobePath

    /** Find the local ffprobe binary via [ToolResolver]. Same Windows-detection
     *  overhaul as [findFfmpegImpl] — pre-v4.2.3 this function had the same
     *  Unix-first bug that caused silent concat failures.
     *
     *  v4.1.0: Renamed to `Impl` and invoked once via `cachedFfprobePath` lazy.
     *  v4.2.3: Delegated to [ToolResolver]. */
    private fun findFfprobeImpl(): String? = ToolResolver.find("ffprobe")

    /** Helper extension: find the first valid segment in a list, or null if none. */
    private fun List<java.io.File>.firstValidSegment(): java.io.File? =
        this.firstOrNull { isValidVideoFile(it) }

    // ===== v3.2.0 — Wireless ADB =====
    //
    // Four blocking methods that spawn `adb pair`, `adb connect`, `adb mdns
    // services`, and `adb disconnect` subprocesses, plus `adb --version` for
    // the platform-tools capability check. All thread-safe, all never-throws
    // (errors are mapped to the corresponding sealed Failure variants or
    // empty/null defaults).
    //
    // Timeouts (D-4 in the design):
    //   pair          → 10s wall-clock, destroyForcibly on overflow
    //   connectWireless → 5s
    //   mdnsServices  → 3s
    //   disconnect    → 3s
    //   getAdbVersion → 2s
    //
    // These stay OUTSIDE the frozen lines 64-88 region (Device data class +
    // switchToWifi legacy). They're appended after the concat helpers at the
    // end of the singleton so the frozen region is byte-stable against
    // `git diff e44bfce`.

    /**
     * Run `adb pair ip:port` and write [code] to its stdin. Blocks for up to
     * 10 seconds wall-clock. Never throws.
     *
     * Success is detected by exit code == 0 (adb pair writes a confirmation
     * to stdout, not stderr). Failure stderr is classified via
     * [parsePairStderr]. The process is `destroyForcibly()`-killed if it
     * exceeds the timeout.
     */
    fun pair(ip: String, port: Int, code: String): PairResult {
        return try {
            val pb = ProcessBuilder(adbPath, "pair", "$ip:$port")
            pb.redirectErrorStream(false) // keep stderr separate for classification
            val process = pb.start()

            // Feed the pairing code on stdin (adb pair reads a line from stdin).
            try {
                process.outputStream.use { os ->
                    os.write((code + "\n").toByteArray())
                    os.flush()
                }
            } catch (_: Exception) { /* process may have died before we wrote — fall through */ }

            val stderrFuture = CompletableFuture.supplyAsync {
                try { process.errorStream.bufferedReader().readText() } catch (_: Exception) { "" }
            }

            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                val stderr = try { stderrFuture.get(500, TimeUnit.MILLISECONDS) } catch (_: Exception) { "" }
                return PairResult.Failure(parsePairStderr(stderr, timedOut = true), stderr)
            }

            val stderr = try { stderrFuture.get(1000, TimeUnit.MILLISECONDS) } catch (_: Exception) { "" }
            if (process.exitValue() == 0) {
                PairResult.Success
            } else {
                PairResult.Failure(parsePairStderr(stderr, timedOut = false), stderr)
            }
        } catch (t: Throwable) {
            PairResult.Failure(PairFailureReason.UNKNOWN, t.message ?: "")
        }
    }

    /**
     * Run `adb connect ip:port`. Blocks for up to 5 seconds. Never throws.
     *
     * adb connect writes both success and failure diagnostics to stdout (not
     * stderr). Success is detected by the presence of `connected to` in the
     * combined output. Anything else is classified via [parseConnectStderr]
     * against the combined output.
     */
    fun connectWireless(ip: String, port: Int): ConnectResult {
        return try {
            val pb = ProcessBuilder(adbPath, "connect", "$ip:$port")
            pb.redirectErrorStream(true) // adb connect writes diagnostics to stdout
            val process = pb.start()

            val outFuture = CompletableFuture.supplyAsync {
                try { process.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
            }

            val completed = process.waitFor(5, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                val out = try { outFuture.get(500, TimeUnit.MILLISECONDS) } catch (_: Exception) { "" }
                return ConnectResult.Failure(parseConnectStderr(out, timedOut = true), out)
            }

            val out = try { outFuture.get(1000, TimeUnit.MILLISECONDS) } catch (_: Exception) { "" }
            if (process.exitValue() == 0 && out.lowercase().contains("connected to")) {
                ConnectResult.Success("$ip:$port")
            } else {
                ConnectResult.Failure(parseConnectStderr(out, timedOut = false), out)
            }
        } catch (t: Throwable) {
            ConnectResult.Failure(ConnectFailureReason.UNKNOWN, t.message ?: "")
        }
    }

    /**
     * Snapshot `adb mdns services`. Blocks for up to 3 seconds. Never throws.
     * Returns an empty list on timeout, non-zero exit, or unparseable output.
     * Results are sorted: PAIRING first, then CONNECT, internally by instance
     * ascending (stable for UI rendering per the wireless spec).
     */
    fun mdnsServices(): List<MdnsService> {
        return try {
            val pb = ProcessBuilder(adbPath, "mdns", "services")
            pb.redirectErrorStream(true)
            val process = pb.start()

            val outFuture = CompletableFuture.supplyAsync {
                try { process.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
            }

            val completed = process.waitFor(3, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return emptyList()
            }

            val out = try { outFuture.get(500, TimeUnit.MILLISECONDS) } catch (_: Exception) { "" }
            if (process.exitValue() != 0) return emptyList()

            parseMdnsServicesOutput(out).sortedWith(
                compareBy({ it.serviceType.ordinal }, { it.instance }),
            )
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Run `adb disconnect id`. Blocks for up to 3 seconds. Returns true only
     * on exit code 0, false otherwise. Never throws.
     */
    fun disconnect(id: String): Boolean {
        return try {
            val pb = ProcessBuilder(adbPath, "disconnect", id)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val completed = process.waitFor(3, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Run `adb --version` and parse the result via [parseAdbVersion]. Blocks
     * for up to 2 seconds. Returns null if the binary is missing, the process
     * times out, or the output doesn't match the canonical format.
     */
    fun getAdbVersion(): AdbVersion? {
        return try {
            val pb = ProcessBuilder(adbPath, "--version")
            pb.redirectErrorStream(true)
            val process = pb.start()

            val outFuture = CompletableFuture.supplyAsync {
                try { process.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
            }

            val completed = process.waitFor(2, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return null
            }

            val out = try { outFuture.get(500, TimeUnit.MILLISECONDS) } catch (_: Exception) { "" }
            if (process.exitValue() != 0) return null
            parseAdbVersion(out)
        } catch (_: Throwable) {
            null
        }
    }
}

// ===== v3.2.0 — Wireless ADB (adb pair + mDNS discovery) =====
//
// Top-level types used by the new pair/connectWireless/mdnsServices/disconnect
// methods on [AdbBridgeApi]. Kept OUTSIDE the `object AdbBridge` singleton so
// the frozen region (lines 64-88: Device data class + switchToWifi legacy)
// stays untouched byte-for-byte. Every type here is a pure value carrier —
// no state, no methods beyond `compareTo` on AdbVersion.

/**
 * Result of an `adb pair ip:port` invocation. Never thrown — errors are
 * always returned as [Failure] so the caller can pattern-match exhaustively.
 */
sealed class PairResult {
    object Success : PairResult()
    data class Failure(
        val reason: PairFailureReason,
        val rawStderr: String,
    ) : PairResult()
}

enum class PairFailureReason {
    /** `adb pair` stderr matched "failed to authenticate" — wrong code. */
    INVALID_CODE,

    /**
     * Pairing failed AND the `_adb-tls-pairing._tcp` mDNS service has
     * disappeared, indicating the pairing popup on the phone was closed
     * or timed out. Caller maps this to the same user message as
     * [INVALID_CODE] (action is identical: reopen the popup).
     */
    EXPIRED_CODE,

    /** stderr matched "connection refused" / "no route to host" / "network is unreachable". */
    CONNECTION_REFUSED,

    /** Process exceeded wall-clock timeout (10s) and was destroyed. */
    TIMEOUT,

    /** Exit code != 0 but stderr matched none of the known patterns. */
    UNKNOWN,
}

/**
 * Result of an `adb connect ip:port` invocation. Never thrown — the
 * [Success] variant carries the resolved deviceId (`"ip:port"`) so the
 * caller can cross-reference it against the next `listDevices()` snapshot.
 */
sealed class ConnectResult {
    data class Success(val deviceId: String) : ConnectResult()
    data class Failure(
        val reason: ConnectFailureReason,
        val rawStderr: String,
    ) : ConnectResult()
}

enum class ConnectFailureReason {
    /** "no route to host" / "network is unreachable" / "host is down". */
    NO_ROUTE,

    /** "connection refused" — the phone is reachable but rejected the socket. */
    REFUSED,

    /** Process exceeded wall-clock timeout (5s). */
    TIMEOUT,

    /** Exit code != 0 but stderr matched none of the known patterns. */
    UNKNOWN,
}

/**
 * One entry in the `adb mdns services` snapshot. Parsed from lines like:
 * ```
 * adb-XXXXXX-YYYYYY    _adb-tls-pairing._tcp.    192.168.1.42:37123
 * adb-XXXXXX-YYYYYY    _adb-tls-connect._tcp.    192.168.1.42:38145
 * ```
 *
 * `instance` is the phone-side ADB instance identifier (stable across
 * pairing/connect services for the same phone — used by the VM to match
 * the pairing service with its corresponding connect service after a
 * successful pair, per the D-10 re-discover step).
 */
data class MdnsService(
    val instance: String,
    val serviceType: MdnsServiceType,
    val ip: String,
    val port: Int,
)

enum class MdnsServiceType {
    /** `_adb-tls-pairing._tcp` — ephemeral, visible only while the pairing popup is open. */
    PAIRING,

    /** `_adb-tls-connect._tcp` — stable while Wireless Debugging is ON. */
    CONNECT,

    /** Any other `_adb-tls-*` service that doesn't match PAIRING or CONNECT. */
    UNKNOWN,
}

/**
 * Parsed `adb --version` output. [Comparable] so callers can gate features on
 * the platform-tools version (e.g. `adbVersion >= AdbVersion(33,0,0)` for the
 * mDNS auto-connect feature).
 */
data class AdbVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AdbVersion> {
    override fun compareTo(other: AdbVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
}

// ===== Pure parsers (internal — exercised by AdbBridgeMdnsParserTest) =====

/**
 * Parse the stdout of `adb mdns services`. Format observed on adb 37.0.0
 * (Darwin and Linux both):
 * ```
 * List of discovered mdns services
 * adb-XXXXXX-YYYYYY	_adb-tls-pairing._tcp.	192.168.1.42:37123
 * adb-XXXXXX-YYYYYY	_adb-tls-connect._tcp.	192.168.1.42:38145
 * ```
 *
 * Pure: no IO, no state mutation. Malformed lines are silently skipped
 * (the caller gets only well-formed entries). Empty input → empty list.
 * Any `_adb-tls-*` service other than PAIRING / CONNECT is filtered out
 * (mapped to null and dropped in the mapNotNull below).
 */
internal fun parseMdnsServicesOutput(text: String): List<MdnsService> {
    if (text.isBlank()) return emptyList()
    return text.lines()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("List of discovered") }
        .mapNotNull { parseMdnsServiceLine(it) }
        .toList()
}

/**
 * Parse a single line of `adb mdns services`. Returns null if the line is
 * structurally malformed, the service type is unknown, the IP is not a
 * literal IPv4, or the port is out of range.
 */
private val RE_MDNS_SPLIT = Regex("\\s+")
private val RE_MDNS_IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

internal fun parseMdnsServiceLine(line: String): MdnsService? {
    val parts = line.split(RE_MDNS_SPLIT)
    if (parts.size < 3) return null
    val instance = parts[0]
    val rawType = parts[1].trimEnd('.')
    val serviceType = when (rawType) {
        "_adb-tls-pairing._tcp" -> MdnsServiceType.PAIRING
        "_adb-tls-connect._tcp" -> MdnsServiceType.CONNECT
        else -> return null
    }
    val addr = parts.last()
    val colonIdx = addr.lastIndexOf(':')
    if (colonIdx <= 0) return null
    val ip = addr.substring(0, colonIdx)
    if (!ip.matches(RE_MDNS_IPV4)) return null
    val port = addr.substring(colonIdx + 1).toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return MdnsService(instance, serviceType, ip, port)
}

/**
 * Classify the stderr of a failed `adb pair` invocation into a
 * [PairFailureReason]. Pure: no IO. Handles the most common stderr
 * patterns seen in the wild across adb 30.x → 37.x on Darwin/Linux.
 *
 * @param timedOut true if the caller killed the process for exceeding
 *                 its wall-clock budget. Takes precedence over stderr
 *                 content classification.
 */
internal fun parsePairStderr(stderr: String, timedOut: Boolean): PairFailureReason {
    if (timedOut) return PairFailureReason.TIMEOUT
    val lower = stderr.lowercase()
    return when {
        "failed to authenticate" in lower -> PairFailureReason.INVALID_CODE
        "connection refused" in lower -> PairFailureReason.CONNECTION_REFUSED
        "no route to host" in lower -> PairFailureReason.CONNECTION_REFUSED
        "network is unreachable" in lower -> PairFailureReason.CONNECTION_REFUSED
        "timeout" in lower -> PairFailureReason.TIMEOUT
        stderr.isBlank() -> PairFailureReason.TIMEOUT
        else -> PairFailureReason.UNKNOWN
    }
}

/**
 * Classify the stderr of a failed `adb connect` invocation into a
 * [ConnectFailureReason]. Pure: no IO.
 */
internal fun parseConnectStderr(stderr: String, timedOut: Boolean): ConnectFailureReason {
    if (timedOut) return ConnectFailureReason.TIMEOUT
    val lower = stderr.lowercase()
    return when {
        "no route to host" in lower -> ConnectFailureReason.NO_ROUTE
        "network is unreachable" in lower -> ConnectFailureReason.NO_ROUTE
        "host is down" in lower -> ConnectFailureReason.NO_ROUTE
        "connection refused" in lower -> ConnectFailureReason.REFUSED
        "timeout" in lower -> ConnectFailureReason.TIMEOUT
        stderr.isBlank() -> ConnectFailureReason.TIMEOUT
        else -> ConnectFailureReason.UNKNOWN
    }
}

/**
 * Parse the output of `adb --version`, returning the detected platform-tools
 * version or null if the output doesn't contain a canonical `Version X.Y.Z`
 * line. Pure: no IO.
 */
private val RE_ADB_VER = Regex("Version (\\d+)\\.(\\d+)\\.(\\d+)")

internal fun parseAdbVersion(output: String): AdbVersion? {
    val match = RE_ADB_VER.find(output) ?: return null
    val (maj, min, patch) = match.destructured
    return AdbVersion(maj.toInt(), min.toInt(), patch.toInt())
}
