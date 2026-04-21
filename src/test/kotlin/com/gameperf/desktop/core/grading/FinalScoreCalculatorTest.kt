package com.gameperf.desktop.core.grading

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for [FinalScoreCalculator].
 *
 * v4.3.4 extraction of the inline grading block that used to live in
 * `AppViewModel.startCapture` (lines 1228-1296). Changelog v4.2.7 explicitly
 * admitted "el grading no tiene tests propios todavia" — these tests close
 * that gap.
 *
 * Tests are organised by section:
 *   A — happy paths (grade A across multiple targets)
 *   B — p50 ratio boundaries (-35 / -20 / -8 buckets)
 *   C — p5 ratio boundaries (-15 / -6 buckets)
 *   D — jank ratio boundaries (-15 / -8 / -3 buckets)
 *   E — stutter / memory / thermal / CPU penalties
 *   F — letter-grade boundary mapping
 *   G — edge cases (targetFps=0, finalElapsed=0, all-penalties, insertion order)
 *
 * Behavior is byte-equivalent to the pre-v4.3.4 inline block. Castellano UI
 * messages are NOT translated and NOT rephrased — they are asserted verbatim.
 */
class FinalScoreCalculatorTest {

    /** Helper: build a clean, on-target input that scores 100 and grade A. */
    private fun perfect(targetFps: Int = 60): GradingInput = GradingInput(
        targetFps = targetFps,
        p50 = targetFps,
        p5 = targetFps,
        totalJank = 0L,
        finalElapsed = 60.0,
        totalStutter = 0,
        peakMem = 800,
        maxTempCpu = 35.0,
        avgCpu = 40,
    )

    // ─────────────────────────────────────────────────────────────────────
    // A — Happy paths
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `on-target 60fps stream with no issues yields score 100 grade A`() {
        val result = FinalScoreCalculator.compute(perfect(60))
        assertEquals(100, result.score)
        assertEquals('A', result.grade)
        assertTrue(result.problems.isEmpty(), "expected no problem messages, got ${result.problems}")
    }

