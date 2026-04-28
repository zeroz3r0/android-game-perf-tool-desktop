package com.gameperf.desktop

import com.gameperf.desktop.core.HardwareScoring
import com.gameperf.desktop.core.HardwareScoring.DeviceTier
import kotlin.test.Test
import kotlin.test.assertEquals

class HardwareScoringTest {

    // ===== detectTier =====

    @Test
    fun `detectTier returns ULTRA_HIGH for Adreno 830`() {
        assertEquals(DeviceTier.ULTRA_HIGH, HardwareScoring.detectTier("Adreno 830"))
    }

    @Test
    fun `detectTier returns HIGH for Mali-G710`() {
        assertEquals(DeviceTier.HIGH, HardwareScoring.detectTier("ARM Mali-G710 MC10"))
    }

    @Test
    fun `detectTier returns MID for Adreno 619`() {
        assertEquals(DeviceTier.MID, HardwareScoring.detectTier("Adreno 619"))
    }

    @Test
    fun `detectTier strips TM suffix and matches correctly`() {
        // v3.1.10: real Android devices report `Adreno (TM) 530`, `Adreno (TM) 619`, etc.
        // The pre-3.1.10 code did a naive lowercase + contains() check and the TM suffix
        // broke every match, sending real devices to UNKNOWN tier (Pixel XL Adreno 530 →
        // UNKNOWN → score 20/100 instead of the correct LOWER_MID). Fixed by normalizing
        // the GPU string before the lookup.
        assertEquals(DeviceTier.MID, HardwareScoring.detectTier("Adreno (TM) 619"))
    }

    @Test
    fun `detectTier matches Pixel XL real GPU string`() {
        // Verbatim from a Pixel XL (Snapdragon 821) session report.
        val real = "Qualcomm, Adreno (TM) 530, OpenGL ES 3.2 V@384.0 (GIT@4a00b6"
        assertEquals(DeviceTier.LOWER_MID, HardwareScoring.detectTier(real))
    }

    @Test
    fun `detectTier handles R trademark suffix`() {
        assertEquals(DeviceTier.HIGH, HardwareScoring.detectTier("Adreno(R) 740"))
    }

    @Test
    fun `detectTier normalizes multiple commas and whitespace`() {
        assertEquals(DeviceTier.HIGH, HardwareScoring.detectTier("ARM,  Mali-G710  ,  MC10"))
    }

    @Test
    fun `detectTier returns LOW for Mali-400`() {
        assertEquals(DeviceTier.LOW, HardwareScoring.detectTier("Mali-400 MP2"))
    }

    @Test
    fun `detectTier returns UNKNOWN for empty string`() {
        assertEquals(DeviceTier.UNKNOWN, HardwareScoring.detectTier(""))
    }

    @Test
    fun `detectTier returns UNKNOWN for unrecognized GPU`() {
        assertEquals(DeviceTier.UNKNOWN, HardwareScoring.detectTier("SomeNewGPU 9000"))
    }

    @Test
    fun `detectTier is case insensitive`() {
        assertEquals(DeviceTier.ULTRA_HIGH, HardwareScoring.detectTier("ADRENO 830"))
        assertEquals(DeviceTier.HIGH, HardwareScoring.detectTier("MALI-G710"))
    }

    @Test
    fun `detectTier handles leading and trailing whitespace`() {
        assertEquals(DeviceTier.HIGH, HardwareScoring.detectTier("  Adreno 740  "))
    }

    // ===== calculateDeviceGrade =====

