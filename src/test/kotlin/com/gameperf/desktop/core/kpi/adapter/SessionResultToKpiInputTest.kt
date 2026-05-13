package com.gameperf.desktop.core.kpi.adapter

import com.gameperf.desktop.core.events.Confidence
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.events.EventType
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.Phase
import com.gameperf.desktop.viewmodel.SessionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD coverage for `toKpiInput(SessionResult): KpiInput`.
 *
 * Each test maps 1:1 to a spec scenario in `sdd/kpi-session-adapter/spec`.
 */
class SessionResultToKpiInputTest {

    private fun event(
        type: EventType,
        startMs: Long,
        endMs: Long?,
    ): DetectedEvent = DetectedEvent(
        type = type,
        sdkSource = "test",
        startMs = startMs,
        endMs = endMs,
        confidence = Confidence.HIGH,
        signatureMatched = "x",
    )

    // 2.1 — Determinism + deviceModel forwarding
    @Test
    fun `toKpiInput forwards deviceModel and is deterministic`() {
        val session = SessionResult(deviceModel = "SM-S911B", duration = 30, avgFps = 60)
        val first = toKpiInput(session)
        val second = toKpiInput(session)
        assertEquals("SM-S911B", first.deviceModel)
        assertEquals(first, second)
    }

    // 2.2 — Empty events → GAMEPLAY only, FPS_AVG from avgFps
    @Test
    fun `toKpiInput empty events yields gameplay-only with FPS_AVG from avgFps`() {
        val session = SessionResult(deviceModel = "x", avgFps = 58, duration = 60)
        val result = toKpiInput(session)
        assertEquals(setOf(Phase.GAMEPLAY), result.rawByPhase.keys)
        val gameplay = result.rawByPhase[Phase.GAMEPLAY]
        assertNotNull(gameplay)
        assertEquals(58.0, gameplay[KpiId.FPS_AVG])
    }

    // 2.3 — Single interstitial creates INTERSTITIAL_AD phase + GAMEPLAY
    @Test
    fun `toKpiInput single interstitial creates INTERSTITIAL_AD phase`() {
        val session = SessionResult(
            deviceModel = "x",
            avgFps = 50,
            duration = 60,
            events = listOf(event(EventType.INTERSTITIAL, 10_000, 20_000)),
        )
        val result = toKpiInput(session)
        assertTrue(Phase.INTERSTITIAL_AD in result.rawByPhase.keys)
        assertTrue(Phase.GAMEPLAY in result.rawByPhase.keys)
        // Both phases populated with at least the always-present FPS_AVG.
        assertNotNull(result.rawByPhase[Phase.INTERSTITIAL_AD]!![KpiId.FPS_AVG])
        assertNotNull(result.rawByPhase[Phase.GAMEPLAY]!![KpiId.FPS_AVG])
    }

    // 2.4 — Two interstitials carve gameplay; INTERSTITIAL_AD windows union
    @Test
    fun `toKpiInput two interstitials union INTERSTITIAL_AD and carve GAMEPLAY`() {
        val session = SessionResult(
            deviceModel = "x",
            avgFps = 45,
            duration = 60,
            events = listOf(
                event(EventType.INTERSTITIAL, 10_000, 20_000),
                event(EventType.INTERSTITIAL, 40_000, 50_000),
            ),
        )
        val result = toKpiInput(session)
        // Both phases present, no others.
        assertEquals(setOf(Phase.INTERSTITIAL_AD, Phase.GAMEPLAY), result.rawByPhase.keys)
        // Both phases populated with FPS_AVG.
        assertNotNull(result.rawByPhase[Phase.INTERSTITIAL_AD]!![KpiId.FPS_AVG])
        assertNotNull(result.rawByPhase[Phase.GAMEPLAY]!![KpiId.FPS_AVG])
    }

