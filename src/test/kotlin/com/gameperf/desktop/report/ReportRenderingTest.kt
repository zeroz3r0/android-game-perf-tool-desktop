package com.gameperf.desktop.report

import com.gameperf.desktop.core.AdbBridge
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Test that generates a real HTML report with synthetic data so the orchestrator
 * can render it through Chrome --print-to-pdf and visually verify the print mode
 * fix. Not a unit test in the traditional sense — more like a fixture generator.
 *
 * The generated HTML lands in `~/GamePerf Reports/` and can be opened directly in
 * a browser or piped through `chrome --headless --print-to-pdf=out.pdf file://...?print=1`
 * to validate the print palette + page break behavior.
 */
class ReportRenderingTest {

    @Test
    fun generateSampleReportForVisualVerification() {
        // Disabled by default — uncomment the line below to regenerate a sample HTML in
        // ~/GamePerf Reports/ that you can pipe through chrome --print-to-pdf to verify
        // visual changes to the print mode without needing a live capture.
        // Run with: ./gradlew test --tests "com.gameperf.desktop.report.ReportRenderingTest"
        if (System.getenv("RUN_REPORT_FIXTURE") != "true") return

        val device = AdbBridge.DeviceInfo(
            model = "Pixel 7 Pro",
            manufacturer = "Google",
            cpu = "Tensor G2 octa-core",
            gpu = "Mali-G710 MC10",
            ram = "12.0 GB",
            cores = 8,
            sdk = 34,
            resolution = "1440x3120"
        )

        // Synthetic 60-second session: FPS oscillates between 35 and 60 with a few drops
        val fpsHistory = (1..60).map { i ->
            when {
                i in 12..14 -> 22                                          // small stutter
                i in 35..38 -> 18                                          // bigger drop
                i in 50..51 -> 45
                else -> 50 + ((i * 7) % 11) - 4                            // oscillation 46..56
            }
        }

        val memHistory = (1..60).map { i -> 380L + (i * 2) + ((i % 5) * 8) }
        val nativeHistory = (1..60).map { i -> 220L + i + ((i % 4) * 3) }
        val javaHistory = (1..60).map { i -> 95L + (i / 4) }

        val cpuHistory = (1..60).map { i ->
            when {
                i in 35..38 -> 88                                          // CPU spike during the FPS drop
                else -> 35 + ((i * 5) % 25)
            }
        }

        val tempCpuHistory = (1..60).map { i -> 32.0 + (i * 0.15) + ((i % 7) * 0.3) }
        val tempGpuHistory = (1..60).map { i -> 30.0 + (i * 0.18) + ((i % 5) * 0.25) }
        val tempSkinHistory = (1..60).map { i -> 28.0 + (i * 0.12) }

        // Frame times: convert from FPS history with some noise
        val allFrameTimes = fpsHistory.flatMap { fps ->
            val target = 1000.0 / fps.coerceAtLeast(1)
            (0 until fps).map { target + ((it % 3) - 1) * 1.5 }
        }

        val avgFps = fpsHistory.average().toInt()
        val minFps = fpsHistory.min()
        val maxFps = fpsHistory.max()

        val sortedFps = fpsHistory.sorted()
        fun pctile(p: Int) = sortedFps[(sortedFps.size * p / 100).coerceIn(0, sortedFps.size - 1)]

        ReportGenerator.generate(
            pkg = "com.example.testgame.action",
            info = device,
            grade = 'B',
            score = 78,
            duration = 60,
            fpsHistory = fpsHistory,
            memHistory = memHistory,
            nativeHistory = nativeHistory,
            javaHistory = javaHistory,
            cpuHistory = cpuHistory,
            tempCpuHistory = tempCpuHistory,
            tempGpuHistory = tempGpuHistory,
            tempSkinHistory = tempSkinHistory,
            allFrameTimes = allFrameTimes,
            avgFps = avgFps,
            minFps = minFps,
            maxFps = maxFps,
            p1 = pctile(1),
            p5 = pctile(5),
            p50 = pctile(50),
            p90 = pctile(90),
            p99 = pctile(99),
            avgFrameTime = allFrameTimes.average(),
            p99FrameTime = allFrameTimes.sorted().let { it[(it.size * 99 / 100).coerceIn(0, it.size - 1)] },
            peakMem = memHistory.max(),
            avgCpu = cpuHistory.average().toInt(),
            maxCpu = cpuHistory.max(),
            maxTempCpu = tempCpuHistory.max(),
            maxTempGpu = tempGpuHistory.max(),
            batteryStart = 87,
            batteryEnd = 84,
            frameDrops = 12,
            jank = 3,
            stutter = 1,
            problems = listOf(
                "Caida de FPS detectada en segundo 35-38 (CPU al 88%)",
                "Stutter ligero entre segundos 12-14"
            ),
            isWifi = false,
            deviceGrade = 'A',
            deviceScore = 92,
            deviceTier = "High-end"
        )

        // The report writes itself to ~/GamePerf Reports/. Just confirm the function returned.
        assertTrue(true)
    }
}
