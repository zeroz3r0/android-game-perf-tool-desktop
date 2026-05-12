package com.gameperf.desktop.core

/**
 * Single source of truth for the battery-sysfs path alternates the desktop
 * tool probes when computing the v4.5.0 FPower metric (mW per frame).
 *
 * Strict ordered list — AOSP-canonical first, OEM alternates after. The
 * [com.gameperf.desktop.core.AdbBridge] walks [ORDERED_PATHS] top-down and
 * caches the first tuple that yields a parseable
 * [com.gameperf.desktop.core.model.FPowerSnapshot]. The cache is cleared on
 * `resetSessionState()` so device swaps within a session are honoured.
 *
 * Mirrors the architecture of [ThermalZoneClassifier]: strict-list, NO fuzzy
 * matching, NO substring guessing. Adding a vendor means appending one
 * [PathTuple] and bumping the test count.
 *
 * See `sdd/fpower-metric/design` §2 + spec FPW-010 for the source-of-truth.
 *
 * Tuple breakdown:
 *  - index 0: AOSP-canonical `power_supply/battery/{current_now,voltage_now}`.
 *  - index 1: Samsung One UI (`batt_current_ua_now` -- always positive, we
 *    still `abs()` defensively).
 *  - index 2: Huawei pre-HarmonyOS NEXT capital-B `Battery/`.
 *  - index 3: Xiaomi / Qualcomm BMS subsystem (`power_supply/bms/`).
 *  - index 4: OnePlus / Realme `bq2589x_charger` IC fallback. Reads charger-
 *    IC current, not battery-IC; values can differ by 5-10% from BMS
 *    readings. Acceptable as last-resort per design ADR-1 risk list.
 */
object FPowerVendorCatalog {

    /** Battery sysfs path tuple: (currentPath, voltagePath). */
    data class PathTuple(val currentPath: String, val voltagePath: String)

    /** Ordered probe list. AOSP-canonical first; OEM alternates after. */
    val ORDERED_PATHS: List<PathTuple> = listOf(
        PathTuple(
            currentPath = "/sys/class/power_supply/battery/current_now",
            voltagePath = "/sys/class/power_supply/battery/voltage_now",
        ),
        PathTuple(
            currentPath = "/sys/class/power_supply/battery/batt_current_ua_now",
            voltagePath = "/sys/class/power_supply/battery/voltage_now",
        ),
        PathTuple(
            currentPath = "/sys/class/power_supply/Battery/current_now",
            voltagePath = "/sys/class/power_supply/Battery/voltage_now",
        ),
        PathTuple(
            currentPath = "/sys/class/power_supply/bms/current_now",
            voltagePath = "/sys/class/power_supply/bms/voltage_now",
        ),
        PathTuple(
            currentPath = "/sys/class/power_supply/bq2589x_charger/current_now",
            voltagePath = "/sys/class/power_supply/bq2589x_charger/voltage_now",
        ),
    )
}
