package com.gameperf.desktop.core

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
 * v4.3.7 — Layer 3 of the session-history-loss prevention rollout.
 *
 * Every [SessionHistory.save] call now rotates a 3-deep backup chain so a corrupted
 * `history.json` (or an unintended evict) can be recovered from the previous saves on
 * disk:
 *
 *  - `history.json.bak.1` — the previous save's contents (most recent backup)
 *  - `history.json.bak.2` — the save before that
 *  - `history.json.bak.3` — the save before that (oldest, evicted on next rotation)
 *
 * Rotation order on save N:
 *  1. bak.2 → bak.3 (oldest evicted)
 *  2. bak.1 → bak.2
 *  3. current history.json → bak.1
 *  4. write new payload to history.json (atomic via .tmp + Files.move)
 *
 * Recovery picks the file with the most entries (or the newest mtime on tie) and copies
 * it back to history.json, returning a [SessionHistory.RecoveryReport].
 */
class SessionHistoryBackupTest {

    private lateinit var dir: File
    private lateinit var historyFile: File

    private fun entry(id: String): SessionHistory.HistoryEntry =
        SessionHistory.HistoryEntry(
            id = id,
            name = "session-$id",
            gamePackage = "com.example.test",
            deviceModel = "TestDevice",
            grade = 'A',
            deviceGrade = 'A',
            avgFps = 60,
            duration = 60,
            date = "01/01/2026 00:00",
            reportPath = "/tmp/informe_$id.html",
            videoPath = "/tmp/video_$id.mp4",
        )

