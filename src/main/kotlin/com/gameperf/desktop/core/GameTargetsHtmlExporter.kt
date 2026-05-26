package com.gameperf.desktop.core

import java.io.File

/**
 * Pure HTML emitter for the user-editable [GameTargetsCatalog].
 *
 * Generates a fully self-contained HTML document (inline CSS, no external
 * scripts, no remote images) listing every catalog entry as a row with the
 * 10 KPI columns documented on [GameTargets]. A prominent banner at the top
 * instructs the user how to save the document as a PDF via the browser's
 * built-in print dialog (Ctrl+P → Imprimir → Guardar como PDF) — this is
 * the project's preferred path because shipping a PDF library would violate
 * the "light, zero external deps" philosophy (engram #522).
 *
 * Mirrors the string-builder style of `ReportGenerator` but kept isolated
 * to avoid further growth on that already-large class (detekt thresholds
 * were already bumped twice in v5.1.0).
 *
 * Failure-mode contract:
 * - [export] NEVER throws; returns [Result.failure] on any IO error.
 * - [buildHtml] is pure; same input → same output, no IO.
 *
 * @since v5.2.0
 */
object GameTargetsHtmlExporter {

    /**
     * Persist the catalog as HTML to [outFile]. Creates the parent directory
     * if needed. Returns [Result.success] with the written file on success,
     * [Result.failure] on any IO error (parent not a directory, disk full,
     * permission denied, etc.). Never throws.
     */
    fun export(catalog: GameTargetsCatalog, outFile: File): Result<File> = runCatching {
        outFile.parentFile?.mkdirs()
        require(outFile.parentFile?.isDirectory == true) {
            "Parent path is not a directory: ${outFile.parentFile}"
        }
        outFile.writeText(buildHtml(catalog), Charsets.UTF_8)
        outFile
    }

    /**
     * Build the self-contained HTML document. Pure function — no IO, no
     * environment lookups. Rows are sorted alphabetically by package name
     * for stable diffs across exports.
     */
    internal fun buildHtml(catalog: GameTargetsCatalog): String {
        val sorted = catalog.targets.entries.sortedBy { it.key }
        val rows = if (sorted.isEmpty()) {
            """<tr><td colspan="12" class="empty">No hay objetivos definidos. """ +
                """Edítalos desde la app (botón Editar objetivos).</td></tr>"""
        } else {
            sorted.joinToString("\n") { (pkg, t) -> renderRow(pkg, t) }
        }

        val gameWord = if (catalog.targets.size == 1) "juego" else "juegos"

        return """<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<title>Objetivos por juego — GamePerf</title>
<style>
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0f172a;color:#e2e8f0;padding:24px;line-height:1.5}
h1{color:#22c55e;margin-top:0}
.banner{background:#1e293b;border-left:4px solid #22c55e;padding:16px 20px;border-radius:6px;margin-bottom:24px;color:#cbd5e1}
.banner strong{color:#22c55e}
table{width:100%;border-collapse:collapse;background:#1e293b;border-radius:8px;overflow:hidden}
th,td{padding:10px 12px;text-align:left;border-bottom:1px solid #334155;font-size:13px}
th{background:#334155;color:#f1f5f9;font-weight:600;position:sticky;top:0}
td.pkg{font-family:'Consolas','Monaco',monospace;color:#7dd3fc;font-size:12px}
td.notes{color:#94a3b8;font-size:12px;font-style:italic;max-width:200px}
td.empty{text-align:center;color:#64748b;padding:32px;font-style:italic}
tr:hover{background:#293548}
footer{margin-top:24px;color:#64748b;font-size:12px;text-align:center}
@media print{
  body{background:white;color:black}
  h1{color:#15803d}
  .banner{display:none}
  table{background:white}
  th{background:#e5e7eb;color:black}
  td.pkg{color:#0284c7}
  tr:hover{background:transparent}
}
</style>
</head>
<body>
<div class="banner">
<strong>📄 Cómo guardar a PDF.</strong>
<span>Para guardar como PDF, abre este archivo en tu navegador y pulsa Ctrl+P (Imprimir → Guardar como PDF).</span>
</div>
<h1>Objetivos por juego</h1>
<p>Catálogo de objetivos de rendimiento configurados en <code>~/GamePerf Reports/game-targets.json</code>. Total: ${catalog.targets.size} $gameWord.</p>
<table>
<thead>
<tr>
<th>Package</th>
<th>Nombre</th>
<th>FPS medio</th>
<th>FPS p1</th>
<th>Frame time (ms)</th>
<th>Temp piel (°C)</th>
<th>Temp CPU (°C)</th>
<th>RAM pico (MB)</th>
<th>CPU medio (%)</th>
<th>FPower (mW/f)</th>
<th>Drenaje bat (%)</th>
<th>Notas</th>
</tr>
</thead>
<tbody>
$rows
</tbody>
</table>
<footer>Generado por GamePerf · Para editar los objetivos abre el editor en la pantalla principal de la app.</footer>
</body>
</html>"""
    }

    private fun renderRow(pkg: String, t: GameTargets): String {
        val displayName = t.displayName ?: "—"
        val avgFps = t.targetAvgFps?.toString() ?: "—"
        val p1Fps = t.targetP1Fps?.toString() ?: "—"
        val frameMs = t.maxAvgFrameTimeMs?.let { "%.1f".format(it) } ?: "—"
        val tempSkin = t.maxTempSkinC?.let { "%.1f".format(it) } ?: "—"
        val tempCpu = t.maxTempCpuC?.let { "%.1f".format(it) } ?: "—"
        val ram = t.maxPeakRamMb?.toString() ?: "—"
        val cpu = t.maxAvgCpuPct?.toString() ?: "—"
        val fpower = t.maxFPowerMwFrame?.let { "%.1f".format(it) } ?: "—"
        val drain = t.maxBatteryDrainPct?.toString() ?: "—"
        val notes = t.notes ?: ""
        return """
            <tr>
                <td class="pkg">${esc(pkg)}</td>
                <td>${esc(displayName)}</td>
                <td>$avgFps</td>
                <td>$p1Fps</td>
                <td>$frameMs</td>
                <td>$tempSkin</td>
                <td>$tempCpu</td>
                <td>$ram</td>
                <td>$cpu</td>
                <td>$fpower</td>
                <td>$drain</td>
                <td class="notes">${esc(notes)}</td>
            </tr>""".trimIndent()
    }

    /** Escape HTML special chars (& < > " ) to prevent injection via user-supplied strings. */
    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
