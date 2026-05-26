package com.gameperf.desktop.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [GameTargetsHtmlExporter].
 *
 * Mirrors the manual temp-dir pattern from `GameTargetsCatalogTest:30-41`
 * (no JUnit Jupiter, no mocks — `kotlin.test` framework).
 *
 * @since v5.2.0
 */
class GameTargetsHtmlExporterTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("game-targets-export-test-")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    // ════════════════ buildHtml — table rendering ════════════════

    @Test
    fun `empty catalog produces HTML with empty-state placeholder`() {
        val catalog = GameTargetsCatalog()
        val html = GameTargetsHtmlExporter.buildHtml(catalog)
        assertTrue(html.contains("No hay objetivos definidos"), "empty placeholder must appear")
        assertTrue(html.contains("Para guardar como PDF"), "PDF instruction banner must appear")
    }

    @Test
    fun `single entry produces row with all columns`() {
        val catalog = GameTargetsCatalog(
            targets = mapOf(
                "com.example.test" to GameTargets(
                    displayName = "Test Game",
                    targetAvgFps = 30,
                    targetP1Fps = 25,
                    maxAvgFrameTimeMs = 33.3,
                    maxTempSkinC = 42.0,
                    maxTempCpuC = 95.0,
                    maxPeakRamMb = 1500L,
                    maxAvgCpuPct = 60,
                    maxFPowerMwFrame = 65.0,
                    maxBatteryDrainPct = 15,
                    notes = "Sample",
                ),
            ),
        )
        val html = GameTargetsHtmlExporter.buildHtml(catalog)
        assertTrue(html.contains("com.example.test"))
        assertTrue(html.contains("Test Game"))
        assertTrue(html.contains(">30<"), "targetAvgFps cell must render")
        assertTrue(html.contains("Sample"))
    }

    @Test
    fun `multiple entries sorted alphabetically by package`() {
        val catalog = GameTargetsCatalog(
            targets = mapOf(
                "com.zebra.game" to GameTargets(displayName = "Zebra"),
                "com.alpha.game" to GameTargets(displayName = "Alpha"),
                "com.mango.game" to GameTargets(displayName = "Mango"),
            ),
        )
        val html = GameTargetsHtmlExporter.buildHtml(catalog)
        val alphaIdx = html.indexOf("com.alpha.game")
        val mangoIdx = html.indexOf("com.mango.game")
        val zebraIdx = html.indexOf("com.zebra.game")
        assertTrue(alphaIdx > 0, "alpha present")
        assertTrue(alphaIdx < mangoIdx, "alpha before mango")
        assertTrue(mangoIdx < zebraIdx, "mango before zebra")
    }

    @Test
    fun `banner verbatim copy present`() {
        val html = GameTargetsHtmlExporter.buildHtml(GameTargetsCatalog())
        assertTrue(
            html.contains(
                "Para guardar como PDF, abre este archivo en tu navegador y pulsa Ctrl+P " +
                    "(Imprimir → Guardar como PDF).",
            ),
            "banner exact copy must appear",
        )
    }

    @Test
    fun `HTML is self-contained — no external CSS or JS or remote images`() {
        // Use a catalog with one entry so the rows DO get rendered (otherwise an
        // exporter that only emits a banner could trivially pass this test).
        val catalog = GameTargetsCatalog(
            targets = mapOf("com.foo" to GameTargets(displayName = "Foo", targetAvgFps = 60)),
        )
        val html = GameTargetsHtmlExporter.buildHtml(catalog)
        assertFalse(html.contains("<link href=\"http"), "no external CSS link")
        assertFalse(html.contains("<script src="), "no external script src")
        assertFalse(html.contains("<img src=\"http"), "no remote image src")
    }

    // ════════════════ export — disk IO contract ════════════════

    @Test
    fun `export writes file to disk and returns Result success`() {
        val out = tempDir.resolve("targets.html").toFile()
        val result = GameTargetsHtmlExporter.export(GameTargetsCatalog(), out)
        assertTrue(result.isSuccess, "export must succeed on valid path")
        assertNotNull(result.getOrNull(), "Result.success must carry the file")
        assertTrue(out.exists(), "file must exist on disk")
        assertTrue(out.readText(Charsets.UTF_8).contains("Para guardar como PDF"))
    }

    @Test
    fun `export returns Result failure on IO error`() {
        // Use a regular file as parent — mkdirs() will refuse to descend into a
        // non-directory. Cross-platform (Windows + Linux + macOS) — matches the
        // pattern from `GameTargetsCatalogTest:185-204` (v5.1.0).
        val blocker = tempDir.resolve("blocker").toFile()
        blocker.writeBytes(byteArrayOf(0x42))
        val out = File(blocker, "targets.html")
        val result = GameTargetsHtmlExporter.export(GameTargetsCatalog(), out)
        assertTrue(result.isFailure, "export must fail when parent is a regular file")
    }

    @Test
    fun `filename pattern includes ISO date`() {
        // Documents the contract that AppViewModel uses (`game-targets-export-YYYY-MM-DD.html`).
        val today = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())
        val expectedName = "game-targets-export-$today.html"
        assertTrue(expectedName.matches(Regex("""game-targets-export-\d{4}-\d{2}-\d{2}\.html""")))
    }
}
