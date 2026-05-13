package com.gameperf.desktop.core

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
 * Regression gate for the v4.6.0 wake-locks fields added to
 * [SessionHistory.SerializableEntry] / [SessionHistory.HistoryEntry]
 * (sdd/vitals-rate-and-wakelocks).
 *
 * Mirrors the v4.4.1 thermalAvailable / v4.5.0 GPU / v4.6.x network
 * regression suites: round-trip preservation + pre-v4.6.0 legacy hydration.
 *
 * The bug pattern guarded against here is "schema bump but data class still
 * carries the old field set" — when the encoder silently dropped v4.4.0
 * fields. Tests here force the wake-locks payload through a save+load cycle
 * and demand byte-equivalent recovery.
 */
class SessionHistoryWakeLocksRoundTripTest {

    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("sessionhistory-wakelocks-").toFile()
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

    private fun baseEntry(id: String): SessionHistory.HistoryEntry = SessionHistory.HistoryEntry(
        id = id,
        name = "wake locks $id",
        gamePackage = "com.example.game",
        deviceModel = "Pixel 8 Pro",
        grade = 'A',
        deviceGrade = 'A',
        avgFps = 58,
        duration = 600,
        date = "13/05/2026 13:00",
        reportPath = "",
        videoPath = "",
    )

    @Test
    fun `wake-locks happy path round-trips (available + screen-off ms)`() {
        val entry = baseEntry("wl-happy").copy(
            wakeLocksAvailable = true,
            wakeLocksScreenOffMs = 7_500_000L,
            wakeLocksScreenOnMs = 250_000L,
            wakeLocksDiagnostic = null,
        )
        SessionHistory.addEntry(entry)

        val loaded = SessionHistory.load().firstOrNull { it.id == "wl-happy" }
        assertNotNull(loaded)
        assertTrue(loaded.wakeLocksAvailable, "wakeLocksAvailable=true must survive round trip")
        assertEquals(7_500_000L, loaded.wakeLocksScreenOffMs)
        assertEquals(250_000L, loaded.wakeLocksScreenOnMs)
        assertNull(loaded.wakeLocksDiagnostic, "happy path persists diagnostic=null")
    }

    @Test
    fun `wake-locks failure path round-trips (unavailable + diagnostic reason)`() {
        val diag = WakeLocksDiagnostic(
            probedCommand = "dumpsys batterystats --charged com.example.game",
            reason = WakeLocksUnavailableReason.PKG_NOT_FOUND,
        )
        val entry = baseEntry("wl-fail").copy(
            wakeLocksAvailable = false,
            wakeLocksScreenOffMs = -1L,
            wakeLocksScreenOnMs = -1L,
            wakeLocksDiagnostic = diag,
        )
        SessionHistory.addEntry(entry)

        val loaded = SessionHistory.load().firstOrNull { it.id == "wl-fail" }
        assertNotNull(loaded)
        assertFalse(loaded.wakeLocksAvailable)
        assertEquals(-1L, loaded.wakeLocksScreenOffMs)
        assertEquals(-1L, loaded.wakeLocksScreenOnMs)
        assertNotNull(loaded.wakeLocksDiagnostic)
        assertEquals(WakeLocksUnavailableReason.PKG_NOT_FOUND, loaded.wakeLocksDiagnostic!!.reason)
        assertTrue(loaded.wakeLocksDiagnostic!!.probedCommand.startsWith("dumpsys"))
    }

    @Test
    fun `legacy pre-v4_6_0 JSON without wake-locks fields hydrates with safe defaults`() {
        // Pre-v4.6.0 row — no wakeLocks* keys at all. Mirrors the v4.4.1 / v4.5.0 / v4.6.x
        // legacy hydration regression tests. Defaults must report "no data" so the
        // report HTML renders the unavailable banner instead of a misleading "0h".
        val legacyJson = """
            [
              {
                "id": "legacy-wl",
                "name": "legacy session",
                "gamePackage": "com.legacy.game",
                "deviceModel": "Pixel 6",
                "grade": "B",
                "deviceGrade": "B",
                "avgFps": 55,
                "duration": 300,
                "date": "01/01/2026 00:00",
                "reportPath": "",
                "videoPath": "",
                "tag": "OUR_GAME",
                "competitorName": "",
                "isFavorite": false
              }
            ]
        """.trimIndent()
        tempFile.parentFile?.mkdirs()
        tempFile.writeText(legacyJson)

        val loaded = SessionHistory.load()
        assertEquals(1, loaded.size)
        val entry = loaded.first()
        assertFalse(
            entry.wakeLocksAvailable,
            "Missing wakeLocksAvailable defaults to false (mirror v4.5.0 GPU precedent)",
        )
        assertEquals(-1L, entry.wakeLocksScreenOffMs, "Missing wakeLocksScreenOffMs defaults to -1L sentinel")
        assertEquals(-1L, entry.wakeLocksScreenOnMs, "Missing wakeLocksScreenOnMs defaults to -1L sentinel")
        assertNull(entry.wakeLocksDiagnostic, "Missing wakeLocksDiagnostic defaults to null")
    }
}
