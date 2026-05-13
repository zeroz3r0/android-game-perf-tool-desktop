package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.ui.util.fmtUS

/**
 * CSV serializer for the `KpiScoreReport` download button.
 *
 * Schema (RFC 4180-style): `phase,kpi,raw_value,score,delta,band\n` header
 * followed by one row per `KpiScore` flattened across all phases.
 *
 * - `raw_value` and `delta` are formatted in US locale (`fmtUS`) so the
 *   decimal separator is always `.` (avoids European-locale CSV mojibake;
 *   project convention since v4.2.4).
 * - Null `rawValue` renders as an empty cell.
 * - Values containing `,`, `"`, `\r`, or `\n` are wrapped in double quotes
 *   and inner `"` doubled per RFC 4180. Well-known enum names contain none
 *   of these so the common case stays unquoted.
 *
 * Pure: deterministic, no I/O.
 *
 * @since v4.6 (shareable-html-report Block F)
 */
object KpiCsvSerializer {

    private const val HEADER: String = "phase,kpi,raw_value,score,delta,band"

    /** Serializes [report] to a CSV string. Always ends with `\n`. */
    fun toCsv(report: KpiScoreReport): String = buildString {
        append(HEADER)
        append('\n')
        for (phase in report.phases) {
            for (kpi in phase.kpiScores) {
                append(escape(phase.phase.name))
                append(',')
                append(escape(kpi.id.name))
                append(',')
                append(kpi.rawValue?.let { fmtUS("%s", it) } ?: "")
                append(',')
                append(kpi.score.toString())
                append(',')
                append(fmtUS("%s", kpi.delta))
                append(',')
                append(escape(kpi.band.name))
                append('\n')
            }
        }
    }

    /**
     * RFC 4180-style cell escaping. Wraps the value in double quotes and
     * doubles inner `"` when the cell contains a special character.
     */
    private fun escape(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        val doubled = value.replace("\"", "\"\"")
        return "\"$doubled\""
    }
}
