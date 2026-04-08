package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.core.AppVersion
import com.gameperf.desktop.core.AutoUpdater
import com.gameperf.desktop.core.CURRENT_VERSION
import com.gameperf.desktop.core.FileCleanup
import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.report.PdfExporter
import com.gameperf.desktop.report.ReportGenerator
import com.gameperf.desktop.ui.util.PickerUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Desktop
import java.io.File
import java.time.LocalDate
import java.util.Collections

enum class AppScreen { HOME, CAPTURING, RESULTS, COMPARISON }

/** A metric sample with the exact second it was captured (for video correlation). */
data class TimedSample(val second: Int, val value: Double)

/** Types of session markers that can be placed during capture. */
enum class MarkerType(val label: String, val colorHex: String) {
    INTERSTITIAL("Intersticial", "#FF6600"),
    VIDEO_REWARD("Video Reward", "#7B2CBF"),
    LOADING("Carga", "#FFAA00"),
    SCENE_CHANGE("Cambio escena", "#00D4FF"),
    CUSTOM("Nota", "#00FF88")
}

/** A marker placed by the user during a capture session.
 *
 * timestampMs: marker position in milliseconds for video correlation.
 * timestampSeconds: convenience accessor for backward compatibility.
 * colorHex: user-chosen color as hex string (defaults to the MarkerType color).
 * title: short label for the marker.
 */
data class SessionMarker(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestampMs: Long,
    val type: MarkerType,
    val title: String = "",
    val note: String = "",
    val colorHex: String = "#FF0000"
) {
    /** Backward-compatible second accessor used by graphs and reports. */
    val timestampSeconds: Int get() = (timestampMs / 1000).toInt()
}

data class LiveMetrics(
    val elapsed: Int = 0,
    val fps: Int = 0,
    val avgFps: Double = 0.0,
    val frameTime: Double = 0.0,
    val cpu: Int = 0,
    val memMb: Long = 0,
    val nativeMb: Long = 0,
    val javaMb: Long = 0,
    val tempCpu: Double = -1.0,
    val tempGpu: Double = -1.0,
    val tempBattery: Double = -1.0,
    val tempSkin: Double = -1.0,
    val jankCount: Int = 0,
    val stutterCount: Int = 0,
    val battery: Int = 0,
    val frameDrops: Int = 0,
    val fpsHistory: List<Int> = emptyList(),
    val fpsTimed: List<TimedSample> = emptyList(),
    val memHistory: List<Long> = emptyList(),
    val nativeHistory: List<Long> = emptyList(),
    val javaHistory: List<Long> = emptyList(),
    val cpuHistory: List<Int> = emptyList(),
    val tempCpuHistory: List<Double> = emptyList(),
    val tempGpuHistory: List<Double> = emptyList(),
    val tempSkinHistory: List<Double> = emptyList(),
    val frameTimeHistory: List<Double> = emptyList(),
    val allFrameTimes: List<Double> = emptyList()
)

data class SessionResult(
    val gamePackage: String = "",
    val deviceModel: String = "",
    val duration: Int = 0,
    val grade: Char = 'F',
    val avgFps: Int = 0,
    val minFps: Int = 0,
    val maxFps: Int = 0,
    val p1Fps: Int = 0,
    val p5Fps: Int = 0,
    val p50Fps: Int = 0,
    val p90Fps: Int = 0,
    val p99Fps: Int = 0,
    val avgFrameTime: Double = 0.0,
    val p99FrameTime: Double = 0.0,
    val peakMemMb: Long = 0,
    val avgCpu: Int = 0,
    val maxCpu: Int = 0,
    val maxTempCpu: Double = 0.0,
    val maxTempGpu: Double = 0.0,
    val batteryStart: Int = 0,
    val batteryEnd: Int = 0,
    val batteryDrain: Int = 0,
    val frameDrops: Int = 0,
    val totalJank: Int = 0,
    val totalStutter: Int = 0,
    val problems: List<String> = emptyList(),
    val reportPath: String = "",
    val isWifi: Boolean = false,
    val videoPath: String = "",
    val deviceGrade: Char = ' ',
    val deviceScore: Int = 0,
    val deviceTier: String = "",
    val markers: List<SessionMarker> = emptyList()
)

class AppViewModel {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Must be called when the application window is closed to avoid scope leaks. */
    fun cleanup() {
        // Delete tracked tmpdir comparison HTMLs created during this session.
        synchronized(_tempComparisons) {
            _tempComparisons.forEach { path ->
                runCatching { File(path).delete() }
            }
            _tempComparisons.clear()
        }
        scope.cancel()
    }

    private val _screen = MutableStateFlow(AppScreen.HOME)
    val screen: StateFlow<AppScreen> = _screen

    private val _adbAvailable = MutableStateFlow(false)
    val adbAvailable: StateFlow<Boolean> = _adbAvailable

    private val _devices = MutableStateFlow<List<AdbBridge.Device>>(emptyList())
    val devices: StateFlow<List<AdbBridge.Device>> = _devices

    private val _selectedDevice = MutableStateFlow<AdbBridge.Device?>(null)
    val selectedDevice: StateFlow<AdbBridge.Device?> = _selectedDevice

    private val _deviceInfo = MutableStateFlow<AdbBridge.DeviceInfo?>(null)
    val deviceInfo: StateFlow<AdbBridge.DeviceInfo?> = _deviceInfo

    private val _gamePackage = MutableStateFlow<String?>(null)
    val gamePackage: StateFlow<String?> = _gamePackage

    private val _statusMessage = MutableStateFlow("Iniciando...")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    private val _isWifi = MutableStateFlow(false)
    val isWifi: StateFlow<Boolean> = _isWifi

    private val _wifiStatus = MutableStateFlow("")
    val wifiStatus: StateFlow<String> = _wifiStatus

    private val _liveMetrics = MutableStateFlow(LiveMetrics())
    val liveMetrics: StateFlow<LiveMetrics> = _liveMetrics

    private val _result = MutableStateFlow(SessionResult())
    val result: StateFlow<SessionResult> = _result

    private val _markers = MutableStateFlow<List<SessionMarker>>(emptyList())
    val markers: StateFlow<List<SessionMarker>> = _markers

    // ===== Video Playback State =====
    private val _videoPosition = MutableStateFlow(0L)
    val videoPosition: StateFlow<Long> = _videoPosition

    private val _isVideoPlaying = MutableStateFlow(false)
    val isVideoPlaying: StateFlow<Boolean> = _isVideoPlaying

    private val _videoDuration = MutableStateFlow(0L)
    val videoDuration: StateFlow<Long> = _videoDuration

    private val _playbackSpeed = MutableStateFlow(1.0)
    val playbackSpeed: StateFlow<Double> = _playbackSpeed

    private val _history = MutableStateFlow<List<SessionHistory.HistoryEntry>>(emptyList())
    val history: StateFlow<List<SessionHistory.HistoryEntry>> = _history

    // ===== Session Tagging =====
    private val _sessionTag = MutableStateFlow(SessionHistory.SessionTag.OUR_GAME)
    val sessionTag: StateFlow<SessionHistory.SessionTag> = _sessionTag

    private val _competitorName = MutableStateFlow("")
    val competitorName: StateFlow<String> = _competitorName

    // ===== Comparison =====
    private val _selectedForComparison = MutableStateFlow<Set<String>>(emptySet())
    val selectedForComparison: StateFlow<Set<String>> = _selectedForComparison

