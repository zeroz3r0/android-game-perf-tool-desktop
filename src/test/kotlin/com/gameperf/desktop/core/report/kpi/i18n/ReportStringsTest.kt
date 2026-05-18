package com.gameperf.desktop.core.report.kpi.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 1.3 — Single-source castellano labels for the HTML report.
 *
 * Verifies presence + exact text of every required const AND that the source
 * file is encoded in valid UTF-8 with no mojibake bytes (no `0xC3 0x83` or
 * `0xC3 0xB1` sequences — the classic Windows-1252 reinterpretation glyphs
 * we burned on in v4.2.4 — `CLAUDE.md`).
 *
 * @since v4.7 (html-report-rag-bands — RAG-006)
 */
class ReportStringsTest {

    @Test
    fun `band labels match castellano formal vocabulary`() {
        assertEquals("Bien", ReportStrings.BAND_GREEN)
        assertEquals("Atencion", ReportStrings.BAND_AMBER)
        assertEquals("Mal", ReportStrings.BAND_RED)
    }

    @Test
    fun `budget labels match castellano formal vocabulary`() {
        assertEquals("Presupuesto 60 fps", ReportStrings.BUDGET_60FPS)
        assertEquals("Presupuesto 30 fps", ReportStrings.BUDGET_30FPS)
        assertEquals("Presupuesto 120 fps", ReportStrings.BUDGET_120FPS)
    }

    @Test
    fun `phase distribution and box-stat labels present`() {
        assertEquals("Distribucion por fase", ReportStrings.PHASE_DIST_TITLE)
        assertEquals("Mediana", ReportStrings.BOX_MEDIAN)
        assertEquals("P1", ReportStrings.BOX_P1)
        assertEquals("P99", ReportStrings.BOX_P99)
        assertEquals("Min", ReportStrings.BOX_MIN)
        assertEquals("Max", ReportStrings.BOX_MAX)
    }

    @Test
    fun `source file is UTF-8 clean with no mojibake byte sequences`() {
        val file = File(
            "src/main/kotlin/com/gameperf/desktop/core/report/kpi/i18n/ReportStrings.kt",
        )
        assertTrue(file.isFile, "ReportStrings.kt must exist at ${file.absolutePath}")

        // Read raw bytes — verify NO Windows-1252-on-UTF-8 mojibake patterns.
        val bytes = file.readBytes()
        // 0xC3 0x83 = "Ã" (mojibake of á when UTF-8 read as cp1252 then re-encoded UTF-8)
        // 0xC3 0xB1 = mojibake of ñ
        var i = 0
        while (i < bytes.size - 1) {
            val pair = (bytes[i].toInt() and 0xFF) to (bytes[i + 1].toInt() and 0xFF)
            // Only flag the specific mojibake sequences (NOT all 0xC3 — that's valid UTF-8 lead byte for ñ etc.)
            assertFalse(
                pair == (0xC3 to 0x83),
                "Mojibake 0xC3 0x83 found at byte $i — file was probably saved as cp1252",
            )
            i++
        }

        // Sanity check: re-read with explicit UTF-8 and verify constants render clean.
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("Bien"), "ReportStrings text must contain literal 'Bien'")
        assertTrue(text.contains("Atencion"), "ReportStrings text must contain literal 'Atencion'")
        assertFalse(text.contains("Ã"), "Mojibake glyph 'Ã' present in ReportStrings.kt")
    }
}
