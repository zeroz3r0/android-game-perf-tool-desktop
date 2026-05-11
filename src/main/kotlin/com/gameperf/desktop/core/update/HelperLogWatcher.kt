package com.gameperf.desktop.core.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Outcome of a single [HelperLogWatcher.awaitCanary] invocation.
 *
 * Sealed so callers can exhaustively `when` over the three terminal states:
 *   - [CanaryFound] — the helper canary line appeared within [timeout]
 *   - [TimedOut]    — timeout elapsed without canary observation
 *   - [Disabled]    — caller passed `timeout = Duration.ZERO` (legacy opt-out, spec W4)
 */
sealed class WatchdogResult {

    /** Canary line observed in the polled log file. */
    data object CanaryFound : WatchdogResult()

    /** Timeout elapsed without observing the canary. */
    data object TimedOut : WatchdogResult()

    /**
     * Watchdog disabled (timeout was `Duration.ZERO`).
     *
     * Per spec REQ 3 / scenario W4 this preserves the legacy AutoUpdater
     * behavior where the JVM exits 1500 ms after spawning the helper without
     * checking for the canary at all.
     */
    data object Disabled : WatchdogResult()
}

/**
 * Polling watchdog that detects the elevated-helper canary line in
 * `last-update.log`.
 *
 * The pure inner loop [awaitCanary] takes injected `clock`, `readTail`,
 * and `sleep` closures so tests can drive deterministic time advancement
 * and predetermined log-content sequences without touching the filesystem.
 *
 * Production callers use the default arguments: `clock = System::currentTimeMillis`,
 * `readTail = ::defaultReadTail` (real `Files.readString`), `sleep = Thread::sleep`.
 *
 * Per design ADR-2 (timeout = 8 s) + ADR-3 (200 ms polling).
 */
object HelperLogWatcher {

    /**
     * Canary line emitted by `update-helper.ps1` immediately after the
     * elevated PowerShell helper starts. MUST match the script verbatim
     * (any drift breaks watchdog detection silently).
     */
    const val CANARY_LINE: String = "===== UAC update helper started ====="

    /** Default poll interval per design ADR-3. */
    val DEFAULT_POLL_INTERVAL: Duration = 200.milliseconds

    /**
     * Production wrapper that reads [path] as a UTF-8 string when it
     * exists, returning `null` otherwise. Swallows transient I/O errors
     * by returning `null` so the caller's polling loop can continue.
     */
    fun defaultReadTail(path: Path): String? {
        if (!Files.exists(path)) return null
        return runCatching { Files.readString(path) }.getOrNull()
    }

    /**
     * Poll [logPath] every [pollInterval] until the canary line appears
     * or [timeout] elapses.
     *
     * @param logPath      path to the helper log (e.g. `~/GamePerf Reports/updates/last-update.log`)
     * @param timeout      maximum wall-clock duration to wait. `Duration.ZERO` returns [WatchdogResult.Disabled].
     * @param pollInterval pause between polls; defaults to [DEFAULT_POLL_INTERVAL].
     * @param clock        epoch-millis supplier; injected so tests can advance deterministically.
     * @param readTail     log reader; returns the file content or `null` if absent / unreadable.
     *                     Exceptions thrown by [readTail] are swallowed and polling continues.
     * @param sleep        pause function called with `pollInterval` millis between polls.
     */
    @Suppress("LongParameterList")
    fun awaitCanary(
        logPath: Path,
        timeout: Duration,
        pollInterval: Duration = DEFAULT_POLL_INTERVAL,
        clock: () -> Long = System::currentTimeMillis,
        readTail: (Path) -> String? = ::defaultReadTail,
        sleep: (Long) -> Unit = Thread::sleep,
    ): WatchdogResult {
        if (timeout == Duration.ZERO) return WatchdogResult.Disabled

        val timeoutMs: Long = timeout.inWholeMilliseconds
        val pollMs: Long = pollInterval.inWholeMilliseconds
        val start: Long = clock()

        while (true) {
            val content: String? = try {
                readTail(logPath)
            } catch (_: Throwable) {
                null
            }
            if (content != null && content.contains(CANARY_LINE)) {
                return WatchdogResult.CanaryFound
            }
            val elapsed: Long = clock() - start
            if (elapsed >= timeoutMs) return WatchdogResult.TimedOut
            sleep(pollMs)
        }
    }
}
