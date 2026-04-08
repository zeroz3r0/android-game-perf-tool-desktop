package com.gameperf.desktop.testing

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.core.AdbBridgeApi
import com.gameperf.desktop.core.AdbVersion
import com.gameperf.desktop.core.ConnectFailureReason
import com.gameperf.desktop.core.ConnectResult
import com.gameperf.desktop.core.MdnsService
import com.gameperf.desktop.core.PairFailureReason
import com.gameperf.desktop.core.PairResult
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
open class FakeAdbBridge(
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
    //
    // NOTE: `open` is required on each override that tests may want to
    // customize via `object : FakeAdbBridge() { override ... }`. In Kotlin,
    // `override fun` is implicitly final unless you add `open`. We only
    // unfreeze the seams that are actually used (listDevices for WP-7/WP-8,
    // switchToWifi for WP-10).

    override fun isAvailable(): Boolean = true

    open override fun listDevices(): List<AdbBridge.Device> = emptyList()

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
    open override fun switchToWifi(usbDeviceId: String, port: Int): String? = null

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

    // ===== v3.2.0 — Wireless ADB scriptable surface =====
    //
    // Design (per sdd design §D-6): queues FIFO for pair / connect / mdns so
    // tests can drive multi-step scenarios (e.g. "first mdns snapshot is
    // empty, second has pairing, third has pairing + connect"). Each pop
    // consumes one entry; when the queue is empty the override falls back to
    // a defensive default (empty list or UNKNOWN failure) so tests see a
    // predictable "nothing was scripted" signal instead of an NPE.
    //
    // Recorded calls mirror the shape of [startCalls] for the existing
    // screenrecord fake — tests assert counts and argument progression.
    //
    // NONE of the pre-existing fields or methods above were touched. This
    // section is purely additive so v3.1.14 tests stay byte-stable.

    /** Results popped in order when the VM calls `pair(ip, port, code)`. */
    val scriptedPair: MutableList<PairResult> = mutableListOf()

    /** Results popped in order when the VM calls `connectWireless(ip, port)`. */
    val scriptedConnect: MutableList<ConnectResult> = mutableListOf()

    /**
     * Snapshots popped in order when the VM calls `mdnsServices()`. The LAST
     * entry sticks: once the queue is down to one snapshot, subsequent calls
     * keep returning it (simulates a steady-state mDNS cache). Empty queue
     * → empty list.
     */
    val scriptedMdnsSnapshots: MutableList<List<MdnsService>> = mutableListOf()

    /**
     * Override the mDNS availability flag. When false, VM logic should skip
     * discovery polls entirely and jump to the manual input form (per
     * scenario WP-3). Tests flip this BEFORE calling `openWifiPanel()`.
     *
     * NOTE: this doesn't gate [mdnsServices] itself — the VM checks this
     * sensor as a separate signal. The fake exposes it as a public var so
     * tests can set it and the VM can read it by reflection / cast.
     */
    var mdnsAvailableOverride: Boolean = true

    /** Default adb version for the platform-tools capability check in VM init. */
    var scriptedAdbVersion: AdbVersion? = AdbVersion(34, 0, 0)

    /** Recorded: one entry per `pair` call, in order. */
    val pairCalls: MutableList<Triple<String, Int, String>> = mutableListOf()

    /** Recorded: one entry per `connectWireless` call, in order. */
    val connectCalls: MutableList<Pair<String, Int>> = mutableListOf()

    /**
     * Recorded: count of `mdnsServices()` invocations. Critical for WP-8
     * regression (must stay at 0 when no WiFi panel is ever opened).
     */
    @Volatile
    var mdnsServiceCalls: Int = 0

    /** Recorded: one entry per `disconnect` call, in order. */
    val disconnectCalls: MutableList<String> = mutableListOf()

    override fun pair(ip: String, port: Int, code: String): PairResult {
        pairCalls += Triple(ip, port, code)
        return if (scriptedPair.isNotEmpty()) {
            scriptedPair.removeAt(0)
        } else {
            PairResult.Failure(PairFailureReason.UNKNOWN, "")
        }
    }

    override fun connectWireless(ip: String, port: Int): ConnectResult {
        connectCalls += ip to port
        return if (scriptedConnect.isNotEmpty()) {
            scriptedConnect.removeAt(0)
        } else {
            ConnectResult.Failure(ConnectFailureReason.UNKNOWN, "")
        }
    }

    override fun mdnsServices(): List<MdnsService> {
        mdnsServiceCalls++
        return when {
            scriptedMdnsSnapshots.isEmpty() -> emptyList()
            scriptedMdnsSnapshots.size == 1 -> scriptedMdnsSnapshots.first()
            else -> scriptedMdnsSnapshots.removeAt(0)
        }
    }

    override fun disconnect(id: String): Boolean {
        disconnectCalls += id
        return true
    }

    override fun getAdbVersion(): AdbVersion? = scriptedAdbVersion
}
