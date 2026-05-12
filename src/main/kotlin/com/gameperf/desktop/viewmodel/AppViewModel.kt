package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.core.AdbBridgeApi
import com.gameperf.desktop.core.AdbVersion
import com.gameperf.desktop.core.AppVersion
import com.gameperf.desktop.core.AutoUpdater
import com.gameperf.desktop.core.CURRENT_VERSION
import com.gameperf.desktop.core.ConnectFailureReason
import com.gameperf.desktop.core.ConnectResult
import com.gameperf.desktop.core.DependencyBootstrap
import com.gameperf.desktop.core.FileCleanup
import com.gameperf.desktop.core.MdnsService
import com.gameperf.desktop.core.MdnsServiceType
import com.gameperf.desktop.core.PairFailureReason
import com.gameperf.desktop.core.PairResult
import com.gameperf.desktop.core.RealAdbBridge
import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.core.Settings
import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.conclusions.ConclusionEngine
import com.gameperf.desktop.core.conclusions.ConclusionInput
import com.gameperf.desktop.core.conclusions.Severity
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.events.EventDetector
import com.gameperf.desktop.core.events.EventDetectorImpl
import com.gameperf.desktop.core.model.Device
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.bridge.AndroidBridge
import com.gameperf.desktop.core.grading.FinalScoreCalculator
import com.gameperf.desktop.core.grading.GradingInput
import com.gameperf.desktop.core.metrics.FilterInput
import com.gameperf.desktop.core.metrics.FilteredMetricsCalculator
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
    val allFrameTimes: List<Double> = emptyList(),
    // v4.5.0 — FPower live tile (mW/frame). The HUD reads [fpower] for the
    // single scalar. [fpowerHistory] / [fpowerTimed] mirror the thermal-history
    // snapshot pattern at lines 111-113 so the graphs draw without extra plumbing.
    // Default 0.0 is the "no reading yet" value; the loop emits the real value
    // only when [com.gameperf.desktop.core.model.FPowerSnapshot.fpowerAvailable]
    // is true (otherwise it stays 0 and the HUD reads as "--", same convention
    // the legacy `tempCpu` tile uses).
    val fpower: Double = 0.0,
    val fpowerHistory: List<Double> = emptyList(),
    val fpowerTimed: List<TimedSample> = emptyList(),
)

/**
 * Detection mode for the session, indicating what level of automatic event
 * detection was available.
 *
 * @property ANDROID_FULL Full logcat + dumpsys detection on Android.
 * @property IOS_PARTIAL iOS best-effort (StoreKit + foreground-loss only).
 * @property MANUAL_ONLY Auto-detection disabled or unavailable; manual markers only.
 *
 * @since v4.4.0
 */
