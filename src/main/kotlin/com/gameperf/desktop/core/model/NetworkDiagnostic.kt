package com.gameperf.desktop.core.model

import kotlinx.serialization.Serializable

/**
 * v4.6.x -- Diagnostic payload populated when [NetworkSnapshot.networkAvailable]
 * is `false`. Mirrors the v4.4.1 [ThermalDiagnostic] and v4.5.0 [GpuDiagnostic]
 * patterns: surfaced via the report HTML banner so the user can identify the
 * unsupported binder code / locked OEM / dumpsys permission denial that
 * prevented a usable reading.
 *
 * Bounded to keep failed-session export size predictable: callers MUST use the
 * [create] factory which caps [probedSources] to at most 10 entries. Direct
 * constructor invocation is permitted only by tests asserting raw shape.
 *
 *  - [probedSources] -- string identifiers of the probes the bridge attempted
 *    (capped at 10 via [create]). Examples: `"BINDER:11"`, `"BINDER:12"`,
 *    `"DUMPSYS"`. String-typed (not enum) so report HTML can surface them as-is.
 *  - [detectedMethod] -- the probe identifier that succeeded, or `null` when
 *    every probe failed. Stable string format (`"BINDER:<code>"` or
 *    `"DUMPSYS"`) so the report HTML can branch on it without enum dependency.
 *  - [failedBinderCodes] -- the subset of binder transaction codes that returned
 *    garbage / empty. Empty when no binder probes ran (e.g. UID lookup failed).
 *  - [reason] -- proximate failure cause; see [NetworkUnavailableReason].
 *
 * See `sdd/network-bandwidth-total-app/spec` NET-002.
 */
@Serializable
data class NetworkDiagnostic(
    val probedSources: List<String>,
    val detectedMethod: String? = null,
    val failedBinderCodes: List<Int> = emptyList(),
    val reason: NetworkUnavailableReason,
) {
    companion object {
        /** Spec design §2.4 — max probedSources retained in a diagnostic export. */
        const val MAX_PROBED_SOURCES: Int = 10

        /**
         * Preferred constructor: clamps [probedSources] to at most
         * [MAX_PROBED_SOURCES] entries (head-take, order preserved). All other
         * fields are passed through unchanged.
         */
        fun create(
            probedSources: List<String>,
            detectedMethod: String? = null,
            failedBinderCodes: List<Int> = emptyList(),
            reason: NetworkUnavailableReason,
        ): NetworkDiagnostic = NetworkDiagnostic(
            probedSources = probedSources.take(MAX_PROBED_SOURCES),
            detectedMethod = detectedMethod,
            failedBinderCodes = failedBinderCodes,
            reason = reason,
        )
    }
}

/**
 * v4.6.x -- Why the network bandwidth pipeline could not produce a usable
 * snapshot. Closed set of exactly 5 reasons (spec NET-002).
 *
 *  - [ALL_PROBES_FAILED] -- every binder candidate returned garbage AND the
 *    dumpsys fallback also failed to parse a usable rx/tx pair.
 *  - [DUMPSYS_PERMISSION_DENIED] -- `dumpsys netstats detail --uid` failed with
 *    a permission error (no `android.permission.DUMP` on the shell).
 *  - [BINDER_UNAVAILABLE] -- every binder transaction code in the catalog
 *    returned an empty / unparseable Parcel. Falls through to dumpsys.
 *  - [IMPLAUSIBLE_VALUE] -- a probe returned a numeric value outside the
 *    plausibility window `[0, 100 GB]` (design D4 — guards against binder
 *    code collisions producing terabyte-scale garbage).
 *  - [CAPTURE_THREW] -- try/catch fallback for adb / shell / pipe / OOM
 *    exceptions. Mirrors thermal + gpu resilience pattern (spec NET-009).
 */
@Serializable
enum class NetworkUnavailableReason {
    ALL_PROBES_FAILED,
    DUMPSYS_PERMISSION_DENIED,
    BINDER_UNAVAILABLE,
    IMPLAUSIBLE_VALUE,
    CAPTURE_THREW,
}
