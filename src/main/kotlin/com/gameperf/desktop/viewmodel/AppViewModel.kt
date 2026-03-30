package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.report.ReportGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Desktop
import java.io.File

enum class AppScreen { HOME, CAPTURING, RESULTS }

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
    val deviceTier: String = ""
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

    @Volatile private var shouldStop = false
    private var captureJob: Job? = null
    private var pollingJob: Job? = null
    private var recordProcess: Process? = null
    private var recordSegment = 0
    private var recordJob: Job? = null

    fun init() {
        scope.launch {
            _adbAvailable.value = AdbBridge.isAvailable()
            if (!_adbAvailable.value) {
                _statusMessage.value = "ADB no encontrado. Instala Android SDK."
                return@launch
            }
            _statusMessage.value = "ADB disponible. Buscando dispositivos..."
            refreshDevices()
        }
        startDevicePolling()
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
        shouldStop = false

        captureJob = scope.launch {
            val batteryStart = AdbBridge.getBatteryLevel(device.id)
            val missedStart = AdbBridge.getMissedFrames(device.id)
            val isWifiMode = _isWifi.value
            if (!isWifiMode) AdbBridge.disableCharging(device.id)

            // Start video recording
            val videoDir = File(System.getProperty("user.home"), "GamePerf Reports")
            videoDir.mkdirs()
            AdbBridge.cleanRecordings(device.id)
            recordSegment = 0
            recordProcess = AdbBridge.startScreenRecord(device.id, recordSegment)

            // Chain recordings every ~175s (before 180s limit)
            recordJob = scope.launch {
                while (!shouldStop) {
                    delay(175_000)
                    if (shouldStop) break
                    AdbBridge.stopScreenRecord(recordProcess)
                    delay(1000)
                    recordSegment++
                    recordProcess = AdbBridge.startScreenRecord(device.id, recordSegment)
                }
            }

            var elapsed = 0
            val fpsHistory = mutableListOf<Int>()
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

            while (!shouldStop && (durationSeconds <= 0 || elapsed < durationSeconds)) {
                delay(1000)
                if (shouldStop) break
                elapsed++

                val frame = AdbBridge.captureFrames(device.id, pkg)
                val mem = AdbBridge.captureMemory(device.id, pkg)
                val cpu = AdbBridge.captureCpuPercent(device.id)
                val thermal = AdbBridge.captureTemperature(device.id)
                val battery = AdbBridge.getBatteryLevel(device.id)

                val fps = frame?.fps ?: 0
                if (fps > 0) fpsHistory.add(fps)
                if (mem != null) { memHistory.add(mem.totalMb); nativeHistory.add(mem.nativeMb); javaHistory.add(mem.javaMb) }
                if (cpu > 0) cpuHistory.add(cpu)
                if (thermal.cpu > 0) tempCpuHistory.add(thermal.cpu)
                if (thermal.gpu > 0) tempGpuHistory.add(thermal.gpu)
                if (thermal.skin > 0) tempSkinHistory.add(thermal.skin)
                if (frame != null && frame.avgFrameTime > 0) {
                    frameTimeAvgHistory.add(frame.avgFrameTime)
                    // Simulate individual frame times from avg (for histogram)
                    allFrameTimes.add(frame.avgFrameTime)
                }
                totalJank += frame?.jankCount ?: 0
                totalStutter += frame?.stutterCount ?: 0

                _liveMetrics.value = LiveMetrics(
                    elapsed = elapsed, fps = fps,
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

            // Stop recording and pull videos
            recordJob?.cancel()
            AdbBridge.stopScreenRecord(recordProcess)
            recordProcess = null
            delay(2000) // let last segment finalize on device
            val recordings = AdbBridge.pullRecordings(device.id, videoDir)
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

            // Generate HTML report
            val reportPath = ReportGenerator.generate(
                pkg = pkg, info = _deviceInfo.value, grade = grade, score = score, duration = elapsed,
                fpsHistory = fpsHistory, memHistory = memHistory, nativeHistory = nativeHistory,
                javaHistory = javaHistory, cpuHistory = cpuHistory,
                tempCpuHistory = tempCpuHistory, tempGpuHistory = tempGpuHistory, tempSkinHistory = tempSkinHistory,
                frameTimeHistory = frameTimeAvgHistory, allFrameTimes = allFrameTimes,
                avgFps = avgFps, minFps = minFps, maxFps = maxFps,
                p1 = p1, p5 = p5, p50 = p50, p90 = p90, p99 = p99,
                avgFrameTime = if (allFrameTimes.isNotEmpty()) allFrameTimes.average() else 0.0,
                p99FrameTime = p99ft,
                peakMem = peakMem, avgCpu = avgCpu, maxCpu = maxCpu,
                maxTempCpu = maxTempCpu, maxTempGpu = maxTempGpu,
                batteryStart = batteryStart, batteryEnd = batteryEnd,
                frameDrops = totalDrops, jank = totalJank, stutter = totalStutter,
                problems = problems, isWifi = isWifiMode,
                deviceGrade = deviceGrade, deviceScore = deviceScore, deviceTier = tier.label
            )

            _result.value = SessionResult(
                gamePackage = pkg, deviceModel = _deviceInfo.value?.model ?: device.model,
                duration = elapsed, grade = grade,
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
                deviceGrade = deviceGrade, deviceScore = deviceScore, deviceTier = tier.label
            )

            _isCapturing.value = false
            _screen.value = AppScreen.RESULTS
        }
    }

    fun stopCapture() { shouldStop = true }

    fun openReport() {
        val path = _result.value.reportPath
        if (path.isNotEmpty()) {
            try {
                val file = File(path)
                if (file.exists()) Desktop.getDesktop().browse(file.toURI())
            } catch (_: Exception) {
                try {
                    val os = System.getProperty("os.name").lowercase()
                    when {
                        os.contains("mac") -> Runtime.getRuntime().exec(arrayOf("open", path))
                        os.contains("win") -> Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", "", path))
                        else -> Runtime.getRuntime().exec(arrayOf("xdg-open", path))
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun openVideo() {
        val path = _result.value.videoPath
        if (path.isNotEmpty()) {
            try {
                val file = File(path)
                if (file.exists()) Desktop.getDesktop().open(file)
            } catch (_: Exception) {
                try {
                    val os = System.getProperty("os.name").lowercase()
                    when {
                        os.contains("mac") -> Runtime.getRuntime().exec(arrayOf("open", path))
                        os.contains("win") -> Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", "", path))
                        else -> Runtime.getRuntime().exec(arrayOf("xdg-open", path))
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun goHome() {
        _screen.value = AppScreen.HOME
        _liveMetrics.value = LiveMetrics()
        recordJob?.cancel()
        AdbBridge.stopScreenRecord(recordProcess)
        recordProcess = null
        refreshDevices()
    }
}
