package com.gameperf.desktop.report

import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v5.2.1 introduced `ReportGenerator.generate(captureWarning = ...)` so the HTML
 * report explains WHY the video section may be missing (screenrecord rejected,
 * device offline, ffmpeg missing, segments corrupt, zero segments pulled).
 *
 * v5.2.3 hardens the contract after Judgment Day surfaced a divergence: the
 * render uses `isNullOrBlank()` but the upstream guard was `== null`. These
 * tests pin BOTH ends of the contract:
 *
 *  - null → no banner (legacy / no warning path).
 *  - blank / whitespace → no banner (the render's `isNullOrBlank()` branch).
 *  - non-blank → banner present AND the text shows up in the HTML.
 *  - HTML-unsafe chars → escaped via `esc()` (no raw `<script>` leaks).
 */
class ReportGeneratorCaptureWarningTest {

    private val device = DeviceInfo(
        model = "TestDevice",
        manufacturer = "TestMaker",
        cpu = "TestCPU",
        gpu = "TestGPU",
        ram = "8.0 GB",
        cores = 8,
        osVersion = "33",
        resolution = "1080x2400",
        platform = DevicePlatform.ANDROID,
    )

    private fun generate(captureWarning: String?): String {
        val path = ReportGenerator.generate(
            pkg = "com.example.capturewarning.test",
            info = device,
            grade = 'B', score = 75, duration = 60,
            fpsHistory = listOf(60, 60, 60),
            memHistory = listOf(400L, 410L, 420L),
            nativeHistory = listOf(200L, 205L, 210L),
            javaHistory = listOf(100L, 102L, 104L),
            cpuHistory = listOf(40, 45, 50),
            tempCpuHistory = listOf(40.0, 42.0, 45.0),
            tempGpuHistory = listOf(35.0, 37.0, 39.0),
            tempSkinHistory = emptyList(),
            allFrameTimes = listOf(16.0, 16.5, 17.0),
            avgFps = 60, minFps = 59, maxFps = 60,
            p1 = 59, p5 = 59, p50 = 60, p90 = 60, p99 = 60,
            avgFrameTime = 16.5, p99FrameTime = 17.0,
            peakMem = 420L, avgCpu = 45, maxCpu = 50,
            maxTempCpu = 45.0, maxTempGpu = 39.0,
            batteryStart = 90, batteryEnd = 88,
            frameDrops = 0, jank = 0, stutter = 0,
            problems = emptyList(),
            isWifi = false,
            captureWarning = captureWarning,
        )
        return File(path).readText()
    }

    /**
     * Marker that anchors the capture warning banner SECTION instance, not the
     * CSS class definition. The class name itself appears in the embedded
     * `<style>` block (~5 selectors) regardless of whether a banner is rendered,
     * so we look for the opening `<section>` tag that wraps an actual banner.
     */
    private val bannerMarker = "<section class=\"capture-warning-banner\">"

    @Test
    fun `captureWarning null - no banner rendered`() {
        val html = generate(captureWarning = null)
        assertFalse(
            html.contains(bannerMarker),
            "null captureWarning must NOT render the banner (legacy path)",
        )
    }

    @Test
    fun `captureWarning empty string - no banner rendered`() {
        val html = generate(captureWarning = "")
        assertFalse(
            html.contains(bannerMarker),
            "empty captureWarning must NOT render the banner (isNullOrBlank branch)",
        )
    }

    @Test
    fun `captureWarning whitespace only - no banner rendered`() {
        val html = generate(captureWarning = "   ")
        assertFalse(
            html.contains(bannerMarker),
            "whitespace-only captureWarning must NOT render the banner (isNullOrBlank branch)",
        )
    }

    @Test
    fun `captureWarning non-blank - banner rendered with text`() {
        val warning = "No hay segmentos de vídeo. Las métricas sí se han registrado correctamente."
        val html = generate(captureWarning = warning)
        assertTrue(
            html.contains(bannerMarker),
            "non-blank captureWarning must render the banner section",
        )
        assertTrue(
            html.contains(warning),
            "non-blank captureWarning text must appear in the rendered HTML",
        )
    }

    @Test
    fun `captureWarning with HTML chars - escaped via esc()`() {
        val raw = "<script>alert(1)</script> & \"broken\""
        val html = generate(captureWarning = raw)
        // Banner must still render — non-blank input.
        assertTrue(html.contains(bannerMarker), "banner must render for non-blank input")
        // Raw HTML payload MUST NOT appear verbatim (would be an XSS hole).
        assertFalse(
            html.contains("<script>alert(1)</script>"),
            "raw <script> payload must NOT appear in the HTML output (esc() must apply)",
        )
        // Escaped form MUST appear (proves esc() ran on this path).
        assertTrue(
            html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"),
            "esc()-encoded form must appear in the HTML output",
        )
        assertTrue(html.contains("&amp;"), "ampersand must be escaped")
        assertTrue(html.contains("&quot;"), "double quote must be escaped")
    }
}
