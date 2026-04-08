package com.gameperf.desktop.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
// v3.1.13: this project's test runtime is kotlin-test-junit → JUnit 4 (NOT Jupiter).
// We use `org.junit.Assume.assumeTrue` instead of the Jupiter equivalent — it has
// the same skip semantics and is available without changing the build dependencies.
import org.junit.Assume.assumeTrue

/**
 * Tests for [AdbBridge.concatSegments] and [AdbBridge.isValidVideoFile] resilience
 * to corrupt segments — the v3.1.12 fix for the moov-atom-not-found bug.
 *
 * Background: when `screenrecord` is killed by the chain timer before Android has
 * time to flush the MP4 moov atom, the resulting `_0.mp4` file exists with non-zero
 * size but is unparseable by ffprobe (`moov atom not found`). Concat demuxer fails
 * entirely on the first bad input, so a single corrupt segment loses the entire
 * video. v3.1.12 validates each segment with ffprobe before concat and skips invalid
 * ones, preserving as much footage as possible.
 *
 * These tests are gated by `RUN_FFMPEG_TESTS=true` because they require ffmpeg and
 * ffprobe to be installed locally (which CI doesn't have).
 *
 * Run with:
 *   RUN_FFMPEG_TESTS=true ./gradlew test --tests "*.ConcatResilienceTest"
 */
class ConcatResilienceTest {

    private fun ffmpegAvailable(): Boolean = try {
        val p = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor()
        p.exitValue() == 0
    } catch (_: Exception) { false }

    /** Generate a tiny valid MP4 with ffmpeg's testsrc source. */
    private fun generateValidMp4(file: File, durationSec: Int = 2) {
        val pb = ProcessBuilder(
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=$durationSec:size=320x240:rate=15",
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            file.absolutePath
        ).redirectErrorStream(true)
        val proc = pb.start()
        proc.inputStream.readBytes()
        proc.waitFor()
        check(file.exists() && file.length() > 0) { "ffmpeg failed to generate test mp4: ${file.absolutePath}" }
    }

    /** Generate a "corrupt" MP4 by truncating a valid one to remove the moov atom. */
    private fun generateCorruptMp4(file: File) {
        // Strategy: write some non-MP4 bytes that look like a partial mp4 header.
        // This is what screenrecord leaves behind when killed mid-write — the file has
        // an ftyp box but no moov box. ffprobe will report "moov atom not found".
        val ftypBox = byteArrayOf(
            // 32 bytes of ftyp box (well-formed)
            0x00, 0x00, 0x00, 0x20.toByte(), // size = 32
            0x66, 0x74, 0x79, 0x70,           // "ftyp"
            0x6d, 0x70, 0x34, 0x32,           // major brand "mp42"
            0x00, 0x00, 0x00, 0x00,           // minor version
            0x69, 0x73, 0x6f, 0x6d,           // compat brand "isom"
            0x6d, 0x70, 0x34, 0x32,           // compat brand "mp42"
            0x69, 0x73, 0x6f, 0x32,           // compat brand "iso2"
            0x61, 0x76, 0x63, 0x31            // compat brand "avc1"
        )
        // Plus some garbage payload (no moov atom, no mdat — just ftyp + bytes)
        val garbage = ByteArray(1024) { (it % 256).toByte() }
        file.writeBytes(ftypBox + garbage)
        check(file.length() > 0)
    }