enum class DetectionMode {
    ANDROID_FULL,
    IOS_PARTIAL,
    MANUAL_ONLY,
}

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
    val markers: List<SessionMarker> = emptyList(),
    // v4.4.0: auto event detection fields. Defaults maintain backward compat.
    val events: List<com.gameperf.desktop.core.events.DetectedEvent> = emptyList(),
    val rawAggregates: com.gameperf.desktop.core.metrics.MetricsAggregates? = null,
    val filteredAggregates: com.gameperf.desktop.core.metrics.MetricsAggregates? = null,
    val conclusions: List<com.gameperf.desktop.core.conclusions.Conclusion> = emptyList(),
    val detectionMode: DetectionMode = DetectionMode.MANUAL_ONLY,
    // v4.5.0 — FPower aggregates + history payload (spec FPW-008). Mirrors the
    // [thermalAvailable] precedent: `fpowerAvailable=true` is the v4.4.x-compatible
    // default so any caller that constructs a [SessionResult] without naming
    // these args stays semantically unchanged. [fpowerAvg] / [fpowerPeak] are
    // computed post-loop from [fpowerHistory] (empty → 0.0).
    val fpowerAvailable: Boolean = true,
    val fpowerDiagnostic: com.gameperf.desktop.core.model.FPowerDiagnostic? = null,
    val fpowerHistory: List<Double> = emptyList(),
    val fpowerTimed: List<TimedSample> = emptyList(),
    val fpowerAvg: Double = 0.0,
    val fpowerPeak: Double = 0.0,
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

        /**
         * v4.3.5 — FPS resume after ad / interstitial.
         * Number of consecutive null FrameSnapshots we tolerate before forcing
         * the AdbBridge layer cache to invalidate. At the 500ms poll cadence
         * this is ≈1.5s — long enough to ride out a transient SurfaceFlinger
         * blip on its own, short enough that the user perceives the recovery
         * as instant after an ad closes.
         */
        internal const val FORCED_LAYER_REDISCOVERY_THRESHOLD = 3

        /**
         * v4.3.5 — last-known FPS fallback window in milliseconds. While the
         * underlying layer is briefly stale (typically 0–3 polls during the
         * ad-close transient) we keep emitting the previous valid FPS instead
         * of flickering the HUD to "--". Outside this window we emit 0 so
         * the user has visible feedback that something is wrong.
         */
        internal const val LAST_KNOWN_FPS_WINDOW_MS = 1_500L

        /**
         * v4.2.6: infer the game's intended target FPS from the observed avg + max.
         *
         * Used by the grading logic so a 30fps-target game isn't penalized for
         * landing at p50=30 (which is on-target, not below). The previous grading
         * compared all games against 60fps thresholds.
         *
         * Heuristic: take the higher of `avgFps` and 95% of `maxFps`. The max is
         * the best the game ever achieved during the session, which is a strong
         * signal of what it tries to render. Drop to 95% to discount one-off
         * spikes from (e.g.) skipping the splash screen.
         *
         * Buckets correspond to the common mobile-game refresh strategies:
         *   ≥ 110 → 120 fps (high-refresh competitive)
         *   ≥ 80  → 90 fps (Genshin's "60+" mode, OnePlus 90hz games)
         *   ≥ 50  → 60 fps (most action games)
         *   ≥ 38  → 45 fps (Unity Auto on mid-range)
         *   else  → 30 fps (battery-saver, casual games, low-end devices)
         *
         * Pure function, easily unit-testable.
         */
        internal fun inferGameTargetFps(avgFps: Int, maxFps: Int): Int {
            val indicator = maxOf(avgFps, (maxFps * 0.95).toInt())
            return when {
                indicator >= 110 -> 120
                indicator >= 80 -> 90
                indicator >= 50 -> 60
                indicator >= 38 -> 45
                else -> 30
            }
        }
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

    // ===== v4.4.0 — Auto Event Detection =====

    /** Live cumulative list of auto-detected events (ads, IAPs, loading). */
    private val _events = MutableStateFlow<List<DetectedEvent>>(emptyList())
    val events: StateFlow<List<DetectedEvent>> = _events

    /** Detection-quality warnings surfaced from [EventDetector]. */
    private val _detectorWarnings = MutableStateFlow<List<String>>(emptyList())
    val detectorWarnings: StateFlow<List<String>> = _detectorWarnings

    /** Active detector for the current capture session, or null when idle. */
    private var eventDetector: EventDetector? = null

    /**
     * v4.4.1 — public mirror of the private [captureStartTime] field, exposed for the live
     * UI overlay (`MiniGraphWithEvents` on `CaptureScreen`) so vertical event lines can be
     * positioned relative to the capture's wall-clock origin without reading a private field.
     *
     * Updated alongside every write to [captureStartTime]: set to the current ms when capture
     * begins (`startCapture` start-clock block), reset to `0L` when capture ends. The UI treats
     * `0L` as "no capture running" and skips the overlay (see [MiniGraphWithEvents.totalMs]
     * guard).
     */
    private val _captureStartMs = MutableStateFlow(0L)
    val captureStartMs: StateFlow<Long> = _captureStartMs

    // ===== Video Playback (delegated v4.1.0) =====
    private val videoDelegate = VideoDelegate()
    val videoPosition: StateFlow<Long> = videoDelegate.videoPosition
    val isVideoPlaying: StateFlow<Boolean> = videoDelegate.isVideoPlaying
    val videoDuration: StateFlow<Long> = videoDelegate.videoDuration
    val playbackSpeed: StateFlow<Double> = videoDelegate.playbackSpeed

    private val _history = MutableStateFlow<List<SessionHistory.HistoryEntry>>(emptyList())
    val history: StateFlow<List<SessionHistory.HistoryEntry>> = _history

    // ===== Session Sharing via .gameperf files (v4.2.8) =====
    //
    // v4.2.8: replaced the Google Drive sync integration with manual .gameperf
    // export/import. The Drive code required the user to:
    //   - Get a credentials.json from Google Cloud Console
    //   - Enable the Drive API on their account
    //   - Share a team folder ID between QA members
    //   - Run an OAuth2 browser flow on first use
    // That's way too much plumbing for a QA tool used by 2-5 people on a team.
    // .gameperf is a self-contained ZIP (via SessionPack) the user can move
    // around however they want: email, Slack, shared folder, USB stick, rsync,
    // whatever. Zero cloud dependencies, zero OAuth, zero maintenance.

    /** Emits a transient message when an export/import succeeds or fails.
     *  The UI snackbars / dialogs observe it to show confirmation. Cleared
     *  after ~5 seconds via [clearSessionPackMessage]. */
    private val _sessionPackMessage = MutableStateFlow<String?>(null)
    val sessionPackMessage: StateFlow<String?> = _sessionPackMessage

    // ===== Session Tagging =====
    private val _sessionTag = MutableStateFlow(SessionHistory.SessionTag.OUR_GAME)
    val sessionTag: StateFlow<SessionHistory.SessionTag> = _sessionTag

    private val _competitorName = MutableStateFlow("")
    val competitorName: StateFlow<String> = _competitorName

    // ===== Comparison =====
    private val _selectedForComparison = MutableStateFlow<Set<String>>(emptySet())
    val selectedForComparison: StateFlow<Set<String>> = _selectedForComparison

    // ===== Auto-Update (delegated v4.1.0) =====
    // v4.4.1: trailing-lambda style replaced with named arg because the constructor
    // gained an injectable historyStore param after onStatusMessage.
    private val updateDelegate = UpdateDelegate(
        scope = scope,
        onStatusMessage = { msg -> _statusMessage.value = msg },
    )
    val updateAvailable: StateFlow<AutoUpdater.ReleaseInfo?> = updateDelegate.updateAvailable
    val updateProgress: StateFlow<Float?> = updateDelegate.updateProgress
    val updateError: StateFlow<String?> = updateDelegate.updateError

    // v4.4.1: fallback panel state — non-null when the last update attempt
    // failed terminally and HomeScreen should render `UpdateFallbackPanel`.
    // Supersedes `updateError` for UAC / watchdog / helper failures (those
    // used to be invisible). Spec REQ "Fallback panel display".
    val updateFallback: StateFlow<com.gameperf.desktop.core.update.UpdateFallbackState?> =
        updateDelegate.updateFallback

    /** v4.4.1 — Snapshot of recent [updateAttempts] for the fallback panel's "Detalles técnicos". */
    fun recentUpdateAttempts(limit: Int = 10): List<com.gameperf.desktop.core.update.UpdateAttempt> =
        com.gameperf.desktop.viewmodel.UpdateDelegate.defaultHistoryStore().recentAttempts(limit)

    // ===== Capture Error (device disconnect, etc.) =====
    /**
     * v4.2.5: live processing status for the post-capture pipeline.
     *
     * Set to a human-readable message ("Descargando video del dispositivo...",
     * "Generando reporte HTML...") at each step of the stop -> pull -> concat ->
     * report -> save flow, which can take 30-90 seconds for a long Android session.
     * Cleared (set to null) once the results screen is ready.
     *
     * The UI (CaptureScreen / ResultsScreen) renders an overlay with this message
     * whenever it's non-null so the user knows the app is busy and not frozen.
     * Pre-v4.2.5 there was no feedback during this window — multiple users thought
     * the app had hung and force-closed it before the report was saved.
     */
    private val _processingStatus = MutableStateFlow<String?>(null)
    val processingStatus: StateFlow<String?> = _processingStatus

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

    // ===== Dependency Bootstrap (v4.2.14) =====
    /** Missing dependencies detected at startup (adb, ffmpeg). */
    private val _missingDeps = MutableStateFlow<List<DependencyBootstrap.MissingTool>>(emptyList())
    val missingDeps: StateFlow<List<DependencyBootstrap.MissingTool>> = _missingDeps

    /** Bootstrap progress for download/extraction. */
    private val _bootstrapProgress = MutableStateFlow<DependencyBootstrap.BootstrapProgress?>(null)
    val bootstrapProgress: StateFlow<DependencyBootstrap.BootstrapProgress?> = _bootstrapProgress

    /** Bootstrap error message for display. */
    private val _bootstrapError = MutableStateFlow<String?>(null)
    val bootstrapError: StateFlow<String?> = _bootstrapError

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

            // v4.2.14: Check for missing dependencies (adb, ffmpeg)
            // Run after adb check so we distinguish "adb not in PATH" from "adb bundled available"
            val missing = DependencyBootstrap.check()
            _missingDeps.value = missing

            // If adb is missing, decide based on the reason
            if (!_adbAvailable.value) {
                val adbMissing = missing.find { it.toolName == "adb" }
                when (adbMissing?.reason) {
                    DependencyBootstrap.MissingReason.BUNDLED_AVAILABLE -> {
                        // Bundled adb available - proceed normally, UI shows "install" banner
                        _statusMessage.value = "ADB no está instalado. Instala ADB desde la app."
                    }
                    else -> {
                        _statusMessage.value = "ADB no encontrado. Instala Android SDK."
                        return@launch
                    }
                }
            } else {
                _statusMessage.value = "ADB disponible. Buscando dispositivos..."
                refreshDevices()
            }
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

    /** v4.4.1 — Wired to the fallback panel's close icon. Spec scenario D1. */
    fun dismissUpdateFallback() = updateDelegate.dismissFallback()

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
            // v4.4.1: mirror to the public StateFlow so the live UI overlay can compute
            // event x-positions relative to capture origin without touching a private field.
            _captureStartMs.value = startTime

            // ═══ v4.4.0 — Auto event detection (Android only, gated by Settings) ═══
            //
            // The detector owns its own LogcatCapture + DumpsysPoller. We launch
            // them on the same `scope` as the rest of the capture so cancellation
            // propagates if the session is torn down. The bridge flows mirror the
            // detector's StateFlows into our public surface.
            //
            // Behaviour when the flag is OFF: nothing is instantiated, no extra
            // adb processes spawn, and `events`/`detectorWarnings` stay empty —
            // identical to pre-v4.4.0 capture behaviour.
            _events.value = emptyList()
            _detectorWarnings.value = emptyList()
            val settings = Settings.load()
            if (settings.autoEventDetectionEnabled && !isIosDevice) {
                val detector = EventDetectorImpl(bridge = adb)
                detector.start(deviceId = device.id, gamePackage = pkg, scope = scope)
                eventDetector = detector
                scope.launch { detector.events.collect { _events.value = it } }
                scope.launch { detector.warnings.collect { _detectorWarnings.value = it } }
            }

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
            // v4.3.6: separate die-CPU history. Pre-v4.3.6 `tempCpuHistory`
            // conflated skin and die into one timeline. The new field tracks
            // the silicon temp explicitly so the report can show both.
            val tempDieCpuHistory = mutableListOf<Double>()
            val frameTimeAvgHistory = mutableListOf<Double>()
            val allFrameTimes = mutableListOf<Double>()

            // v4.4.0: timestamped twins for FilteredMetricsCalculator input.
            // These parallel the positional histories above but include the
            // capture-relative timestamp for each sample, enabling time-based
            // filtering of metrics during detected events (ads, IAPs, loading).
            val cpuTimed = mutableListOf<TimedSample>()
            val memTimed = mutableListOf<TimedSample>()
            val nativeTimed = mutableListOf<TimedSample>()
            val javaTimed = mutableListOf<TimedSample>()
            val tempCpuTimed = mutableListOf<TimedSample>()
            val tempGpuTimed = mutableListOf<TimedSample>()
            val tempSkinTimed = mutableListOf<TimedSample>()
            val tempDieCpuTimed = mutableListOf<TimedSample>()
            val frameTimeTimed = mutableListOf<TimedSample>()
            val jankTimed = mutableListOf<TimedSample>()
            val stutterTimed = mutableListOf<TimedSample>()
            // v4.5.0 — FPower accumulators (design §9b). Parallels the thermal history
            // pattern at lines 1056-1077. Single timeline; the per-tick capture lands a
            // value when [com.gameperf.desktop.core.model.FPowerSnapshot.fpowerAvailable]
            // is true AND `fpowerMwPerFrame > 0` (see history-append guard below).
            val fpowerHistory = mutableListOf<Double>()
            val fpowerTimed = mutableListOf<TimedSample>()
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
            // v4.5.0 — FPower live cache (design §9a). The default snapshot has all
            // numeric fields = -1.0 with `fpowerAvailable=true` (the v4.4.x-compatible
            // sentinel from FPowerSnapshot in Metrics.kt:93). The per-tick capture
            // overwrites this when the medium-cadence (`iterCount % 4 == 0`) tier fires.
            // Pre-first-poll the history-append guard `fpowerMwPerFrame > 0` excludes
            // the sentinel so no -1.0 value contaminates [fpowerHistory].
            var lastFPower = com.gameperf.desktop.core.model.FPowerSnapshot()

            // v4.3.5 — FPS resume after ad / interstitial:
            // After an ad close the SurfaceFlinger layer cache (inside AdbBridge)
            // can lock onto a zombie SurfaceView and return null FrameSnapshots
            // every poll. We track how many consecutive nulls we've seen; once
            // we hit [FORCED_LAYER_REDISCOVERY_THRESHOLD] (≈1.5s at 500ms ticks)
            // we force the cache to drop so the next captureFrames does a fresh
            // dumpsys --list and re-ranks candidates. The counter resets on the
            // first non-null frame.
            var consecutiveNullFrames = 0
            // v4.3.5 — last-known FPS fallback (Fix 4):
            // [LastKnownFpsTracker] keeps the previous valid FPS sticky for
            // [LAST_KNOWN_FPS_WINDOW_MS] so the HUD doesn't flicker to "--"
            // during the ad-close transient. After the window expires it
            // returns 0 and the HUD shows "--". History/report fields stay
            // truthful — only the live UI emission consults this tracker.
            val lastKnownFpsTracker = com.gameperf.desktop.core.LastKnownFpsTracker(
                windowMs = LAST_KNOWN_FPS_WINDOW_MS,
            )

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
                    // v4.2.5: pass pkg so we measure the GAME's CPU%, not the
                    // device-wide CPU% (which used to mislead users into thinking
                    // a 30% reading meant the game was light when really it was
                    // 30% across ALL processes including system + idle).
                    cpu = adb.captureCpuPercent(device.id, pkg)
                    if (shouldStop) break
                    battery = adb.getBatteryLevel(device.id)
                    if (shouldStop) break

                    // MEDIUM TIER (every ~2s): thermals
                    val runThermal = iterCount % 4 == 0
                    if (runThermal) {
                        val t = adb.captureTemperature(device.id)
                        if (shouldStop) break
                        // v4.4.1 (temperature-not-shown, Q1): convert from positional to named args.
                        // The 4-positional form silently dropped t.dieCpu (added in v4.3.6) AND
                        // would also drop the new t.thermalAvailable / t.diagnostic fields landed
                        // in T1 of this change. Named-args makes every field explicit so future
                        // ThermalSnapshot widenings stay propagated automatically. ADDITIVE only —
                        // no surrounding refactor (the enclosing startCapture body is detekt-baseline).
                        lastThermal = com.gameperf.desktop.core.model.ThermalSnapshot(
                            cpu = t.cpu,
                            gpu = t.gpu,
                            battery = t.battery,
                            skin = t.skin,
                            dieCpu = t.dieCpu,
                            thermalAvailable = t.thermalAvailable,
                            diagnostic = t.diagnostic,
                        )
                        // v4.5.0 — FPower poll co-located with thermal at the medium tier
                        // (~2s cadence at the 500ms loop, design §9c + ADR-6). The bridge
                        // owns the per-device path cache so steady-state cost is ~2 shell
                        // reads. We pass [frame?.fps] as the per-tick honest reading per
                        // ADR-3 (FPS = same per-tick value, NOT smoothed). On `fps <= 0`
                        // the parser raises FPS_ZERO and the tick is dropped by the
                        // history-append guard below.
                        val rawFpsForFpower = (frame?.fps ?: 0).toDouble()
                        val fpowerSnap = adb.captureFPower(device.id, currentFps = rawFpsForFpower)
                        if (shouldStop) break
                        lastFPower = fpowerSnap
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

                // v4.3.5 — FPS resume after ad / interstitial.
                // Track null FrameSnapshots specifically (not the broader allFailed
                // heuristic). When we hit the threshold, force the layer cache to
                // drop so the next captureFrames does a fresh dumpsys --list and
                // re-ranks candidates. Reset on any non-null frame so transient
                // single-frame nulls don't trigger unnecessary re-discovery.
                if (!isIosDevice) {
                    if (frame == null) {
                        consecutiveNullFrames++
                        if (consecutiveNullFrames >= FORCED_LAYER_REDISCOVERY_THRESHOLD) {
                            adb.invalidateLayerCache(device.id, pkg)
                            consecutiveNullFrames = 0  // give re-discovery a fresh window
                        }
                    } else {
                        consecutiveNullFrames = 0
                    }
                }

                val sampleSecond = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                val fps = frame?.fps ?: 0
                // v4.3.5 — last-known FPS fallback (sticky HUD across ad close).
                // History/report fields use the raw [fps]; only [displayFps]
                // (consumed by the live LiveMetrics emission) goes through the
                // sticky tracker. See [LastKnownFpsTracker] for details.
                val displayFps: Int = lastKnownFpsTracker.update(
                    rawFps = fps,
                    nowMs = System.currentTimeMillis(),
                )
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
                    // v4.4.0: timestamped memory twins for FilteredMetricsCalculator.
                    memTimed.add(TimedSample(sampleSecond, memNow.totalMb.toDouble()))
                    nativeTimed.add(TimedSample(sampleSecond, memNow.nativeMb.toDouble()))
                    javaTimed.add(TimedSample(sampleSecond, memNow.javaMb.toDouble()))
                    if (memHistory.size > MAX_HISTORY_SIZE) memHistory.removeFirst()
                    if (nativeHistory.size > MAX_HISTORY_SIZE) nativeHistory.removeFirst()
                    if (javaHistory.size > MAX_HISTORY_SIZE) javaHistory.removeFirst()
                    if (memTimed.size > MAX_HISTORY_SIZE) memTimed.removeFirst()
                    if (nativeTimed.size > MAX_HISTORY_SIZE) nativeTimed.removeFirst()
                    if (javaTimed.size > MAX_HISTORY_SIZE) javaTimed.removeFirst()
                }
                if (cpu > 0) {
                    cpuHistory.add(cpu)
                    cpuTimed.add(TimedSample(sampleSecond, cpu.toDouble()))
                    if (cpuHistory.size > MAX_HISTORY_SIZE) cpuHistory.removeFirst()
                    if (cpuTimed.size > MAX_HISTORY_SIZE) cpuTimed.removeFirst()
                }
                val shouldRecordThermal = isIosDevice || (iterCount % 4 == 1) // align with runThermal above
                // v4.1.0: thermal fields use NaN as sentinel. NaN > 0 is false in IEEE 754,
                // so the guard works identically, but we use !isNaN() for clarity.
                if (shouldRecordThermal) {
                    // v4.3.6: prefer skin for the user-facing `tempCpuHistory`
                    // when skin is available. Falls back to die when no skin
                    // sensor exists. Old `.gameperf` exports stay readable
                    // because the field type didn't change.
                    //
                    // v4.4.1 (temperature-not-shown): make the "no thermal data" path
                    // EXPLICIT instead of silently falling through the three-branch
                    // when() to NaN. AdbThermalParser flips lastThermal.thermalAvailable
                    // to false when no CPU/SKIN zone classifies (unsupported vendor,
                    // permission denied, all temps OOR). Short-circuiting here keeps
                    // tempCpuHistory empty so the post-loop maxOrNull stays at 0.0 AND
                    // the persisted thermalAvailable=false propagates downstream — the
                    // report will render "N/D" + diagnostic banner instead of "0°C".
                    val userFacingTemp = if (!lastThermal.thermalAvailable) {
                        Double.NaN
                    } else when {
                        !lastThermal.skin.isNaN() && lastThermal.skin > 0 -> lastThermal.skin
                        !lastThermal.dieCpu.isNaN() && lastThermal.dieCpu > 0 -> lastThermal.dieCpu
                        !lastThermal.cpu.isNaN() && lastThermal.cpu > 0 -> lastThermal.cpu
                        else -> Double.NaN
                    }
                    if (!userFacingTemp.isNaN()) {
                        tempCpuHistory.add(userFacingTemp)
                        tempCpuTimed.add(TimedSample(sampleSecond, userFacingTemp))
                        if (tempCpuHistory.size > MAX_HISTORY_SIZE) tempCpuHistory.removeFirst()
                        if (tempCpuTimed.size > MAX_HISTORY_SIZE) tempCpuTimed.removeFirst()
                    }
                    if (!lastThermal.gpu.isNaN() && lastThermal.gpu > 0) {
                        tempGpuHistory.add(lastThermal.gpu)
                        tempGpuTimed.add(TimedSample(sampleSecond, lastThermal.gpu))
                        if (tempGpuHistory.size > MAX_HISTORY_SIZE) tempGpuHistory.removeFirst()
                        if (tempGpuTimed.size > MAX_HISTORY_SIZE) tempGpuTimed.removeFirst()
                    }
                    if (!lastThermal.skin.isNaN() && lastThermal.skin > 0) {
                        tempSkinHistory.add(lastThermal.skin)
                        tempSkinTimed.add(TimedSample(sampleSecond, lastThermal.skin))
                        if (tempSkinHistory.size > MAX_HISTORY_SIZE) tempSkinHistory.removeFirst()
                        if (tempSkinTimed.size > MAX_HISTORY_SIZE) tempSkinTimed.removeFirst()
                    }
                    if (!lastThermal.dieCpu.isNaN() && lastThermal.dieCpu > 0) {
                        tempDieCpuHistory.add(lastThermal.dieCpu)
                        tempDieCpuTimed.add(TimedSample(sampleSecond, lastThermal.dieCpu))
                        if (tempDieCpuHistory.size > MAX_HISTORY_SIZE) tempDieCpuHistory.removeFirst()
                        if (tempDieCpuTimed.size > MAX_HISTORY_SIZE) tempDieCpuTimed.removeFirst()
                    }
                    // v4.5.0 — FPower history append (design §9d). Co-located with thermal
                    // recording at the same `iterCount % 4 == 1` cadence (one tick AFTER
                    // the poll above so the freshest read lands here). Guards both on the
                    // availability flag AND `> 0` so the sentinel -1.0 from a pre-first-poll
                    // [FPowerSnapshot] AND the parser's `IMPLAUSIBLE_VALUE` fallback both
                    // stay out of the persisted timeline.
                    if (lastFPower.fpowerAvailable && lastFPower.fpowerMwPerFrame > 0) {
                        fpowerHistory.add(lastFPower.fpowerMwPerFrame)
                        fpowerTimed.add(TimedSample(sampleSecond, lastFPower.fpowerMwPerFrame))
                        if (fpowerHistory.size > MAX_HISTORY_SIZE) fpowerHistory.removeFirst()
                        if (fpowerTimed.size > MAX_HISTORY_SIZE) fpowerTimed.removeFirst()
                    }
                }
                if (frame != null && frame.avgFrameTime > 0) {
                    frameTimeAvgHistory.add(frame.avgFrameTime)
                    allFrameTimes.add(frame.avgFrameTime)
                    frameTimeTimed.add(TimedSample(sampleSecond, frame.avgFrameTime))
                    if (frameTimeAvgHistory.size > MAX_HISTORY_SIZE) frameTimeAvgHistory.removeFirst()
                    if (allFrameTimes.size > MAX_FRAME_TIMES_SIZE) allFrameTimes.removeFirst()
                    if (frameTimeTimed.size > MAX_HISTORY_SIZE) frameTimeTimed.removeFirst()
                }
                totalJank += frame?.jankCount ?: 0
                totalStutter += frame?.stutterCount ?: 0
                // v4.4.0: timestamped jank/stutter twins (cumulative) for FilteredMetricsCalculator.
                jankTimed.add(TimedSample(sampleSecond, totalJank.toDouble()))
                stutterTimed.add(TimedSample(sampleSecond, totalStutter.toDouble()))
                if (jankTimed.size > MAX_HISTORY_SIZE) jankTimed.removeFirst()
                if (stutterTimed.size > MAX_HISTORY_SIZE) stutterTimed.removeFirst()

                val currentElapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                // v4.1.0-perf: snapshot history lists only every 2 seconds (4 iterations)
                // instead of every 500ms. The scalar fields (fps, cpu, temps, etc.) still
                // update every cycle for responsive UI, but the heavy list copies that the
                // graphs consume only refresh at 0.5 Hz — imperceptible to the user.
                val snapshotHistories = iterCount % 4 == 0
                val prev = _liveMetrics.value
                _liveMetrics.value = LiveMetrics(
                    // v4.3.5: HUD shows the sticky last-known FPS during the
                    // ad-close transient (≤1.5s). Outside the window
                    // displayFps falls back to 0 and the HUD shows "--".
                    // History/report fields below intentionally use the raw
                    // [fps] so persisted data stays truthful.
                    elapsed = currentElapsed, fps = displayFps,
                    avgFps = if (fpsHistory.isNotEmpty()) fpsHistory.average() else 0.0,
                    frameTime = frame?.avgFrameTime ?: 0.0,
                    cpu = cpu,
                    memMb = lastMem?.totalMb ?: 0,
                    nativeMb = lastMem?.nativeMb ?: 0,
                    javaMb = lastMem?.javaMb ?: 0,
                    // v4.3.6: HUD `tempCpu` shows the user-facing temp. Prefer
                    // skin (case temp the user feels) over die (silicon, often
                    // alarming-looking 80-95°C under load but normal). Falls
                    // back to die or legacy `cpu` when skin is unavailable.
                    tempCpu = when {
                        !lastThermal.skin.isNaN() && lastThermal.skin > 0 -> lastThermal.skin
                        !lastThermal.dieCpu.isNaN() && lastThermal.dieCpu > 0 -> lastThermal.dieCpu
                        else -> lastThermal.cpu
                    },
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
                    allFrameTimes = if (snapshotHistories) allFrameTimes.toList() else prev.allFrameTimes,
                    // v4.5.0 — FPower live tile + history (design §9e). The scalar follows
                    // the same "0.0 when unavailable" convention as the legacy thermal
                    // tiles: HUD reads as "--" instead of a misleading numeric value. List
                    // snapshots mirror the `snapshotHistories` 0.5 Hz gate so the graphs
                    // refresh at the same cadence as the thermal histories.
                    fpower = if (lastFPower.fpowerAvailable) lastFPower.fpowerMwPerFrame else 0.0,
                    fpowerHistory = if (snapshotHistories) fpowerHistory.toList() else prev.fpowerHistory,
                    fpowerTimed = if (snapshotHistories) fpowerTimed.toList() else prev.fpowerTimed,
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

            // v4.4.0: stop auto event detection. Force-closes any still-open
            // events with `endInferred=true` so the report can disclose the
            // synthesized boundary.
            eventDetector?.stop()
            eventDetector = null

            // v4.2.5: surface processing status to the UI so the user knows the
            // post-capture pipeline is running (~30-90s for long sessions). The
            // overlay disappears when _processingStatus goes back to null.
            _processingStatus.value = "Deteniendo grabacion..."

            // v4.0.0: platform-aware recording stop + pull
            val videoPath: String
            if (isIosDevice) {
                // iOS: stop sidecar capture → it returns the stitched video path
                _processingStatus.value = "Descargando video del dispositivo iOS..."
                val iosVideoPath = if (iosScreenCaptureId != null) {
                    sidecarLifecycle?.client?.stopScreenRecord(device.id, iosScreenCaptureId)
                } else null
                videoPath = iosVideoPath ?: ""
            } else {
                // Android: stop adb screenrecord, pull segments, concat
                adb.stopScreenRecord(recordProcess)
                recordProcess = null
                _processingStatus.value = "Esperando que el dispositivo cierre el archivo de video..."
                delay(3000) // let last segment finalize on device
                _processingStatus.value = "Descargando video del dispositivo..."
                val recordings = adb.pullRecordings(device.id, sessionId, videoDir)

                // v4.2.3: Surface "ffmpeg missing" as a distinct, actionable warning
                // BEFORE attempting concat. Previously concat would return null and the
                // code fell through to "concat failed, falling back to first segment"
                // which gave the user a 2:56 video for a 30-min session with no hint
                // of why. Now the user gets a specific install-ffmpeg message AND we
                // still preserve the first segment as best-effort playback.
                val ffmpegMissing = recordings.size > 1 &&
                    com.gameperf.desktop.core.ToolResolver.find("ffmpeg") == null

                videoPath = if (recordings.isNotEmpty()) {
                    val unified = java.io.File(videoDir, "video_${sessionId}.mp4")
                    val result = if (recordings.size > 1) {
                        _processingStatus.value = "Uniendo ${recordings.size} segmentos de video con ffmpeg..."
                        adb.concatSegments(recordings, unified)
                    } else {
                        if (adb.isValidVideoFile(recordings.first())) recordings.first() else null
                    }
                    if (result != null) {
                        result.absolutePath
                    } else if (ffmpegMissing) {
                        val anyValid = recordings.firstOrNull { adb.isValidVideoFile(it) } ?: recordings.first()
                        System.err.println("AppViewModel: ffmpeg not found, video kept as ${recordings.size} separate segments")
                        _captureWarning.value = "ffmpeg no esta instalado — el video se grabo en ${recordings.size} segmentos " +
                            "separados de ~3 min cada uno. Solo se muestra el primero. " +
                            "Instala ffmpeg (con winget install Gyan.FFmpeg, scoop install ffmpeg, o brew install ffmpeg) " +
                            "y al reabrir la app los segmentos se juntaran automaticamente."
                        anyValid.absolutePath
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
            // v4.3.6: skin and die history maxes for the new thermal split.
            // tempCpuHistory carries the user-facing temp (skin if available,
            // else die fallback) for back-compat with old `.gameperf` exports.
            val maxTempSkin = tempSkinHistory.maxOrNull() ?: 0.0
            val maxTempDieCpu = tempDieCpuHistory.maxOrNull() ?: 0.0
            val totalDrops = missedEnd - missedStart

            // ═══ v4.4.0 — Filtered + Raw aggregates (auto event detection) ═══
            //
            // Build a FilterInput from the timed twin series populated during the
            // polling loop, then compute BOTH views in one call:
            //   - raw      → whole-session aggregates (legacy semantics).
            //   - filtered → samples inside detected event windows excluded.
            //
            // Excessive-filter guardrail (FLT-005): if more than 70% of samples
            // would be excluded, computeWithFallback swaps `filtered` for `raw`
            // and flips `excessiveFiltering = true`. We surface that as a warning
            // so the report shows raw values with a banner instead of a sample
            // so small the percentiles become meaningless.
            //
            // When auto-detection is OFF (or no events were detected on Android,
            // or iOS path), `_events.value` is empty ⇒ filtered ≡ raw and
            // grading behaviour is byte-equivalent to pre-v4.4.0.
            val filterInput = FilterInput(
                fpsTimed = fpsTimed.toList(),
                cpuTimed = cpuTimed.toList(),
                memTimed = memTimed.toList(),
                nativeTimed = nativeTimed.toList(),
                javaTimed = javaTimed.toList(),
                tempCpuTimed = tempCpuTimed.toList(),
                tempGpuTimed = tempGpuTimed.toList(),
                tempSkinTimed = tempSkinTimed.toList(),
                tempDieCpuTimed = tempDieCpuTimed.toList(),
                frameTimeTimed = frameTimeTimed.toList(),
                jankTimed = jankTimed.toList(),
                stutterTimed = stutterTimed.toList(),
                captureStartTime = captureStartTime,
                sessionEndMs = (finalElapsed * 1000.0).toLong(),
            )
            val filterResult = FilteredMetricsCalculator.computeWithFallback(
                filterInput,
                _events.value,
            )
            if (filterResult.excessiveFiltering) {
                _detectorWarnings.value = _detectorWarnings.value +
                    "Más del 70% de la sesión fue excluida por eventos detectados; mostrando métricas brutas en su lugar."
            }

            // ═══ Grading (v4.3.4: extracted to FinalScoreCalculator) ═══
            //
            // The grading logic — proportional FPS thresholds (v4.2.6), per-game
            // jank ratio (v4.2.7), stutter/memory/thermal/CPU penalties — used to
            // live inline here. v4.3.4 extracted it to a pure object under
            // core/grading following the CLAUDE.md rule "tests puros sin mocks":
            // any function with complex logic must have a pure extractable version.
            // See FinalScoreCalculator.kt for the full penalty table and behavior
            // notes; FinalScoreCalculatorTest.kt covers every bucket boundary.
            //
            // v4.4.0: GradingInput now consumes FILTERED aggregates. Ad-induced
            // FPS spikes no longer contaminate the score. When no events are
            // detected, `filterResult.filtered` ≡ raw and the grade is unchanged.
            val targetFps = inferGameTargetFps(avgFps = avgFps, maxFps = maxFps)
            val gradedAgg = filterResult.filtered
            val gradingResult = FinalScoreCalculator.compute(
                GradingInput(
                    targetFps = targetFps,
                    p50 = gradedAgg.p50,
                    p5 = gradedAgg.p5,
                    totalJank = totalJank.toLong(),
                    finalElapsed = finalElapsed.toDouble(),
                    totalStutter = totalStutter,
                    peakMem = gradedAgg.peakMem,
                    // v4.3.6: maxTempCpu is now semantically "user-facing temp"
                    // (skin if available, else die fallback). The dual thermal
                    // threshold uses peakThermalDie to fire when only the
                    // silicon is overheated.
                    maxTempCpu = gradedAgg.maxTempCpu,
                    avgCpu = gradedAgg.avgCpu,
                    peakThermalDie = gradedAgg.maxTempDieCpu,
                )
            )
            val score = gradingResult.score
            val grade = gradingResult.grade
            // Kept as MutableList because downstream call sites (HardwareScoring,
            // ReportGenerator, SessionResult) currently only read it, but a future
            // change might want to append device-tier-specific messages here. Cheap
            // safety guarantee — `.toMutableList()` is O(n) on a small list.
            val problems = gradingResult.problems.toMutableList()

            // Device-specific grade
            val tier = com.gameperf.desktop.core.HardwareScoring.detectTier(_deviceInfo.value?.gpu ?: "")
            val (deviceGrade, deviceScore) = com.gameperf.desktop.core.HardwareScoring.calculateDeviceGrade(
                avgFps = avgFps,
                p1Fps = p1,
                tier = tier,
                problems = problems,
                // v4.3.6: pass the inferred game target so the device-adjusted
                // grade is proportional too. Path B of the dual-grading fix.
                targetFps = targetFps,
            )

            // Snapshot markers before generating report
            val sessionMarkers = _markers.value

            // ═══ v4.4.0 — Conclusions pillar (T4.13 + T4.14) ═══
            //
            // Run the deterministic heuristic rule catalog over filtered + raw
            // aggregates. The engine is pure: same input → same output, no I/O.
            //
            // Pre-flight short-circuit (T4.14): sessions shorter than 30s OR
            // with fewer than 60 raw samples can't produce statistically
            // meaningful trends. We emit a single INFO conclusion explaining
            // that and skip the rule catalog entirely.
            //
            // For the regular path, we filter the timed twins (mem/temp/fps)
            // using the same union of padded event ranges Phase 3 applied.
            // This lets trend rules (MemoryGrowthRule, etc.) operate on the
            // same "kept" sample set the dashboard shows.
            val conclusions: List<Conclusion> = if (
                finalElapsed < 30 || filterResult.raw.sampleCount < 60
            ) {
                listOf(
                    Conclusion(
                        ruleId = "insufficient-data",
                        severity = Severity.INFO,
                        headline = "La sesión es demasiado corta para extraer conclusiones fiables " +
                            "(${finalElapsed}s).",
                        recommendation = "Para análisis representativos, captura sesiones de al menos " +
                            "1 minuto en condiciones normales de juego.",
                    )
                )
            } else {
                // Convert unioned absolute ranges → capture-relative ms so we
                // can match against TimedSample.second (which is capture-rel).
                val unionAbs = FilteredMetricsCalculator.unionRanges(_events.value)
                val relativeRanges = unionAbs.mapNotNull { range ->
                    val relStart = (range.startMs - captureStartTime).coerceAtLeast(0L)
                    val relEnd = (range.endMs - captureStartTime).coerceAtLeast(0L)
                    if (relEnd <= 0L) null else (relStart..relEnd)
                }

                fun List<TimedSample>.outsideEventWindows(): List<TimedSample> =
                    if (relativeRanges.isEmpty()) this
                    else filter { sample ->
                        val ms = sample.second * 1000L
                        relativeRanges.none { ms in it }
                    }

                val conclusionInput = ConclusionInput(
                    filtered = filterResult.filtered,
                    raw = filterResult.raw,
                    targetFps = targetFps,
                    deviceTier = tier,
                    events = _events.value,
                    sessionDurationS = finalElapsed,
                    memTimedFiltered = memTimed.toList().outsideEventWindows(),
                    tempCpuTimedFiltered = tempCpuTimed.toList().outsideEventWindows(),
                    fpsTimedFiltered = fpsTimed.toList().outsideEventWindows(),
                    // v4.4.1 (discovery #274 + Q2 frozen): sourced from the
                    // last per-tick snapshot. When the thermal pipeline could
                    // not classify any zone (vendor catalog gap, permission
                    // denied, OOR), `false` propagates to the 3 thermal-derived
                    // rules so they short-circuit instead of emitting a
                    // fabricated "device has headroom" claim.
                    thermalAvailable = lastThermal.thermalAvailable,
                )
                ConclusionEngine.run(conclusionInput)
            }

            // Generate HTML report (wrapped in try-catch to avoid crash on report failure)
            _processingStatus.value = "Generando reporte HTML..."
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
                    // v4.3.6: maxTempCpu is now the user-facing peak (skin if
                    // available, else die fallback). maxTempSkin lets the
                    // report card render a separate "Die máx" sub-line and
                    // pick the right throttle copy (42°C skin vs 95°C die).
                    maxTempCpu = if (maxTempDieCpu > 0) maxTempDieCpu else maxTempCpu,
                    maxTempGpu = maxTempGpu,
                    batteryStart = batteryStart, batteryEnd = batteryEnd,
                    frameDrops = totalDrops, jank = totalJank, stutter = totalStutter,
                    problems = problems, isWifi = isWifiMode,
                    deviceGrade = deviceGrade, deviceScore = deviceScore, deviceTier = tier.label,
                    fpsTimestamps = fpsTimed.map { it.second to it.value.toInt() },
                    markers = sessionMarkers,
                    targetFps = targetFps,
                    maxTempSkin = maxTempSkin,
                    // v4.4.0 — auto event detection / dual-view / conclusions payload.
                    // Phase 6 (T6.1-T6.6) renders these in the HTML report. The detection
                    // mode default is MANUAL_ONLY; Phase 5 (T5.9) will set it to
                    // ANDROID_FULL or IOS_PARTIAL once the iOS branch wires in.
                    events = _events.value,
                    conclusions = conclusions,
                    filteredAggregates = filterResult.filtered,
                    rawAggregates = filterResult.raw,
                    detectionMode = if (eventDetector != null) DetectionMode.ANDROID_FULL else DetectionMode.MANUAL_ONLY,
                    detectorWarnings = _detectorWarnings.value,
                    captureStartMs = captureStartTime,
                    // v4.4.1 (temperature-not-shown, Phase 6 wire): propagate the
                    // last-known thermal availability flag + diagnostic payload
                    // so the report renders "N/D" + a Spanish-tuteo-formal banner
                    // listing the raw vendor zone names instead of a misleading
                    // "0°C". Defaults on the generator preserve baseline rendering
                    // for legacy fixtures (ReportRenderingTest) and pre-v4.4.1
                    // history re-loads where lastThermal.diagnostic is null.
                    thermalAvailable = lastThermal.thermalAvailable,
                    thermalDiagnostic = lastThermal.diagnostic,
                    // v4.5.0 (fpower-metric, Batch 5 wire): propagate the per-
                    // session FPower payload to the report. Defaults on the
                    // generator preserve legacy rendering for pre-v4.5.0 history
                    // re-loads where fpowerAvailable=true / history empty.
                    fpowerHistory = fpowerHistory.toList(),
                    fpowerAvg = if (fpowerHistory.isNotEmpty()) fpowerHistory.average() else 0.0,
                    fpowerPeak = fpowerHistory.maxOrNull() ?: 0.0,
                    fpowerAvailable = lastFPower.fpowerAvailable,
                    fpowerDiagnostic = lastFPower.diagnostic,
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
                markers = sessionMarkers,
                // v4.4.0 — auto event detection payload.
                events = _events.value,
                rawAggregates = filterResult.raw,
                filteredAggregates = filterResult.filtered,
                conclusions = conclusions,
                // v4.4.1 — set detectionMode HERE so the pendingEntry builder below can
                // copy it from _result.value verbatim. Without this line, detectionMode
                // would always default to MANUAL_ONLY and the persisted history would
                // lie about whether the auto-detector actually ran (Bug 2 fidelity fix).
                detectionMode = if (eventDetector != null) DetectionMode.ANDROID_FULL else DetectionMode.MANUAL_ONLY,
                // v4.5.0 — FPower aggregates + history payload (design §9f, spec FPW-008).
                // Empty history → 0.0 for both avg and peak (mirrors the post-loop guard at
                // `maxTempCpu = tempCpuHistory.maxOrNull() ?: 0.0`).
                fpowerAvailable = lastFPower.fpowerAvailable,
                fpowerDiagnostic = lastFPower.diagnostic,
                fpowerHistory = fpowerHistory.toList(),
                fpowerTimed = fpowerTimed.toList(),
                fpowerAvg = if (fpowerHistory.isNotEmpty()) fpowerHistory.average() else 0.0,
                fpowerPeak = fpowerHistory.maxOrNull() ?: 0.0,
            )

            // P95 frame time
            val p95ft = if (ftSorted.isNotEmpty()) ftSorted[(ftSorted.size * 0.95).toInt().coerceIn(0, ftSorted.size - 1)] else 0.0

            // Save to history. The legacy overload returns the entries that the
            // retention cap pushed off the bottom of the list. We forward each
            // evicted entry to FileCleanup so its HTML report and all video segments
            // disappear from disk in the same atomic step.
            //
            // v4.3.7 — Layer 4: build the HistoryEntry up front so we can analyze it
            // through SessionHistory.analyzeEvictionRisk BEFORE persisting. If the
            // analysis says we'd silently evict a real non-favorite session, we defer
            // the insert by raising _evictionPending and let the UI dialog drive the
            // resolution via confirmEviction(EvictionDecision). Fakes still go through
            // the silent path unchanged.
            _processingStatus.value = "Guardando sesion en el historial..."
            val captureTag = _sessionTag.value
            val captureCompetitor = _competitorName.value
            val pendingEntry = SessionHistory.HistoryEntry(
                id = System.currentTimeMillis().toString(),
                name = if (captureTag == SessionHistory.SessionTag.COMPETITION && captureCompetitor.isNotEmpty())
                    "$captureCompetitor - ${_deviceInfo.value?.model ?: device.model}"
                else "$pkg - ${_deviceInfo.value?.model ?: device.model}",
                gamePackage = pkg,
                deviceModel = _deviceInfo.value?.model ?: device.model,
                grade = grade,
                deviceGrade = deviceGrade,
                avgFps = avgFps,
                duration = finalElapsed,
                date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(java.util.Date()),
                reportPath = reportPath,
                videoPath = videoPath,
                tag = captureTag,
                competitorName = captureCompetitor,
                p1Fps = p1, p5Fps = p5,
                avgFrameTime = if (allFrameTimes.isNotEmpty()) allFrameTimes.average() else 0.0,
                p95FrameTime = p95ft, p99FrameTime = p99ft,
                peakMemMb = peakMem, avgCpu = avgCpu,
                maxTemp = maxTempCpu, score = score,
                markers = sessionMarkers,
                fpsTimed = fpsTimed.map { it.second to it.value.toInt() },
                // v4.4.1 — additive named-args copying the auto-event-detection payload from
                // the in-memory SessionResult / ViewModel state into the persisted HistoryEntry.
                // Bug 2 (auto-event-detection-not-marking): the v4.4.0 schema bump promised
                // these fields but the builder dropped them on the way to disk. Read BEFORE the
                // `captureStartTime = 0L` reset on the next block — the value is still valid here.
                events = _result.value.events,
                detectionMode = _result.value.detectionMode,
                detectorWarnings = _detectorWarnings.value,
                rawAggregates = _result.value.rawAggregates,
                filteredAggregates = _result.value.filteredAggregates,
                conclusions = _result.value.conclusions,
                captureStartMs = captureStartTime,
                // v4.4.1 (temperature-not-shown, Q2): persist the per-tick availability
                // flag from the last thermal sample. AdbThermalParser sets it to false
                // when the device exposes no classifiable CPU/SKIN zone (unsupported
                // vendor like Pixel XL pre-T0 + Tab A8 pre-T0, permission denied, all
                // temps OOR). Default `true` on the var preserves v4.3.x behavior: a
                // session that NEVER captured thermal at all (zero ticks, e.g. ultra
                // short captures or an iOS-only run that bypassed the Android branch)
                // still records `true` and the report renders the legacy 0°C cell —
                // matching pre-v4.4.1 user experience.
                thermalAvailable = lastThermal.thermalAvailable,
                // v4.5.0 — FPower mirror of the session payload (design §9f, spec FPW-008).
                // Pull from _result.value so a future refactor that drops a field at the
                // SessionResult build site here gets caught by the round-trip tests at the
                // HistoryEntry boundary. Mirrors how detectionMode/events/aggregates are
                // sourced from _result.value (Bug 2 lesson — single source of truth).
                fpowerAvailable = _result.value.fpowerAvailable,
                fpowerDiagnostic = _result.value.fpowerDiagnostic,
                fpowerHistory = _result.value.fpowerHistory,
                fpowerTimed = _result.value.fpowerTimed,
                fpowerAvg = _result.value.fpowerAvg,
                fpowerPeak = _result.value.fpowerPeak,
            )
            val deferredForDialog = analyzePendingEviction(pendingEntry)
            if (!deferredForDialog) {
                val evicted = SessionHistory.addEntry(pendingEntry)
                evicted.forEach { FileCleanup.deleteSessionFiles(it) }
            }
            _history.value = SessionHistory.load()

            captureStartTime = 0L
            // v4.4.1: keep the public mirror in sync — UI uses 0L as "no active capture".
            _captureStartMs.value = 0L
            _isCapturing.value = false
            // v4.2.5: clear the processing-status overlay now that we're switching
            // to RESULTS — the user is about to see all the data and doesn't need
            // the "procesando..." spinner anymore.
            _processingStatus.value = null
            _screen.value = AppScreen.RESULTS
        }
    }

    fun stopCapture() {
        shouldStop = true
        _statusMessage.value = "Deteniendo captura..."
        // v4.2.5: also surface the status as the processing overlay text so the
        // user sees feedback IMMEDIATELY when clicking "Detener", before the loop
        // exits and the post-capture pipeline starts emitting its own statuses.
        _processingStatus.value = "Deteniendo captura..."
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
    // Session sharing via .gameperf files (v4.2.8 — replaced Drive sync)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Export a session from the history to a .gameperf file at [destFile].
     * The UI typically obtains [destFile] via a native "Save As" dialog so the
     * user chooses where it lands (Desktop, shared-folder, USB, etc.).
     *
     * Returns true on success. On failure, the error message is surfaced via
     * [sessionPackMessage] for the UI to display.
     *
     * The resulting file is a self-contained ZIP — the recipient can double-
     * click it in a File Explorer to see manifest.json and report.html, or
     * open it in any unzip tool. The app also supports re-importing it via
     * [importSessionPackFromFile].
     */
    fun exportSessionPack(entryId: String, destFile: File) {
        val entry = _history.value.firstOrNull { it.id == entryId } ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val tmpDir = File(System.getProperty("java.io.tmpdir"), "gameperf_export")
                val packFile = com.gameperf.desktop.cloud.SessionPack.export(entry, tmpDir)
                // Move/copy to the user-chosen destination, then clean up the temp.
                packFile.copyTo(destFile, overwrite = true)
                packFile.delete()
                _sessionPackMessage.value = "Sesion exportada a ${destFile.name}"
            } catch (e: Exception) {
                _sessionPackMessage.value = "Error exportando: ${e.message}"
            }
        }
    }

    /**
     * Import a .gameperf file from disk into the local history. The file can
     * come from anywhere — a teammate shared it via Slack, email, or a USB
     * stick. [packFile] is the full path to the .gameperf the user selected
     * via a native "Open" dialog.
     *
     * Duplicates (same session id already in history) are silently skipped so
     * repeated imports are idempotent.
     */
    fun importSessionPackFromFile(packFile: File) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val reportsDir = File(System.getProperty("user.home"), "GamePerf Reports")
                val imported = com.gameperf.desktop.cloud.SessionPack.import(packFile, reportsDir)
                val existing = _history.value.any { it.id == imported.id }
                if (existing) {
                    _sessionPackMessage.value = "La sesion ya estaba en el historial, no se duplico"
                } else {
                    SessionHistory.addEntry(imported)
                    _history.value = SessionHistory.load()
                    _sessionPackMessage.value = "Sesion '${imported.name}' importada al historial"
                }
            } catch (e: Exception) {
                _sessionPackMessage.value = "Error importando: ${e.message}"
            }
        }
    }

    /** Clear the transient "exported / imported" message. Called by the UI
     *  after a brief display (typically 4-5 seconds). */
    fun clearSessionPackMessage() {
        _sessionPackMessage.value = null
    }

    // ===== Dependency Bootstrap actions (v4.2.14) =====

    /**
     * Trigger the in-app download of a missing tool (adb / ffmpeg).
     *
     * Resolves the official URL from [DependencyBootstrap.TOOL_URLS], invokes
     * [ToolInstaller.download] into the user-writable tools directory, and
     * updates [bootstrapProgress] / [bootstrapError] accordingly. On success,
     * removes the tool from [missingDeps] so the banner dismisses itself.
     *
     * Spec: "Download succeeds with progress feedback" — see in-app-dep-bootstrap spec.
     */
    fun installMissingDep(toolName: String) {
        val url = DependencyBootstrap.downloadUrl(toolName) ?: run {
            _bootstrapError.value = "URL desconocida para $toolName."
            return
        }
        scope.launch(Dispatchers.IO) {
            _bootstrapError.value = null
            _bootstrapProgress.value = DependencyBootstrap.BootstrapProgress.Downloading(0f)
            val targetDir = com.gameperf.desktop.core.UserToolsDir.base(
                isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
            )
            val sha = DependencyBootstrap.sha256(toolName)
            val result = com.gameperf.desktop.core.ToolInstaller.download(url, targetDir, sha)
            if (result.isSuccess) {
                _bootstrapProgress.value = DependencyBootstrap.BootstrapProgress.Completed
                // Refresh missing list — the tool is now in UserToolsDir.
                _missingDeps.value = DependencyBootstrap.check()
                // Re-check adb availability so the rest of init() can proceed.
                _adbAvailable.value = adb.isAvailable()
                if (_adbAvailable.value) {
                    _statusMessage.value = "$toolName instalado. Buscando dispositivos..."
                    refreshDevices()
                }
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Error desconocido"
                _bootstrapError.value = "Error al descargar $toolName: $msg. Verifica tu conexión o proxy."
                _bootstrapProgress.value = DependencyBootstrap.BootstrapProgress.Failed(msg)
            }
        }
    }

    /**
     * Open the official download page for [toolName] in the system browser.
     * Manual fallback when the in-app downloader fails (corporate proxy, etc.).
     */
    fun openToolDownloadUrl(toolName: String) {
        val url = DependencyBootstrap.downloadUrl(toolName) ?: return
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI(url))
            }
        } catch (e: Exception) {
            _bootstrapError.value = "No se pudo abrir el navegador: ${e.message}"
        }
    }

    /** Dismiss the bootstrap error banner. */
    fun dismissBootstrapError() {
        _bootstrapError.value = null
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
        // Restore FPS timeline so the chart below the video shows data
        _liveMetrics.value = LiveMetrics(
            fpsTimed = entry.fpsTimed.map { TimedSample(it.first, it.second.toDouble()) }
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

    // ===== v4.3.7 — Session-history loss prevention (Layer 4: dialog + recovery) =====

    /**
     * State raised by [analyzePendingEviction] when a new history insert would evict a
     * REAL non-favorite session. The HomeScreen observes this and shows the
     * EvictionConfirmDialog. The user resolves it via [confirmEviction].
     *
     * @property newEntry        the entry the user is trying to add (deferred until they decide)
     * @property evictableEntry  the existing entry that will be removed if the user confirms
     */
    data class EvictionPendingState(
        val newEntry: SessionHistory.HistoryEntry,
        val evictableEntry: SessionHistory.HistoryEntry,
    )

    private val _evictionPending = MutableStateFlow<EvictionPendingState?>(null)
    val evictionPending: StateFlow<EvictionPendingState?> = _evictionPending

    /**
     * Outcome of the recovery action; the UI uses this to show a status toast.
     * `null` means no recovery has been attempted yet (or it's been dismissed).
     */
    private val _recoveryStatus = MutableStateFlow<String?>(null)
    val recoveryStatus: StateFlow<String?> = _recoveryStatus

    /**
     * v4.3.7 — invoked by the HomeScreen "Recuperar de respaldo" button. Restores
     * `history.json` from the deepest still-usable backup (see
     * [SessionHistory.recoverFromBackup]) and pushes the result into [_history] so the
     * UI refreshes. Sets [_recoveryStatus] for the toast.
     */
    fun recoverHistoryFromBackup() {
        val report = SessionHistory.recoverFromBackup()
        _history.value = SessionHistory.load()
        _recoveryStatus.value = if (report.restoredFrom != null) {
            val ts = java.text.SimpleDateFormat("HH:mm").format(java.util.Date())
            "Restauradas ${report.entriesAfter} sesiones desde respaldo ($ts)"
        } else {
            "No hay respaldo con más datos que tu historial actual"
        }
    }

    /** Clears the recovery status toast after the UI dismisses it. */
    fun clearRecoveryStatus() {
        _recoveryStatus.value = null
    }

    /**
     * Resolution choice for the eviction confirmation dialog. The HomeScreen sends one
     * of these into [confirmEviction] when the user picks a button.
     */
    enum class EvictionDecision {
        /** "Marcar favorita" — promote the evictable to favorite, then re-attempt the insert. */
        FAVORITE_EXISTING,
        /** "Eliminar de todas formas" — proceed with the eviction. */
        EVICT,
        /** "Cancelar" — discard the new entry. */
        CANCEL,
    }

    /**
     * Resolve a pending eviction state with the user's [decision]. Idempotent if no
     * eviction is currently pending. The actual disk insert / favorite toggle happens here.
     */
    fun confirmEviction(decision: EvictionDecision) {
        val pending = _evictionPending.value ?: return
        when (decision) {
            EvictionDecision.FAVORITE_EXISTING -> {
                SessionHistory.toggleFavorite(pending.evictableEntry.id)
                // Re-insert the new entry — with the legacy entry now favorited, the next
                // analyzeEvictionRisk pass will pick a different evictable (or none).
                val evicted = SessionHistory.addEntry(pending.newEntry)
                evicted.forEach { FileCleanup.deleteSessionFiles(it) }
            }
            EvictionDecision.EVICT -> {
                val evicted = SessionHistory.addEntry(pending.newEntry)
                evicted.forEach { FileCleanup.deleteSessionFiles(it) }
            }
            EvictionDecision.CANCEL -> {
                // Nothing to do — the new entry was deferred and is now discarded.
            }
        }
        _history.value = SessionHistory.load()
        _evictionPending.value = null
    }

    /**
     * Pure VM-side wrapper around [SessionHistory.analyzeEvictionRisk]. Returns true
     * when the caller should defer the insert (i.e. a confirmation dialog has been raised);
     * false when the caller can proceed with the insert immediately.
     *
     * Sets [_evictionPending] as a side effect when confirmation is required, so the
     * UI Composable observing [evictionPending] can render the dialog.
     */
    fun analyzePendingEviction(candidate: SessionHistory.HistoryEntry): Boolean {
        val analysis = SessionHistory.analyzeEvictionRisk(_history.value, candidate)
        if (analysis is SessionHistory.EvictionAnalysis.ConfirmationRequired) {
            _evictionPending.value = EvictionPendingState(candidate, analysis.evictableEntry)
            return true
        }
        return false
    }

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
