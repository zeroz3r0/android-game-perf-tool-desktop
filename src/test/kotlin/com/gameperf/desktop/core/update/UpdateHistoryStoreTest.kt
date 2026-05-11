package com.gameperf.desktop.core.update

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RED-first tests for [UpdateHistoryStore] (jsonl append-only, cap-100, corrupt-tolerant).
 *
 * Drives the API per design §3 + §7 test plan:
 *   - append creates parent dir + file when missing
 *   - append twice persists ordered entries
 *   - cap-100 FIFO eviction on overflow
 *   - corrupt mid-file lines silently skipped on read
 *   - missing file returns empty list (no exception)
 *   - recentAttempts(limit) respects the limit
 *
 * Style mirrors `InstallLocationTest`: `Files.createTempDirectory` + `@BeforeTest`/`@AfterTest`,
 * no external test deps beyond `kotlin.test`.
 */
class UpdateHistoryStoreTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("update-history-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    private fun newAttempt(
        timestamp: Long = 1_700_000_000_000L,
        toVersion: String = "4.4.1",
        outcome: UpdateOutcome = UpdateOutcome.Success,
    ): UpdateAttempt = UpdateAttempt(
        timestamp = timestamp,
        fromVersion = "4.4.0",
        toVersion = toVersion,
        outcome = outcome,
        durationMs = 1_500L,
    )

    // ═══════ append ═══════

    @Test
    fun `append to non-existent file creates the file with one entry`() {
        val file = File(tempDir, "history.jsonl")
        val store = FileUpdateHistoryStore(file)
        val attempt = newAttempt()

        store.append(attempt)

        assertTrue(file.exists(), "history file must be created on first append")
        val recent = store.recentAttempts()
        assertEquals(1, recent.size)
        assertEquals(attempt, recent.single())
    }

    @Test
    fun `append twice then recentAttempts returns both in chronological order`() {
        val file = File(tempDir, "history.jsonl")
        val store = FileUpdateHistoryStore(file)
        val first = newAttempt(timestamp = 1L, toVersion = "4.4.1")
        val second = newAttempt(timestamp = 2L, toVersion = "4.4.2")

        store.append(first)
        store.append(second)

        val recent = store.recentAttempts()
        assertEquals(listOf(first, second), recent)
    }

    @Test
    fun `append also creates the parent directory when missing`() {
        val nested = File(tempDir, "updates/history.jsonl")
        val store = FileUpdateHistoryStore(nested)

        store.append(newAttempt())

        assertTrue(nested.parentFile.exists(), "parent dir must be created on first append")
        assertTrue(nested.exists())
    }

    // ═══════ cap-100 FIFO eviction ═══════

    @Test
    fun `append beyond cap-100 evicts the oldest entries (FIFO)`() {
        val file = File(tempDir, "history.jsonl")
        val store = FileUpdateHistoryStore(file)

        // Append 105 distinct attempts; oldest 5 must be evicted.
        for (i in 1..105) {
            store.append(newAttempt(timestamp = i.toLong(), toVersion = "4.4.$i"))
        }

        val recent = store.recentAttempts(limit = 200)
        assertEquals(100, recent.size, "file must be capped at 100 lines")
        assertEquals(6L, recent.first().timestamp, "oldest 5 entries must be evicted")
        assertEquals(105L, recent.last().timestamp, "newest entry must be preserved")
    }

    // ═══════ corrupt-tolerance ═══════

    @Test
    fun `corrupt line in the middle is skipped silently and the rest is parsed`() {
        val file = File(tempDir, "history.jsonl")
        val good1 = newAttempt(timestamp = 1L, toVersion = "4.4.1")
        val good2 = newAttempt(timestamp = 2L, toVersion = "4.4.2")
        // Hand-craft a file with: good1, garbage, good2.
        file.writeText(
            buildString {
                appendLine(writeJsonlLine(good1))
                appendLine("this is not json {{{")
                appendLine(writeJsonlLine(good2))
            }
        )
        val store = FileUpdateHistoryStore(file)

        val recent = store.recentAttempts()

        assertEquals(listOf(good1, good2), recent, "corrupt line must be silently skipped")
    }

    // ═══════ missing-file tolerance ═══════

    @Test
    fun `recentAttempts on a missing file returns an empty list without throwing`() {
        val file = File(tempDir, "does-not-exist.jsonl")
        val store = FileUpdateHistoryStore(file)

        val recent = store.recentAttempts()

        assertEquals(emptyList<UpdateAttempt>(), recent)
    }

    // ═══════ recentAttempts(limit) ═══════

    @Test
    fun `recentAttempts respects an explicit limit returning the newest N entries`() {
        val file = File(tempDir, "history.jsonl")
        val store = FileUpdateHistoryStore(file)
        for (i in 1..20) {
            store.append(newAttempt(timestamp = i.toLong(), toVersion = "4.4.$i"))
        }

        val recent = store.recentAttempts(limit = 5)

        assertEquals(5, recent.size)
        // Newest 5: timestamps 16..20, in chronological order (file order).
        assertEquals(listOf(16L, 17L, 18L, 19L, 20L), recent.map { it.timestamp })
    }

    // ═══════ trailing newline tolerance ═══════

    @Test
    fun `recentAttempts tolerates a trailing newline at the end of the file`() {
        val file = File(tempDir, "history.jsonl")
        val store = FileUpdateHistoryStore(file)
        store.append(newAttempt(timestamp = 1L))
        // Force a trailing newline by appending one extra; file should still parse cleanly.
        file.appendText("\n")

        val recent = store.recentAttempts()

        assertEquals(1, recent.size)
    }

    // ═══════ optional fields preserved ═══════

    @Test
    fun `append preserves optional errorMessage and helperLogTail through the roundtrip`() {
        val file = File(tempDir, "history.jsonl")
        val store = FileUpdateHistoryStore(file)
        val attempt = UpdateAttempt(
            timestamp = 42L,
            fromVersion = "4.4.0",
            toVersion = "4.4.1",
            outcome = UpdateOutcome.FailedDownload(httpStatus = 404, message = "not found"),
            durationMs = 250L,
            errorMessage = "asset missing",
            helperLogTail = "tail line A\\ntail line B",
        )

        store.append(attempt)

        val parsed = store.recentAttempts().single()
        assertEquals(attempt, parsed)
        assertNotNull(parsed.errorMessage)
        assertNotNull(parsed.helperLogTail)
    }
}
