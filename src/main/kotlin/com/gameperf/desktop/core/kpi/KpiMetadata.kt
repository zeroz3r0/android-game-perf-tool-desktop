package com.gameperf.desktop.core.kpi

import kotlinx.serialization.Serializable

/**
 * Foundation data types for the KPI scoring framework
 * (Issue #2 Block E, `sdd/kpi-scoring-framework`).
 *
 * Pure value types — NO I/O, NO mutable state, NO time. All KPI metadata
 * MUST live in [KpiCatalog]. All phase weights MUST live in [PhaseWeights].
 * DO NOT redefine these enums or data classes anywhere else.
 *
 * Source of truth for thresholds, units, categories:
 * `docs/competitive-analysis-and-kpis.md` §§3.1, 3.2, 3.6, 5.1, 5.2, 6.3.
 *
 * @since v4.5 (kpi-scoring internal v1)
 */

/**
 * Identifier for every KPI tracked by the framework.
 *
 * 26 entries derived from docs §5.1 master catalog. Spec requires ≥23 entries —
 * the framework currently exposes more granular split (e.g. cold/warm/hot
 * start trichotomy from §3.1 Vitals; PerfDog Single Jank + Big Jank per §3.6).
 *
 * IMPORTANT: Adding a new KPI means appending here AND adding a matching
 * entry to [KpiCatalog.ALL]. No KPI metadata lives anywhere else
 * (mirrors `SdkSignatureCatalog.ALL` / `ThermalZoneClassifier` pattern;
 * see CLAUDE.md v4.2.13 anti-duplication rule).
 */
enum class KpiId {
    // Smoothness (FPS / frame-time family)
    FPS_AVG,
    FPS_P1,
    FPS_STABILITY,
    FRAME_TIME_P99,
    SLOW_FRAMES,
    FROZEN_FRAMES,
    JANK_COUNT,
    BIG_JANK_COUNT,

    // Resource
    CPU_AVG_NORMALIZED,
    CPU_MAX,
    GPU_AVG,
    RAM_AVG,
    RAM_MAX,
    NETWORK_TOTAL,
    BATTERY_DRAIN,
    FPOWER,
    // v4.6.0 — single-session absolute hours of partial wake locks accumulated
    // with the screen off, as a proxy for Google Play Vitals's "excessive partial
    // wake locks" gate (>2h in any 24h window). Single source: KpiCatalog.ALL.
    WAKE_LOCKS_RATE,

    // Thermal
    TEMP_AVG,
    TEMP_MAX,
    THROTTLING_EVENTS,

    // Stability
    ANR_COUNT,
    CRASH_COUNT,
    SLOW_SESSION_RATE,
    // v4.6.0 — Vitals-aware single-session proxies (sdd/vitals-rate-and-wakelocks).
    // Naming matches Google Play Vitals semantics (cross-session user-perceived rate);
    // v1 measures a single-session count/total as a proxy. v2 will roll up
    // SessionHistory entries to compute the real rate.
    CRASH_RATE_USERS,
    ANR_RATE_USERS,

    // Responsiveness
    COLD_START_MS,
    WARM_START_MS,
    HOT_START_MS,
    TTID,
    TTFD,
}

/** KPI grouping per docs §6.4. */
enum class Category {
    Smoothness,
    Resource,
    Thermal,
    Stability,
    Responsiveness,
}

/** Game phase per docs §4.1. */
enum class Phase {
    APP_STARTUP,
    CINEMATIC,
    TUTORIAL,
    LEVEL_LOADING,
    SCREEN_NAV,
    INTERSTITIAL_AD,
    REWARDED_AD,
    GAMEPLAY,
}

/** Device class for tier-specific thresholds (docs §6.3). */
enum class DeviceTier {
    TOP,
    MID,
    LOW,
}

/**
 * Whether higher KPI values are better (e.g. FPS) or lower are better
 * (e.g. frame-time, cold-start time, ANR count).
 */
enum class Direction {
    HIGHER_IS_BETTER,
    LOWER_IS_BETTER,
}

/**
 * Trichotomy band per spec — `score ≥ 80 → GREEN`, `60 ≤ score < 80 → AMBER`,
 * `score < 60 → RED`. See spec Requirement: Comparison Engine with Color Bands.
 */
enum class Band {
    GREEN,
    AMBER,
    RED,
}

/**
 * Per-tier scoring threshold pair.
 *
 * - For [Direction.HIGHER_IS_BETTER]: `value ≥ target → 100`, `value ≤ floor → 0`,
 *   linear interpolation between.
 * - For [Direction.LOWER_IS_BETTER]: `value ≤ target → 100`, `value ≥ floor → 0`,
 *   linear interpolation between (i.e. floor is the WORST value here).
 *
 * Both numbers are kept as `Double` regardless of the KPI's natural unit so
 * the pure scoring math has no unit conversions.
 */
data class Threshold(val target: Double, val floor: Double)

/**
 * Catalog entry for a single KPI.
 *
 * @property id stable enum id used by aggregators and reports.
 * @property unit display unit (`fps`, `ms`, `%`, `MB`, `°C`, `mW/frame`, `count`).
 * @property category grouping for cross-phase category aggregation (§6.4).
 * @property direction whether higher or lower raw values are better.
 * @property thresholds per-tier (TOP/MID/LOW) `[Threshold]`. Catalog
 *   invariant: ALL three tiers MUST be present (see `KpiCatalogTest`).
 * @property sourceCitation human-readable doc anchor (e.g. "§3.1 Android Vitals
 *   slow cold start ≥5s"). Catalog invariant: MUST be non-empty.
 */
data class Kpi(
    val id: KpiId,
    val unit: String,
    val category: Category,
    val direction: Direction,
    val thresholds: Map<DeviceTier, Threshold>,
    val sourceCitation: String,
)

/**
 * Per-KPI scoring result inside a phase.
 *
 * @property rawValue the measured value (null when the session lacks data —
 *   aggregators MUST exclude null-value KPIs from the weighted denominator).
 * @property score 0..100 from `LinearScoring.scoreLinear`.
 * @property delta `rawValue - target` (signed); meaningful sign depends on
 *   the KPI's [Direction].
 * @property band trichotomy band per spec.
 */
@Serializable
data class KpiScore(
    val id: KpiId,
    val phase: Phase,
    val rawValue: Double?,
    val score: Int,
    val delta: Double,
    val band: Band,
)

/** Per-phase aggregate, with the underlying per-KPI scores attached for drill-down. */
@Serializable
data class PhaseScore(
    val phase: Phase,
    val score: Int,
    val band: Band,
    val kpiScores: List<KpiScore>,
)

/** Per-category aggregate (cross-phase). */
@Serializable
data class CategoryScore(
    val category: Category,
    val score: Int,
    val band: Band,
)

/**
 * Top-level KPI scoring artifact for one session.
 *
 * `null` is returned by `KpiScoringFacade.compute(...)` when the internal
 * feature flag is OFF (see design D5).
 *
 * `@Serializable` because the shareable HTML report (`sdd/shareable-html-report`)
 * embeds a base64 JSON copy as a downloadable data URL. The download must
 * round-trip back through `kotlinx-serialization` to the same value
 * (asserted by `KpiMetadataSerializationTest`).
 */
@Serializable
data class KpiScoreReport(
    val sessionScore: Int,
    val sessionBand: Band,
    val phases: List<PhaseScore>,
    val categories: List<CategoryScore>,
)
