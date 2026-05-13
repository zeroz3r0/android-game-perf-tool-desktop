package com.gameperf.desktop.report

import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.model.WakeLocksDiagnostic
import com.gameperf.desktop.core.model.WakeLocksUnavailableReason
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v4.6.0 — ReportGenerator HTML rendering tests for the
 * `vitals-rate-and-wakelocks` change. Covers spec WLK-001 (h:m
 * human-readable render when available) + 4 Spanish-tuteo-formal banner
 * variants (one per [WakeLocksUnavailableReason]) per design §7.
 *
 * Mirrors the structure of [ReportGeneratorNetworkTest] /
 * [ReportGeneratorGpuTest] per design ADR-1 (mirror gpu/fpower architecture
 * exactly).
 *
 * Property assertions:
 *  1. **Numeric render**: `wakeLocksAvailable=true` + non-zero screenOffMs ->
 *     the section carries hours formatted to 1 decimal place.
 *  2. **Banner variants**: each [WakeLocksUnavailableReason] maps to a
 *     distinct Spanish-tuteo-formal sentence.
 *  3. **Vitals caveat**: the section discloses the v1 single-session vs
 *     cross-session Vitals semantic gap so the user doesn't mistake the
 *     reading for the official Play Console metric.
 *  4. **Backward compat**: calling `generate(...)` without any of the new
 *     wake-locks params produces HTML that contains NO `sec-wake-locks`
 *     section.
 */
class ReportGeneratorWakeLocksTest {

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

