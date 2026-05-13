package com.gameperf.desktop.core.kpi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 4 — `PhaseAggregator` invariants.
 *
 * Spec coverage: `sdd/kpi-scoring-framework/spec` — Requirement: Phase
 * Aggregation. Renormalize-on-missing-data per design D4: KPIs with null
 * score are excluded from the weighted denominator.
 *
 * Pure: deterministic, no I/O.
 */
class PhaseAggregatorTest {

    private val weights = PhaseWeights.DEFAULT

    @Test
    fun `happy path computes weighted average of present KPIs`() {
        // GAMEPLAY weights (subset used here):
        //   FPS_AVG = 0.20, FPS_P1 = 0.15, FPS_STABILITY = 0.15
        // Provide ALL gameplay KPIs so denominator = 1.0 exactly.
        val gameplayWeights = weights.kpiWeightsForPhase[Phase.GAMEPLAY]!!
        // Pick a uniform score = 80 for every gameplay KPI → weighted avg = 80.
        val scores: Map<KpiId, Int?> = gameplayWeights.mapValues { 80 }

        val result = aggregatePhase(Phase.GAMEPLAY, scores, weights)
        assertEquals(Phase.GAMEPLAY, result?.phase)
        assertEquals(80, result?.score)
        assertEquals(Band.GREEN, result?.band)
    }

    @Test
    fun `weighted average reflects different per-KPI scores`() {
        // APP_STARTUP weights:
        //   COLD_START_MS=0.35, TTID=0.20, RAM_AVG=0.10, SLOW_FRAMES=0.10,
        //   CPU_MAX=0.10, ANR_COUNT=0.075, CRASH_COUNT=0.075
        // Scores: 100 across the board EXCEPT cold-start=0 → expect 100*(1-0.35)+0*0.35 = 65.
        val startup = weights.kpiWeightsForPhase[Phase.APP_STARTUP]!!
        val scores: Map<KpiId, Int?> = startup.mapValues { (kpi, _) ->
            if (kpi == KpiId.COLD_START_MS) 0 else 100
        }
        val result = aggregatePhase(Phase.APP_STARTUP, scores, weights)
        assertEquals(65, result?.score) // 100*0.65 = 65
        assertEquals(Band.AMBER, result?.band)
    }

    @Test
    fun `missing KPI is excluded and weights are renormalized`() {
        // APP_STARTUP weights total 1.0. Drop COLD_START_MS (weight 0.35),
        // remaining weights sum to 0.65. Scores: 100 across the remaining KPIs.
        // Renormalized average MUST be 100 (not 65 — that would be wrong).
        val startup = weights.kpiWeightsForPhase[Phase.APP_STARTUP]!!
        val scores: Map<KpiId, Int?> = startup.mapValues { (kpi, _) ->
            if (kpi == KpiId.COLD_START_MS) null else 100
        }
        val result = aggregatePhase(Phase.APP_STARTUP, scores, weights)
        assertEquals(100, result?.score)
        assertEquals(Band.GREEN, result?.band)
    }

    @Test
    fun `renormalization with mixed scores`() {
        // APP_STARTUP, drop COLD_START_MS. Remaining KPIs:
        //   TTID(0.20)=100, RAM_AVG(0.10)=0, SLOW_FRAMES(0.10)=100,
        //   CPU_MAX(0.10)=50, ANR(0.075)=100, CRASH(0.075)=100.
        // Numerator = 0.20*100 + 0.10*0 + 0.10*100 + 0.10*50 + 0.075*100 + 0.075*100
        //           = 20 + 0 + 10 + 5 + 7.5 + 7.5 = 50.
        // Denominator = 0.20+0.10+0.10+0.10+0.075+0.075 = 0.65.
        // Renormalized score = 50 / 0.65 ≈ 76.92 → roundToInt() = 77.
        val scores: Map<KpiId, Int?> = mapOf(
            KpiId.COLD_START_MS to null,
            KpiId.TTID to 100,
            KpiId.RAM_AVG to 0,
            KpiId.SLOW_FRAMES to 100,
            KpiId.CPU_MAX to 50,
            KpiId.ANR_COUNT to 100,
            KpiId.CRASH_COUNT to 100,
        )
        val result = aggregatePhase(Phase.APP_STARTUP, scores, weights)
        assertEquals(77, result?.score)
        assertEquals(Band.AMBER, result?.band)
    }

    @Test
    fun `all KPIs missing returns null`() {
        val startup = weights.kpiWeightsForPhase[Phase.APP_STARTUP]!!
        val scores: Map<KpiId, Int?> = startup.mapValues { null }
        val result = aggregatePhase(Phase.APP_STARTUP, scores, weights)
        assertNull(result)
    }

    @Test
    fun `empty score map returns null`() {
        val result = aggregatePhase(Phase.GAMEPLAY, emptyMap(), weights)
        assertNull(result)
    }

    @Test
    fun `score on band boundaries`() {
        // Engineer scores so the weighted average is exactly 80 (GREEN).
        val gameplayWeights = weights.kpiWeightsForPhase[Phase.GAMEPLAY]!!
        val scores80: Map<KpiId, Int?> = gameplayWeights.mapValues { 80 }
        val r80 = aggregatePhase(Phase.GAMEPLAY, scores80, weights)
        assertEquals(80, r80?.score)
        assertEquals(Band.GREEN, r80?.band)

        // 60 → AMBER (inclusive lower bound: AMBER is 60..79).
        val scores60: Map<KpiId, Int?> = gameplayWeights.mapValues { 60 }
        val r60 = aggregatePhase(Phase.GAMEPLAY, scores60, weights)
        assertEquals(60, r60?.score)
        assertEquals(Band.AMBER, r60?.band)

        // 59 → RED.
        val scores59: Map<KpiId, Int?> = gameplayWeights.mapValues { 59 }
        val r59 = aggregatePhase(Phase.GAMEPLAY, scores59, weights)
        assertEquals(59, r59?.score)
        assertEquals(Band.RED, r59?.band)
    }
}
