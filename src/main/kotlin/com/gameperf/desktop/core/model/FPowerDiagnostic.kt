package com.gameperf.desktop.core.model

import kotlinx.serialization.Serializable

/**
 * v4.5.0 -- Diagnostic payload populated when [FPowerSnapshot.fpowerAvailable]
 * is `false`. Surfaced to the user via the report HTML banner so they (or
 * the dev team) can file an issue listing the actual sysfs paths the bridge
 * tried, enabling vendor-catalog growth.
 *
 * The payload is intentionally bounded to keep failed-session export size
 * predictable: callers SHOULD cap [rawPathsTried] / [lastReadout] at a few
 * entries per probe attempt.
 *
 *  - [rawPathsTried] -- the literal `/sys/class/power_supply/.../{current,voltage}_now`
 *    strings the bridge tried before giving up. Lets users / devs identify
 *    the missing vendor tuple for [com.gameperf.desktop.core.FPowerVendorCatalog].
 *  - [lastReadout] -- last raw payload per probed path. Helps distinguish
 *    "file missing" (empty / error) from "implausible value" (numeric out
 *    of range) when triaging a failure.
 *  - [reason] -- the proximate failure cause; see [FPowerUnavailableReason].
 *
 * Mirrors `core/model/ThermalDiagnostic.kt` per design ADR-1.
 *
 * See `sdd/fpower-metric/design` §4 + spec FPW-005.
 */
@Serializable
data class FPowerDiagnostic(
    val rawPathsTried: List<String>,
    val lastReadout: Map<String, String>,
    val reason: FPowerUnavailableReason,
)

/**
 * v4.5.0 -- Why the FPower pipeline could not produce a usable snapshot.
 *
 *  - [BATTERY_PATH_MISSING] -- every tuple in
 *    [com.gameperf.desktop.core.FPowerVendorCatalog.ORDERED_PATHS] returned
 *    empty / unparseable content. Typical of non-rooted devices on locked-
 *    down Android 13+ vendors, or iOS (which always returns this with
 *    `rawPathsTried = emptyList()`).
 *  - [FPS_ZERO] -- battery sysfs read succeeded but the per-tick FPS sample
 *    was <=0, so the mW-per-frame divisor is undefined. The path tuple is
 *    cached because the read worked; the tick is just dropped.
 *  - [IMPLAUSIBLE_VALUE] -- numeric values out of the plausibility window
 *    (`0 < powerW < 30 W`, `0 < fpowerMwPerFrame < 500`). See ADR-4.
 *  - [OEM_LOCKED] -- adb shell returned a permission-denied-equivalent
 *    response specific to OEM hardening (e.g. Huawei knox).
 *  - [PERMISSION_DENIED] -- adb shell explicitly refused (e.g. SELinux).
 *    Same user-facing effect as [BATTERY_PATH_MISSING] but distinct so the
 *    diagnostic banner can suggest enabling adb root.
 *  - [UNKNOWN] -- fallback when the call site cannot disambiguate. iOS
 *    bridge uses this per design §7.
 */
@Serializable
enum class FPowerUnavailableReason {
    BATTERY_PATH_MISSING,
    FPS_ZERO,
    IMPLAUSIBLE_VALUE,
    OEM_LOCKED,
    PERMISSION_DENIED,
    UNKNOWN,
}
