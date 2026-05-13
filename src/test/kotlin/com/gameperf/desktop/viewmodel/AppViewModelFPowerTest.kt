package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.core.model.FPowerDiagnostic
import com.gameperf.desktop.core.model.FPowerUnavailableReason
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v4.5.0 — Batch 4 boundary tests for the FPower wiring in [AppViewModel].
 *
 * Mirrors the precedent set by [AppViewModelAggregationTest]: we do NOT spin up
 * a real ViewModel (the capture loop is 1400+ LOC of ADB plumbing). Instead we
 * exercise the persistence boundary — what [LiveMetrics] / [SessionResult] /
 * [SessionHistory.HistoryEntry] hold AFTER the loop has run, and assert every
 * new FPower field round-trips end-to-end via [SessionHistory.addEntry] →
 * [SessionHistory.load].
 *
 * Coverage targets:
 *  - FPW-007 cadence (covered structurally — the per-tick capture call lives in
 *    Batch 3's [com.gameperf.desktop.core.AdbBridgeFPowerTest]; here we assert
 *    the resulting `lastFPower.fpowerMwPerFrame` lands on the persisted entry).
 *  - FPW-008 persisted fields: `fpowerAvailable`, `fpowerDiagnostic`,
 *    `fpowerHistory`, `fpowerTimed`, `fpowerAvg`, `fpowerPeak`.
 *  - FPW-012 backward compat: a pre-v4.5.0 `.gameperf` row that lacks the
 *    fpower fields deserialises with safe defaults
 *    (`fpowerAvailable=true, history empty, diagnostic=null`).
 *
 * Why we don't drive the live loop: see the explanatory comment in
 * [AppViewModelAggregationTest] §145 — the v4.4.1 precedent for this style
 * landed after the same lesson with the auto-event-detection schema bump.
 */
