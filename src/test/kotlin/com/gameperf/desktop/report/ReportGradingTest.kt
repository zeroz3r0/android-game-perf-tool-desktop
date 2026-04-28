package com.gameperf.desktop.report

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure unit tests for the metric-card grading helpers extracted from
 * `ReportGenerator.generate` in v4.3.6. These cover Path C of the dual-grading
 * fix described in `sdd/grading-thermal-realism/explore`.
 *
 * Pre-v4.3.6 the FPS metric card used `metricGrade(avgFps, 55, 45, 30, 20)` —
 * baked-in 60-fps thresholds. A 30-fps Unity game on a Galaxy S23 hit p50=30
 * → "C" on the FPS card, "B" on the frame-time card. The fix: normalise both
 * metrics by `targetFps` BEFORE feeding them to the bracket grader so a 30-fps
 * game at target produces the same A grade as a 60-fps game at target.
 */
class ReportGradingTest {

    // ── FPS card ─────────────────────────────────────────────────────────

    @Test
    fun `fpsCardGrade scores 30fps at target as A`() {
        // 30 / 30 = 1.0 → ratio 60 == hit-the-yardstick → A.
        assertEquals('A', ReportGrading.fpsCardGrade(avgFps = 30, targetFps = 30))
    }

    @Test
    fun `fpsCardGrade scores 60fps at target as A`() {
        // Pre-v4.3.6 reference: 60fps target, 60fps actual → A.
        assertEquals('A', ReportGrading.fpsCardGrade(avgFps = 60, targetFps = 60))
    }

    @Test
    fun `fpsCardGrade scores 90fps at target as A`() {
        assertEquals('A', ReportGrading.fpsCardGrade(avgFps = 90, targetFps = 90))
    }

    @Test
    fun `fpsCardGrade is proportional - same ratio same grade`() {
        // 30/30 and 60/60 are the SAME ratio (1.0). Must produce the SAME grade.
        // This is the core property that broke pre-v4.3.6.
        assertEquals(
            ReportGrading.fpsCardGrade(avgFps = 60, targetFps = 60),
            ReportGrading.fpsCardGrade(avgFps = 30, targetFps = 30),
        )
    }

    @Test
    fun `fpsCardGrade penalises severe drops below target`() {
        // 15fps on a 30fps target = 50% of target → C bracket
        // (matches pre-v4.3.6 behavior of metricGrade(30, ...) = "30 normalized to 60 yardstick" = "30" → C)
        val grade = ReportGrading.fpsCardGrade(avgFps = 15, targetFps = 30)
        assert(grade == 'C' || grade == 'D' || grade == 'F') {
            "15fps on 30fps target should score C/D/F, got $grade"
        }
    }

    @Test
    fun `fpsCardGrade falls back gracefully when targetFps is 0`() {
        // Pathological — should not divide by zero. Falls back to non-proportional
        // grading against 60.
        val grade = ReportGrading.fpsCardGrade(avgFps = 30, targetFps = 0)
        // Pre-v4.3.6 behavior: metricGrade(30, 55, 45, 30, 20) = C.
        assertEquals('C', grade)
    }

    // ── Frame-time card ──────────────────────────────────────────────────

    @Test
    fun `frameTimeCardGrade scores 33ms at 30fps target as A`() {
        // 30fps target → target ms = 33.33. avg=33.3 hits target → A.
        assertEquals('A', ReportGrading.frameTimeCardGrade(avgFrameTime = 33.3, targetFps = 30))
    }

    @Test
    fun `frameTimeCardGrade scores 16_67ms at 60fps target as A`() {
        // Pre-v4.3.6 reference.
        assertEquals('A', ReportGrading.frameTimeCardGrade(avgFrameTime = 16.67, targetFps = 60))
    }

    @Test
    fun `frameTimeCardGrade is proportional - same ratio same grade`() {
        // 33.3ms on 30fps target == 16.67ms on 60fps target — same ratio.
        assertEquals(
            ReportGrading.frameTimeCardGrade(avgFrameTime = 16.67, targetFps = 60),
            ReportGrading.frameTimeCardGrade(avgFrameTime = 33.3, targetFps = 30),
        )
    }

    @Test
    fun `frameTimeCardGrade penalises 1_5x target as B or worse`() {
        // 50ms (1.5x of 33.3ms target) → B at the most generous, more likely C.
        val grade = ReportGrading.frameTimeCardGrade(avgFrameTime = 50.0, targetFps = 30)
        assert(grade in listOf('B', 'C', 'D')) {
            "50ms (1.5x target) should score B/C/D, got $grade"
        }
    }

    @Test
    fun `frameTimeCardGrade penalises 1_8x target as D or worse`() {
        // 60ms (1.8x of 33.3ms target).
        val grade = ReportGrading.frameTimeCardGrade(avgFrameTime = 60.0, targetFps = 30)
        assert(grade in listOf('C', 'D', 'F')) {
            "60ms (1.8x target) should score C/D/F, got $grade"
        }
    }

    @Test
    fun `frameTimeCardGrade falls back gracefully when targetFps is 0`() {
        // Falls back to 60fps reference.
        val grade = ReportGrading.frameTimeCardGrade(avgFrameTime = 16.67, targetFps = 0)
        assertEquals('A', grade)
    }

    @Test
    fun `frameTimeCardGrade returns A when avgFrameTime is 0 (no data)`() {
        // 0ms = no frames captured. Don't penalise — match pre-v4.3.6 maxTempCpu pattern.
        val grade = ReportGrading.frameTimeCardGrade(avgFrameTime = 0.0, targetFps = 60)
        assertEquals('A', grade)
    }
}
