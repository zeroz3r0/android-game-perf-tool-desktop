package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.core.AdbBridgeApi
import com.gameperf.desktop.core.AdbVersion
import com.gameperf.desktop.core.AppVersion
import com.gameperf.desktop.core.AutoUpdater
import com.gameperf.desktop.core.CURRENT_VERSION
import com.gameperf.desktop.core.ConnectFailureReason
import com.gameperf.desktop.core.ConnectResult
import com.gameperf.desktop.core.FileCleanup
import com.gameperf.desktop.core.MdnsService
import com.gameperf.desktop.core.MdnsServiceType
import com.gameperf.desktop.core.PairFailureReason
import com.gameperf.desktop.core.PairResult
import com.gameperf.desktop.core.RealAdbBridge
import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.core.model.Device
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.bridge.AndroidBridge
import com.gameperf.desktop.core.ios.IosBridge
import com.gameperf.desktop.core.ios.SidecarClient
import com.gameperf.desktop.core.ios.SidecarLifecycle
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
    // v4.1.0: sentinel changed from -1.0 to NaN for thermal fields.
    // NaN propagates safely through arithmetic (NaN + x = NaN, max(NaN, x) = NaN)
    // and is trivially detectable via .isNaN(). The old -1.0 sentinel was dangerous
    // because -1.0 is a valid Double that silently corrupts averages and comparisons
    // (e.g. maxOf(-1.0, 40.0) = 40.0 — looks correct but hides the "unavailable" state).
    val tempCpu: Double = Double.NaN,
    val tempGpu: Double = Double.NaN,
    val tempBattery: Double = Double.NaN,
    val tempSkin: Double = Double.NaN,
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

/**
 * v3.1.14 — [AppViewModel] now takes an [AdbBridgeApi] through its constructor so
 * tests can inject a `FakeAdbBridge` and exercise `startSegmentWithRetry` (and
 * any other capture-path logic) without having to mock the `object AdbBridge`
 * singleton. Production callers can keep using the no-arg form; [Main] does.
 *
 * Rationale: the v3.1.13 gap. Before v3.1.14, validateScreenRecordProcess was
 * testable (pure) but startSegmentWithRetry was not, because it called
 * `AdbBridge.startScreenRecord` directly. With this seam, a test can script
 * scenarios like "first COMPACT dies, then STANDARD retry also dies" and
 * assert the expected null return + recordChainFailures increment.
 */
