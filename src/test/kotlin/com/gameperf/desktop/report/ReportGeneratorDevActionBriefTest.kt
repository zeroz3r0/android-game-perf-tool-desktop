package com.gameperf.desktop.report

import com.gameperf.desktop.core.conclusions.Severity
import com.gameperf.desktop.core.devactions.ActionStep
import com.gameperf.desktop.core.devactions.CodeAreaHint
import com.gameperf.desktop.core.devactions.Confidence
import com.gameperf.desktop.core.devactions.DevActionBrief
import com.gameperf.desktop.core.devactions.DevActionEvidence
import com.gameperf.desktop.core.devactions.DevActionItem
import com.gameperf.desktop.core.devactions.GameEngine
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v4.5.0 — Sprint 3 ReportGenerator rendering tests for the
 * `dev-action-brief` change. Covers spec DAB-008 (section at TOP of body
 * BEFORE summary cards + before #sec-conclusions), DAB-009 (Spanish
 * tuteo-formal), DAB-010 (backward compat — no brief passed = no section),
 * DAB-011 (severity CSS classes), DAB-012 (Top-N cap + "Mostrar todos"
 * toggle), DAB-013 (evidence rendering), and design ADR-7 (placement).
 *
 * Mirrors the structure of [ReportGeneratorFPowerTest] (Sprint 3 builds
 * on top of the v4.5.0 fpower precedent). Pure assertions on the
 * generated HTML string (file is written for free inspection but tests
 * read it back from disk).
 */
class ReportGeneratorDevActionBriefTest {

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

