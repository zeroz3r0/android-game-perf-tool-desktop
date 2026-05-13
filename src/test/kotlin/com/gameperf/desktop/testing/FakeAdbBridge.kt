package com.gameperf.desktop.testing

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.core.AdbBridgeApi
import com.gameperf.desktop.core.AdbVersion
import com.gameperf.desktop.core.ConnectFailureReason
import com.gameperf.desktop.core.ConnectResult
import com.gameperf.desktop.core.FPowerParser
import com.gameperf.desktop.core.FPowerVendorCatalog
import com.gameperf.desktop.core.GpuProbeResult
import com.gameperf.desktop.core.GpuUsageParser
import com.gameperf.desktop.core.GpuVendor
import com.gameperf.desktop.core.GpuVendorCatalog
import com.gameperf.desktop.core.MdnsService
import com.gameperf.desktop.core.PairFailureReason
import com.gameperf.desktop.core.PairResult
import com.gameperf.desktop.core.ProbeFormat
import com.gameperf.desktop.core.model.Device
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.model.FPowerSnapshot
import com.gameperf.desktop.core.model.FrameSnapshot
import com.gameperf.desktop.core.model.GpuDiagnostic
import com.gameperf.desktop.core.model.GpuSnapshot
import com.gameperf.desktop.core.model.GpuUnavailableReason
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
        // v4.5.0 (gpu-usage-percent) — best-effort `echo 0 > perfcounter` for
        // every device where WE flipped the bit, then clear the GPU probe
        // cache (spec GPU-007.3 + GPU-014). Mirrors AdbBridge.resetSessionState.
        gpuStateMap.entries
            .filter { it.value.perfcounterEnabledByUs }
            .forEach { (deviceId, _) ->
                // Best-effort — swallow failures (spec GPU-007.3 + GPU-014).
                try {
                    shell(
                        deviceId,
                        "echo 0 > ${GpuVendorCatalog.ADRENO_PERFCOUNTER_NODE}",
                        timeoutMs = 2000,
                    )
                } catch (_: Exception) { /* swallow */ }
            }
        gpuStateMap.clear()
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

    // ===== v4.5.0 — GPU usage (gpu-usage-percent) ============================

    /**
     * v4.5.0 — optional override for [captureGpuUsage]. When non-null, the
     * fake returns this exact snapshot identity, short-circuiting BEFORE any
     * [shell] reads or state-map mutation. Install via [setGpu] for tests
     * that don't care about the probe cascade (e.g., ViewModel wiring tests).
     *
     * When null, [captureGpuUsage] simulates the production state machine end
     * to end against [shellResponses] so wired probe-flow tests can drive it
     * with substring-keyed responses.
     */
    @Volatile
    private var scriptedGpu: GpuSnapshot? = null

    /**
     * v4.5.0 builder — install a [GpuSnapshot] fixture that [captureGpuUsage]
     * returns identity. Lazy / unit-scope scenarios only. Tests exercising
     * the per-device probe-cache (vendor caching, perfcounter enable, sticky
     * failures) should leave this null and populate [shellResponses] with
     * the catalog path keys instead.
     */
    fun setGpu(snapshot: GpuSnapshot): FakeAdbBridge {
        scriptedGpu = snapshot
        return this
    }

    /**
     * v4.5.0 — per-device GPU probe-cache mirror of [AdbBridge.gpuStateMap].
     * Cleared by [resetSessionState]. Mirrors the production state machine
     * end-to-end so the spec GPU-001..GPU-022 + GPU-007 + GPU-014 lifecycle
     * tests can be driven via [shellResponses] without spawning real adb.
     */
    private data class GpuDeviceState(
        val vendor: GpuVendor?,
        val winningPath: String?,
        val format: ProbeFormat?,
        val lastBusyTotal: Pair<Long, Long>?,
        val perfcounterEnabledByUs: Boolean = false,
        val firstProbeFailed: Boolean = false,
        val terminalDiagnostic: GpuDiagnostic? = null,
    )
    private val gpuStateMap: MutableMap<String, GpuDeviceState> = mutableMapOf()

    /**
     * v4.5.0 — exception-injection hook. When the deviceId matches an entry
     * here, [captureGpuUsage] throws the supplied exception so the
     * try/catch CAPTURE_THREW resilience path (spec GPU-022) can be tested
     * without subclassing.
     */
    val gpuThrowOn: MutableMap<String, Exception> = mutableMapOf()

    override fun captureGpuUsage(deviceId: String): GpuSnapshot {
        scriptedGpu?.let { return it }
        return try {
            gpuThrowOn[deviceId]?.let { throw it }
            captureGpuUsageImpl(deviceId)
        } catch (_: Exception) {
            GpuSnapshot(
                usagePct = -1,
                gpuAvailable = false,
                diagnostic = GpuDiagnostic(
                    probedPaths = emptyList(),
                    detectedVendor = null,
                    reason = GpuUnavailableReason.CAPTURE_THREW,
                ),
            )
        }
    }

    private fun captureGpuUsageImpl(deviceId: String): GpuSnapshot {
        val cached = gpuStateMap[deviceId]
        // Sticky terminal failure — return cached diagnostic without re-shelling.
        if (cached?.firstProbeFailed == true) {
            val diag = cached.terminalDiagnostic ?: GpuDiagnostic(
                probedPaths = GpuVendorCatalog.PROBE_CANDIDATES.map { it.path }
                    .take(GPU_FAKE_DIAGNOSTIC_CAP),
                detectedVendor = cached.vendor?.name,
                reason = GpuUnavailableReason.ALL_PROBES_FAILED,
            )
            return GpuSnapshot(usagePct = -1, gpuAvailable = false, diagnostic = diag)
        }
        // Probe (first call OR after a successful perfcounter-enable cleared winningPath).
        if (cached == null || cached.winningPath == null) {
            // Probe each catalog path individually against [shellResponses]
            // so tests can drive per-path substring matches without colliding
            // with multi-path probe shell strings (design §2.7 simulation
            // convention; production AdbBridge still uses a single for-loop
            // shell — the per-path fake is behaviour-equivalent because
            // GpuUsageParser.parseProbeOutput just iterates the catalog in
            // order looking for the first non-empty hit).
            var hit: GpuProbeResult? = null
            for (candidate in GpuVendorCatalog.PROBE_CANDIDATES) {
                val raw = shell(deviceId, "cat ${candidate.path} 2>/dev/null", timeoutMs = 3000)
                val payload = raw.trim()
                if (payload.isEmpty()) continue
                hit = GpuProbeResult(
                    vendor = candidate.vendor,
                    winningPath = candidate.path,
                    format = candidate.format,
                    rawPayload = payload,
                )
                break
            }
            if (hit == null) {
                return handleGpuProbeMissed(deviceId, cached)
            }
            // First hit defines vendor + format. PowerVR → permanent unavailable.
            if (hit.vendor == GpuVendor.POWERVR) {
                val diag = GpuDiagnostic(
                    probedPaths = GpuVendorCatalog.PROBE_CANDIDATES.map { it.path }
                        .take(GPU_FAKE_DIAGNOSTIC_CAP),
                    detectedVendor = "POWERVR",
                    reason = GpuUnavailableReason.POWERVR_UNSUPPORTED,
                )
                gpuStateMap[deviceId] = GpuDeviceState(
                    vendor = GpuVendor.POWERVR,
                    winningPath = null,
                    format = null,
                    lastBusyTotal = null,
                    perfcounterEnabledByUs = cached?.perfcounterEnabledByUs ?: false,
                    firstProbeFailed = true,
                    terminalDiagnostic = diag,
                )
                return GpuSnapshot(usagePct = -1, gpuAvailable = false, diagnostic = diag)
            }
            gpuStateMap[deviceId] = GpuDeviceState(
                vendor = hit.vendor,
                winningPath = hit.winningPath,
                format = hit.format,
                lastBusyTotal = null,
                perfcounterEnabledByUs = cached?.perfcounterEnabledByUs ?: false,
                firstProbeFailed = false,
            )
            return readGpuFromCached(deviceId, hit)
        }
        return readGpuFromCached(deviceId, cached)
    }

    /**
     * Both Adreno probes empty (or vendor unknown / probe miss). Attempt
     * `echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter` if we haven't yet AND
     * the vendor cache hints at Adreno (or is unknown — A13+ may emit empty
     * `gpu_busy_percentage` AND empty `gpubusy` before the enable). On
     * success drop the winningPath so the next tick re-probes; on failure
     * mark terminal-unavailable.
     */
    private fun handleGpuProbeMissed(deviceId: String, prev: GpuDeviceState?): GpuSnapshot {
        if (prev?.perfcounterEnabledByUs != true && prev?.firstProbeFailed != true) {
            val enableCmd = "echo 1 > ${GpuVendorCatalog.ADRENO_PERFCOUNTER_NODE} 2>&1; echo rc=$?"
            val out = shell(deviceId, enableCmd, timeoutMs = 2000)
            val ok = out.contains("rc=0") &&
                !out.contains("Permission", ignoreCase = true) &&
                !out.contains("denied", ignoreCase = true)
            if (ok) {
                gpuStateMap[deviceId] = GpuDeviceState(
                    vendor = GpuVendor.ADRENO,
                    winningPath = null,
                    format = null,
                    lastBusyTotal = null,
                    perfcounterEnabledByUs = true,
                    firstProbeFailed = false,
                )
                return GpuSnapshot(
                    usagePct = -1,
                    gpuAvailable = false,
                    diagnostic = GpuDiagnostic(
                        probedPaths = GpuVendorCatalog.PROBE_CANDIDATES
                            .filter { it.vendor == GpuVendor.ADRENO }
                            .map { it.path }
                            .take(GPU_FAKE_DIAGNOSTIC_CAP),
                        detectedVendor = "ADRENO",
                        reason = GpuUnavailableReason.ALL_PROBES_FAILED,
                    ),
                )
            }
            // Enable failed — mark terminal.
            val diag = GpuDiagnostic(
                probedPaths = GpuVendorCatalog.PROBE_CANDIDATES
                    .filter { it.vendor == GpuVendor.ADRENO }
                    .map { it.path }
                    .take(GPU_FAKE_DIAGNOSTIC_CAP),
                detectedVendor = "ADRENO",
                failedEnableCommand = enableCmd,
                reason = GpuUnavailableReason.ADRENO_PERFCOUNTER_DISABLED,
            )
            gpuStateMap[deviceId] = GpuDeviceState(
                vendor = GpuVendor.ADRENO,
                winningPath = null,
                format = null,
                lastBusyTotal = null,
                perfcounterEnabledByUs = false,
                firstProbeFailed = true,
                terminalDiagnostic = diag,
            )
            return GpuSnapshot(usagePct = -1, gpuAvailable = false, diagnostic = diag)
        }
        // Already enabled but probe STILL empty → ADRENO_BLOCKED terminal.
        // Reaching here requires prev != null (Kotlin smart-cast confirms:
        // both `firstProbeFailed=true` and `perfcounterEnabledByUs=false`
        // branches above filtered the null case).
        val diag = GpuDiagnostic(
            probedPaths = GpuVendorCatalog.PROBE_CANDIDATES
                .filter { it.vendor == GpuVendor.ADRENO }
                .map { it.path }
                .take(GPU_FAKE_DIAGNOSTIC_CAP),
            detectedVendor = "ADRENO",
            reason = GpuUnavailableReason.ADRENO_BLOCKED,
        )
        gpuStateMap[deviceId] = prev.copy(
            firstProbeFailed = true,
            terminalDiagnostic = diag,
        )
        return GpuSnapshot(usagePct = -1, gpuAvailable = false, diagnostic = diag)
    }

    private fun readGpuFromCached(deviceId: String, probeHit: GpuProbeResult): GpuSnapshot {
        // Path freshly resolved on this tick — payload already in hand for
        // MALI / ADRENO_GPU_BUSY_PERCENTAGE. For ADRENO_KGSL_BUSY_TOTAL the
        // payload is the baseline; we store it and return UNAVAILABLE (next
        // tick computes the delta via the cached-state branch).
        return when (probeHit.format) {
            ProbeFormat.MALI_INT_0_100 ->
                GpuUsageParser.parseMali(probeHit.rawPayload)?.let {
                    GpuSnapshot(it, gpuAvailable = true, diagnostic = null)
                } ?: GpuSnapshot(-1, false, null)
            ProbeFormat.ADRENO_GPU_BUSY_PERCENTAGE ->
                GpuUsageParser.parseAdrenoGpuBusyPercentage(probeHit.rawPayload)?.let {
                    GpuSnapshot(it, gpuAvailable = true, diagnostic = null)
                } ?: GpuSnapshot(-1, false, null)
            ProbeFormat.ADRENO_KGSL_BUSY_TOTAL -> {
                val baseline = GpuUsageParser.parseAdrenoGpuBusy(probeHit.rawPayload)
                if (baseline != null) {
                    gpuStateMap[deviceId] = gpuStateMap[deviceId]!!.copy(lastBusyTotal = baseline)
                }
                GpuSnapshot(-1, false, null)
            }
            ProbeFormat.POWERVR_UNKNOWN -> GpuSnapshot(-1, false, null)
        }
    }

    @Suppress("ReturnCount")
    private fun readGpuFromCached(deviceId: String, st: GpuDeviceState): GpuSnapshot {
        val path = st.winningPath ?: return GpuSnapshot(-1, false, null)
        return when (st.format) {
            ProbeFormat.MALI_INT_0_100 -> {
                val raw = shell(deviceId, "cat $path 2>/dev/null", timeoutMs = 2000)
                GpuUsageParser.parseMali(raw)?.let {
                    GpuSnapshot(it, gpuAvailable = true, diagnostic = null)
                } ?: GpuSnapshot(-1, false, null)
            }
            ProbeFormat.ADRENO_GPU_BUSY_PERCENTAGE -> {
                val raw = shell(deviceId, "cat $path 2>/dev/null", timeoutMs = 2000)
                GpuUsageParser.parseAdrenoGpuBusyPercentage(raw)?.let {
                    GpuSnapshot(it, gpuAvailable = true, diagnostic = null)
                } ?: GpuSnapshot(-1, false, null)
            }
            ProbeFormat.ADRENO_KGSL_BUSY_TOTAL -> {
                val raw = shell(deviceId, "cat $path 2>/dev/null", timeoutMs = 2000)
                val curr = GpuUsageParser.parseAdrenoGpuBusy(raw)
                    ?: return GpuSnapshot(-1, false, null)
                val prev = st.lastBusyTotal
                gpuStateMap[deviceId] = st.copy(lastBusyTotal = curr)
                if (prev == null) return GpuSnapshot(-1, false, null)
                val pct = GpuUsageParser.computeAdrenoDelta(prev, curr)
                    ?: return GpuSnapshot(-1, false, null)
                GpuSnapshot(pct, gpuAvailable = true, diagnostic = null)
            }
            ProbeFormat.POWERVR_UNKNOWN, null -> GpuSnapshot(-1, false, null)
        }
    }

    private companion object {
        /** Mirror of [AdbBridge]'s diagnostic cap so test sizes track production. */
        const val FPOWER_FAKE_DIAGNOSTIC_CAP: Int = 10
        /** GPU diagnostic cap — mirrors GpuDiagnostic spec GPU-011 (≤10 paths). */
        const val GPU_FAKE_DIAGNOSTIC_CAP: Int = 10
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
