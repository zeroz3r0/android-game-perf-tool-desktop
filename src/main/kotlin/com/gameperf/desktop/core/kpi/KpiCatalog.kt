package com.gameperf.desktop.core.kpi

/**
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║  SINGLE SOURCE OF TRUTH for every KPI metric the scoring framework     ║
 * ║  knows about.                                                          ║
 * ║                                                                        ║
 * ║  DO NOT define KPI metadata (thresholds, citations, direction, units)  ║
 * ║  ANYWHERE ELSE in the codebase. Adding a new KPI = appending one       ║
 * ║  [Kpi] entry to [ALL] AND one matching [KpiId] enum entry in           ║
 * ║  `KpiMetadata.kt`. No parallel catalogs, no inline `Threshold(...)`    ║
 * ║  literals scattered across modules.                                    ║
 * ║                                                                        ║
 * ║  This mirrors the anti-duplication rule learned the hard way for       ║
 * ║  `SdkSignatureCatalog.ALL` and `ToolResolver` (see CLAUDE.md v4.2.13   ║
 * ║  and v4.4.0). When this rule has been broken in the past, the same     ║
 * ║  bug recurred three releases in a row.                                 ║
 * ║                                                                        ║
 * ║  Architectural test `9.2` in `tasks.md` greps `KpiId\.` outside        ║
 * ║  `core/kpi/` and MUST find zero references except call sites that      ║
 * ║  use `KpiCatalog.byId(...)`.                                           ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * Threshold anchors come from `docs/competitive-analysis-and-kpis.md`:
 *  - §3.1 Android Vitals (cold/warm/hot start, slow/frozen frames, ANR,
 *    crash, slow-session-rate, network).
 *  - §3.2 RAIL (16 ms frame budget → frame-time p99 target).
 *  - §3.6 PerfDog (FPower mW/frame, Jank thresholds 84/125 ms,
 *    CPU% normalized).
 *  - §5.1 master KPI catalog (this file's structure mirrors that table).
 *  - §6.3 device-tier breakdown (TOP/MID/LOW).
 *
 * Tier rationale (recap):
 *  - TOP   → 2024 flagship class (e.g. Pixel 8 Pro, Galaxy S23+). Higher
 *            FPS targets, tighter latency budgets, more RAM headroom.
 *  - MID   → 2022-2023 mid-range. Default for unknown devices.
 *  - LOW   → 2020-2021 / budget. Relaxed FPS targets (30fps), longer
 *            cold-start budgets (≤5s before Vitals "slow").
 *
 * @since v4.5 (kpi-scoring internal v1)
 */
object KpiCatalog {

