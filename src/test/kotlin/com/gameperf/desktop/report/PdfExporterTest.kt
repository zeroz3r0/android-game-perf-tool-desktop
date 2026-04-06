package com.gameperf.desktop.report

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests for [PdfExporter]. These exercise the real Playwright + Chromium
 * pipeline and are therefore opt-in only: they download (~180 MB) and run a real
 * headless browser. CI does not run them by default.
 *
 * To enable: `RUN_PLAYWRIGHT_TESTS=true ./gradlew test`
 *
 * The opt-in is implemented as a runtime check at the start of each test (instead of
 * JUnit5's `@EnabledIfEnvironmentVariable`) because this project uses kotlin.test +
 * the JUnit 4 platform under the hood, which does not ship that annotation.
 */
class PdfExporterTest {

    private lateinit var workDir: File

    private val playwrightEnabled: Boolean
        get() = System.getenv("RUN_PLAYWRIGHT_TESTS") == "true"

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("pdfexporter-test-").toFile()
    }

    @AfterTest
    fun tearDown() {
        // Best-effort recursive cleanup. Browser may still be alive across tests
        // (PlaywrightManager is a singleton); shutdown is intentionally NOT called
        // here so subsequent tests reuse the same Browser instance.
        workDir.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    @Test
    fun `exportHtmlToPdf produces non empty pdf`() {
        if (!playwrightEnabled) {
            println("Skipping PdfExporterTest.exportHtmlToPdf_producesNonEmptyPdf " +
                "(set RUN_PLAYWRIGHT_TESTS=true to enable)")
            return
        }

        // Minimal HTML with a canvas + Chart.js inline. We do not need to verify
        // the chart visually; we just need to confirm a non-empty A4 PDF is produced.
        val html = File(workDir, "minimal.html")
        html.writeText("""
            <!doctype html>
            <html><head><meta charset="utf-8"><title>Test</title>
            <style>body{background:#222;color:#eee;font-family:sans-serif;padding:20px}</style>
            </head><body>
            <h1>Hello PDF</h1>
            <p>This is a minimal report used by PdfExporterTest.</p>
            </body></html>
        """.trimIndent())

        val targetPdf = File(workDir, "out.pdf")

        PdfExporter.exportHtmlToPdf(html.absolutePath, targetPdf)

        assertTrue(targetPdf.exists(), "PDF file should exist")
        assertTrue(targetPdf.length() > 1024, "PDF should be larger than 1 KB (was ${targetPdf.length()} bytes)")

        // Magic header check: every PDF starts with %PDF-
        val firstBytes = targetPdf.inputStream().use { it.readNBytes(5) }
        val header = String(firstBytes, Charsets.US_ASCII)
        assertTrue(header == "%PDF-", "PDF magic header expected, got '$header'")
    }

    @Test
    fun `exportHtmlToPdf throws on missing html`() {
        if (!playwrightEnabled) {
            println("Skipping PdfExporterTest.exportHtmlToPdf_throwsOnMissingHtml " +
                "(set RUN_PLAYWRIGHT_TESTS=true to enable)")
            return
        }

        val ghost = File(workDir, "does_not_exist.html").absolutePath
        val target = File(workDir, "ghost.pdf")

        assertFailsWith(PdfExporter.PdfExportException::class) {
            PdfExporter.exportHtmlToPdf(ghost, target)
        }
    }
}