    // 2.5 — IAP carves gameplay but produces no IAP-derived phase
    @Test
    fun `toKpiInput IAP carves gameplay but no phase`() {
        // IAP from 5..8s; rest of duration is gameplay.
        val session = SessionResult(
            deviceModel = "x",
            avgFps = 50,
            duration = 60,
            events = listOf(event(EventType.IAP, 5_000, 8_000)),
        )
        val result = toKpiInput(session)
        // Only GAMEPLAY present — IAP has no Phase mapping (D4).
        assertEquals(setOf(Phase.GAMEPLAY), result.rawByPhase.keys)
        // Compare to a sibling scenario: a session WITHOUT the IAP event
        // would still have only GAMEPLAY. The contract here is the absence
        // of any IAP-derived phase key — checked above by .keys equality.
        // (The exact KPI value difference is documented in design D6 and
        // not asserted in v1 since CPU/RAM session aggregates are reused.)
        assertNotNull(result.rawByPhase[Phase.GAMEPLAY]!![KpiId.FPS_AVG])
    }

    // 2.6 — Missing thermal (maxTempCpu=0.0) omits TEMP_* KPIs
    @Test
    fun `toKpiInput missing thermal omits TEMP_ KPIs`() {
        val withThermal = SessionResult(
            deviceModel = "x", avgFps = 50, duration = 30, maxTempCpu = 42.5,
        )
        val withoutThermal = SessionResult(
            deviceModel = "x", avgFps = 50, duration = 30, maxTempCpu = 0.0,
        )
        val withResult = toKpiInput(withThermal).rawByPhase[Phase.GAMEPLAY]!!
        val withoutResult = toKpiInput(withoutThermal).rawByPhase[Phase.GAMEPLAY]!!
        // With thermal: TEMP_MAX must be present.
        assertNotNull(withResult[KpiId.TEMP_MAX])
        assertEquals(42.5, withResult[KpiId.TEMP_MAX])
        // Without thermal: NO thermal KPIs in ANY phase map.
        assertFalse(KpiId.TEMP_AVG in withoutResult.keys)
        assertFalse(KpiId.TEMP_MAX in withoutResult.keys)
    }

    // 2.7 — Missing FPower omits FPOWER KPI
    @Test
    fun `toKpiInput missing fpower omits FPOWER`() {
        val withFpower = SessionResult(
            deviceModel = "x", avgFps = 50, duration = 30,
            fpowerAvailable = true,
            fpowerHistory = listOf(45.0, 50.0, 55.0),
            fpowerAvg = 50.0,
        )
        val withoutFpower = SessionResult(
            deviceModel = "x", avgFps = 50, duration = 30,
            fpowerAvailable = false,
        )
        val withResult = toKpiInput(withFpower).rawByPhase[Phase.GAMEPLAY]!!
        val withoutResult = toKpiInput(withoutFpower).rawByPhase[Phase.GAMEPLAY]!!
        // With fpower → FPOWER present, value forwarded.
        assertNotNull(withResult[KpiId.FPOWER])
        assertEquals(50.0, withResult[KpiId.FPOWER])
        // Without fpower → NO FPOWER KPI in any phase.
        assertFalse(KpiId.FPOWER in withoutResult.keys)
    }

    // 2.8 — Input not mutated by the adapter
    @Test
    fun `toKpiInput does not mutate input`() {
        val events = listOf(
            event(EventType.INTERSTITIAL, 10_000, 20_000),
            event(EventType.IAP, 30_000, 35_000),
        )
        val fpower = listOf(40.0, 45.0, 50.0)
        val session = SessionResult(
            deviceModel = "SM-S911B",
            avgFps = 55,
            duration = 60,
            events = events,
            fpowerHistory = fpower,
            fpowerAvg = 45.0,
        )
        val snapshotEventsSize = session.events.size
        val snapshotFpowerSize = session.fpowerHistory.size
        val eventsRef = session.events
        val fpowerRef = session.fpowerHistory

        toKpiInput(session)

        // Sizes unchanged.
        assertEquals(snapshotEventsSize, session.events.size)
        assertEquals(snapshotFpowerSize, session.fpowerHistory.size)
        // Reference identity preserved (no defensive copies that swap the field).
        assertTrue(eventsRef === session.events)
        assertTrue(fpowerRef === session.fpowerHistory)
        // Content unchanged (sanity).
        assertEquals(events, session.events)
        assertEquals(fpower, session.fpowerHistory)
    }
}
