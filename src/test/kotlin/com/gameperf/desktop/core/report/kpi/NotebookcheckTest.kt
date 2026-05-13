package com.gameperf.desktop.core.report.kpi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T0.3 — Notebookcheck annotation formatter.
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: Notebookcheck
 * Annotation Formatter (`Ø<avg> (<min>-<max>)` per docs §7.3).
 *
 * Pure: deterministic, no I/O, US-locale decimal separator (project
 * convention from `Formatting.fmtUS`).
 */
class NotebookcheckTest {

    @Test
    fun `integer format emits Ø60 (59-61)`() {
        assertEquals("Ø60 (59-61)", Notebookcheck.format(60, 59, 61))
    }

    @Test
    fun `decimal format with 1-digit precision emits US decimal point`() {
        assertEquals(
            "Ø14.3 (12.1-16.7)",
            Notebookcheck.format(14.3, 12.1, 16.7, decimals = 1),
        )
    }

    @Test
    fun `decimal format with 2-digit precision`() {
        // Avoids locale-specific comma even on Spanish/Argentine JVM defaults
        // (mirrors v4.2.4 mojibake-class fix: never trust default locale).
        assertEquals(
            "Ø42.50 (38.25-45.75)",
            Notebookcheck.format(42.5, 38.25, 45.75, decimals = 2),
        )
    }

    @Test
    fun `integer format rounds Doubles correctly`() {
        // decimals=0 with floating-point inputs should still emit a clean integer.
        assertEquals("Ø60 (59-61)", Notebookcheck.format(60.0, 59.0, 61.0))
    }

    @Test
    fun `result starts with Unicode capital O with stroke (U+00D8)`() {
        val out = Notebookcheck.format(60, 59, 61)
        assertTrue(out.startsWith("\u00D8"), "expected Ø (U+00D8) prefix, got code=${out[0].code}")
    }
}
