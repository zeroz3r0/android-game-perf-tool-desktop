package com.gameperf.desktop.core.kpi

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 2 — `PhaseWeights` invariants.
 *
 * Spec coverage: `sdd/kpi-scoring-framework/spec` — Requirement: Phase
 * Aggregation + Session Aggregation. Weights MUST sum to 1.0 ±ε per phase
 * (so weighted-average produces a value in [0,100] without renormalization
 * in the happy path) AND `phaseWeights` MUST sum to 1.0 across all phases.
 *
 * Pure-Kotlin (no I/O).
 */
class PhaseWeightsTest {

    private val epsilon = 0.001

    @Test
    fun `DEFAULT exposes kpiWeightsForPhase covering every Phase enum value`() {
        val covered = PhaseWeights.DEFAULT.kpiWeightsForPhase.keys
        assertEquals(
            Phase.values().toSet(),
            covered,
            "kpiWeightsForPhase must define a weight map for every Phase",
        )
    }

    @Test
    fun `each phase KPI weight map sums to one within epsilon`() {
        PhaseWeights.DEFAULT.kpiWeightsForPhase.forEach { (phase, weights) ->
            val sum = weights.values.sum()
            assertTrue(
                abs(sum - 1.0) < epsilon,
                "weights for $phase sum to $sum (expected 1.0 ±$epsilon)",
            )
        }
    }

    @Test
    fun `every per-phase weight is strictly positive`() {
        PhaseWeights.DEFAULT.kpiWeightsForPhase.forEach { (phase, weights) ->
            weights.forEach { (kpi, w) ->
                assertTrue(
                    w > 0.0,
                    "$phase weight for $kpi must be > 0.0 (got $w) — use omission to mean 'irrelevant'",
                )
            }
        }
    }

    @Test
    fun `phaseWeights covers every Phase enum value`() {
        assertEquals(
            Phase.values().toSet(),
            PhaseWeights.DEFAULT.phaseWeights.keys,
            "phaseWeights must define a weight for every Phase",
        )
    }

    @Test
    fun `phaseWeights sums to one within epsilon`() {
        val sum = PhaseWeights.DEFAULT.phaseWeights.values.sum()
        assertTrue(
            abs(sum - 1.0) < epsilon,
            "phaseWeights sum to $sum (expected 1.0 ±$epsilon)",
        )
    }

    @Test
    fun `every phase weight is strictly positive`() {
        PhaseWeights.DEFAULT.phaseWeights.forEach { (phase, weight) ->
            assertTrue(
                weight > 0.0,
                "$phase weight must be > 0.0 (got $weight) — use omission to mean 'irrelevant'",
            )
        }
    }

    @Test
    fun `gameplay phase carries the highest individual phase weight`() {
        // Per docs §5.2 — gameplay is the most representative phase of the
        // session (other phases are bursts). This guards against accidental
        // weight inversions in future edits.
        val gameplay = PhaseWeights.DEFAULT.phaseWeights[Phase.GAMEPLAY]
        assertNotNull(gameplay)
        PhaseWeights.DEFAULT.phaseWeights
            .filterKeys { it != Phase.GAMEPLAY }
            .forEach { (otherPhase, otherWeight) ->
                assertTrue(
                    gameplay >= otherWeight,
                    "Gameplay weight ($gameplay) must be ≥ $otherPhase weight ($otherWeight)",
                )
            }
    }

    @Test
    fun `gameplay phase weights reference smoothness KPIs critically`() {
        // docs §5.2: Gameplay critical KPIs include FPS_AVG, FPS_P1,
        // FPS_STABILITY, TEMP_AVG/MAX, THROTTLING_EVENTS, FPOWER.
        val gameplayWeights = PhaseWeights.DEFAULT.kpiWeightsForPhase[Phase.GAMEPLAY]
        assertNotNull(gameplayWeights)
        assertTrue(
            KpiId.FPS_AVG in gameplayWeights.keys,
            "Gameplay phase MUST score FPS_AVG (docs §5.2 critical KPI)",
        )
        assertTrue(
            KpiId.FPS_P1 in gameplayWeights.keys,
            "Gameplay phase MUST score FPS_P1 (docs §5.2 critical KPI)",
        )
        assertTrue(
            KpiId.FPS_STABILITY in gameplayWeights.keys,
            "Gameplay phase MUST score FPS_STABILITY (docs §5.2 critical KPI)",
        )
    }

    @Test
    fun `app startup phase scores cold start and TTID`() {
        // docs §5.2 — APP_STARTUP critical KPIs.
        val startupWeights = PhaseWeights.DEFAULT.kpiWeightsForPhase[Phase.APP_STARTUP]
        assertNotNull(startupWeights)
        assertTrue(KpiId.COLD_START_MS in startupWeights.keys)
        assertTrue(KpiId.TTID in startupWeights.keys)
    }
}
