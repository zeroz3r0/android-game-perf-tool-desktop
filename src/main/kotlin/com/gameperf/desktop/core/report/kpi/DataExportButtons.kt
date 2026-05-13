package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.KpiScoreReport
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Renders two `<a download>` anchors wrapped in a `kpi-export-buttons` div:
 *  - CSV (`text/csv`) via [KpiCsvSerializer.toCsv]
 *  - JSON (`application/json`) via `kotlinx-serialization`
 *
 * Both payloads are inlined as base64 `data:` URLs so the resulting HTML is
 * fully self-contained (no `<script>`, no `fetch`, no Blob). Works offline,
 * works when the report is opened from an email attachment.
 *
 * Tradeoff: base64 inflates payload size by ~33%. The size-budget test
 * (`KpiReportSizeTest`) verifies the full report stays under 5 MB for a
 * typical 60s session.
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: CSV + JSON
 * Download Buttons.
 *
 * Pure: deterministic, no I/O.
 *
 * @since v4.6 (shareable-html-report Block F)
 */
internal fun renderExportButtons(report: KpiScoreReport, pkg: String): String {
    val csv = KpiCsvSerializer.toCsv(report)
    val json = Json.encodeToString(KpiScoreReport.serializer(), report)
    val encoder = Base64.getEncoder()
    val csvB64 = encoder.encodeToString(csv.toByteArray(Charsets.UTF_8))
    val jsonB64 = encoder.encodeToString(json.toByteArray(Charsets.UTF_8))
    return buildString {
        append("<div class=\"kpi-export-buttons\">")
        append("<a class=\"kpi-export-btn kpi-export-csv\" ")
        append("download=\"kpi_$pkg.csv\" ")
        append("href=\"data:text/csv;base64,$csvB64\">Descargar CSV</a>")
        append("<a class=\"kpi-export-btn kpi-export-json\" ")
        append("download=\"kpi_$pkg.json\" ")
        append("href=\"data:application/json;base64,$jsonB64\">Descargar JSON</a>")
        append("</div>")
    }
}
