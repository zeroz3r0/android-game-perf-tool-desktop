package com.gameperf.desktop.core.kpi

/**
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║  SINGLE SOURCE OF TRUTH for KPI-per-phase and phase-relative weights.  ║
 * ║                                                                        ║
 * ║  DO NOT inline weight literals in aggregators. DO NOT maintain a       ║
 * ║  parallel weights table anywhere else. Adding a phase / KPI weight =   ║
 * ║  editing this file and ONLY this file.                                 ║
 * ║                                                                        ║
 * ║  This mirrors the anti-duplication rule of `KpiCatalog`,               ║
 * ║  `SdkSignatureCatalog`, `ThermalZoneClassifier`, and `ToolResolver`    ║
 * ║  (CLAUDE.md v4.2.13 / v4.4.0). Every time this rule has been broken   ║
 * ║  in this codebase, the same bug has re-shipped 1-3 releases later.    ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * Anchors:
 *  - `docs/competitive-analysis-and-kpis.md` §5.2 (KPI relevance per phase).
 *  - `docs/competitive-analysis-and-kpis.md` §4.1 (eight game phases).
 *  - design D4 (missing data renormalizes — aggregators recompute the
 *    denominator from KPIs/phases actually present in the session, so the
 *    "sum to 1.0" invariant here is the canonical-shape invariant).
 *
 * Layout rationale:
 *  - Critical KPIs from §5.2 get the heaviest weight.
 *  - Important KPIs get medium weight.
 *  - Nice-to-have KPIs are either omitted (semantically equivalent to
 *    "irrelevant for this phase") or given a small weight.
 *  - Irrelevant KPIs per §5.2 are NOT present in a phase's map.
 *
 * Per-phase maps SUM TO 1.0 (within ε = 1e-3). Property test
 * `PhaseWeightsTest` is the canary.
 *
 * @since v4.5 (kpi-scoring internal v1)
 */
