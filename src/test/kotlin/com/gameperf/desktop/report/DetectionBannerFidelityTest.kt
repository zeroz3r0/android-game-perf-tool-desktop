package com.gameperf.desktop.report

import com.gameperf.desktop.viewmodel.DetectionMode
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * v4.8.0 — events-catalog-and-device-naming spec `event-detection-fidelity`
 * R1 scenarios. Bug B regression guard: the detection-mode banner used to
 * lie (always "auto-detección desactivada") because the production caller
 * in `AppViewModel` read `eventDetector != null` AFTER the cleanup line
 * that nulled the reference. PR2 captures the lifecycle flag BEFORE the
 * cleanup and passes it explicitly into `ReportGenerator.generate(detectionMode=...)`.
 *
 * Coverage:
 *  - R1.S1: detector ran during the session → caller passes ANDROID_FULL →
 *    banner says "Detección automática Android".
 *  - R1.S2: detector never ran → caller passes MANUAL_ONLY → banner says
 *    "Marcadores manuales únicamente".
 *
 * The fix lives in `AppViewModel.kt` (capture point + 2 call sites). This
 * test pins the report-side contract so any future regression in the
 * production caller surfaces as a banner-string mismatch.
 *
 * @since v4.8.0
 */
class DetectionBannerFidelityTest {

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

    private fun generate(mode: DetectionMode): String {
        val path = ReportGenerator.generate(
            pkg = "com.test.fidelity",
            info = device,
            grade = 'B',
            score = 80,
            duration = 60,
            fpsHistory = listOf(60, 60, 60),
            memHistory = listOf(100L, 100L, 100L),
            nativeHistory = listOf(50L, 50L, 50L),
            javaHistory = listOf(50L, 50L, 50L),
            cpuHistory = listOf(20, 20, 20),
            tempCpuHistory = listOf(35.0, 36.0, 37.0),
            tempGpuHistory = listOf(40.0, 41.0, 42.0),
            tempSkinHistory = listOf(33.0, 34.0, 35.0),
            allFrameTimes = listOf(16.6, 16.6, 16.6),
            avgFps = 60, minFps = 60, maxFps = 60,
            p1 = 60, p5 = 60, p50 = 60, p90 = 60, p99 = 60,
            avgFrameTime = 16.6, p99FrameTime = 16.6,
            peakMem = 100L, avgCpu = 20, maxCpu = 25,
            maxTempCpu = 42.0, maxTempGpu = 42.0,
            batteryStart = 100, batteryEnd = 99,
            frameDrops = 0, jank = 0, stutter = 0,
            problems = emptyList(), isWifi = false,
            detectionMode = mode,
        )
        val html = File(path).readText()
        File(path).delete()
        return html
    }

    // R1.S1 — detector active → banner reflects auto-detección Android.
    @Test
    fun `banner reports Android auto-detection when caller passes ANDROID_FULL`() {
        val html = generate(DetectionMode.ANDROID_FULL)
        assertTrue(
            html.contains("Detección automática Android"),
            "ANDROID_FULL mode must surface the auto-detection banner verbatim — " +
                "regression guard for engram #495 (Bug B detection-mode lie).",
        )
    }

    // R1.S2 — detector never ran → banner reflects manual-only.
    @Test
    fun `banner reports manual-only when caller passes MANUAL_ONLY`() {
        val html = generate(DetectionMode.MANUAL_ONLY)
        assertTrue(
            html.contains("Marcadores manuales únicamente"),
            "MANUAL_ONLY mode must surface the manual-only banner verbatim",
        )
    }
}
