package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Band
import com.gameperf.desktop.core.kpi.KpiId
import com.gameperf.desktop.core.kpi.KpiScore
import com.gameperf.desktop.core.kpi.KpiScoreReport
import com.gameperf.desktop.core.kpi.Phase
import com.gameperf.desktop.core.kpi.PhaseScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T2.2 — Android Vitals warning banner ([renderVitalsBanner]).
 *
 * Spec coverage: `sdd/shareable-html-report/spec` — Requirement: Android
 * Vitals Warning Banners. Breach conditions:
 *  - cold start ≥ catalog `COLD_START_MS` floor (5000 ms MID)
 *  - ANR count rate ≥ 0.47% (docs §3.1 Vitals user-perceived ANR)
 *  - slow frames > 25% (docs §3.1 Vitals "excessive slow frames")
 *  - frozen frames > 0.1% (docs §3.1 Vitals "excessive frozen frames")
 *
 * Pure: deterministic, no I/O.
 */
class AndroidVitalsBannersTest {

    private fun reportOf(vararg scores: KpiScore): KpiScoreReport {
        val byPhase = scores.groupBy { it.phase }
        return KpiScoreReport(
            sessionScore = 0,
            sessionBand = Band.RED,
            phases = byPhase.map { (phase, list) ->
                PhaseScore(phase = phase, score = 0, band = Band.RED, kpiScores = list)
            },
            categories = emptyList(),
        )
    }

    private fun kpi(id: KpiId, raw: Double?, phase: Phase = Phase.APP_STARTUP): KpiScore =
        KpiScore(id = id, phase = phase, rawValue = raw, score = 0, delta = 0.0, band = Band.RED)

    @Test
    fun `no breaches returns empty string`() {
        val report = reportOf(
            kpi(KpiId.COLD_START_MS, 1500.0),
            kpi(KpiId.ANR_COUNT, 0.0),
            kpi(KpiId.SLOW_FRAMES, 10.0, Phase.GAMEPLAY),
            kpi(KpiId.FROZEN_FRAMES, 0.01, Phase.GAMEPLAY),
        )
        assertEquals("", renderVitalsBanner(report, durationSec = 60))
    }

    @Test
    fun `cold start at or above catalog floor renders breach`() {
        val report = reportOf(kpi(KpiId.COLD_START_MS, 5500.0))
        val html = renderVitalsBanner(report, durationSec = 60)
        assertTrue(html.isNotEmpty(), "expected non-empty banner; got empty")
        assertTrue(
            "Cold start lento (\u22655s)" in html,
            "expected literal 'Cold start lento (\u22655s)'; got:\n$html",
        )
        assertTrue("sec-vitals-banner" in html, "expected section id")
        assertTrue("kpi-vitals-warn" in html, "expected warning class")
    }

    @Test
    fun `cold start strictly below catalog floor does not breach`() {
        val report = reportOf(kpi(KpiId.COLD_START_MS, 4999.0))
        assertEquals("", renderVitalsBanner(report, durationSec = 60))
    }

    @Test
    fun `ANR count at or above 047 percent rate renders breach`() {
        // 5 ANRs over 1000s window → 0.5% rate, above the 0.47% Vitals threshold.
        val report = reportOf(kpi(KpiId.ANR_COUNT, 5.0))
        val html = renderVitalsBanner(report, durationSec = 1000)
        assertTrue(
            "ANR \u22650.47%" in html,
            "expected literal 'ANR \u22650.47%'; got:\n$html",
        )
    }

    @Test
    fun `slow frames above 25 percent renders breach`() {
        val report = reportOf(kpi(KpiId.SLOW_FRAMES, 30.0, Phase.GAMEPLAY))
        val html = renderVitalsBanner(report, durationSec = 60)
        assertTrue(
            "Slow frames >25%" in html,
            "expected literal 'Slow frames >25%'; got:\n$html",
        )
    }

    @Test
    fun `frozen frames above 01 percent renders breach`() {
        val report = reportOf(kpi(KpiId.FROZEN_FRAMES, 0.2, Phase.GAMEPLAY))
        val html = renderVitalsBanner(report, durationSec = 60)
        assertTrue(
            "Frozen frames >0.1%" in html,
            "expected literal 'Frozen frames >0.1%'; got:\n$html",
        )
    }

    @Test
    fun `multiple breaches concatenate in a single section`() {
        val report = reportOf(
            kpi(KpiId.COLD_START_MS, 7000.0),
            kpi(KpiId.SLOW_FRAMES, 40.0, Phase.GAMEPLAY),
        )
        val html = renderVitalsBanner(report, durationSec = 60)
        assertTrue("Cold start lento" in html)
        assertTrue("Slow frames" in html)
        // exactly one <section>
        assertEquals(1, html.split("<section").size - 1, "expected exactly one section wrapper")
    }

    @Test
    fun `null rawValue is treated as no data and does not breach`() {
        val report = reportOf(
            kpi(KpiId.COLD_START_MS, null),
            kpi(KpiId.SLOW_FRAMES, null, Phase.GAMEPLAY),
        )
        assertEquals("", renderVitalsBanner(report, durationSec = 60))
    }

    // ===== v4.6.0 — vitals-rate-and-wakelocks =====

    @Test
    fun `crash count above zero renders crash rate breach`() {
        val report = reportOf(kpi(KpiId.CRASH_COUNT, 1.0))
        val html = renderVitalsBanner(report, durationSec = 60)
        assertTrue(
            html.contains("Crash") || html.contains("crash"),
            "expected breach to mention crash; got:\n$html",
        )
        assertTrue(
            html.contains("1.09%"),
            "expected Vitals 1.09% floor citation; got:\n$html",
        )
    }

