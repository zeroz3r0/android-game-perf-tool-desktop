package com.gameperf.desktop.core.events

/**
 * One frame of `dumpsys activity activities` output: a single entry from
 * the activity stack as reported by Android's ActivityTaskManagerService.
 *
 * Used by [DumpsysPoller] to track the top-of-stack activity. While
 * logcat catches SDK behavior via log statements (which can be ProGuard-
 * stripped in release builds), dumpsys catches the activity launch at the
 * system level and survives ProGuard.
 *
 * @property cmp Component string in the form `package/activity`
 *   (e.g. `com.example/com.google.android.gms.ads.AdActivity`).
 * @property pid Process ID of the activity, or `-1` if unknown.
 * @property taskId Task stack ID, or `-1` if unknown.
 *
 * @since v4.4.0
 */
internal data class ActivityFrame(
    val cmp: String,
    val pid: Int = -1,
    val taskId: Int = -1,
)