    // ===== Auto-Update =====
    private val _updateAvailable = MutableStateFlow<AutoUpdater.ReleaseInfo?>(null)
    val updateAvailable: StateFlow<AutoUpdater.ReleaseInfo?> = _updateAvailable

    private val _updateProgress = MutableStateFlow<Float?>(null)
    val updateProgress: StateFlow<Float?> = _updateProgress

    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError

    // ===== Capture Error (device disconnect, etc.) =====
    private val _captureError = MutableStateFlow<String?>(null)
    val captureError: StateFlow<String?> = _captureError
    // v3.1.11: non-fatal warnings (capture continues, but the user should know).
    // Used for cases like "video recording failed but metrics succeeded".
    private val _captureWarning = MutableStateFlow<String?>(null)
    val captureWarning: StateFlow<String?> = _captureWarning

    // ===== PDF Export =====
    /**
     * Lifecycle of a single PDF export attempt. Drives the [ExportBanner] composable.
     * The flow is always Idle -> InProgress -> Success | Error -> auto-reset to Idle
     * by the banner after 3s.
     *
     * [Error.actionUrl] / [Error.actionLabel] are optional and used by the banner to
     * render an inline action button (e.g. "Descargar Chrome" when no browser is
     * detected). Both must be non-null for the button to appear.
     */
    sealed class ExportStatus {
        object Idle : ExportStatus()
        object InProgress : ExportStatus()
        data class Success(val path: String) : ExportStatus()
        data class Error(
            val message: String,
            val actionUrl: String? = null,
            val actionLabel: String? = null,
        ) : ExportStatus()
    }

    private val _exportStatus = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    val exportStatus: StateFlow<ExportStatus> = _exportStatus.asStateFlow()

    /**
     * Tracks comparison HTMLs written to `java.io.tmpdir` during this run so they can
     * be deleted by [cleanup] on window close. Synchronized for safety against
     * concurrent generations from different threads.
     */
    private val _tempComparisons: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile private var shouldStop = false
    @Volatile private var captureStartTime: Long = 0L
    private var captureJob: Job? = null
    private var pollingJob: Job? = null
    private var recordProcess: Process? = null
    private var recordSegment = 0
    private var recordJob: Job? = null

    // v3.1.13: diagnostic counter for chain segment failures (segments 1..N that
    // died during warm-up after the first one). Exposed for tests and future telemetry.
    // Reset on every new capture in startCapture().
    @Volatile internal var recordChainFailures: Int = 0
        private set

    /**
     * v3.1.13 — Result of validating a freshly-started screenrecord [Process].
     *
     * Why this exists: a [Process] returned by `AdbBridge.startScreenRecord` may
     * already be dead by the time we look at it (encoder rejected, /sdcard full,
     * low-memory killer, unsupported codec). We need a single uniform way to
     * detect that and surface the stderr for diagnosis. Used by both the initial
     * segment path and the chain loop in [recordJob].
     *
     * Pure data class — no side effects on construction. The actual waiting/checking
     * happens in [validateScreenRecordProcess], which is package-private so tests
     * can drive it with synthetic processes (e.g. `sh -c "exit 1"`) without having
     * to mock the entire `AdbBridge` singleton.
     */
    internal sealed class ScreenRecordValidation {
        /** Process is still alive after the warm-up window — ready for use. */
        data class Alive(val process: Process) : ScreenRecordValidation()
        /** Process died during warm-up. [stderr] is the captured tail (best-effort). */
        data class DeadDuringWarmup(val exitCode: Int, val stderr: String) : ScreenRecordValidation()
        /** The caller passed a null process (i.e. `ProcessBuilder.start()` itself failed). */
        object NullProcess : ScreenRecordValidation()
    }

    /**
     * v3.1.13 — Validate a freshly-started screenrecord process.
     *
     * Waits [warmupMs] (default 1500ms — what `screenrecord` needs to actually start
     * capturing frames), then checks `isAlive`. If the process died, reads up to
     * 2KB of its stderr (which is also stdout because `redirectErrorStream(true)`
     * is set in [AdbBridge.startScreenRecord]) for diagnosis.
     *
     * **Pure-ish**: the only side effects are `delay()` and reading the process'
     * own stream. Does NOT mutate any AppViewModel state, does NOT log to stderr.
     * The caller is responsible for logging and reacting to the result.
     *
     * Visible to tests so we can verify the dead-process detection without having
     * to start a real `screenrecord` chain on a real device.
     */
    internal suspend fun validateScreenRecordProcess(
        process: Process?,
        warmupMs: Long = 1500
    ): ScreenRecordValidation {
        if (process == null) return ScreenRecordValidation.NullProcess
        delay(warmupMs)
        if (process.isAlive) return ScreenRecordValidation.Alive(process)
        // Process died. `redirectErrorStream(true)` means stderr is on the inputStream.
        // Read defensively: cap at 2KB so we don't block on a runaway producer.
        val tail = try {
            val buf = ByteArray(2048)
            val read = process.inputStream.read(buf)
            if (read > 0) String(buf, 0, read).trim() else "(no output)"
        } catch (_: Exception) { "(stderr unreadable)" }
        val exit = try { process.exitValue() } catch (_: Exception) { -1 }
        return ScreenRecordValidation.DeadDuringWarmup(exitCode = exit, stderr = tail)
    }

    /**
     * v3.1.13 — Start a single screenrecord segment with retry-on-failure semantics.
     *
     * Logic:
     *   1. Call `AdbBridge.startScreenRecord` with [profile].
     *   2. Validate via [validateScreenRecordProcess] (warm-up 1500ms + isAlive check).
     *   3. If alive → return the process.
     *   4. If dead → log the stderr. If [profile] != STANDARD, retry with STANDARD.
     *   5. If the retry also dies → log and return null.
     *
     * Used by BOTH the initial segment path in [startCapture] AND each iteration of
     * the chain loop in [recordJob]. Before v3.1.13, only the initial segment had
     * this logic — chain segments called `startScreenRecord` directly and any
     * silent death produced a `break` with no warning. That was the root cause of
     * the v3.1.10/11/12 chain regressions.
     *
     * @return the started, alive [Process] on success; null if both attempts failed.
     */
    private suspend fun startSegmentWithRetry(
        deviceId: String,
        sessionId: String,
        segment: Int,
        profile: AdbBridge.ScreenRecordProfile
    ): Process? {
        val firstAttempt = AdbBridge.startScreenRecord(deviceId, sessionId, segment, profile)
        when (val v = validateScreenRecordProcess(firstAttempt)) {
            is ScreenRecordValidation.Alive -> return v.process
            is ScreenRecordValidation.DeadDuringWarmup -> {
                System.err.println(
                    "AppViewModel: screenrecord segment=$segment profile=$profile died during warm-up " +
                        "(exit=${v.exitCode}): ${v.stderr}"
                )
            }
            ScreenRecordValidation.NullProcess -> {
                System.err.println(
                    "AppViewModel: screenrecord segment=$segment profile=$profile failed to start " +
                        "(ProcessBuilder.start returned null)"
                )
            }
        }

        // Retry with STANDARD profile if we weren't already on it.
        if (profile != AdbBridge.ScreenRecordProfile.STANDARD) {
            System.err.println("AppViewModel: retrying segment=$segment with STANDARD profile")
            val retry = AdbBridge.startScreenRecord(
                deviceId, sessionId, segment, AdbBridge.ScreenRecordProfile.STANDARD
            )
            when (val v = validateScreenRecordProcess(retry)) {
                is ScreenRecordValidation.Alive -> return v.process
                is ScreenRecordValidation.DeadDuringWarmup -> {
                    System.err.println(
                        "AppViewModel: STANDARD retry for segment=$segment also died " +
                            "(exit=${v.exitCode}): ${v.stderr}"
                    )
                }
                ScreenRecordValidation.NullProcess -> {
                    System.err.println(
                        "AppViewModel: STANDARD retry for segment=$segment failed to start"
                    )
                }
            }
        }

        return null
    }