    private fun sampleItem(
        ruleId: String = "stable-low-fps-low-cpu",
        severity: Severity = Severity.CRITICAL,
        title: String = "FPS bajo estable con CPU disponible.",
        confidence: Confidence = Confidence.HIGH,
        docLink: String? = "https://docs.unity3d.com/Manual/profiler.html",
    ): DevActionItem = DevActionItem(
        ruleId = ruleId,
        severity = severity,
        title = title,
        evidence = DevActionEvidence(
            metric = "fps",
            segment = "FILTERED",
            values = mapOf("p50" to "28", "avgCpu" to "45"),
        ),
        diagnostic = "El motor no satura la CPU; el target FPS quizá esté en 30.",
        codeAreaHints = listOf(
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Application.targetFrameRate",
                whyHere = "Revisa el frame-rate objetivo del motor.",
                docLink = "https://docs.unity3d.com/ScriptReference/Application-targetFrameRate.html",
            ),
        ),
        suggestedActions = listOf(
            ActionStep(
                description = "Verifica el valor de Application.targetFrameRate en el bootstrap.",
                tool = "Unity Profiler",
                docLink = docLink,
                engineSpecific = GameEngine.UNITY,
            ),
        ),
        relatedLogcatLines = emptyList(),
        confidence = confidence,
    )

    private fun generate(brief: DevActionBrief? = null, passBriefArg: Boolean = true): String {
        val path = if (passBriefArg) {
            ReportGenerator.generate(
                pkg = "com.example.devactions.test",
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
                devActionBrief = brief,
            )
        } else {
            // Backward-compat path: NO devActionBrief arg at all (defaulted).
            ReportGenerator.generate(
                pkg = "com.example.devactions.legacy",
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

    /** Marker substring that anchors the dev-action-brief section. */
    private val sectionMarker = """<section id="sec-dev-action-brief""""

    // ── DAB-010 backward compat (no brief = no section) ─────────────────

    @Test
    fun `DAB-010 generate without devActionBrief arg renders no dev-action-brief section`() {
        val html = generate(passBriefArg = false)
        assertFalse(
            html.contains(sectionMarker),
            "defaulted (no brief) call must NOT render the sec-dev-action-brief section",
        )
    }

    @Test
    fun `DAB-008 generate with null brief renders no section`() {
        val html = generate(brief = null)
        assertFalse(
            html.contains(sectionMarker),
            "explicit null brief must omit the section entirely",
        )
    }

    @Test
    fun `DAB-008 generate with empty brief items renders no section`() {
        val html = generate(brief = DevActionBrief(items = emptyList()))
        assertFalse(
            html.contains(sectionMarker),
            "empty items list must omit the section entirely",
        )
    }

    // ── DAB-008 ADR-7 placement at TOP of body BEFORE summary ───────────

    @Test
    fun `DAB-008 section appears BEFORE sec-summary in byte offset`() {
        val html = generate(brief = DevActionBrief(items = listOf(sampleItem())))
        val briefIdx = html.indexOf(sectionMarker)
        val summaryIdx = html.indexOf("""<section id="sec-summary"""")
        assertTrue(briefIdx > 0, "dev-action-brief section must be present")
        assertTrue(summaryIdx > 0, "summary section must be present")
        assertTrue(
            briefIdx < summaryIdx,
            "ADR-7: dev-action-brief at byte=$briefIdx must precede sec-summary at byte=$summaryIdx",
        )
    }

    @Test
    fun `DAB-008 section appears BEFORE sec-conclusions when both present`() {
        // Note: this report has no conclusions, so sec-conclusions may not exist.
        // The invariant we lock here is: IF both are rendered, dev-action-brief leads.
        val html = generate(brief = DevActionBrief(items = listOf(sampleItem())))
        val briefIdx = html.indexOf(sectionMarker)
        val conclusionsIdx = html.indexOf("""<section id="sec-conclusions"""")
        // sec-conclusions only renders when conclusions list is non-empty;
        // our generate() helper passes none, so we just assert the brief is rendered.
        assertTrue(briefIdx > 0, "dev-action-brief must be present")
        if (conclusionsIdx > 0) {
            assertTrue(briefIdx < conclusionsIdx, "brief must precede conclusions")
        }
    }

    // ── DAB-011 severity CSS classes ─────────────────────────────────────

    @Test
    fun `DAB-011 CRITICAL item carries severity-CRITICAL CSS class`() {
        val html = generate(brief = DevActionBrief(items = listOf(sampleItem(severity = Severity.CRITICAL))))
        assertTrue(
            html.contains("severity-CRITICAL"),
            "CRITICAL item must include 'severity-CRITICAL' CSS class",
        )
    }

    @Test
    fun `DAB-011 WARNING item carries severity-WARNING CSS class`() {
        val html = generate(
            brief = DevActionBrief(
                items = listOf(sampleItem(ruleId = "thermal-throttling", severity = Severity.WARNING)),
            ),
        )
        assertTrue(html.contains("severity-WARNING"), "WARNING must include severity-WARNING")
    }

    @Test
    fun `DAB-011 INFO item carries severity-INFO CSS class`() {
        val html = generate(
            brief = DevActionBrief(
                items = listOf(sampleItem(ruleId = "ad-vs-game-fps-gap", severity = Severity.INFO)),
            ),
        )
        assertTrue(html.contains("severity-INFO"), "INFO must include severity-INFO")
    }

    // ── DAB-012 Top-N cap + toggle ──────────────────────────────────────

    @Test
    fun `DAB-012 brief with 6 items renders all 6 plus the show-all toggle`() {
        val items = (1..6).map { sampleItem(ruleId = "rule-$it", title = "Hallazgo #$it") }
        val html = generate(brief = DevActionBrief(items = items, topN = 5))
        // All 6 titles present in HTML (CSS hides the 6th by default).
        items.forEach { item ->
            assertTrue(
                html.contains(item.title),
                "All ${items.size} items must be present in HTML; missing: ${item.title}",
            )
        }
        // Look for the actual button element, not the CSS class string (which
        // is always present in the inline <style> block).
        assertTrue(
            html.contains("""<button class="show-all-toggle""""),
            "toggle button element must be rendered when items > topN",
        )
        assertTrue(html.contains("Mostrar todos los hallazgos"), "toggle must include Spanish copy")
    }

    @Test
    fun `DAB-012 brief with 3 items renders NO toggle (under cap)`() {
        val items = (1..3).map { sampleItem(ruleId = "rule-$it", title = "Hallazgo #$it") }
        val html = generate(brief = DevActionBrief(items = items, topN = 5))
        // CSS class string is always present (in inline <style>); look for the
        // actual rendered button element to assert absence correctly.
        assertFalse(
            html.contains("""<button class="show-all-toggle""""),
            "toggle button element must be absent when items.size <= topN",
        )
    }

    // ── DAB-009 Spanish tuteo-formal copy ───────────────────────────────

    @Test
    fun `DAB-009 dev-action-brief section uses Spanish tuteo-formal (no usted, no vosotros)`() {
        val items = listOf(
            sampleItem(ruleId = "rule-1"),
            sampleItem(ruleId = "rule-2", severity = Severity.WARNING),
        )
        val html = generate(brief = DevActionBrief(items = items))
        // Isolate the brief section to avoid false-matching the rest of the report.
        val start = html.indexOf(sectionMarker)
        val end = html.indexOf("</section>", start)
        val sec = html.substring(start, end + "</section>".length)
        assertFalse(Regex("\\busted\\b").containsMatchIn(sec), "must not use 'usted' (no formal Spanish)")
        assertFalse(Regex("\\bvosotros\\b").containsMatchIn(sec), "must not use 'vosotros' (no peninsular)")
    }

    // ── DAB-013 doc-link rendering ──────────────────────────────────────

    @Test
    fun `DAB-013 ActionStep docLink renders as anchor tag`() {
        val docUrl = "https://docs.unity3d.com/Manual/profiler.html"
        val html = generate(brief = DevActionBrief(items = listOf(sampleItem(docLink = docUrl))))
        assertTrue(
            html.contains("""href="$docUrl""""),
            "ActionStep.docLink must render as <a href=...> (got no anchor with $docUrl)",
        )
    }

    // ── DAB-008 nav-link ────────────────────────────────────────────────

    @Test
    fun `DAB-008 nav-link Accion Dev added as FIRST nav entry when brief non-empty`() {
        val html = generate(brief = DevActionBrief(items = listOf(sampleItem())))
        assertTrue(
            html.contains("""href="#sec-dev-action-brief""""),
            "nav must contain anchor to #sec-dev-action-brief",
        )
        // Validate ordering: dev-action nav-link before sec-summary nav-link in the topnav.
        val navIdx = html.indexOf("""href="#sec-dev-action-brief"""")
        val summaryNavIdx = html.indexOf("""href="#sec-summary"""")
        assertTrue(navIdx in 1 until summaryNavIdx, "Accion Dev nav-link must precede Resumen nav-link")
    }

    @Test
    fun `DAB-008 nav-link omitted when brief is empty`() {
        val html = generate(brief = DevActionBrief(items = emptyList()))
        assertFalse(
            html.contains("""href="#sec-dev-action-brief""""),
            "no nav-link when brief items empty",
        )
    }
}
