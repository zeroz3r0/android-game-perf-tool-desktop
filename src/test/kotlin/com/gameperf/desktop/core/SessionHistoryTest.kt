package com.gameperf.desktop.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SessionHistory]. Each test runs against an isolated temporary
 * `history.json` file injected via [SessionHistory.historyFileOverride] so we never
 * touch the user's real `~/GamePerf Reports/history.json`.
 *
 * No external dependencies — kotlin.test only. The temp file lifecycle is managed
 * with `Files.createTempFile` + `@BeforeTest`/`@AfterTest`.
 */
class SessionHistoryTest {

    private lateinit var tempFile: File

    private fun makeEntry(id: String, date: String = "01/01/2026 00:00"): SessionHistory.HistoryEntry =
        SessionHistory.HistoryEntry(
            id = id,
            name = "session-$id",
            gamePackage = "com.example.test",
            deviceModel = "TestDevice",
            grade = 'A',
            deviceGrade = 'A',
            avgFps = 60,
            duration = 60,
            date = date,
            reportPath = "/tmp/informe_$id.html",
            videoPath = "/tmp/video_$id.mp4"
        )

    @BeforeTest
    fun setUp() {
        // Use a tempfile inside a fresh dir so the parentFile mkdirs() in save() is harmless.
        val dir = Files.createTempDirectory("sessionhistory-test-").toFile()
        tempFile = File(dir, "history.json")
        SessionHistory.historyFileOverride = tempFile
    }

    @AfterTest
    fun tearDown() {
        SessionHistory.historyFileOverride = null
        runCatching { tempFile.delete() }
        runCatching { tempFile.parentFile?.delete() }
    }

    // ===== addEntry =====

    @Test
    fun `addEntry below limit returns empty list`() {
        // Seed history with 3 entries via direct save() to skip the addEntry path.
        SessionHistory.save(listOf(makeEntry("a"), makeEntry("b"), makeEntry("c")))
        assertEquals(3, SessionHistory.load().size)

        val pruned = SessionHistory.addEntry(makeEntry("d"))

        assertTrue(pruned.isEmpty(), "below limit should return empty list")
        assertEquals(4, SessionHistory.load().size)
    }

    @Test
    fun `addEntry at limit returns oldest as pruned`() {
        // 5 entries already (MAX_ENTRIES). The new one pushes the bottom one out.
        SessionHistory.save(
            (1..SessionHistory.MAX_ENTRIES).map { makeEntry("e$it") }
        )
        assertEquals(SessionHistory.MAX_ENTRIES, SessionHistory.load().size)

        val pruned = SessionHistory.addEntry(makeEntry("new"))

        assertEquals(1, pruned.size, "exactly one entry should be evicted")
        // The list is sorted by insertion order: e1 was inserted first → ends up at the bottom.
        // After save() we get [e1, e2, e3, e4, e5]. Then addEntry inserts "new" at index 0 →
        // [new, e1, e2, e3, e4, e5] → take(5) = [new, e1, e2, e3, e4] → evicted = [e5].
        assertEquals("e5", pruned.first().id)
        assertEquals(SessionHistory.MAX_ENTRIES, SessionHistory.load().size)
    }

    @Test
    fun `addEntry insert at index zero`() {
        SessionHistory.save(listOf(makeEntry("old1"), makeEntry("old2")))

        SessionHistory.addEntry(makeEntry("freshly-added"))

        val all = SessionHistory.load()
        assertEquals("freshly-added", all.first().id, "new entry must land at index 0")
    }

    // ===== deleteEntry =====

    @Test
    fun `deleteEntry existing id returns removed entry`() {
        SessionHistory.save(listOf(makeEntry("a"), makeEntry("b"), makeEntry("c")))

        val removed = SessionHistory.deleteEntry("b")

        assertNotNull(removed, "should return the removed entry")
        assertEquals("b", removed.id)
        val remaining = SessionHistory.load().map { it.id }
        assertEquals(listOf("a", "c"), remaining)
    }

    @Test
    fun `deleteEntry missing id returns null`() {
        SessionHistory.save(listOf(makeEntry("a"), makeEntry("b")))

        val removed = SessionHistory.deleteEntry("missing")

        assertNull(removed)
        // History is unchanged.
        assertEquals(2, SessionHistory.load().size)
    }

    // ===== updateEntry =====

    @Test
    fun `updateEntry modifies in place`() {
        SessionHistory.save(listOf(makeEntry("a"), makeEntry("b"), makeEntry("c")))

        val original = SessionHistory.load().first { it.id == "b" }
        val mutated = original.copy(reportPath = "", videoPath = "")
        SessionHistory.updateEntry(mutated)

        val reloaded = SessionHistory.load().first { it.id == "b" }
        assertEquals("", reloaded.reportPath, "reportPath should be cleared")
        assertEquals("", reloaded.videoPath, "videoPath should be cleared")
        // Order preserved: id "b" still in the middle.
        assertEquals(listOf("a", "b", "c"), SessionHistory.load().map { it.id })
    }

    // ===== concurrency =====

    @Test
    fun `synchronized access concurrent add and delete does not corrupt json`() {
        // Pre-seed with 2 entries.
        SessionHistory.save(listOf(makeEntry("seed1"), makeEntry("seed2")))

        val executor = Executors.newFixedThreadPool(20)
        val latch = CountDownLatch(1)
        val tasks = mutableListOf<java.util.concurrent.Future<*>>()

        // 10 add tasks + 10 delete tasks racing through @Synchronized methods.
        for (i in 0 until 10) {
            tasks += executor.submit {
                latch.await()
                SessionHistory.addEntry(makeEntry("add-$i"))
            }
            tasks += executor.submit {
                latch.await()
                SessionHistory.deleteEntry("seed1") // most calls will return null after the first
            }
        }

        latch.countDown()
        for (t in tasks) {
            t.get(5, TimeUnit.SECONDS) // throws if a thread crashed
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))

        // The file must still be parseable as a valid history (no corruption).
        val finalEntries = SessionHistory.load()
        // The exact size depends on interleaving; what matters is no exceptions and parseable JSON.
        // Bound check: at most MAX_ENTRIES (retention enforced) and at least 0.
        assertTrue(finalEntries.size <= SessionHistory.MAX_ENTRIES,
            "history must not exceed MAX_ENTRIES after concurrent ops")
        // No duplicate ids — every entry should have a unique id field.
        val ids = finalEntries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "no duplicate ids in final state")
    }
}
