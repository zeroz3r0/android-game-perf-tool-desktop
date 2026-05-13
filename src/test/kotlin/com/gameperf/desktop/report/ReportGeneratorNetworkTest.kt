package com.gameperf.desktop.report

import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.model.NetworkDiagnostic
import com.gameperf.desktop.core.model.NetworkUnavailableReason
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v4.6.x -- ReportGenerator HTML rendering tests for the `network-bandwidth-
 * total-app` change. Covers spec NET-001 (KB/MB human-readable render when
 * available) + 5 Spanish-tuteo-formal banner variants (one per
 * [NetworkUnavailableReason]) per design §6.
 *
 * Mirrors the structure of [ReportGeneratorGpuTest] per design ADR-1
 * (mirror gpu/fpower architecture exactly).
 *
 * Property assertions:
 *  1. **Numeric render**: `networkAvailable=true` + non-empty history -> the
 *     section carries the max RX/TX values in KB/MB human-readable form +
 *     chart canvas, no diagnostic banner.
 *  2. **Banner variants**: each [NetworkUnavailableReason] maps to a distinct
 *     Spanish-tuteo-formal sentence.
 *  3. **Caveat**: foreground app + per-connection caveat present when
 *     available (NET-design D2: total-app bandwidth, no per-connection
 *     differentiation).
 *  4. **Backward compat**: calling `generate(...)` without any of the new
 *     network params produces HTML that contains NO `sec-network` section.
 */
class ReportGeneratorNetworkTest {

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