    @Test
    fun `on-target 30fps stream yields score 100 grade A -- v4_2_6 proportional grading`() {
        // Pre-v4.2.6 a 30fps-target game with p50=30 was penalized -35 because
        // the threshold was hardcoded to a 60fps reference. v4.2.6 fixed it
        // by making the FPS ratios proportional to the inferred target.
        val result = FinalScoreCalculator.compute(perfect(30))
        assertEquals(100, result.score)
        assertEquals('A', result.grade)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `on-target 90fps stream and 120fps stream both yield A`() {
        val r90 = FinalScoreCalculator.compute(perfect(90))
        val r120 = FinalScoreCalculator.compute(perfect(120))
        assertEquals(100, r90.score); assertEquals('A', r90.grade)
        assertEquals(100, r120.score); assertEquals('A', r120.grade)
    }

    // ─────────────────────────────────────────────────────────────────────
    // B — p50 ratio boundaries
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `p50 at 84 percent of target applies -8 no problem message`() {
        // 60 * 0.84 = 50.4 → p50=50 → ratio 0.833 → falls into < 0.85 bucket → -8
        val input = perfect(60).copy(p50 = 50, p5 = 60)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(92, result.score)
        // The < 0.85 bucket has NO problem message, only a score deduction.
        assertTrue(result.problems.isEmpty(), "no message expected for the -8 bucket")
    }

    @Test
    fun `p50 at 69 percent applies -20 and adds problem`() {
        // 60 * 0.69 = 41.4 → p50=41 → ratio 0.683 → < 0.7 → -20 + message
        val input = perfect(60).copy(p50 = 41, p5 = 60)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(80, result.score)
        assertEquals(1, result.problems.size)
        assertEquals(
            "FPS mediana 41 vs target 60 - Se nota falta de fluidez en escenas con accion",
            result.problems[0],
        )
    }

    @Test
    fun `p50 at 49 percent applies -35 and adds problem`() {
        // 60 * 0.49 = 29.4 → p50=29 → ratio 0.483 → < 0.5 → -35 + message
        val input = perfect(60).copy(p50 = 29, p5 = 60)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(65, result.score)
        assertEquals(1, result.problems.size)
        assertEquals(
            "FPS mediana 29 vs target 60 - Muy bajo para una experiencia fluida",
            result.problems[0],
        )
    }

    @Test
    fun `p50 at exactly 85 percent applies NO penalty`() {
        // 60 * 0.85 = 51 → ratio = 51/60 = 0.85 → NOT < 0.85 → no penalty.
        val input = perfect(60).copy(p50 = 51, p5 = 60)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(100, result.score)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `p50 at exactly 70 percent applies -8 not -20 (boundary)`() {
        // 60 * 0.70 = 42 → ratio = 42/60 = 0.70 → NOT < 0.70 but IS < 0.85 → -8
        val input = perfect(60).copy(p50 = 42, p5 = 60)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(92, result.score)
        assertTrue(result.problems.isEmpty(), "the -8 bucket has no message")
    }

    @Test
    fun `p50 at exactly 50 percent applies -20 not -35 (boundary)`() {
        // 60 * 0.50 = 30 → ratio = 30/60 = 0.50 → NOT < 0.50 but IS < 0.70 → -20
        val input = perfect(60).copy(p50 = 30, p5 = 60)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(80, result.score)
        assertEquals(1, result.problems.size)
        assertEquals(
            "FPS mediana 30 vs target 60 - Se nota falta de fluidez en escenas con accion",
            result.problems[0],
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // C — p5 ratio boundaries
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `p5 at 59 percent of target applies -6`() {
        // 60 * 0.59 = 35.4 → p5=35 → ratio 0.583 → < 0.6 → -6, no message
        val input = perfect(60).copy(p5 = 35)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(94, result.score)
        assertTrue(result.problems.isEmpty(), "the -6 p5 bucket has no message")
    }

    @Test
    fun `p5 at 39 percent of target applies -15 and adds problem`() {
        // 60 * 0.39 = 23.4 → p5=23 → ratio 0.383 → < 0.4 → -15 + message
        val input = perfect(60).copy(p5 = 23)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(85, result.score)
        assertEquals(1, result.problems.size)
        assertEquals(
            "P5 FPS: 23 - Caidas severas que causan congelaciones visibles",
            result.problems[0],
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // D — Jank ratio
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `jankRatio above 20 percent applies -15 and adds problem`() {
        // approxTotalFrames = 60.0 * 60 = 3600 → jank=800 → ratio 0.222 → > 0.20
        val input = perfect(60).copy(totalJank = 800L)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(85, result.score)
        assertEquals(1, result.problems.size)
        // The percentage is computed as (ratio * 100).toInt(); 0.2222 * 100 = 22.22 → 22
        assertEquals(
            "800 frames con jank (22% de la sesion) - Falta de fluidez perceptible",
            result.problems[0],
        )
    }

    @Test
    fun `jankRatio between 10 and 20 percent applies -8`() {
        // 3600 frames, jank=540 → ratio 0.15 → > 0.10, ≤ 0.20 → -8, no message
        val input = perfect(60).copy(totalJank = 540L)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(92, result.score)
        assertTrue(result.problems.isEmpty(), "the -8 jank bucket has no message")
    }

    @Test
    fun `jankRatio between 5 and 10 percent applies -3`() {
        // 3600 frames, jank=270 → ratio 0.075 → > 0.05, ≤ 0.10 → -3
        val input = perfect(60).copy(totalJank = 270L)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(97, result.score)
        assertTrue(result.problems.isEmpty(), "the -3 jank bucket has no message")
    }

    @Test
    fun `jankRatio at exactly 5 percent applies NO penalty`() {
        // 3600 frames, jank=180 → ratio 0.05 → NOT > 0.05 → no penalty
        val input = perfect(60).copy(totalJank = 180L)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(100, result.score)
        assertTrue(result.problems.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────
    // E — Stutter / memory / thermal / CPU
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `totalStutter greater than 5 applies -10 and adds problem`() {
        val input = perfect(60).copy(totalStutter = 6)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(90, result.score)
        assertEquals(1, result.problems.size)
        assertEquals("6 freezes visibles (frames > 100ms) durante la sesion", result.problems[0])
    }

    @Test
    fun `totalStutter of exactly 5 applies NO penalty`() {
        val input = perfect(60).copy(totalStutter = 5)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(100, result.score)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `peakMem above 2000 applies -12 with peak memory problem`() {
        val input = perfect(60).copy(peakMem = 2100)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(88, result.score)
        assertEquals(1, result.problems.size)
        assertEquals(
            "Pico de memoria 2100MB - Riesgo de cierre forzado en dispositivos con poca RAM",
            result.problems[0],
        )
    }

    @Test
    fun `peakMem between 1500 and 2000 applies -6 with memoria alta problem`() {
        val input = perfect(60).copy(peakMem = 1800)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(94, result.score)
        assertEquals(1, result.problems.size)
        assertEquals("Memoria alta: 1800MB", result.problems[0])
    }

    @Test
    fun `peakMem at exactly 2000 falls into elif (-6 not -12)`() {
        // 2000 is NOT > 2000 → falls into the elif > 1500 branch → -6, "Memoria alta"
        val input = perfect(60).copy(peakMem = 2000)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(94, result.score)
        assertEquals(1, result.problems.size)
        assertEquals("Memoria alta: 2000MB", result.problems[0])
    }

    @Test
    fun `maxTempCpu above 45 applies -12 and adds thermal throttling problem`() {
        // 47.8 → toInt() drops decimals → "47C"
        val input = perfect(60).copy(maxTempCpu = 47.8)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(88, result.score)
        assertEquals(1, result.problems.size)
        assertEquals(
            "Temperatura CPU 47C - Thermal throttling activo, reduce rendimiento",
            result.problems[0],
        )
    }

    @Test
    fun `avgCpu above 85 applies -12 and adds bottleneck problem`() {
        val input = perfect(60).copy(avgCpu = 92)
        val result = FinalScoreCalculator.compute(input)
        assertEquals(88, result.score)
        assertEquals(1, result.problems.size)
        assertEquals("CPU saturada al 92% - Cuello de botella principal", result.problems[0])
    }

    // ─────────────────────────────────────────────────────────────────────
    // F — Letter grade boundaries
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `score 85 maps to A`() {
        // p5 at 23 → -15 → score 85 → A boundary
        val input = perfect(60).copy(p5 = 23)
        val r = FinalScoreCalculator.compute(input)
        assertEquals(85, r.score)
        assertEquals('A', r.grade)
    }

    @Test
    fun `score 84 maps to B, score 70 maps to B, score 69 maps to C`() {
        // 84: -8 (p50<0.85) + -8 (jank 10-20%) = -16 → 84
        val r84 = FinalScoreCalculator.compute(perfect(60).copy(p50 = 50, totalJank = 540L))
        assertEquals(84, r84.score); assertEquals('B', r84.grade)

        // 70: -20 (p50<0.7) + -10 (stutter) = -30 → 70
        val r70 = FinalScoreCalculator.compute(perfect(60).copy(p50 = 41, totalStutter = 6))
        assertEquals(70, r70.score); assertEquals('B', r70.grade)

        // 69: -15 (p5<0.4) + -10 (stutter) + -6 (memoria>1500) = -31
        val r69 = FinalScoreCalculator.compute(perfect(60).copy(p5 = 23, totalStutter = 6, peakMem = 1800))
        assertEquals(69, r69.score); assertEquals('C', r69.grade)
    }

    @Test
    fun `score 55 maps to C, score 54 maps to D, score 40 maps to D, score 39 maps to F`() {
        // 55: -35 (p50<0.5) + -10 (stutter) = -45 → 55
        val r55 = FinalScoreCalculator.compute(perfect(60).copy(p50 = 29, totalStutter = 6))
        assertEquals(55, r55.score); assertEquals('C', r55.grade)

        // 54: -8 (p50<0.85) + -6 (p5<0.6) + -8 (jank 10-20%) + -12 (thermal>45) + -12 (cpu>85) = -46
        val r54 = FinalScoreCalculator.compute(
            perfect(60).copy(p50 = 50, p5 = 35, totalJank = 540L, maxTempCpu = 47.0, avgCpu = 92)
        )
        assertEquals(54, r54.score); assertEquals('D', r54.grade)

        // 40: need -60. -35 (p50) + -15 (p5) + -10 (stutter) = -60 → 40
        val r40 = FinalScoreCalculator.compute(
            perfect(60).copy(p50 = 29, p5 = 23, totalStutter = 6)
        )
        assertEquals(40, r40.score); assertEquals('D', r40.grade)

        // 39: -20 (p50<0.7) + -15 (p5<0.4) + -8 (jank 10-20%) + -6 (memoria>1500) + -12 (thermal>45) = -61
        val r39 = FinalScoreCalculator.compute(
            perfect(60).copy(p50 = 41, p5 = 23, totalJank = 540L, peakMem = 1800, maxTempCpu = 47.0)
        )
        assertEquals(39, r39.score); assertEquals('F', r39.grade)
    }

    // ─────────────────────────────────────────────────────────────────────
    // G — Edge cases
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `targetFps=0 makes both ratios default to 1_0 no FPS penalty`() {
        // Pathological: target couldn't be inferred. Both ratios fall back to 1.0
        // so neither the p50 nor the p5 buckets fire. Other penalties still work.
        val input = GradingInput(
            targetFps = 0,
            p50 = 0,
            p5 = 0,
            totalJank = 0L,
            finalElapsed = 60.0,
            totalStutter = 0,
            peakMem = 800,
            maxTempCpu = 35.0,
            avgCpu = 40,
        )
        val r = FinalScoreCalculator.compute(input)
        assertEquals(100, r.score)
        assertEquals('A', r.grade)
        assertTrue(r.problems.isEmpty())
    }

    @Test
    fun `finalElapsed=0 keeps approxTotalFrames at minimum 1 no division by zero`() {
        // approxTotalFrames = (0.0 * 60).toLong().coerceAtLeast(1) = 1
        // jankRatio = 0L / 1 = 0.0 → no penalty. The important check is that
        // compute() does NOT throw an ArithmeticException.
        val input = perfect(60).copy(finalElapsed = 0.0)
        val r = FinalScoreCalculator.compute(input)
        assertEquals(100, r.score)
        assertEquals('A', r.grade)
    }

    @Test
    fun `all penalties firing yields negative score mapping to F preserves pre-v4_3_4 behavior`() {
        // Worst case: every bucket triggers. Score must NOT be clamped to >= 0.
        val input = GradingInput(
            targetFps = 60,
            p50 = 10,         // -35 (p50 ratio 0.166 < 0.5)
            p5 = 5,           // -15 (p5 ratio 0.083 < 0.4)
            totalJank = 1000L, // 1000 / 3600 = 0.277 > 0.20 → -15
            finalElapsed = 60.0,
            totalStutter = 100, // -10
            peakMem = 3000,     // -12
            maxTempCpu = 60.0,  // -12
            avgCpu = 99,        // -12
        )
        val r = FinalScoreCalculator.compute(input)
        // 100 - 35 - 15 - 15 - 10 - 12 - 12 - 12 = -11
        assertEquals(-11, r.score)
        assertEquals('F', r.grade)
        assertFalse(r.score >= 0, "score must be allowed to go negative — this is not a bug")
    }

    @Test
    fun `problems list preserves insertion order FPS then P5 then jank then stutter then mem then thermal then cpu`() {
        // Trip every bucket that emits a message and assert the EXACT order.
        // ReportGenerator iterates `problems` as-is, so insertion order is part
        // of the public contract.
        val input = GradingInput(
            targetFps = 60,
            p50 = 10,           // -35, message #1 ("FPS mediana ...")
            p5 = 5,             // -15, message #2 ("P5 FPS: ...")
            totalJank = 1000L,  // > 0.20 → message #3 ("... frames con jank ...")
            finalElapsed = 60.0,
            totalStutter = 10,  // > 5 → message #4 ("... freezes visibles ...")
            peakMem = 2500,     // > 2000 → message #5 ("Pico de memoria ...")
            maxTempCpu = 50.0,  // > 45 → message #6 ("Temperatura CPU ...")
            avgCpu = 95,        // > 85 → message #7 ("CPU saturada ...")
        )
        val r = FinalScoreCalculator.compute(input)
        assertEquals(7, r.problems.size, "expected 7 problem messages, got ${r.problems.size}: ${r.problems}")
        assertTrue(r.problems[0].startsWith("FPS mediana"), "msg #1 must be FPS, got '${r.problems[0]}'")
        assertTrue(r.problems[1].startsWith("P5 FPS:"), "msg #2 must be P5, got '${r.problems[1]}'")
        assertTrue(r.problems[2].contains("frames con jank"), "msg #3 must be jank, got '${r.problems[2]}'")
        assertTrue(r.problems[3].contains("freezes visibles"), "msg #4 must be stutter, got '${r.problems[3]}'")
        assertTrue(r.problems[4].startsWith("Pico de memoria"), "msg #5 must be peak memory, got '${r.problems[4]}'")
        assertTrue(r.problems[5].startsWith("Temperatura CPU"), "msg #6 must be thermal, got '${r.problems[5]}'")
        assertTrue(r.problems[6].startsWith("CPU saturada"), "msg #7 must be CPU bottleneck, got '${r.problems[6]}'")
    }
}
