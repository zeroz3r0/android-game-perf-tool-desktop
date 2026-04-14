package com.gameperf.desktop.testing

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.core.AdbBridgeApi
import com.gameperf.desktop.core.AdbVersion
import com.gameperf.desktop.core.ConnectFailureReason
import com.gameperf.desktop.core.ConnectResult
import com.gameperf.desktop.core.MdnsService
import com.gameperf.desktop.core.PairFailureReason
import com.gameperf.desktop.core.PairResult
import com.gameperf.desktop.core.model.Device
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.model.FrameSnapshot
import com.gameperf.desktop.core.model.MemSnapshot
import com.gameperf.desktop.core.model.ThermalSnapshot
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

    override fun resetSessionState() { /* no-op */ }

    override fun captureFrames(deviceId: String, pkg: String): FrameSnapshot? = null
    override fun captureCpuPercent(deviceId: String): Int = 0
    override fun captureMemory(deviceId: String, pkg: String): MemSnapshot? = null
    override fun captureTemperature(deviceId: String): ThermalSnapshot =
        ThermalSnapshot(Double.NaN, Double.NaN, Double.NaN, Double.NaN)

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
