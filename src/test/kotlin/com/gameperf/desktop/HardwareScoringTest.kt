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
    fun `detectTier returns UNKNOWN for Adreno with TM suffix`() {
        // "Adreno (TM) 619" does NOT contain "adreno 619" as substring
        assertEquals(DeviceTier.UNKNOWN, HardwareScoring.detectTier("Adreno (TM) 619"))
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
        // Perfect scenario for UNKNOWN tier (expected 60fps)
        val (grade, score) = HardwareScoring.calculateDeviceGrade(
            avgFps = 60, p1Fps = 30, tier = DeviceTier.UNKNOWN, problems = emptyList()
        )
        assertEquals('A', grade)
        assert(score >= 85) { "Score $score should be >= 85" }
    }
}