    @Test
    fun `isValidVideoFile returns true for a valid generated mp4`() {
        // v3.1.13: replaced silent `if (env != true) return` with JUnit Assumptions so
        // the test correctly reports as SKIPPED instead of falsely PASSED when the
        // gating env var is absent.
        assumeTrue("Requires RUN_FFMPEG_TESTS=true", System.getenv("RUN_FFMPEG_TESTS") == "true")
        assumeTrue("Requires ffmpeg in PATH", ffmpegAvailable())

        val tmpDir = createTempDirectory("gp-test-").toFile()
        try {
            val valid = File(tmpDir, "valid.mp4")
            generateValidMp4(valid)
            assertTrue(AdbBridge.isValidVideoFile(valid), "generated valid mp4 should pass validation")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `isValidVideoFile returns false for a corrupt mp4 with no moov atom`() {
        assumeTrue("Requires RUN_FFMPEG_TESTS=true", System.getenv("RUN_FFMPEG_TESTS") == "true")
        assumeTrue("Requires ffmpeg in PATH", ffmpegAvailable())

        val tmpDir = createTempDirectory("gp-test-").toFile()
        try {
            val corrupt = File(tmpDir, "corrupt.mp4")
            generateCorruptMp4(corrupt)
            assertTrue(corrupt.exists() && corrupt.length() > 0, "corrupt file should be present")
            assertEquals(false, AdbBridge.isValidVideoFile(corrupt),
                "ftyp-only mp4 with no moov atom should fail validation")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `isValidVideoFile returns false for non-existent file`() {
        // No ffmpeg needed for this — early-out happens before ffprobe call.
        val nonExistent = File("/tmp/definitely-does-not-exist-${System.currentTimeMillis()}.mp4")
        assertEquals(false, AdbBridge.isValidVideoFile(nonExistent))
    }

    @Test
    fun `isValidVideoFile returns false for empty file`() {
        val tmpDir = createTempDirectory("gp-test-").toFile()
        try {
            val empty = File(tmpDir, "empty.mp4")
            empty.createNewFile()
            assertEquals(0L, empty.length())
            assertEquals(false, AdbBridge.isValidVideoFile(empty))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ===== concatSegments resilience =====

    @Test
    fun `concatSegments skips corrupt first segment and uses valid ones`() {
        assumeTrue("Requires RUN_FFMPEG_TESTS=true", System.getenv("RUN_FFMPEG_TESTS") == "true")
        assumeTrue("Requires ffmpeg in PATH", ffmpegAvailable())

        val tmpDir = createTempDirectory("gp-test-").toFile()
        try {
            // Mimic the user's real scenario: _0.mp4 is corrupt (chain killed it before
            // moov flush), _1.mp4 and _2.mp4 are valid.
            val seg0 = File(tmpDir, "video_test_0.mp4")
            val seg1 = File(tmpDir, "video_test_1.mp4")
            val seg2 = File(tmpDir, "video_test_2.mp4")
            generateCorruptMp4(seg0)
            generateValidMp4(seg1, durationSec = 3)
            generateValidMp4(seg2, durationSec = 2)

            val output = File(tmpDir, "video_test.mp4")
            val result = AdbBridge.concatSegments(listOf(seg0, seg1, seg2), output)

            // The result should be a real, playable file (not null, not the corrupt seg0)
            assertNotNull(result, "concat should succeed by skipping corrupt seg0")
            assertTrue(result.exists() && result.length() > 0)
            assertEquals(false, result.absolutePath == seg0.absolutePath,
                "result must NOT be the corrupt seg0")

            // The output should be playable
            assertTrue(AdbBridge.isValidVideoFile(result),
                "concat result should be a valid playable mp4")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `concatSegments returns null when ALL segments are corrupt`() {
        assumeTrue("Requires RUN_FFMPEG_TESTS=true", System.getenv("RUN_FFMPEG_TESTS") == "true")
        assumeTrue("Requires ffmpeg in PATH", ffmpegAvailable())

        val tmpDir = createTempDirectory("gp-test-").toFile()
        try {
            val seg0 = File(tmpDir, "video_test_0.mp4")
            val seg1 = File(tmpDir, "video_test_1.mp4")
            generateCorruptMp4(seg0)
            generateCorruptMp4(seg1)

            val output = File(tmpDir, "video_test.mp4")
            val result = AdbBridge.concatSegments(listOf(seg0, seg1), output)

            assertNull(result, "concat should return null when all segments are corrupt")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `concatSegments returns the single valid segment when only one is valid`() {
        assumeTrue("Requires RUN_FFMPEG_TESTS=true", System.getenv("RUN_FFMPEG_TESTS") == "true")
        assumeTrue("Requires ffmpeg in PATH", ffmpegAvailable())

        val tmpDir = createTempDirectory("gp-test-").toFile()
        try {
            // Only one valid segment among three.
            val seg0 = File(tmpDir, "video_test_0.mp4")
            val seg1 = File(tmpDir, "video_test_1.mp4")
            val seg2 = File(tmpDir, "video_test_2.mp4")
            generateCorruptMp4(seg0)
            generateValidMp4(seg1, durationSec = 2)
            generateCorruptMp4(seg2)

            val output = File(tmpDir, "video_test.mp4")
            val result = AdbBridge.concatSegments(listOf(seg0, seg1, seg2), output)

            assertNotNull(result)
            // Should return seg1 directly (no concat needed for a single valid segment)
            // or a single-input concat that's still playable.
            assertTrue(AdbBridge.isValidVideoFile(result), "result should be playable")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `concatSegments preserves valid 2-segment concat when both are valid`() {
        assumeTrue("Requires RUN_FFMPEG_TESTS=true", System.getenv("RUN_FFMPEG_TESTS") == "true")
        assumeTrue("Requires ffmpeg in PATH", ffmpegAvailable())

        val tmpDir = createTempDirectory("gp-test-").toFile()
        try {
            val seg1 = File(tmpDir, "video_test_1.mp4")
            val seg2 = File(tmpDir, "video_test_2.mp4")
            generateValidMp4(seg1, durationSec = 3)
            generateValidMp4(seg2, durationSec = 2)

            val output = File(tmpDir, "video_test.mp4")
            val result = AdbBridge.concatSegments(listOf(seg1, seg2), output)

            assertNotNull(result)
            assertEquals(output.absolutePath, result.absolutePath, "should produce the unified output")
            assertTrue(AdbBridge.isValidVideoFile(result))
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
