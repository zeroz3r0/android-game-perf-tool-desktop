package com.gameperf.desktop.core.events

/**
 * Signature definition for one ad/billing SDK.
 *
 * Used by [SdkSignatureCatalog] for pattern matching against incoming
 * logcat lines and dumpsys activity frames.
 *
 * @property sdk Human-readable SDK name (e.g., "AdMob", "Unity Ads").
 * @property type Event type produced when this signature matches.
 * @property activityClasses Fully-qualified Android activity class names that
 *           appear in `dumpsys activity activities` when this SDK shows.
 * @property logcatTags Logcat tags to filter (passed to `adb logcat <tag>:D *:S`).
 * @property openPatterns Regex patterns matching log MESSAGES that indicate the
 *           ad/IAP starts. Order matters — first match wins.
 * @property closePatterns Regex patterns matching log MESSAGES that indicate
 *           the ad/IAP ends. Used to compute endMs.
 *
 * @since v4.4.0
 */
internal data class SdkSignature(
    val sdk: String,
    val type: EventType,
    val activityClasses: List<String>,
    val logcatTags: List<String>,
    val openPatterns: List<Regex>,
    val closePatterns: List<Regex>,
)
