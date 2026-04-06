package com.gameperf.desktop.report

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Converts a local HTML report (file://) to a PDF by spawning a system-installed
 * Chromium-based browser in headless mode via [ProcessBuilder] and the
 * `--print-to-pdf` flag.
 *
 * Replaces the previous Playwright-based implementation. The public API
 * (`PdfExporter.exportHtmlToPdf`, `PdfExportException`) is intentionally
 * unchanged so callers in the ViewModel remain untouched.
 *
 * Blocking by design: callers must wrap invocations in `withContext(Dispatchers.IO)`.
 * Every failure mode is surfaced through [PdfExportException] with a user-friendly
 * Spanish message. Stderr from Chrome is intentionally NOT parsed — Chrome headless
 * on macOS prints known noise (`CVDisplayLinkCreateWithCGDisplay`, `task_policy_set
 * TASK_CATEGORY_POLICY`, `Trying to load the allocator multiple times`) that is
 * non-fatal. Success is determined solely by `exitValue == 0` plus a non-empty
 * output file.
 */
object PdfExporter {

    /** User-facing wrapper for every failure in the export pipeline. */
    class PdfExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val TIMEOUT_SECONDS: Long = 60
    private const val VIRTUAL_TIME_BUDGET_MS: Long = 10_000

    /**
     * Converts [htmlPath] (an absolute path to a local HTML file) into a PDF written
     * at [targetPdf]. Uses an installed Chromium-based browser (Chrome, Chromium,
     * Edge, Brave, Vivaldi or Arc) detected via [BrowserDetector]. Throws
     * [PdfExportException] on any failure.
     */
    fun exportHtmlToPdf(htmlPath: String, targetPdf: File) {
        val source = File(htmlPath)
        if (!source.exists()) {
            throw PdfExportException("Informe HTML no encontrado: $htmlPath")
        }

        val browser = BrowserDetector.detect()
            ?: throw PdfExportException(
                "No se encontró Google Chrome, Microsoft Edge, Brave ni Chromium instalado. " +
                    "Instalá uno (por ejemplo Chrome) para exportar a PDF."
            )

        targetPdf.parentFile?.mkdirs()

        // Fresh user-data-dir avoids "profile in use" conflicts when the user already
        // has Chrome open. Random suffix prevents collisions across concurrent exports.
        val userDataDir = File(
            System.getProperty("java.io.tmpdir"),
            "gameperf-chrome-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}"
        ).apply { mkdirs() }

        // Flags validated experimentally with Chrome 146+. --virtual-time-budget gives
        // Chart.js a chance to draw before the print snapshot. The ?print=1 query string
        // signals the report HTML to use the print color palette (dark text, light fills,
        // no animations) so charts render legibly on white paper.
        val command = listOf(
            browser.executable.absolutePath,
            "--headless",
            "--disable-gpu",
            "--no-sandbox",
            "--no-pdf-header-footer",
            "--hide-scrollbars",
            "--virtual-time-budget=$VIRTUAL_TIME_BUDGET_MS",
            "--run-all-compositor-stages-before-draw",
            "--user-data-dir=${userDataDir.absolutePath}",
            "--print-to-pdf=${targetPdf.absolutePath}",
            "file://${source.absolutePath}?print=1",
        )

        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (t: Throwable) {
            runCatching { userDataDir.deleteRecursively() }
            throw PdfExportException(
                "No se pudo lanzar ${browser.name}: ${t.message}",
                t
            )
        }

        try {
            val completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                throw PdfExportException(
                    "Timeout: el navegador tardó más de $TIMEOUT_SECONDS segundos en generar el PDF"
                )
            }

            val exit = process.exitValue()
            if (exit != 0) {
                throw PdfExportException("${browser.name} falló generando el PDF (exit code $exit)")
            }
            if (!targetPdf.exists() || targetPdf.length() == 0L) {
                throw PdfExportException(
                    "${browser.name} terminó sin errores pero el PDF está vacío o no se creó"
                )
            }
        } finally {
            runCatching { userDataDir.deleteRecursively() }
        }
    }
}