    @Test
    fun `crash count zero does not render crash rate breach`() {
        val report = reportOf(kpi(KpiId.CRASH_COUNT, 0.0))
        val html = renderVitalsBanner(report, durationSec = 60)
        assertFalse(
            html.contains("Crash") || html.contains("crash rate"),
            "expected zero crashes to NOT emit a crash banner; got:\n$html",
        )
    }

    @Test
    fun `anr count above zero renders ANR rate users breach line`() {
        val report = reportOf(kpi(KpiId.ANR_COUNT, 1.0))
        val html = renderVitalsBanner(report, durationSec = 60)
        // Existing "ANR ≥0.47%" line (rate-based) STILL fires; this new line
        // adds the single-session users-rate proxy citation.
        assertTrue(
            html.contains("0.47%"),
            "expected Vitals 0.47% ANR floor citation; got:\n$html",
        )
    }

    @Test
    fun `wake locks screen-off above 2h renders wake locks breach`() {
        val report = reportOf()  // no kpiScores needed — uses SessionResult-level data
        val html = renderVitalsBanner(
            report,
            durationSec = 600,
            wakeLocksScreenOffMs = 7_500_000L,  // ~2h 5m, above floor
        )
        assertTrue(html.isNotEmpty(), "expected non-empty banner; got empty")
        assertTrue(
            html.contains("wake locks", ignoreCase = true) || html.contains("Wake locks"),
            "expected wake-locks line; got:\n$html",
        )
        assertTrue(
            html.contains("2h") || html.contains("2 h") || html.contains("2.0h"),
            "expected '2h' threshold citation; got:\n$html",
        )
    }

    @Test
    fun `wake locks screen-off below 2h does not breach`() {
        val report = reportOf()
        val html = renderVitalsBanner(
            report,
            durationSec = 600,
            wakeLocksScreenOffMs = 3_600_000L,  // exactly 1h — below floor
        )
        assertFalse(
            html.contains("wake locks", ignoreCase = true),
            "expected NO wake-locks banner below 2h floor; got:\n$html",
        )
    }

    @Test
    fun `wake locks default minus one ms does not breach`() {
        // Default sentinel propagates from SessionResult when capture failed.
        val report = reportOf()
        val html = renderVitalsBanner(report, durationSec = 60, wakeLocksScreenOffMs = -1L)
        assertEquals("", html, "no banner when wake locks unavailable")
    }

    // ===== v4.7 (html-report-rag-bands — RAG-007) =====
    // Banner thresholds now consume `KpiCatalog.byId(...).thresholds[MID].floor`
    // as the single source of truth (closes engram followup #460). The previous
    // local consts (`WAKE_LOCKS_BAD_MS`, `CRASH_RATE_USERS_BAD_PCT`,
    // `ANR_RATE_USERS_BAD_PCT`) are deleted. Boundary semantics: the breach
    // gate is INCLUSIVE at the catalog floor (mirrors `LinearScoring.bandFor`
    // which maps `value == floor` → score 0 → Band.RED).

    @Test
    fun `wake locks at exactly catalog floor in ms is breach (inclusive)`() {
        // Catalog floor: 2.0h → 7_200_000 ms. Inclusive policy: == floor breaches.
        val floorMs = (com.gameperf.desktop.core.kpi.KpiCatalog
            .byId(com.gameperf.desktop.core.kpi.KpiId.WAKE_LOCKS_RATE)
            .thresholds[com.gameperf.desktop.core.kpi.DeviceTier.MID]!!.floor * 3_600_000L).toLong()
        val report = reportOf()
        val html = renderVitalsBanner(report, durationSec = 600, wakeLocksScreenOffMs = floorMs)
        assertTrue(
            html.contains("wake locks", ignoreCase = true) || html.contains("Wake locks"),
            "expected wake-locks breach at exactly floor (inclusive); got:\n$html",
        )
    }

    @Test
    fun `wake locks one ms below floor does not breach (boundary 1_999h vs 2_001h)`() {
        val floorMs = (com.gameperf.desktop.core.kpi.KpiCatalog
            .byId(com.gameperf.desktop.core.kpi.KpiId.WAKE_LOCKS_RATE)
            .thresholds[com.gameperf.desktop.core.kpi.DeviceTier.MID]!!.floor * 3_600_000L).toLong()
        val report = reportOf()
        val html = renderVitalsBanner(report, durationSec = 600, wakeLocksScreenOffMs = floorMs - 1L)
        assertFalse(
            html.contains("wake locks", ignoreCase = true),
            "value just below floor must not breach; got:\n$html",
        )
    }

    @Test
    fun `wake locks gate is exactly floor times 3600000 (precision)`() {
        // Multiply BEFORE `.toLong()` — verify the gate is exactly 7_200_000 for
        // floor 2.0h, not 7_199_999 from a premature `.toLong()` rounding.
        val floor = com.gameperf.desktop.core.kpi.KpiCatalog
            .byId(com.gameperf.desktop.core.kpi.KpiId.WAKE_LOCKS_RATE)
            .thresholds[com.gameperf.desktop.core.kpi.DeviceTier.MID]!!.floor
        val expectedGateMs = (floor * 3_600_000L).toLong()
        assertEquals(7_200_000L, expectedGateMs, "floor*3_600_000L must compute exactly 7_200_000")
    }
}