class AppViewModel(
    private val adb: AdbBridgeApi = RealAdbBridge(),
) {
    // H-2: cap history lists to prevent unbounded memory growth in long sessions.
    // 7200 entries = 2 hours at 1Hz polling (more than any practical session).
    // 500_000 frame times = ~2.3 hours at 60fps.
    companion object {
        internal const val MAX_HISTORY_SIZE = 7_200
        internal const val MAX_FRAME_TIMES_SIZE = 500_000
    }
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
        // v4.0.0: stop iOS sidecar if running
        try { sidecarLifecycle?.stop() } catch (_: Exception) { }
        scope.cancel()
    }

    private val _screen = MutableStateFlow(AppScreen.HOME)
    val screen: StateFlow<AppScreen> = _screen

    private val _adbAvailable = MutableStateFlow(false)
    val adbAvailable: StateFlow<Boolean> = _adbAvailable

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val _selectedDevice = MutableStateFlow<Device?>(null)
    val selectedDevice: StateFlow<Device?> = _selectedDevice

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo

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

    // ===== Video Playback (delegated v4.1.0) =====
    private val videoDelegate = VideoDelegate()
    val videoPosition: StateFlow<Long> = videoDelegate.videoPosition
    val isVideoPlaying: StateFlow<Boolean> = videoDelegate.isVideoPlaying
    val videoDuration: StateFlow<Long> = videoDelegate.videoDuration
    val playbackSpeed: StateFlow<Double> = videoDelegate.playbackSpeed

    private val _history = MutableStateFlow<List<SessionHistory.HistoryEntry>>(emptyList())
    val history: StateFlow<List<SessionHistory.HistoryEntry>> = _history

    // ===== Google Drive Sync (v4.2) =====

    sealed class DriveSyncState {
        object Disconnected : DriveSyncState()
        object Connecting : DriveSyncState()
        data class Connected(val email: String) : DriveSyncState()
        data class Error(val message: String) : DriveSyncState()
    }

    sealed class DriveOp {
        object Idle : DriveOp()
        data class Uploading(val sessionName: String) : DriveOp()
        data class Downloading(val sessionName: String) : DriveOp()
        object Refreshing : DriveOp()
    }

    private val driveSync = com.gameperf.desktop.cloud.DriveSync(
        configDir = java.io.File(System.getProperty("user.home"), ".gameperf")
    )

    private val _driveState = MutableStateFlow<DriveSyncState>(
        if (driveSync.isAuthenticated && driveSync.hasCredentials)
            DriveSyncState.Connected(driveSync.userEmail)
        else DriveSyncState.Disconnected
    )
    val driveState: StateFlow<DriveSyncState> = _driveState

    private val _remoteSessions = MutableStateFlow<List<com.gameperf.desktop.cloud.DriveSync.RemoteSession>>(emptyList())
    val remoteSessions: StateFlow<List<com.gameperf.desktop.cloud.DriveSync.RemoteSession>> = _remoteSessions

    private val _driveOp = MutableStateFlow<DriveOp>(DriveOp.Idle)
    val driveOp: StateFlow<DriveOp> = _driveOp

    /** Folder ID shared by the team — empty until configured. */
    val driveTeamFolderId: String get() = driveSync.teamFolderId
    val driveHasCredentials: Boolean get() = driveSync.hasCredentials

    // ===== Session Tagging =====
    private val _sessionTag = MutableStateFlow(SessionHistory.SessionTag.OUR_GAME)
    val sessionTag: StateFlow<SessionHistory.SessionTag> = _sessionTag

    private val _competitorName = MutableStateFlow("")
    val competitorName: StateFlow<String> = _competitorName

    // ===== Comparison =====
    private val _selectedForComparison = MutableStateFlow<Set<String>>(emptySet())
    val selectedForComparison: StateFlow<Set<String>> = _selectedForComparison

    // ===== Auto-Update (delegated v4.1.0) =====
    private val updateDelegate = UpdateDelegate(scope) { msg -> _statusMessage.value = msg }
    val updateAvailable: StateFlow<AutoUpdater.ReleaseInfo?> = updateDelegate.updateAvailable
    val updateProgress: StateFlow<Float?> = updateDelegate.updateProgress
    val updateError: StateFlow<String?> = updateDelegate.updateError

    // ===== Capture Error (device disconnect, etc.) =====
    private val _captureError = MutableStateFlow<String?>(null)
    val captureError: StateFlow<String?> = _captureError
    // v3.1.11: non-fatal warnings (capture continues, but the user should know).
    // Used for cases like "video recording failed but metrics succeeded".
    private val _captureWarning = MutableStateFlow<String?>(null)
    val captureWarning: StateFlow<String?> = _captureWarning

    // ===== PDF Export (delegated v4.1.0) =====
    // ExportStatus sealed class moved to ExportDelegate.kt
    private val exportDelegate = ExportDelegate(scope)
    val exportStatus: StateFlow<ExportDelegate.ExportStatus> = exportDelegate.exportStatus

    /**
     * Tracks comparison HTMLs written to `java.io.tmpdir` during this run so they can
     * be deleted by [cleanup] on window close. Synchronized for safety against
     * concurrent generations from different threads.
     */
    private val _tempComparisons: MutableList<String> = Collections.synchronizedList(mutableListOf())

    // ===== iOS Sidecar =====
    private var sidecarLifecycle: SidecarLifecycle? = null
    private var iosBridge: IosBridge? = null

    private val _iosAvailable = MutableStateFlow(false)
    val iosAvailable: StateFlow<Boolean> = _iosAvailable

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
     *
     * v3.1.14: visibility widened from `private` to `internal` so unit tests can
     * drive it end-to-end via a [AdbBridgeApi] fake. No production caller outside
     * this class — still an implementation detail.
     */
    internal suspend fun startSegmentWithRetry(
        deviceId: String,
        sessionId: String,
        segment: Int,
        profile: AdbBridge.ScreenRecordProfile
    ): Process? {
        val firstAttempt = adb.startScreenRecord(deviceId, sessionId, segment, profile)
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
            val retry = adb.startScreenRecord(
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
            _adbAvailable.value = adb.isAvailable()
            if (!_adbAvailable.value) {
                _statusMessage.value = "ADB no encontrado. Instala Android SDK."
                return@launch
            }
            _statusMessage.value = "ADB disponible. Buscando dispositivos..."
            refreshDevices()
        }
        startDevicePolling()
        checkForUpdates()
        // v3.2.0 — Wireless ADB (D-11): non-blocking capability check on the
        // local platform-tools version. Used by WifiPanelContent to show a
        // banner when adb < 33 (mDNS auto-connect not available). Zero effect
        // on the USB happy path because it only runs once, on a background
        // dispatcher, and the resulting StateFlow is read only when the WiFi
        // panel is open.
        // v4.1.0: adbVersion check moved to WifiDelegate init.
        // v4.0.0 — iOS sidecar: attempt to start the pymobiledevice3 sidecar
        // in background. If Python is missing or sidecar fails to start, iOS
        // is silently unavailable (Android continues working normally).
        scope.launch(Dispatchers.IO) {
            tryInitIosSidecar()
        }
    }

    /**
     * v4.0.0 — Try to start the iOS sidecar. Non-blocking, non-fatal.
     * If successful, [iosBridge] is set and [_iosAvailable] becomes true.
     * Device polling will then include iOS devices in the list.
     */
    private fun tryInitIosSidecar() {
        try {
            if (!SidecarLifecycle.isPythonAvailable()) {
                System.err.println("AppViewModel: Python 3 not found, iOS support disabled")
                return
            }

            // Locate sidecar directory relative to the app
            val sidecarDir = findSidecarDir() ?: run {
                System.err.println("AppViewModel: sidecar/ directory not found, iOS support disabled")
                return
            }

            val lifecycle = SidecarLifecycle(sidecarDir)
            val started = lifecycle.start(scope)
            if (started) {
                sidecarLifecycle = lifecycle
                iosBridge = IosBridge(lifecycle.client)
                _iosAvailable.value = true
                System.err.println("AppViewModel: iOS sidecar started on port, iOS support enabled")
            } else {
                System.err.println("AppViewModel: iOS sidecar failed to start: ${lifecycle.lastError}")
            }
        } catch (e: Exception) {
            System.err.println("AppViewModel: iOS sidecar init error: ${e.message}")
        }
    }

    /**
     * Find the sidecar/ directory. Checks:
     * 1. Next to the JAR (production)
     * 2. Project root (development)
     * 3. ~/.gameperf/sidecar/ (installed)
     */
    /**
     * Find the sidecar/ directory containing the Python package.
     *
     * v4.1.0: expanded search paths to cover all installation types:
     * - macOS .app bundle: Contents/app/sidecar/ (next to the JARs)
     * - macOS .app bundle: Contents/Resources/sidecar/ (jpackage resources)
     * - Fat JAR standalone: sidecar/ next to the JAR
     * - Development: sidecar/ in the project root (CWD)
     * - User install: ~/.gameperf/sidecar/
     */
    private fun findSidecarDir(): String? {
        val candidates = mutableListOf<java.io.File>()

        // macOS .app bundle: the JAR lives in Contents/app/, sidecar can be sibling
        // Also check Contents/Resources/ which is the jpackage resource dir
        val appBundlePath = System.getProperty("jpackage.app-path")
        if (appBundlePath != null) {
            val contentsDir = java.io.File(appBundlePath).parentFile?.parentFile // Contents/
            if (contentsDir != null) {
                candidates += java.io.File(contentsDir, "app/sidecar")
                candidates += java.io.File(contentsDir, "Resources/sidecar")
                candidates += java.io.File(contentsDir, "sidecar")
            }
        }

        // Development: project root (CWD)
        candidates += java.io.File("sidecar")
        // Production: next to user.dir
        candidates += java.io.File(System.getProperty("user.dir"), "sidecar")
        // User home install
        candidates += java.io.File(System.getProperty("user.home"), ".gameperf/sidecar")
        // Next to the JAR (from java.class.path)
        val classPath = System.getProperty("java.class.path", "")
        val jarDir = classPath.split(java.io.File.pathSeparator)
            .firstOrNull { it.endsWith(".jar") }
            ?.let { java.io.File(it).parentFile }
        if (jarDir != null) {
            candidates += java.io.File(jarDir, "sidecar")
        }

        val exeName = if (System.getProperty("os.name")?.lowercase()?.contains("win") == true)
            "gameperf-sidecar.exe" else "gameperf-sidecar"
        val found = candidates.firstOrNull { dir ->
            dir.isDirectory && (
                java.io.File(dir, "gameperf_sidecar/__init__.py").exists() ||  // Python source mode
                java.io.File(dir, exeName).exists()                             // PyInstaller binary mode
            )
        }
        if (found != null) {
            System.err.println("AppViewModel: sidecar found at ${found.absolutePath}")
        } else {
            System.err.println("AppViewModel: sidecar NOT found. Searched: ${candidates.map { it.absolutePath }}")
        }
        return found?.absolutePath
    }

    // ===== Auto-Update (delegated v4.1.0) =====

    fun checkForUpdates() = updateDelegate.checkForUpdates()
    fun downloadAndApplyUpdate() = updateDelegate.downloadAndApplyUpdate()
    fun dismissUpdate() = updateDelegate.dismissUpdate()

    private fun startDevicePolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                delay(3000)
                if (_screen.value == AppScreen.HOME) {
                    val adbDevs = adb.listDevices()
                    val androidDevs = adbDevs.map { Device(id = it.id, model = it.model, platform = DevicePlatform.ANDROID, isWifi = it.isWifi) }
                    val iosDevs = try { iosBridge?.listDevices() ?: emptyList() } catch (_: Exception) { emptyList() }
                    val devs = androidDevs + iosDevs
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
            // v4.1.0: adb.listDevices() now returns core.model.Device directly (no conversion needed)
            val androidDevs = adb.listDevices()
            // v4.0.0: merge iOS devices if sidecar is available
            val iosDevs = try {
                iosBridge?.listDevices() ?: emptyList()
            } catch (_: Exception) { emptyList() }
            val devs = androidDevs + iosDevs
            _devices.value = devs
            if (devs.isNotEmpty() && _selectedDevice.value == null) {
                selectDevice(devs.first())
            } else if (devs.isEmpty()) {
                _selectedDevice.value = null
                _deviceInfo.value = null
                _gamePackage.value = null
                _statusMessage.value = "Conecta un dispositivo Android o iOS por USB"
            }
        }
    }

    fun selectDevice(device: Device) {
        scope.launch {
            _selectedDevice.value = device
            _isWifi.value = device.isWifi
            _statusMessage.value = "Conectado a ${device.model}. Leyendo specs..."

            if (device.platform == DevicePlatform.IOS) {
                // iOS device — use IosBridge for info
                val iosInfo = iosBridge?.getDeviceInfo(device.id)
                _deviceInfo.value = iosInfo ?: DeviceInfo(
                    device.model, "Apple", "Unknown", "Apple GPU", "Unknown",
                    0, "Unknown", "Unknown", DevicePlatform.IOS,
                )
                _statusMessage.value = "Buscando juego en primer plano..."
                _gamePackage.value = iosBridge?.detectGame(device.id)
                _statusMessage.value = if (_gamePackage.value != null) "Listo para capturar"
                    else "No se detecto juego. Abre un juego en el iPhone y pulsa Refrescar."
            } else {
                // Android device — use AdbBridgeApi
                // v4.1.0: adb.getDeviceInfo() now returns core.model.DeviceInfo directly
                _deviceInfo.value = adb.getDeviceInfo(device.id)
                _statusMessage.value = "Buscando juego en primer plano..."
                _gamePackage.value = adb.detectGame(device.id)
                _statusMessage.value = if (_gamePackage.value != null) "Listo para capturar"
                    else "No se detecto juego. Abre un juego y pulsa Refrescar."
            }
        }
    }

    fun refreshGame() {
        val device = _selectedDevice.value ?: return
        scope.launch {
            _statusMessage.value = "Buscando juego..."
            _gamePackage.value = adb.detectGame(device.id)
            _statusMessage.value = if (_gamePackage.value != null) "Listo para capturar" else "No se detecto juego."
        }
    }

    // ===== WiFi Mode =====

    fun switchToWifi() {
        val device = _selectedDevice.value ?: return
        if (device.isWifi) return
        // H-3: run on IO dispatcher — switchToWifi contains Thread.sleep(2000)
        // and blocking exec() calls that would starve Dispatchers.Default.
        scope.launch(Dispatchers.IO) {
            _wifiStatus.value = "Activando WiFi ADB..."
            val wifiId = adb.switchToWifi(device.id)
            if (wifiId != null) {
                _wifiStatus.value = "Conectado via WiFi: $wifiId\nDesconecta el cable USB para medir bateria real."
                // Wait and refresh
                delay(3000)
                val adbDevs = adb.listDevices()
                val devs = adbDevs.map { Device(id = it.id, model = it.model, platform = DevicePlatform.ANDROID, isWifi = it.isWifi) }
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
        val isIosDevice = device.platform == DevicePlatform.IOS

        _screen.value = AppScreen.CAPTURING
        _isCapturing.value = true
        _liveMetrics.value = LiveMetrics()
        _markers.value = emptyList()
        _captureError.value = null
        _captureWarning.value = null
        shouldStop = false
        recordChainFailures = 0  // v3.1.13: reset diagnostic counter per capture
        if (!isIosDevice) adb.resetSessionState()

        captureJob = scope.launch {
            val batteryStart = if (isIosDevice) {
                iosBridge?.getBatteryLevel(device.id) ?: 100
            } else {
                adb.getBatteryLevel(device.id)
            }
            val missedStart = if (isIosDevice) 0 else adb.getMissedFrames(device.id)
            val isWifiMode = _isWifi.value
            if (!isWifiMode && !isIosDevice) adb.disableCharging(device.id)

            // Start video recording and metrics at the same moment
            val videoDir = File(System.getProperty("user.home"), "GamePerf Reports")
            videoDir.mkdirs()
            val sessionId = java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(java.util.Date())
            if (!isIosDevice) adb.cleanRecordings(device.id)
            recordSegment = 0
            // v4.0.0: iOS screen recording goes through the sidecar
            var iosScreenCaptureId: String? = null
            if (isIosDevice) {
                iosScreenCaptureId = iosBridge?.let { bridge ->
                    val client = (bridge as? IosBridge)?.let {
                        sidecarLifecycle?.client
                    }
                    client?.startScreenRecord(device.id, sessionId)
                }
                if (iosScreenCaptureId == null) {
                    _captureWarning.value = "No se pudo iniciar la grabación de pantalla en iOS. Las métricas sí se están registrando."
                }
            } else {
                // Android: Pick screenrecord profile based on device tier
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
                recordProcess = startSegmentWithRetry(device.id, sessionId, recordSegment, recordProfile)
                if (recordProcess == null) {
                    _captureWarning.value = "El video no se pudo grabar en este dispositivo (screenrecord rechazado por el sistema). Las metricas si se estan registrando."
                    System.err.println("AppViewModel: screenrecord failed for both COMPACT and STANDARD profiles on device ${device.id}")
                }
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
            // v4.0.0: chain recording is Android-only (iOS uses sidecar single-session)
            if (!isIosDevice) {
                val chainProfile = run {
                    val gpu = _deviceInfo.value?.gpu ?: ""
                    val tier = com.gameperf.desktop.core.HardwareScoring.detectTier(gpu)
                    when (tier) {
                        com.gameperf.desktop.core.HardwareScoring.DeviceTier.LOW,
                        com.gameperf.desktop.core.HardwareScoring.DeviceTier.LOWER_MID ->
                            AdbBridge.ScreenRecordProfile.COMPACT
                        else -> AdbBridge.ScreenRecordProfile.STANDARD
                    }
                }
                recordJob = scope.launch {
                    while (!shouldStop) {
                        delay(175_000)
                        if (shouldStop) break
                        adb.stopScreenRecord(recordProcess)
                        delay(3000)
                        recordSegment++
                        val nextProcess = startSegmentWithRetry(device.id, sessionId, recordSegment, chainProfile)
                        if (nextProcess == null) {
                            recordChainFailures++
                            val msg = describeChainFailure(recordSegment)
                            System.err.println("AppViewModel: chain segment $recordSegment failed after retry — $msg")
                            if (_captureWarning.value == null) {
                                _captureWarning.value = msg
                            }
                            recordProcess = null
                            break
                        }
                        recordProcess = nextProcess
                    }
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
            var lastMem: com.gameperf.desktop.core.model.MemSnapshot? = null
            var lastThermal = com.gameperf.desktop.core.model.ThermalSnapshot(Double.NaN, Double.NaN, Double.NaN, Double.NaN)

            while (!shouldStop) {
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                if (durationSeconds > 0 && elapsed >= durationSeconds) break
                if (shouldStop) break

                // Small delay between sampling cycles — the fast tier (FPS + CPU + battery)
                // takes ~30-50ms on a mid-range device, so real cadence is ~0.5-0.6s.
                delay(500)
                if (shouldStop) break

                // === METRICS CAPTURE ===
                // v4.1.0: both AdbBridgeApi and IosBridge now return core.model.FrameSnapshot
                // directly — no conversion needed.
                val frame: com.gameperf.desktop.core.model.FrameSnapshot?
                val cpu: Int
                val battery: Int

                if (isIosDevice) {
                    // iOS: all metrics come from the sidecar via iosBridge (single HTTP call per method)
                    frame = iosBridge?.captureFrames(device.id, pkg)
                    if (shouldStop) break
                    cpu = iosBridge?.captureCpuPercent(device.id) ?: 0
                    if (shouldStop) break
                    battery = iosBridge?.getBatteryLevel(device.id) ?: 0
                    if (shouldStop) break

                    // iOS: thermal + memory on every iteration (sidecar caches, HTTP is cheap)
                    val iosTherm = iosBridge?.captureTemperature(device.id)
                    if (iosTherm != null) {
                        lastThermal = com.gameperf.desktop.core.model.ThermalSnapshot(iosTherm.cpu, iosTherm.gpu, iosTherm.battery, iosTherm.skin)
                    }
                    val iosMem = iosBridge?.captureMemory(device.id, pkg)
                    if (iosMem != null) {
                        lastMem = com.gameperf.desktop.core.model.MemSnapshot(iosMem.totalMb, iosMem.nativeMb, iosMem.javaMb)
                    }
                } else {
                    // Android: tiered cadence to reduce overhead
                    // FAST TIER (every 500ms): FPS, CPU, battery
                    frame = adb.captureFrames(device.id, pkg)
                    if (shouldStop) break
                    cpu = adb.captureCpuPercent(device.id)
                    if (shouldStop) break
                    battery = adb.getBatteryLevel(device.id)
                    if (shouldStop) break

                    // MEDIUM TIER (every ~2s): thermals
                    val runThermal = iterCount % 4 == 0
                    if (runThermal) {
                        val t = adb.captureTemperature(device.id)
                        if (shouldStop) break
                        lastThermal = com.gameperf.desktop.core.model.ThermalSnapshot(t.cpu, t.gpu, t.battery, t.skin)
                    }

                    // SLOW TIER (every ~5s): memory
                    val runMem = iterCount % 10 == 0
                    if (runMem) {
                        val m = adb.captureMemory(device.id, pkg)
                        if (shouldStop) break
                        if (m != null) lastMem = com.gameperf.desktop.core.model.MemSnapshot(m.totalMb, m.nativeMb, m.javaMb)
                    }
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
                    // H-2: cap to prevent unbounded growth
                    if (fpsHistory.size > MAX_HISTORY_SIZE) fpsHistory.removeFirst()
                    if (fpsTimed.size > MAX_HISTORY_SIZE) fpsTimed.removeFirst()
                }
                val memNow = lastMem
                // v4.0.0: iOS captures mem/thermal every iteration; Android uses tiered cadence
                val shouldRecordMem = isIosDevice || (iterCount % 10 == 1) // align with runMem above
                if (shouldRecordMem && memNow != null) {
                    memHistory.add(memNow.totalMb)
                    nativeHistory.add(memNow.nativeMb)
                    javaHistory.add(memNow.javaMb)
                    if (memHistory.size > MAX_HISTORY_SIZE) memHistory.removeFirst()
                    if (nativeHistory.size > MAX_HISTORY_SIZE) nativeHistory.removeFirst()
                    if (javaHistory.size > MAX_HISTORY_SIZE) javaHistory.removeFirst()
                }
                if (cpu > 0) {
                    cpuHistory.add(cpu)
                    if (cpuHistory.size > MAX_HISTORY_SIZE) cpuHistory.removeFirst()
                }
                val shouldRecordThermal = isIosDevice || (iterCount % 4 == 1) // align with runThermal above
                // v4.1.0: thermal fields use NaN as sentinel. NaN > 0 is false in IEEE 754,
                // so the guard works identically, but we use !isNaN() for clarity.
                if (shouldRecordThermal) {
                    if (!lastThermal.cpu.isNaN() && lastThermal.cpu > 0) {
                        tempCpuHistory.add(lastThermal.cpu)
                        if (tempCpuHistory.size > MAX_HISTORY_SIZE) tempCpuHistory.removeFirst()
                    }
                    if (!lastThermal.gpu.isNaN() && lastThermal.gpu > 0) {
                        tempGpuHistory.add(lastThermal.gpu)
                        if (tempGpuHistory.size > MAX_HISTORY_SIZE) tempGpuHistory.removeFirst()
                    }
                    if (!lastThermal.skin.isNaN() && lastThermal.skin > 0) {
                        tempSkinHistory.add(lastThermal.skin)
                        if (tempSkinHistory.size > MAX_HISTORY_SIZE) tempSkinHistory.removeFirst()
                    }
                }
                if (frame != null && frame.avgFrameTime > 0) {
                    frameTimeAvgHistory.add(frame.avgFrameTime)
                    allFrameTimes.add(frame.avgFrameTime)
                    if (frameTimeAvgHistory.size > MAX_HISTORY_SIZE) frameTimeAvgHistory.removeFirst()
                    if (allFrameTimes.size > MAX_FRAME_TIMES_SIZE) allFrameTimes.removeFirst()
                }
                totalJank += frame?.jankCount ?: 0
                totalStutter += frame?.stutterCount ?: 0

                val currentElapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                // v4.1.0-perf: snapshot history lists only every 2 seconds (4 iterations)
                // instead of every 500ms. The scalar fields (fps, cpu, temps, etc.) still
                // update every cycle for responsive UI, but the heavy list copies that the
                // graphs consume only refresh at 0.5 Hz — imperceptible to the user.
                val snapshotHistories = iterCount % 4 == 0
                val prev = _liveMetrics.value
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
                    fpsHistory = if (snapshotHistories) fpsHistory.toList() else prev.fpsHistory,
                    fpsTimed = if (snapshotHistories) fpsTimed.toList() else prev.fpsTimed,
                    memHistory = if (snapshotHistories) memHistory.toList() else prev.memHistory,
                    nativeHistory = if (snapshotHistories) nativeHistory.toList() else prev.nativeHistory,
                    javaHistory = if (snapshotHistories) javaHistory.toList() else prev.javaHistory,
                    cpuHistory = if (snapshotHistories) cpuHistory.toList() else prev.cpuHistory,
                    tempCpuHistory = if (snapshotHistories) tempCpuHistory.toList() else prev.tempCpuHistory,
                    tempGpuHistory = if (snapshotHistories) tempGpuHistory.toList() else prev.tempGpuHistory,
                    tempSkinHistory = if (snapshotHistories) tempSkinHistory.toList() else prev.tempSkinHistory,
                    frameTimeHistory = if (snapshotHistories) frameTimeAvgHistory.toList() else prev.frameTimeHistory,
                    allFrameTimes = if (snapshotHistories) allFrameTimes.toList() else prev.allFrameTimes
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

            // v4.0.0: platform-aware recording stop + pull
            val videoPath: String
            if (isIosDevice) {
                // iOS: stop sidecar capture → it returns the stitched video path
                val iosVideoPath = if (iosScreenCaptureId != null) {
                    sidecarLifecycle?.client?.stopScreenRecord(device.id, iosScreenCaptureId)
                } else null
                videoPath = iosVideoPath ?: ""
            } else {
                // Android: stop adb screenrecord, pull segments, concat
                adb.stopScreenRecord(recordProcess)
                recordProcess = null
                delay(3000) // let last segment finalize on device
                val recordings = adb.pullRecordings(device.id, sessionId, videoDir)

                videoPath = if (recordings.isNotEmpty()) {
                    val unified = java.io.File(videoDir, "video_${sessionId}.mp4")
                    val result = if (recordings.size > 1) {
                        adb.concatSegments(recordings, unified)
                    } else {
                        if (adb.isValidVideoFile(recordings.first())) recordings.first() else null
                    }
                    if (result != null) {
                        result.absolutePath
                    } else {
                        val anyValid = recordings.firstOrNull { adb.isValidVideoFile(it) }
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
            }

            // === FINALIZE ===
            if (!isWifiMode && !isIosDevice) adb.restoreCharging(device.id)
            val batteryEnd = if (isIosDevice) {
                iosBridge?.getBatteryLevel(device.id) ?: 0
            } else {
                adb.getBatteryLevel(device.id)
            }
            val missedEnd = if (isIosDevice) 0 else adb.getMissedFrames(device.id)

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

    // ===== Video Playback (delegated v4.1.0) =====

    fun setVideoPosition(positionMs: Long) = videoDelegate.setVideoPosition(positionMs)
    fun setVideoPlaying(playing: Boolean) = videoDelegate.setVideoPlaying(playing)
    fun setVideoDuration(durationMs: Long) = videoDelegate.setVideoDuration(durationMs)
    fun setPlaybackSpeed(speed: Double) = videoDelegate.setPlaybackSpeed(speed)

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

    fun toggleFavorite(id: String) {
        SessionHistory.toggleFavorite(id)
        _history.value = SessionHistory.load()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Google Drive sync
    // ─────────────────────────────────────────────────────────────────────────

    /** Launch OAuth2 browser flow. Runs on IO, updates driveState. */
    fun connectDrive() {
        if (_driveState.value is DriveSyncState.Connecting) return
        _driveState.value = DriveSyncState.Connecting
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val email = driveSync.authenticate()
                _driveState.value = DriveSyncState.Connected(email)
                refreshRemoteSessions()
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("credentials.json") == true ->
                        "Falta credentials.json — sigue las instrucciones de configuración"
                    e.message?.contains("403") == true ->
                        "Acceso denegado. Verifica los permisos del proyecto en Google Cloud"
                    else -> e.message ?: "Error desconocido"
                }
                _driveState.value = DriveSyncState.Error(msg)
            }
        }
    }

    /** Sign out — removes local tokens. */
    fun disconnectDrive() {
        driveSync.signOut()
        _driveState.value = DriveSyncState.Disconnected
        _remoteSessions.value = emptyList()
    }

    /** Upload a single session to Drive. */
    fun uploadSession(entryId: String) {
        val entry = _history.value.firstOrNull { it.id == entryId } ?: return
        _driveOp.value = DriveOp.Uploading(entry.name)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"), "gameperf_packs")
                val packFile = com.gameperf.desktop.cloud.SessionPack.export(entry, tmpDir)
                val appProps = com.gameperf.desktop.cloud.SessionPack.appPropertiesFrom(entry)
                driveSync.uploadSession(packFile, appProps)
                packFile.delete()
                _driveOp.value = DriveOp.Idle
                refreshRemoteSessions()
            } catch (e: Exception) {
                _driveState.value = DriveSyncState.Error("Error al subir: ${e.message}")
                _driveOp.value = DriveOp.Idle
            }
        }
    }

    /** Refresh the list of remote sessions from Drive. */
    fun refreshRemoteSessions() {
        if (_driveState.value !is DriveSyncState.Connected) return
        _driveOp.value = DriveOp.Refreshing
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                _remoteSessions.value = driveSync.listSessions()
            } catch (e: Exception) {
                _driveState.value = DriveSyncState.Error("Error al leer Drive: ${e.message}")
            } finally {
                _driveOp.value = DriveOp.Idle
            }
        }
    }

    /** Download a remote session and import it into local history. */
    fun downloadAndImportSession(fileId: String, sessionName: String) {
        _driveOp.value = DriveOp.Downloading(sessionName)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"), "gameperf_downloads")
                val packFile = driveSync.downloadSession(fileId, tmpDir)
                val reportsDir = java.io.File(System.getProperty("user.home"), "GamePerf Reports")
                val imported = com.gameperf.desktop.cloud.SessionPack.import(packFile, reportsDir)
                packFile.delete()
                // Only import if not already present
                val existing = _history.value.any { it.id == imported.id }
                if (!existing) {
                    SessionHistory.addEntry(imported)
                    _history.value = SessionHistory.load()
                }
                _driveOp.value = DriveOp.Idle
            } catch (e: Exception) {
                _driveState.value = DriveSyncState.Error("Error al descargar: ${e.message}")
                _driveOp.value = DriveOp.Idle
            }
        }
    }

    /** Set a custom team folder ID (shared by a teammate). */
    fun setDriveTeamFolder(folderId: String) {
        driveSync.setTeamFolderId(folderId)
        if (_driveState.value is DriveSyncState.Connected) {
            refreshRemoteSessions()
        }
    }

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

    /**
     * v4.2.0: Re-open a past session from history in the Results screen,
     * exactly as it looked when it was captured. Reconstructs a [SessionResult]
     * from the persisted [SessionHistory.HistoryEntry].
     */
    fun viewHistorySession(entry: SessionHistory.HistoryEntry) {
        _result.value = SessionResult(
            gamePackage = entry.gamePackage,
            deviceModel = entry.deviceModel,
            duration = entry.duration,
            grade = entry.grade,
            avgFps = entry.avgFps,
            p1Fps = entry.p1Fps,
            p5Fps = entry.p5Fps,
            avgFrameTime = entry.avgFrameTime,
            p99FrameTime = entry.p99FrameTime,
            peakMemMb = entry.peakMemMb,
            avgCpu = entry.avgCpu,
            reportPath = entry.reportPath,
            videoPath = entry.videoPath,
            deviceGrade = entry.deviceGrade,
            deviceScore = entry.score,
            markers = entry.markers,
        )
        _markers.value = entry.markers
        videoDelegate.reset()
        _screen.value = AppScreen.RESULTS
    }

    fun goHome() {
        captureJob?.cancel()
        shouldStop = true
        _screen.value = AppScreen.HOME
        _liveMetrics.value = LiveMetrics()
        _markers.value = emptyList()
        _selectedForComparison.value = emptySet()
        videoDelegate.reset()
        recordJob?.cancel()
        adb.stopScreenRecord(recordProcess)
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

    /** Reset the export status banner to Idle. Called by ExportBanner after auto-dismiss. */
    fun resetExportStatus() = exportDelegate.resetExportStatus()

    // ===== PDF Export (delegated v4.1.0) =====

    fun exportCurrentReportToPdf() {
        val current = _result.value
        if (current.reportPath.isEmpty()) return
        val defaultName = "informe_${safePkg(current.gamePackage)}_${safeDevice(current.deviceModel)}_${shortDate(currentDateString())}.pdf"
        exportDelegate.exportToPdf(current.reportPath, defaultName)
    }

    fun exportHistoryEntryToPdf(entry: SessionHistory.HistoryEntry) {
        if (entry.reportPath.isEmpty()) return
        val defaultName = "informe_${safePkg(entry.gamePackage)}_${safeDevice(entry.deviceModel)}_${shortDate(entry.date)}.pdf"
        exportDelegate.exportToPdf(entry.reportPath, defaultName)
    }

    fun exportComparisonToPdf(htmlPath: String) {
        if (htmlPath.isEmpty()) return
        val defaultName = "comparativa_${shortDate(LocalDate.now().toString())}.pdf"
        exportDelegate.exportToPdf(htmlPath, defaultName)
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

    // ===== v3.2.0 — Wireless ADB (delegated v4.1.0) =====
    private val wifiDelegate = WifiDelegate(adb, scope) { refreshDevices() }
    val wifiPanel: StateFlow<WifiDelegate.WifiPanelState> = wifiDelegate.wifiPanel
    val mdnsAvailable: StateFlow<Boolean> = wifiDelegate.mdnsAvailable
    val pairingServiceAlive: StateFlow<Boolean> = wifiDelegate.pairingServiceAlive
    val adbVersion: StateFlow<AdbVersion?> = wifiDelegate.adbVersion

    internal fun setMdnsAvailableForTest(available: Boolean) = wifiDelegate.setMdnsAvailableForTest(available)
    fun openWifiPanel() = wifiDelegate.openWifiPanel()
    fun closeWifiPanel() = wifiDelegate.closeWifiPanel()
    fun selectMdnsDevice(service: MdnsService) = wifiDelegate.selectMdnsDevice(service)
    fun submitCodeForSelected(code: String) = wifiDelegate.submitCodeForSelected(code)
    fun submitManual(ip: String, pairPort: Int, code: String) = wifiDelegate.submitManual(ip, pairPort, code)
    fun retryError() = wifiDelegate.retryError()
}