data class PhaseWeights(
    /**
     * For each [Phase], the weighted contribution of each [KpiId] to that
     * phase's aggregated score. Sum of values in each inner map ≈ 1.0.
     */
    val kpiWeightsForPhase: Map<Phase, Map<KpiId, Double>>,

    /**
     * Weight of each [Phase] inside the session-level aggregate.
     * Sum ≈ 1.0. Per design D4, sessions missing a phase renormalize at
     * the [SessionAggregator] layer (this table is the canonical shape).
     */
    val phaseWeights: Map<Phase, Double>,
) {
    companion object {

        /**
         * Canonical default weights derived from docs §5.2 + product
         * judgement on the "Critical/Important/Nice-to-have" trichotomy.
         *
         * Numbers chosen so each phase map sums to exactly 1.0 (no
         * floating-point drift) and gameplay dominates the session
         * aggregate (gameplay is the biggest window in any captured
         * session by minutes-on-screen).
         */
        val DEFAULT: PhaseWeights = PhaseWeights(
            kpiWeightsForPhase = mapOf(
                // ── App startup / SDK init ───────────────────────────
                // §5.2 Critical: cold start, TTID, RAM at boot, slow frames.
                // §5.2 Important: CPU peak, ANR, crash.
                Phase.APP_STARTUP to mapOf(
                    KpiId.COLD_START_MS to 0.35,
                    KpiId.TTID to 0.20,
                    KpiId.RAM_AVG to 0.10,
                    KpiId.SLOW_FRAMES to 0.10,
                    KpiId.CPU_MAX to 0.10,
                    KpiId.ANR_COUNT to 0.075,
                    KpiId.CRASH_COUNT to 0.075,
                ),

                // ── Cinematics ───────────────────────────────────────
                // §5.2 Critical: FPS stability, frame time p99, frozen frames.
                // §5.2 Important: CPU avg, GPU avg, slow frames, FPower.
                Phase.CINEMATIC to mapOf(
                    KpiId.FPS_STABILITY to 0.30,
                    KpiId.FRAME_TIME_P99 to 0.20,
                    KpiId.FROZEN_FRAMES to 0.15,
                    KpiId.CPU_AVG_NORMALIZED to 0.10,
                    KpiId.GPU_AVG to 0.10,
                    KpiId.SLOW_FRAMES to 0.10,
                    KpiId.FPOWER to 0.05,
                ),

                // ── Tutorials ────────────────────────────────────────
                // §5.2 Critical: FPS stability, slow frames, TTID per screen.
                // §5.2 Important: CPU avg, RAM, FPower.
                Phase.TUTORIAL to mapOf(
                    KpiId.FPS_STABILITY to 0.30,
                    KpiId.SLOW_FRAMES to 0.20,
                    KpiId.TTID to 0.20,
                    KpiId.CPU_AVG_NORMALIZED to 0.15,
                    KpiId.RAM_AVG to 0.10,
                    KpiId.FPOWER to 0.05,
                ),

                // ── Level / map loading ──────────────────────────────
                // §5.2 Critical: loading time (TTFD), RAM peak, network total.
                // §5.2 Important: CPU peak, frame time p99.
                Phase.LEVEL_LOADING to mapOf(
                    KpiId.TTFD to 0.35,
                    KpiId.RAM_MAX to 0.20,
                    KpiId.NETWORK_TOTAL to 0.15,
                    KpiId.CPU_MAX to 0.15,
                    KpiId.FRAME_TIME_P99 to 0.15,
                ),

                // ── Screen navigation ────────────────────────────────
                // §5.2 Critical: TTID per transition, frame time p99.
                // §5.2 Important: CPU peak, RAM delta, slow frames.
                Phase.SCREEN_NAV to mapOf(
                    KpiId.TTID to 0.35,
                    KpiId.FRAME_TIME_P99 to 0.25,
                    KpiId.CPU_MAX to 0.15,
                    KpiId.RAM_AVG to 0.15,
                    KpiId.SLOW_FRAMES to 0.10,
                ),

                // ── Interstitial ads ─────────────────────────────────
                // §5.2 Critical: RAM delta, network total during ad load,
                //                frame time on close, slow frames during ad.
                // §5.2 Important: CPU avg normalized.
                Phase.INTERSTITIAL_AD to mapOf(
                    KpiId.RAM_MAX to 0.25,
                    KpiId.NETWORK_TOTAL to 0.25,
                    KpiId.FRAME_TIME_P99 to 0.20,
                    KpiId.SLOW_FRAMES to 0.15,
                    KpiId.CPU_AVG_NORMALIZED to 0.15,
                ),

                // ── Rewarded video ───────────────────────────────────
                // §5.2 Same as interstitial + video FPS continuity.
                Phase.REWARDED_AD to mapOf(
                    KpiId.FPS_STABILITY to 0.20,
                    KpiId.RAM_MAX to 0.20,
                    KpiId.NETWORK_TOTAL to 0.20,
                    KpiId.FRAME_TIME_P99 to 0.15,
                    KpiId.SLOW_FRAMES to 0.15,
                    KpiId.CPU_AVG_NORMALIZED to 0.10,
                ),

                // ── Gameplay (default) ───────────────────────────────
                // §5.2 Critical: FPS avg, FPS p1, FPS stability, temperature
                //                avg/max, throttling events, FPower.
                // §5.2 Important: GPU avg, CPU avg normalized, RAM, slow
                //                 session rate, battery drain, PerfDog Jank.
                Phase.GAMEPLAY to mapOf(
                    KpiId.FPS_AVG to 0.20,
                    KpiId.FPS_P1 to 0.15,
                    KpiId.FPS_STABILITY to 0.15,
                    KpiId.TEMP_MAX to 0.10,
                    KpiId.THROTTLING_EVENTS to 0.05,
                    KpiId.FPOWER to 0.10,
                    KpiId.JANK_COUNT to 0.05,
                    KpiId.CPU_AVG_NORMALIZED to 0.05,
                    KpiId.GPU_AVG to 0.05,
                    KpiId.RAM_AVG to 0.05,
                    KpiId.SLOW_SESSION_RATE to 0.05,
                ),
            ),

            // Phase-level weights — gameplay dominates because it is the
            // biggest window in any captured session. Startup runs once
            // per session, ads / loading are bursts, gameplay is the
            // ongoing steady-state being measured.
            phaseWeights = mapOf(
                Phase.APP_STARTUP to 0.10,
                Phase.CINEMATIC to 0.05,
                Phase.TUTORIAL to 0.05,
                Phase.LEVEL_LOADING to 0.10,
                Phase.SCREEN_NAV to 0.05,
                Phase.INTERSTITIAL_AD to 0.05,
                Phase.REWARDED_AD to 0.05,
                Phase.GAMEPLAY to 0.55,
            ),
        )
    }
}
