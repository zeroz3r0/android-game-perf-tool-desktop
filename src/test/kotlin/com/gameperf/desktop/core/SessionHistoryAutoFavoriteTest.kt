package com.gameperf.desktop.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v4.3.7 — Layer 2 of the session-history-loss prevention rollout.
 *
 * Real ("non-fake / non-test / non-emulator") sessions are auto-favorited at insertion
 * time so they cannot be silently evicted by a burst of test runs. The classifier is a
 * pure function exposed at [SessionHistory.isFakeOrTestSession]; tests target it
 * directly to keep the unit fast and free of disk dependencies, plus a few integration
 * tests through `addEntry` to lock in the side effect.
 *
 * Detection rules (per the spec, in order):
 *  - deviceModel == "Fake" (literal) → fake
 *  - deviceModel.isEmpty() → fake/test
 *  - deviceModel.startsWith("emulator-") → emulator (treated as test, NOT auto-favorited)
 *  - gamePackage == "com.test.game" (literal) → fake
 *  - gamePackage.isEmpty() → fake/test
 *  - else → REAL → auto-favorite
 */
class SessionHistoryAutoFavoriteTest {

    private lateinit var tempFile: File

    private fun entry(
        id: String,
        deviceModel: String,
        gamePackage: String,
        isFavorite: Boolean = false
    ): SessionHistory.HistoryEntry =
        SessionHistory.HistoryEntry(
            id = id,
            name = "session-$id",
            gamePackage = gamePackage,
            deviceModel = deviceModel,
            grade = 'A',
            deviceGrade = 'A',
            avgFps = 60,
            duration = 60,
            date = "01/01/2026 00:00",
            reportPath = "/tmp/informe_$id.html",
            videoPath = "/tmp/video_$id.mp4",
            isFavorite = isFavorite,
        )

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("sessionhistory-autofav-").toFile()
        tempFile = File(dir, "history.json")
        SessionHistory.historyFileOverride = tempFile
    }

    @AfterTest
    fun tearDown() {
        SessionHistory.historyFileOverride = null
        runCatching { tempFile.delete() }
        runCatching { tempFile.parentFile?.delete() }
    }

    // ===== isFakeOrTestSession (pure) =====

    @Test
    fun `isFakeOrTestSession returns true when deviceModel equals Fake literal`() {
        val e = entry("a", deviceModel = "Fake", gamePackage = "com.example.realgame")
        assertTrue(SessionHistory.isFakeOrTestSession(e))
    }

    @Test
    fun `isFakeOrTestSession returns true when gamePackage equals com_test_game literal`() {
        val e = entry("a", deviceModel = "SM-S911B", gamePackage = "com.test.game")
        assertTrue(SessionHistory.isFakeOrTestSession(e))
    }

    @Test
    fun `isFakeOrTestSession returns true when deviceModel is empty`() {
        val e = entry("a", deviceModel = "", gamePackage = "com.example.realgame")
        assertTrue(SessionHistory.isFakeOrTestSession(e))
    }

    @Test
    fun `isFakeOrTestSession returns true when gamePackage is empty`() {
        val e = entry("a", deviceModel = "SM-S911B", gamePackage = "")
        assertTrue(SessionHistory.isFakeOrTestSession(e))
    }

    @Test
    fun `isFakeOrTestSession returns true when deviceModel starts with emulator dash`() {
        val e = entry("a", deviceModel = "emulator-5554", gamePackage = "com.example.realgame")
        assertTrue(SessionHistory.isFakeOrTestSession(e),
            "emulator devices are treated as test sessions and should not auto-favorite")
    }

    @Test
    fun `isFakeOrTestSession returns false for a real Samsung S23 session`() {
        val e = entry("a", deviceModel = "SM-S911B", gamePackage = "com.example.realgame")
        assertFalse(SessionHistory.isFakeOrTestSession(e),
            "a real device + a real package must classify as real")
    }

    @Test
    fun `isFakeOrTestSession returns false for a real Pixel session`() {
        val e = entry("a", deviceModel = "Pixel 7", gamePackage = "com.king.candycrushsaga")
        assertFalse(SessionHistory.isFakeOrTestSession(e))
    }

    // ===== addEntry side effect =====

    @Test
    fun `addEntry auto-favorites a real session`() {
        val real = entry("real-1", deviceModel = "SM-S911B", gamePackage = "com.example.realgame")

        SessionHistory.addEntry(real)

        val saved = SessionHistory.load().first { it.id == "real-1" }
        assertTrue(saved.isFavorite,
            "real sessions must be auto-favorited so they can't be evicted by test bursts")
    }

    @Test
    fun `addEntry leaves a fake session as non-favorite`() {
        val fake = entry("fake-1", deviceModel = "Fake", gamePackage = "com.test.game")

        SessionHistory.addEntry(fake)

        val saved = SessionHistory.load().first { it.id == "fake-1" }
        assertFalse(saved.isFavorite,
            "fake sessions must remain non-favorite so they remain evictable")
    }

    @Test
    fun `addEntry leaves an emulator session as non-favorite`() {
        val emu = entry("emu-1", deviceModel = "emulator-5554", gamePackage = "com.example.realgame")

        SessionHistory.addEntry(emu)

        val saved = SessionHistory.load().first { it.id == "emu-1" }
        assertFalse(saved.isFavorite,
            "emulator sessions are test sessions and must NOT auto-favorite")
    }

    @Test
    fun `addEntry preserves explicit user favoriting on a fake session`() {
        // The user can still manually mark a fake favorite — the auto-favorite logic
        // only ADDS the flag for real sessions; it never CLEARS an explicit favorite.
        val fakeButFavorite = entry(
            id = "fake-fav",
            deviceModel = "Fake",
            gamePackage = "com.test.game",
            isFavorite = true,
        )

        SessionHistory.addEntry(fakeButFavorite)

        val saved = SessionHistory.load().first { it.id == "fake-fav" }
        assertTrue(saved.isFavorite,
            "if the user explicitly favorited a fake, addEntry must not strip the flag")
    }

    @Test
    fun `manual unfavorite of a real session persists across save and load`() {
        val real = entry("real-x", deviceModel = "SM-S911B", gamePackage = "com.example.realgame")
        SessionHistory.addEntry(real)
        // It is auto-favorited at this point.
        assertTrue(SessionHistory.load().first { it.id == "real-x" }.isFavorite)

        // The user toggles it off (via the in-app star icon).
        SessionHistory.toggleFavorite("real-x")

        // Round-trip through load() to prove the state actually survived to disk.
        val reloaded = SessionHistory.load().first { it.id == "real-x" }
        assertFalse(reloaded.isFavorite,
            "manual unfavorite must persist — Layer 2 only auto-promotes on insert, not on every load")
    }

    // ===== UI counter visible classification =====

    @Test
    fun `bulk insert of fake sessions does not promote them to favorites`() {
        // Reproduces the exact incident: five quick Fake-mode tests.
        repeat(5) { i ->
            SessionHistory.addEntry(
                entry("burst-$i", deviceModel = "Fake", gamePackage = "com.test.game")
            )
        }

        val favs = SessionHistory.load().count { it.isFavorite }
        assertEquals(0, favs,
            "burst of fake sessions must not auto-favorite — they're meant to evict each other freely")
    }
}