    @Test
    fun `calculateDeviceGrade returns A for high FPS on mid device`() {
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 50, p1Fps = 40, tier = DeviceTier.MID, problems = emptyList()
        )
        assertEquals('A', grade)
        assert(score >= 85) { "Score $score should be >= 85 for grade A" }
    }

    @Test
    fun `calculateDeviceGrade returns poor grade for low FPS on ultra high device`() {
        val (grade, _) = HardwareScoring.calculateDeviceGrade(
            avgFps = 25, p1Fps = 15, tier = DeviceTier.ULTRA_HIGH, problems = emptyList()
        )
        assert(grade == 'D' || grade == 'F') { "Grade should be D or F, was $grade" }
    }

    @Test
    fun `calculateDeviceGrade penalizes thermal problems`() {
        val (_, scoreClean) = HardwareScoring.calculateDeviceGrade(
            avgFps = 60, p1Fps = 55, tier = DeviceTier.HIGH, problems = emptyList()
        )
        val (_, scoreWithProblems) = HardwareScoring.calculateDeviceGrade(
            avgFps = 60, p1Fps = 55, tier = DeviceTier.HIGH,
            problems = listOf("Temperatura CPU 48C - Thermal throttling activo")
        )
        assert(scoreWithProblems < scoreClean) { "Score with thermal problem ($scoreWithProblems) should be < clean ($scoreClean)" }
    }

    @Test
    fun `calculateDeviceGrade score is clamped between 0 and 100`() {
        val (_, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 5, p1Fps = 2, tier = DeviceTier.ULTRA_HIGH,
            problems = listOf(
                "Temperatura CPU 50C", "Memoria alta: 3000MB",
                "CPU saturada al 95%", "100 frames perdidos"
            )
        )
        assert(score in 0..100) { "Score $score should be in 0..100" }
    }

    @Test
    fun `calculateDeviceGrade boundary - score 85 is grade A`() {
        // Perfect scenario for UNKNOWN tier (v3.1.11: expected 45fps, not 60)
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 60, p1Fps = 30, tier = DeviceTier.UNKNOWN, problems = emptyList()
        )
        assertEquals('A', grade)
        assert(score >= 85) { "Score $score should be >= 85" }
    }

    // ===== v3.1.11: real-device regression tests =====
    //
    // These tests lock in the bug #3 fix from the user complaint: "el Huawei Y5 Lite
    // a 28-30 fps muy estables le ha dado D, eso seria una A o B". The previous
    // scoring was too strict in two ways:
    //   1. The PowerVR Rogue GE8300 GPU string didn't match `powervr ge8300` because
    //      of the `rogue ` infix
    //   2. The middle-bracket FPS penalty was too aggressive (-15 just for being below
    //      expected, regardless of how close)
    //   3. UNKNOWN tier defaulted to 60/30 (flagship-class expectations) which made
    //      every unrecognized GPU look terrible
    // All three are now fixed. These tests catch any future regression.

    @Test
    fun `detectTier matches Huawei Y5 Lite real GPU string with Rogue infix`() {
        // Verbatim from a Huawei Y5 Lite (AMN-LX9, MT6739) session report.
        // The "Rogue" between "PowerVR" and "GE8300" was breaking the substring match
        // in v3.1.10. v3.1.11 strips the family qualifier before lookup.
        val real = "Imagination Technologies, PowerVR Rogue GE8300, OpenGL ES 3.2"
        assertEquals(DeviceTier.LOW, HardwareScoring.detectTier(real))
    }

    @Test
    fun `detectTier matches PowerVR variants with brand prefix`() {
        // Various ways vendors report PowerVR GPUs.
        assertEquals(DeviceTier.LOW, HardwareScoring.detectTier("PowerVR Rogue GE8300"))
        assertEquals(DeviceTier.LOW, HardwareScoring.detectTier("Imagination PowerVR GE8100"))
        assertEquals(DeviceTier.LOWER_MID, HardwareScoring.detectTier("PowerVR Rogue GE8320"))
    }

    @Test
    fun `Y5 Lite at 28 fps stable gets at least grade B not D`() {
        // Real scenario from user report: Huawei Y5 Lite running Touch2Goal Soccer
        // at 28 FPS average, p1 around 14 (occasional dips to ~half framerate). User
        // reported v3.1.10 gave it grade D, said it should be A or B max.
        //
        // With v3.1.11 fixes:
        //   - GPU correctly detected as LOW tier (expected=30, floor=20)
        //   - 28 fps is 93% of expected → -3 penalty (was -15)
        //   - p1=14 is 70% of floor → -5 penalty (was -8)
        //   - Two minor problems → -3, -4 = -7 penalty
        //   - Score: 100 - 3 - 5 - 7 = 85 → grade A
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 28,
            p1Fps = 14,
            tier = DeviceTier.LOW,
            problems = listOf(
                "FPS promedio 28 - Muy bajo para una experiencia fluida",
                "P1 FPS: 14 - Caidas severas que causan congelaciones visibles"
            )
        )
        assert(grade == 'A' || grade == 'B') {
            "Y5 Lite at 28fps stable should be grade A or B for its hardware class, got $grade ($score/100)"
        }
    }

    @Test
    fun `Y5 Lite at 28 fps without problems list gets grade A`() {
        // Same scenario but without the auto-detected problems penalty.
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 28, p1Fps = 14, tier = DeviceTier.LOW, problems = emptyList()
        )
        assertEquals('A', grade, "28fps stable on a LOW tier device with no problems should be A, got $grade ($score)")
    }

    @Test
    fun `Y5 Lite end-to-end - GPU string to grade`() {
        // The complete pipeline that v3.1.10 was getting wrong: GPU string → tier → grade.
        val gpu = "Imagination Technologies, PowerVR Rogue GE8300, OpenGL ES 3.2"
        val tier = HardwareScoring.detectTier(gpu)
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 28, p1Fps = 14, tier = tier, problems = emptyList()
        )
        assertEquals(DeviceTier.LOW, tier, "Y5 Lite GPU must be detected as LOW")
        assertEquals('A', grade, "End-to-end Y5 Lite should be A, got $grade ($score)")
    }

    @Test
    fun `Pixel XL with Adreno 530 at 43 fps gets grade A`() {
        // Reference test: the Pixel XL is LOWER_MID (expected=35, floor=25).
        // 43 fps is well above expected. Should be a clean A.
        val gpu = "Qualcomm, Adreno (TM) 530, OpenGL ES 3.2"
        val tier = HardwareScoring.detectTier(gpu)
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 43, p1Fps = 35, tier = tier, problems = emptyList()
        )
        assertEquals(DeviceTier.LOWER_MID, tier)
        assertEquals('A', grade, "Pixel XL at 43fps stable should be A, got $grade ($score)")
    }

    @Test
    fun `Galaxy S7 SD820 at 32 fps gets at least grade C`() {
        // Galaxy S7 has Adreno 530 (Snapdragon 820). Same GPU as Pixel XL.
        // 32 fps for a LOWER_MID device (expected=35, floor=25):
        //   - 32 < 35 but 32 >= 35*0.9=31 → -3 penalty
        //   - Assume p1 around 24 (just below floor) → -5 penalty
        //   - Score: 100 - 3 - 5 = 92 → A. But with two problems penalties → 85 → still A.
        // Anyway, no way this should be D.
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 32, p1Fps = 24, tier = DeviceTier.LOWER_MID, problems = emptyList()
        )
        assert(grade in listOf('A', 'B', 'C')) {
            "Galaxy S7 at 32fps on LOWER_MID should be A/B/C, got $grade ($score)"
        }
    }

    @Test
    fun `flagship at 30 fps still grade D - hardware-aware penalty works`() {
        // Negative regression test: a flagship device at 30 FPS should still be
        // graded badly because that's terrible for its class. Make sure the
        // softened middle brackets didn't accidentally let flagships off the hook.
        val (grade, _) = HardwareScoring.calculateDeviceGrade(
            avgFps = 30, p1Fps = 20, tier = DeviceTier.ULTRA_HIGH, problems = emptyList()
        )
        assert(grade == 'D' || grade == 'F') {
            "ULTRA_HIGH device at 30fps should still be D or F, got $grade"
        }
    }

    // ===== v4.1.0 — Additional edge cases for GPU normalization =====

    @Test
    fun `detectTier handles Adreno without spaces before number`() {
        // Known gap: `Adreno(TM)530` (no space between TM) and number)
        assertEquals(DeviceTier.LOWER_MID, HardwareScoring.detectTier("Adreno(TM)530"))
    }

    @Test
    fun `detectTier handles Mali without hyphen`() {
        // Known gap: `Mali(TM) G710` (space instead of hyphen before model)
        assertEquals(DeviceTier.HIGH, HardwareScoring.detectTier("Mali G710 MC10"))
    }

    @Test
    fun `detectTier handles Mali with TM and no hyphen`() {
        assertEquals(DeviceTier.HIGH, HardwareScoring.detectTier("ARM Mali(TM) G710"))
    }

    @Test
    fun `detectTier empty string returns UNKNOWN`() {
        assertEquals(DeviceTier.UNKNOWN, HardwareScoring.detectTier(""))
    }

    @Test
    fun `detectTier gibberish returns UNKNOWN`() {
        assertEquals(DeviceTier.UNKNOWN, HardwareScoring.detectTier("FooBar GPU 9000"))
    }

    @Test
    fun `detectTier Apple GPU strings match tier map entries`() {
        // Apple GPU strings like "Apple GPU (A17 Pro)" contain "a17 pro" after normalization,
        // which IS in the gpuTierMap (added for iOS hardware scoring in v4.0.0).
        assertEquals(DeviceTier.HIGH, HardwareScoring.detectTier("Apple GPU (A17 Pro)"))
        assertEquals(DeviceTier.ULTRA_HIGH, HardwareScoring.detectTier("Apple GPU (A18 Pro)"))
    }

    @Test
    fun `calculateDeviceGrade negative avgFps returns F`() {
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = -5, p1Fps = -5, tier = DeviceTier.MID, problems = emptyList()
        )
        assertEquals('F', grade)
        assertEquals(0, score)
    }

    @Test
    fun `calculateDeviceGrade perfect score for tier ceiling`() {
        // Device hitting exactly its expectedFps with no problems → score 100
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 60, p1Fps = 55, tier = DeviceTier.HIGH, problems = emptyList()
        )
        assertEquals('A', grade)
        assertEquals(100, score)
    }

    @Test
    fun `calculateDeviceGrade multiple problems stack penalties`() {
        val (_, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 60, p1Fps = 55, tier = DeviceTier.HIGH,
            problems = listOf("Temperatura CPU alta", "Memoria alta: 1800MB", "CPU saturada 90%")
        )
        // 3 problems should each reduce the score
        assert(score < 80) { "3 problems should reduce score below 80, got $score" }
    }

    @Test
    fun `avgFps zero returns capture failure sentinel grade F score 0`() {
        // v3.1.11: distinguish "device is bad" from "tool failed to measure".
        // avgFps=0 means the capture pipeline never read a real FPS sample —
        // probably the findLayer / dumpsys --latency path failed (the Pixel XL
        // bug pre-v3.1.10). Don't reward an unmeasured session with a real grade.
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 0, p1Fps = 0, tier = DeviceTier.HIGH, problems = emptyList()
        )
        assertEquals('F', grade)
        assertEquals(0, score)
    }

    // ═════════════════════════════════════════════════════════════════════
    // v4.3.6 — proportional grading via targetFps. Path B of the dual-grading
    // fix described in `sdd/grading-thermal-realism/explore`. The hardcoded
    // tier.expectedFps was making 30-fps Unity-vsync games on flagship S23
    // devices (HIGH tier, expected=60) score F. The new contract: the
    // effective expected FPS is `min(tier.expectedFps, targetFps)` so a
    // 30-fps game is graded against a 30-fps yardstick on EVERY tier.
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `30fps Unity game on HIGH tier scores A when proportional`() {
        // p50 = 30, p1 = 28 — basically perfect for a 30fps target. Pre-v4.3.6
        // this scored F because expected was hardcoded to 60. With targetFps=30
        // wired in, the effective expected drops to min(60,30)=30 and the
        // device hits target → grade A.
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 30, p1Fps = 28, tier = DeviceTier.HIGH, problems = emptyList(), targetFps = 30
        )
        assertEquals('A', grade, "30fps Unity on S23 (HIGH) at target should be A, got $grade ($score)")
    }

    @Test
    fun `30fps Unity game on MID tier also scores A when proportional`() {
        // MID expected=45. min(45,30) = 30 → 30 hits target → A.
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 30, p1Fps = 28, tier = DeviceTier.MID, problems = emptyList(), targetFps = 30
        )
        assertEquals('A', grade, "30fps Unity on MID at target should be A, got $grade ($score)")
    }

    @Test
    fun `30fps Unity game on LOW tier scores A when proportional`() {
        // LOW expected=30. min(30,30) = 30 → at target → A.
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 30, p1Fps = 25, tier = DeviceTier.LOW, problems = emptyList(), targetFps = 30
        )
        assertEquals('A', grade, "30fps on LOW at target should be A, got $grade ($score)")
    }

    @Test
    fun `45fps target on HIGH tier at 45fps scores A`() {
        // 45fps Auto-mode game; HIGH tier expected=60, effective expected=45.
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 45, p1Fps = 42, tier = DeviceTier.HIGH, problems = emptyList(), targetFps = 45
        )
        assertEquals('A', grade, "45fps on HIGH at target should be A, got $grade ($score)")
    }

    @Test
    fun `60fps target on HIGH tier at 60fps scores A (back-compat behavior)`() {
        // The pre-v4.3.6 default: targetFps=60 on HIGH, expected=min(60,60)=60.
        // Behavior identical to the old code path. Verify back-compat.
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 60, p1Fps = 55, tier = DeviceTier.HIGH, problems = emptyList(), targetFps = 60
        )
        assertEquals('A', grade)
        assertEquals(100, score)
    }

    @Test
    fun `90fps target on ULTRA_HIGH at 90fps scores A`() {
        // For high-refresh games (Genshin 90hz mode etc) the effective expected
        // takes the MIN of tier.expectedFps and targetFps — for ULTRA_HIGH
        // (expected=60) and targetFps=90 the effective expected stays at 60.
        // 90 >> 60 → easy A.
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 90, p1Fps = 85, tier = DeviceTier.ULTRA_HIGH, problems = emptyList(), targetFps = 90
        )
        assertEquals('A', grade, "90fps on ULTRA_HIGH at target should be A, got $grade ($score)")
    }

    @Test
    fun `targetFps below tier expected lowers the grading floor`() {
        // 30fps target on HIGH. At p1=15 (way below the new effective floor
        // of min(50, max(25, 25))=25) the grade should still penalise — but
        // less than the pre-v4.3.6 case where p1=15 was compared against 50.
        val (gradeProp, scoreProp) = HardwareScoring.calculateDeviceGrade(
            avgFps = 28, p1Fps = 15, tier = DeviceTier.HIGH, problems = emptyList(), targetFps = 30
        )
        // 28 fps is 93% of effective expected (30) → small penalty (-3)
        // p1=15 vs effective floor 25 → 15/25 = 60% → small dip (-12).
        // 100 - 3 - 12 = 85 → A boundary.
        assert(gradeProp == 'A' || gradeProp == 'B') {
            "30fps target with p1 dip should still be A or B, got $gradeProp ($scoreProp)"
        }
    }

    @Test
    fun `flagship at 30fps with 60fps target STILL scores poorly (regression guard)`() {
        // Negative regression: if the user's game IS targeting 60 but the
        // flagship lands at 30, the device IS underperforming. The fix must
        // NOT mask this — only proportional scaling for actual lower-target
        // games matters. With targetFps=60 and avgFps=30, behavior is the
        // same as pre-v4.3.6.
        val (grade, _) = HardwareScoring.calculateDeviceGrade(
            avgFps = 30, p1Fps = 20, tier = DeviceTier.ULTRA_HIGH, problems = emptyList(), targetFps = 60
        )
        assert(grade == 'D' || grade == 'F') {
            "ULTRA_HIGH at 30fps with 60fps target should still be D/F, got $grade"
        }
    }

    @Test
    fun `default targetFps overload preserves pre-v4_3_6 behavior`() {
        // The 4-arg form (no targetFps) defaults to 60 → identical to old code.
        val (grade1, score1) = HardwareScoring.calculateDeviceGrade(
            avgFps = 60, p1Fps = 55, tier = DeviceTier.HIGH, problems = emptyList()
        )
        val (grade2, score2) = HardwareScoring.calculateDeviceGrade(
            avgFps = 60, p1Fps = 55, tier = DeviceTier.HIGH, problems = emptyList(), targetFps = 60
        )
        assertEquals(grade2, grade1)
        assertEquals(score2, score1)
    }
}
