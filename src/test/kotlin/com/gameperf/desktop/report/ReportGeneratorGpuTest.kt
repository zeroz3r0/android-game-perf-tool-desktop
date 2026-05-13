package com.gameperf.desktop.report

import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.model.GpuDiagnostic
import com.gameperf.desktop.core.model.GpuUnavailableReason
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v4.5.0 -- ReportGenerator HTML rendering tests for the `gpu-usage-percent`
 * Sprint 1 change. Covers spec GPU-018 (% render when available) /
 * GPU-019 (5 Spanish-tuteo-formal banner variants) / GPU-020 (foreground-
 * attribution caveat + Adreno warm-up footnote) per design §7.
 *
 * Mirrors the structure of [ReportGeneratorFPowerTest] per design ADR-1
 * (mirror thermal/fpower architecture exactly).
 *
 * Property assertions:
 *  1. **% render (GPU-018)**: `gpuAvailable=true` + non-empty history -> the
 *     section carries the max % value and chart canvas, no diagnostic banner.
 *  2. **Banner variants (GPU-019)**: each [GpuUnavailableReason] maps to a
 *     distinct Spanish-tuteo-formal sentence with vendor-specific keywords
 *     (Adreno / PowerVR / MediaTek+Unisoc / "tu dispositivo" etc).
 *  3. **Adreno warm-up footnote (GPU-020)**: when `detectedVendor=="ADRENO"`
 *     the rendered section carries the warm-up disclaimer.
 *  4. **Foreground-attribution caveat (GPU-020)**: when `gpuAvailable=true`
 *     the rendered section always carries the foreground-app disclaimer.
 *  5. **Backward compat**: calling `generate(...)` without any of the new
 *     gpu params produces HTML that contains NO `sec-gpu` section.
 *
 * Pure assertions on the generated HTML file. Pattern identical to the
 * sibling [ReportGeneratorFPowerTest].
 */
class ReportGeneratorGpuTest {

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

