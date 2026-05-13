package com.gameperf.desktop.core.model

import kotlinx.serialization.Serializable

/**
 * v4.6.0 — Platform-agnostic wake-locks snapshot for the
 * sdd/vitals-rate-and-wakelocks change.
 *
 * Represents a single capture of partial wake locks accumulated by a game's
 * package, as parsed from `adb shell dumpsys batterystats --charged <pkg>`
 * by [com.gameperf.desktop.core.WakeLocksParser]. v1 surfaces an absolute
 * single-session total as a proxy for Google Play Vitals's
 * "excessive partial wake locks" gate (>2h in a 24h screen-off window —
 * engram #424).
 *
 * Sentinel semantics (mirrors v4.5.0 [GpuSnapshot] / v4.6.x [NetworkSnapshot]):
 *  - [totalScreenOffMs] / [totalScreenOnMs] are non-negative ms when
 *    [wakeLocksAvailable] is `true`; `-1L` otherwise.
 *  - [partialLockCount] is the number of wake-lock entries the parser
 *    successfully attributed to the game's package — informational only,
 *    never feeds scoring. `0` when no data is available.
 *  - [wakeLocksAvailable] defaults to `false` (mirrors GPU / network — pre-v4.6.0
 *    sessions never captured wake locks, so deserialising a pre-v4.6.0
 *    `.gameperf` row must report "no data").
 *  - [diagnostic] is `null` on the happy path; populated by the parser /
 *    bridge when the probe pipeline fails so the report HTML can surface a
 *    castellano tuteo-formal banner instead of a misleading "0h".
 *
 * iOS sessions always persist [wakeLocksAvailable] = `false` (out of scope
 * for v1 — there is no direct equivalent to `dumpsys batterystats`).
 *
 * See `sdd/vitals-rate-and-wakelocks/spec` and `design` §3.
 */
@Serializable
data class WakeLocksSnapshot(
    val totalScreenOffMs: Long = -1L,
    val totalScreenOnMs: Long = -1L,
    val partialLockCount: Int = 0,
    val wakeLocksAvailable: Boolean = false,
    val diagnostic: WakeLocksDiagnostic? = null,
)

/**
 * v4.6.0 — Diagnostic payload populated when
 * [WakeLocksSnapshot.wakeLocksAvailable] is `false`. Mirrors v4.5.0
 * [GpuDiagnostic] / v4.6.x [NetworkDiagnostic]: surfaced via the report HTML
 * banner so the user can identify the missing package / permission denial /
 * implausible value that prevented a usable reading.
 *
 *  - [probedCommand] — the exact shell command the bridge issued (e.g.
 *    `"dumpsys batterystats --charged com.example.game"`). String-typed so
 *    the report HTML can surface it as-is for diagnostic purposes.
 *  - [reason] — proximate failure cause; see [WakeLocksUnavailableReason].
 */
@Serializable
data class WakeLocksDiagnostic(
    val probedCommand: String,
    val reason: WakeLocksUnavailableReason,
)

/**
 * v4.6.0 — Why the wake-locks pipeline could not produce a usable snapshot.
 * Closed set of exactly 4 reasons (spec design §3).
 *
 *  - [PKG_NOT_FOUND] — the package the user asked about did not appear in
 *    `dumpsys batterystats` output. Typical cause: the user didn't open the
 *    game between `adb shell am force-stop` and the capture.
 *  - [PARSE_FAILED] — the dumpsys output was malformed, truncated, or denied
 *    by permissions (no `All partial wake locks:` section header).
 *  - [OUT_OF_RANGE_VALUE] — the parser extracted a duration below 0 or above
 *    24h. Guards against malformed `XXh YYm ZZs` strings producing
 *    implausibly large totals (mirrors v4.6.x network plausibility window).
 *  - [CAPTURE_THREW] — try/catch fallback for adb / shell / pipe / OOM
 *    exceptions. Mirrors thermal + gpu + network resilience pattern.
 */
@Serializable
enum class WakeLocksUnavailableReason {
    PKG_NOT_FOUND,
    PARSE_FAILED,
    OUT_OF_RANGE_VALUE,
    CAPTURE_THREW,
}
