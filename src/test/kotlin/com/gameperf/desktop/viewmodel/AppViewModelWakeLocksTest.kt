package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.core.model.WakeLocksDiagnostic
import com.gameperf.desktop.core.model.WakeLocksUnavailableReason
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
 * v4.6.0 — Boundary tests for the Wake Locks wiring in [AppViewModel].
 *
 * Mirrors the [AppViewModelNetworkTest] precedent: we do NOT spin up a real
 * ViewModel (the capture loop is 1500+ LOC of ADB plumbing). Instead we
 * exercise the persistence boundary -- what [SessionResult] /
 * [SessionHistory.HistoryEntry] hold AFTER the loop has run, and assert
 * every new wake-locks field round-trips end-to-end via
 * [SessionHistory.addEntry] + [SessionHistory.load].
 *
 * Coverage targets:
 *  - WLK-001 persisted fields: `wakeLocksAvailable`, `wakeLocksScreenOffMs`,
 *    `wakeLocksScreenOnMs`, `wakeLocksDiagnostic`.
 *  - Backward compat: a pre-v4.6.0 `.gameperf` row that lacks the
 *    wake-locks fields deserialises with safe defaults
 *    (`wakeLocksAvailable=false`, sentinel `-1L` ms, diagnostic=null).
 *
 * Pattern is identical to [AppViewModelNetworkTest] / [AppViewModelGpuTest].
 */
class AppViewModelWakeLocksTest {

    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("wake-locks-pending-").toFile()
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

    // ===== SessionResult shape =====

    @Test
    fun `SessionResult has wake-locks fields with defaults`() {
        val r = SessionResult()
        assertFalse(r.wakeLocksAvailable, "wakeLocksAvailable defaults false (pre-v4.6.0 never captured)")
        assertNull(r.wakeLocksDiagnostic, "diagnostic defaults null on happy path")
        assertEquals(-1L, r.wakeLocksScreenOffMs, "screenOff defaults -1L sentinel")
        assertEquals(-1L, r.wakeLocksScreenOnMs, "screenOn defaults -1L sentinel")
    }

    @Test
    fun `SessionResult carries wake-locks aggregates when populated`() {
        val r = SessionResult(
            wakeLocksAvailable = true,
            wakeLocksScreenOffMs = 6_790_000L,
            wakeLocksScreenOnMs = 0L,
        )
        assertTrue(r.wakeLocksAvailable)
        assertEquals(6_790_000L, r.wakeLocksScreenOffMs)
        assertEquals(0L, r.wakeLocksScreenOnMs)
    }

    @Test
    fun `SessionResult unavailable path carries diagnostic`() {
        val diag = WakeLocksDiagnostic(
            probedCommand = "dumpsys batterystats --charged com.example.game",
            reason = WakeLocksUnavailableReason.CAPTURE_THREW,
        )
        val r = SessionResult(
            wakeLocksAvailable = false,
            wakeLocksDiagnostic = diag,
        )
        assertFalse(r.wakeLocksAvailable)
        val loaded = r.wakeLocksDiagnostic
        assertNotNull(loaded)
        assertEquals(WakeLocksUnavailableReason.CAPTURE_THREW, loaded.reason)
        assertEquals("dumpsys batterystats --charged com.example.game", loaded.probedCommand)
    }

    // ===== HistoryEntry round-trip =====

    private fun baseEntry(id: String = "wl-1"): SessionHistory.HistoryEntry =
        SessionHistory.HistoryEntry(
            id = id,
            name = "wake-locks session",
            gamePackage = "com.example.game",
            deviceModel = "Pixel 8 Pro",
            grade = 'A',
            deviceGrade = 'A',
            avgFps = 60,
            duration = 60,
            date = "13/05/2026 14:00",
            reportPath = "",
            videoPath = "",
        )

    @Test
    fun `pendingEntry carries wakeLocksAvailable=true with totals`() {
        val entry = baseEntry("wl-happy").copy(
            wakeLocksAvailable = true,
            wakeLocksScreenOffMs = 6_790_000L,
            wakeLocksScreenOnMs = 0L,
        )

        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "wl-happy" }

