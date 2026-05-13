package com.gameperf.desktop.report

import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.model.FPowerDiagnostic
import com.gameperf.desktop.core.model.FPowerUnavailableReason
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v4.5.0 -- ReportGenerator HTML rendering tests for the `fpower-metric`
 * change. Covers spec FPW-009 (color bands + Spanish-tuteo-formal banner +
 * defaulted-args backward compat) and design ADR-5 (banner voice register).
 *
 * Mirrors the structure of [ReportThermalAvailabilityRenderingTest] per
 * design ADR-1 (mirror thermal architecture exactly).
 *
 * Property assertions:
 *  1. **Color bands (FPW-009)**: avg in `<50` carries `fpower-green`, in
 *     `50..<65` carries `fpower-amber`, `>=65` carries `fpower-red`.
 *     Boundary at `49.99 / 50.0 / 64.99 / 65.0`.
 *  2. **Unavailable + diagnostic (FPW-009, ADR-5)**: `fpowerAvailable=false`
 *     with a populated `FPowerDiagnostic` renders an "N/D" placeholder AND a
 *     Spanish-tuteo-formal banner that includes the raw paths tried + a
 *     reason-specific sentence.
 *  3. **Each [FPowerUnavailableReason]** maps to a distinct reason-specific
 *     keyword in the banner copy (BATTERY_PATH_MISSING, FPS_ZERO,
 *     IMPLAUSIBLE_VALUE, OEM_LOCKED, PERMISSION_DENIED, UNKNOWN).
 *  4. **Backward compat (FPW-012)**: calling `generate(...)` without any of
 *     the new fpower params produces HTML that contains NO `fpower-card`
 *     section (legacy fixture stays unchanged).
 *
 * Pure assertions on the generated HTML string (file written to
 * `~/GamePerf Reports/` for free inspection, but tests read the file
 * contents back to verify the markup).
 *
 * See `sdd/fpower-metric/spec` (FPW-009, FPW-012) and
 * `sdd/fpower-metric/design` (§10 + ADR-5) for the rendering contract.
 */
class ReportGeneratorFPowerTest {

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

    // Minimal valid baseline + opt-in fpower params. Defaults skip the fpower
    // section entirely so the backward-compat test exercises the same baseline.
    private fun generate(
        fpowerHistory: List<Double> = emptyList(),
        fpowerAvg: Double = 0.0,
        fpowerPeak: Double = 0.0,
        fpowerAvailable: Boolean = true,
        fpowerDiagnostic: FPowerDiagnostic? = null,
        passFPowerArgs: Boolean = true,
    ): String {
        val path = if (passFPowerArgs) {
            ReportGenerator.generate(
                pkg = "com.example.fpower.test",
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
                fpowerHistory = fpowerHistory,
                fpowerAvg = fpowerAvg,
                fpowerPeak = fpowerPeak,
                fpowerAvailable = fpowerAvailable,
                fpowerDiagnostic = fpowerDiagnostic,
            )
        } else {
            // Backward-compat path: NO fpower args at all (defaulted by generate()).
            ReportGenerator.generate(
                pkg = "com.example.fpower.legacy",
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
            )
        }
        return File(path).readText()
    }

