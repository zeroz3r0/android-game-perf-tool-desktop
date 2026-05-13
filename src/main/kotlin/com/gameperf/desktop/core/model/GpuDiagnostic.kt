package com.gameperf.desktop.core.model

import kotlinx.serialization.Serializable

/**
 * v4.5.0 -- Diagnostic payload populated when [GpuSnapshot.gpuAvailable] is
 * `false`. Mirrors the v4.4.1 [ThermalDiagnostic] pattern: surfaced via the
 * report HTML banner so the user (or dev team) can identify the unsupported
 * vendor / locked OEM / SELinux denial that prevented a usable reading.
 *
 * Bounded to keep failed-session export size predictable: callers SHOULD
 * truncate [probedPaths] to ~10 entries (see `sdd/gpu-usage-percent/design`
 * §2.4 and FakeAdbBridge substring-key uniqueness assertion in
 * [com.gameperf.desktop.core.GpuVendorCatalog]).
 *
 *  - [probedPaths] -- literal sysfs paths the bridge attempted (capped at 10).
 *  - [detectedVendor] -- vendor inferred from the winning probe, or `null` when
 *    every probe failed. Stable string-named (`"MALI"`, `"ADRENO"`, `"POWERVR"`)
 *    so report HTML can branch on it without enum dependency.
 *  - [failedEnableCommand] -- the exact `echo 1 > .../perfcounter` command the
 *    bridge issued when [reason] is [GpuUnavailableReason.ADRENO_PERFCOUNTER_DISABLED].
 *    `null` for every other reason.
 *  - [reason] -- proximate failure cause; see [GpuUnavailableReason].
 */
@Serializable
data class GpuDiagnostic(
    val probedPaths: List<String>,
    val detectedVendor: String? = null,
    val failedEnableCommand: String? = null,
    val reason: GpuUnavailableReason,
)

/**
 * v4.5.0 -- Why the GPU pipeline could not produce a usable snapshot.
 *
 *  - [ALL_PROBES_FAILED] -- every candidate path in [com.gameperf.desktop.core.GpuVendorCatalog]
 *    returned empty AND the perfcounter enable was not applicable (no Adreno
 *    candidate matched). Covers MALI_NOT_FOUND / ADRENO_NOT_FOUND /
 *    ALL_PATHS_EMPTY / NOT_PROBED_YET / OUT_OF_RANGE_VALUE / COUNTER_WRAPAROUND
 *    from the original spec — collapsed for Sprint 1 per design §2.4.
 *  - [ADRENO_BLOCKED] -- Adreno detected but the perfcounter family remains
 *    unreadable post-enable (rare; SELinux or OEM lock).
 *  - [ADRENO_PERFCOUNTER_DISABLED] -- Adreno detected, both probes empty, and
 *    the `echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter` enable attempt failed
 *    (A13+ locked OEMs typical).
 *  - [POWERVR_UNSUPPORTED] -- PowerVR detected (any path matched the catalog
 *    PowerVR placeholders) OR Sprint 1 graceful fallback when vendor unknown.
 *  - [CAPTURE_THREW] -- try/catch fallback for adb / shell / pipe / OOM
 *    exceptions. Mirrors thermal resilience pattern.
 */
@Serializable
enum class GpuUnavailableReason {
    ALL_PROBES_FAILED,
    ADRENO_BLOCKED,
    ADRENO_PERFCOUNTER_DISABLED,
    POWERVR_UNSUPPORTED,
    CAPTURE_THREW,
}
