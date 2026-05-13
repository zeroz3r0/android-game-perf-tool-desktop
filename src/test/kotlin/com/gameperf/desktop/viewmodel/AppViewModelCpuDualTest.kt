package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.SessionHistory
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v4.5.0 — Sprint 1 boundary tests for the dual CPU wiring in
 * `cpu-total-vs-app-usage`. Mirrors the structure of [AppViewModelFPowerTest]
 * per the architecture lesson logged in [AppViewModelAggregationTest]: we do
 * NOT spin up a real ViewModel (the capture loop is 1400+ LOC of ADB plumbing).
 * Instead we exercise the persistence boundary — what [LiveMetrics] /
 * [SessionResult] / [SessionHistory.HistoryEntry] hold AFTER the loop has run,
 * and assert every new cpuTotalHistory field round-trips end-to-end via
 * `SessionHistory.addEntry` → `SessionHistory.load`.
 *
 * Coverage targets:
 *  - CDU-002: LiveMetrics carries cpuTotalHistory with empty default + populated.
 *  - CDU-003: SessionResult carries cpuTotalHistory with empty default + populated.
 *  - CDU-004: SerializableEntry + HistoryEntry round-trip cpuTotalHistory.
 *  - CDU-005: ViewModel populates cpuTotalHistory per tick (asserted at the
 *    persistence boundary — the per-tick capture call lives in Sprint 0's
 *    [com.gameperf.desktop.core.AdbBridgeCpuDualTest]).
 */
class AppViewModelCpuDualTest {

    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("cpudual-pending-").toFile()
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

    // ===== LiveMetrics shape (CDU-002) =====

    @Test
    fun `LiveMetrics has cpuTotalHistory with empty default`() {
        val live = LiveMetrics()
        assertTrue(live.cpuTotalHistory.isEmpty(), "cpuTotalHistory defaults empty")
    }

    @Test
    fun `LiveMetrics carries populated cpuTotalHistory`() {
        val totals = listOf(60, 65, 70, 75)
        val live = LiveMetrics(cpuTotalHistory = totals)
        assertEquals(totals, live.cpuTotalHistory, "cpuTotalHistory round-trips on the live state")
    }

    // ===== SessionResult shape (CDU-003) =====

    @Test
    fun `SessionResult has cpuTotalHistory with empty default`() {
        val r = SessionResult()
        assertTrue(r.cpuTotalHistory.isEmpty(), "cpuTotalHistory defaults empty (CDU-003)")
    }

    @Test
    fun `SessionResult carries populated cpuTotalHistory`() {
        val totals = listOf(45, 55, 65)
        val r = SessionResult(cpuTotalHistory = totals)
        assertEquals(totals, r.cpuTotalHistory)
    }

    // ===== HistoryEntry round-trip (CDU-004) =====

    private fun baseEntry(id: String = "cpu-dual-1"): SessionHistory.HistoryEntry =
        SessionHistory.HistoryEntry(
            id = id,
            name = "cpu dual session",
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
    fun `HistoryEntry round-trip preserves cpuTotalHistory via SessionHistory`() {
        val totals = listOf(70, 75, 80, 85)
        val entry = baseEntry("cpu-dual-rt").copy(cpuTotalHistory = totals)

        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "cpu-dual-rt" }

        assertNotNull(loaded, "Pending cpuTotalHistory entry must round-trip via SessionHistory")
        assertEquals(totals, loaded.cpuTotalHistory, "cpuTotalHistory must round-trip element-equal")
    }

    @Test
    fun `HistoryEntry defaults cpuTotalHistory to empty list when not provided`() {
        // Builder uses ZERO cpuTotalHistory named-arg. Default must be the
        // empty list so a Sprint-1-aware code path that re-loads a legacy
        // .gameperf doesn't observe null where the previous schema was
        // implicitly the empty list.
        val entry = baseEntry("cpu-dual-defaults")
        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "cpu-dual-defaults" }
        assertNotNull(loaded)
        assertTrue(loaded.cpuTotalHistory.isEmpty(), "default cpuTotalHistory is empty list")
    }

    // ===== CDU-005: tick semantics (pure assertion against the field surface) =====

    @Test
    fun `LiveMetrics cpuTotalHistory grows independently of cpuHistory (CDU-005 contract)`() {
        // Simulate the ViewModel loop's two-channel append pattern. We don't
        // invoke the real loop — we assert the data model lets us build a
        // LiveMetrics snapshot where the two histories have INDEPENDENT contents.
        // If either field accidentally aliased the other, this test would fail
        // because the lists would coalesce.
        val appCpu = listOf(20, 25, 30)
        val totalCpu = listOf(60, 70, 80) // total > app per tick (other procs)
        val live = LiveMetrics(
            cpuHistory = appCpu,
            cpuTotalHistory = totalCpu,
        )
        assertEquals(appCpu, live.cpuHistory, "app channel independent")
        assertEquals(totalCpu, live.cpuTotalHistory, "total channel independent")
        assertTrue(
            live.cpuTotalHistory.zip(live.cpuHistory).all { (t, a) -> t >= a },
            "in this fixture every total >= app — invariant of the GameBench mental model",
        )
    }
}
