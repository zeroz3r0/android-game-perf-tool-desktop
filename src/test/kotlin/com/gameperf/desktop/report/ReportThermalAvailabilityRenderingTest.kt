package com.gameperf.desktop.report

import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.core.model.ThermalDiagnostic
import com.gameperf.desktop.core.model.ThermalUnavailableReason
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v4.4.1 -- ReportGenerator HTML rendering tests for the
 * `temperature-not-shown` change.
 *
 * Covers the BODY rendering tied to the two new params widened into
 * `ReportGenerator.generate(...)`:
 *  - `thermalAvailable: Boolean = true`
 *  - `thermalDiagnostic: ThermalDiagnostic? = null`
 *
 * Three property assertions:
 *  1. `thermalAvailable = false` → temperature card renders "N/D" + sub-line
 *     "Sensor no disponible" instead of the legacy "0°C" that the user reads
 *     as "device is cold".
 *  2. `thermalAvailable = false` with a populated `ThermalDiagnostic` → the
 *     temp section emits a Spanish-tuteo-formal banner listing the raw vendor
 *     zone names AND explaining the reason. Each [ThermalUnavailableReason]
 *     value maps to its own sentence.
 *  3. `thermalAvailable = true` (defaults) → v4.4.0 baseline rendering is
 *     preserved. No "N/D", no diagnostic banner, regular numeric temp shown.
 *
 * Pure assertions on the generated HTML string (file is written to the user's
 * `~/GamePerf Reports/` for free inspection, but the tests read the file
 * contents back to verify the markup).
 *
 * See `sdd/temperature-not-shown/design` ADR for the rendering contract and
 * `CHANGELOG.md` (v4.4.1) for the user-facing behavior.
 */
class ReportThermalAvailabilityRenderingTest {

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

    // Minimal valid inputs reused across tests. The defaults keep the rendered
    // HTML small AND lay out the temp section in its non-skin variant so the
    // assertions can target a single known temp value.
    private fun generate(
        thermalAvailable: Boolean = true,
        thermalDiagnostic: ThermalDiagnostic? = null,
        maxTempCpu: Double = 45.0,
    ): String {
        val path = ReportGenerator.generate(
            pkg = "com.example.thermal.test",
            info = device,
            grade = 'B',
            score = 75,
            duration = 30,
            fpsHistory = listOf(60, 60, 60),
            memHistory = listOf(400L, 410L, 420L),
            nativeHistory = listOf(200L, 205L, 210L),
            javaHistory = listOf(100L, 102L, 104L),
            cpuHistory = listOf(40, 45, 50),
            tempCpuHistory = if (thermalAvailable) listOf(40.0, 42.0, 45.0) else emptyList(),
            tempGpuHistory = if (thermalAvailable) listOf(35.0, 37.0, 39.0) else emptyList(),
            tempSkinHistory = emptyList(),
            allFrameTimes = listOf(16.0, 16.5, 17.0),
            avgFps = 60, minFps = 59, maxFps = 60,
            p1 = 59, p5 = 59, p50 = 60, p90 = 60, p99 = 60,
            avgFrameTime = 16.5, p99FrameTime = 17.0,
            peakMem = 420L, avgCpu = 45, maxCpu = 50,
            maxTempCpu = if (thermalAvailable) maxTempCpu else 0.0,
            maxTempGpu = if (thermalAvailable) 39.0 else 0.0,
            batteryStart = 90, batteryEnd = 88,
            frameDrops = 0, jank = 0, stutter = 0,
            problems = emptyList(),
            isWifi = false,
            thermalAvailable = thermalAvailable,
            thermalDiagnostic = thermalDiagnostic,
        )
        return File(path).readText()
    }

