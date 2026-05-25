package com.gameperf.desktop.core.events

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure regex-based parser for `adb logcat -v threadtime` lines.
 *
 * The threadtime format is:
 * ```
 *   MM-DD HH:MM:SS.mmm  PID  TID L TAG: MSG
 * ```
 *
 * Example line:
 * ```
 *   01-15 14:32:18.456  1234  5678 I AdActivity: Showing ad
 * ```
 *
 * Year inference: logcat threadtime omits the year; this parser assumes the
 * line was emitted in the current year of the desktop clock at parse time.
 * For sessions that cross a year boundary this is wrong by one year.
 *
 * **Reception-time semantics (v4.9.0, engram #503)**: the parsed
 * [LogLine.tsMs] field is the DEVICE clock value embedded by Android in the
 * threadtime header. The detector ([com.gameperf.desktop.core.events.EventDetectorImpl])
 * does NOT use it for event correlation — it uses `timeProvider()` (desktop
 * `System.currentTimeMillis()` by default) at the moment of observation so
 * `event.startMs` aligns coherently with `AppViewModel.captureStartTime` and
 * with the recorded video timeline. The `LogLine.tsMs` field is preserved
 * for forensic use (a future logcat viewer can show the original device
 * timestamp alongside the desktop reception timestamp).
 *
 * Pre-v4.9.0 the detector consumed `line.tsMs` directly, so when device and
 * desktop clocks drifted (NTP gaps, USB-debugging clock skew) events
 * appeared with a constant N-second offset relative to the video. v4.9.0
 * fixes this by switching to reception-time at every event-creation site.
 * See [com.gameperf.desktop.core.events.LogLine].
 *
 * The parser is lenient: any input that does not match [THREADTIME_REGEX]
 * returns `null`. Callers should treat null as "skip this line, continue
 * reading". UTF-8 decoding is the caller's responsibility (the [String] passed
 * in must already be decoded).
 *
 * The regex is compiled once as a top-level `private val` per the CLAUDE.md
 * "regex hot-path" rule (never compile inline in a hot loop).
 *
 * @since v4.4.0
 */
object LogcatLineParser {

    /**
     * Parse one logcat threadtime line.
     *
     * @param rawLine A single, already-UTF-8-decoded log line, without the
     *   trailing newline.
     * @return Parsed [LogLine] on success, `null` if the line does not match
     *   the threadtime format.
     */
    @Suppress("ReturnCount", "DestructuringDeclarationWithTooManyEntries")
    fun parse(rawLine: String): LogLine? {
        if (rawLine.isEmpty()) return null
        return try {
            val match = THREADTIME_REGEX.matchEntire(rawLine) ?: return null
            val groups = match.groupValues
            val tsMs = parseDeviceTimestamp(groups[1]) ?: return null
            val pid = groups[2].toIntOrNull() ?: return null
            val tid = groups[3].toIntOrNull() ?: return null
            val level = groups[4].firstOrNull() ?: return null
            val tag = groups[5].trim()
            if (tag.isEmpty()) return null
            LogLine(tsMs = tsMs, pid = pid, tid = tid, level = level, tag = tag, msg = groups[6])
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") e: RuntimeException) {
            // Defensive: any malformed regex group / parse failure is a "skip"
            // signaled by null. Logging would be noisy and the caller already
            // treats null as "ignore this line" per parse() KDoc.
            null
        }
    }

    private fun parseDeviceTimestamp(tsRaw: String): Long? {
        return try {
            val year = LocalDateTime.now().year
            val withYear = "$year-$tsRaw"
            val ldt = LocalDateTime.parse(withYear, DEVICE_TS_FORMATTER)
            ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") e: RuntimeException) {
            // Same rationale as parse() — null signals "skip this line" to the caller.
            null
        }
    }
}

/**
 * Compiled once at class init — never inline this in a hot path
 * (CLAUDE.md regex rule).
 *
 * Capture groups:
 *  1. timestamp (`MM-DD HH:MM:SS.mmm`)
 *  2. pid
 *  3. tid
 *  4. level (single char)
 *  5. tag (anything but `:`, may have trailing whitespace, trimmed by caller)
 *  6. message (rest of line, may contain `:` characters)
 */
private val THREADTIME_REGEX: Regex = Regex(
    """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+([^:]+):\s?(.*)$""",
)

private val DEVICE_TS_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
