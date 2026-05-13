package com.gameperf.desktop.core.kpi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Phase 4 — `SessionAggregator` invariants.
 *
 * Spec coverage: `sdd/kpi-scoring-framework/spec` — Requirement: Session
 * Aggregation. Combines per-phase scores using [PhaseWeights.phaseWeights].
 * Missing phases (null PhaseScore) are excluded and remaining phase weights
 * are renormalized.
 *
 * All-phases-missing → null session score (NOT zero).
 *
 * Pure: deterministic, no I/O.
 */
class SessionAggregatorTest {

    private val weights = PhaseWeights.DEFAULT

    private fun ps(phase: Phase, score: Int): PhaseScore =
        PhaseScore(phase = phase, score = score, band = bandOf(score), kpiScores = emptyList())

    @Test
    fun `all phases present uses canonical phase weights`() {
        // Every phase at 80 → session = 80 GREEN.
        val phaseScores = Phase.values().map { ps(it, 80) }
        val result = aggregateSession(phaseScores, weights)
        assertNotNull(result)
        assertEquals(80, result.sessionScore)
        assertEquals(Band.GREEN, result.sessionBand)
        // Phases echoed back in output.
        assertEquals(phaseScores.size, result.phases.size)
    }

    @Test
    fun `weighted average reflects different per-phase scores`() {
        // Gameplay (0.55 weight) = 100, everyone else = 0.
        // Session = 0.55 * 100 = 55 → RED.
        val phaseScores = Phase.values().map {
            ps(it, if (it == Phase.GAMEPLAY) 100 else 0)
        }
        val result = aggregateSession(phaseScores, weights)
        assertNotNull(result)
        assertEquals(55, result.sessionScore)
        assertEquals(Band.RED, result.sessionBand)
    }

    @Test
    fun `missing phase is excluded and remaining weights are renormalized`() {
        // Only gameplay present, all = 100. Renormalized → 100, not 55.
        val phaseScores = listOf(ps(Phase.GAMEPLAY, 100))
        val result = aggregateSession(phaseScores, weights)
        assertNotNull(result)
        assertEquals(100, result.sessionScore)
        assertEquals(Band.GREEN, result.sessionBand)
    }

    @Test
    fun `renormalization with mixed missing phases`() {
        // Gameplay (0.55) = 80, Startup (0.10) = 40, all others missing.
        // Sum = 0.55*80 + 0.10*40 = 44 + 4 = 48. Denominator = 0.65.
        // Renormalized = 48 / 0.65 ≈ 73.85 → 74.
        val phaseScores = listOf(
            ps(Phase.GAMEPLAY, 80),
            ps(Phase.APP_STARTUP, 40),
        )
        val result = aggregateSession(phaseScores, weights)
        assertNotNull(result)
        assertEquals(74, result.sessionScore)
        assertEquals(Band.AMBER, result.sessionBand)
    }

    @Test
    fun `empty phase list returns null`() {
        val result = aggregateSession(emptyList(), weights)
        assertNull(result)
    }

    @Test
    fun `phase not in weights table is ignored`() {
        // No phase should be missing from weights — but guard against future
        // additions. Single gameplay phase still gives 100.
        val phaseScores = listOf(ps(Phase.GAMEPLAY, 100))
        val result = aggregateSession(phaseScores, weights)
        assertNotNull(result)
        assertEquals(100, result.sessionScore)
    }
}
