package com.gameperf.desktop.report

import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.options.WaitUntilState
import java.io.File

/**
 * Converts a local HTML report (file://) to a PDF using Playwright's headless Chromium.
 *
 * Blocking by design: callers must wrap invocations in `withContext(Dispatchers.IO)` when
 * called from a coroutine. Every failure mode is surfaced through [PdfExportException] with
 * a user-friendly message that can be rendered in the Compose UI.
 *
 * The Browser instance is shared across exports via [PlaywrightManager] — only the Page is
 * disposed after each run.
 */
object PdfExporter {

    /** User-facing wrapper for every failure in the export pipeline. */
    class PdfExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Converts [htmlPath] (an absolute path to a local HTML file) into a PDF written at
     * [targetPdf]. A4 format with `printBackground = true` so dark-theme backgrounds are
     * preserved. Throws [PdfExportException] on any failure.
     */
    fun exportHtmlToPdf(htmlPath: String, targetPdf: File) {
        val source = File(htmlPath)
        if (!source.exists()) {
            throw PdfExportException("Informe HTML no encontrado: $htmlPath")
        }

        val page: Page
        try {
            page = PlaywrightManager.newPage()
        } catch (e: PlaywrightException) {
            throw PdfExportException(
                "No se pudo preparar el motor PDF. Se necesita internet solo la primera vez " +
                    "para preparar el motor PDF. Tip: podés usar Imprimir -> Guardar como PDF " +
                    "desde el browser mientras tanto.",
                e
            )
        } catch (e: Throwable) {
            throw PdfExportException("Error inicializando Playwright: ${e.message}", e)
        }

        try {
            page.navigate(
                "file://${source.absolutePath}",
                Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE)
            )
            targetPdf.parentFile?.mkdirs()
            page.pdf(
                Page.PdfOptions()
                    .setPath(targetPdf.toPath())
                    .setFormat("A4")
                    .setPrintBackground(true)
            )
        } catch (e: PlaywrightException) {
            throw PdfExportException("Error generando PDF: ${e.message}", e)
        } finally {
            try { page.close() } catch (_: Throwable) {}
        }
    }
}
