package com.gameperf.desktop.core.events

/**
 * A single parsed logcat line in `threadtime` format.
 *
 * Fields populated from the canonical adb logcat threadtime output:
 *   `MM-DD HH:MM:SS.mmm  PID  TID L TAG: MSG`
 *
 * Timestamp resolution: millisecond. Year is inferred from the desktop clock
 * at parse time (logcat threadtime omits the year). The timestamp is converted
 * to absolute epoch-millis by [LogcatLineParser] using a "current year" assumption.
 *
 * @property tsMs Absolute epoch-millis derived from `MM-DD HH:MM:SS.mmm`
 *   plus the current desktop year. **DEVICE clock value**: this is what the
 *   Android device's logcat header emitted; it is NOT used by
 *   [com.gameperf.desktop.core.events.EventDetectorImpl] for event timestamps
 *   (v4.9.0, engram #503 — detector uses reception-time via `timeProvider()`).
 *   This field is preserved for forensic use (planned logcat viewer) so the
 *   original device-time is available alongside the desktop reception-time
 *   stamped on each [com.gameperf.desktop.core.events.DetectedEvent].
 * @property pid Process ID emitting the log line.
 * @property tid Thread ID emitting the log line.
 * @property level Log level character: `V`, `D`, `I`, `W`, `E`, `F`, or `A`.
 * @property tag Log tag (the field before the first `:`).
 * @property msg The remaining message body (may itself contain `:` characters).
 *
 * @since v4.4.0
 */
data class LogLine(
    val tsMs: Long,
    val pid: Int,
    val tid: Int,
    val level: Char,
    val tag: String,
    val msg: String,
)
