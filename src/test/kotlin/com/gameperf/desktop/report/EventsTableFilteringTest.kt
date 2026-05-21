package com.gameperf.desktop.report

import com.gameperf.desktop.core.events.Confidence
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.events.EventType
import com.gameperf.desktop.core.model.DeviceInfo
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.viewmodel.DetectionMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v4.8.1 (engram #502) — events-table filtering regression tests.
 *
 * The "Eventos detectados" table must hide cold-start init noise:
 *   - SDK_INIT (Firebase Analytics, GameAnalytics, AppsFlyer, Adjust, …)
 *   - APP_STARTUP (synthetic, already disclosed in the portada)
 *   - SCREEN_TRANSITION (ActivityTaskManager MainActivity boot)
 *
 * But MUST keep showing in-gameplay events (interstitials, IAPs, ANRs).
 * And the silent-detector warning logic from v4.8.0 PR1 must stay coherent:
 * a session whose only events are hidden types still counts as "0 meaningful".
 *
 * @since v4.8.1
 */
class EventsTableFilteringTest {

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

    private fun event(type: EventType, startMs: Long, endMs: Long? = null, sdk: String = "test"): DetectedEvent =
        DetectedEvent(
            type = type,
            sdkSource = sdk,
            startMs = startMs,
            endMs = endMs ?: (startMs + 1_000L),
            confidence = Confidence.HIGH,
            signatureMatched = "$sdk:test",
        )

    private fun generate(events: List<DetectedEvent>, durationSec: Int = 60): String {
        val path = ReportGenerator.generate(
            pkg = "com.test.events",
            info = device,
            grade = 'B',
            score = 80,
            duration = durationSec,
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
            events = events,
            detectionMode = DetectionMode.ANDROID_FULL,
            detectorWasActive = true,
        )
        val html = File(path).readText()
        File(path).delete()
        return html
    }

    // ═══════ Hidden types ═══════

    @Test
    fun `SDK_INIT events at t=0 are hidden from the table`() {
        // 4 SDK_INIT events firing in the first 1.5s — the exact Piece Out scenario.
        val initNoise = listOf(
            event(EventType.SDK_INIT, 0L, sdk = "Firebase Analytics"),
            event(EventType.SDK_INIT, 300L, sdk = "GameAnalytics"),
            event(EventType.SDK_INIT, 500L, sdk = "AppsFlyer"),
            event(EventType.SDK_INIT, 1_500L, sdk = "Adjust"),
        )
        val html = generate(initNoise)
        // "Init SDK" is the type label rendered for SDK_INIT. None must appear.
        assertFalse(
            html.contains(">Init SDK<"),
            "SDK_INIT events must not appear in the events table — they are cold-start noise",
        )
    }

    @Test
    fun `APP_STARTUP events are hidden from the table`() {
        val onlyStartup = listOf(event(EventType.APP_STARTUP, 0L, sdk = "System"))
        val html = generate(onlyStartup)
        assertFalse(
            html.contains(">Arranque<"),
            "APP_STARTUP must not appear in the events table — already disclosed in the portada",
        )
    }

    @Test
    fun `SCREEN_TRANSITION events are hidden from the table`() {
        val onlyTransition = listOf(event(EventType.SCREEN_TRANSITION, 0L, sdk = "ActivityTaskManager"))
        val html = generate(onlyTransition)
        assertFalse(
            html.contains(">Navegación<"),
            "SCREEN_TRANSITION must not appear in the events table — typically boot-time noise",
        )
    }

    // ═══════ Visible types keep showing ═══════

    @Test
    fun `INTERSTITIAL events remain visible in the table`() {
        val ad = listOf(event(EventType.INTERSTITIAL, 60_000L, sdk = "AppLovin"))
        val html = generate(ad)
        assertTrue(
            html.contains(">Intersticial<"),
            "Legitimate ad event must still render — it answers 'what happened at FPS drop'",
        )
    }

    @Test
    fun `mixed init noise plus legitimate ad shows only the ad row`() {
        val mixed = listOf(
            event(EventType.SDK_INIT, 0L, sdk = "Firebase Analytics"),
            event(EventType.SDK_INIT, 300L, sdk = "GameAnalytics"),
            event(EventType.APP_STARTUP, 0L, sdk = "System"),
            event(EventType.INTERSTITIAL, 60_000L, sdk = "AppLovin"),
        )
        val html = generate(mixed)
        assertTrue(html.contains(">Intersticial<"), "Ad row must remain")
        assertFalse(html.contains(">Init SDK<"), "Init noise must not appear")
        assertFalse(html.contains(">Arranque<"), "Startup must not appear")
    }

    // ═══════ Silent-detector warning coherence ═══════

    @Test
    fun `session full of init events still triggers silent-detector warning`() {
        // 5 minutes of init-only events — visible count is 0 → warning must fire.
        val initOnly = listOf(
            event(EventType.SDK_INIT, 0L, sdk = "Firebase Analytics"),
            event(EventType.SDK_INIT, 300L, sdk = "GameAnalytics"),
            event(EventType.APP_STARTUP, 0L, sdk = "System"),
        )
        val html = generate(initOnly, durationSec = 5 * 60)
        assertTrue(
            html.contains("El detector estuvo activo pero no observó marcas conocidas"),
            "5-min session with only init noise must fire the silent-detector warning",
        )
    }

    @Test
    fun `session with init events plus one legitimate ad does NOT trigger silent-detector warning`() {
        val mixed = listOf(
            event(EventType.SDK_INIT, 0L, sdk = "Firebase Analytics"),
            event(EventType.INTERSTITIAL, 60_000L, sdk = "AppLovin"),
        )
        val html = generate(mixed, durationSec = 5 * 60)
        assertFalse(
            html.contains("El detector estuvo activo pero no observó marcas conocidas"),
            "1 legitimate event = real detection — warning must NOT fire",
        )
    }

    // ═══════ Hidden set constant pinned ═══════

    @Test
    fun `HIDDEN_EVENT_TYPES_IN_TABLE contains the v4_8_1 baseline three types`() {
        // Pins the contract — adding to the set is a deliberate change.
        val expected = setOf(
            EventType.SDK_INIT,
            EventType.APP_STARTUP,
            EventType.SCREEN_TRANSITION,
        )
        assertTrue(
            ReportGenerator.HIDDEN_EVENT_TYPES_IN_TABLE == expected,
            "Hidden set must be exactly $expected — change requires CHANGELOG entry",
        )
    }
}
