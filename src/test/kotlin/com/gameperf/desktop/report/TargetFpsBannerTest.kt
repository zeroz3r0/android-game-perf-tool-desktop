package com.gameperf.desktop.report

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v4.8.2 (engram #504) — tests for the explanatory banner that discloses
 * the auto-detected target FPS in the FPS section of the HTML report.
 *
 * The banner exists because the v4.8.2 threshold raise (60 floor 50 → 55,
 * 45 floor 38 → 42) made the detection more conservative. Without an
 * explanation the user sees a 30-FPS casual game graded A and wonders if
 * the system is lying. This banner makes the rationale visible.
 *
 * Coverage:
 *  - 30, 45, 60, 90, 120 each render with the matching rationale paragraph
 *  - The 30-FPS rationale explicitly states "objetivo normal en móvil casual"
 *  - The value rendered matches the targetFps argument
 *
 * @since v4.8.2
 */
class TargetFpsBannerTest {

    @Test
    fun `30 FPS target renders the casual-mobile-is-fine rationale`() {
        val html = ReportGenerator.targetFpsBanner(targetFps = 30, avgFps = 30, maxFps = 33)
        assertTrue(html.contains("30 FPS"), "must mention the target value verbatim")
        assertTrue(
            html.contains("objetivo normal en móvil casual"),
            "30-FPS rationale must explicitly normalise that 30 stable is fine on casual mobile",
        )
        assertTrue(
            html.contains("extraordinario"),
            "30-FPS rationale must mention that hitting 60 in casual would be extraordinary",
        )
    }

    @Test
    fun `45 FPS target renders the unity-auto rationale`() {
        val html = ReportGenerator.targetFpsBanner(targetFps = 45, avgFps = 44, maxFps = 47)
        assertTrue(html.contains("45 FPS"))
        assertTrue(html.contains("Unity Auto"), "45-FPS rationale must reference Unity Auto")
    }

    @Test
    fun `60 FPS target renders the action-game rationale`() {
        val html = ReportGenerator.targetFpsBanner(targetFps = 60, avgFps = 58, maxFps = 60)
        assertTrue(html.contains("60 FPS"))
        assertTrue(
            html.contains("targetFrameRate"),
            "60-FPS rationale must mention the Unity API the dev sets explicitly",
        )
    }

    @Test
    fun `90 FPS target renders the high-refresh-adaptive rationale`() {
        val html = ReportGenerator.targetFpsBanner(targetFps = 90, avgFps = 88, maxFps = 91)
        assertTrue(html.contains("90 FPS"))
        assertTrue(html.contains("90 Hz") || html.contains("Genshin"))
    }

    @Test
    fun `120 FPS target renders the competitive-shooter rationale`() {
        val html = ReportGenerator.targetFpsBanner(targetFps = 120, avgFps = 118, maxFps = 122)
        assertTrue(html.contains("120 FPS"))
        assertTrue(
            html.contains("competitive") || html.contains("alta frecuencia"),
            "120-FPS rationale must reference competitive / high refresh context",
        )
    }

    @Test
    fun `banner uses callout-info styling consistent with other report banners`() {
        val html = ReportGenerator.targetFpsBanner(targetFps = 30, avgFps = 30, maxFps = 30)
        assertTrue(html.contains("callout callout-info"), "must reuse the existing callout-info class")
        assertTrue(html.contains("target-fps-banner"), "must apply the dedicated styling class")
    }

    @Test
    fun `banner does not render hardcoded 60 FPS reference (regression for v4_8_2)`() {
        // Pre-v4.8.2 the FPS card-desc said "Objetivo: 60 FPS estable" hardcoded.
        // Now the objective is dynamic so the banner must NEVER mention 60
        // when the target is 30.
        val html = ReportGenerator.targetFpsBanner(targetFps = 30, avgFps = 30, maxFps = 30)
        assertFalse(
            html.contains("Objetivo: 60 FPS"),
            "30-FPS session must NOT show a 60-FPS hardcoded reference",
        )
    }
}
