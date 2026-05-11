package com.gameperf.desktop.core.update

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * RED-first tests for [HelperLogWatcher.awaitCanary].
 *
 * Pure function with three injected dependencies for full testability:
 *   - `clock: () -> Long`        epoch-ms supplier (driven by [FakeClock] in tests)
 *   - `readTail: (Path) -> String?` log file reader (returns null when absent)
 *   - `sleep: (Long) -> Unit`    poll-interval pause (no-op in tests)
 *
 * Returns a sealed [WatchdogResult]: `CanaryFound`, `TimedOut`, `Disabled`.
 *
 * Production wrapper performs real `Files.readString` + `Thread.sleep`; this
 * test exercises only the pure inner loop.
 */
class HelperLogWatcherTest {

    private val anyPath: Path = Paths.get("dummy/last-update.log")
    private val canaryLine = HelperLogWatcher.CANARY_LINE

    /**
     * Deterministic clock that advances exactly [step] millis on every read.
     * Used to drive the `awaitCanary` polling loop without real time.
     */
    private class FakeClock(start: Long, private val step: Long) {
        private var now = start
        fun nowMs(): Long {
            val current = now
            now += step
            return current
        }
    }

    // ═══════ canary detection ═══════

    @Test
    fun `awaitCanary returns CanaryFound when canary appears on first poll`() {
        val clock = FakeClock(start = 1_000L, step = 200L)
        val tails = listOf("garbage line\n$canaryLine\nmore garbage")
        var pollCount = 0
        val readTail: (Path) -> String? = { tails[pollCount++.coerceAtMost(tails.lastIndex)] }
        var sleepCalls = 0
        val sleep: (Long) -> Unit = { sleepCalls++ }

        val result = HelperLogWatcher.awaitCanary(
            logPath = anyPath,
            timeout = 8.seconds,
            pollInterval = 200.milliseconds,
            clock = clock::nowMs,
            readTail = readTail,
            sleep = sleep,
        )

        assertEquals(WatchdogResult.CanaryFound, result)
        assertEquals(1, pollCount, "canary on first poll must short-circuit")
        assertEquals(0, sleepCalls, "no sleep needed when canary already present")
    }

    @Test
    fun `awaitCanary returns CanaryFound when canary appears at the edge of timeout`() {
        // Timeout = 800ms, poll every 200ms. Canary appears on poll 4 (elapsed = 600ms).
        val clock = FakeClock(start = 0L, step = 200L)
        val polls = listOf("nope", "nope", "nope", "yes\n$canaryLine\n")
        var pollCount = 0
        val readTail: (Path) -> String? = { polls[pollCount++] }

        val result = HelperLogWatcher.awaitCanary(
            logPath = anyPath,
            timeout = 800.milliseconds,
            pollInterval = 200.milliseconds,
            clock = clock::nowMs,
            readTail = readTail,
            sleep = {},
        )

        assertEquals(WatchdogResult.CanaryFound, result)
        assertEquals(4, pollCount, "must have polled 4 times before finding canary")
    }

    @Test
    fun `awaitCanary returns TimedOut when canary never appears within timeout`() {
        val clock = FakeClock(start = 0L, step = 200L)
        val readTail: (Path) -> String? = { "no canary here at all" }

        val result = HelperLogWatcher.awaitCanary(
            logPath = anyPath,
            timeout = 1.seconds,
            pollInterval = 200.milliseconds,
            clock = clock::nowMs,
            readTail = readTail,
            sleep = {},
        )

        assertEquals(WatchdogResult.TimedOut, result)
    }

    // ═══════ file-not-yet-created ═══════

    @Test
    fun `awaitCanary tolerates readTail returning null until the file is created`() {
        // First two polls: file missing (null). Third poll: canary present.
        val clock = FakeClock(start = 0L, step = 200L)
        val polls: List<String?> = listOf(null, null, "boot...\n$canaryLine\n")
        var pollCount = 0
        val readTail: (Path) -> String? = { polls[pollCount++] }

        val result = HelperLogWatcher.awaitCanary(
            logPath = anyPath,
            timeout = 1.seconds,
            pollInterval = 200.milliseconds,
            clock = clock::nowMs,
            readTail = readTail,
            sleep = {},
        )

        assertEquals(WatchdogResult.CanaryFound, result)
        assertEquals(3, pollCount)
    }

    // ═══════ readTail throwing ═══════

    @Test
    fun `awaitCanary swallows readTail exceptions and continues polling`() {
        val clock = FakeClock(start = 0L, step = 200L)
        var pollCount = 0
        val readTail: (Path) -> String? = {
            pollCount++
            when (pollCount) {
                1 -> throw java.nio.file.AccessDeniedException("locked")
                2 -> throw RuntimeException("transient")
                else -> "ok\n$canaryLine\n"
            }
        }

        val result = HelperLogWatcher.awaitCanary(
            logPath = anyPath,
            timeout = 2.seconds,
            pollInterval = 200.milliseconds,
            clock = clock::nowMs,
            readTail = readTail,
            sleep = {},
        )

        assertEquals(WatchdogResult.CanaryFound, result)
        assertEquals(3, pollCount, "must keep polling after thrown exceptions")
    }

    // ═══════ disabled (timeout=0) ═══════

    @Test
    fun `awaitCanary returns Disabled when timeout is zero (legacy opt-out)`() {
        var pollCount = 0
        val readTail: (Path) -> String? = { pollCount++; "irrelevant" }

        val result = HelperLogWatcher.awaitCanary(
            logPath = anyPath,
            timeout = Duration.ZERO,
            pollInterval = 200.milliseconds,
            clock = { 0L },
            readTail = readTail,
            sleep = {},
        )

        assertEquals(WatchdogResult.Disabled, result)
        assertEquals(0, pollCount, "Disabled must short-circuit before any poll")
    }

    // ═══════ poll interval respected ═══════

    @Test
    fun `awaitCanary calls sleep with pollInterval millis between polls`() {
        // Clock advances 200ms per read; timeout 1s; canary never appears.
        val clock = FakeClock(start = 0L, step = 200L)
        val sleepDurations = mutableListOf<Long>()
        val sleep: (Long) -> Unit = { sleepDurations.add(it) }

        HelperLogWatcher.awaitCanary(
            logPath = anyPath,
            timeout = 1.seconds,
            pollInterval = 250.milliseconds,
            clock = clock::nowMs,
            readTail = { "no canary" },
            sleep = sleep,
        )

        assertTrue(sleepDurations.isNotEmpty(), "must sleep between polls")
        assertTrue(
            sleepDurations.all { it == 250L },
            "every sleep call must use pollInterval millis; got $sleepDurations",
        )
    }

    // ═══════ canary line constant ═══════

    @Test
    fun `CANARY_LINE matches the line written by update-helper PowerShell script`() {
        // ADR / spec REQ 3: must equal the exact line emitted by `update-helper.ps1`.
        assertEquals("===== UAC update helper started =====", HelperLogWatcher.CANARY_LINE)
    }
}