    /**
     * Extracts the substring of the rendered HTML covering the temperature
     * section (`<section id="sec-temp">` … `</section>`). Used so assertions
     * targeted at the temp card / banner don't accidentally match copies of
     * the same string elsewhere (e.g. methodology dump).
     */
    private fun tempSection(html: String): String {
        val start = html.indexOf("""<section id="sec-temp"""")
        check(start >= 0) { "rendered HTML must contain a #sec-temp section" }
        val end = html.indexOf("</section>", start)
        check(end >= 0) { "#sec-temp section must be closed" }
        return html.substring(start, end + "</section>".length)
    }

    /**
     * Extracts the substring covering the temperature METRIC CARD on the
     * dashboard (the `<div class="metric-card">` block whose `metric-title`
     * carries "Temperatura"). Used to assert "N/D" lands in the card vs.
     * elsewhere in the report.
     */
    private fun tempMetricCard(html: String): String {
        // The temp metric card is the only one whose metric-title contains
        // "Temperatura" — works for both "Temperatura die" and
        // "Temperatura piel" variants. We anchor on the title and walk
        // backwards to the enclosing card div.
        val titleIdx = html.indexOf("Temperatura")
        check(titleIdx >= 0) { "rendered HTML must contain a Temperatura metric card title" }
        val cardStart = html.lastIndexOf("""<div class="metric-card">""", titleIdx)
        check(cardStart >= 0) { "metric card opening div must precede the title" }
        // Close on the next </div> after the title that matches metric-card depth.
        // The metric-card layout is fixed (4 inner divs), so we search forward
        // for the FOURTH </div> after cardStart.
        var idx = cardStart
        var closes = 0
        while (closes < 5 && idx < html.length) {
            val next = html.indexOf("</div>", idx + 1)
            if (next < 0) break
            closes++
            idx = next
        }
        return html.substring(cardStart, idx + "</div>".length)
    }

    // ── Test 1: thermalAvailable=false renders N/D placeholder ─────────────

    @Test
    fun `thermalAvailable=false renders N_D placeholder instead of 0 degrees`() {
        val html = generate(thermalAvailable = false)
        val card = tempMetricCard(html)

        assertTrue(
            card.contains("N/D"),
            "temp metric card must render 'N/D' when thermalAvailable=false, got: $card",
        )
        assertFalse(
            card.contains("0\u00B0C") || card.contains("0&deg;C"),
            "temp metric card must NOT render the misleading '0\u00B0C' when thermalAvailable=false, got: $card",
        )
        assertTrue(
            card.contains("Sensor no disponible"),
            "temp metric card must render the 'Sensor no disponible' sub-line, got: $card",
        )
    }

    // ── Test 2: diagnostic banner lists raw zone names + reason ────────────

    @Test
    fun `thermalAvailable=false with diagnostic renders Spanish banner listing raw zone names and reason`() {
        val diag = ThermalDiagnostic(
            rawZoneNames = listOf("vendor-zone-a", "vendor-zone-b", "mystery-temp-x"),
            classificationCounts = mapOf("DieCpu" to 0, "Skin" to 0, "null" to 3),
            reason = ThermalUnavailableReason.ALL_ZONES_UNCLASSIFIED,
        )
        val html = generate(thermalAvailable = false, thermalDiagnostic = diag)
        val temp = tempSection(html)

        // Banner element present using the existing status-box / callout idiom
        // (the implementation may pick either status-warn or callout-warning;
        // we accept any of the precedent classes).
        val hasBannerClass =
            temp.contains("status-warn") ||
                temp.contains("status-box") ||
                temp.contains("callout-warning") ||
                temp.contains("thermal-diag-banner")
        assertTrue(
            hasBannerClass,
            "temp section must include a banner element (status-warn/status-box/callout-warning/thermal-diag-banner), got: $temp",
        )

        // Raw zone names surfaced verbatim
        assertTrue(
            temp.contains("vendor-zone-a"),
            "banner must list raw zone name 'vendor-zone-a', got: $temp",
        )
        assertTrue(
            temp.contains("vendor-zone-b"),
            "banner must list raw zone name 'vendor-zone-b', got: $temp",
        )
        assertTrue(
            temp.contains("mystery-temp-x"),
            "banner must list raw zone name 'mystery-temp-x', got: $temp",
        )

        // Reason explained in Spanish (tuteo-formal). The ALL_ZONES_UNCLASSIFIED
        // sentence mentions "clasificar" + "zonas".
        assertTrue(
            temp.contains("clasificar") && temp.contains("zonas"),
            "banner must explain ALL_ZONES_UNCLASSIFIED reason mentioning 'clasificar' and 'zonas', got: $temp",
        )
    }

    @Test
    fun `diagnostic banner covers all ThermalUnavailableReason values`() {
        // Each reason must map to a distinct, non-empty Spanish sentence.
        // Property: rendering the same diagnostic structure with each reason
        // value produces a banner that contains a reason-specific keyword.
        val zoneNames = listOf("vendor-zone")
        val counts = mapOf("null" to 1)
        val expectations = mapOf(
            ThermalUnavailableReason.NO_ZONES_DETECTED to "no reportó",
            ThermalUnavailableReason.ALL_ZONES_UNCLASSIFIED to "clasificar",
            ThermalUnavailableReason.ALL_TEMPS_INVALID to "rango",
            ThermalUnavailableReason.PERMISSION_DENIED to "permisos",
            ThermalUnavailableReason.UNKNOWN to "desconocid",
        )
        for ((reason, expectedKeyword) in expectations) {
            val diag = ThermalDiagnostic(
                rawZoneNames = zoneNames,
                classificationCounts = counts,
                reason = reason,
            )
            val html = generate(thermalAvailable = false, thermalDiagnostic = diag)
            val temp = tempSection(html)
            assertTrue(
                temp.contains(expectedKeyword),
                "banner for reason=$reason must mention '$expectedKeyword' (Spanish tuteo-formal), got: $temp",
            )
        }
    }

    // ── Test 3: thermalAvailable=true preserves baseline rendering ─────────

    @Test
    fun `thermalAvailable=true preserves v4_4_0 baseline rendering`() {
        // Defaults: thermalAvailable=true, thermalDiagnostic=null. Legacy
        // callers (ReportRenderingTest, ReportGradingTest) MUST keep their
        // pre-v4.4.1 rendering unchanged.
        val html = generate(thermalAvailable = true, thermalDiagnostic = null, maxTempCpu = 45.0)
        val card = tempMetricCard(html)
        val temp = tempSection(html)

        // Numeric temp present in the card (45°C or 45&deg;C variant).
        val hasNumeric =
            card.contains("45\u00B0C") ||
                card.contains("45&deg;C") ||
                card.contains("45°C")
        assertTrue(hasNumeric, "temp metric card must render the numeric '45°C' baseline, got: $card")

        // No "N/D" placeholder in the temp card.
        assertFalse(
            card.contains("N/D"),
            "temp metric card must NOT render 'N/D' when thermalAvailable=true, got: $card",
        )

        // No diagnostic banner classes in the temp section. The pre-existing
        // section already uses status-box / status-ok for empty problem lists,
        // but those live in #sec-problems — the #sec-temp section must stay
        // free of banner markup when nothing is wrong.
        assertFalse(
            temp.contains("thermal-diag-banner") ||
                temp.contains("callout-warning") ||
                temp.contains("status-warn"),
            "temp section must NOT include a diagnostic banner when thermalAvailable=true, got: $temp",
        )
    }
}
