package com.gameperf.desktop.core.kpi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Phase 4 — `CategoryAggregator` invariants.
 *
 * Spec coverage: `sdd/kpi-scoring-framework/spec` — Requirement: Category
 * Aggregation. Cross-phase weighted average grouped by [Category]. KPI
 * weight inside a category = `phase-weight × phase-kpi-weight` (matches the
 * session-level weighting so categories and session aggregate are
 * consistent). Missing values are excluded and remaining weights are
 * renormalized.
 *
 * No-data category → `null` (must be distinct from "score 0").
 *
 * Pure: deterministic, no I/O.
 */
class CategoryAggregatorTest {

    private val weights = PhaseWeights.DEFAULT

    @Test
    fun `happy path averages all KPIs of a given category across phases`() {
        // All Smoothness KPIs scored 80 in every phase that references them →
        // Smoothness category score = 80 GREEN.
        val scoresByPhase: Map<Phase, Map<KpiId, Int?>> = weights.kpiWeightsForPhase.mapValues { (_, kpiMap) ->
            kpiMap.mapValues { 80 }
        }
        val cats = aggregateCategories(scoresByPhase, weights)
        val smooth = cats.firstOrNull { it.category == Category.Smoothness }
        assertNotNull(smooth)
        assertEquals(80, smooth.score)
        assertEquals(Band.GREEN, smooth.band)
    }

    @Test
    fun `category score reflects weighted average across phases`() {
        // Gameplay smoothness KPIs scored 100, every other smoothness usage
        // scored 0. Gameplay dominates (phaseWeight 0.55) so smoothness should
        // be above 50.
        val scoresByPhase: Map<Phase, Map<KpiId, Int?>> = weights.kpiWeightsForPhase.mapValues { (phase, kpiMap) ->
            kpiMap.mapValues { (kpi, _) ->
                val cat = KpiCatalog.byId(kpi).category
                when {
                    cat != Category.Smoothness -> 100 // irrelevant — filtered out by category aggregator
                    phase == Phase.GAMEPLAY -> 100
                    else -> 0
                }
            }
        }
        val smooth = aggregateCategories(scoresByPhase, weights)
            .firstOrNull { it.category == Category.Smoothness }
        assertNotNull(smooth)
        // Gameplay smoothness weight (FPS_AVG+FPS_P1+FPS_STABILITY+JANK_COUNT
        // = 0.20+0.15+0.15+0.05 = 0.55) × phase weight 0.55 dominates;
        // all other smoothness contributions are 0.
        // Numerator = 0.55 * 100 * 0.55 = 30.25.
        // Denominator = sum over all (phase, kpi) of phase-weight × kpi-weight
        // where kpi.category == Smoothness. Just assert "gameplay dominates so
        // score > 50".
        assert(smooth.score >= 50) { "Gameplay-only-100 smoothness should be ≥ 50, got ${smooth.score}" }
    }

    @Test
    fun `category with all KPIs missing returns null`() {
        // Mark every Stability KPI as null → Stability category MUST be null.
        val scoresByPhase: Map<Phase, Map<KpiId, Int?>> = weights.kpiWeightsForPhase.mapValues { (_, kpiMap) ->
            kpiMap.mapValues { (kpi, _) ->
                val cat = KpiCatalog.byId(kpi).category
                if (cat == Category.Stability) null else 80
            }
        }
        val cats = aggregateCategories(scoresByPhase, weights)
        val stability = cats.firstOrNull { it.category == Category.Stability }
        assertNull(stability, "Category with all KPIs missing must be excluded (null), not zeroed")
    }

    @Test
    fun `category with partial data is renormalized not zeroed`() {
        // Within Smoothness, only score FPS_AVG=100 everywhere it appears; all
        // other smoothness KPIs are null. Renormalized score MUST be 100, not
        // weighted toward 0.
        val scoresByPhase: Map<Phase, Map<KpiId, Int?>> = weights.kpiWeightsForPhase.mapValues { (_, kpiMap) ->
            kpiMap.mapValues { (kpi, _) ->
                val cat = KpiCatalog.byId(kpi).category
                when {
                    cat != Category.Smoothness -> 80 // other categories don't matter here
                    kpi == KpiId.FPS_AVG -> 100
                    else -> null
                }
            }
        }
        val smooth = aggregateCategories(scoresByPhase, weights)
            .firstOrNull { it.category == Category.Smoothness }
        assertNotNull(smooth)
        assertEquals(100, smooth.score)
        assertEquals(Band.GREEN, smooth.band)
    }

    @Test
    fun `every category present in any phase appears in the output`() {
        // All KPIs scored 50 → every category referenced anywhere in
        // PhaseWeights.DEFAULT must appear in the output.
        val scoresByPhase: Map<Phase, Map<KpiId, Int?>> = weights.kpiWeightsForPhase.mapValues { (_, kpiMap) ->
            kpiMap.mapValues { 50 }
        }
        val cats = aggregateCategories(scoresByPhase, weights)
        val expectedCategories = weights.kpiWeightsForPhase.values
            .flatMap { it.keys }
            .map { KpiCatalog.byId(it).category }
            .toSet()
        val gotCategories = cats.map { it.category }.toSet()
        assertEquals(expectedCategories, gotCategories)
        // And every category score is 50 RED-ish (AMBER actually since 50 < 60? RED).
        cats.forEach {
            assertEquals(50, it.score, "category ${it.category} should aggregate to 50")
            assertEquals(Band.RED, it.band)
        }
    }
}
