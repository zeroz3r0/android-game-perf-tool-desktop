package com.gameperf.desktop.core

/**
 * v4.5.0 -- Single source of truth for the GPU sysfs probe candidate list.
 *
 * Strict ordered list — Mali first (single-shot kernel-computed percent),
 * Adreno `gpu_busy_percentage` (single-shot percent) before Adreno `gpubusy`
 * (cumulative counters requiring delta math + 1-tick warm-up), PowerVR
 * placeholder last (Sprint 1.5 crowdsource entry — graceful degradation
 * with `POWERVR_UNSUPPORTED` reason today).
 *
 * Mirrors [FPowerVendorCatalog] and [ThermalZoneClassifier]: strict list, NO
 * fuzzy matching, NO substring guessing on this side. Adding a vendor means
 * appending one [GpuProbeCandidate] and bumping the substring-uniqueness
 * test count in [com.gameperf.desktop.core.GpuVendorCatalogTest].
 *
 * Anti-duplication rule (CLAUDE.md v4.2.13): every other module — bridge,
 * parser, FakeAdbBridge, report — reads probe paths from THIS object. No
 * inline path literals anywhere else.
 *
 * See `sdd/gpu-usage-percent/design` §2.1 + spec GPU-004 / GPU-008.
 */
object GpuVendorCatalog {

    /**
     * Ordered probe priority. ORDER MATTERS — the first non-empty hit wins
     * in [GpuUsageParser.parseProbeOutput]. FakeAdbBridge `shellResponses`
     * keys must remain substring-unique across this list (asserted in test).
     */
    val PROBE_CANDIDATES: List<GpuProbeCandidate> = listOf(
        // ── MALI (single-shot kernel-computed percent — fastest path) ────
        GpuProbeCandidate(
            vendor = GpuVendor.MALI,
            path = "/sys/class/misc/mali0/device/utilization",
            format = ProbeFormat.MALI_INT_0_100,
            confidence = Confidence.HIGH,
        ),
        GpuProbeCandidate(
            vendor = GpuVendor.MALI,
            // BSP typo alternate seen on older Samsung / Mediatek vendors.
            path = "/sys/class/misc/mali0/device/utility",
            format = ProbeFormat.MALI_INT_0_100,
            confidence = Confidence.MEDIUM,
        ),
        GpuProbeCandidate(
            vendor = GpuVendor.MALI,
            // Platform-bus alternate for kernels that don't expose /sys/class/misc/mali0/.
            path = "/sys/devices/platform/mali/utilization",
            format = ProbeFormat.MALI_INT_0_100,
            confidence = Confidence.MEDIUM,
        ),
        // ── ADRENO percent first (single-shot — no warm-up) ───────────────
        GpuProbeCandidate(
            vendor = GpuVendor.ADRENO,
            path = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            format = ProbeFormat.ADRENO_GPU_BUSY_PERCENTAGE,
            confidence = Confidence.HIGH,
        ),
        // ── ADRENO cumulative counters (delta math + 1-tick warm-up) ──────
        GpuProbeCandidate(
            vendor = GpuVendor.ADRENO,
            path = "/sys/class/kgsl/kgsl-3d0/gpubusy",
            format = ProbeFormat.ADRENO_KGSL_BUSY_TOTAL,
            confidence = Confidence.HIGH,
        ),
        // ── POWERVR placeholder (Sprint 1.5 crowdsource — LOW confidence) ─
        GpuProbeCandidate(
            vendor = GpuVendor.POWERVR,
            path = "/proc/mtk_mali/utilization",
            format = ProbeFormat.POWERVR_UNKNOWN,
            confidence = Confidence.LOW,
        ),
    )

    /**
     * Adreno A13+ privileged write target used to unlock the perfcounter
     * family when both Adreno probes return empty (see spec GPU-007 +
     * design §4 Adreno enable lifecycle). Write-only — NEVER a probe target,
     * asserted in [GpuVendorCatalogTest].
     */
    const val ADRENO_PERFCOUNTER_NODE: String = "/sys/class/kgsl/kgsl-3d0/perfcounter"
}

/** GPU vendor family inferred from the winning probe path. */
enum class GpuVendor { MALI, ADRENO, POWERVR }

/** Parse strategy associated with a probe path. */
enum class ProbeFormat {
    MALI_INT_0_100,
    ADRENO_KGSL_BUSY_TOTAL,
    ADRENO_GPU_BUSY_PERCENTAGE,
    POWERVR_UNKNOWN,
}

/** Reliability of a candidate path entry — informational, not enforced. */
enum class Confidence { HIGH, MEDIUM, LOW }

/**
 * One sysfs probe entry. Catalog-defined — never constructed at runtime by
 * the bridge or report (single source of truth in [GpuVendorCatalog]).
 */
data class GpuProbeCandidate(
    val vendor: GpuVendor,
    val path: String,
    val format: ProbeFormat,
    val confidence: Confidence,
)
