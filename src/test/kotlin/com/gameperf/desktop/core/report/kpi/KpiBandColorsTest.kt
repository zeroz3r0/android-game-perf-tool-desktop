package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Band
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T0.2 — Single-source band-to-color mapping.
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: Single
 * Source for Band Colors. Mirrors `KpiCatalog` / `SdkSignatureCatalog`
 * anti-duplication rule (CLAUDE.md v4.2.13, v4.4.0).
 *
 * Pure: deterministic, no I/O.
 */
class KpiBandColorsTest {

    private val hexPattern = Regex("^#[0-9a-fA-F]{6}$")

    // ─── forBand(): three distinct non-empty #RRGGBB strings ───────────────

    @Test
    fun `forBand returns a non-empty hex string for GREEN`() {
        val hex = KpiBandColors.forBand(Band.GREEN)
        assertTrue(hex.isNotBlank(), "GREEN hex must not be blank")
        assertTrue(hexPattern.matches(hex), "GREEN hex must match #RRGGBB; got '$hex'")
    }

    @Test
    fun `forBand returns a non-empty hex string for AMBER`() {
        val hex = KpiBandColors.forBand(Band.AMBER)
        assertTrue(hex.isNotBlank(), "AMBER hex must not be blank")
        assertTrue(hexPattern.matches(hex), "AMBER hex must match #RRGGBB; got '$hex'")
    }

    @Test
    fun `forBand returns a non-empty hex string for RED`() {
        val hex = KpiBandColors.forBand(Band.RED)
        assertTrue(hex.isNotBlank(), "RED hex must not be blank")
        assertTrue(hexPattern.matches(hex), "RED hex must match #RRGGBB; got '$hex'")
    }

    @Test
    fun `forBand returns three distinct values for the three bands`() {
        val g = KpiBandColors.forBand(Band.GREEN)
        val a = KpiBandColors.forBand(Band.AMBER)
        val r = KpiBandColors.forBand(Band.RED)
        assertEquals(3, setOf(g, a, r).size, "expected 3 distinct band colors; got G=$g A=$a R=$r")
    }

    // ─── cssClassFor(): stable class names referenced by KPI_CSS ───────────

    @Test
    fun `cssClassFor GREEN returns kpi-band-green`() {
        assertEquals("kpi-band-green", KpiBandColors.cssClassFor(Band.GREEN))
    }

    @Test
    fun `cssClassFor AMBER returns kpi-band-amber`() {
        assertEquals("kpi-band-amber", KpiBandColors.cssClassFor(Band.AMBER))
    }

    @Test
    fun `cssClassFor RED returns kpi-band-red`() {
        assertEquals("kpi-band-red", KpiBandColors.cssClassFor(Band.RED))
    }
}
