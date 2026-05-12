package com.gameperf.desktop.testing

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.core.AdbBridgeApi
import com.gameperf.desktop.core.AdbVersion
import com.gameperf.desktop.core.ConnectFailureReason
import com.gameperf.desktop.core.ConnectResult
import com.gameperf.desktop.core.FPowerParser
import com.gameperf.desktop.core.FPowerVendorCatalog
import com.gameperf.desktop.core.MdnsService
import com.gameperf.desktop.core.PairFailureReason
import com.gameperf.desktop.core.PairResult
import com.gameperf.desktop.core.model.Device
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.model.FPowerSnapshot
import com.gameperf.desktop.core.model.FrameSnapshot
import com.gameperf.desktop.core.model.MemSnapshot
import com.gameperf.desktop.core.model.ThermalSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v3.1.14 — In-memory fake of [AdbBridgeApi] for unit tests.
 * v4.1.0  — Return types migrated to core.model.* (matches AdbBridgeApi v4.1.0).
 */
open class FakeAdbBridge(
    private val scriptedStarts: MutableList<ScriptedStart> = mutableListOf(),
) : AdbBridgeApi {

    sealed class ScriptedStart {
        data class Spawn(val command: List<String>) : ScriptedStart()
        object Null : ScriptedStart()
    }

    data class StartCall(
        val deviceId: String,
        val sessionId: String,
        val segment: Int,
        val profile: AdbBridge.ScreenRecordProfile,
    )

    val startCalls: MutableList<StartCall> = mutableListOf()

    fun queueFastFail(stderr: String = "encoder rejected"): FakeAdbBridge {
        scriptedStarts += ScriptedStart.Spawn(ProcessTestUtils.fastFailCommand(stderr))
        return this
    }

    fun queueAlive(seconds: Int = 2): FakeAdbBridge {
        scriptedStarts += ScriptedStart.Spawn(ProcessTestUtils.sleepCommand(seconds))
        return this
    }

    fun queueNull(): FakeAdbBridge {
        scriptedStarts += ScriptedStart.Null
        return this
    }

    // ===== AdbBridgeApi (v4.1.0: returns core.model.* types) =====

    override fun isAvailable(): Boolean = true

    open override fun listDevices(): List<Device> = emptyList()

    override fun getDeviceInfo(deviceId: String): DeviceInfo =
        DeviceInfo(
            model = "Fake", manufacturer = "Fake", cpu = "Fake",
            gpu = "Fake", ram = "0 GB", cores = 1,
            osVersion = "29", resolution = "0x0",
            platform = DevicePlatform.ANDROID,
        )

    override fun detectGame(deviceId: String): String? = null
    open override fun switchToWifi(usbDeviceId: String, port: Int): String? = null

    override fun getBatteryLevel(deviceId: String): Int = 100
    override fun getMissedFrames(deviceId: String): Int = 0
    override fun disableCharging(deviceId: String): String = ""
    override fun restoreCharging(deviceId: String): String = ""

    override fun resetSessionState() {
        // v4.5.0 — mirror production: clear the per-device FPower probe cache
        // so a fresh probe re-walks ORDERED_PATHS. Without this the cache
        // contract (FPW-006, design ADR-7) would not be testable via the fake.
        fpowerStateMap.clear()
    }

    /** Records every [invalidateLayerCache] call so tests can assert the
     *  capture-loop forced re-discovery K times. v4.3.5. */
    val invalidateLayerCacheCalls: MutableList<Pair<String, String>> = mutableListOf()
    override fun invalidateLayerCache(deviceId: String, pkg: String) {
        invalidateLayerCacheCalls += deviceId to pkg
    }

    override fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? = null
    open override fun captureCpuPercent(deviceId: String): Int = 0
    open override fun captureCpuPercent(deviceId: String, pkg: String): Int = captureCpuPercent(deviceId)

    /**
     * v4.5.0 — Default impl delegates to the two existing `captureCpuPercent`
     * overrides so subclasses that script the underlying readouts via
     * `override fun captureCpuPercent(...)` get the right values composed
     * into the dual snapshot for free. Tests for `cpu-total-vs-app-usage`
     * follow this pattern (see `AdbBridgeCpuDualTest.ScriptedCpuBridge`).
     */
    open override fun captureCpuDual(deviceId: String, pkg: String): com.gameperf.desktop.core.CpuDualSnapshot =
        com.gameperf.desktop.core.CpuDualSnapshot(
            totalDeviceCpuPct = captureCpuPercent(deviceId),
            appCpuPct = captureCpuPercent(deviceId, pkg),
        )

    override fun captureMemory(deviceId: String, pkg: String): MemSnapshot? = null

    /**
     * v4.4.1 — Optional override for [captureTemperature]. When non-null, the
     * fake returns this exact snapshot (additive — preserves the legacy
     * NaN-quad default for tests that don't care about thermal). Use the
     * [setThermal] builder to install a fixture.
     */
    @Volatile
    private var scriptedThermal: ThermalSnapshot? = null

    /**
     * v4.4.1 builder — install a [ThermalSnapshot] fixture that
     * [captureTemperature] will return. Defaults to `thermalAvailable=true`
     * for the legacy NaN-quad case (preserves v4.3.x test semantics).
     */
    fun setThermal(snapshot: ThermalSnapshot): FakeAdbBridge {
        scriptedThermal = snapshot
        return this
    }

    override fun captureTemperature(deviceId: String): ThermalSnapshot =
        scriptedThermal ?: ThermalSnapshot(Double.NaN, Double.NaN, Double.NaN, Double.NaN)

    /**
     * v4.5.0 — optional override for [captureFPower]. When non-null, the fake
     * returns this exact snapshot (identity) — short-circuits BEFORE issuing
     * any [shell] reads. When null, [captureFPower] simulates a cold catalog
     * probe against [shellResponses] so end-to-end probe scenarios work too.
     * Install via [setFPower].
     */
    @Volatile
    private var scriptedFPower: FPowerSnapshot? = null

    /**
     * v4.5.0 builder — install an [FPowerSnapshot] fixture that
     * [captureFPower] returns identity. Use this for lazy / unit-scope
     * scenarios. Tests that want to exercise the catalog-walk + cache logic
     * end-to-end should leave [scriptedFPower] null and populate
     * [shellResponses] with the vendor sysfs path keys instead.
     */
    fun setFPower(snapshot: FPowerSnapshot): FakeAdbBridge {
        scriptedFPower = snapshot
        return this
    }

    /**
     * v4.5.0 — per-device probe-cache mirror of [AdbBridge.fpowerStateMap].
     * Cleared by [resetSessionState]. Allows tests to exercise the FPW-006
     * cache contract end-to-end without spinning up a real adb subprocess.
     */
    private data class FPowerDeviceState(
        val winningTuple: FPowerVendorCatalog.PathTuple?,
        val firstProbeFailed: Boolean,
    )
    private val fpowerStateMap: MutableMap<String, FPowerDeviceState> = mutableMapOf()

    override fun captureFPower(deviceId: String, currentFps: Double): FPowerSnapshot {
        scriptedFPower?.let { return it }

        // Cache hit / cached failure — production parity (FPW-006).
        val cached = fpowerStateMap[deviceId]
        if (cached != null) {
            if (cached.firstProbeFailed) {
                return FPowerParser.buildSnapshot(
                    currentMicroA = null,
                    voltageMicroV = null,
                    fps = currentFps,
                    rawPathsTried = emptyList(),
                    lastReadout = emptyMap(),
                )
            }
            val winner = cached.winningTuple
            if (winner != null) {
                val currentRaw = shell(deviceId, "cat ${winner.currentPath} 2>/dev/null", timeoutMs = 2000)
                val voltageRaw = shell(deviceId, "cat ${winner.voltagePath} 2>/dev/null", timeoutMs = 2000)
                return FPowerParser.buildSnapshot(
                    currentMicroA = FPowerParser.parseMicroAmpere(currentRaw),
                    voltageMicroV = FPowerParser.parseMicroVolt(voltageRaw),
                    fps = currentFps,
                    rawPathsTried = listOf(winner.currentPath, winner.voltagePath),
                    lastReadout = mapOf(
                        winner.currentPath to currentRaw,
                        winner.voltagePath to voltageRaw,
                    ),
                )
            }
        }

        // Cold-probe simulation. Mirrors AdbBridge.captureFPower so end-to-end
        // tests can drive the catalog walk by populating shellResponses with
        // vendor path keys. Caches winner / first-probe-failure to honour
        // FPW-006 cache contract.
        val pathsTried = mutableListOf<String>()
        val readouts = linkedMapOf<String, String>()
        for (tuple in FPowerVendorCatalog.ORDERED_PATHS) {
            val currentRaw = shell(deviceId, "cat ${tuple.currentPath} 2>/dev/null", timeoutMs = 2000)
            val voltageRaw = shell(deviceId, "cat ${tuple.voltagePath} 2>/dev/null", timeoutMs = 2000)
            pathsTried += tuple.currentPath
            pathsTried += tuple.voltagePath
            readouts[tuple.currentPath] = currentRaw
            readouts[tuple.voltagePath] = voltageRaw
            val currentMicroA = FPowerParser.parseMicroAmpere(currentRaw)
            val voltageMicroV = FPowerParser.parseMicroVolt(voltageRaw)
            if (currentMicroA != null && voltageMicroV != null) {
                fpowerStateMap[deviceId] = FPowerDeviceState(
                    winningTuple = tuple,
                    firstProbeFailed = false,
                )
                return FPowerParser.buildSnapshot(
                    currentMicroA = currentMicroA,
                    voltageMicroV = voltageMicroV,
                    fps = currentFps,
                    rawPathsTried = listOf(tuple.currentPath, tuple.voltagePath),
                    lastReadout = mapOf(
                        tuple.currentPath to currentRaw,
                        tuple.voltagePath to voltageRaw,
                    ),
                )
            }
        }
        fpowerStateMap[deviceId] = FPowerDeviceState(
            winningTuple = null,
            firstProbeFailed = true,
        )
        return FPowerParser.buildSnapshot(
            currentMicroA = null,
            voltageMicroV = null,
            fps = currentFps,
            rawPathsTried = pathsTried.take(FPOWER_FAKE_DIAGNOSTIC_CAP),
            lastReadout = readouts.entries.take(FPOWER_FAKE_DIAGNOSTIC_CAP)
                .associate { it.key to it.value },
        )
    }

    private companion object {
        /** Mirror of [AdbBridge]'s diagnostic cap so test sizes track production. */
        const val FPOWER_FAKE_DIAGNOSTIC_CAP: Int = 10
    }

    override fun startScreenRecord(
        deviceId: String, sessionId: String, segment: Int,
        profile: AdbBridge.ScreenRecordProfile,
    ): Process? {
        startCalls += StartCall(deviceId, sessionId, segment, profile)
        if (scriptedStarts.isEmpty()) {
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

    // ===== v4.4.0 — logcat / shell stubs for auto event detection =====

    /**
     * Classpath resource path of the logcat fixture used by [startLogcat].
     * Configure via [setLogcatFixture]. When `null`, [startLogcat] returns null.
     */
    @Volatile
    private var currentLogcatFixture: String? = null

    /** Recorded calls to [startLogcat] for assertion. */
    val startLogcatCalls: MutableList<Pair<String, List<String>>> = mutableListOf()

    /**
     * Configure the classpath resource that [startLogcat] should replay as the
     * spawned process's stdout. Path is relative to test resources (e.g.
     * "logcat-fixtures/admob-interstitial.log").
     */
    fun setLogcatFixture(resourcePath: String?): FakeAdbBridge {
        currentLogcatFixture = resourcePath
        return this
    }

    override fun startLogcat(deviceId: String, tagArgs: List<String>): Process? {
        startLogcatCalls += deviceId to tagArgs
        val fixture = currentLogcatFixture ?: return null
        val bytes = javaClass.classLoader
            .getResourceAsStream(fixture)
            ?.use { it.readAllBytes() }
            ?: return null
        return FakeLogcatProcess(bytes)
    }

    /** Recorded calls to [shell]: deviceId → command. */
    val shellCalls: MutableList<Pair<String, String>> = mutableListOf()

    /**
     * Canned responses for `shell(deviceId, cmd)`. Tests configure by adding
     * `cmd` (or a substring) → output mapping. First substring match wins.
     * If empty, returns "" (mirrors real bridge's swallow-on-error behavior).
     */
    val shellResponses: MutableMap<String, String> = mutableMapOf()

    override fun shell(deviceId: String, cmd: String, timeoutMs: Long): String {
        shellCalls += deviceId to cmd
        // Substring match so tests can set "dumpsys activity" as a key
        // without having to repeat the full command string.
        return shellResponses.entries.firstOrNull { (key, _) -> cmd.contains(key) }?.value
            ?: ""
    }

    override fun pullRecordings(
        deviceId: String, sessionId: String, localDir: File, maxSegments: Int,
    ): List<File> = emptyList()

    override fun cleanRecordings(deviceId: String) { /* no-op */ }

    override fun concatSegments(segments: List<File>, output: File): File? = null
    override fun isValidVideoFile(file: File): Boolean = false

    // ===== v3.2.0 — Wireless ADB =====

    val scriptedPair: MutableList<PairResult> = mutableListOf()
    val scriptedConnect: MutableList<ConnectResult> = mutableListOf()
    val scriptedMdnsSnapshots: MutableList<List<MdnsService>> = mutableListOf()
    var mdnsAvailableOverride: Boolean = true
    var scriptedAdbVersion: AdbVersion? = AdbVersion(34, 0, 0)

    val pairCalls: MutableList<Triple<String, Int, String>> = mutableListOf()
    val connectCalls: MutableList<Pair<String, Int>> = mutableListOf()
    @Volatile var mdnsServiceCalls: Int = 0
    val disconnectCalls: MutableList<String> = mutableListOf()

    override fun pair(ip: String, port: Int, code: String): PairResult {
        pairCalls += Triple(ip, port, code)
        return if (scriptedPair.isNotEmpty()) scriptedPair.removeAt(0)
        else PairResult.Failure(PairFailureReason.UNKNOWN, "")
    }

    override fun connectWireless(ip: String, port: Int): ConnectResult {
        connectCalls += ip to port
        return if (scriptedConnect.isNotEmpty()) scriptedConnect.removeAt(0)
        else ConnectResult.Failure(ConnectFailureReason.UNKNOWN, "")
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

/**
 * In-memory [Process] that replays a fixed byte buffer on its `inputStream`.
 *
 * Used by [FakeAdbBridge.startLogcat] so logcat-driven tests can drive the
 * event pipeline without spawning a real `adb` process. The fixture bytes are
 * delivered as a single read; once exhausted, [waitFor] returns 0 and the
 * process is no longer alive.
 */
private class FakeLogcatProcess(bytes: ByteArray) : Process() {
    private val input = ByteArrayInputStream(bytes)
    private val output = ByteArrayOutputStream()
    @Volatile private var alive = true
    override fun getOutputStream() = output
    override fun getInputStream() = input
    override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int { alive = false; return 0 }
    override fun exitValue(): Int =
        if (alive) throw IllegalThreadStateException() else 0
    override fun destroy() { alive = false }
    override fun isAlive(): Boolean = alive
}
