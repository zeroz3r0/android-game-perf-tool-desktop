package com.gameperf.desktop.core.ios

import kotlinx.coroutines.*
import java.net.ServerSocket

/**
 * Manages the lifecycle of the pymobiledevice3 FastAPI sidecar process.
 *
 * Responsibilities:
 * - Find a free port for the sidecar
 * - Spawn `python3 -m gameperf_sidecar --port {port}`
 * - Health check via GET /health every 5s
 * - Auto-restart on crash (up to [maxRestarts] times)
 * - Graceful shutdown via POST /shutdown + destroyForcibly fallback
 *
 * @param sidecarDir Path to the sidecar/ directory containing the Python package.
 * @param maxRestarts Maximum restart attempts before giving up.
 */
class SidecarLifecycle(
    private val sidecarDir: String,
    private val maxRestarts: Int = 3,
) {
    private var process: Process? = null
    private var port: Int = 0
    private var restartCount: Int = 0
    private var healthJob: Job? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** The SidecarClient configured to talk to this sidecar instance. */
    @Volatile
    private var _client: SidecarClient? = null
    val client: SidecarClient
        get() = _client ?: SidecarClient(baseUrl = "http://127.0.0.1:$port").also { _client = it }

    /**
     * Start the sidecar process. Blocks until the sidecar is healthy or fails.
     *
     * @param scope CoroutineScope for the health check loop.
     * @return true if the sidecar started successfully, false otherwise.
     */
    fun start(scope: CoroutineScope): Boolean {
        if (isRunning) return true

        // Check Python availability only when NOT using a bundled binary
        if (findBundledBinary(sidecarDir) == null && !isPythonAvailable()) {
            lastError = "Python 3 no encontrado. Instala Python 3.9+ para habilitar soporte iOS."
            return false
        }

        // Windows: iTunes/Apple Mobile Device Support must be installed for usbmuxd
        // This applies even when using the bundled binary (pymobiledevice3 inside still needs it).
        if (isWindows() && !isITunesAvailable()) {
            lastError = "iTunes no detectado. En Windows, instala iTunes para conectar dispositivos iOS."
            return false
        }

        port = findFreePort()
        if (port == 0) {
            lastError = "No se pudo encontrar un puerto libre para el sidecar iOS."
            return false
        }

        val started = spawnProcess()
        if (!started) return false

        // Wait for health check (up to 10s)
        val healthy = waitForHealth(timeoutMs = 10_000)
        if (!healthy) {
            lastError = "El sidecar iOS no respondio al health check tras 10 segundos."
            stop()
            return false
        }

        isRunning = true
        restartCount = 0

        // Start health monitoring in background
        healthJob = scope.launch {
            monitorHealth()
        }

        return true
    }

    /** Stop the sidecar gracefully. */
    fun stop() {
        isRunning = false
        healthJob?.cancel()
        healthJob = null

        // Try graceful shutdown
        try {
            client.shutdown()
            Thread.sleep(1000)
        } catch (_: Exception) { }

        // Force kill if still alive
        process?.let { proc ->
            if (proc.isAlive) {
                proc.destroyForcibly()
                proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        process = null
    }

    private fun spawnProcess(): Boolean {
        return try {
            val binary = findBundledBinary(sidecarDir)
            val pb = if (binary != null) {
                // v4.1.0+: PyInstaller binary — no Python needed, no PYTHONPATH
                ProcessBuilder(
                    binary.absolutePath,
                    "--port", port.toString(),
                    "--host", "127.0.0.1",
                )
            } else {
                // Fallback: Python source mode (dev / manual install)
                ProcessBuilder(
                    "python3", "-m", "gameperf_sidecar",
                    "--port", port.toString(),
                    "--host", "127.0.0.1",
                ).also { b ->
                    b.directory(java.io.File(sidecarDir))
                    val env = b.environment()
                    val existing = env["PYTHONPATH"] ?: ""
                    env["PYTHONPATH"] = if (existing.isEmpty()) sidecarDir else "$sidecarDir:$existing"
                }
            }
            pb.redirectErrorStream(true)

            process = pb.start()

            // Drain stdout/stderr in background to prevent pipe buffer deadlock
            Thread({
                try {
                    process?.inputStream?.bufferedReader()?.forEachLine { line ->
                        System.err.println("[ios-sidecar] $line")
                    }
                } catch (_: Exception) { }
            }, "ios-sidecar-drain").apply { isDaemon = true }.start()

            true
        } catch (e: Exception) {
            lastError = "No se pudo iniciar el sidecar: ${e.message}"
            false
        }
    }

    private fun waitForHealth(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (client.isHealthy()) return true
            Thread.sleep(500)
        }
        return false
    }

    private suspend fun monitorHealth() {
        while (isRunning) {
            delay(5000)
            if (!isRunning) break

            val healthy = withContext(Dispatchers.IO) { client.isHealthy() }
            if (!healthy && isRunning) {
                System.err.println("SidecarLifecycle: health check failed, process alive=${process?.isAlive}")

                if (restartCount < maxRestarts) {
                    restartCount++
                    System.err.println("SidecarLifecycle: restarting (attempt $restartCount/$maxRestarts)")
                    process?.destroyForcibly()

                    val restarted = withContext(Dispatchers.IO) { spawnProcess() }
                    if (restarted) {
                        val healthy2 = withContext(Dispatchers.IO) { waitForHealth(10_000) }
                        if (!healthy2) {
                            lastError = "El sidecar iOS no se recupero tras reinicio $restartCount/$maxRestarts"
                            isRunning = false
                        }
                    } else {
                        isRunning = false
                    }
                } else {
                    lastError = "El sidecar iOS murio tras $maxRestarts intentos de reinicio."
                    isRunning = false
                }
            }
        }
    }

    companion object {
        /** Find a free TCP port. Returns 0 on failure. */
        internal fun findFreePort(): Int {
            return try {
                ServerSocket(0).use { it.localPort }
            } catch (_: Exception) {
                0
            }
        }

        /**
         * Look for the PyInstaller-bundled binary next to the JAR or inside sidecarDir.
         * Returns null if only the Python source layout is available.
         */
        internal fun findBundledBinary(sidecarDir: String? = null): java.io.File? {
            val exeName = if (isWindows()) "gameperf-sidecar.exe" else "gameperf-sidecar"
            val candidates = mutableListOf<java.io.File>()

            // 1. In sidecarDir itself (e.g. sidecar/gameperf-sidecar)
            if (sidecarDir != null) {
                candidates += java.io.File(sidecarDir, exeName)
            }
            // 2. Next to the JAR on java.class.path
            val classPath = System.getProperty("java.class.path", "")
            classPath.split(java.io.File.pathSeparator)
                .firstOrNull { it.endsWith(".jar") }
                ?.let { java.io.File(it).parentFile?.resolve(exeName) }
                ?.let { candidates += it }
            // 3. macOS .app bundle — Contents/app/
            val appPath = System.getProperty("jpackage.app-path")
            if (appPath != null) {
                val contentsDir = java.io.File(appPath).parentFile?.parentFile
                if (contentsDir != null) {
                    candidates += java.io.File(contentsDir, "app/$exeName")
                    candidates += java.io.File(contentsDir, "Resources/$exeName")
                }
            }
            // 4. user.dir (CWD — covers dev scenarios where binary was built locally)
            candidates += java.io.File(System.getProperty("user.dir"), exeName)

            return candidates.firstOrNull { it.exists() && it.canExecute() }
        }

        /** Check if python3 is available on PATH. */
        internal fun isPythonAvailable(): Boolean {
            return try {
                val proc = ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true)
                    .start()
                val exited = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                exited && proc.exitValue() == 0
            } catch (_: Exception) {
                false
            }
        }

        /** Returns true if running on Windows. */
        internal fun isWindows(): Boolean =
            System.getProperty("os.name")?.lowercase()?.contains("win") == true

        /**
         * Check if iTunes / Apple Mobile Device Support is installed on Windows.
         *
         * On Windows, pymobiledevice3 needs the Apple usbmuxd driver that comes
         * bundled with iTunes. Without it, device listing returns empty.
         *
         * Detection: check for the `AppleMobileDeviceService` Windows service,
         * or for the iTunes executable in Program Files.
         */
        internal fun isITunesAvailable(): Boolean {
            if (!isWindows()) return true  // Not applicable on Mac/Linux
            return try {
                // Check for Apple Mobile Device Service via sc query
                val proc = ProcessBuilder("sc", "query", "Apple Mobile Device Service")
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText()
                val exited = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                if (exited && output.contains("RUNNING")) return true

                // Fallback: check for iTunes.exe in common locations
                val paths = listOf(
                    "C:\\Program Files\\iTunes\\iTunes.exe",
                    "C:\\Program Files (x86)\\iTunes\\iTunes.exe",
                    System.getenv("LOCALAPPDATA")?.let { "$it\\Microsoft\\WindowsApps\\iTunes.exe" },
                ).filterNotNull()
                paths.any { java.io.File(it).exists() }
            } catch (_: Exception) {
                false
            }
        }
    }
}
