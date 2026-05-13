package com.gameperf.desktop.core.report.kpi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T2.1 — Caveats section ([renderCaveats]).
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: Caveats
 * Section (3 Spanish-tuteo-formal paragraphs explaining GPU sysfs status,
 * 1Hz sampling, device-tier defaults).
 *
 * Pure: deterministic, no I/O.
 */
class KpiCaveatsTest {

    @Test
    fun `output wraps content in section with id sec-caveats`() {
        val html = renderCaveats("MID")
        assertTrue(
            "id=\"sec-caveats\"" in html,
            "expected section anchor id=\"sec-caveats\"; got:\n$html",
        )
        assertTrue("<section" in html && "</section>" in html, "expected a complete <section> tag")
    }

    @Test
    fun `output mentions GPU sampling caveat`() {
        val html = renderCaveats("MID")
        assertTrue(
            "GPU" in html,
            "caveats must reference GPU sampling status (Sprint 1 paused); got:\n$html",
        )
    }

    @Test
    fun `output mentions 1Hz sampling cadence`() {
        val html = renderCaveats("MID")
        assertTrue(
            "1Hz" in html || "1 Hz" in html,
            "caveats must reference the 1Hz sampling cadence; got:\n$html",
        )
    }

    @Test
    fun `blank tier is rendered as MID default literal`() {
        val html = renderCaveats("")
        assertTrue(
            "MID (default)" in html,
            "blank tier must render as 'MID (default)' label; got:\n$html",
        )
    }

    @Test
    fun `non-blank tier is rendered verbatim`() {
        val html = renderCaveats("TOP")
        assertTrue("TOP" in html, "expected TOP literal in output; got:\n$html")
        assertFalse(
            "MID (default)" in html,
            "should not append default suffix when tier is explicit; got:\n$html",
        )
    }
}
