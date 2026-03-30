package com.gameperf.desktop.core

/**
 * Hardware-aware performance scoring.
 * Classifies devices into performance tiers and adjusts grade expectations.
 *
 * Data source: QA team device spreadsheet with Speedometer benchmarks
 * covering ~300 SoCs across Qualcomm, MediaTek, Samsung, Apple, HiSilicon.
 */
object HardwareScoring {

    enum class DeviceTier(val label: String, val expectedFps: Int, val fpsFloor: Int) {
        ULTRA_HIGH("Ultra High-End", 60, 55),
        HIGH("High-End", 60, 50),
        UPPER_MID("Upper Mid-Range", 55, 45),
        MID("Mid-Range", 45, 35),
        LOWER_MID("Lower Mid-Range", 35, 25),
        LOW("Low-End", 30, 20),
        UNKNOWN("Unknown", 60, 30)
    }

    /**
     * GPU model -> tier mapping.
     * Keys are lowercase substrings matched against the GPU string from SurfaceFlinger/getprop.
     */
    private val gpuTierMap = mapOf(
        // Ultra High
        "adreno 830" to DeviceTier.ULTRA_HIGH,
        "adreno 750" to DeviceTier.ULTRA_HIGH,
        "immortalis-g925" to DeviceTier.ULTRA_HIGH,
        "a18 pro" to DeviceTier.ULTRA_HIGH,
        "a18 gpu" to DeviceTier.ULTRA_HIGH,
        "xclipse 940" to DeviceTier.ULTRA_HIGH,
        "maleoon 920" to DeviceTier.ULTRA_HIGH,

        // High
        "adreno 740" to DeviceTier.HIGH,
        "adreno 730" to DeviceTier.HIGH,
        "adreno 660" to DeviceTier.HIGH,
        "adreno 650" to DeviceTier.HIGH,
        "adreno 725" to DeviceTier.HIGH,
        "mali-g710" to DeviceTier.HIGH,
        "mali-g715" to DeviceTier.HIGH,
        "mali-g78" to DeviceTier.HIGH,
        "immortalis-g715" to DeviceTier.HIGH,
        "immortalis-g720" to DeviceTier.HIGH,
        "xclipse 920" to DeviceTier.HIGH,
        "xclipse 530" to DeviceTier.HIGH,
        "a17 pro" to DeviceTier.HIGH,
        "a16 gpu" to DeviceTier.HIGH,
        "a15 gpu" to DeviceTier.HIGH,
        "maleoon 910" to DeviceTier.HIGH,

        // Upper Mid
        "adreno 735" to DeviceTier.UPPER_MID,
        "adreno 720" to DeviceTier.UPPER_MID,
        "adreno 710" to DeviceTier.UPPER_MID,
        "adreno 644" to DeviceTier.UPPER_MID,
        "adreno 642" to DeviceTier.UPPER_MID,
        "adreno 640" to DeviceTier.UPPER_MID,
        "adreno 630" to DeviceTier.UPPER_MID,
        "adreno 810" to DeviceTier.UPPER_MID,
        "mali-g610" to DeviceTier.UPPER_MID,
        "mali-g615" to DeviceTier.UPPER_MID,
        "mali-g77" to DeviceTier.UPPER_MID,
        "mali-g76" to DeviceTier.UPPER_MID,
        "mali-g68" to DeviceTier.UPPER_MID,

        // Mid
        "adreno 620" to DeviceTier.MID,
        "adreno 619" to DeviceTier.MID,
        "adreno 618" to DeviceTier.MID,
        "adreno 616" to DeviceTier.MID,
        "adreno 612" to DeviceTier.MID,
        "adreno 610" to DeviceTier.MID,
        "mali-g57" to DeviceTier.MID,
        "mali-g52" to DeviceTier.MID,
        "mali-g72" to DeviceTier.MID,
        "mali-g71" to DeviceTier.MID,
        "img bxm" to DeviceTier.MID,

        // Lower Mid
        "adreno 540" to DeviceTier.LOWER_MID,
        "adreno 530" to DeviceTier.LOWER_MID,
        "adreno 512" to DeviceTier.LOWER_MID,
        "adreno 509" to DeviceTier.LOWER_MID,
        "adreno 508" to DeviceTier.LOWER_MID,
        "adreno 506" to DeviceTier.LOWER_MID,
        "adreno 505" to DeviceTier.LOWER_MID,
        "adreno 504" to DeviceTier.LOWER_MID,
        "mali-g51" to DeviceTier.LOWER_MID,
        "mali-t880" to DeviceTier.LOWER_MID,
        "mali-t860" to DeviceTier.LOWER_MID,
        "mali-t830" to DeviceTier.LOWER_MID,
        "mali-t760" to DeviceTier.LOWER_MID,
        "powervr ge8320" to DeviceTier.LOWER_MID,
        "powervr ge8300" to DeviceTier.LOWER_MID,
        "powervr gm9446" to DeviceTier.LOWER_MID,

        // Low
        "adreno 420" to DeviceTier.LOW,
        "adreno 418" to DeviceTier.LOW,
        "adreno 405" to DeviceTier.LOW,
        "adreno 330" to DeviceTier.LOW,
        "adreno 320" to DeviceTier.LOW,
        "adreno 308" to DeviceTier.LOW,
        "adreno 306" to DeviceTier.LOW,
        "adreno 305" to DeviceTier.LOW,
        "adreno 304" to DeviceTier.LOW,
        "adreno 302" to DeviceTier.LOW,
        "adreno 200" to DeviceTier.LOW,
        "adreno 203" to DeviceTier.LOW,
        "adreno 205" to DeviceTier.LOW,
        "adreno 220" to DeviceTier.LOW,
        "adreno 225" to DeviceTier.LOW,
        "mali-400" to DeviceTier.LOW,
        "mali-450" to DeviceTier.LOW,
        "mali-t720" to DeviceTier.LOW,
        "mali-t628" to DeviceTier.LOW,
        "powervr ge8100" to DeviceTier.LOW,
        "powervr gx6" to DeviceTier.LOW,
        "sgx" to DeviceTier.LOW,
        "tegra" to DeviceTier.LOW,
        "gc800" to DeviceTier.LOW,
        "gc nano" to DeviceTier.LOW
    )

