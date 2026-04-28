package com.gameperf.desktop.report

/**
 * Pure metric-card grading helpers used by [ReportGenerator]. Path C of the
 * v4.3.6 dual-grading-system fix described in
 * `sdd/grading-thermal-realism/explore`.
 *
 * Pre-v4.3.6 the FPS metric card called `metricGrade(avgFps, 55, 45, 30, 20)`
 * — a hardcoded 60-fps yardstick. A 30-fps Unity-vsync game on a Galaxy S23
 * was scored "C" on the FPS card and "B" on the frame-time card even though
 * it WAS hitting its own target. The fix is to normalise both inputs by
 * `targetFps` BEFORE bracket-grading.
 *
 * Both helpers return `'A'` for the no-data sentinel (avgFps==0 or
 * avgFrameTime==0.0) so the report doesn't visually scream "F" at the user
 * just because the capture failed.
 *
 * Pure object — no I/O, no UI dependencies, trivially unit-testable.
 */
internal object ReportGrading {

    /** Bracket grade with the same letter mapping the inline ReportGenerator uses. */
    private fun bracket(normalized: Int, a: Int, b: Int, c: Int, d: Int): Char = when {
        normalized >= a -> 'A'
        normalized >= b -> 'B'
        normalized >= c -> 'C'
        normalized >= d -> 'D'
        else -> 'F'
    }

    /**
     * Grade for the FPS metric card.
     *
     * The math: scale `avgFps` to a "60-fps yardstick" via `avgFps * 60 / targetFps`
     * and re-use the legacy [55, 45, 30, 20] brackets. A 30-fps target game at
     * p50=30 scales to 60 → A. A 60-fps target game at p50=30 stays at 30 → C.
     *
     * Falls back to the legacy brackets (no normalisation) when `targetFps <= 0`
     * to preserve back-compat for sessions where the inferer never ran.
     */
    fun fpsCardGrade(avgFps: Int, targetFps: Int): Char {
        if (avgFps <= 0) return 'A'  // sentinel: no data
        if (targetFps <= 0) {
            // pre-v4.3.6 behavior — 60-fps reference
            return bracket(avgFps, 55, 45, 30, 20)
        }
        val normalised = (avgFps.toDouble() * 60.0 / targetFps).toInt()
        return bracket(normalised, 55, 45, 30, 20)
    }

    /**
     * Grade for the frame-time metric card.
     *
     * The math: target frame time = `1000 / targetFps`. A grade is assigned
     * by how many multiples of target the actual avgFrameTime is:
     *  - ≤ 1.0x target → A
     *  - ≤ 1.3x       → B
     *  - ≤ 1.5x       → C
     *  - ≤ 1.8x       → D
     *  - else         → F
     *
     * On a 30-fps target (33.3ms) and avgFrameTime=33.3 → ratio 1.0 → A.
     * On a 60-fps target (16.67ms) and avgFrameTime=33.3 → ratio 2.0 → F.
     *
     * Falls back to a 60-fps reference (16.67ms) when targetFps ≤ 0.
     */
    fun frameTimeCardGrade(avgFrameTime: Double, targetFps: Int): Char {
        if (avgFrameTime <= 0.0) return 'A'  // sentinel: no data
        val targetMs = if (targetFps > 0) 1000.0 / targetFps else 1000.0 / 60.0
        val ratio = avgFrameTime / targetMs
        // 1.05 buffer on the A bracket: at-target frames jitter ±5% on real hardware
        // and we don't want a 16.67ms-on-60fps measurement to score B just because
        // 16.67/16.6666 = 1.0002. The downstream brackets stay strict.
        return when {
            ratio <= 1.05 -> 'A'
            ratio <= 1.3 -> 'B'
            ratio <= 1.5 -> 'C'
            ratio <= 1.8 -> 'D'
            else -> 'F'
        }
    }
}
