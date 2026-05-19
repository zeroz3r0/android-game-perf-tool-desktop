package com.gameperf.desktop.core.sharing

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ReportSharer].
 *
 * Focus is on the **pure** [ReportSharer.buildClipboardText] function — the
 * one with deterministic output that we can pin behaviour on. The AWT side
 * effects (system clipboard, `Desktop.open`) are not exercised here: they
 * are headless-hostile and would make the suite flaky on CI / locked
 * desktops. Test coverage of the failure-path mapping
 * ([ReportShareResult.Failure] for `REPORT_NOT_FOUND`) lives in
 * [shareLocally_returns_REPORT_NOT_FOUND_failure_when_file_missing].
 */
class ReportSharerTest {

    private lateinit var tmpDir: File
    private lateinit var reportFile: File

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("gameperf-sharer-test-").toFile()
        reportFile = File(tmpDir, "informe_com_example_game_Pixel_7a_2026-05-19_1451.html")
        reportFile.writeText("<html><body>fake report</body></html>", Charsets.UTF_8)
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ═══════ buildClipboardText — happy path ═══════

    @Test
    fun `buildClipboardText includes session name, package, device and file name`() {
        val text = ReportSharer.buildClipboardText(
            reportFile = reportFile,
            sessionName = "Boss fight nivel 12",
            packageName = "com.vivastudios.tower.battle",
            deviceName = "Google Pixel 7a",
        )

        assertTrue(text.contains("Boss fight nivel 12"), "title line should include session name")
        assertTrue(text.contains("com.vivastudios.tower.battle"), "package line should include pkg")
        assertTrue(text.contains("Google Pixel 7a"), "device line should include device")
        assertTrue(text.contains(reportFile.name), "file line should include report name")
        assertTrue(text.contains("autocontenido"), "should explain HTML is self-contained")
    }

    @Test
    fun `buildClipboardText includes parent folder path`() {
        val text = ReportSharer.buildClipboardText(
            reportFile = reportFile,
            sessionName = "any",
            packageName = "any",
            deviceName = "any",
        )
        assertTrue(
            text.contains(reportFile.parentFile.absolutePath),
            "folder line must include the absolute parent path so the recipient can find it",
        )
    }

    // ═══════ buildClipboardText — edge cases ═══════

    @Test
    fun `buildClipboardText falls back to package name when session name is blank`() {
        val text = ReportSharer.buildClipboardText(
            reportFile = reportFile,
            sessionName = "",
            packageName = "com.vivastudios.tower.battle",
            deviceName = "Pixel 7a",
        )
        assertTrue(
            text.lineSequence().first().contains("com.vivastudios.tower.battle"),
            "first line should use package as title when session name is empty",
        )
    }

    @Test
    fun `buildClipboardText renders fallback placeholders when package and device are blank`() {
        val text = ReportSharer.buildClipboardText(
            reportFile = reportFile,
            sessionName = "X",
            packageName = "",
            deviceName = "",
        )
        assertTrue(text.contains("Paquete: (desconocido)"), "must render placeholder, not bare colon")
        assertTrue(text.contains("Dispositivo: (desconocido)"), "must render placeholder, not bare colon")
    }

    @Test
    fun `buildClipboardText is deterministic for same inputs`() {
        val a = ReportSharer.buildClipboardText(reportFile, "S", "P", "D")
        val b = ReportSharer.buildClipboardText(reportFile, "S", "P", "D")
        assertEquals(a, b, "pure function must return identical text for identical inputs")
    }

    @Test
    fun `buildClipboardText pastes legibly with no broken control characters`() {
        val text = ReportSharer.buildClipboardText(reportFile, "Session", "pkg", "Device")
        assertFalse(text.contains('\t'), "no tabs — they paste inconsistently across chat clients")
        assertFalse(text.contains("\r\n"), "no CRLF — keep LF-only for consistency")
        // Sanity: text is non-empty and contains at least 5 lines (title, pkg, device, file, folder).
        assertTrue(text.lines().size >= 5, "expected at least 5 lines, got ${text.lines().size}")
    }

    // ═══════ shareLocally — failure paths (no AWT here) ═══════

    @Test
    fun `shareLocally returns REPORT_NOT_FOUND failure when file missing`() {
        val ghost = File(tmpDir, "does-not-exist.html")
        assertFalse(ghost.exists(), "precondition: ghost file must not exist")

        val result = ReportSharer.shareLocally(
            reportFile = ghost,
            sessionName = "x",
            packageName = "x",
            deviceName = "x",
        )

        val failure = result as? ReportShareResult.Failure
        assertNotNull(failure, "expected Failure result, got $result")
        assertEquals(ReportShareResult.FailureReason.REPORT_NOT_FOUND, failure.reason)
        assertTrue(
            failure.userMessage.contains("informe HTML", ignoreCase = true),
            "user message should mention the report explicitly",
        )
    }
}