    /**
     * Extracts the `<section id="sec-fpower" ...>` ... `</section>` substring
     * from the rendered HTML. Returns the empty string when no FPower
     * section is present. Used to anchor assertions against the SECTION
     * element so that the inline CSS rules (`.fpower-green {...}`, etc.)
     * don't false-match the negative assertions.
     */
    private fun fpowerSectionFragment(html: String): String {
        val start = html.indexOf("""<section id="sec-fpower"""")
        if (start < 0) return ""
        val end = html.indexOf("</section>", start)
        if (end < 0) return ""
        return html.substring(start, end + "</section>".length)
    }

    private fun hasFPowerSection(html: String): Boolean = fpowerSectionFragment(html).isNotEmpty()

    // ── Color bands (FPW-009) ───────────────────────────────────────────────

    @Test
    fun `FPW-009 avg=30 lands in fpower-green band`() {
        val html = generate(
            fpowerHistory = listOf(20.0, 30.0, 40.0),
            fpowerAvg = 30.0,
            fpowerPeak = 40.0,
        )
        val sec = fpowerSectionFragment(html)
        assertTrue(sec.isNotEmpty(), "FPower section must be rendered")
        assertTrue(
            sec.contains("fpower-green"),
            "avg=30 section must carry fpower-green class; got: $sec",
        )
        assertFalse(
            sec.contains("fpower-amber") || sec.contains("fpower-red"),
            "avg=30 section must NOT carry amber or red band classes; got: $sec",
        )
    }

    @Test
    fun `FPW-009 avg=55 lands in fpower-amber band`() {
        val html = generate(
            fpowerHistory = listOf(50.0, 55.0, 60.0),
            fpowerAvg = 55.0,
            fpowerPeak = 60.0,
        )
        val sec = fpowerSectionFragment(html)
        assertTrue(sec.contains("fpower-amber"), "avg=55 section must carry fpower-amber class")
        assertFalse(sec.contains("fpower-red"), "avg=55 must not be red")
    }

    @Test
    fun `FPW-009 avg=80 lands in fpower-red band`() {
        val html = generate(
            fpowerHistory = listOf(70.0, 80.0, 90.0),
            fpowerAvg = 80.0,
            fpowerPeak = 90.0,
        )
        val sec = fpowerSectionFragment(html)
        assertTrue(sec.contains("fpower-red"), "avg=80 section must carry fpower-red class")
        assertFalse(sec.contains("fpower-green"), "avg=80 must not be green")
    }

    // ── Boundary cases for color bands (FPW-009 cutpoints) ──────────────────

    @Test
    fun `FPW-009 boundary avg=49_99 stays green (exclusive upper)`() {
        val html = generate(fpowerHistory = listOf(49.99), fpowerAvg = 49.99, fpowerPeak = 49.99)
        val sec = fpowerSectionFragment(html)
        assertTrue(sec.contains("fpower-green"), "49.99 section must still be green")
        assertFalse(sec.contains("fpower-amber"), "49.99 must not flip to amber yet")
    }

    @Test
    fun `FPW-009 boundary avg=50_0 flips to amber (inclusive lower)`() {
        val html = generate(fpowerHistory = listOf(50.0), fpowerAvg = 50.0, fpowerPeak = 50.0)
        val sec = fpowerSectionFragment(html)
        assertTrue(sec.contains("fpower-amber"), "50.0 must flip to amber")
        assertFalse(sec.contains("fpower-green"), "50.0 must no longer be green")
    }

    @Test
    fun `FPW-009 boundary avg=64_99 stays amber (exclusive upper)`() {
        val html = generate(fpowerHistory = listOf(64.99), fpowerAvg = 64.99, fpowerPeak = 64.99)
        val sec = fpowerSectionFragment(html)
        assertTrue(sec.contains("fpower-amber"), "64.99 must still be amber")
        assertFalse(sec.contains("fpower-red"), "64.99 must not flip to red yet")
    }

    @Test
    fun `FPW-009 boundary avg=65_0 flips to red (inclusive lower)`() {
        val html = generate(fpowerHistory = listOf(65.0), fpowerAvg = 65.0, fpowerPeak = 65.0)
        val sec = fpowerSectionFragment(html)
        assertTrue(sec.contains("fpower-red"), "65.0 must flip to red")
        assertFalse(sec.contains("fpower-amber"), "65.0 must no longer be amber")
    }

    // ── Unavailable + diagnostic banner (FPW-009, ADR-5) ────────────────────

    @Test
    fun `FPW-009 fpowerAvailable=false renders N_D placeholder plus banner`() {
        val diag = FPowerDiagnostic(
            rawPathsTried = listOf(
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/battery/voltage_now",
            ),
            lastReadout = emptyMap(),
            reason = FPowerUnavailableReason.BATTERY_PATH_MISSING,
        )
        val html = generate(fpowerAvailable = false, fpowerDiagnostic = diag)
        val sec = fpowerSectionFragment(html)

        assertTrue(sec.isNotEmpty(), "unavailable + diagnostic must still render an fpower section")
        assertTrue(
            sec.contains("fpower-unavailable"),
            "unavailable card must carry fpower-unavailable class",
        )
        assertTrue(
            sec.contains("N/D"),
            "fpower card must render N/D when fpowerAvailable=false",
        )
        assertTrue(
            sec.contains("battery/current_now"),
            "banner must list raw path 'battery/current_now', got: $sec",
        )
        assertTrue(
            sec.contains("fpower-diag-banner"),
            "unavailable card must include the diagnostic banner element",
        )
    }

    // ── Each FPowerUnavailableReason maps to distinct Spanish copy (ADR-5) ──

    @Test
    fun `ADR-5 banner covers all FPowerUnavailableReason values with distinct Spanish keywords`() {
        // Per spec FPW-005 + design ADR-5: each reason gets its own
        // tuteo-formal sentence. The property is rendering-with-reason-X
        // contains a reason-specific keyword. Keywords picked to be both
        // human-readable AND distinct across reasons.
        val expectations = mapOf(
            FPowerUnavailableReason.BATTERY_PATH_MISSING to "batería",
            FPowerUnavailableReason.FPS_ZERO to "FPS",
            FPowerUnavailableReason.IMPLAUSIBLE_VALUE to "plausible",
            FPowerUnavailableReason.OEM_LOCKED to "OEM",
            FPowerUnavailableReason.PERMISSION_DENIED to "permisos",
            FPowerUnavailableReason.UNKNOWN to "desconocid",
        )
        for ((reason, keyword) in expectations) {
            val diag = FPowerDiagnostic(
                rawPathsTried = listOf("/sys/class/power_supply/battery/current_now"),
                lastReadout = emptyMap(),
                reason = reason,
            )
            val html = generate(fpowerAvailable = false, fpowerDiagnostic = diag)
            val sec = fpowerSectionFragment(html)
            assertTrue(
                sec.contains(keyword),
                "banner for reason=$reason must mention '$keyword' (Spanish tuteo-formal), got section: $sec",
            )
        }
    }

    // ── Backward compat (FPW-012) ───────────────────────────────────────────

    @Test
    fun `FPW-012 generate without fpower args renders no fpower-card section`() {
        val html = generate(passFPowerArgs = false)
        assertFalse(
            hasFPowerSection(html),
            "defaulted args (no fpower passed) must NOT render the sec-fpower section",
        )
    }

    @Test
    fun `FPW-009 fpowerAvailable=true with empty history renders no fpower-card (edge ultra-short capture)`() {
        // Per spec FPW-009 scenario 3: fpowerAvailable=true AND
        // fpowerHistory.isEmpty() → no card (legacy/thermal precedent).
        val html = generate(
            fpowerHistory = emptyList(),
            fpowerAvg = 0.0,
            fpowerPeak = 0.0,
            fpowerAvailable = true,
            fpowerDiagnostic = null,
        )
        assertFalse(
            hasFPowerSection(html),
            "available=true + empty history must NOT render the sec-fpower section (matches thermal precedent)",
        )
    }
}