    /**
     * Detect device tier from GPU string reported by SurfaceFlinger/getprop.
     */
    fun detectTier(gpuString: String): DeviceTier {
        val gpu = gpuString.lowercase().trim()
        for ((key, tier) in gpuTierMap) {
            if (gpu.contains(key)) return tier
        }
        return DeviceTier.UNKNOWN
    }

    /**
     * Calculate a hardware-adjusted grade.
     *
     * A Snapdragon 450 running at 45 FPS = grade A (impressive for that hardware).
     * A Snapdragon 8 Gen 3 running at 45 FPS = grade D (poor for that hardware).
     */
    fun calculateDeviceGrade(avgFps: Int, p1Fps: Int, tier: DeviceTier, problems: List<String>): Pair<Char, Int> {
        var score = 100

        val expectedFps = tier.expectedFps
        val fpsFloor = tier.fpsFloor

        // FPS penalty relative to device tier expectations
        when {
            avgFps >= expectedFps -> score -= 0
            avgFps >= (expectedFps * 0.85).toInt() -> score -= 5
            avgFps >= fpsFloor -> score -= 15
            avgFps >= (fpsFloor * 0.7).toInt() -> score -= 30
            else -> score -= 45
        }

        // P1 penalty relative to floor
        when {
            p1Fps >= fpsFloor -> score -= 0
            p1Fps >= (fpsFloor * 0.7).toInt() -> score -= 8
            p1Fps >= (fpsFloor * 0.5).toInt() -> score -= 15
            else -> score -= 25
        }

        // Problem penalties (slightly reduced vs general grade)
        for (p in problems) {
            when {
                p.contains("thermal", true) || p.contains("temperatura", true) -> score -= 8
                p.contains("memoria", true) || p.contains("memory", true) -> score -= 6
                p.contains("cpu", true) -> score -= 8
                p.contains("frame", true) -> score -= 4
                else -> score -= 3
            }
        }

        score = score.coerceIn(0, 100)
        val grade = when {
            score >= 85 -> 'A'
            score >= 70 -> 'B'
            score >= 55 -> 'C'
            score >= 40 -> 'D'
            else -> 'F'
        }
        return grade to score
    }
}