    /**
     * v3.1.13 — Build a human-readable diagnostic message for a failed segment.
     * Looks at the process' last stderr if we still have it (we don't in the chain
     * loop because we drop the reference). For now this is just a fallback string;
     * the per-attempt stderr is already logged inside [startSegmentWithRetry].
     */
    private fun describeChainFailure(segment: Int): String =
        "El video dejó de grabarse en el segmento $segment — el dispositivo rechazó " +
            "screenrecord (encoder, espacio en /sdcard o memoria insuficiente). " +
            "Las métricas posteriores siguen siendo válidas."

    fun init() {
        // Startup file-system cleanup runs on IO before the rest of init touches the
        // history StateFlow. We snapshot the history, ask FileCleanup to remove orphans
        // and repair broken refs, persist the repairs, then load the cleaned state.
        scope.launch(Dispatchers.IO) {
            try {
                val snapshot = SessionHistory.load()
                val result = FileCleanup.pruneOrphans(snapshot)
                result.repairedEntries.forEach { SessionHistory.updateEntry(it) }
                FileCleanup.pruneTmpComparisons()
            } catch (t: Throwable) {
                System.err.println("AppViewModel.init: pruneOrphans failed: ${t.message}")
            }
            try {
                // v3.1.9: legacy entries created before the concat fix point at `_0.mp4`
                // and effectively cap playback at ~2:56. Concat sibling segments into a
                // unified file and rewrite the entry path. ffmpeg-gated, never destructive.
                val snapshot2 = SessionHistory.load()
                val truncationRepairs = FileCleanup.repairTruncatedVideos(snapshot2)
                truncationRepairs.forEach { SessionHistory.updateEntry(it) }
                if (truncationRepairs.isNotEmpty()) {
                    System.err.println("AppViewModel.init: repaired ${truncationRepairs.size} truncated video entries")
                }
            } catch (t: Throwable) {
                System.err.println("AppViewModel.init: repairTruncatedVideos failed: ${t.message}")
            }
            _history.value = SessionHistory.load()
            _adbAvailable.value = AdbBridge.isAvailable()
            if (!_adbAvailable.value) {
                _statusMessage.value = "ADB no encontrado. Instala Android SDK."
                return@launch
            }
            _statusMessage.value = "ADB disponible. Buscando dispositivos..."
            refreshDevices()
        }
        startDevicePolling()
        checkForUpdates()
    }

    // ===== Auto-Update =====

    fun checkForUpdates() {
        scope.launch(Dispatchers.IO) {
            try {
                val release = AutoUpdater.checkForUpdate()
                if (release != null && AutoUpdater.isNewer(release.version, CURRENT_VERSION)) {
                    _updateAvailable.value = release
                }
            } catch (_: Exception) {
                // Silently ignore — update check is non-critical
            }
        }
    }

    fun downloadAndApplyUpdate() {
        val release = _updateAvailable.value ?: return
        val downloadUrl = release.jarUrl
        if (downloadUrl == null) {
            _updateError.value = "No hay JAR disponible para tu plataforma. Descarga manualmente desde: ${release.htmlUrl}"
            return
        }
        scope.launch(Dispatchers.IO) {
            _updateProgress.value = 0f
            _updateError.value = null
            try {
                val file = AutoUpdater.downloadUpdate(downloadUrl) { progress ->
                    _updateProgress.value = progress
                }
                if (file != null) {
                    _updateProgress.value = 1f
                    delay(500)
                    val result = AutoUpdater.applyUpdate(file)
                    if (result.success && result.needsManualRestart) {
                        // Development mode: JAR saved, show message to user
                        _updateError.value = null
                        _updateProgress.value = null
                        _statusMessage.value = result.message
                    } else if (!result.success) {
                        _updateError.value = result.message.ifEmpty { "Error al aplicar la actualización" }
                        _updateProgress.value = null
                    }
                    // If auto-restart succeeded, we'll never reach here (System.exit called)
                } else {
                    val reason = AutoUpdater.lastDownloadError
                    _updateError.value = if (reason.isNullOrBlank()) {
                        "Error al descargar la actualizacion."
                    } else {
                        "Error al descargar: $reason"
                    }
                    _updateProgress.value = null
                }
            } catch (e: Exception) {
                _updateError.value = "Error: ${e.message}"
                _updateProgress.value = null
            }
        }
    }

    fun dismissUpdate() {
        _updateAvailable.value = null
        _updateError.value = null
        _updateProgress.value = null
    }