    // Minimal valid baseline + opt-in network params. Defaults skip the
    // network section entirely so the backward-compat test exercises the
    // same baseline.
    @Suppress("LongParameterList")
    private fun generate(
        networkAvailable: Boolean = false,
        networkDiagnostic: NetworkDiagnostic? = null,
        networkRxHistory: List<Long> = emptyList(),
        networkTxHistory: List<Long> = emptyList(),
        maxNetworkRxBytes: Long = -1L,
        maxNetworkTxBytes: Long = -1L,
        passNetworkArgs: Boolean = true,
    ): String {
        val path = if (passNetworkArgs) {
            ReportGenerator.generate(
                pkg = "com.example.net.test",
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
                networkAvailable = networkAvailable,
                networkDiagnostic = networkDiagnostic,
                networkRxHistory = networkRxHistory,
                networkTxHistory = networkTxHistory,
                maxNetworkRxBytes = maxNetworkRxBytes,
                maxNetworkTxBytes = maxNetworkTxBytes,
            )
        } else {
            // Backward-compat path: NO network args at all (defaulted by generate()).
            ReportGenerator.generate(
                pkg = "com.example.net.legacy",
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

    /** Extract the `<section id="sec-network"...>...</section>` substring to anchor assertions. */
    private fun networkSectionFragment(html: String): String {
        val start = html.indexOf("""<section id="sec-network"""")
        if (start < 0) return ""
        val end = html.indexOf("</section>", start)
        if (end < 0) return ""
        return html.substring(start, end + "</section>".length)
    }

    private fun hasNetworkSection(html: String): Boolean = networkSectionFragment(html).isNotEmpty()

    // ===== Numeric render when available =====

    @Test
    fun `available true with history renders KB or MB and canvas`() {
        val rx = listOf(1_500_000L, 2_500_000L, 5_000_000L)
        val tx = listOf(200_000L, 400_000L, 800_000L)
        val html = generate(
            networkAvailable = true,
            networkRxHistory = rx,
            networkTxHistory = tx,
            maxNetworkRxBytes = 5_000_000L,
            maxNetworkTxBytes = 800_000L,
        )
        val sec = networkSectionFragment(html)
        assertTrue(sec.isNotEmpty(), "section must render when available + history present")
        // 5_000_000 bytes = ~4.77 MB, expect MB-formatted output
        assertTrue(
            sec.contains("MB") || sec.contains("KB"),
            "max network bytes must render in human-readable KB or MB, got: $sec",
        )
        assertTrue(sec.contains("networkChart"), "section must carry a chart canvas id")
        assertFalse(sec.contains("network-diag-banner"), "happy path must NOT include diagnostic banner")
        assertFalse(sec.contains("N/D"), "happy path must NOT show N/D placeholder")
    }

    @Test
    fun `available false with empty history renders no section`() {
        val html = generate(networkAvailable = false, networkRxHistory = emptyList())
        assertFalse(hasNetworkSection(html), "available=false + null diagnostic must NOT render section")
    }

    @Test
    fun `available true with empty history renders no section`() {
        val html = generate(networkAvailable = true, networkRxHistory = emptyList())
        assertFalse(
            hasNetworkSection(html),
            "available=true + empty history must NOT render section (matches gpu+fpower precedent)",
        )
    }

    // ===== Banner variants — one per NetworkUnavailableReason =====

    @Test
    fun `BINDER_UNAVAILABLE banner mentions binder or Android version`() {
        val diag = NetworkDiagnostic(
            probedSources = listOf("BINDER:11", "BINDER:12", "BINDER:14", "BINDER:15"),
            detectedMethod = null,
            failedBinderCodes = listOf(11, 12, 14, 15),
            reason = NetworkUnavailableReason.BINDER_UNAVAILABLE,
        )
        val sec = networkSectionFragment(generate(networkAvailable = false, networkDiagnostic = diag))
        assertTrue(sec.isNotEmpty(), "unavailable + diagnostic must still render an network section")
        assertTrue(sec.contains("N/D"), "unavailable must show N/D placeholder")
        assertTrue(sec.contains("network-diag-banner"), "must include diagnostic banner element")
        assertTrue(
            sec.contains("binder", ignoreCase = true) || sec.contains("Android"),
            "BINDER_UNAVAILABLE banner must mention 'binder' or 'Android' (version hint), got: $sec",
        )
    }

    @Test
    fun `DUMPSYS_PERMISSION_DENIED banner mentions permission or root`() {
        val diag = NetworkDiagnostic(
            probedSources = listOf("BINDER:11", "BINDER:12", "BINDER:14", "BINDER:15", "DUMPSYS"),
            detectedMethod = null,
            failedBinderCodes = listOf(11, 12, 14, 15),
            reason = NetworkUnavailableReason.DUMPSYS_PERMISSION_DENIED,
        )
        val sec = networkSectionFragment(generate(networkAvailable = false, networkDiagnostic = diag))
        assertTrue(
            sec.contains("permiso", ignoreCase = true) || sec.contains("root", ignoreCase = true) ||
                sec.contains("dumpsys", ignoreCase = true),
            "DUMPSYS_PERMISSION_DENIED banner must mention permission/root/dumpsys, got: $sec",
        )
    }

    @Test
    fun `ALL_PROBES_FAILED banner is generic and lists probed sources`() {
        val diag = NetworkDiagnostic(
            probedSources = listOf("BINDER:11", "BINDER:12", "BINDER:14", "BINDER:15", "DUMPSYS"),
            detectedMethod = null,
            failedBinderCodes = listOf(11, 12, 14, 15),
            reason = NetworkUnavailableReason.ALL_PROBES_FAILED,
        )
        val sec = networkSectionFragment(generate(networkAvailable = false, networkDiagnostic = diag))
        assertTrue(
            sec.contains("netstats") || sec.contains("dispositivo") || sec.contains("sondeo") ||
                sec.contains("sensor"),
            "ALL_PROBES_FAILED banner must mention netstats / device / probe, got: $sec",
        )
    }

    @Test
    fun `IMPLAUSIBLE_VALUE banner mentions implausible value or validation`() {
        val diag = NetworkDiagnostic(
            probedSources = listOf("BINDER:11"),
            detectedMethod = "BINDER:11",
            failedBinderCodes = emptyList(),
            reason = NetworkUnavailableReason.IMPLAUSIBLE_VALUE,
        )
        val sec = networkSectionFragment(generate(networkAvailable = false, networkDiagnostic = diag))
        assertTrue(
            sec.contains("implausible", ignoreCase = true) || sec.contains("valor") ||
                sec.contains("validación") || sec.contains("anómalo") || sec.contains("rango"),
            "IMPLAUSIBLE_VALUE banner must reference implausible / value / out-of-range, got: $sec",
        )
    }

    @Test
    fun `CAPTURE_THREW banner carries a generic resilience message`() {
        val diag = NetworkDiagnostic(
            probedSources = emptyList(),
            detectedMethod = null,
            failedBinderCodes = emptyList(),
            reason = NetworkUnavailableReason.CAPTURE_THREW,
        )
        val sec = networkSectionFragment(generate(networkAvailable = false, networkDiagnostic = diag))
        assertTrue(
            sec.contains("error inesperado") || sec.contains("Reportá") || sec.contains("reportá") ||
                sec.contains("inesperado"),
            "CAPTURE_THREW banner must reference an unexpected error, got: $sec",
        )
    }

    @Test
    fun `every NetworkUnavailableReason produces a banner sentence`() {
        // Defensive: every enum variant must produce a banner with at least
        // some Spanish copy (catches a future addition that forgets to wire
        // the reason -> sentence mapping).
        NetworkUnavailableReason.values().forEach { reason ->
            val diag = NetworkDiagnostic(
                probedSources = listOf("BINDER:11"),
                detectedMethod = null,
                failedBinderCodes = listOf(11),
                reason = reason,
            )
            val sec = networkSectionFragment(generate(networkAvailable = false, networkDiagnostic = diag))
            assertTrue(sec.isNotEmpty(), "reason=$reason must still render the network section")
            assertTrue(sec.contains("network-diag-banner"), "reason=$reason must include diagnostic banner")
        }
    }

    // ===== Caveat: per-connection out-of-scope =====

    @Test
    fun `caveat about per-connection scope present when available`() {
        val sec = networkSectionFragment(
            generate(
                networkAvailable = true,
                networkRxHistory = listOf(1_500_000L, 2_500_000L),
                networkTxHistory = listOf(200_000L, 400_000L),
                maxNetworkRxBytes = 2_500_000L,
                maxNetworkTxBytes = 400_000L,
            )
        )
        assertTrue(
            sec.contains("total") || sec.contains("conexión") || sec.contains("conexion") ||
                sec.contains("API") || sec.contains("VPN"),
            "available section must carry the per-connection caveat (total app, no per-connection split), got: $sec",
        )
    }

    // ===== Backward compat =====

    @Test
    fun `BACKWARD-COMPAT generate without network args renders no sec-network section`() {
        val html = generate(passNetworkArgs = false)
        assertFalse(
            hasNetworkSection(html),
            "defaulted args (no network passed) must NOT render the sec-network section",
        )
    }
}