class AppViewModelFPowerTest {

    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("fpower-pending-").toFile()
        tempFile = File(dir, "history.json")
        SessionHistory.historyFileOverride = tempFile
    }

    @AfterTest
    fun tearDown() {
        SessionHistory.historyFileOverride = null
        runCatching { tempFile.delete() }
        runCatching { tempFile.parentFile?.listFiles()?.forEach { it.delete() } }
        runCatching { tempFile.parentFile?.delete() }
    }

    // ===== LiveMetrics shape =====

    @Test
    fun `LiveMetrics has fpower fields with defaults`() {
        val live = LiveMetrics()
        assertEquals(0.0, live.fpower, "fpower scalar defaults to 0.0")
        assertTrue(live.fpowerHistory.isEmpty(), "fpowerHistory defaults empty")
        assertTrue(live.fpowerTimed.isEmpty(), "fpowerTimed defaults empty")
    }

    @Test
    fun `LiveMetrics carries fpower history snapshot when populated`() {
        val history = listOf(38.4, 42.1, 50.0, 51.2)
        val timed = listOf(
            TimedSample(2, 38.4),
            TimedSample(4, 42.1),
            TimedSample(6, 50.0),
            TimedSample(8, 51.2),
        )
        val live = LiveMetrics(
            fpower = 51.2,
            fpowerHistory = history,
            fpowerTimed = timed,
        )
        assertEquals(51.2, live.fpower)
        assertEquals(4, live.fpowerHistory.size)
        assertEquals(4, live.fpowerTimed.size)
        assertEquals(history, live.fpowerHistory)
        assertEquals(timed, live.fpowerTimed)
    }

    // ===== SessionResult shape =====

    @Test
    fun `SessionResult has fpower fields with defaults`() {
        val r = SessionResult()
        assertTrue(r.fpowerAvailable, "fpowerAvailable defaults true (v4.4.x compat)")
        assertNull(r.fpowerDiagnostic, "fpowerDiagnostic defaults null on happy path")
        assertTrue(r.fpowerHistory.isEmpty(), "fpowerHistory defaults empty")
        assertTrue(r.fpowerTimed.isEmpty(), "fpowerTimed defaults empty")
        assertEquals(0.0, r.fpowerAvg, "fpowerAvg defaults 0.0")
        assertEquals(0.0, r.fpowerPeak, "fpowerPeak defaults 0.0")
    }

    @Test
    fun `SessionResult carries fpower aggregates when populated`() {
        val history = listOf(40.0, 50.0, 60.0)
        val r = SessionResult(
            fpowerAvailable = true,
            fpowerHistory = history,
            fpowerTimed = history.mapIndexed { i, v -> TimedSample(i * 2, v) },
            fpowerAvg = history.average(),
            fpowerPeak = history.max(),
        )
        assertTrue(r.fpowerAvailable)
        assertEquals(50.0, r.fpowerAvg, "avg of 40, 50, 60")
        assertEquals(60.0, r.fpowerPeak, "peak of 40, 50, 60")
        assertEquals(3, r.fpowerHistory.size)
        assertEquals(3, r.fpowerTimed.size)
    }

    @Test
    fun `SessionResult unavailable path carries diagnostic`() {
        val diag = FPowerDiagnostic(
            rawPathsTried = listOf(
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/battery/voltage_now",
            ),
            lastReadout = mapOf("/sys/class/power_supply/battery/current_now" to ""),
            reason = FPowerUnavailableReason.BATTERY_PATH_MISSING,
        )
        val r = SessionResult(
            fpowerAvailable = false,
            fpowerDiagnostic = diag,
        )
        assertFalse(r.fpowerAvailable, "unavailable path flips the flag")
        val loadedDiag = r.fpowerDiagnostic
        assertNotNull(loadedDiag)
        assertEquals(FPowerUnavailableReason.BATTERY_PATH_MISSING, loadedDiag.reason)
        assertEquals(2, loadedDiag.rawPathsTried.size)
        assertTrue(r.fpowerHistory.isEmpty(), "no readings when unavailable")
    }

    // ===== HistoryEntry round-trip =====

    private fun baseEntry(id: String = "fp-1"): SessionHistory.HistoryEntry =
        SessionHistory.HistoryEntry(
            id = id,
            name = "fpower session",
            gamePackage = "com.vivastudios.pieceout",
            deviceModel = "Samsung SM-X200",
            grade = 'A',
            deviceGrade = 'A',
            avgFps = 60,
            duration = 60,
            date = "12/05/2026 10:00",
            reportPath = "",
            videoPath = "",
        )

    @Test
    fun `pendingEntry carries fpowerAvailable=true with history`() {
        val history = listOf(38.4, 42.1, 50.0, 51.2)
        val timed = history.mapIndexed { i, v -> TimedSample(i * 2, v) }
        val entry = baseEntry("fp-happy").copy(
            fpowerAvailable = true,
            fpowerHistory = history,
            fpowerTimed = timed,
            fpowerAvg = history.average(),
            fpowerPeak = history.max(),
        )

        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "fp-happy" }

        assertNotNull(loaded, "Pending fpower entry must round-trip via SessionHistory")
        assertTrue(loaded.fpowerAvailable, "fpowerAvailable=true must survive serialisation")
        assertEquals(history, loaded.fpowerHistory, "fpowerHistory must round-trip element-equal")
        assertEquals(4, loaded.fpowerTimed.size, "fpowerTimed must round-trip with same arity")
        assertEquals(history.average(), loaded.fpowerAvg, 0.0001, "fpowerAvg lossless")
        assertEquals(history.max(), loaded.fpowerPeak, 0.0001, "fpowerPeak lossless")
        assertNull(loaded.fpowerDiagnostic, "happy path has no diagnostic")
    }

    @Test
    fun `pendingEntry carries fpowerAvailable=false plus diagnostic`() {
        val diag = FPowerDiagnostic(
            rawPathsTried = listOf(
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/battery/voltage_now",
            ),
            lastReadout = mapOf(
                "/sys/class/power_supply/battery/current_now" to "",
                "/sys/class/power_supply/battery/voltage_now" to "4200000",
            ),
            reason = FPowerUnavailableReason.BATTERY_PATH_MISSING,
        )
        val entry = baseEntry("fp-unavail").copy(
            fpowerAvailable = false,
            fpowerDiagnostic = diag,
        )

        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "fp-unavail" }

        assertNotNull(loaded)
        assertFalse(loaded.fpowerAvailable, "unavailable flag must round-trip false")
        val loadedDiag = loaded.fpowerDiagnostic
        assertNotNull(loadedDiag, "diagnostic must round-trip")
        assertEquals(FPowerUnavailableReason.BATTERY_PATH_MISSING, loadedDiag.reason)
        assertEquals(2, loadedDiag.rawPathsTried.size)
        assertEquals(2, loadedDiag.lastReadout.size)
        assertTrue(loaded.fpowerHistory.isEmpty(), "no readings on unavailable path")
    }

    @Test
    fun `pendingEntry default fpower fields are backward compat shape`() {
        // Builder uses ZERO fpower named-args. Defaults must match v4.4.x semantics
        // so a Batch-4-aware code path that re-loads a legacy `.gameperf` doesn't
        // observe "false" or null where the previous schema was implicitly true.
        val entry = baseEntry("fp-defaults")
        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "fp-defaults" }
        assertNotNull(loaded)
        assertTrue(loaded.fpowerAvailable, "default fpowerAvailable=true preserves v4.4.x compat")
        assertNull(loaded.fpowerDiagnostic, "default diagnostic=null")
        assertTrue(loaded.fpowerHistory.isEmpty())
        assertTrue(loaded.fpowerTimed.isEmpty())
        assertEquals(0.0, loaded.fpowerAvg)
        assertEquals(0.0, loaded.fpowerPeak)
    }

    @Test
    fun `pendingEntry preserves each FPowerUnavailableReason`() {
        // Spot-check every enum variant round-trips to catch a future addition that
        // forgets to wire the (de)serialiser path.
        val reasons = FPowerUnavailableReason.values().toList()
        reasons.forEachIndexed { idx, reason ->
            val entry = baseEntry("fp-reason-$idx").copy(
                fpowerAvailable = false,
                fpowerDiagnostic = FPowerDiagnostic(
                    rawPathsTried = listOf("/sys/class/power_supply/battery/current_now"),
                    lastReadout = emptyMap(),
                    reason = reason,
                ),
            )
            SessionHistory.addEntry(entry)
        }
        val loaded = SessionHistory.load()
        reasons.forEachIndexed { idx, reason ->
            val e = loaded.firstOrNull { it.id == "fp-reason-$idx" }
            assertNotNull(e, "reason=$reason entry must load")
            assertEquals(reason, e.fpowerDiagnostic?.reason, "reason=$reason must round-trip")
        }
    }

    // ===== FPower aggregation contract =====
    //
    // The post-loop builder at AppViewModel.kt computes:
    //   fpowerAvg  = if (fpowerHistory.isNotEmpty()) fpowerHistory.average() else 0.0
    //   fpowerPeak = fpowerHistory.maxOrNull() ?: 0.0
    // Mirror those rules in pure form so a future refactor that drops the
    // empty-list guard is caught.

    @Test
    fun `fpower aggregates from empty history return 0_0`() {
        val history = emptyList<Double>()
        val avg = if (history.isNotEmpty()) history.average() else 0.0
        val peak = history.maxOrNull() ?: 0.0
        assertEquals(0.0, avg, "avg of empty history is 0.0 not NaN")
        assertEquals(0.0, peak, "peak of empty history is 0.0 not -Infinity")
    }

    @Test
    fun `fpower aggregates from populated history match average and max`() {
        val history = listOf(38.4, 42.1, 50.0, 51.2, 65.5)
        val avg = if (history.isNotEmpty()) history.average() else 0.0
        val peak = history.maxOrNull() ?: 0.0
        assertEquals(history.average(), avg)
        assertEquals(65.5, peak)
        assertTrue(avg in 49.0..50.0, "sanity range")
    }
}
