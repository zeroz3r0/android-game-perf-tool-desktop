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
        // v3.1.11: UNKNOWN was 60/30 — same as ULTRA_HIGH, which made unrecognized
        // GPUs get penalized as if they were flagship chips. A Huawei Y5 Lite at 28 FPS
        // ended up with grade D because the GE8300 wasn't recognized and fell here.
        // New defaults assume "average mid-range device" — neither a flagship nor a
        // calculator. If the user reports an UNKNOWN tier on a real device, the right
        // fix is to ADD it to the gpuTierMap below, not to change these defaults.
        UNKNOWN("Unknown", 45, 30)
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
        // v3.1.11: GE8300 was here. It belongs to LOW (it's the GPU of MT6739 / Helio A22
        // / Cortex-A53 quad-core 1.5GHz devices like the Huawei Y5 Lite and Nokia 1 Plus).
        // Going to a sub-30 fps threshold matches reality.
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
        "powervr ge8300" to DeviceTier.LOW,    // MT6739 (Y5 Lite, Nokia 1 Plus, etc.)
        "powervr ge8100" to DeviceTier.LOW,    // MT6737 / older MTK low-end
        "powervr ge8200" to DeviceTier.LOW,
        "powervr gx6" to DeviceTier.LOW,
        "powervr g6200" to DeviceTier.LOW,
        "powervr g6400" to DeviceTier.LOW,
        "powervr 540" to DeviceTier.LOW,       // PowerVR SGX 540 (very old)
        "sgx" to DeviceTier.LOW,
        "tegra" to DeviceTier.LOW,
        "gc800" to DeviceTier.LOW,
        "gc nano" to DeviceTier.LOW,
        "videocore" to DeviceTier.LOW          // Broadcom VideoCore (Pi-class, very old MTKs)
    )

    /**
     * Detect device tier from GPU string reported by SurfaceFlinger/getprop.
     *
     * v3.1.10: normalize the GPU string before matching. Real Android devices report
     * strings like `Qualcomm, Adreno (TM) 530, OpenGL ES 3.2 V@384.0` and
     * `ARM Mali-G710 MC10`. Our gpuTierMap keys are plain tokens like `adreno 530`
     * and `mali-g710`, so we strip `(tm)`, `(r)`, extra whitespace, and commas before
     * matching.
     *
     * v3.1.11: also strip series/family qualifiers that vendors put between the brand
     * and the model number. The Huawei Y5 Lite reports
     * `Imagination Technologies, PowerVR Rogue GE8300, OpenGL ES 3.2` — after v3.1.10
     * normalization that becomes `imagination technologies powervr rogue ge8300` which
     * does NOT contain `powervr ge8300` (the map key) because of the `rogue ` in the
     * middle. v3.1.11 strips known qualifier tokens (`rogue`, `series`, `family`,
     * `bxe`, `ge`-prefixed prefixes when they're part of brand names) so the literal
     * matches work.
     *
     * Also strips brand names (`qualcomm`, `arm`, `imagination technologies`) since
     * the map keys never include them — this lets us be more aggressive about
     * substring matching without false positives from brand-name overlap.
     */
    fun detectTier(gpuString: String): DeviceTier {
        val gpu = gpuString.lowercase()
            // v4.1.0: replace with space (not empty) so `Adreno(TM)530` → `adreno 530`
            // instead of `adreno530` which wouldn't match the map key.
            .replace(Regex("\\(tm\\)"), " ")
            .replace(Regex("\\(r\\)"), " ")
            .replace(Regex("[,;]"), " ")
            // v3.1.11: strip vendor brand prefixes that never appear in the map
            .replace(Regex("\\bqualcomm\\b"), "")
            .replace(Regex("\\bimagination technologies\\b"), "")
            .replace(Regex("\\bimagination\\b"), "")
            .replace(Regex("\\barm\\b"), "")
            // v3.1.11: strip family/series qualifiers that vendors insert between
            // brand and model number. These are NEVER part of the map keys.
            .replace(Regex("\\brogue\\b"), "")
            .replace(Regex("\\bseries\\b"), "")
            .replace(Regex("\\bfamily\\b"), "")
            .replace(Regex("\\bopengl es \\d+(\\.\\d+)?\\b"), "")  // strip OpenGL version suffix
            .replace(Regex("[,;]"), " ")
            // v4.1.0: normalize `mali g710` → `mali-g710` so it matches the map key.
            // Handles `Mali(TM) G710` which after (TM) strip becomes `mali  g710` → `mali g710`.
            .replace(Regex("\\bmali\\s+([gmt])"), "mali-$1")
            .replace(Regex("\\s+"), " ")
            .trim()
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
     *
     * v3.1.11: rebalanced thresholds. The previous version was too strict on the
     * "between fpsFloor and expectedFps" range — a Y5 Lite (LOW tier, expected=30,
     * floor=20) running at 28 FPS got -15 just for "not hitting expected" even though
     * 28 is 93% of expected and well above floor. New brackets are gentler in the
     * "between expected and floor" zone (where good-enough performance lives) and
     * still strict below floor (where actually broken performance lives).
     *
     * Sentinel: avgFps == 0 means the tool failed to read FPS at all (capture-side
     * bug, not user fault). Returns the previous behavior of grade F + score 0 so
     * the report makes it obvious something went wrong on capture, not on the device.
     */
    fun calculateDeviceGrade(avgFps: Int, p1Fps: Int, tier: DeviceTier, problems: List<String>): Pair<Char, Int> {
        // v3.1.11: capture-side failure (tool couldn't measure) — distinct from
        // device performance. Don't reward an unmeasured session with a real grade.
        if (avgFps <= 0) return 'F' to 0

        var score = 100

        val expectedFps = tier.expectedFps
        val fpsFloor = tier.fpsFloor

        // FPS penalty relative to device tier expectations
        // v3.1.11: softened middle brackets. A device hitting 90% of its expected
        // is doing GREAT for its class (-3 instead of -15) and hitting expected itself
        // is the platonic ideal of performance for that hardware (no penalty).
        when {
            avgFps >= expectedFps -> score -= 0                          // at or above expected → A territory
            avgFps >= (expectedFps * 0.9).toInt() -> score -= 3          // 90% of expected → still A
            avgFps >= (expectedFps * 0.8).toInt() -> score -= 7          // 80% of expected → A or high B
            avgFps >= fpsFloor -> score -= 12                            // between floor and 80% → B/C
            avgFps >= (fpsFloor * 0.7).toInt() -> score -= 25            // below floor → D
            else -> score -= 40                                          // way below floor → F
        }

        // P1 penalty relative to floor
        // v3.1.11: also softened — p1 == floor (occasional dip to floor) is normal,
        // not penalty-worthy. Only when p1 dips well below floor do we count it as a
        // real problem.
        when {
            p1Fps >= fpsFloor -> score -= 0                              // p1 above floor → no penalty
            p1Fps >= (fpsFloor * 0.7).toInt() -> score -= 5              // small dip → small penalty
            p1Fps >= (fpsFloor * 0.5).toInt() -> score -= 12             // moderate dip
            else -> score -= 22                                          // severe dip
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
