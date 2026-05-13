package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.ui.util.fmtUS

/**
 * Notebookcheck-style shorthand `Ø<avg> (<min>-<max>)` for FPS/CPU/temp/FPower
 * (`docs/competitive-analysis-and-kpis.md` §7.3).
 *
 * Always emits US decimal point regardless of host locale — mirrors `fmtUS`
 * (project convention since v4.2.4 mojibake fix). Pure: deterministic,
 * no I/O.
 *
 * @since v4.6 (shareable-html-report Block F)
 */
object Notebookcheck {

    /**
     * Returns the literal string `Ø<avg> (<min>-<max>)` formatted with
     * [decimals] fractional digits. The leading character is Unicode
     * `U+00D8` (LATIN CAPITAL LETTER O WITH STROKE), the convention used by
     * Notebookcheck reviews for "average".
     *
     * @param avg average value to format.
     * @param min minimum value to format.
     * @param max maximum value to format.
     * @param decimals number of fractional digits (default `0`, integer
     *   rendering). Inputs are formatted via [fmtUS] so the separator is
     *   always `.` regardless of locale.
     */
    fun format(avg: Number, min: Number, max: Number, decimals: Int = 0): String {
        val pattern = "%.${decimals}f"
        val a = fmtUS(pattern, avg.toDouble())
        val lo = fmtUS(pattern, min.toDouble())
        val hi = fmtUS(pattern, max.toDouble())
        return "\u00D8$a ($lo-$hi)"
    }
}