    private fun startDevicePolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                delay(3000)
                if (_screen.value == AppScreen.HOME) {
                    val devs = AdbBridge.listDevices()
                    val changed = devs.map { it.id } != _devices.value.map { it.id }
                    if (changed) {
                        _devices.value = devs
                        if (devs.isNotEmpty() && _selectedDevice.value == null) {
                            selectDevice(devs.first())
                        } else if (devs.isEmpty()) {
                            _selectedDevice.value = null
                            _deviceInfo.value = null
                            _gamePackage.value = null
                            _isWifi.value = false
                            _statusMessage.value = "Conecta un dispositivo Android por USB"
                        }
                    }
                }
            }
        }
    }

    fun refreshDevices() {
        scope.launch {
            _statusMessage.value = "Buscando dispositivos..."
            val devs = AdbBridge.listDevices()
            _devices.value = devs
            if (devs.isNotEmpty() && _selectedDevice.value == null) {
                selectDevice(devs.first())
            } else if (devs.isEmpty()) {
                _selectedDevice.value = null
                _deviceInfo.value = null
                _gamePackage.value = null
                _statusMessage.value = "Conecta un dispositivo Android por USB"
            }
        }
    }

    fun selectDevice(device: AdbBridge.Device) {
        scope.launch {
            _selectedDevice.value = device
            _isWifi.value = device.isWifi
            _statusMessage.value = "Conectado a ${device.model}. Leyendo specs..."
            _deviceInfo.value = AdbBridge.getDeviceInfo(device.id)
            _statusMessage.value = "Buscando juego en primer plano..."
            _gamePackage.value = AdbBridge.detectGame(device.id)
            _statusMessage.value = if (_gamePackage.value != null) "Listo para capturar" else "No se detecto juego. Abre un juego y pulsa Refrescar."
        }
    }

    fun refreshGame() {
        val device = _selectedDevice.value ?: return
        scope.launch {
            _statusMessage.value = "Buscando juego..."
            _gamePackage.value = AdbBridge.detectGame(device.id)
            _statusMessage.value = if (_gamePackage.value != null) "Listo para capturar" else "No se detecto juego."
        }
    }

    // ===== WiFi Mode =====

    fun switchToWifi() {
        val device = _selectedDevice.value ?: return
        if (device.isWifi) return
        scope.launch {
            _wifiStatus.value = "Activando WiFi ADB..."
            val wifiId = AdbBridge.switchToWifi(device.id)
            if (wifiId != null) {
                _wifiStatus.value = "Conectado via WiFi: $wifiId\nDesconecta el cable USB para medir bateria real."
                // Wait and refresh
                delay(3000)
                val devs = AdbBridge.listDevices()
                _devices.value = devs
                val wifiDevice = devs.find { it.id == wifiId }
                if (wifiDevice != null) {
                    selectDevice(wifiDevice)
                    _isWifi.value = true
                    _wifiStatus.value = "WiFi conectado. Desconecta el cable USB."
                } else {
                    _wifiStatus.value = "WiFi activo pero no verificado. Intenta refrescar."
                }
            } else {
                _wifiStatus.value = "No se pudo activar WiFi. Verifica que el movil y el PC estan en la misma red."
            }
        }
    }

    // ===== Capture =====

    fun startCapture(durationSeconds: Int = 0) {
        val device = _selectedDevice.value ?: return
        val pkg = _gamePackage.value ?: return

        _screen.value = AppScreen.CAPTURING
        _isCapturing.value = true
        _liveMetrics.value = LiveMetrics()
        _markers.value = emptyList()
        _captureError.value = null
        _captureWarning.value = null
        shouldStop = false
        recordChainFailures = 0  // v3.1.13: reset diagnostic counter per capture
        AdbBridge.resetSessionState()

        captureJob = scope.launch {
            val batteryStart = AdbBridge.getBatteryLevel(device.id)
            val missedStart = AdbBridge.getMissedFrames(device.id)
            val isWifiMode = _isWifi.value
            if (!isWifiMode) AdbBridge.disableCharging(device.id)

            // Start video recording and metrics at the same moment
            val videoDir = File(System.getProperty("user.home"), "GamePerf Reports")
            videoDir.mkdirs()
            val sessionId = java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(java.util.Date())
            AdbBridge.cleanRecordings(device.id)
            recordSegment = 0
            // v3.1.10: Pick screenrecord profile based on device tier. LOW and LOWER_MID
            // devices (like the Pixel XL with Adreno 530) get the compact profile to
            // minimize the SurfaceFlinger virtual-display downscale cost. Everyone else
            // keeps the standard 720p profile.
            val recordProfile = run {
                val gpu = _deviceInfo.value?.gpu ?: ""
                val tier = com.gameperf.desktop.core.HardwareScoring.detectTier(gpu)
                when (tier) {
                    com.gameperf.desktop.core.HardwareScoring.DeviceTier.LOW,
                    com.gameperf.desktop.core.HardwareScoring.DeviceTier.LOWER_MID ->
                        AdbBridge.ScreenRecordProfile.COMPACT
                    else -> AdbBridge.ScreenRecordProfile.STANDARD
                }
            }
            // v3.1.11/13: startScreenRecord has TWO failure modes that the v3.1.10 code
            // didn't distinguish:
            //   (a) ProcessBuilder.start() throws → returns null immediately
            //   (b) start() succeeds but `screenrecord` exits within 100ms with non-zero
            //       (unsupported codec, "ERROR: --size <WxH>: width/height must be a
            //        multiple of 16", missing /sdcard permission, etc.). In this case
            //       start() returns a Process object but it's already dead. v3.1.10 had
            //       no detection for this — the chain timer fired, pullRecordings found
            //       no files, the user got a session with empty videoPath and no error.
            //
            // v3.1.13: the detection logic (warm-up + isAlive + stderr capture + retry
            // with STANDARD) used to be inline as `tryStart`. It's now extracted to
            // [startSegmentWithRetry] so the chain loop in recordJob can reuse the
            // EXACT SAME logic for segments 1..N. Before v3.1.13, only the initial
            // segment had this — chain segments could die silently and the loop
            // would just `break` without telling the user. That was the v3.1.10/11/12
            // root cause.
            recordProcess = startSegmentWithRetry(device.id, sessionId, recordSegment, recordProfile)
            if (recordProcess == null) {
                // Both attempts failed. Surface a non-fatal warning so the user knows
                // why the report has no video. Capture continues with metrics only.
                _captureWarning.value = "El video no se pudo grabar en este dispositivo (screenrecord rechazado por el sistema). Las metricas si se estan registrando."
                System.err.println("AppViewModel: screenrecord failed for both COMPACT and STANDARD profiles on device ${device.id}")
            }
            // Note: startSegmentWithRetry already includes the 1500ms warm-up delay (and
            // a second one if the retry path is taken), no need to delay again here.

            // NOW start the clock - video and metrics are synced from this point
            val startTime = System.currentTimeMillis()
            captureStartTime = startTime

            // Chain recordings every ~175s (before the 180s screenrecord hard limit).
            //
            // v3.1.12 — moov atom corruption fix:
            //   The previous version used `delay(1000)` between stop and next start.
            //   That was insufficient: when `stopScreenRecord` calls `destroyForcibly()`,
            //   the adb shell process dies but the `screenrecord` binary running on the
            //   device needs time to flush the MP4 moov atom to /sdcard. 1 second was
            //   not enough on the Pixel XL (Android 10) and similar devices — the
            //   resulting `_0.mp4` ended up with no moov atom, was unplayable, and the
            //   pull+concat path produced a broken videoPath that the player couldn't
            //   read ("No se pudo leer la duración del video" error).
            //   Increased to 3 seconds. The chain interval is still ~175s overall so
            //   the extra 2 seconds per chain step is negligible (~2% overhead at 10 min).
            //
            // v3.1.11: only chain if the initial segment actually started.
            // v3.1.13: chain segments now go through [startSegmentWithRetry] just like
            // the first one. If a chain segment dies during warm-up we get the same
            // stderr-capture + retry-with-STANDARD treatment, AND if it ultimately
            // fails we surface an EXPLICIT warning to the user instead of breaking
            // silently. This closes the v3.1.10/11/12 regression saga.
            recordJob = scope.launch {
                while (!shouldStop) {
                    delay(175_000)
                    if (shouldStop) break
                    AdbBridge.stopScreenRecord(recordProcess)
                    // v3.1.12: 3-second wait to let the device-side screenrecord binary
                    // flush its moov atom. This prevents the segment-zero corruption that
                    // produced 7MB partial files instead of full 80MB segments.
                    delay(3000)
                    recordSegment++
                    val nextProcess = startSegmentWithRetry(device.id, sessionId, recordSegment, recordProfile)
                    if (nextProcess == null) {
                        // v3.1.13: do NOT break silently. Tell the user the chain stopped
                        // and why, so they understand the video is partial. Metrics
                        // capture continues — the chain failure is non-fatal.
                        recordChainFailures++
                        val msg = describeChainFailure(recordSegment)
                        System.err.println("AppViewModel: chain segment $recordSegment failed after retry — $msg")
                        // Only set the warning if we don't already have one (don't
                        // clobber e.g. a "video corrupt" message from concat).
                        if (_captureWarning.value == null) {
                            _captureWarning.value = msg
                        }
                        recordProcess = null
                        break  // exit the chain loop, but do NOT stop the metrics capture
                    }
                    recordProcess = nextProcess
                }
            }

            // Independent timer that updates elapsed every second (smooth UI counter)
            // This runs independently of ADB commands which can take 2-3s each
            val timerJob = scope.launch {
                while (!shouldStop) {
                    delay(1000)
                    val currentElapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                    _liveMetrics.value = _liveMetrics.value.copy(elapsed = currentElapsed)
                }
            }

            val fpsHistory = mutableListOf<Int>()
            val fpsTimed = mutableListOf<TimedSample>()
            val memHistory = mutableListOf<Long>()
            val nativeHistory = mutableListOf<Long>()
            val javaHistory = mutableListOf<Long>()
            val cpuHistory = mutableListOf<Int>()
            val tempCpuHistory = mutableListOf<Double>()
            val tempGpuHistory = mutableListOf<Double>()
            val tempSkinHistory = mutableListOf<Double>()
            val frameTimeAvgHistory = mutableListOf<Double>()
            val allFrameTimes = mutableListOf<Double>()
            var totalJank = 0
            var totalStutter = 0
            var consecutiveAdbFailures = 0

            // v3.1.10: Tiered cadence to reduce capture overhead on the game.
            //
            // Rationale: `dumpsys meminfo <pkg>` blocks the game's main looper for 50-200ms
            // per call, and `dumpsys thermalservice` / sysfs thermal are medium cost. The
            // only truly cheap fast-tier metrics are FPS (via `dumpsys SurfaceFlinger
            // --latency` which is 5-20ms) and CPU (via `cat /proc/stat` which is 5-10ms).
            //
            // We still run the loop every 500ms but guard the expensive calls with counters
            // so they only fire on the slower cadence. Memory every 5s, thermal every 2s.
            // Battery is cheap so it stays on the fast tier.
            //
            // `getMissedFrames` (which does a full `dumpsys SurfaceFlinger` costing 150-500ms
            // and grabs the global compositor lock) is REMOVED from the live loop entirely.
            // It's now only called at session boundaries (start + end) for the final delta
            // that lands in the report. The live UI counter for frameDrops is updated using
            // `totalJank` which we already track per sample for free.
            //
            // Last-known-value pattern: the LiveMetrics update always receives something for
            // each field, even on iterations where a slow-tier metric didn't fire. We hold
            // the last observed mem/thermal values in locals and re-emit them.
            var iterCount = 0
            var lastMem: AdbBridge.MemSnapshot? = null
            var lastThermal = AdbBridge.ThermalSnapshot(-1.0, -1.0, -1.0, -1.0)

            while (!shouldStop) {
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                if (durationSeconds > 0 && elapsed >= durationSeconds) break
                if (shouldStop) break

                // Small delay between sampling cycles — the fast tier (FPS + CPU + battery)
                // takes ~30-50ms on a mid-range device, so real cadence is ~0.5-0.6s.
                delay(500)
                if (shouldStop) break

                // === FAST TIER (every iteration ~= every 500ms) ===
                // FPS via --latency (5-20ms), CPU via /proc/stat (5-10ms), battery (5-15ms)
                val frame = AdbBridge.captureFrames(device.id, pkg)
                if (shouldStop) break
                val cpu = AdbBridge.captureCpuPercent(device.id)
                if (shouldStop) break
                val battery = AdbBridge.getBatteryLevel(device.id)
                if (shouldStop) break

                // === MEDIUM TIER (every ~2s, i.e. every 4th iteration) ===
                // Thermal sensors — sysfs is fast-ish (~30-80ms) but multi-cat adds up.
                val runThermal = iterCount % 4 == 0
                if (runThermal) {
                    val t = AdbBridge.captureTemperature(device.id)
                    if (shouldStop) break
                    lastThermal = t
                }

                // === SLOW TIER (every ~5s, i.e. every 10th iteration) ===
                // `dumpsys meminfo <pkg>` is the worst offender: 200-800ms AND blocks the
                // game's main thread. Memory is a slow-changing signal, 5s is plenty.
                val runMem = iterCount % 10 == 0
                if (runMem) {
                    val m = AdbBridge.captureMemory(device.id, pkg)
                    if (shouldStop) break
                    if (m != null) lastMem = m
                }

                iterCount++

                // Device disconnect detection: if the fast tier returned all null/0/empty
                // the device is likely disconnected. We only need the fast-tier results
                // for this heuristic — the slow tiers may legitimately be idle.
                val allFailed = frame == null && cpu == 0 && battery == 0
                if (allFailed) {
                    consecutiveAdbFailures++
                    if (consecutiveAdbFailures >= 3) {
                        shouldStop = true
                        _captureError.value = "Dispositivo desconectado durante la captura"
                        break
                    }
                } else {
                    consecutiveAdbFailures = 0
                }

                val sampleSecond = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                val fps = frame?.fps ?: 0
                if (fps > 0) {
                    fpsHistory.add(fps)
                    fpsTimed.add(TimedSample(sampleSecond, fps.toDouble()))
                }
                val memNow = lastMem
                if (runMem && memNow != null) {
                    memHistory.add(memNow.totalMb)
                    nativeHistory.add(memNow.nativeMb)
                    javaHistory.add(memNow.javaMb)
                }
                if (cpu > 0) cpuHistory.add(cpu)
                if (runThermal) {
                    if (lastThermal.cpu > 0) tempCpuHistory.add(lastThermal.cpu)
                    if (lastThermal.gpu > 0) tempGpuHistory.add(lastThermal.gpu)
                    if (lastThermal.skin > 0) tempSkinHistory.add(lastThermal.skin)
                }
                if (frame != null && frame.avgFrameTime > 0) {
                    frameTimeAvgHistory.add(frame.avgFrameTime)
                    allFrameTimes.add(frame.avgFrameTime)
                }
                totalJank += frame?.jankCount ?: 0
                totalStutter += frame?.stutterCount ?: 0

                val currentElapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                _liveMetrics.value = LiveMetrics(
                    elapsed = currentElapsed, fps = fps,
                    avgFps = if (fpsHistory.isNotEmpty()) fpsHistory.average() else 0.0,
                    frameTime = frame?.avgFrameTime ?: 0.0,
                    cpu = cpu,
                    memMb = lastMem?.totalMb ?: 0,
                    nativeMb = lastMem?.nativeMb ?: 0,
                    javaMb = lastMem?.javaMb ?: 0,
                    tempCpu = lastThermal.cpu,
                    tempGpu = lastThermal.gpu,
                    tempBattery = lastThermal.battery,
                    tempSkin = lastThermal.skin,
                    jankCount = totalJank, stutterCount = totalStutter,
                    battery = battery,
                    // v3.1.10: frameDrops live counter replaced by totalJank. The final
                    // report number is computed post-loop from missedEnd - missedStart so
                    // precision is preserved; the live counter is now "jank count" which
                    // comes for free from the per-frame analysis in captureFrames.
                    frameDrops = totalJank,
                    fpsHistory = fpsHistory.toList(),
                    fpsTimed = fpsTimed.toList(),
                    memHistory = memHistory.toList(),
                    nativeHistory = nativeHistory.toList(),
                    javaHistory = javaHistory.toList(),
                    cpuHistory = cpuHistory.toList(),
                    tempCpuHistory = tempCpuHistory.toList(),
                    tempGpuHistory = tempGpuHistory.toList(),
                    tempSkinHistory = tempSkinHistory.toList(),
                    frameTimeHistory = frameTimeAvgHistory.toList(),
                    allFrameTimes = allFrameTimes.toList()
                )
            }

            // Capture actual session duration BEFORE cleanup
            val finalElapsed = if (durationSeconds > 0) {
                durationSeconds // Use the requested duration, not wall clock
            } else {
                ((System.currentTimeMillis() - startTime) / 1000).toInt()
            }

            // Stop recording and pull videos
            timerJob.cancel()
            recordJob?.cancel()
            AdbBridge.stopScreenRecord(recordProcess)
            recordProcess = null
            // v3.1.12: increased from 2000ms to 3000ms. The screenrecord binary on the
            // device needs time to flush the moov atom to /sdcard after receiving SIGTERM.
            // 2 seconds was insufficient on some devices (the Pixel XL produced corrupt
            // segments because the chain timer was killing screenrecord after only 1s of
            // post-stop wait — see recordJob delay below).
            delay(3000) // let last segment finalize on device
            val recordings = AdbBridge.pullRecordings(device.id, sessionId, videoDir)

            // Concatenate all segments into a single unified video file. screenrecord
            // has a hard 3-min/segment limit so longer sessions produce multiple files
            // (_0.mp4, _1.mp4, ...). v3.1.9 introduced concat to fix the 2:56 truncation;
            // v3.1.12 makes the concat resilient to corrupt segments (filters them out
            // before invoking ffmpeg) and the fallback uses `firstValidSegment` instead
            // of `first()` so the user never gets a path pointing at a corrupt _0.mp4.
            val videoPath: String = if (recordings.isNotEmpty()) {
                val unified = java.io.File(videoDir, "video_${sessionId}.mp4")
                val result = if (recordings.size > 1) {
                    AdbBridge.concatSegments(recordings, unified)
                } else {
                    // Single segment — validate it's playable, return null if corrupt
                    if (AdbBridge.isValidVideoFile(recordings.first())) recordings.first() else null
                }
                if (result != null) {
                    result.absolutePath
                } else {
                    // Concat failed AND no single valid segment. Surface a warning so the
                    // user knows the video is missing (not just empty), and try to find
                    // ANY valid segment in the original list as a last resort.
                    val anyValid = recordings.firstOrNull { AdbBridge.isValidVideoFile(it) }
                    if (anyValid != null) {
                        System.err.println("AppViewModel: concat failed, falling back to first valid segment: ${anyValid.name}")
                        _captureWarning.value = "El video se grabo solo parcialmente. Algunos segmentos estaban corruptos y se descartaron."
                        anyValid.absolutePath
                    } else {
                        System.err.println("AppViewModel: NO valid video segments produced (all ${recordings.size} corrupt)")
                        _captureWarning.value = "Los segmentos de video estan corruptos y no se pudieron unir. Las metricas si estan completas."
                        ""
                    }
                }
            } else {
                ""
            }

            // === FINALIZE ===
            if (!isWifiMode) AdbBridge.restoreCharging(device.id)
            val batteryEnd = AdbBridge.getBatteryLevel(device.id)
            val missedEnd = AdbBridge.getMissedFrames(device.id)

            val sorted = fpsHistory.sorted()
            val n = sorted.size
            val avgFps = if (n > 0) sorted.average().toInt() else 0
            val minFps = sorted.firstOrNull() ?: 0
            val maxFps = sorted.lastOrNull() ?: 0
            fun pct(p: Double) = if (n > 0) sorted[(n * p).toInt().coerceIn(0, n - 1)] else 0
            val p1 = pct(0.01); val p5 = pct(0.05); val p50 = pct(0.50); val p90 = pct(0.90); val p99 = pct(0.99)

            val ftSorted = allFrameTimes.sorted()
            val p99ft = if (ftSorted.isNotEmpty()) ftSorted[(ftSorted.size * 0.99).toInt().coerceIn(0, ftSorted.size - 1)] else 0.0

            val peakMem = memHistory.maxOrNull() ?: 0
            val avgCpu = if (cpuHistory.isNotEmpty()) cpuHistory.average().toInt() else 0
            val maxCpu = cpuHistory.maxOrNull() ?: 0
            val maxTempCpu = tempCpuHistory.maxOrNull() ?: 0.0
            val maxTempGpu = tempGpuHistory.maxOrNull() ?: 0.0
            val totalDrops = missedEnd - missedStart

            // Grade
            val problems = mutableListOf<String>()
            var score = 100
            when {
                avgFps < 30 -> { score -= 35; problems.add("FPS promedio $avgFps - Muy bajo para una experiencia fluida") }
                avgFps < 45 -> { score -= 20; problems.add("FPS promedio $avgFps - Se nota falta de fluidez en escenas con accion") }
                avgFps < 55 -> score -= 10
            }
            if (p1 < 20) { score -= 15; problems.add("P1 FPS: $p1 - Caidas severas que causan congelaciones visibles") }
            else if (p1 < 30) score -= 8
            if (totalDrops > 30) { score -= 12; problems.add("$totalDrops frames perdidos por el compositor grafico") }
            if (peakMem > 2000) { score -= 12; problems.add("Pico de memoria ${peakMem}MB - Riesgo de cierre forzado en dispositivos con poca RAM") }
            else if (peakMem > 1500) { score -= 6; problems.add("Memoria alta: ${peakMem}MB") }
            if (maxTempCpu > 45) { score -= 12; problems.add("Temperatura CPU ${maxTempCpu.toInt()}C - Thermal throttling activo, reduce rendimiento") }
            if (avgCpu > 85) { score -= 12; problems.add("CPU saturada al ${avgCpu}% - Cuello de botella principal") }
            val grade = when { score >= 85 -> 'A'; score >= 70 -> 'B'; score >= 55 -> 'C'; score >= 40 -> 'D'; else -> 'F' }

            // Device-specific grade
            val tier = com.gameperf.desktop.core.HardwareScoring.detectTier(_deviceInfo.value?.gpu ?: "")
            val (deviceGrade, deviceScore) = com.gameperf.desktop.core.HardwareScoring.calculateDeviceGrade(avgFps, p1, tier, problems)

            // Snapshot markers before generating report
            val sessionMarkers = _markers.value

            // Generate HTML report (wrapped in try-catch to avoid crash on report failure)
            val reportPath = try {
                ReportGenerator.generate(
                    pkg = pkg, info = _deviceInfo.value, grade = grade, score = score, duration = finalElapsed,
                    fpsHistory = fpsHistory, memHistory = memHistory, nativeHistory = nativeHistory,
                    javaHistory = javaHistory, cpuHistory = cpuHistory,
                    tempCpuHistory = tempCpuHistory, tempGpuHistory = tempGpuHistory, tempSkinHistory = tempSkinHistory,
                    allFrameTimes = allFrameTimes,
                    avgFps = avgFps, minFps = minFps, maxFps = maxFps,
                    p1 = p1, p5 = p5, p50 = p50, p90 = p90, p99 = p99,
                    avgFrameTime = if (allFrameTimes.isNotEmpty()) allFrameTimes.average() else 0.0,
                    p99FrameTime = p99ft,
                    peakMem = peakMem, avgCpu = avgCpu, maxCpu = maxCpu,
                    maxTempCpu = maxTempCpu, maxTempGpu = maxTempGpu,
                    batteryStart = batteryStart, batteryEnd = batteryEnd,
                    frameDrops = totalDrops, jank = totalJank, stutter = totalStutter,
                    problems = problems, isWifi = isWifiMode,
                    deviceGrade = deviceGrade, deviceScore = deviceScore, deviceTier = tier.label,
                    fpsTimestamps = fpsTimed.map { it.second to it.value.toInt() },
                    markers = sessionMarkers
                )
            } catch (e: Exception) {
                System.err.println("Error generating report: ${e.message}")
                ""
            }

            _result.value = SessionResult(
                gamePackage = pkg, deviceModel = _deviceInfo.value?.model ?: device.model,
                duration = finalElapsed, grade = grade,
                avgFps = avgFps, minFps = minFps, maxFps = maxFps,
                p1Fps = p1, p5Fps = p5, p50Fps = p50, p90Fps = p90, p99Fps = p99,
                avgFrameTime = if (allFrameTimes.isNotEmpty()) allFrameTimes.average() else 0.0,
                p99FrameTime = p99ft,
                peakMemMb = peakMem, avgCpu = avgCpu, maxCpu = maxCpu,
                maxTempCpu = maxTempCpu, maxTempGpu = maxTempGpu,
                batteryStart = batteryStart, batteryEnd = batteryEnd,
                batteryDrain = batteryStart - batteryEnd,
                frameDrops = totalDrops, totalJank = totalJank, totalStutter = totalStutter,
                problems = problems, reportPath = reportPath, isWifi = isWifiMode,
                videoPath = videoPath,
                deviceGrade = deviceGrade, deviceScore = deviceScore, deviceTier = tier.label,
                markers = sessionMarkers
            )

            // P95 frame time
            val p95ft = if (ftSorted.isNotEmpty()) ftSorted[(ftSorted.size * 0.95).toInt().coerceIn(0, ftSorted.size - 1)] else 0.0

            // Save to history. The legacy overload returns the entries that the new
            // hard 5-session retention limit pushed off the bottom of the list. We
            // forward each evicted entry to FileCleanup so its HTML report and all
            // video segments disappear from disk in the same atomic step.
            val captureTag = _sessionTag.value
            val captureCompetitor = _competitorName.value
            val evicted = SessionHistory.addEntry(
                gamePackage = pkg, deviceModel = _deviceInfo.value?.model ?: device.model,
                grade = grade, deviceGrade = deviceGrade,
                avgFps = avgFps, duration = finalElapsed,
                reportPath = reportPath, videoPath = videoPath,
                tag = captureTag, competitorName = captureCompetitor,
                p1Fps = p1, p5Fps = p5,
                avgFrameTime = if (allFrameTimes.isNotEmpty()) allFrameTimes.average() else 0.0,
                p95FrameTime = p95ft, p99FrameTime = p99ft,
                peakMemMb = peakMem, avgCpu = avgCpu,
                maxTemp = maxTempCpu, score = score,
                markers = sessionMarkers
            )
            evicted.forEach { FileCleanup.deleteSessionFiles(it) }
            _history.value = SessionHistory.load()

            captureStartTime = 0L
            _isCapturing.value = false
            _screen.value = AppScreen.RESULTS
        }
    }

    fun stopCapture() {
        shouldStop = true
        _statusMessage.value = "Deteniendo captura..."
    }

    fun clearCaptureError() {
        _captureError.value = null
    }

    fun clearCaptureWarning() {
        _captureWarning.value = null
    }

    /**
     * v3.1.13 — Manually re-run the legacy-video repair logic that already runs once
     * automatically in [init]. Exposed as a button in [HomeScreen] so users can
     * trigger it after a power loss / app crash that left segments unconcatenated.
     *
     * Updates [statusMessage] with the outcome. Never throws — failures are caught
     * inside `FileCleanup.repairTruncatedVideos` and reported via stderr.
     *
     * Note: `repairTruncatedVideos` returns the list of *successfully repaired*
     * entries (NOT a Result struct). It does not expose a separate "checked" count.
     * The status message reflects only the repaired count, which is what the user
     * cares about. If the list is empty, we cannot distinguish "nothing needed
     * fixing" from "fixing failed for everything we tried" without instrumenting
     * FileCleanup further (out of scope for v3.1.13).
     */
    fun repairOldVideos() {
        scope.launch {
            _statusMessage.value = "Reparando videos antiguos..."
            val repaired = withContext(Dispatchers.IO) {
                try {
                    val snapshot = SessionHistory.load()
                    val result = FileCleanup.repairTruncatedVideos(snapshot)
                    result.forEach { SessionHistory.updateEntry(it) }
                    if (result.isNotEmpty()) {
                        _history.value = SessionHistory.load()
                    }
                    result.size
                } catch (t: Throwable) {
                    System.err.println("AppViewModel.repairOldVideos: ${t.message}")
                    -1
                }
            }
            _statusMessage.value = when {
                repaired < 0 -> "No se pudieron reparar los videos (revisa los logs)"
                repaired == 0 -> "No hay videos antiguos por reparar"
                repaired == 1 -> "Se reparó 1 video"
                else -> "Se repararon $repaired videos"
            }
        }
    }

    /** Place a marker at the current capture second (used during live capture). */
    fun addMarker(type: MarkerType, note: String = "") {
        if (!_isCapturing.value || captureStartTime == 0L) return
        val elapsedMs = System.currentTimeMillis() - captureStartTime
        _markers.value = _markers.value + SessionMarker(
            timestampMs = elapsedMs,
            type = type,
            title = type.label,
            note = note,
            colorHex = type.colorHex
        )
    }

    /** Add a marker at a specific timestamp (used from the results timeline). */
    fun addTimelineMarker(timestampMs: Long, title: String, note: String, colorHex: String, type: MarkerType) {
        _markers.value = _markers.value + SessionMarker(
            timestampMs = timestampMs,
            type = type,
            title = title,
            note = note,
            colorHex = colorHex
        )
        // Update the result to reflect new markers
        _result.value = _result.value.copy(markers = _markers.value)
    }

    /** Edit an existing marker by its id. */
    fun editMarker(id: String, title: String, note: String, colorHex: String, type: MarkerType) {
        _markers.value = _markers.value.map { m ->
            if (m.id == id) m.copy(title = title, note = note, colorHex = colorHex, type = type) else m
        }
        _result.value = _result.value.copy(markers = _markers.value)
    }

    /** Delete a marker by its id. */
    fun deleteMarker(id: String) {
        _markers.value = _markers.value.filter { it.id != id }
        _result.value = _result.value.copy(markers = _markers.value)
    }

    // ===== Video Playback =====

    fun setVideoPosition(positionMs: Long) { _videoPosition.value = positionMs }
    fun setVideoPlaying(playing: Boolean) { _isVideoPlaying.value = playing }
    fun setVideoDuration(durationMs: Long) { _videoDuration.value = durationMs }
    fun setPlaybackSpeed(speed: Double) { _playbackSpeed.value = speed }

    private fun openFile(path: String) {
        if (path.isEmpty()) return
        val file = File(path)
        if (!file.exists()) return
        scope.launch(Dispatchers.IO) {
            // Try Desktop.open first (works for most file types)
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(file)
                    return@launch
                }
            } catch (_: Exception) {}
            // Fallback to OS-specific command
            try {
                val os = System.getProperty("os.name").lowercase()
                when {
                    os.contains("mac") -> ProcessBuilder("open", file.absolutePath).start()
                    os.contains("win") -> ProcessBuilder("cmd", "/c", "start", "", file.absolutePath).start()
                    else -> ProcessBuilder("xdg-open", file.absolutePath).start()
                }
            } catch (_: Exception) {}
        }
    }

    fun openReport() { openFile(_result.value.reportPath) }

    fun openVideo() { openFile(_result.value.videoPath) }

    fun renameHistoryEntry(id: String, newName: String) {
        SessionHistory.updateName(id, newName)
        _history.value = SessionHistory.load()
    }

    fun deleteHistoryEntry(id: String) {
        // Atomic manual delete: remove the JSON entry AND its physical files (HTML
        // report + every video segment matching the sessionId). The trash button
        // used to leak orphans because the previous impl only mutated history.json.
        val removed = SessionHistory.deleteEntry(id)
        if (removed != null) {
            FileCleanup.deleteSessionFiles(removed)
        }
        // Also remove from comparison selection if present
        _selectedForComparison.value = _selectedForComparison.value - id
        _history.value = SessionHistory.load()
    }

    fun openHistoryReport(entry: SessionHistory.HistoryEntry) {
        openFile(entry.reportPath)
    }

    fun openHistoryVideo(entry: SessionHistory.HistoryEntry) {
        openFile(entry.videoPath)
    }

    fun goHome() {
        captureJob?.cancel()
        shouldStop = true
        _screen.value = AppScreen.HOME
        _liveMetrics.value = LiveMetrics()
        _markers.value = emptyList()
        _selectedForComparison.value = emptySet()
        _videoPosition.value = 0L
        _isVideoPlaying.value = false
        _videoDuration.value = 0L
        _playbackSpeed.value = 1.0
        recordJob?.cancel()
        AdbBridge.stopScreenRecord(recordProcess)
        recordProcess = null
        _history.value = SessionHistory.load()
        refreshDevices()
    }

    // ===== Session Tagging =====

    fun setSessionTag(tag: SessionHistory.SessionTag) {
        _sessionTag.value = tag
    }

    fun setCompetitorName(name: String) {
        _competitorName.value = name
    }

    fun updateHistoryTag(id: String, tag: SessionHistory.SessionTag, competitorName: String = "") {
        SessionHistory.updateTag(id, tag, competitorName)
        _history.value = SessionHistory.load()
    }

    // ===== Comparison =====

    fun toggleComparisonSelection(entryId: String) {
        val current = _selectedForComparison.value.toMutableSet()
        if (current.contains(entryId)) current.remove(entryId) else current.add(entryId)
        _selectedForComparison.value = current
    }

    fun clearComparisonSelection() {
        _selectedForComparison.value = emptySet()
    }

    fun canCompare(): Boolean = _selectedForComparison.value.size >= 2

    fun getSelectedEntries(): List<SessionHistory.HistoryEntry> {
        val selected = _selectedForComparison.value
        return _history.value.filter { it.id in selected }
    }

    fun goToComparison() {
        _screen.value = AppScreen.COMPARISON
    }

    fun generateComparisonReport(entries: List<SessionHistory.HistoryEntry>): String {
        // Comparisons live in java.io.tmpdir (not ~/GamePerf Reports) so they never
        // pollute the user's reports folder. Each generated path is tracked so
        // cleanup() can sweep them on window close.
        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val path = ReportGenerator.generateComparison(entries, tmpDir)
        if (path.isNotEmpty()) {
            _tempComparisons.add(path)
        }
        return path
    }

    /** Reset the export status banner to Idle. Called by [ExportBanner] after auto-dismiss. */
    fun resetExportStatus() {
        _exportStatus.value = ExportStatus.Idle
    }

    // ===== PDF Export — sourced from current session result =====

    /**
     * Export the current ResultsScreen report to a user-chosen PDF location. Drives
     * the [exportStatus] flow through the full lifecycle so the UI can show the
     * banner / preparing-engine modal at the right moments.
     */
    fun exportCurrentReportToPdf() {
        val current = _result.value
        if (current.reportPath.isEmpty()) {
            _exportStatus.value = ExportStatus.Error("No hay informe HTML para exportar.")
            return
        }
        val defaultName = "informe_${safePkg(current.gamePackage)}_${safeDevice(current.deviceModel)}_${shortDate(currentDateString())}.pdf"
        runExportPipeline(current.reportPath, defaultName)
    }

    /**
     * Export a history entry's HTML report to PDF. Same pipeline as
     * [exportCurrentReportToPdf] but sourced from the entry instead of `_result`.
     */
    fun exportHistoryEntryToPdf(entry: SessionHistory.HistoryEntry) {
        if (entry.reportPath.isEmpty()) {
            _exportStatus.value = ExportStatus.Error("Esta entrada no tiene informe HTML.")
            return
        }
        val defaultName = "informe_${safePkg(entry.gamePackage)}_${safeDevice(entry.deviceModel)}_${shortDate(entry.date)}.pdf"
        runExportPipeline(entry.reportPath, defaultName)
    }

    /**
     * Export the comparison HTML at [htmlPath] to PDF. The path is typically the one
     * returned by [generateComparisonReport] (lives in `java.io.tmpdir`).
     */
    fun exportComparisonToPdf(htmlPath: String) {
        if (htmlPath.isEmpty()) {
            _exportStatus.value = ExportStatus.Error("No hay comparativa generada para exportar.")
            return
        }
        val defaultName = "comparativa_${shortDate(LocalDate.now().toString())}.pdf"
        runExportPipeline(htmlPath, defaultName)
    }

    /**
     * Shared export pipeline used by all three exportXxxToPdf entry points.
     * Handles: status transitions, file picker, blocking PdfExporter call on
     * Dispatchers.IO, and exhaustive error wrapping. The pipeline goes directly
     * from Idle -> InProgress -> Success | Error (no intermediate state).
     *
     * The "no browser detected" branch maps to an [ExportStatus.Error] with an
     * inline action button payload so the banner can offer a "Descargar Chrome"
     * link.
     */
    private fun runExportPipeline(htmlPath: String, defaultFileName: String) {
        scope.launch {
            _exportStatus.value = ExportStatus.InProgress
            val target: File? = try {
                PickerUtils.pickSaveFile(
                    title = "Guardar informe PDF",
                    defaultName = defaultFileName,
                    extension = "pdf"
                )
            } catch (t: Throwable) {
                _exportStatus.value = ExportStatus.Error("No se pudo abrir el selector: ${t.message}")
                return@launch
            }
            if (target == null) {
                _exportStatus.value = ExportStatus.Idle
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    PdfExporter.exportHtmlToPdf(htmlPath, target)
                }
                _exportStatus.value = ExportStatus.Success(target.absolutePath)
            } catch (e: PdfExporter.PdfExportException) {
                val msg = e.message ?: "Error desconocido al exportar PDF"
                val isNoBrowser = msg.startsWith("No se encontró")
                _exportStatus.value = ExportStatus.Error(
                    message = msg,
                    actionUrl = if (isNoBrowser) "https://www.google.com/chrome/" else null,
                    actionLabel = if (isNoBrowser) "Descargar Chrome" else null,
                )
            } catch (e: Throwable) {
                _exportStatus.value = ExportStatus.Error("Error inesperado: ${e.message}")
            }
        }
    }

    // ===== Filename helpers =====
    // Match the convention used by ReportGenerator.generate's HTML filenames so that
    // exported PDFs are visually grouped with their source HTMLs in file managers.

    private fun safePkg(pkg: String): String =
        pkg.replace('.', '_').replace(Regex("[^A-Za-z0-9_]"), "").takeLast(30)

    private fun safeDevice(device: String): String =
        device.replace(' ', '_').replace(Regex("[^A-Za-z0-9_]"), "")

    /**
     * Compress a date string like "31/03/2026 12:21" or "2026-03-31 12:21:49" or
     * "2026-04-06" into "YYYYMMDD". Tolerant of either dd/MM/yyyy or yyyy-MM-dd input.
     */
    private fun shortDate(date: String): String {
        if (date.isEmpty()) return ""
        // dd/MM/yyyy ... -> yyyyMMdd
        val slashRegex = Regex("""^(\d{2})/(\d{2})/(\d{4})""")
        slashRegex.find(date)?.let { m ->
            return "${m.groupValues[3]}${m.groupValues[2]}${m.groupValues[1]}"
        }
        // yyyy-MM-dd ... -> yyyyMMdd
        return date.take(10).replace("-", "")
    }

    private fun currentDateString(): String =
        java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(java.util.Date())
}
