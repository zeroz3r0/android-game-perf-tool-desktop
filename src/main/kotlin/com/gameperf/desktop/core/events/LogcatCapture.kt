package com.gameperf.desktop.core.events

import com.gameperf.desktop.core.AdbBridgeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Streams logcat lines from a device via [AdbBridgeApi.startLogcat].
 *
 * Owns a single `adb logcat` [Process] for its lifetime. Reads lines on
 * [Dispatchers.IO] and invokes [onLine] for each successfully parsed
 * [LogLine]. Malformed lines are silently dropped (the parser already
 * returns null on bad input — callers don't need to differentiate noise
 * from signal at this layer).
 *
 * ### Gap detection
 *
 * The reader tracks the wall-clock receive time of every line. If no line
 * arrives for [GAP_THRESHOLD_MS] ms, [onGap] is invoked once with the gap
 * duration when the next line finally arrives. The orchestrating
 * `EventDetector` uses this to flag detection windows that span a gap as
 * `Confidence.LOW` (an SDK signal could have happened during the silent
 * window without us seeing it).
 *
 * ### Lifecycle
 *
 * - [start] is idempotent: calling twice without [stop] in between returns
 *   `false` on the second call.
 * - [stop] forcibly destroys the process and cancels the reader coroutine.
 *   Calling [stop] before [start] or after a previous [stop] is a no-op.
 * - The [running] [StateFlow] reflects the current state and transitions
 *   `false → true` on successful start, `true → false` on any of: stop call,
 *   process death, IO exception, end-of-stream.
 *
 * ### Threading
 *
 * Reading happens on [Dispatchers.IO] (blocking BufferedReader). The
 * [onLine] / [onGap] callbacks fire on that same dispatcher — callers that
 * need main-thread work must marshal via their own dispatcher.
 *
 * @since v4.4.0
 */
internal class LogcatCapture(
    private val bridge: AdbBridgeApi,
    private val onLine: (LogLine) -> Unit,
    private val onGap: (gapMs: Long) -> Unit,
) {
    companion object {
        /**
         * Threshold above which a silent period in the logcat stream is
         * treated as a "gap" worth surfacing to the detector. Chosen at 5s
         * because real Android logcat under load still emits at least one
         * system line every 2-3s; silence beyond 5s implies the stream
         * stalled (process killed, USB hiccup, device sleep).
         */
        const val GAP_THRESHOLD_MS = 5_000L
    }

    private var process: Process? = null
    private var readJob: Job? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /**
     * Start streaming. Spawns the logcat process via the [bridge] and a
     * coroutine on [scope]+[Dispatchers.IO] that reads lines until EOF or
     * cancellation.
     *
     * @return `true` if the process spawned and the reader was launched;
     *   `false` if already running or if [AdbBridgeApi.startLogcat] returned
     *   `null` (adb missing, device gone, invalid id).
     */
    fun start(deviceId: String, tagArgs: List<String>, scope: CoroutineScope): Boolean {
        if (_running.value) return false
        val proc = bridge.startLogcat(deviceId, tagArgs) ?: return false
        process = proc
        _running.value = true

        readJob = scope.launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    var lastReceiveMs = System.currentTimeMillis()
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        val now = System.currentTimeMillis()
                        val gap = now - lastReceiveMs
                        if (gap > GAP_THRESHOLD_MS) onGap(gap)
                        lastReceiveMs = now
                        val parsed = LogcatLineParser.parse(line) ?: continue
                        onLine(parsed)
                    }
                }
            } catch (_: Exception) {
                // Process killed, stream closed, or coroutine cancelled.
                // Silent shutdown — caller observes the running flow.
            } finally {
                _running.value = false
            }
        }
        return true
    }

    /**
     * Forcibly destroy the underlying process and cancel the reader. Safe
     * to call multiple times; safe to call before [start].
     */
    fun stop() {
        try { process?.destroyForcibly() } catch (_: Exception) { /* no-op */ }
        readJob?.cancel()
        process = null
        readJob = null
        _running.value = false
    }
}
