package com.gameperf.desktop.core.update

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for the v4.7.2 hotfix: [HelperLogWatcher.awaitCanary]
 * must only consider canary lines written AFTER the current update attempt
 * began. Markers from previous successful updates that still live in the
 * append-only `last-update.log` must NOT trigger a false `CanaryFound`.
 *
 * Root cause analysis: engram observation #487.
 * Design: engram observation #490.
 */
class HelperLogWatcherStaleCanaryTest {

    private val anyPath: Path = Paths.get("dummy/last-update.log")
    private val canary = HelperLogWatcher.CANARY_LINE

    private class FakeClock(start: Long, private val step: Long) {
        private var now = start
        fun nowMs(): Long {
            val current = now
            now += step
            return current
        }
    }

    // ═══════ scenario 1: stale-only canary pre-baseline → TimedOut ═══════

    @Test
    fun `awaitCanary ignores stale canary written before baseline and times out`() {
        // Log already contains a canary from a PREVIOUS successful update.
        // The current attempt's helper never writes a new canary (e.g. UAC denied).
        // Watcher must return TimedOut — the bug scenario from engram #487.
        val staleLog = buildString {
            append("[2026-05-19] previous update OK\n")
            append("$canary\n")
            append("[2026-05-19] JAR replaced successfully.\n")
            append("[2026-05-19] ===== UAC update helper finished OK =====\n")
        }
        val baseline = staleLog.length.toLong()
        val clock = FakeClock(start = 0L, step = 200L)

        val result = HelperLogWatcher.awaitCanary(
            logPath = anyPath,
            timeout = 600.milliseconds,
            pollInterval = 200.milliseconds,
            baselineLength = baseline,
            clock = clock::nowMs,
            // log does NOT grow across polls — current helper never started.
            readTail = { staleLog },
            sleep = {},
        )

        assertEquals(WatchdogResult.TimedOut, result)
    }

    // ═══════ scenario 2: fresh canary post-baseline → CanaryFound ═══════

    @Test
    fun `awaitCanary detects canary written after baseline`() {
        // Log was empty before this attempt. Helper writes the canary on poll 2.
        val baseline = 0L
        val polls = listOf(
            "", // first poll: nothing yet
            "[now] $canary\n[now] copying jar...\n",
        )
        var pollCount = 0
        val clock = FakeClock(start = 0L, step = 200L)

        val result = HelperLogWatcher.awaitCanary(
            logPath = anyPath,
            timeout = 1.seconds,
            pollInterval = 200.milliseconds,
            baselineLength = baseline,
            clock = clock::nowMs,
            readTail = { polls[pollCount++.coerceAtMost(polls.lastIndex)] },
            sleep = {},
        )

        assertEquals(WatchdogResult.CanaryFound, result)
    }

    // ═══════ scenario 3: mixed stale + fresh → CanaryFound ═══════

    @Test
    fun `awaitCanary detects fresh canary even when a stale canary lives pre-baseline`() {
        // Log contains a canary from a previous run (pre-baseline) AND the
        // current helper successfully appends a new canary post-baseline.
        // Watcher must NOT short-circuit on the stale one — and must still
        // find the fresh one in the slice after baseline.
        val staleSection = "[2026-05-19] $canary\n[2026-05-19] finished OK\n"
        val baseline = staleSection.length.toLong()
        val combined = staleSection + "[2026-05-21] $canary\n[2026-05-21] copying...\n"
        val clock = FakeClock(start = 0L, step = 200L)

        val result = HelperLogWatcher.awaitCanary(
            logPath = anyPath,
            timeout = 1.seconds,
            pollInterval = 200.milliseconds,
            baselineLength = baseline,
            clock = clock::nowMs,
            readTail = { combined },
            sleep = {},
        )

        assertEquals(WatchdogResult.CanaryFound, result)
    }

    // ═══════ scenario 4: baselineLength > content.length → clamp, no crash, no false positive ═══════

    @Test
    fun `awaitCanary clamps oversize baseline to content length without crashing`() {
        // Pathological case: log was truncated/rotated between baseline capture
        // and current poll. Baseline now exceeds available content. Watcher
        // must NOT throw, must NOT report CanaryFound (slice is empty), and
        // must eventually TimedOut.
        val tinyContent = "x"
        val baseline = 1_000_000L
        val clock = FakeClock(start = 0L, step = 200L)

        val result = HelperLogWatcher.awaitCanary(
            logPath = anyPath,
            timeout = 600.milliseconds,
            pollInterval = 200.milliseconds,
            baselineLength = baseline,
            clock = clock::nowMs,
            readTail = { tinyContent },
            sleep = {},
        )

        assertEquals(WatchdogResult.TimedOut, result)
    }
}
