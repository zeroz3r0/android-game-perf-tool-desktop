package com.gameperf.desktop.core.grading

/**
 * Inputs required to compute the final session grade. All values are pre-computed
 * by [com.gameperf.desktop.viewmodel.AppViewModel] from the session histories
 * (FPS, memory, CPU, thermal) so this calculator stays platform-agnostic and
 * trivially testable.
 *
 * **IMPORTANT (v4.4.0+):** Values must be filtered upstream by
 * [com.gameperf.desktop.core.metrics.FilteredMetricsCalculator] when auto event
 * detection is enabled. Raw whole-session aggregates should NOT be passed here
 * because ad-induced FPS spikes contaminate the score. The orchestrator in
 * [com.gameperf.desktop.viewmodel.AppViewModel] already does this routing via
 * `FilteredMetricsCalculator.computeWithFallback(...)`.
 *
 * @see com.gameperf.desktop.core.metrics.FilteredMetricsCalculator.computeWithFallback
 *
 * @property targetFps inferred game target (see `inferGameTargetFps`). Zero is
 *   tolerated — both FPS ratios fall back to 1.0 to avoid division by zero.
 * @property p50 median FPS across the session (sorted history index n*0.5).
 * @property p5 5th-percentile FPS across the session (sorted history index n*0.05).
 * @property totalJank per-game jank frames (counted with dynamic 1.5x target threshold,
 *   see v4.2.7 reliability fix in CLAUDE.md).
 * @property finalElapsed session duration in seconds (used to approximate frame total
 *   for the jank ratio normalization).
 * @property totalStutter frames whose render time exceeded 100ms (visible freezes).
 * @property peakMem peak PSS App Summary in MB.
 * @property maxTempCpu max user-facing CPU temperature observed (°C). v4.3.6:
 *   semantically this is the SKIN temperature when the device exposes a skin
 *   sensor; otherwise it falls back to die-CPU. The threshold for firing the
 *   thermal penalty is 45°C (skin throttle).
 * @property avgCpu average CPU% across the session, scoped to the game pid (per-process,
 *   see v4.2.5 reliability fix).
 * @property peakThermalDie max CPU die (silicon junction) temperature observed (°C).
 *   v4.3.6: separate input from [maxTempCpu]. Threshold 95°C (die throttle).
 *   Fires ONLY when [maxTempCpu] did not already trigger the skin penalty —
 *   prevents double-counting when both indicators are simultaneously high.
 *   Defaults to 0.0 so existing call sites that haven't been migrated remain
 *   byte-equivalent to pre-v4.3.6.
 */
data class GradingInput(
    val targetFps: Int,
    val p50: Int,
    val p5: Int,
    val totalJank: Long,
    val finalElapsed: Double,
    val totalStutter: Int,
    val peakMem: Long,
    val maxTempCpu: Double,
    val avgCpu: Int,
    val peakThermalDie: Double = 0.0,
)

/**
 * Output of [FinalScoreCalculator.compute].
 *
 * @property score raw score in points. STARTS at 100 and accumulates penalties.
 *   CAN GO NEGATIVE if every penalty fires — by design, see CLAUDE.md.
 * @property grade letter mapping derived from [score] (A/B/C/D/F).
 * @property problems immutable list of castellano-formal user-facing messages,
 *   in the same insertion order as the legacy inline block. The order is part
 *   of the contract because `ReportGenerator` iterates problems as-is.
 */
data class GradingResult(
    val score: Int,
    val grade: Char,
    val problems: List<String>,
)

/**
 * Pure grading logic extracted from `AppViewModel.startCapture` (v4.3.4). Behavior
 * is byte-equivalent to the inline block that lived at lines 1228-1296 before
 * the extraction. See CLAUDE.md "Tests puros sin mocks" rule.
 *
 * Penalty thresholds (full table is documented in the apply-progress engram entry
 * `sdd/grading-tests/apply-progress`):
 *
 *  - p50 ratio: -35 / -20 / -8 buckets (v4.2.6 proportional grading)
 *  - p5 ratio: -15 / -6 buckets
 *  - jank ratio: -15 / -8 / -3 buckets (v4.2.7 per-game ratio)
 *  - stutter > 5 frames: -10
 *  - peak memory > 2000MB: -12, > 1500MB elif: -6
 *  - thermal > 45°C: -12
 *  - avg CPU > 85%: -12
 *
 * Letter mapping: >=85 A, >=70 B, >=55 C, >=40 D, else F.
 *
 * No clamping is applied to [GradingResult.score] — a session that triggers every
 * penalty WILL produce a negative score and an F grade. That is intentional and
 * preserved across the v4.3.4 extraction.
 */
