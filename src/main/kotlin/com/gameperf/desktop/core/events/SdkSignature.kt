package com.gameperf.desktop.core.events

/**
 * Signature definition for one ad/billing SDK or engine.
 *
 * Used by [SdkSignatureCatalog] for pattern matching against incoming
 * logcat lines and dumpsys activity frames.
 *
 * Sprint 0 shape change (event-segmentation-coverage):
 *  - The legacy `type: EventType` was renamed to [defaultType] and is now
 *    only the fallback for activity-level matches (which carry no pattern
 *    context).
 *  - [openPatterns] is now `List<Pair<Regex, EventType>>` instead of
 *    `List<Regex>` so a single signature row can host patterns that
 *    classify into different [EventType] values (e.g. Sprint 2b will use
 *    this to split "Loaded ad" vs. "Showing rewarded video" within one
 *    AdMob entry; Sprint 1 uses it so a future Unity Engine entry can
 *    emit both `LOADING` and `APP_STARTUP` from the same logcat tag).
 *  - Existing v4.4.0/v4.4.1 entries are homogeneous: every open-pattern
 *    pair carries the same type, equal to [defaultType]. The new shape is
 *    BYTE-EQUIVALENT in behavior for those entries.
 *
 * @property sdk Human-readable SDK name (e.g., "AdMob", "Unity Ads").
 * @property defaultType Event type produced for activity-level matches
 *           (where no pattern carries a per-line discriminator) and as the
 *           fallback for homogeneous open-pattern rows.
 * @property activityClasses Fully-qualified Android activity class names that
 *           appear in `dumpsys activity activities` when this SDK shows.
 * @property logcatTags Logcat tags to filter (passed to `adb logcat <tag>:D *:S`).
 * @property openPatterns Pairs of `(regex, type)`. The regex matches log
 *           MESSAGES that indicate the ad/IAP/loading window starts; the
 *           type is the [EventType] emitted when that specific regex wins.
 *           Order matters — first match wins.
 * @property closePatterns Regex patterns matching log MESSAGES that indicate
 *           the event ends. Used to compute endMs.
 * @property dedupWindowMs Optional same-SDK open-event dedup window in
 *           milliseconds. When non-null, [EventDetectorImpl.tryOpen] MUST
 *           suppress a new open for this signature if another event with the
 *           same `sdkSource` is already open and started within this window.
 *           Default `null` = no dedup (legacy 18-entry behavior preserved).
 *           Sprint 4 (vr-event-detection) introduced this for the
 *           multi-runtime VR signature (VrApi + OpenXR fire on the same
 *           Quest headset session within ~1s) — see design D1.
 *
 * @since v4.4.0 (Sprint 0 of event-segmentation-coverage rewrites the
 *        `type` and `openPatterns` shape; closePatterns and activityClasses
 *        are unchanged). [dedupWindowMs] added in Sprint 4
 *        (vr-event-detection) — additive, default null, zero behavior
 *        change for the existing 18 entries.
 */
internal data class SdkSignature(
    val sdk: String,
    val defaultType: EventType,
    val activityClasses: List<String>,
    val logcatTags: List<String>,
    val openPatterns: List<Pair<Regex, EventType>>,
    val closePatterns: List<Regex>,
    val dedupWindowMs: Long? = null,
)