    // Minimal valid baseline + opt-in gpu params. Defaults skip the gpu section
    // entirely so the backward-compat test exercises the same baseline.
    @Suppress("LongParameterList")
    private fun generate(
        gpuAvailable: Boolean = false,
        gpuDiagnostic: GpuDiagnostic? = null,
        gpuUsageHistory: List<Int> = emptyList(),
        maxGpuUsage: Int = -1,
        passGpuArgs: Boolean = true,
    ): String {
        val path = if (passGpuArgs) {
            ReportGenerator.generate(
                pkg = "com.example.gpu.test",
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
                gpuAvailable = gpuAvailable,
                gpuDiagnostic = gpuDiagnostic,
                gpuUsageHistory = gpuUsageHistory,
                maxGpuUsage = maxGpuUsage,
            )
        } else {
            // Backward-compat path: NO gpu args at all (defaulted by generate()).
            ReportGenerator.generate(
                pkg = "com.example.gpu.legacy",
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

    /** Extract the `<section id="sec-gpu"...>...</section>` substring to anchor assertions. */
    private fun gpuSectionFragment(html: String): String {
        val start = html.indexOf("""<section id="sec-gpu"""")
        if (start < 0) return ""
        val end = html.indexOf("</section>", start)
        if (end < 0) return ""
        return html.substring(start, end + "</section>".length)
    }

    private fun hasGpuSection(html: String): Boolean = gpuSectionFragment(html).isNotEmpty()

    // ===== GPU-018: % render when available =====

    @Test
    fun `GPU-018 available true with history renders percent and canvas`() {
        val history = listOf(35, 48, 60, 72)
        val html = generate(
            gpuAvailable = true,
            gpuUsageHistory = history,
            maxGpuUsage = 72,
        )
        val sec = gpuSectionFragment(html)
        assertTrue(sec.isNotEmpty(), "section must render when available + history present")
        assertTrue(sec.contains("72%"), "max GPU% must appear in section, got: $sec")
        assertTrue(sec.contains("gpuChart"), "section must carry a chart canvas id")
        assertFalse(sec.contains("gpu-diag-banner"), "happy path must NOT include diagnostic banner")
        assertFalse(sec.contains("N/D"), "happy path must NOT show N/D placeholder")
    }

    @Test
    fun `GPU-018 unavailable with empty history renders no section`() {
        // available=false but NO diagnostic -> nothing to render (matches the
        // thermal+fpower precedent: ultra-short capture, legacy default).
        val html = generate(gpuAvailable = false, gpuUsageHistory = emptyList())
        assertFalse(hasGpuSection(html), "available=false + null diagnostic must NOT render section")
    }

    @Test
    fun `GPU-018 available true with empty history renders no section`() {
        // Edge case: capture reported gpuAvailable=true at some point but never
        // appended a sample (e.g. only Adreno warm-up baseline ticks). The
        // section should NOT render because there's nothing useful to display.
        val html = generate(gpuAvailable = true, gpuUsageHistory = emptyList())
        assertFalse(
            hasGpuSection(html),
            "available=true + empty history must NOT render section (matches thermal+fpower precedent)",
        )
    }

    // ===== GPU-019: 5 Spanish-tuteo-formal banner variants =====

    @Test
    fun `GPU-019 ADRENO_PERFCOUNTER_DISABLED banner mentions Adreno and failedEnableCommand`() {
        val diag = GpuDiagnostic(
            probedPaths = listOf(
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/gpubusy",
            ),
            detectedVendor = "ADRENO",
            failedEnableCommand = "echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter 2>&1",
            reason = GpuUnavailableReason.ADRENO_PERFCOUNTER_DISABLED,
        )
        val sec = gpuSectionFragment(generate(gpuAvailable = false, gpuDiagnostic = diag))
        assertTrue(sec.isNotEmpty(), "unavailable + diagnostic must still render an gpu section")
        assertTrue(sec.contains("N/D"), "unavailable must show N/D placeholder")
        assertTrue(sec.contains("gpu-diag-banner"), "must include diagnostic banner element")
        assertTrue(sec.contains("Adreno", ignoreCase = false), "banner must mention 'Adreno'")
        assertTrue(sec.contains("perfcounter"), "banner must reference the perfcounter command path")
        assertTrue(sec.contains("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"), "banner must list probed paths")
    }

    @Test
    fun `GPU-019 POWERVR_UNSUPPORTED banner mentions PowerVR and crowdsource invite`() {
        val diag = GpuDiagnostic(
            probedPaths = listOf("/proc/mtk_mali/utilization"),
            detectedVendor = "POWERVR",
            reason = GpuUnavailableReason.POWERVR_UNSUPPORTED,
        )
        val sec = gpuSectionFragment(generate(gpuAvailable = false, gpuDiagnostic = diag))
        assertTrue(sec.contains("PowerVR"), "banner must mention 'PowerVR'")
        // Crowdsource invite alludes to MediaTek or Unisoc + Sprint 1.5 follow-up.
        assertTrue(
            sec.contains("MediaTek") || sec.contains("Unisoc"),
            "banner must reference MediaTek/Unisoc (the typical PowerVR carrier vendors)",
        )
    }

    @Test
    fun `GPU-019 ALL_PROBES_FAILED banner is generic and lists probedPaths`() {
        val diag = GpuDiagnostic(
            probedPaths = listOf(
                "/sys/class/misc/mali0/device/utilization",
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            ),
            detectedVendor = null,
            reason = GpuUnavailableReason.ALL_PROBES_FAILED,
        )
        val sec = gpuSectionFragment(generate(gpuAvailable = false, gpuDiagnostic = diag))
        // Generic copy mentions sensors / vendor catalogue / probes.
        assertTrue(
            sec.contains("sensores") || sec.contains("vendor") || sec.contains("catálogo") || sec.contains("catalog"),
            "ALL_PROBES_FAILED banner must mention sensors / vendor / catalogue, got: $sec",
        )
        assertTrue(
            sec.contains("/sys/class/misc/mali0/device/utilization"),
            "banner must list the probed paths verbatim for filing an issue",
        )
    }

    @Test
    fun `GPU-019 CAPTURE_THREW banner carries a generic resilience message`() {
        val diag = GpuDiagnostic(
            probedPaths = emptyList(),
            detectedVendor = null,
            reason = GpuUnavailableReason.CAPTURE_THREW,
        )
        val sec = gpuSectionFragment(generate(gpuAvailable = false, gpuDiagnostic = diag))
        assertTrue(
            sec.contains("error inesperado") || sec.contains("Reportá"),
            "CAPTURE_THREW banner must reference an unexpected error and ask to report it, got: $sec",
        )
    }

    @Test
    fun `GPU-019 ADRENO_BLOCKED banner mentions Adreno and SELinux or OEM`() {
        val diag = GpuDiagnostic(
            probedPaths = listOf("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"),
            detectedVendor = "ADRENO",
            reason = GpuUnavailableReason.ADRENO_BLOCKED,
        )
        val sec = gpuSectionFragment(generate(gpuAvailable = false, gpuDiagnostic = diag))
        assertTrue(sec.contains("Adreno"), "ADRENO_BLOCKED must mention 'Adreno'")
        assertTrue(
            sec.contains("SELinux") || sec.contains("OEM"),
            "ADRENO_BLOCKED must mention SELinux or OEM as the gatekeeper, got: $sec",
        )
    }

    @Test
    fun `GPU-019 every GpuUnavailableReason produces a banner sentence`() {
        // Defensive: every enum variant must produce a banner with at least
        // some Spanish copy (catches a future addition that forgets to wire
        // the reason -> sentence mapping).
        GpuUnavailableReason.values().forEach { reason ->
            val diag = GpuDiagnostic(
                probedPaths = listOf("/sys/dummy"),
                detectedVendor = null,
                reason = reason,
            )
            val sec = gpuSectionFragment(generate(gpuAvailable = false, gpuDiagnostic = diag))
            assertTrue(sec.isNotEmpty(), "reason=$reason must still render the gpu section")
            assertTrue(sec.contains("gpu-diag-banner"), "reason=$reason must include diagnostic banner")
        }
    }

    // ===== GPU-020: Adreno warm-up footnote =====

    @Test
    fun `GPU-020 Adreno warm-up footnote present when vendor is ADRENO`() {
        // Happy path with Adreno-detected diagnostic available alongside the
        // history (the bridge populates lastGpu.diagnostic on the first
        // successful Adreno read so the report can disclose the warm-up).
        val diag = GpuDiagnostic(
            probedPaths = listOf("/sys/class/kgsl/kgsl-3d0/gpubusy"),
            detectedVendor = "ADRENO",
            reason = GpuUnavailableReason.ALL_PROBES_FAILED, // reason ignored when available=true
        )
        val sec = gpuSectionFragment(
            generate(
                gpuAvailable = true,
                gpuDiagnostic = diag,
                gpuUsageHistory = listOf(35, 48, 60, 72),
                maxGpuUsage = 72,
            )
        )
        assertTrue(
            sec.contains("Adreno warm-up") || sec.contains("warm-up"),
            "Adreno-detected section must carry the warm-up footnote, got: $sec",
        )
    }

    @Test
    fun `GPU-020 Adreno warm-up footnote absent when vendor is MALI`() {
        // Mali probes are kernel-computed pct (no warm-up needed). The
        // footnote should NOT render to avoid misleading copy on devices
        // that don't have the issue.
        val diag = GpuDiagnostic(
            probedPaths = listOf("/sys/class/misc/mali0/device/utilization"),
            detectedVendor = "MALI",
            reason = GpuUnavailableReason.ALL_PROBES_FAILED, // reason ignored when available=true
        )
        val sec = gpuSectionFragment(
            generate(
                gpuAvailable = true,
                gpuDiagnostic = diag,
                gpuUsageHistory = listOf(35, 48, 60, 72),
                maxGpuUsage = 72,
            )
        )
        assertFalse(sec.contains("warm-up"), "Mali-detected section must NOT carry Adreno warm-up footnote")
    }

    // ===== GPU-020: foreground-attribution caveat =====

    @Test
    fun `GPU-020 foreground-attribution caveat always present when available`() {
        val sec = gpuSectionFragment(
            generate(
                gpuAvailable = true,
                gpuUsageHistory = listOf(35, 48, 60, 72),
                maxGpuUsage = 72,
            )
        )
        assertTrue(
            sec.contains("foreground") || sec.contains("Atribución"),
            "available section must carry the foreground-attribution caveat, got: $sec",
        )
        assertTrue(
            sec.contains("DVFS") || sec.contains("clock") || sec.contains("potencia"),
            "caveat must explain DVFS / clock so users understand peak under-reporting, got: $sec",
        )
    }

    // ===== Backward compat =====

    @Test
    fun `BACKWARD-COMPAT generate without gpu args renders no sec-gpu section`() {
        val html = generate(passGpuArgs = false)
        assertFalse(
            hasGpuSection(html),
            "defaulted args (no gpu passed) must NOT render the sec-gpu section",
        )
    }
}
