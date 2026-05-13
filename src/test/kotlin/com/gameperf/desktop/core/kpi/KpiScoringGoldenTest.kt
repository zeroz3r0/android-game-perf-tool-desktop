package com.gameperf.desktop.core.kpi

import com.gameperf.desktop.core.Settings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 7 — Golden fixtures + properties for `KpiScoringFacade` (KPI-008).
 *
 * Three synthetic gameplay sessions exercising the full pipeline end-to-end:
 *  - **good**  — every KPI at its TOP-tier target  → score 100, GREEN.
 *  - **mixed** — partial loss on top-weighted KPIs  → AMBER band (60..79).
 *  - **bad**   — most KPIs at floor                 → score < 50, RED.
 *
 * These fixtures lock the catalog × weights × scoring composition. If any
 * single layer drifts (catalog thresholds, phase weights, linear scoring
 * math, or aggregator semantics), the band assertion here flips and the
 * test fails — that is the whole point.
 *
 * Fixtures are written as in-test factories (NOT JSON files) so the test
 * stays self-contained and the catalog/weights references resolve at
 * compile time. If we ever need cross-language fixtures, JSON can be added
 * later.
 *
 * Also covers two facade-level properties:
 *  - Determinism: same input → identical [KpiScoreReport] across two runs.
 *  - Flag OFF → null regardless of input shape.
 *
 * @since v4.5 (kpi-scoring internal v1)
 */
class KpiScoringGoldenTest {

    @BeforeTest
    fun clearSysprop() {
        System.clearProperty(FeatureFlags.INTERNAL_FLAG_KEY)
    }

    @AfterTest
    fun clearSyspropAfter() {
        System.clearProperty(FeatureFlags.INTERNAL_FLAG_KEY)
    }

    /** TOP-tier target for [kpiId] — guaranteed score = 100. */
    private fun topTarget(kpiId: KpiId): Double =
        KpiCatalog.byId(kpiId).thresholds[DeviceTier.TOP]!!.target

    /** TOP-tier floor for [kpiId] — guaranteed score = 0. */
    private fun topFloor(kpiId: KpiId): Double =
        KpiCatalog.byId(kpiId).thresholds[DeviceTier.TOP]!!.floor

    /**
     * Builds a GAMEPLAY-only [KpiInput] where each KPI's raw value is chosen
     * via [pick]. `pick(kpiId)` returns either the target or the floor of
     * the TOP tier for that KPI, letting the fixture pick a per-KPI score
     * (100 or 0) and compose a known weighted average.
     */
    private fun gameplayFixture(pick: (KpiId) -> Double): KpiInput {
        val raw = PhaseWeights.DEFAULT.kpiWeightsForPhase[Phase.GAMEPLAY]!!
            .keys
            .associateWith { pick(it) }
        return KpiInput(deviceModel = "Pixel 8 Pro", rawByPhase = mapOf(Phase.GAMEPLAY to raw))
    }

    // ─────────────────────── Golden #1: good session ───────────────────────

    @Test
    fun `golden good session — all KPIs at target — scores 100 GREEN`() {
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "true")
        val report = KpiScoringFacade.compute(gameplayFixture { topTarget(it) })
        assertNotNull(report)
        assertEquals(100, report.sessionScore, "good fixture must score 100")
        assertEquals(Band.GREEN, report.sessionBand)
        assertTrue(report.sessionScore >= 85, "good fixture must be ≥85 GREEN, got ${report.sessionScore}")
        // Every populated category must be GREEN since each KPI scored 100.
        report.categories.forEach { assertEquals(Band.GREEN, it.band, "category ${it.category}") }
    }

    // ─────────────────────── Golden #2: mixed session ───────────────────────

    @Test
    fun `golden mixed session — top-weighted KPIs at floor — lands AMBER`() {
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "true")
        // Put FPS_AVG (0.20) and FPS_P1 (0.15) at floor → contribute 0 to the
        // weighted average; remaining 0.65 of weight contributes 100 each →
        // gameplay phase score = 65 (AMBER).
        val floorSet = setOf(KpiId.FPS_AVG, KpiId.FPS_P1)
        val report = KpiScoringFacade.compute(
            gameplayFixture { if (it in floorSet) topFloor(it) else topTarget(it) },
        )
        assertNotNull(report)
        assertEquals(65, report.sessionScore, "mixed fixture composes to exactly 65")
        assertEquals(Band.AMBER, report.sessionBand)
        assertTrue(
            report.sessionScore in 60..79,
            "mixed fixture must land in AMBER band, got ${report.sessionScore}",
        )
    }

    // ─────────────────────── Golden #3: bad session ───────────────────────

    @Test
    fun `golden bad session — most KPIs at floor — scores below 50 RED`() {
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "true")
        // Keep only TEMP_MAX (0.10) + JANK_COUNT (0.05) + CPU_AVG_NORMALIZED
        // (0.05) at target → 20% of weight = 20. The remaining 80% at floor →
        // gameplay phase score = 20 (RED).
        val targetSet = setOf(KpiId.TEMP_MAX, KpiId.JANK_COUNT, KpiId.CPU_AVG_NORMALIZED)
        val report = KpiScoringFacade.compute(
            gameplayFixture { if (it in targetSet) topTarget(it) else topFloor(it) },
        )
        assertNotNull(report)
        assertEquals(20, report.sessionScore, "bad fixture composes to exactly 20")
        assertEquals(Band.RED, report.sessionBand)
        assertTrue(report.sessionScore < 50, "bad fixture must be <50 RED, got ${report.sessionScore}")
    }

    // ─────────────────────── Property: determinism ───────────────────────

    @Test
    fun `property — facade is deterministic across repeated calls`() {
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "true")
        val input = gameplayFixture { topTarget(it) }
        val a = KpiScoringFacade.compute(input)
        val b = KpiScoringFacade.compute(input)
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a, b, "facade must be deterministic for identical input")
    }

    // ─────────────────────── Property: flag-OFF gate ───────────────────────

    @Test
    fun `property — flag OFF returns null regardless of input shape`() {
        // Default Settings has flag OFF and sysprop is cleared by @BeforeTest.
        val good = gameplayFixture { topTarget(it) }
        val mixed = gameplayFixture { if (it == KpiId.FPS_AVG) topFloor(it) else topTarget(it) }
        val bad = gameplayFixture { topFloor(it) }
        val empty = KpiInput(deviceModel = "Pixel 8 Pro", rawByPhase = emptyMap())
        listOf(good, mixed, bad, empty).forEach { input ->
            assertNull(
                KpiScoringFacade.compute(input, settings = Settings()),
                "flag OFF must return null for any input",
            )
        }
    }
}
