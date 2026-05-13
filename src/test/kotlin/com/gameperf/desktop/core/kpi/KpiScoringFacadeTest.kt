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
 * Phase 6 — `KpiScoringFacade` invariants (KPI-008).
 *
 * Spec coverage: orchestrates Extract → Score → Aggregate → Compare. Returns
 * `null` when the feature flag is OFF (design D5). Returns a non-null
 * [KpiScoreReport] when flag is ON and at least one phase has data.
 *
 * Pure-ish: the facade reads the JVM system property; we set/clear per test.
 */
class KpiScoringFacadeTest {

    @BeforeTest
    fun clearSysprop() {
        System.clearProperty(FeatureFlags.INTERNAL_FLAG_KEY)
    }

    @AfterTest
    fun clearSyspropAfter() {
        System.clearProperty(FeatureFlags.INTERNAL_FLAG_KEY)
    }

    /** Build a minimal `KpiInput` with one GAMEPLAY phase, all KPIs scoring well. */
    private fun gameplayHappyInput(deviceModel: String = "Pixel 8 Pro"): KpiInput {
        // Use raw values that the LOWER_IS_BETTER / HIGHER_IS_BETTER scoring
        // will all hit ≥ 80 on TOP-tier thresholds. We pick "at target" for
        // every gameplay KPI so the weighted average is exactly 100.
        val rawByPhase = mapOf(
            Phase.GAMEPLAY to PhaseWeights.DEFAULT.kpiWeightsForPhase[Phase.GAMEPLAY]!!
                .keys
                .associateWith { kpiId ->
                    // For each KPI, set the raw value to its TOP-tier target →
                    // LinearScoring returns 100 regardless of direction.
                    val kpi = KpiCatalog.byId(kpiId)
                    kpi.thresholds[DeviceTier.TOP]!!.target
                },
        )
        return KpiInput(deviceModel = deviceModel, rawByPhase = rawByPhase)
    }

    @Test
    fun `facade returns null when flag OFF by default`() {
        val report = KpiScoringFacade.compute(gameplayHappyInput(), settings = Settings())
        assertNull(report)
    }

    @Test
    fun `facade returns KpiScoreReport when flag ON via sysprop`() {
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "true")
        val report = KpiScoringFacade.compute(gameplayHappyInput(), settings = Settings())
        assertNotNull(report)
        assertEquals(100, report.sessionScore)
        assertEquals(Band.GREEN, report.sessionBand)
    }

    @Test
    fun `facade returns KpiScoreReport when flag ON via settings`() {
        val report = KpiScoringFacade.compute(
            gameplayHappyInput(),
            settings = Settings(kpiScoringInternalEnabled = true),
        )
        assertNotNull(report)
        assertEquals(100, report.sessionScore)
    }

    @Test
    fun `facade orchestrates phase, session, and category aggregation`() {
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "true")
        val report = KpiScoringFacade.compute(gameplayHappyInput(), settings = Settings())
        assertNotNull(report)
        // Phase produced
        assertEquals(1, report.phases.size)
        assertEquals(Phase.GAMEPLAY, report.phases.first().phase)
        assertEquals(100, report.phases.first().score)
        // Categories populated (facade wires aggregateCategories into the report)
        assertTrue(report.categories.isNotEmpty(), "facade must populate categories")
        // Every populated category is on GREEN since all KPIs scored 100
        report.categories.forEach { assertEquals(Band.GREEN, it.band) }
    }

    @Test
    fun `facade scores worse when raw values miss target`() {
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "true")
        // Pick FPS_AVG — HIGHER_IS_BETTER, weight 0.20 within GAMEPLAY. Send
        // the raw value at the floor → score = 0 for that KPI; the rest still
        // at target → 100. Weighted average → 100*0.80 + 0*0.20 = 80.
        val gameplay = PhaseWeights.DEFAULT.kpiWeightsForPhase[Phase.GAMEPLAY]!!
        val raw: Map<KpiId, Double> = gameplay.keys.associateWith { kpiId ->
            val kpi = KpiCatalog.byId(kpiId)
            if (kpiId == KpiId.FPS_AVG) kpi.thresholds[DeviceTier.TOP]!!.floor
            else kpi.thresholds[DeviceTier.TOP]!!.target
        }
        val input = KpiInput(deviceModel = "Pixel 8 Pro", rawByPhase = mapOf(Phase.GAMEPLAY to raw))
        val report = KpiScoringFacade.compute(input, settings = Settings())
        assertNotNull(report)
        // Session score must be < 100 since at least one KPI scored 0.
        assertTrue(report.sessionScore < 100, "expected degraded score, got ${report.sessionScore}")
    }

    @Test
    fun `facade resolves device tier when not provided via deviceModel`() {
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "true")
        // Use Galaxy Tab A8 → LOW tier per DeviceTierCatalog. The LOW tier
        // floor for COLD_START_MS is much more lenient than TOP, so a value
        // that would score 0 on TOP scores higher on LOW. We just assert the
        // facade picked the LOW tier path by checking it does NOT crash and
        // returns a report (tier integration is the key invariant).
        val gameplay = PhaseWeights.DEFAULT.kpiWeightsForPhase[Phase.GAMEPLAY]!!
        val raw: Map<KpiId, Double> = gameplay.keys.associateWith { kpiId ->
            KpiCatalog.byId(kpiId).thresholds[DeviceTier.LOW]!!.target
        }
        val input = KpiInput(deviceModel = "Galaxy Tab A8", rawByPhase = mapOf(Phase.GAMEPLAY to raw))
        val report = KpiScoringFacade.compute(input, settings = Settings())
        assertNotNull(report)
        // LOW-tier targets → score 100 because we hit each LOW target exactly.
        assertEquals(100, report.sessionScore)
    }

    @Test
    fun `facade honors explicit tier override over deviceModel resolution`() {
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "true")
        // Send LOW raw values but force TOP tier. Most LOW-tier targets are
        // worse than TOP-tier targets → scoring against TOP thresholds yields
        // a low score (this validates the tier override is wired through).
        val gameplay = PhaseWeights.DEFAULT.kpiWeightsForPhase[Phase.GAMEPLAY]!!
        val raw: Map<KpiId, Double> = gameplay.keys.associateWith { kpiId ->
            KpiCatalog.byId(kpiId).thresholds[DeviceTier.LOW]!!.target
        }
        val input = KpiInput(deviceModel = "Galaxy Tab A8", rawByPhase = mapOf(Phase.GAMEPLAY to raw))
        val reportTop = KpiScoringFacade.compute(input, tier = DeviceTier.TOP, settings = Settings())
        val reportLow = KpiScoringFacade.compute(input, tier = DeviceTier.LOW, settings = Settings())
        assertNotNull(reportTop)
        assertNotNull(reportLow)
        assertEquals(100, reportLow.sessionScore)
        assertTrue(
            reportTop.sessionScore <= reportLow.sessionScore,
            "TOP tier should score LOW-values no better than LOW tier — got top=${reportTop.sessionScore}, low=${reportLow.sessionScore}",
        )
    }
}
