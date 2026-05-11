package com.gameperf.desktop.core.model

import kotlinx.serialization.Serializable

/**
 * v4.4.1 -- Diagnostic payload populated when [ThermalSnapshot.thermalAvailable]
 * is `false`. Surfaced to the user via the report HTML banner so they (or the
 * dev team) can file a bug report listing the actual vendor zone names that
 * the classifier could not bucket.
 *
 * The payload is intentionally bounded to keep failed-session export size
 * predictable: callers SHOULD truncate [rawZoneNames] to ~10 entries.
 *
 *  - [rawZoneNames] -- the literal `thermal_zoneN/type` strings the parser saw
 *    (sample, up to ~10). Lets users / devs identify the unsupported vendor.
 *  - [classificationCounts] -- bucket counts (e.g. `"DieCpu" -> 0`, `"null" -> 5`).
 *    Helps distinguish "all classified to null" from "all temps out of range".
 *  - [reason] -- the proximate failure cause; see [ThermalUnavailableReason].
 *
 * See `sdd/temperature-not-shown/design` ADR-5 (separate optional class kept
 * out of the happy path).
 */
@Serializable
data class ThermalDiagnostic(
    val rawZoneNames: List<String>,
    val classificationCounts: Map<String, Int>,
    val reason: ThermalUnavailableReason,
)

/**
 * v4.4.1 -- Why the thermal pipeline could not produce a usable snapshot.
 *
 *  - [NO_ZONES_DETECTED] -- `adb` returned an empty body (sysfs + dumpsys both
 *    silent). Typical of permission-denied scenarios on Android 11+ non-root.
 *  - [ALL_ZONES_UNCLASSIFIED] -- zones detected, but none matched the
 *    [com.gameperf.desktop.core.ThermalZoneClassifier] strict allow-list nor
 *    the stage-2 keyword catch-all. Vendor is unsupported.
 *  - [ALL_TEMPS_INVALID] -- zones classified, but every temperature fell
 *    outside the parser plausibility window (see `withinPlausibilityWindow`).
 *  - [PERMISSION_DENIED] -- adb shell explicitly refused (e.g. SELinux). Same
 *    user-facing effect as [NO_ZONES_DETECTED] but distinct so we can suggest
 *    enabling adb root in the diagnostic banner.
 *  - [UNKNOWN] -- fallback when the call site cannot disambiguate.
 */
@Serializable
enum class ThermalUnavailableReason {
    NO_ZONES_DETECTED,
    ALL_ZONES_UNCLASSIFIED,
    ALL_TEMPS_INVALID,
    PERMISSION_DENIED,
    UNKNOWN,
}
