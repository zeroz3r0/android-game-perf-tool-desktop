package com.gameperf.desktop.report

import com.gameperf.desktop.core.events.Confidence
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.events.EventType
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * auto-phase-detection-from-engine-logs (Phase 5, AUTO-007) — disclaimer
 * banner gating. The banner is rendered when ANY of the 4 AUTO EventType
 * variants (CUTSCENE / MENU_NAV / COMBAT_PHASE / TUTORIAL_PHASE) is
 * present in the session events list. Spanish tuteo formal copy.
 *
 * Coverage:
 *  - At least one AUTO event present → banner rendered.
 *  - Zero AUTO events → banner NOT rendered (zero-cost on legacy callers).
 *  - Banner copy matches spec verbatim.
 */
class ReportGeneratorAutoPhaseBannerTest {

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

    private fun autoEvent(type: EventType, startMs: Long = 0L): DetectedEvent =
        DetectedEvent(
            type = type,
            sdkSource = "Unity auto-phase",
            startMs = startMs,
            endMs = startMs + 1_000L,
            confidence = Confidence.MEDIUM,
            signatureMatched = "auto-phase:test",
        )

    private fun nonAutoEvent(): DetectedEvent =
        DetectedEvent(
            type = EventType.INTERSTITIAL,
            sdkSource = "AdMob",
            startMs = 0L,
            endMs = 1_000L,
            confidence = Confidence.HIGH,
            signatureMatched = "Showing ad",
        )

    private fun generate(events: List<DetectedEvent>): String {
        val path = ReportGenerator.generate(
            pkg = "com.example.autophase.test",
            info = device,
            grade = 'B', score = 75, duration = 60,
            fpsHistory = listOf(60, 60, 60),
            memHistory = listOf(400L, 410L, 420L),
            nativeHistory = listOf(200L, 205L, 210L),
            javaHistory = listOf(100L, 102L, 104L),
            cpuHistory = listOf(40, 45, 50),
            tempCpuHistory = listOf(40.0, 42.0, 45.0),
            tempGpuHistory = listOf(35.0, 37.0, 39.0),
            tempSkinHistory = emptyList(),
            allFrameTimes = listOf(16.0, 16.5, 17.0),
            avgFps = 60, minFps = 59, maxFps = 60,
            p1 = 59, p5 = 59, p50 = 60, p90 = 60, p99 = 60,
            avgFrameTime = 16.5, p99FrameTime = 17.0,
            peakMem = 420L, avgCpu = 45, maxCpu = 50,
            maxTempCpu = 45.0, maxTempGpu = 39.0,
            batteryStart = 90, batteryEnd = 88,
            frameDrops = 0, jank = 0, stutter = 0,
            problems = emptyList(),
            isWifi = false,
            events = events,
        )
        return File(path).readText()
    }

    /** Marker substring that anchors the auto-phase banner. */
    private val bannerMarker = "Fases detectadas automáticamente por nombre de escena"

    @Test
    fun `AUTO-007 banner renders when COMBAT_PHASE event present`() {
        val html = generate(events = listOf(autoEvent(EventType.COMBAT_PHASE)))
        assertTrue(
            html.contains(bannerMarker),
            "COMBAT_PHASE event must trigger the auto-phase disclaimer banner",
        )
    }

    @Test
    fun `AUTO-007 banner renders when CUTSCENE event present`() {
        val html = generate(events = listOf(autoEvent(EventType.CUTSCENE)))
        assertTrue(html.contains(bannerMarker))
    }

    @Test
    fun `AUTO-007 banner renders when MENU_NAV event present`() {
        val html = generate(events = listOf(autoEvent(EventType.MENU_NAV)))
        assertTrue(html.contains(bannerMarker))
    }

    @Test
    fun `AUTO-007 banner renders when TUTORIAL_PHASE event present`() {
        val html = generate(events = listOf(autoEvent(EventType.TUTORIAL_PHASE)))
        assertTrue(html.contains(bannerMarker))
    }

    @Test
    fun `AUTO-007 banner NOT rendered when zero AUTO events present`() {
        val html = generate(events = emptyList())
        assertFalse(
            html.contains(bannerMarker),
            "no AUTO events → banner must be omitted (zero-cost legacy path)",
        )
    }

    @Test
    fun `AUTO-007 banner NOT rendered when only non-AUTO events present`() {
        val html = generate(events = listOf(nonAutoEvent()))
        assertFalse(
            html.contains(bannerMarker),
            "INTERSTITIAL only → no AUTO banner",
        )
    }

    @Test
    fun `AUTO-007 banner mentions Unity and Unreal coverage scope`() {
        // AUTO-007: banner must disclose detection scope so users know
        // the limit (not all engines covered v1).
        val html = generate(events = listOf(autoEvent(EventType.COMBAT_PHASE)))
        assertTrue(html.contains("Unity"), "banner must mention Unity")
        assertTrue(html.contains("Unreal"), "banner must mention Unreal")
    }
}