    @Suppress("LongParameterList")
    private fun generate(
        wakeLocksAvailable: Boolean = false,
        wakeLocksDiagnostic: WakeLocksDiagnostic? = null,
        wakeLocksScreenOffMs: Long = -1L,
        wakeLocksScreenOnMs: Long = -1L,
        passWakeLocksArgs: Boolean = true,
    ): String {
        val path = if (passWakeLocksArgs) {
            ReportGenerator.generate(
                pkg = "com.example.wl.test",
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
                wakeLocksAvailable = wakeLocksAvailable,
                wakeLocksDiagnostic = wakeLocksDiagnostic,
                wakeLocksScreenOffMs = wakeLocksScreenOffMs,
                wakeLocksScreenOnMs = wakeLocksScreenOnMs,
            )
        } else {
            // Backward-compat path: NO wake-locks args (defaulted by generate()).
            ReportGenerator.generate(
                pkg = "com.example.wl.legacy",
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

    private fun wakeLocksSectionFragment(html: String): String {
        val start = html.indexOf("""<section id="sec-wake-locks"""")
        if (start < 0) return ""
        val end = html.indexOf("</section>", start)
        if (end < 0) return ""
        return html.substring(start, end + "</section>".length)
    }

    private fun hasWakeLocksSection(html: String): Boolean =
        wakeLocksSectionFragment(html).isNotEmpty()

    // ===== Numeric render when available =====

    @Test
    fun `available true with screen-off totals renders hours human-readable`() {
        // 2h 30m = 9_000_000 ms — over the Vitals 2h threshold.
        val html = generate(
            wakeLocksAvailable = true,
            wakeLocksScreenOffMs = 9_000_000L,
            wakeLocksScreenOnMs = 0L,
        )
        val sec = wakeLocksSectionFragment(html)
        assertTrue(sec.isNotEmpty(), "section must render when available + non-zero screenOff")
        assertTrue(
            sec.contains("2.5h") || sec.contains("2,5h") || sec.contains("2.5 h"),
            "screen-off total must render as hours with 1 decimal, got: $sec",
        )
        assertFalse(sec.contains("wake-locks-diag-banner"), "happy path must NOT include diagnostic banner")
        assertFalse(sec.contains("N/D"), "happy path must NOT show N/D placeholder")
    }

    @Test
    fun `available false with no diagnostic renders no section`() {
        val html = generate(wakeLocksAvailable = false, wakeLocksDiagnostic = null)
        assertFalse(
            hasWakeLocksSection(html),
            "available=false + null diagnostic must NOT render the section",
        )
    }

    @Test
    fun `available true with zero ms renders no section`() {
        // Zero screen-off means no wake locks at all — nothing meaningful to
        // report. Mirrors the gpu/network "empty history" precedent.
        val html = generate(
            wakeLocksAvailable = true,
            wakeLocksScreenOffMs = 0L,
            wakeLocksScreenOnMs = 0L,
        )
        assertFalse(
            hasWakeLocksSection(html),
            "available=true + zero ms must NOT render the section",
        )
    }

    // ===== Banner variants — one per WakeLocksUnavailableReason =====

    @Test
    fun `PKG_NOT_FOUND banner mentions package or batterystats`() {
        val diag = WakeLocksDiagnostic(
            probedCommand = "dumpsys batterystats --charged com.example.game",
            reason = WakeLocksUnavailableReason.PKG_NOT_FOUND,
        )
        val sec = wakeLocksSectionFragment(generate(wakeLocksAvailable = false, wakeLocksDiagnostic = diag))
        assertTrue(sec.isNotEmpty(), "unavailable + diagnostic must render section")
        assertTrue(sec.contains("N/D"), "unavailable must show N/D placeholder")
        assertTrue(sec.contains("wake-locks-diag-banner"), "must include diagnostic banner element")
        assertTrue(
            sec.contains("paquete", ignoreCase = true) ||
                sec.contains("batterystats", ignoreCase = true) ||
                sec.contains("juego", ignoreCase = true),
            "PKG_NOT_FOUND banner must reference package/game/batterystats, got: $sec",
        )
    }

    @Test
    fun `PARSE_FAILED banner mentions parse or permission`() {
        val diag = WakeLocksDiagnostic(
            probedCommand = "dumpsys batterystats --charged com.example.game",
            reason = WakeLocksUnavailableReason.PARSE_FAILED,
        )
        val sec = wakeLocksSectionFragment(generate(wakeLocksAvailable = false, wakeLocksDiagnostic = diag))
        assertTrue(
            sec.contains("leer", ignoreCase = true) ||
                sec.contains("permiso", ignoreCase = true) ||
                sec.contains("dumpsys", ignoreCase = true) ||
                sec.contains("restring", ignoreCase = true),
            "PARSE_FAILED banner must reference parse/permission/dumpsys, got: $sec",
        )
    }

    @Test
    fun `OUT_OF_RANGE_VALUE banner mentions implausible or out-of-range`() {
        val diag = WakeLocksDiagnostic(
            probedCommand = "dumpsys batterystats --charged com.example.game",
            reason = WakeLocksUnavailableReason.OUT_OF_RANGE_VALUE,
        )
        val sec = wakeLocksSectionFragment(generate(wakeLocksAvailable = false, wakeLocksDiagnostic = diag))
        assertTrue(
            sec.contains("rango", ignoreCase = true) ||
                sec.contains("valor", ignoreCase = true) ||
                sec.contains("reset", ignoreCase = true) ||
                sec.contains("contador", ignoreCase = true),
            "OUT_OF_RANGE_VALUE banner must reference out-of-range/value, got: $sec",
        )
    }

    @Test
    fun `CAPTURE_THREW banner carries generic resilience message`() {
        val diag = WakeLocksDiagnostic(
            probedCommand = "dumpsys batterystats --charged com.example.game",
            reason = WakeLocksUnavailableReason.CAPTURE_THREW,
        )
        val sec = wakeLocksSectionFragment(generate(wakeLocksAvailable = false, wakeLocksDiagnostic = diag))
        assertTrue(
            sec.contains("error", ignoreCase = true) ||
                sec.contains("inesperado", ignoreCase = true) ||
                sec.contains("FPS"),
            "CAPTURE_THREW banner must reference unexpected error / FPS-still-valid, got: $sec",
        )
    }

    @Test
    fun `every WakeLocksUnavailableReason produces a banner sentence`() {
        WakeLocksUnavailableReason.values().forEach { reason ->
            val diag = WakeLocksDiagnostic(
                probedCommand = "dumpsys batterystats --charged com.example.game",
                reason = reason,
            )
            val sec = wakeLocksSectionFragment(generate(wakeLocksAvailable = false, wakeLocksDiagnostic = diag))
            assertTrue(sec.isNotEmpty(), "reason=$reason must still render the wake-locks section")
            assertTrue(
                sec.contains("wake-locks-diag-banner"),
                "reason=$reason must include diagnostic banner",
            )
        }
    }

    // ===== Vitals caveat — single-session v1 disclosure =====

    @Test
    fun `Vitals caveat about single-session vs cross-session present when available`() {
        val sec = wakeLocksSectionFragment(
            generate(
                wakeLocksAvailable = true,
                wakeLocksScreenOffMs = 9_000_000L,
            )
        )
        assertTrue(
            sec.contains("sesión", ignoreCase = true) ||
                sec.contains("sesion", ignoreCase = true) ||
                sec.contains("Vitals", ignoreCase = true) ||
                sec.contains("Play", ignoreCase = true),
            "available section must carry the v1 single-session caveat, got: $sec",
        )
    }

    // ===== Backward compat =====

    @Test
    fun `BACKWARD-COMPAT generate without wake-locks args renders no section`() {
        val html = generate(passWakeLocksArgs = false)
        assertFalse(
            hasWakeLocksSection(html),
            "defaulted args (no wake-locks passed) must NOT render the sec-wake-locks section",
        )
    }
}
