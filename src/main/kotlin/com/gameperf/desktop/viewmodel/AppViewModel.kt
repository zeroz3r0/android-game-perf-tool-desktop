package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.core.AppVersion
import com.gameperf.desktop.core.AutoUpdater
import com.gameperf.desktop.core.CURRENT_VERSION
import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.report.ReportGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Desktop
import java.io.File

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

    @Volatile private var shouldStop = false
    @Volatile private var captureStartTime: Long = 0L
    private var captureJob: Job? = null
    private var pollingJob: Job? = null
    private var recordProcess: Process? = null
    private var recordSegment = 0
    private var recordJob: Job? = null

    fun init() {
        scope.launch {
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
            _updateError.value = "No se encontro archivo JAR en el release."
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
                    // Small delay to show 100%
                    delay(500)
                    AutoUpdater.applyUpdate(file)
                } else {
                    _updateError.value = "Error al descargar la actualizacion."
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
        shouldStop = false

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
            recordProcess = AdbBridge.startScreenRecord(device.id, sessionId, recordSegment)
            // screenrecord needs ~1s to actually start capturing frames
            delay(1500)

            // NOW start the clock - video and metrics are synced from this point
            val startTime = System.currentTimeMillis()
            captureStartTime = startTime

            // Chain recordings every ~175s (before 180s limit)
            recordJob = scope.launch {
                while (!shouldStop) {
                    delay(175_000)
                    if (shouldStop) break
                    AdbBridge.stopScreenRecord(recordProcess)
                    delay(1000)
                    recordSegment++
                    recordProcess = AdbBridge.startScreenRecord(device.id, sessionId, recordSegment)
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

            while (!shouldStop) {
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                if (durationSeconds > 0 && elapsed >= durationSeconds) break
                delay(1000)
                if (shouldStop) break

                // Run ADB commands with early-exit checks between each
                val frame = AdbBridge.captureFrames(device.id, pkg)
                if (shouldStop) break
                val mem = AdbBridge.captureMemory(device.id, pkg)
                if (shouldStop) break
                val cpu = AdbBridge.captureCpuPercent(device.id)
                if (shouldStop) break
                val thermal = AdbBridge.captureTemperature(device.id)
                if (shouldStop) break
                val battery = AdbBridge.getBatteryLevel(device.id)

                val sampleSecond = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                val fps = frame?.fps ?: 0
                if (fps > 0) {
                    fpsHistory.add(fps)
                    fpsTimed.add(TimedSample(sampleSecond, fps.toDouble()))
                }
                if (mem != null) { memHistory.add(mem.totalMb); nativeHistory.add(mem.nativeMb); javaHistory.add(mem.javaMb) }
                if (cpu > 0) cpuHistory.add(cpu)
                if (thermal.cpu > 0) tempCpuHistory.add(thermal.cpu)
                if (thermal.gpu > 0) tempGpuHistory.add(thermal.gpu)
                if (thermal.skin > 0) tempSkinHistory.add(thermal.skin)
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
                    memMb = mem?.totalMb ?: 0, nativeMb = mem?.nativeMb ?: 0, javaMb = mem?.javaMb ?: 0,
                    tempCpu = thermal.cpu, tempGpu = thermal.gpu,
                    tempBattery = thermal.battery, tempSkin = thermal.skin,
                    jankCount = totalJank, stutterCount = totalStutter,
                    battery = battery,
                    frameDrops = AdbBridge.getMissedFrames(device.id) - missedStart,
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
            recordJob?.cancel()
            AdbBridge.stopScreenRecord(recordProcess)
            recordProcess = null
            delay(2000) // let last segment finalize on device
            val recordings = AdbBridge.pullRecordings(device.id, sessionId, videoDir)
            val videoPath = recordings.firstOrNull()?.absolutePath ?: ""

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

            // Generate HTML report
            val reportPath = ReportGenerator.generate(
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

            // Save to history
            val captureTag = _sessionTag.value
            val captureCompetitor = _competitorName.value
            SessionHistory.addEntry(
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

    fun openHistoryReport(entry: SessionHistory.HistoryEntry) {
        openFile(entry.reportPath)
    }

    fun openHistoryVideo(entry: SessionHistory.HistoryEntry) {
        openFile(entry.videoPath)
    }

    fun goHome() {
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

    fun canCompare(): Boolean {
        val selected = _selectedForComparison.value
        if (selected.size < 2) return false
        val entries = _history.value.filter { it.id in selected }
        val hasOurs = entries.any { it.tag == SessionHistory.SessionTag.OUR_GAME }
        val hasCompetition = entries.any { it.tag == SessionHistory.SessionTag.COMPETITION }
        return hasOurs && hasCompetition
    }

    fun getSelectedEntries(): List<SessionHistory.HistoryEntry> {
        val selected = _selectedForComparison.value
        return _history.value.filter { it.id in selected }
    }

    fun goToComparison() {
        _screen.value = AppScreen.COMPARISON
    }

    fun generateComparisonReport(entries: List<SessionHistory.HistoryEntry>): String {
        return ReportGenerator.generateComparison(entries)
    }
}
