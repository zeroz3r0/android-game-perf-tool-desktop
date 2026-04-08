package com.gameperf.desktop.testing

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.core.AdbBridgeApi
import java.io.File

/**
 * v3.1.14 — In-memory fake of [AdbBridgeApi] for unit tests.
 *
 * Design goals:
 *   - Deterministic: no adb, no ffmpeg, no sleeps beyond what the caller drives.
 *   - Scriptable `startScreenRecord`: the constructor takes a list of
 *     `ScriptedStart` entries that are consumed in order. Each entry can
 *     either be `Success(pb)` (a ready-to-return [ProcessBuilder] spec that
 *     will be `.start()`ed lazily, giving the test a real [Process] handle)
 *     or `Null` (emulating `ProcessBuilder.start()` throwing inside
 *     `AdbBridge.startScreenRecord`, which returns null).
 *   - Recorded calls: every `startScreenRecord` invocation appends to
 *     [startCalls] so tests can assert on count and on the profile sequence
 *     (e.g. "first call was COMPACT, retry was STANDARD").
 *   - Sane defaults: every other method returns a benign value so the
 *     `AppViewModel` code paths that exercise `startSegmentWithRetry` can run
 *     without NPE'ing on unrelated collaborators.
 *
 * Why real Processes instead of mocking `isAlive()`/`exitValue()`: because
 * [com.gameperf.desktop.viewmodel.AppViewModel.validateScreenRecordProcess]
 * already reads from the process' inputStream to capture stderr, and mocking
 * that surface is more code than just spawning `sh -c 'exit 1'` the way the
 * existing v3.1.13 tests do. This way the fake is ~30 lines instead of 200,
 * and the tests exercise the same `validateScreenRecordProcess` code path
 * that production uses.
 */
class FakeAdbBridge(
    private val scriptedStarts: MutableList<ScriptedStart> = mutableListOf(),
) : AdbBridgeApi {

    /** One entry per expected `startScreenRecord` call. Consumed FIFO. */
    sealed class ScriptedStart {
        /**
         * Spawn a real process using the given command; the test gets the
         * resulting [Process] back. Use for both "dies fast" (e.g.
         * `sh -c 'echo rejected >&2; exit 1'`) and "stays alive" (e.g.
         * `sh -c 'sleep 2'`) scenarios.
         */
        data class Spawn(val command: List<String>) : ScriptedStart()
        /** Emulate `ProcessBuilder.start()` throwing → `startScreenRecord` returns null. */
        object Null : ScriptedStart()
    }

    /** One entry per observed call, so tests can assert order/profile progression. */
    data class StartCall(
        val deviceId: String,
        val sessionId: String,
        val segment: Int,
        val profile: AdbBridge.ScreenRecordProfile,
    )

    val startCalls: MutableList<StartCall> = mutableListOf()

    /**
     * Convenience: push a scripted "fast-fail" process that exits with code 1
     * and writes the given line to stderr (via `redirectErrorStream(true)` in
     * production, so we echo to both here).
     */
    fun queueFastFail(stderr: String = "encoder rejected"): FakeAdbBridge {
        // Use single-quote escape so shell parsing is robust for the stderr text.
        val safe = stderr.replace("'", "'\\''")
        scriptedStarts += ScriptedStart.Spawn(
            listOf("sh", "-c", "echo '$safe' >&2; echo '$safe'; exit 1")
        )
        return this
    }

    /** Convenience: push a process that sleeps (stays alive through warm-up). */
    fun queueAlive(seconds: Int = 2): FakeAdbBridge {
        scriptedStarts += ScriptedStart.Spawn(listOf("sh", "-c", "sleep $seconds"))
        return this
    }

    /** Convenience: push a null entry — emulates `ProcessBuilder.start()` failing. */
    fun queueNull(): FakeAdbBridge {
        scriptedStarts += ScriptedStart.Null
        return this
    }

    // ===== AdbBridgeApi =====

    override fun isAvailable(): Boolean = true

    override fun listDevices(): List<AdbBridge.Device> = emptyList()

    override fun getDeviceInfo(deviceId: String): AdbBridge.DeviceInfo =
        AdbBridge.DeviceInfo(
            model = "Fake",
            manufacturer = "Fake",
            cpu = "Fake",
            gpu = "Fake",
            ram = "0 GB",
            cores = 1,
            sdk = 29,
            resolution = "0x0",
        )

    override fun detectGame(deviceId: String): String? = null
    override fun switchToWifi(usbDeviceId: String, port: Int): String? = null

    override fun getBatteryLevel(deviceId: String): Int = 100
    override fun getMissedFrames(deviceId: String): Int = 0
    override fun disableCharging(deviceId: String): String = ""
    override fun restoreCharging(deviceId: String): String = ""

    override fun resetSessionState() { /* no-op */ }

    override fun captureFrames(deviceId: String, pkg: String): AdbBridge.FrameSnapshot? = null
    override fun captureCpuPercent(deviceId: String): Int = 0
    override fun captureMemory(deviceId: String, pkg: String): AdbBridge.MemSnapshot? = null
    override fun captureTemperature(deviceId: String): AdbBridge.ThermalSnapshot =
        AdbBridge.ThermalSnapshot(-1.0, -1.0, -1.0, -1.0)

    override fun startScreenRecord(
        deviceId: String,
        sessionId: String,
        segment: Int,
        profile: AdbBridge.ScreenRecordProfile,
    ): Process? {
        startCalls += StartCall(deviceId, sessionId, segment, profile)
        if (scriptedStarts.isEmpty()) {
            // Unexpected call beyond what the test scripted — surface loudly so
            // the test fails fast instead of silently returning null (which
            // would be indistinguishable from a legitimate null scenario).
            error(
                "FakeAdbBridge.startScreenRecord called ${startCalls.size} times but " +
                    "only ${startCalls.size - 1} scripted entries were queued. " +
                    "Extra call: deviceId=$deviceId segment=$segment profile=$profile"
            )
        }
        return when (val next = scriptedStarts.removeAt(0)) {
            is ScriptedStart.Null -> null
            is ScriptedStart.Spawn -> try {
                ProcessBuilder(next.command).redirectErrorStream(true).start()
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun stopScreenRecord(process: Process?) {
        try { process?.destroyForcibly() } catch (_: Exception) { /* no-op */ }
    }

    override fun pullRecordings(
        deviceId: String,
        sessionId: String,
        localDir: File,
        maxSegments: Int,
    ): List<File> = emptyList()

    override fun cleanRecordings(deviceId: String) { /* no-op */ }

    override fun concatSegments(segments: List<File>, output: File): File? = null
    override fun isValidVideoFile(file: File): Boolean = false
}
