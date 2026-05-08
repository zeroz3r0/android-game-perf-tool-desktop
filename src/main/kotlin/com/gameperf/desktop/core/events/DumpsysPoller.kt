package com.gameperf.desktop.core.events

import com.gameperf.desktop.core.AdbBridgeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Polls `dumpsys activity activities` at 1 Hz to track the top of the
 * activity stack. Runs as a complementary signal alongside [LogcatCapture]:
 * logcat catches SDK code-level signals (which can be ProGuard-stripped in
 * release builds), while dumpsys catches the launch at the OS level and
 * therefore survives ProGuard.
 *
 * ### Self-disabling
 *
 * After [MAX_CONSECUTIVE_FAILURES] back-to-back failures (timeout, empty
 * output, exception), the poll loop exits silently. This avoids spamming
 * a broken adb connection (e.g. device unplugged mid-session) at 1 Hz for
 * the rest of the capture.
 *
 * ### Parsing
 *
 * Output is matched line-by-line against [CMP_REGEX]. Only the first
 * [TOP_OF_STACK_LIMIT] matches are emitted — `dumpsys activity activities`
 * lists every task on the device, but only the topmost handful are useful
 * for ad/IAP detection (the rest are background tasks).
 *
 * @property bridge ADB bridge used to invoke `adb shell dumpsys`.
 * @property onActivityStack Invoked once per successful poll with the
 *   parsed top-of-stack frames (oldest-first as they appear in dumpsys).
 *
 * @since v4.4.0
 */
internal class DumpsysPoller(
    private val bridge: AdbBridgeApi,
    private val onActivityStack: (frames: List<ActivityFrame>) -> Unit,
) {
    companion object {
        /** 1 Hz cadence. */
        const val POLL_INTERVAL_MS = 1_000L

        /** Hard ceiling on a single poll — reached devices have been observed
         *  taking > 1s for `dumpsys activity activities` under load. We
         *  cap at 250 ms so a stuck call doesn't starve the loop. */
        const val POLL_TIMEOUT_MS = 250L

        /** After this many consecutive failures, the poller self-disables. */
        const val MAX_CONSECUTIVE_FAILURES = 5

        /** Top-of-stack cap. Beyond this, frames are dropped — they're
         *  background tasks not relevant to the foreground app being measured. */
        const val TOP_OF_STACK_LIMIT = 5
    }

    private var pollJob: Job? = null

    /**
     * Start the poll loop. Coroutine runs on [Dispatchers.IO] until the
     * scope is cancelled, [stop] is called, or [MAX_CONSECUTIVE_FAILURES]
     * is reached.
     */
    fun start(deviceId: String, scope: CoroutineScope) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            while (isActive && consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
                val output = try {
                    withTimeoutOrNull(POLL_TIMEOUT_MS) {
                        bridge.shell(deviceId, "dumpsys activity activities", POLL_TIMEOUT_MS)
                    }
                } catch (_: Exception) {
                    null
                }

                if (output.isNullOrBlank()) {
                    consecutiveFailures++
                } else {
                    consecutiveFailures = 0
                    val frames = parseFrames(output)
                    onActivityStack(frames)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Cancel the poll loop. Safe to call multiple times. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Extract activity frames from a raw `dumpsys activity activities`
     * dump. Visible internally so the unit test can hit this directly
     * without scheduling a real coroutine.
     *
     * Captures up to [TOP_OF_STACK_LIMIT] `cmp=PACKAGE/ACTIVITY` matches.
     * Garbage / empty input returns an empty list (never throws).
     */
    internal fun parseFrames(output: String): List<ActivityFrame> {
        if (output.isBlank()) return emptyList()
        return CMP_REGEX.findAll(output)
            .take(TOP_OF_STACK_LIMIT)
            .map { match -> ActivityFrame(cmp = match.groupValues[1]) }
            .toList()
    }
}

/**
 * Compiled once at file load — never inline this in a hot path
 * (CLAUDE.md regex rule).
 *
 * Captures the full `package/activity` component string (the form
 * Android's dumpsys uses). Examples that match:
 *
 *  - `cmp=com.example/com.google.android.gms.ads.AdActivity`
 *  - `cmp=com.example.app/.MainActivity`
 *
 * The capture group stops at whitespace, `}`, or end-of-line — these
 * delimit the `cmp=` token in real dumpsys output.
 */
private val CMP_REGEX: Regex = Regex("""cmp=([^\s}]+/[^\s}]+)""")
