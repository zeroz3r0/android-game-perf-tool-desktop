package com.gameperf.desktop.report

import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SDD `cpu-total-vs-app-usage` Sprint 2 — ReportGenerator HTML rendering tests
 * for the dual-line CPU chart (GameBench-inspired). Covers design ADR-5
 * (Chart.js 2-dataset branch) + ADR-3 (backward compat via defaulted-empty
 * list mirroring v4.5.0 fpower playbook) + Spanish-tuteo-formal caveat copy.
 *
 * Mirrors the structure of [ReportGeneratorFPowerTest] per design ADR-3
 * (mirror fpower backward-compat architecture exactly).
 *
 * Property assertions:
 *  1. **CPUDUAL-001 — Dual view rendered when populated**: passing a
 *     non-empty `cpuTotalHistory` renders TWO Chart.js datasets in the CPU
 *     chart with labels `CPU total dispositivo` (first) + `CPU app` (second)
 *     per design ADR-5.
 *  2. **CPUDUAL-002 — Legacy single view when empty**: passing an empty
 *     `cpuTotalHistory` (or omitting the argument entirely) keeps the
 *     existing single-dataset `CPU %` form so legacy fixtures and pre-v4.5.x
 *     `.gameperf` re-renders stay byte-equivalent.
 *  3. **CPUDUAL-003 — Spanish-tuteo-formal caveat copy**: when the dual
 *     view is active the CPU section must include a sentence containing the
 *     phrase `saturado por otros procesos` (the design-mandated copy that
 *     explains GameBench's "total includes OS + other apps" semantics).
 *  4. **CPUDUAL-004 — No caveat in legacy view**: when `cpuTotalHistory`
 *     is empty the dual-view caveat must NOT be rendered (so legacy fixtures
 *     and `ReportRenderingTest` stay byte-equivalent).
 *
 * Pure string assertions on the generated HTML. Each test writes its report
 * to `~/GamePerf Reports/` for free inspection but reads the markup back to
 * verify the contract.
 *
 * See `openspec/changes/cpu-total-vs-app-usage/spec.md` (CPUDUAL-001..004)
 * and `openspec/changes/cpu-total-vs-app-usage/design.md` (ADR-3 + ADR-5).
 */
class ReportGeneratorCpuDualTest {

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

    /**
     * Minimal valid baseline + opt-in cpuTotalHistory param. When
     * `passCpuTotalArg = false` the test exercises the defaulted-arg
     * (legacy / pre-cpu-dual) code path.
     */
    private fun generate(
        cpuTotalHistory: List<Int> = emptyList(),
        passCpuTotalArg: Boolean = true,
    ): String {
        val path = if (passCpuTotalArg) {
            ReportGenerator.generate(
                pkg = "com.example.cpudual.test",
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
                cpuTotalHistory = cpuTotalHistory,
            )
        } else {
            // Backward-compat path: NO cpuTotalHistory arg at all
            // (defaulted by generate()).
            ReportGenerator.generate(
                pkg = "com.example.cpudual.legacy",
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
     * Extracts the `<section id="sec-cpu" ...>...</section>` substring from
     * the rendered HTML. Anchored on `id="sec-cpu"` so assertions don't
     * false-match content from other CPU mentions elsewhere in the report
     * (Temperature section, Methodology grid, comparison radar, etc.).
     */
    private fun cpuSectionFragment(html: String): String {
        val start = html.indexOf("""<section id="sec-cpu"""")
        if (start < 0) return ""
        val end = html.indexOf("</section>", start)
        if (end < 0) return ""
        return html.substring(start, end + "</section>".length)
    }

    /**
     * Extracts the JS IIFE that builds the CPU Chart.js chart (anchored on
     * `getElementById('cpuChart')`). Used by tests that assert on dataset
     * shape — they must look at the chart payload, NOT the surrounding
     * section, because the chart JS is appended later in the HTML body.
     */
    private fun cpuChartScript(html: String): String {
        val anchor = "getElementById('cpuChart')"
        val anchorIdx = html.indexOf(anchor)
        if (anchorIdx < 0) return ""
        // Walk back to the enclosing `(function(){` and forward to the
        // matching `})();` close. Cheap heuristic: previous `(function`
        // up to next `})();`.
        val start = html.lastIndexOf("(function(){", anchorIdx).coerceAtLeast(0)
        val end = html.indexOf("})();", anchorIdx).let {
            if (it < 0) html.length else it + "})();".length
        }
        return html.substring(start, end)
    }

    // ── CPUDUAL-001 — Dual view rendered when populated ─────────────────────

    @Test
    fun `CPUDUAL-001 dual CPU view - cpuTotalHistory non-empty - HTML contains both dataset labels`() {
        val html = generate(cpuTotalHistory = listOf(70, 75, 80))
        val script = cpuChartScript(html)

        assertTrue(script.isNotEmpty(), "cpuChart script block must be present in HTML")
        assertTrue(
            script.contains("CPU total dispositivo"),
            "dual view must render the 'CPU total dispositivo' dataset label per ADR-5",
        )
        assertTrue(
            script.contains("'CPU app'") || script.contains("\"CPU app\""),
            "dual view must render the 'CPU app' dataset label per ADR-5",
        )
    }

    @Test
    fun `CPUDUAL-001 dual CPU view - cpuTotalHistory data values embedded in chart`() {
        // Per design ADR-5: the second dataset must carry the actual total
        // history values so Chart.js plots them. We assert on the raw
        // comma-joined integers because that's how the existing CPU chart
        // emits the App series (`data:[$cpuD]` → `data:[40,45,50]`).
        val html = generate(cpuTotalHistory = listOf(70, 75, 80))
        val script = cpuChartScript(html)

        assertTrue(
            script.contains("70,75,80"),
            "dual view chart must embed the cpuTotalHistory series (70,75,80) verbatim, got: $script",
        )
    }

    // ── CPUDUAL-002 — Legacy single view when empty ─────────────────────────

    @Test
    fun `CPUDUAL-002 legacy single CPU view - cpuTotalHistory empty - HTML contains only legacy label`() {
        val html = generate(cpuTotalHistory = emptyList())
        val script = cpuChartScript(html)

        assertTrue(script.isNotEmpty(), "cpuChart script block must be present in HTML")
        assertTrue(
            script.contains("'CPU %'") || script.contains("\"CPU %\""),
            "legacy view must keep the original 'CPU %' dataset label",
        )
        assertFalse(
            script.contains("CPU total dispositivo"),
            "empty cpuTotalHistory must NOT render the 'CPU total dispositivo' label (legacy compat)",
        )
    }

    @Test
    fun `CPUDUAL-002 backward compat - generate without cpuTotalHistory arg renders legacy single dataset`() {
        // Per design ADR-3: the defaulted-empty cpuTotalHistory param means
        // callers that haven't been updated yet (legacy fixtures, pre-v4.5.x
        // history re-renders) get IDENTICAL output to before the change.
        val html = generate(passCpuTotalArg = false)
        val script = cpuChartScript(html)

        assertFalse(
            script.contains("CPU total dispositivo"),
            "omitting the cpuTotalHistory arg must NOT render the dual-view dataset label",
        )
        assertTrue(
            script.contains("'CPU %'") || script.contains("\"CPU %\""),
            "omitting the cpuTotalHistory arg must keep the legacy 'CPU %' single dataset",
        )
    }

    // ── CPUDUAL-003 — Spanish-tuteo-formal caveat copy ──────────────────────

    @Test
    fun `CPUDUAL-003 dual CPU view contains Spanish caveat about device saturation`() {
        val html = generate(cpuTotalHistory = listOf(70, 75, 80))
        val cpuSection = cpuSectionFragment(html)

        assertTrue(cpuSection.isNotEmpty(), "CPU section must be present")
        assertTrue(
            cpuSection.contains("saturado por otros procesos"),
            "dual view CPU section must include the Spanish-tuteo-formal caveat about device " +
                "saturation (mandated by design ADR-5 + user feedback 2026-05-12), got: $cpuSection",
        )
    }

    @Test
    fun `CPUDUAL-003 caveat copy uses tuteo-formal voice not usted`() {
        // Negative invariant — the rest of the report uses tuteo-formal
        // ("tu juego", "tu dispositivo"). The new caveat MUST follow the
        // same register (no "usted", no "su dispositivo").
        val html = generate(cpuTotalHistory = listOf(70, 75, 80))
        val cpuSection = cpuSectionFragment(html)

        // Find the caveat sentence in the section.
        val caveatIdx = cpuSection.indexOf("saturado por otros procesos")
        assertTrue(caveatIdx >= 0, "caveat must be present (pre-condition for voice check)")

        // Look at the surrounding ~300 chars (the caveat sentence ± context).
        val window = cpuSection.substring(
            (caveatIdx - 200).coerceAtLeast(0),
            (caveatIdx + 200).coerceAtMost(cpuSection.length),
        )

        assertFalse(
            window.contains(" usted ") || window.contains("Usted "),
            "caveat copy must use tuteo-formal voice (no 'usted'), got window: $window",
        )
    }

    // ── CPUDUAL-004 — No caveat in legacy view ──────────────────────────────

    @Test
    fun `CPUDUAL-004 legacy view does NOT contain the dual-view caveat`() {
        val html = generate(cpuTotalHistory = emptyList())
        val cpuSection = cpuSectionFragment(html)

        assertTrue(cpuSection.isNotEmpty(), "CPU section must still be present in legacy view")
        assertFalse(
            cpuSection.contains("saturado por otros procesos"),
            "legacy view (empty cpuTotalHistory) must NOT render the dual-view caveat " +
                "(backward compat with ReportRenderingTest)",
        )
    }

    @Test
    fun `CPUDUAL-004 backward compat - generate without cpuTotalHistory arg does NOT render caveat`() {
        val html = generate(passCpuTotalArg = false)
        val cpuSection = cpuSectionFragment(html)

        assertFalse(
            cpuSection.contains("saturado por otros procesos"),
            "omitting the cpuTotalHistory arg must NOT render the dual-view caveat",
        )
    }

    // ── Cross-check: legacy chart label count stays at exactly 1 ────────────

    @Test
    fun `CPUDUAL-002 legacy chart still uses exactly one dataset entry`() {
        // Regression guard against accidental dual-dataset emission when
        // cpuTotalHistory is empty. We count `label:'CPU` occurrences inside
        // the CPU chart script — that pattern matches dataset labels
        // ('CPU %' or 'CPU total dispositivo' / 'CPU app') without
        // false-matching the threshold-line annotation's `label:{content:...}`
        // or option `scales.y.label` keys.
        val html = generate(cpuTotalHistory = emptyList())
        val script = cpuChartScript(html)
        val datasetLabels = Regex("""label\s*:\s*['"]CPU""").findAll(script).count()

        assertEquals(
            1,
            datasetLabels,
            "legacy CPU chart must emit exactly 1 dataset label entry, got $datasetLabels in: $script",
        )
    }
}
