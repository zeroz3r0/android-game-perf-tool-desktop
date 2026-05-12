package com.gameperf.desktop.core.events

/**
 * Outcome of [SdkSignatureCatalog.matchOpen] when an incoming logcat line
 * is claimed by a catalogued SDK signature.
 *
 * Replaces the prior `Pair<SdkSignature, Regex>` tuple. The named fields:
 *
 *  - decouple production code from positional `.first` / `.second` access,
 *    which got brittle once we needed a THIRD piece of information
 *    (the resolved event type — see [resolvedType]);
 *  - make the per-pattern type discriminator first-class, so a single
 *    [SdkSignature] entry can host patterns that map to different
 *    [EventType] values (e.g. a future Unity Engine entry can emit
 *    `LOADING` for "Loading scene" and `APP_STARTUP` for "Application
 *    started" without splitting into two catalog rows).
 *
 * @property sig The signature whose open pattern matched.
 * @property pattern The specific open pattern (within [SdkSignature.openPatterns])
 *           that matched the line. Used for the per-event signature
 *           tracking key and for debug-logging false positives.
 * @property resolvedType The [EventType] that the matched pattern resolves to.
 *           For homogeneous signatures (all patterns of the same type) this
 *           equals [SdkSignature.defaultType]; for heterogeneous signatures
 *           it is the type tagged to the matched pattern entry.
 *
 * @since Sprint 0 of event-segmentation-coverage (replaces the
 *        `Pair<SdkSignature, Regex>` return shape of [SdkSignatureCatalog.matchOpen]).
 */
internal data class MatchResult(
    val sig: SdkSignature,
    val pattern: Regex,
    val resolvedType: EventType,
)
