package com.gameperf.desktop.report

import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * T5.1 — Backward-compat guard for the shareable-html-report Block F new
 * KPI params on [ReportGenerator.generate].
 *
 * The new optional params (`kpiReport`, `kpiInternalEnabled`, `kpiTier`) all
 * default to a no-op state. When the caller does NOT pass them, the
 * generator MUST emit HTML that is byte-equivalent to passing the explicit
 * defaults — i.e. NO new sections, NO new CSS, NO new data URLs.
 *
 * This protects legacy callers (UI, pre-v4.6 `.gameperf` re-renders, the 6
 * existing report tests) from any visible change.
 *
 * Strategy: strip the per-invocation nondeterministic bits (sessionId, the
 * exact second-resolution timestamp), then assert string equality between
 * the two generated reports.
 *
 * @since v4.6 (shareable-html-report Block F)
 */
class KpiSectionsOffByDefaultTest {

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

    private fun generateLegacy(): String {
        val path = ReportGenerator.generate(
            pkg = "com.example.legacy",
            info = device,
            grade = 'B',
            score = 75,
            duration = 30,
            fpsHistory = listOf(60, 60, 60),
            memHistory = listOf(400L, 410L, 420L),
            nativeHistory = listOf(200L, 205L, 210L),
            javaHistory = listOf(100L, 102L, 104L),
            cpuHistory = listOf(40, 45, 50),
            tempCpuHistory = listOf(40.0, 42.0, 45.0),
            tempGpuHistory = listOf(35.0, 37.0, 39.0),
            tempSkinHistory = emptyList(),
            allFrameTimes = listOf(16.0, 17.0, 18.0),
            avgFps = 60, minFps = 58, maxFps = 62,
            p1 = 55, p5 = 56, p50 = 60, p90 = 61, p99 = 62,
            avgFrameTime = 16.7, p99FrameTime = 18.0,
            peakMem = 420L, avgCpu = 45, maxCpu = 50,
            maxTempCpu = 45.0, maxTempGpu = 39.0,
            batteryStart = 90, batteryEnd = 85,
            frameDrops = 2, jank = 1, stutter = 0,
            problems = emptyList(), isWifi = true,
        )
        return File(path).readText(Charsets.UTF_8)
    }

    private fun generateExplicitDefaults(): String {
        val path = ReportGenerator.generate(
            pkg = "com.example.legacy",
            info = device,
            grade = 'B',
            score = 75,
            duration = 30,
            fpsHistory = listOf(60, 60, 60),
            memHistory = listOf(400L, 410L, 420L),
            nativeHistory = listOf(200L, 205L, 210L),
            javaHistory = listOf(100L, 102L, 104L),
            cpuHistory = listOf(40, 45, 50),
            tempCpuHistory = listOf(40.0, 42.0, 45.0),
            tempGpuHistory = listOf(35.0, 37.0, 39.0),
            tempSkinHistory = emptyList(),
            allFrameTimes = listOf(16.0, 17.0, 18.0),
            avgFps = 60, minFps = 58, maxFps = 62,
            p1 = 55, p5 = 56, p50 = 60, p90 = 61, p99 = 62,
            avgFrameTime = 16.7, p99FrameTime = 18.0,
            peakMem = 420L, avgCpu = 45, maxCpu = 50,
            maxTempCpu = 45.0, maxTempGpu = 39.0,
            batteryStart = 90, batteryEnd = 85,
            frameDrops = 2, jank = 1, stutter = 0,
            problems = emptyList(), isWifi = true,
            kpiReport = null,
            kpiInternalEnabled = false,
            kpiTier = null,
        )
        return File(path).readText(Charsets.UTF_8)
    }

    /**
     * Strip per-invocation nondeterministic bits: 8-char hex session id,
     * the date stamp in the header, the ISO timestamp inside the JSON
     * payload, and the seconds in the footer. Everything else MUST be
     * byte-identical between the two invocations.
     */
    private fun normalize(html: String): String =
        html
            // Session ID: 8 hex chars, appears 3+ times (header pill, footer, JSON)
            .replace(Regex("[0-9A-F]{8}"), "SESSIONID")
            // Footer "dd/MM/yyyy 'a las' HH:mm:ss" (most specific, match first)
            .replace(Regex("""\d{2}/\d{2}/\d{4} a las \d{2}:\d{2}:\d{2}"""), "DATEFOOTER")
            // Header date "dd/MM/yyyy HH:mm" (no seconds)
            .replace(Regex("""\d{2}/\d{2}/\d{4} \d{2}:\d{2}"""), "DATE")
            // ISO date "yyyy-MM-ddTHH:mm:ss"
            .replace(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}"""), "DATEISO")

    @Test
    fun `legacy call without KPI params is byte-equivalent to explicit defaults`() {
        val legacy = normalize(generateLegacy())
        val explicit = normalize(generateExplicitDefaults())
        assertEquals(
            legacy.length,
            explicit.length,
            "legacy and explicit-defaults reports differ in length after normalization",
        )
        assertEquals(legacy, explicit, "legacy and explicit-defaults reports must be byte-identical after normalization")
    }

    @Test
    fun `legacy call emits no new KPI section ids`() {
        val html = generateLegacy()
        assertFalse("""id="sec-kpi-scoring"""" in html, "sec-kpi-scoring must NOT appear when flag off")
        assertFalse("""id="sec-vitals-banner"""" in html, "sec-vitals-banner must NOT appear when flag off")
        assertFalse("""id="sec-phase-breakdown"""" in html, "sec-phase-breakdown must NOT appear when flag off")
        assertFalse("""id="sec-caveats"""" in html, "sec-caveats must NOT appear when flag off")
        assertFalse("data:text/csv;base64," in html, "CSV data URL must NOT appear when flag off")
        assertFalse("data:application/json;base64," in html, "JSON data URL must NOT appear when flag off")
        assertFalse(".kpi-band-green" in html, "kpi CSS must NOT be inlined when flag off")
    }

    @Test
    fun `explicit-defaults call emits no new KPI section ids`() {
        val html = generateExplicitDefaults()
        assertFalse("""id="sec-kpi-scoring"""" in html)
        assertFalse("""id="sec-vitals-banner"""" in html)
        assertFalse("data:text/csv;base64," in html)
        assertFalse(".kpi-band-green" in html)
    }
}
