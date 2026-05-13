package com.gameperf.desktop.core.kpi

/**
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║  SINGLE SOURCE OF TRUTH for device-tier classification.                ║
 * ║                                                                        ║
 * ║  DO NOT inline tier lookups in callers. DO NOT maintain a parallel     ║
 * ║  device→tier map anywhere else. Adding a new device = appending ONE    ║
 * ║  entry to [TOP_MODELS] / [MID_MODELS] / [LOW_MODELS] below.            ║
 * ║                                                                        ║
 * ║  This mirrors the anti-duplication rule of `ThermalZoneClassifier`,    ║
 * ║  `SdkSignatureCatalog`, and `ToolResolver` (CLAUDE.md v4.2.13 /        ║
 * ║  v4.4.0). When the same data lives in two places, the next bug fix    ║
 * ║  inevitably forgets one copy.                                          ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * Tier definitions per `docs/competitive-analysis-and-kpis.md` §6.3:
 *  - TOP — 2024-class flagships (60 FPS, <2s cold start budget, ≥8 GB RAM).
 *  - MID — 2022-2023 mid-range (30-60 FPS, <3s cold start, 4-8 GB RAM).
 *           **Default tier for unrecognized devices** (spec scenario).
 *  - LOW — 2020-2021 / budget (30 FPS, <5s cold start, ≤4 GB RAM).
 *
 * Resolution strategy (mirror `SdkSignatureCatalog.activityClasses`):
 *  1. Trim + case-fold input.
 *  2. Exact-match against allow-lists in order TOP → MID → LOW.
 *  3. Substring containment for marketing names (e.g. `"SM-S911B (Galaxy
 *     S23)"` ⊇ `"galaxy s23"`).
 *  4. Default → MID.
 *
 * Allow-lists are deliberately CONSERVATIVE in v1 — when in doubt we fall
 * through to MID rather than wrongly classify a device. Adding new entries
 * is the explicit growth path (matches `SdkSignatureCatalog.ALL` evolution).
 *
 * @since v4.5 (kpi-scoring internal v1)
 */
object DeviceTierCatalog {

    /** 2024-class flagships and recent equivalents. */
    private val TOP_MODELS: Set<String> = setOf(
        // Google Pixel
        "pixel 7 pro",
        "pixel 8",
        "pixel 8 pro",
        "pixel 9",
        "pixel 9 pro",
        "pixel 9 pro xl",
        // Samsung Galaxy S — flagship line
        "galaxy s22",
        "galaxy s22+",
        "galaxy s22 ultra",
        "galaxy s23",
        "galaxy s23+",
        "galaxy s23 ultra",
        "galaxy s24",
        "galaxy s24+",
        "galaxy s24 ultra",
        // Samsung Galaxy S — model-number variants (SM-S9xxx)
        "sm-s901",
        "sm-s906",
        "sm-s908",
        "sm-s911",
        "sm-s911b", // S23 specific (CLAUDE.md v4.3.3 reference)
        "sm-s916",
        "sm-s918",
        "sm-s921",
        "sm-s926",
        "sm-s928",
        // OnePlus / Xiaomi flagships
        "oneplus 11",
        "oneplus 12",
        "xiaomi 13",
        "xiaomi 13 pro",
        "xiaomi 14",
        "xiaomi 14 pro",
    )

    /** 2022-2023 mid-range. */
    private val MID_MODELS: Set<String> = setOf(
        // Google Pixel A-series mid-range
        "pixel 6a",
        "pixel 7a",
        "pixel 8a",
        // Samsung Galaxy A — mid-range
        "galaxy a54",
        "galaxy a55",
        "sm-a546",
        "sm-a556",
        // Xiaomi Redmi Note (mid)
        "redmi note 12",
        "redmi note 13",
    )

    /** 2020-2021 / budget / older flagships. */
    private val LOW_MODELS: Set<String> = setOf(
        // Google Pixel older
        "pixel 4a",
        "pixel 5a",
        // Samsung Galaxy A — budget
        "galaxy a14",
        "galaxy a24",
        "sm-a145",
        "sm-a245",
        // Samsung Tab A — tablet budget
        "galaxy tab a8",
        "galaxy tab a7",
        "sm-x205",
        // Generic low-end markers
        "redmi 10",
        "redmi 12",
    )

    /**
     * Resolve a device model name to a [DeviceTier].
     *
     * @param deviceModel marketing name (e.g. "Pixel 8 Pro") OR model number
     *   (e.g. "SM-S911B"). Case-insensitive. Whitespace-trimmed.
     *   `null` / blank → [DeviceTier.MID] (spec scenario "unknown → MID").
     * @return resolved [DeviceTier]. Never returns null — MID is the safe
     *   middle-ground default per spec.
     */
    fun resolve(deviceModel: String?): DeviceTier {
        val normalized = deviceModel?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return DeviceTier.MID

        // 1. Exact match.
        if (normalized in TOP_MODELS) return DeviceTier.TOP
        if (normalized in MID_MODELS) return DeviceTier.MID
        if (normalized in LOW_MODELS) return DeviceTier.LOW

        // 2. Substring containment (e.g. "SM-S911B Galaxy S23" matches "galaxy s23").
        TOP_MODELS.firstOrNull { normalized.contains(it) }?.let { return DeviceTier.TOP }
        LOW_MODELS.firstOrNull { normalized.contains(it) }?.let { return DeviceTier.LOW }
        MID_MODELS.firstOrNull { normalized.contains(it) }?.let { return DeviceTier.MID }

        // 3. Default — safe middle-ground for unrecognized devices.
        return DeviceTier.MID
    }
}