    /**
     * Every KPI tracked by the framework, with per-tier scoring thresholds.
     * Order in the list is irrelevant to scoring but is grouped by [Category]
     * for readability.
     */
    val ALL: List<Kpi> = listOf(
        // ══════════════════════════ Smoothness ══════════════════════════
        Kpi(
            id = KpiId.FPS_AVG,
            unit = "fps",
            category = Category.Smoothness,
            direction = Direction.HIGHER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 60.0, floor = 30.0),
                DeviceTier.MID to Threshold(target = 45.0, floor = 24.0),
                DeviceTier.LOW to Threshold(target = 30.0, floor = 20.0),
            ),
            sourceCitation = "docs §3.1 Vitals games (30/20 FPS bar) + §6.3 tier targets",
        ),
        Kpi(
            id = KpiId.FPS_P1,
            unit = "fps",
            category = Category.Smoothness,
            direction = Direction.HIGHER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 50.0, floor = 25.0),
                DeviceTier.MID to Threshold(target = 35.0, floor = 20.0),
                DeviceTier.LOW to Threshold(target = 25.0, floor = 15.0),
            ),
            sourceCitation = "docs §5.1 FPS p1 (press §2.3 PC-grade percentile)",
        ),
        Kpi(
            id = KpiId.FPS_STABILITY,
            unit = "%",
            category = Category.Smoothness,
            direction = Direction.HIGHER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 95.0, floor = 80.0),
                DeviceTier.MID to Threshold(target = 90.0, floor = 70.0),
                DeviceTier.LOW to Threshold(target = 85.0, floor = 60.0),
            ),
            sourceCitation = "docs §5.1 (% frames within ±10% of target — GameBench convention)",
        ),
        Kpi(
            id = KpiId.FRAME_TIME_P99,
            unit = "ms",
            category = Category.Smoothness,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 16.6, floor = 33.3),
                DeviceTier.MID to Threshold(target = 22.2, floor = 50.0),
                DeviceTier.LOW to Threshold(target = 33.3, floor = 84.0),
            ),
            sourceCitation = "docs §3.2 RAIL 16 ms budget + §3.4 frame-budget table",
        ),
        Kpi(
            id = KpiId.SLOW_FRAMES,
            unit = "%",
            category = Category.Smoothness,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 10.0, floor = 50.0),
                DeviceTier.MID to Threshold(target = 20.0, floor = 50.0),
                DeviceTier.LOW to Threshold(target = 30.0, floor = 50.0),
            ),
            sourceCitation = "docs §3.1 Vitals 'Excessive slow frames' >50% bad threshold",
        ),
        Kpi(
            id = KpiId.FROZEN_FRAMES,
            unit = "%",
            category = Category.Smoothness,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 0.01, floor = 0.1),
                DeviceTier.MID to Threshold(target = 0.05, floor = 0.1),
                DeviceTier.LOW to Threshold(target = 0.08, floor = 0.1),
            ),
            sourceCitation = "docs §3.1 Vitals 'Excessive frozen frames' >0.1% bad threshold",
        ),
        Kpi(
            id = KpiId.JANK_COUNT,
            unit = "count",
            category = Category.Smoothness,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 0.0, floor = 30.0),
                DeviceTier.MID to Threshold(target = 2.0, floor = 50.0),
                DeviceTier.LOW to Threshold(target = 5.0, floor = 80.0),
            ),
            sourceCitation = "docs §3.6 PerfDog Jank (FT>2×avg(3) AND FT>84ms)",
        ),
        Kpi(
            id = KpiId.BIG_JANK_COUNT,
            unit = "count",
            category = Category.Smoothness,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 0.0, floor = 5.0),
                DeviceTier.MID to Threshold(target = 0.0, floor = 10.0),
                DeviceTier.LOW to Threshold(target = 1.0, floor = 20.0),
            ),
            sourceCitation = "docs §3.6 PerfDog Big Jank (FT>2×avg(3) AND FT>125ms)",
        ),

        // ══════════════════════════ Resource ══════════════════════════
        Kpi(
            id = KpiId.CPU_AVG_NORMALIZED,
            unit = "%",
            category = Category.Resource,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 30.0, floor = 70.0),
                DeviceTier.MID to Threshold(target = 40.0, floor = 80.0),
                DeviceTier.LOW to Threshold(target = 50.0, floor = 90.0),
            ),
            sourceCitation = "docs §3.6 PerfDog freq-normalized CPU% (removes throttle distortion)",
        ),
        Kpi(
            id = KpiId.CPU_MAX,
            unit = "%",
            category = Category.Resource,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 60.0, floor = 95.0),
                DeviceTier.MID to Threshold(target = 70.0, floor = 95.0),
                DeviceTier.LOW to Threshold(target = 80.0, floor = 100.0),
            ),
            sourceCitation = "docs §5.1 CPU max (tier-dependent peak)",
        ),
        Kpi(
            id = KpiId.GPU_AVG,
            unit = "%",
            category = Category.Resource,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 40.0, floor = 85.0),
                DeviceTier.MID to Threshold(target = 50.0, floor = 90.0),
                DeviceTier.LOW to Threshold(target = 60.0, floor = 95.0),
            ),
            sourceCitation = "docs §5.1 GPU avg (sysfs — Sprint 1 paused, threshold for v2)",
        ),
        Kpi(
            id = KpiId.RAM_AVG,
            unit = "MB",
            category = Category.Resource,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 1024.0, floor = 3072.0),
                DeviceTier.MID to Threshold(target = 768.0, floor = 1536.0),
                DeviceTier.LOW to Threshold(target = 512.0, floor = 1024.0),
            ),
            sourceCitation = "docs §3.4 derived (1GB→512MB game budget convention) + §6.3 tier headroom",
        ),
        Kpi(
            id = KpiId.RAM_MAX,
            unit = "MB",
            category = Category.Resource,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 1536.0, floor = 4096.0),
                DeviceTier.MID to Threshold(target = 1024.0, floor = 2048.0),
                DeviceTier.LOW to Threshold(target = 768.0, floor = 1280.0),
            ),
            sourceCitation = "docs §5.1 RAM max (peak RSS, tier headroom §6.3)",
        ),
        Kpi(
            id = KpiId.NETWORK_TOTAL,
            unit = "MB",
            category = Category.Resource,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 10.0, floor = 100.0),
                DeviceTier.MID to Threshold(target = 10.0, floor = 100.0),
                DeviceTier.LOW to Threshold(target = 5.0, floor = 50.0),
            ),
            sourceCitation = "docs §3.1 Vitals bg >50 MB/day bad behavior",
        ),
        Kpi(
            id = KpiId.BATTERY_DRAIN,
            unit = "mAh",
            category = Category.Resource,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 100.0, floor = 500.0),
                DeviceTier.MID to Threshold(target = 100.0, floor = 500.0),
                DeviceTier.LOW to Threshold(target = 80.0, floor = 400.0),
            ),
            sourceCitation = "docs §3.1 Vitals watch face 4.44%/h analogue extended to game session",
        ),
        Kpi(
            id = KpiId.FPOWER,
            unit = "mW/frame",
            category = Category.Resource,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 50.0, floor = 65.0),
                DeviceTier.MID to Threshold(target = 50.0, floor = 65.0),
                DeviceTier.LOW to Threshold(target = 50.0, floor = 65.0),
            ),
            sourceCitation = "docs §3.6 PerfDog FPower <50 excellent / 50-65 acceptable / >65 bad",
        ),
        Kpi(
            id = KpiId.WAKE_LOCKS_RATE,
            unit = "h",
            category = Category.Resource,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 0.5, floor = 2.0),
                DeviceTier.MID to Threshold(target = 0.5, floor = 2.0),
                DeviceTier.LOW to Threshold(target = 0.5, floor = 2.0),
            ),
            sourceCitation = "Google Play Vitals 2024 — excessive partial wake locks >2h in 24h" +
                " screen-off = bad behavior (engram #424). v1 single-session absolute hours" +
                " proxy; v2 cross-session.",
        ),

        // ══════════════════════════ Thermal ══════════════════════════
        Kpi(
            id = KpiId.TEMP_AVG,
            unit = "°C",
            category = Category.Thermal,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 38.0, floor = 45.0),
                DeviceTier.MID to Threshold(target = 38.0, floor = 45.0),
                DeviceTier.LOW to Threshold(target = 38.0, floor = 45.0),
            ),
            sourceCitation = "docs §5.1 skin avg (vendor-agnostic; throttling ~42°C from UI copy)",
        ),
        Kpi(
            id = KpiId.TEMP_MAX,
            unit = "°C",
            category = Category.Thermal,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 42.0, floor = 48.0),
                DeviceTier.MID to Threshold(target = 42.0, floor = 48.0),
                DeviceTier.LOW to Threshold(target = 42.0, floor = 48.0),
            ),
            sourceCitation = "docs §5.1 skin max (throttle threshold + 6°C panic zone)",
        ),
        Kpi(
            id = KpiId.THROTTLING_EVENTS,
            unit = "count",
            category = Category.Thermal,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 0.0, floor = 3.0),
                DeviceTier.MID to Threshold(target = 0.0, floor = 5.0),
                DeviceTier.LOW to Threshold(target = 0.0, floor = 10.0),
            ),
            sourceCitation = "docs §5.1 thermalservice events (0 preferred)",
        ),

        // ══════════════════════════ Stability ══════════════════════════
        Kpi(
            id = KpiId.ANR_COUNT,
            unit = "count",
            category = Category.Stability,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 0.0, floor = 1.0),
                DeviceTier.MID to Threshold(target = 0.0, floor = 1.0),
                DeviceTier.LOW to Threshold(target = 0.0, floor = 2.0),
            ),
            sourceCitation = "docs §3.1 Vitals user-perceived ANR ≥0.47% DAU bad (0 per session preferred)",
        ),
        Kpi(
            id = KpiId.CRASH_COUNT,
            unit = "count",
            category = Category.Stability,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 0.0, floor = 1.0),
                DeviceTier.MID to Threshold(target = 0.0, floor = 1.0),
                DeviceTier.LOW to Threshold(target = 0.0, floor = 1.0),
            ),
            sourceCitation = "docs §3.1 Vitals user-perceived crash ≥1.09% DAU bad",
        ),
        Kpi(
            id = KpiId.SLOW_SESSION_RATE,
            unit = "%",
            category = Category.Stability,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 5.0, floor = 25.0),
                DeviceTier.MID to Threshold(target = 10.0, floor = 25.0),
                DeviceTier.LOW to Threshold(target = 15.0, floor = 25.0),
            ),
            sourceCitation = "docs §3.1 Vitals slow-session-rate >25% bad (FPS-target-aware)",
        ),
        Kpi(
            id = KpiId.CRASH_RATE_USERS,
            unit = "%",
            category = Category.Stability,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 0.0, floor = 1.09),
                DeviceTier.MID to Threshold(target = 0.0, floor = 1.09),
                DeviceTier.LOW to Threshold(target = 0.0, floor = 1.09),
            ),
            sourceCitation = "Google Play Vitals 2024 — user-perceived crash rate ≥1.09% = bad behavior" +
                " (engram #424). v1 single-session proxy; v2 cross-session.",
        ),
        Kpi(
            id = KpiId.ANR_RATE_USERS,
            unit = "%",
            category = Category.Stability,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 0.0, floor = 0.47),
                DeviceTier.MID to Threshold(target = 0.0, floor = 0.47),
                DeviceTier.LOW to Threshold(target = 0.0, floor = 0.47),
            ),
            sourceCitation = "Google Play Vitals 2024 — user-perceived ANR rate ≥0.47% = bad behavior" +
                " (engram #424). v1 single-session proxy; v2 cross-session.",
        ),

        // ══════════════════════════ Responsiveness ══════════════════════════
        Kpi(
            id = KpiId.COLD_START_MS,
            unit = "ms",
            category = Category.Responsiveness,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 1500.0, floor = 5000.0),
                DeviceTier.MID to Threshold(target = 2000.0, floor = 5000.0),
                DeviceTier.LOW to Threshold(target = 3000.0, floor = 5000.0),
            ),
            sourceCitation = "docs §3.1 Vitals cold start ≥5s SLOW + §3.5 Apple 400ms anchor",
        ),
        Kpi(
            id = KpiId.WARM_START_MS,
            unit = "ms",
            category = Category.Responsiveness,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 500.0, floor = 2000.0),
                DeviceTier.MID to Threshold(target = 700.0, floor = 2000.0),
                DeviceTier.LOW to Threshold(target = 1000.0, floor = 2000.0),
            ),
            sourceCitation = "docs §3.1 Vitals warm start ≥2s SLOW",
        ),
        Kpi(
            id = KpiId.HOT_START_MS,
            unit = "ms",
            category = Category.Responsiveness,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 200.0, floor = 1000.0),
                DeviceTier.MID to Threshold(target = 300.0, floor = 1000.0),
                DeviceTier.LOW to Threshold(target = 500.0, floor = 1000.0),
            ),
            sourceCitation = "docs §3.1 Vitals hot start ≥1s SLOW",
        ),
        Kpi(
            id = KpiId.TTID,
            unit = "ms",
            category = Category.Responsiveness,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 1000.0, floor = 3000.0),
                DeviceTier.MID to Threshold(target = 1500.0, floor = 3000.0),
                DeviceTier.LOW to Threshold(target = 2000.0, floor = 3000.0),
            ),
            sourceCitation = "docs §5.1 TTID (Sentry §2.2 convention)",
        ),
        Kpi(
            id = KpiId.TTFD,
            unit = "ms",
            category = Category.Responsiveness,
            direction = Direction.LOWER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(target = 2000.0, floor = 5000.0),
                DeviceTier.MID to Threshold(target = 3000.0, floor = 5000.0),
                DeviceTier.LOW to Threshold(target = 4000.0, floor = 6000.0),
            ),
            sourceCitation = "docs §5.1 TTFD (Sentry §2.2 — opt-in, full display)",
        ),
    )

    /** Indexed view used by aggregators. Built once from [ALL]. */
    private val byIdMap: Map<KpiId, Kpi> = ALL.associateBy { it.id }

    /**
     * Lookup helper — `byId(KpiId.FPS_AVG)` returns the catalog entry.
     * Throws `IllegalStateException` if the id is missing from [ALL]
     * (catalog invariant test `byId returns the matching entry` enforces
     * presence of every enum entry).
     */
    fun byId(id: KpiId): Kpi =
        byIdMap[id] ?: error("KpiCatalog is missing entry for $id — every KpiId must be listed in ALL")

    /** Returns every KPI in [category]. */
    fun forCategory(category: Category): List<Kpi> =
        ALL.filter { it.category == category }
}