        assertNotNull(loaded, "wake-locks entry must round-trip via SessionHistory")
        assertTrue(loaded.wakeLocksAvailable, "wakeLocksAvailable=true must survive serialisation")
        assertEquals(6_790_000L, loaded.wakeLocksScreenOffMs, "screenOff lossless")
        assertEquals(0L, loaded.wakeLocksScreenOnMs, "screenOn lossless")
        assertNull(loaded.wakeLocksDiagnostic, "happy path has no diagnostic")
    }

    @Test
    fun `pendingEntry carries wakeLocksAvailable=false plus diagnostic`() {
        val diag = WakeLocksDiagnostic(
            probedCommand = "dumpsys batterystats --charged com.example.game",
            reason = WakeLocksUnavailableReason.PARSE_FAILED,
        )
        val entry = baseEntry("wl-unavail").copy(
            wakeLocksAvailable = false,
            wakeLocksDiagnostic = diag,
        )

        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "wl-unavail" }

        assertNotNull(loaded)
        assertFalse(loaded.wakeLocksAvailable)
        val loadedDiag = loaded.wakeLocksDiagnostic
        assertNotNull(loadedDiag, "diagnostic must round-trip")
        assertEquals(WakeLocksUnavailableReason.PARSE_FAILED, loadedDiag.reason)
        assertEquals("dumpsys batterystats --charged com.example.game", loadedDiag.probedCommand)
        assertEquals(-1L, loaded.wakeLocksScreenOffMs, "unavailable path keeps -1L sentinel")
        assertEquals(-1L, loaded.wakeLocksScreenOnMs, "unavailable path keeps -1L sentinel")
    }

    @Test
    fun `pendingEntry default wake-locks fields are backward compat shape`() {
        // Builder uses ZERO wake-locks named-args. Defaults must match the
        // "no wake-locks data" semantics so a v4.5.x `.gameperf` row that
        // lacks ALL wake-locks keys hydrates identically to a fresh session
        // that never captured wake-locks.
        val entry = baseEntry("wl-defaults")
        SessionHistory.addEntry(entry)
        val loaded = SessionHistory.load().firstOrNull { it.id == "wl-defaults" }
        assertNotNull(loaded)
        assertFalse(loaded.wakeLocksAvailable, "default wakeLocksAvailable=false preserves never-captured semantics")
        assertNull(loaded.wakeLocksDiagnostic, "default diagnostic=null")
        assertEquals(-1L, loaded.wakeLocksScreenOffMs)
        assertEquals(-1L, loaded.wakeLocksScreenOnMs)
    }

    @Test
    fun `pendingEntry preserves each WakeLocksUnavailableReason`() {
        // Spot-check every enum variant round-trips to catch a future addition
        // that forgets to wire the (de)serialiser path.
        val reasons = WakeLocksUnavailableReason.values().toList()
        reasons.forEachIndexed { idx, reason ->
            val entry = baseEntry("wl-reason-$idx").copy(
                wakeLocksAvailable = false,
                wakeLocksDiagnostic = WakeLocksDiagnostic(
                    probedCommand = "dumpsys batterystats --charged com.example.game",
                    reason = reason,
                ),
            )
            SessionHistory.addEntry(entry)
        }
        val loaded = SessionHistory.load()
        reasons.forEachIndexed { idx, reason ->
            val e = loaded.firstOrNull { it.id == "wl-reason-$idx" }
            assertNotNull(e, "reason=$reason entry must load")
            assertEquals(reason, e.wakeLocksDiagnostic?.reason, "reason=$reason must round-trip")
        }
    }

    // ===== Pre-v4.6.0 backward compat (missing fields in JSON) =====

    @Test
    fun `legacy v4_5_x row missing wake-locks keys loads with safe defaults`() {
        // Simulate a v4.5.x history.json row that predates this change.
        val legacyJson = """[
  {
    "id": "legacy-wl-1",
    "name": "pre-v4.6.0 session",
    "gamePackage": "com.legacy.game",
    "deviceModel": "Samsung SM-G998B",
    "grade": "B",
    "deviceGrade": "B",
    "avgFps": 58,
    "duration": 120,
    "date": "01/01/2025 10:00",
    "reportPath": "",
    "videoPath": "",
    "isFavorite": true
  }
]"""
        tempFile.writeText(legacyJson)
        val loaded = SessionHistory.load()
        assertEquals(1, loaded.size, "legacy row must load")
        val e = loaded[0]
        assertEquals("legacy-wl-1", e.id)
        // Backward-compat assertions: every new wake-locks field hydrates to
        // the "never captured" defaults documented on HistoryEntry.
        assertFalse(e.wakeLocksAvailable, "missing wakeLocksAvailable key defaults to false")
        assertEquals(-1L, e.wakeLocksScreenOffMs, "missing key defaults to -1L sentinel")
        assertEquals(-1L, e.wakeLocksScreenOnMs, "missing key defaults to -1L sentinel")
        assertNull(e.wakeLocksDiagnostic, "missing wakeLocksDiagnostic key defaults null")
    }
}