    private fun bak(n: Int) = File(dir, "history.json.bak.$n")

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("sessionhistory-backup-").toFile()
        historyFile = File(dir, "history.json")
        SessionHistory.historyFileOverride = historyFile
    }

    @AfterTest
    fun tearDown() {
        SessionHistory.historyFileOverride = null
        runCatching { dir.deleteRecursively() }
    }

    // ===== rotation =====

    @Test
    fun `first save creates history but no backup yet`() {
        SessionHistory.save(listOf(entry("a")))

        assertTrue(historyFile.exists(), "history.json must exist after first save")
        assertFalse(bak(1).exists(), "no backup should exist yet — there was nothing to back up")
        assertFalse(bak(2).exists())
        assertFalse(bak(3).exists())
    }

    @Test
    fun `second save promotes first save to bak1`() {
        SessionHistory.save(listOf(entry("v1")))
        val firstSnapshot = historyFile.readText()

        SessionHistory.save(listOf(entry("v1"), entry("v2")))

        assertTrue(bak(1).exists(), "second save must produce bak.1")
        assertEquals(firstSnapshot, bak(1).readText(),
            "bak.1 must hold the EXACT content of the previous history.json")
        assertFalse(bak(2).exists(), "after only two saves bak.2 should not exist yet")
    }

    @Test
    fun `third save shifts older backups down the chain`() {
        SessionHistory.save(listOf(entry("v1")))
        val s1 = historyFile.readText()
        SessionHistory.save(listOf(entry("v1"), entry("v2")))
        val s2 = historyFile.readText()

        SessionHistory.save(listOf(entry("v1"), entry("v2"), entry("v3")))

        assertEquals(s2, bak(1).readText(), "bak.1 should now hold the second save")
        assertEquals(s1, bak(2).readText(), "bak.2 should now hold the first save")
        assertFalse(bak(3).exists(), "bak.3 should not exist yet")
    }

    @Test
    fun `fourth save fills bak3 with the oldest still-existing snapshot`() {
        SessionHistory.save(listOf(entry("v1")))
        val s1 = historyFile.readText()
        SessionHistory.save(listOf(entry("v1"), entry("v2")))
        val s2 = historyFile.readText()
        SessionHistory.save(listOf(entry("v1"), entry("v2"), entry("v3")))
        val s3 = historyFile.readText()

        SessionHistory.save(listOf(entry("v1"), entry("v2"), entry("v3"), entry("v4")))

        assertEquals(s3, bak(1).readText())
        assertEquals(s2, bak(2).readText())
        assertEquals(s1, bak(3).readText(), "bak.3 should now hold the oldest still-tracked save")
    }

    @Test
    fun `fifth save evicts the oldest backup`() {
        SessionHistory.save(listOf(entry("v1"))) // s1
        val s2Entries = listOf(entry("v1"), entry("v2"))
        SessionHistory.save(s2Entries)
        val s2 = historyFile.readText()
        val s3Entries = s2Entries + entry("v3")
        SessionHistory.save(s3Entries)
        val s3 = historyFile.readText()
        val s4Entries = s3Entries + entry("v4")
        SessionHistory.save(s4Entries)
        val s4 = historyFile.readText()

        // Fifth save: s1 should fall off the chain entirely.
        SessionHistory.save(s4Entries + entry("v5"))

        assertEquals(s4, bak(1).readText(), "bak.1 should now hold s4")
        assertEquals(s3, bak(2).readText(), "bak.2 should now hold s3")
        assertEquals(s2, bak(3).readText(), "bak.3 should now hold s2 — s1 has been evicted")
    }

    // ===== recovery =====

    @Test
    fun `recoverFromBackup with no backups returns a no-op report`() {
        SessionHistory.save(listOf(entry("a"), entry("b")))

        val report = SessionHistory.recoverFromBackup()

        assertEquals(2, report.entriesBefore, "must reflect the live history's size before recovery")
        assertEquals(2, report.entriesAfter, "no backups present → no recovery → entriesAfter == before")
        assertNull(report.restoredFrom, "no backup file should be reported")
    }

    @Test
    fun `recoverFromBackup picks the backup with the most entries`() {
        // Save 5 entries — 1 in current, 4 in bak.1, 3 in bak.2 ... not realistic without
        // forcing it manually. We seed the chain by hand:
        // current → 1 entry, bak.1 → 5 entries (the "lost" history we want to recover).
        SessionHistory.save(listOf(entry("only-survivor")))
        val richHistory = (1..5).map { entry("rescued-$it") }
        bak(1).writeText(buildJsonForEntries(richHistory))

        val report = SessionHistory.recoverFromBackup()

        assertEquals(1, report.entriesBefore)
        assertEquals(5, report.entriesAfter,
            "recovery must restore the backup with the largest entry count")
        assertEquals("history.json.bak.1", report.restoredFrom)

        val restored = SessionHistory.load()
        assertEquals(5, restored.size, "history.json must now hold the restored entries")
        assertTrue(restored.any { it.id == "rescued-1" })
    }

    @Test
    fun `recoverFromBackup with smaller or equal backups does not overwrite live history`() {
        // current has 5; backups have 2 and 1 — recovery would LOSE data, so it must skip.
        val live = (1..5).map { entry("live-$it") }
        SessionHistory.save(live)
        bak(1).writeText(buildJsonForEntries(listOf(entry("a"), entry("b"))))
        bak(2).writeText(buildJsonForEntries(listOf(entry("a"))))

        val report = SessionHistory.recoverFromBackup()

        assertEquals(5, report.entriesBefore)
        assertEquals(5, report.entriesAfter, "no backup is bigger than current → recovery is a no-op")
        assertNull(report.restoredFrom)
        // history.json content unchanged
        assertEquals(5, SessionHistory.load().size)
    }

    @Test
    fun `recoverFromBackup tie-breaks on most recent mtime when sizes are equal`() {
        // bak.1 and bak.2 both have 3 entries, but bak.1 is more recent.
        SessionHistory.save(listOf(entry("only")))
        bak(2).writeText(buildJsonForEntries((1..3).map { entry("older-$it") }))
        Thread.sleep(10) // ensure distinct mtime
        bak(1).writeText(buildJsonForEntries((1..3).map { entry("newer-$it") }))

        val report = SessionHistory.recoverFromBackup()

        assertNotNull(report.restoredFrom)
        assertEquals("history.json.bak.1", report.restoredFrom,
            "on size tie, the most recent backup wins")
        val restored = SessionHistory.load()
        assertTrue(restored.any { it.id == "newer-1" }, "should restore the newer set")
    }

    // ===== atomic write =====

    @Test
    fun `save uses tmp + atomic move so a half-finished write does not corrupt history`() {
        SessionHistory.save(listOf(entry("first")))
        val before = historyFile.readText()

        // Drop a corrupt .tmp file from a hypothetical previous failed save attempt.
        val tmp = File(dir, "history.json.tmp")
        tmp.writeText("not even close to valid JSON {")

        // A NEW save must overwrite history.json cleanly via atomic move and not leave
        // partial state behind.
        SessionHistory.save(listOf(entry("first"), entry("second")))

        val parsed = SessionHistory.load()
        assertEquals(2, parsed.size, "history.json must be valid after the new save")
        assertTrue(parsed.any { it.id == "second" })
        assertTrue(historyFile.readText() != before,
            "history.json must reflect the new payload, not the leftover tmp")
    }

    @Test
    fun `backup files survive a json round-trip — content is byte-identical`() {
        // The rotation copies file BYTES, so re-saving and re-reading must not mutate
        // backup files (no JSON canonicalization drift across rotation).
        val payload = listOf(entry("byte-stable"), entry("byte-stable-2"))
        SessionHistory.save(payload)
        val onDisk = historyFile.readText()
        SessionHistory.save(payload + entry("triggers-rotation"))

        assertEquals(onDisk, bak(1).readText(),
            "bak.1 must be byte-identical to the prior history.json — no re-serialization")
    }

    // ===== helpers =====

    /**
     * Hand-builds the JSON payload SessionHistory.save would produce for a given entry list.
     * We delegate to [SessionHistory.save] itself by overriding the file location, then
     * read back, so the format stays in lockstep with the real serializer.
     */
    private fun buildJsonForEntries(entries: List<SessionHistory.HistoryEntry>): String {
        val isolatedDir = Files.createTempDirectory("backup-builder-").toFile()
        val isolated = File(isolatedDir, "history.json")
        val savedOverride = SessionHistory.historyFileOverride
        SessionHistory.historyFileOverride = isolated
        try {
            SessionHistory.save(entries)
            return isolated.readText()
        } finally {
            SessionHistory.historyFileOverride = savedOverride
            runCatching { isolatedDir.deleteRecursively() }
        }
    }
}
