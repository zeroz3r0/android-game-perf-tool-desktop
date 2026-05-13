package com.gameperf.desktop.core.kpi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 2 — `KpiCatalog` invariants (single source of truth).
 *
 * Spec coverage: `sdd/kpi-scoring-framework/spec` — Requirement: KPI Catalog
 * Single Source of Truth. Asserts ≥23 entries, distinct ids, all three
 * device tiers (TOP/MID/LOW) present per KPI, non-empty citation, lookup
 * helpers `byId` and `forCategory` behave.
 *
 * Pure-Kotlin tests — no I/O, no mocks (CLAUDE.md convention).
 */
class KpiCatalogTest {

    @Test
    fun `ALL exposes at least twenty three KPIs per spec`() {
        assertTrue(
            KpiCatalog.ALL.size >= 23,
            "expected ≥23 KPIs in catalog per spec, got ${KpiCatalog.ALL.size}",
        )
    }

    @Test
    fun `KpiId entries appear at most once in ALL`() {
        val ids = KpiCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate KpiId entries in catalog")
    }

    @Test
    fun `every catalog entry has TOP MID LOW thresholds`() {
        val required = setOf(DeviceTier.TOP, DeviceTier.MID, DeviceTier.LOW)
        KpiCatalog.ALL.forEach { kpi ->
            assertEquals(
                required,
                kpi.thresholds.keys,
                "${kpi.id} is missing per-tier thresholds (got ${kpi.thresholds.keys})",
            )
        }
    }

    @Test
    fun `every catalog entry has non-empty unit and citation`() {
        KpiCatalog.ALL.forEach { kpi ->
            assertTrue(kpi.unit.isNotBlank(), "${kpi.id} has blank unit")
            assertTrue(
                kpi.sourceCitation.isNotBlank(),
                "${kpi.id} has blank sourceCitation (anchor required for doc-anchor test)",
            )
        }
    }

    @Test
    fun `byId returns the matching entry`() {
        val fpsAvg = KpiCatalog.byId(KpiId.FPS_AVG)
        assertNotNull(fpsAvg)
        assertEquals(KpiId.FPS_AVG, fpsAvg.id)
        assertEquals(Category.Smoothness, fpsAvg.category)
        assertEquals(Direction.HIGHER_IS_BETTER, fpsAvg.direction)
    }

    @Test
    fun `byId throws or returns null for missing entry is impossible because ALL covers every KpiId`() {
        // Every enum entry must be present so `byId` is total.
        KpiId.values().forEach { id ->
            assertNotNull(
                KpiCatalog.ALL.find { it.id == id },
                "catalog is missing KpiId.${id.name} — must list every enum entry",
            )
        }
    }

    @Test
    fun `cold start KPI matches Android Vitals five second slow threshold floor`() {
        val coldStart = KpiCatalog.byId(KpiId.COLD_START_MS)
        // docs §3.1 — cold start ≥ 5s is "slow" (Vitals bad threshold) → floor = 5000ms.
        // direction is LOWER_IS_BETTER because cold start is a duration we want minimized.
        assertEquals(Direction.LOWER_IS_BETTER, coldStart.direction)
        val midFloor = coldStart.thresholds[DeviceTier.MID]?.floor
        assertEquals(
            5000.0,
            midFloor,
            "cold start MID floor MUST anchor on Vitals 5s slow threshold (docs §3.1)",
        )
    }

    @Test
    fun `slow frames KPI floor anchors on Android Vitals fifty percent threshold`() {
        val slow = KpiCatalog.byId(KpiId.SLOW_FRAMES)
        // docs §3.1 — "Excessive slow frames" → >50% of frames had render time >16 ms.
        // Direction is LOWER_IS_BETTER, floor = 50.0 (%).
        assertEquals(Direction.LOWER_IS_BETTER, slow.direction)
        val midFloor = slow.thresholds[DeviceTier.MID]?.floor
        assertEquals(
            50.0,
            midFloor,
            "slow-frames MID floor MUST anchor on Vitals 50% bad threshold (docs §3.1)",
        )
    }

    @Test
    fun `FPower KPI matches PerfDog fifty and sixty five mW thresholds`() {
        val fp = KpiCatalog.byId(KpiId.FPOWER)
        // docs §3.6 — <50 mW/frame excellent, 50-65 acceptable, >65 investigate.
        // direction LOWER_IS_BETTER, MID target=50, MID floor=65.
        assertEquals(Direction.LOWER_IS_BETTER, fp.direction)
        val mid = fp.thresholds[DeviceTier.MID]
        assertEquals(50.0, mid?.target, "FPower target MUST anchor on PerfDog <50 mW (docs §3.6)")
        assertEquals(65.0, mid?.floor, "FPower floor MUST anchor on PerfDog >65 mW (docs §3.6)")
    }

    @Test
    fun `forCategory filters by category`() {
        val smoothness = KpiCatalog.forCategory(Category.Smoothness)
        assertTrue(
            smoothness.isNotEmpty(),
            "Smoothness category should contain FPS / frame-time KPIs",
        )
        assertTrue(
            smoothness.all { it.category == Category.Smoothness },
            "forCategory returned KPIs from other categories",
        )
        // sanity: FPS_AVG is a smoothness KPI per design Category enum mapping
        assertTrue(smoothness.any { it.id == KpiId.FPS_AVG })
    }

    @Test
    fun `all five categories are represented in the catalog`() {
        val present = KpiCatalog.ALL.map { it.category }.toSet()
        assertEquals(
            setOf(
                Category.Smoothness,
                Category.Resource,
                Category.Thermal,
                Category.Stability,
                Category.Responsiveness,
            ),
            present,
            "catalog must contain at least one KPI per category (design D6)",
        )
    }

    @Test
    fun `higher is better thresholds have target greater than floor`() {
        KpiCatalog.ALL
            .filter { it.direction == Direction.HIGHER_IS_BETTER }
            .forEach { kpi ->
                kpi.thresholds.forEach { (tier, t) ->
                    assertTrue(
                        t.target > t.floor,
                        "${kpi.id}[$tier] HIGHER_IS_BETTER must have target>${t.target} > floor=${t.floor}",
                    )
                }
            }
    }

    @Test
    fun `lower is better thresholds have floor greater than target`() {
        KpiCatalog.ALL
            .filter { it.direction == Direction.LOWER_IS_BETTER }
            .forEach { kpi ->
                kpi.thresholds.forEach { (tier, t) ->
                    assertTrue(
                        t.floor > t.target,
                        "${kpi.id}[$tier] LOWER_IS_BETTER must have floor=${t.floor} > target=${t.target}",
                    )
                }
            }
    }
}
