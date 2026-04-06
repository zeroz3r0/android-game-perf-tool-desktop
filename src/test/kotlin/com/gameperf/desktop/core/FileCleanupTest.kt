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
 * Unit tests for [FileCleanup]. Each test runs against an isolated temporary directory
 * injected via [FileCleanup.reportsDirOverride] so we never touch the user's real
 * `~/GamePerf Reports/` folder.
 *
 * No external test dependencies (kotlin.test only): the temp dir lifecycle is managed
 * with `Files.createTempDirectory` + `@BeforeTest`/`@AfterTest`. Recursive teardown is
 * intentional and tolerant of files left behind by buggy code under test.
 */
class FileCleanupTest {

    private lateinit var tempDir: File

    /**
     * Build a HistoryEntry with the minimum fields the cleanup code touches plus
     * sensible defaults for the rest. The defaults match production usage so the
     * tests double as a smoke check on `HistoryEntry` field nullability.
     */
    private fun entry(
        id: String = "test-id",
        reportPath: String = "",
        videoPath: String = ""
    ): SessionHistory.HistoryEntry = SessionHistory.HistoryEntry(
        id = id,
        name = "test",
        gamePackage = "com.example.test",
        deviceModel = "TestDevice",
        grade = 'A',
        deviceGrade = 'A',
        avgFps = 60,
        duration = 60,
        date = "01/01/2026 00:00",
        reportPath = reportPath,
        videoPath = videoPath
    )

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("filecleanup-test-").toFile()
        FileCleanup.reportsDirOverride = tempDir
    }

    @AfterTest
    fun tearDown() {
        FileCleanup.reportsDirOverride = null
        // Recursive cleanup, tolerant of any leftover files.
        tempDir.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    // ===== extractSessionId =====

    @Test
    fun `extractSessionId matches modern pattern`() {
        val sid = FileCleanup.extractSessionId("/tmp/x/video_20260331_132149_0.mp4")
        assertEquals("20260331_132149", sid)
    }

    @Test
    fun `extractSessionId returns null for legacy recording`() {
        assertNull(FileCleanup.extractSessionId("/tmp/x/recording_1.mp4"))
    }

    @Test
    fun `extractSessionId returns null for empty path`() {
        assertNull(FileCleanup.extractSessionId(""))
    }

    @Test
    fun `extractSessionId returns null for unexpected path`() {
        assertNull(FileCleanup.extractSessionId("/weird/path.mp4"))
        assertNull(FileCleanup.extractSessionId("/x/video_no_underscore.mp4"))
    }

    // ===== deleteSessionFiles =====

    @Test
    fun `deleteSessionFiles deletes html and all segments`() {
        val sid = "20260331_132149"
        val html = File(tempDir, "informe_${sid}.html").apply { writeText("html") }
        val seg0 = File(tempDir, "video_${sid}_0.mp4").apply { writeText("v0") }
        val seg1 = File(tempDir, "video_${sid}_1.mp4").apply { writeText("v1") }
        val seg2 = File(tempDir, "video_${sid}_2.mp4").apply { writeText("v2") }

        FileCleanup.deleteSessionFiles(
            entry(reportPath = html.absolutePath, videoPath = seg0.absolutePath)
        )

        assertFalse(html.exists(), "html should be deleted")
        assertFalse(seg0.exists(), "seg0 should be deleted")
        assertFalse(seg1.exists(), "seg1 should be deleted")
        assertFalse(seg2.exists(), "seg2 should be deleted")
    }

    @Test
    fun `deleteSessionFiles tolerates empty videoPath`() {
        val html = File(tempDir, "informe_test.html").apply { writeText("html") }

        // Must not throw despite videoPath = ""
        FileCleanup.deleteSessionFiles(entry(reportPath = html.absolutePath, videoPath = ""))

        assertFalse(html.exists(), "html should still be deleted")
    }

    @Test
    fun `deleteSessionFiles tolerates missing files`() {
        val ghostHtml = File(tempDir, "informe_ghost.html")
        val ghostVideo = File(tempDir, "video_20260101_000000_0.mp4")

        // Both files do not exist on disk. Must not throw.
        FileCleanup.deleteSessionFiles(
            entry(reportPath = ghostHtml.absolutePath, videoPath = ghostVideo.absolutePath)
        )

        // Sanity check: still missing.
        assertFalse(ghostHtml.exists())
        assertFalse(ghostVideo.exists())
    }

    @Test
    fun `deleteSessionFiles legacy recording deletes literal`() {
        val legacy = File(tempDir, "recording_1.mp4").apply { writeText("legacy") }

        FileCleanup.deleteSessionFiles(entry(videoPath = legacy.absolutePath))

        assertFalse(legacy.exists(), "legacy recording_1.mp4 should be deleted literally")
    }

    @Test
    fun `deleteSessionFiles does not touch other sessions`() {
        val sidA = "20260101_100000"
        val sidB = "20260202_200000"
        val htmlA = File(tempDir, "informe_${sidA}.html").apply { writeText("a") }
        val segA0 = File(tempDir, "video_${sidA}_0.mp4").apply { writeText("a0") }
        val htmlB = File(tempDir, "informe_${sidB}.html").apply { writeText("b") }
        val segB0 = File(tempDir, "video_${sidB}_0.mp4").apply { writeText("b0") }
        val segB1 = File(tempDir, "video_${sidB}_1.mp4").apply { writeText("b1") }

        FileCleanup.deleteSessionFiles(
            entry(reportPath = htmlA.absolutePath, videoPath = segA0.absolutePath)
        )

        assertFalse(htmlA.exists())
        assertFalse(segA0.exists())
        assertTrue(htmlB.exists(), "session B html should survive")
        assertTrue(segB0.exists(), "session B seg0 should survive")
        assertTrue(segB1.exists(), "session B seg1 should survive")
    }

    // ===== pruneOrphans =====

    @Test
    fun `pruneOrphans deletes files not in snapshot`() {
        // 5 HTMLs on disk, snapshot only references 3 -> 2 should be deleted.
        val referenced = (1..3).map { i ->
            File(tempDir, "informe_ref$i.html").apply { writeText("r$i") }
        }
        val orphans = (1..2).map { i ->
            File(tempDir, "informe_orphan$i.html").apply { writeText("o$i") }
        }

        val snapshot = referenced.map { f ->
            entry(id = f.name, reportPath = f.absolutePath)
        }

        val result = FileCleanup.pruneOrphans(snapshot)

        assertEquals(2, result.deletedFiles)
        referenced.forEach { assertTrue(it.exists(), "referenced ${it.name} should survive") }
        orphans.forEach { assertFalse(it.exists(), "orphan ${it.name} should be deleted") }
        assertTrue(result.repairedEntries.isEmpty(), "no repairs expected")
    }

    @Test
    fun `pruneOrphans repairs entries with broken paths`() {
        // Entry references a video that does not exist on disk -> should be repaired.
        val brokenHtml = File(tempDir, "informe_broken.html") // never created
        val brokenVideo = File(tempDir, "video_20260101_000000_0.mp4") // never created

        val snapshot = listOf(
            entry(
                id = "broken-1",
                reportPath = brokenHtml.absolutePath,
                videoPath = brokenVideo.absolutePath
            )
        )

        val result = FileCleanup.pruneOrphans(snapshot)

        assertEquals(1, result.repairedEntries.size)
        val repaired = result.repairedEntries.first()
        assertEquals("broken-1", repaired.id)
        assertEquals("", repaired.reportPath, "broken reportPath must be cleared")
        assertEquals("", repaired.videoPath, "broken videoPath must be cleared")
    }

    @Test
    fun `pruneOrphans skips subdirectories`() {
        // updates/ subdir with a file inside that uses the whitelist prefix
        val updatesDir = File(tempDir, "updates").apply { mkdirs() }
        val payload = File(updatesDir, "informe_leftover.html").apply { writeText("payload") }

        val result = FileCleanup.pruneOrphans(snapshot = emptyList())

        assertTrue(payload.exists(), "files inside subdirectories must never be touched")
        assertTrue(updatesDir.exists(), "updates/ subdir must survive")
        assertEquals(0, result.deletedFiles)
    }

    @Test
    fun `pruneOrphans skips non whitelist files`() {
        val notes = File(tempDir, "my_notes.txt").apply { writeText("personal") }
        val random = File(tempDir, "random_data.json").apply { writeText("data") }

        val result = FileCleanup.pruneOrphans(snapshot = emptyList())

        assertTrue(notes.exists(), "user files outside the whitelist must survive")
        assertTrue(random.exists(), "random files must survive")
        assertEquals(0, result.deletedFiles)
    }

    @Test
    fun `pruneOrphans skips history json`() {
        // history.json is in the WHITELIST_PREFIXES blacklist (excluded by name).
        val historyJson = File(tempDir, "history.json").apply { writeText("[]") }
        // Also add an orphan so we know prune is actually scanning.
        val orphan = File(tempDir, "informe_orphan.html").apply { writeText("o") }

        val result = FileCleanup.pruneOrphans(snapshot = emptyList())

        assertTrue(historyJson.exists(), "history.json must never be deleted by pruneOrphans")
        assertFalse(orphan.exists(), "orphan should be deleted (sanity check)")
        assertEquals(1, result.deletedFiles)
    }

    @Test
    fun `pruneOrphans empty folder no op`() {
        // Empty dir + history.json only.
        File(tempDir, "history.json").writeText("[]")

        val result = FileCleanup.pruneOrphans(snapshot = emptyList())

        assertEquals(0, result.deletedFiles)
        assertTrue(result.repairedEntries.isEmpty())
    }

    // ===== Bonus regression: segment preservation =====

    @Test
    fun `pruneOrphans preserves video segments whose sessionId is referenced`() {
        // The JSON only stores video_20260101_120000_0.mp4, but disk has _0, _1, _2.
        // Pass 1 must NOT delete _1 or _2 because their sessionId is referenced.
        val sid = "20260101_120000"
        val seg0 = File(tempDir, "video_${sid}_0.mp4").apply { writeText("0") }
        val seg1 = File(tempDir, "video_${sid}_1.mp4").apply { writeText("1") }
        val seg2 = File(tempDir, "video_${sid}_2.mp4").apply { writeText("2") }

        val snapshot = listOf(entry(videoPath = seg0.absolutePath))

        val result = FileCleanup.pruneOrphans(snapshot)

        assertTrue(seg0.exists())
        assertTrue(seg1.exists(), "seg1 must survive (segment-aware preservation)")
        assertTrue(seg2.exists(), "seg2 must survive (segment-aware preservation)")
        assertEquals(0, result.deletedFiles)
    }
}
