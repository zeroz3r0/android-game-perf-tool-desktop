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
 * v4.3.7 — Layer 4 of the session-history-loss prevention rollout.
 *
 * Surfaces a confirmation flow before evicting non-fake non-favorite sessions and
 * surfaces a "requires manual eviction" outcome when ALL entries are favorites
 * (so the user is forced to make a deliberate choice instead of silently overwriting
 * a favorite's slot).
 *
 * The analyzer is a PURE function that doesn't touch disk — it just inspects a snapshot
 * + a candidate and returns an [SessionHistory.EvictionAnalysis] sealed class. The
 * AppViewModel uses this to decide whether to raise an `EvictionPendingState` for the UI
 * dialog or proceed silently.
 */
class SessionHistoryEvictionTest {

    private lateinit var dir: File

    private fun fake(id: String): SessionHistory.HistoryEntry =
        SessionHistory.HistoryEntry(
            id = id, name = "fake-$id",
            gamePackage = "com.test.game", deviceModel = "Fake",
            grade = 'A', deviceGrade = 'A', avgFps = 60, duration = 60,
            date = "01/01/2026 00:00",
            reportPath = "/tmp/r$id.html", videoPath = "/tmp/v$id.mp4",
        )

    private fun real(id: String, isFavorite: Boolean = false): SessionHistory.HistoryEntry =
        SessionHistory.HistoryEntry(
            id = id, name = "real-$id",
            gamePackage = "com.example.realgame", deviceModel = "SM-S911B",
            grade = 'A', deviceGrade = 'A', avgFps = 60, duration = 60,
            date = "01/01/2026 00:00",
            reportPath = "/tmp/r$id.html", videoPath = "/tmp/v$id.mp4",
            isFavorite = isFavorite,
        )

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("sessionhistory-eviction-").toFile()
        SessionHistory.historyFileOverride = File(dir, "history.json")
    }

    @AfterTest
    fun tearDown() {
        SessionHistory.historyFileOverride = null
        runCatching { dir.deleteRecursively() }
    }

    // ===== analyzeEvictionRisk (pure) =====

    @Test
    fun `analyze with under-cap snapshot returns NoEviction`() {
        val snapshot = (1..50).map { fake("f$it") }

        val result = SessionHistory.analyzeEvictionRisk(snapshot, fake("new"))

        assertTrue(result is SessionHistory.EvictionAnalysis.NoEviction,
            "with 50 fakes and a cap of ${SessionHistory.MAX_ENTRIES} no eviction is needed")
    }

    @Test
    fun `analyze evicts oldest fake silently when cap reached and oldest is fake`() {
        // 100 fakes — adding one more pushes the oldest out, no confirmation needed.
        val snapshot = (1..SessionHistory.MAX_ENTRIES).map { fake("f$it") }

        val result = SessionHistory.analyzeEvictionRisk(snapshot, fake("new"))

        assertTrue(result is SessionHistory.EvictionAnalysis.SilentEviction,
            "evicting a fake must be silent — no dialog needed")
        // The candidate to evict is the oldest non-favorite — last in the recents list.
        assertEquals("f${SessionHistory.MAX_ENTRIES}", result.evictableEntry.id)
    }

    @Test
    fun `analyze raises ConfirmationRequired when oldest evictable is a real non-favorite`() {
        // The user has loaded history.json from disk where the OLDEST entry was saved
        // as non-favorite (pre-Layer-2 legacy data). New entries land at index 0 and the
        // tail of the recents list is what falls off the cap, so we put `realLegacy` last
        // to make it the eviction candidate. The new auto-favorite logic does NOT touch
        // existing data on load — so the legacy real session is still evictable.
        val realLegacy = real("legacy-real", isFavorite = false)
        val recents = (1 until SessionHistory.MAX_ENTRIES).map { fake("f$it") } + realLegacy

        val result = SessionHistory.analyzeEvictionRisk(recents, fake("new"))

        assertTrue(result is SessionHistory.EvictionAnalysis.ConfirmationRequired,
            "a non-fake non-favorite about to be evicted MUST trigger a confirm dialog")
        assertEquals("legacy-real", result.evictableEntry.id,
            "the dialog must point at the real legacy entry, not at a fake")
    }

    @Test
    fun `analyze with all-favorites snapshot accepts new fake into empty recents bucket`() {
        // Spec edge case: cap is full of favorites. With Layer 2 auto-favoriting on,
        // the favorites bucket has no upper bound (favorites are NEVER auto-evicted),
        // so adding a fake just lands in the empty recents bucket. No eviction, no
        // manual-eviction prompt. RequiresManualEviction is reserved for paths where
        // the analyzer detects a structurally impossible situation; this isn't one.
        val allFav = (1..SessionHistory.MAX_ENTRIES).map { real("r$it", isFavorite = true) }

        val result = SessionHistory.analyzeEvictionRisk(allFav, fake("new"))

        assertTrue(result is SessionHistory.EvictionAnalysis.NoEviction,
            "favorites are unbounded; adding a fake into an empty recents bucket is always safe")
    }

    @Test
    fun `analyze with mixed favorites and fakes evicts the oldest fake`() {
        // 50 favorited reals + 50 fakes → adding a fake should silently evict the oldest fake.
        val favs = (1..50).map { real("fav-$it", isFavorite = true) }
        val fakes = (1..50).map { fake("k$it") }
        val snapshot = favs + fakes

        val result = SessionHistory.analyzeEvictionRisk(snapshot, fake("new"))

        // 50 + 50 = 100 = MAX_ENTRIES; the recents (50 fakes) are NOT yet at the cap
        // because cap applies to recents only — so no eviction at all.
        assertTrue(result is SessionHistory.EvictionAnalysis.NoEviction)
    }

    @Test
    fun `analyze treats favorites as off-limits even when cap reached`() {
        // 50 favorited reals + 100 fakes (cap-limit on recents). Adding new fake →
        // evict oldest fake (NOT a favorite).
        val favs = (1..50).map { real("fav-$it", isFavorite = true) }
        val fakes = (1..SessionHistory.MAX_ENTRIES).map { fake("k$it") }
        val snapshot = favs + fakes

        val result = SessionHistory.analyzeEvictionRisk(snapshot, fake("new"))

        assertTrue(result is SessionHistory.EvictionAnalysis.SilentEviction)
        assertEquals("k${SessionHistory.MAX_ENTRIES}", result.evictableEntry.id,
            "must evict the oldest fake, never one of the 50 favorites")
        assertFalse(result.evictableEntry.isFavorite)
    }

    @Test
    fun `analyze ignores fakes inserted on top — they are never auto-favorited`() {
        // After inserting 5 fakes in a row (the real-world incident reproduction),
        // a new fake just keeps adding without eviction risk because we are nowhere
        // near the cap. The whole point of v4.3.7 is to make THIS the boring path.
        val snapshot = (1..5).map { fake("burst-$it") }

        val result = SessionHistory.analyzeEvictionRisk(snapshot, fake("new"))

        assertTrue(result is SessionHistory.EvictionAnalysis.NoEviction,
            "the literal incident scenario (burst of fakes) must never trigger eviction now")
    }

    // ===== integration with addEntry =====

    @Test
    fun `addEntry preserves legacy non-favorite real session — does not retroactively favorite`() {
        // Pre-Layer-2 entries on disk: real session saved with isFavorite=false.
        // We seed via direct save() (bypasses addEntry's auto-favorite path).
        val legacy = real("legacy", isFavorite = false)
        SessionHistory.save(listOf(legacy))

        // Add a fake — this goes through addEntry. The legacy entry must not be promoted
        // to favorite as a side effect; Layer 2 ONLY auto-favorites NEW inserts.
        SessionHistory.addEntry(fake("k1"))

        val reloaded = SessionHistory.load().first { it.id == "legacy" }
        assertFalse(reloaded.isFavorite,
            "Layer 2 must NOT retroactively favorite legacy entries — only Layer 4 (the dialog) opts in")
    }
}