object FinalScoreCalculator {
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun compute(input: GradingInput): GradingResult {
        val targetFps = input.targetFps
        val p50 = input.p50
        val p5 = input.p5
        val totalJank = input.totalJank
        val finalElapsed = input.finalElapsed
        val totalStutter = input.totalStutter
        val peakMem = input.peakMem
        val maxTempCpu = input.maxTempCpu
        val avgCpu = input.avgCpu

        val problems = mutableListOf<String>()
        var score = 100
        // FPS median scoring proportional to the game's target.
        // p50 ≥ 85% of target = on-target, no penalty.
        // p50 < 85% but ≥ 70% = small penalty (8 pts).
        // p50 < 70% but ≥ 50% = medium penalty (20 pts).
        // p50 < 50% = severe (35 pts), the game is broken at its own target.
        val p50Ratio = if (targetFps > 0) p50.toDouble() / targetFps else 1.0
        when {
            p50Ratio < 0.5 -> {
                score -= 35
                problems.add("FPS mediana $p50 vs target ${targetFps} - Muy bajo para una experiencia fluida")
            }
            p50Ratio < 0.7 -> {
                score -= 20
                problems.add("FPS mediana $p50 vs target ${targetFps} - Se nota falta de fluidez en escenas con accion")
            }
            p50Ratio < 0.85 -> score -= 8
        }
        // P5 scoring proportional to target. p5 ≥ 60% of target = OK.
        // Below 40% of target = severe drops; between 40-60% = mild.
        val p5Ratio = if (targetFps > 0) p5.toDouble() / targetFps else 1.0
        when {
            p5Ratio < 0.4 -> {
                score -= 15
                problems.add("P5 FPS: $p5 - Caidas severas que causan congelaciones visibles")
            }
            p5Ratio < 0.6 -> score -= 6
        }
        // Jank as a per-game ratio (v4.2.7). Normalized so long sessions aren't
        // unfairly penalized just for accumulating jank over time.
        val approxTotalFrames = (finalElapsed * targetFps).toLong().coerceAtLeast(1)
        val jankRatio = totalJank.toDouble() / approxTotalFrames
        when {
            jankRatio > 0.20 -> {
                score -= 15
                problems.add("$totalJank frames con jank (${(jankRatio * 100).toInt()}% de la sesion) - Falta de fluidez perceptible")
            }
            jankRatio > 0.10 -> score -= 8
            jankRatio > 0.05 -> score -= 3
        }
        // Stutter penalty (frames > 100ms = visible freezes regardless of target).
        if (totalStutter > 5) {
            score -= 10
            problems.add("$totalStutter freezes visibles (frames > 100ms) durante la sesion")
        }
        if (peakMem > 2000) { score -= 12; problems.add("Pico de memoria ${peakMem}MB - Riesgo de cierre forzado en dispositivos con poca RAM") }
        else if (peakMem > 1500) { score -= 6; problems.add("Memoria alta: ${peakMem}MB") }
        // v4.3.6 — dual thermal threshold:
        //   - Skin path: legacy `maxTempCpu > 45` fires -12 + skin-throttle message.
        //   - Die path:  fires ONLY when skin did NOT already fire AND die > 95°C.
        //                Prevents double-counting when both rails are hot.
        val skinFired = maxTempCpu > 45
        if (skinFired) {
            score -= 12
            problems.add("Temperatura CPU ${maxTempCpu.toInt()}C - Thermal throttling activo, reduce rendimiento")
        } else if (input.peakThermalDie > 95) {
            score -= 12
            problems.add("Temperatura die CPU ${input.peakThermalDie.toInt()}C - Throttling severo a nivel de silicio")
        }
        if (avgCpu > 85) { score -= 12; problems.add("CPU saturada al ${avgCpu}% - Cuello de botella principal") }
        val grade = when { score >= 85 -> 'A'; score >= 70 -> 'B'; score >= 55 -> 'C'; score >= 40 -> 'D'; else -> 'F' }

        return GradingResult(score = score, grade = grade, problems = problems.toList())
    }
}
