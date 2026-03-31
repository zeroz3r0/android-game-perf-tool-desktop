package com.gameperf.desktop.ui.util

import java.util.Locale

/** Locale-safe US format wrapper — avoids decimal comma on European systems. */
fun fmtUS(pattern: String, vararg args: Any?): String =
    String.format(Locale.US, pattern, *args)

/** Format milliseconds to MM:SS display string. */
fun formatTimeMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

/** Format a duration in seconds to "M:SS" clock-style string. */
fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}

/** Format a duration in seconds to "Xm Ys" human-readable string. */
fun formatDurationHuman(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m}m ${s}s"
}
