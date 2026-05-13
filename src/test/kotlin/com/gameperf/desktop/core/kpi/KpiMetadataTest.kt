package com.gameperf.desktop.core.kpi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1 — Foundation. Asserts the data classes + enums from the design
 * `Interfaces / Contracts` block compile and behave as immutable value types.
 *
 * No I/O. Pure-Kotlin per CLAUDE.md "tests puros sin mocks".
 *
 * Coverage: sdd/kpi-scoring-framework/spec — Requirement: KPI Catalog Single
 * Source of Truth (enum shape, threshold tuple shape), Linear Scoring
 * (Direction sign), Comparison Engine (Band trichotomy).
 */
class KpiMetadataTest {

    // ═══════ enum exhaustiveness (KpiId ≥ 23 entries per spec §5.1) ═══════

    @Test
    fun `KpiId enum has at least twenty three entries`() {
        assertTrue(
            KpiId.values().size >= 23,
            "expected ≥23 KPI ids per docs/competitive-analysis-and-kpis.md §5.1, got ${KpiId.values().size}",
        )
    }

    @Test
    fun `KpiId entries are distinct`() {
        val ids = KpiId.values().toList()
        assertEquals(ids.size, ids.toSet().size, "duplicate KpiId enum entries")
    }

    @Test
    fun `Category enum exposes the five categories from spec §6_4`() {
        val expected = setOf(
            Category.Smoothness,
            Category.Resource,
            Category.Thermal,
            Category.Stability,
            Category.Responsiveness,
        )
        assertEquals(expected, Category.values().toSet())
    }

    @Test
    fun `Phase enum exposes the eight game phases from doc §4_1`() {
        val expected = setOf(
            Phase.APP_STARTUP,
            Phase.CINEMATIC,
            Phase.TUTORIAL,
            Phase.LEVEL_LOADING,
            Phase.SCREEN_NAV,
            Phase.INTERSTITIAL_AD,
            Phase.REWARDED_AD,
            Phase.GAMEPLAY,
        )
        assertEquals(expected, Phase.values().toSet())
    }

    @Test
    fun `DeviceTier enum exposes TOP MID LOW`() {
        val expected = setOf(DeviceTier.TOP, DeviceTier.MID, DeviceTier.LOW)
        assertEquals(expected, DeviceTier.values().toSet())
    }

    @Test
    fun `Direction enum exposes HIGHER and LOWER`() {
        val expected = setOf(Direction.HIGHER_IS_BETTER, Direction.LOWER_IS_BETTER)
        assertEquals(expected, Direction.values().toSet())
    }

    @Test
    fun `Band enum exposes GREEN AMBER RED`() {
        val expected = setOf(Band.GREEN, Band.AMBER, Band.RED)
        assertEquals(expected, Band.values().toSet())
    }

    // ═══════ value-type behaviour (data classes) ═══════

    @Test
    fun `Threshold holds target and floor as Double`() {
        val t = Threshold(target = 60.0, floor = 20.0)
        assertEquals(60.0, t.target)
        assertEquals(20.0, t.floor)
    }

    @Test
    fun `Threshold supports value equality`() {
        val a = Threshold(target = 60.0, floor = 20.0)
        val b = Threshold(target = 60.0, floor = 20.0)
        val c = Threshold(target = 60.0, floor = 30.0)
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun `Kpi data class carries id category direction thresholds and citation`() {
        val k = Kpi(
            id = KpiId.FPS_AVG,
            unit = "fps",
            category = Category.Smoothness,
            direction = Direction.HIGHER_IS_BETTER,
            thresholds = mapOf(
                DeviceTier.TOP to Threshold(60.0, 30.0),
                DeviceTier.MID to Threshold(45.0, 24.0),
                DeviceTier.LOW to Threshold(30.0, 20.0),
            ),
            sourceCitation = "§3.1 Android Vitals games (30 FPS bar / 20 FPS floor)",
        )
        assertEquals(KpiId.FPS_AVG, k.id)
        assertEquals(Category.Smoothness, k.category)
        assertEquals(Direction.HIGHER_IS_BETTER, k.direction)
        assertNotNull(k.thresholds[DeviceTier.TOP])
        assertNotNull(k.thresholds[DeviceTier.MID])
        assertNotNull(k.thresholds[DeviceTier.LOW])
        assertTrue(k.sourceCitation.isNotEmpty())
    }

    @Test
    fun `KpiScore wraps rawValue score delta band per design interface`() {
        val s = KpiScore(
            id = KpiId.FPS_AVG,
            phase = Phase.GAMEPLAY,
            rawValue = 55.0,
            score = 83,
            delta = -5.0,
            band = Band.GREEN,
        )
        assertEquals(83, s.score)
        assertEquals(Band.GREEN, s.band)
        assertEquals(55.0, s.rawValue)
    }

    @Test
    fun `KpiScore rawValue accepts null when data missing`() {
        val s = KpiScore(
            id = KpiId.GPU_AVG,
            phase = Phase.GAMEPLAY,
            rawValue = null,
            score = 0,
            delta = 0.0,
            band = Band.RED,
        )
        assertEquals(null, s.rawValue)
    }

    @Test
    fun `PhaseScore aggregates per-phase score band and kpi list`() {
        val kpi = KpiScore(
            id = KpiId.FPS_AVG, phase = Phase.GAMEPLAY,
            rawValue = 60.0, score = 100, delta = 0.0, band = Band.GREEN,
        )
        val p = PhaseScore(
            phase = Phase.GAMEPLAY,
            score = 100,
            band = Band.GREEN,
            kpiScores = listOf(kpi),
        )
        assertEquals(Phase.GAMEPLAY, p.phase)
        assertEquals(1, p.kpiScores.size)
    }

    @Test
    fun `CategoryScore holds category score and band`() {
        val c = CategoryScore(category = Category.Smoothness, score = 85, band = Band.GREEN)
        assertEquals(Category.Smoothness, c.category)
        assertEquals(85, c.score)
        assertEquals(Band.GREEN, c.band)
    }

    @Test
    fun `KpiScoreReport aggregates session phases categories and bands`() {
        val report = KpiScoreReport(
            sessionScore = 82,
            sessionBand = Band.GREEN,
            phases = emptyList(),
            categories = emptyList(),
        )
        assertEquals(82, report.sessionScore)
        assertEquals(Band.GREEN, report.sessionBand)
        assertTrue(report.phases.isEmpty())
        assertTrue(report.categories.isEmpty())
    }
}
