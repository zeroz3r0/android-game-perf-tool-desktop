package com.gameperf.desktop.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ToolInstaller].
 *
 * Tests:
 * - SHA256 verification (passes for valid file, fails for tampered file)
 * - ZIP extraction (extracts contents and preserves directory structure)
 * - chmod (Unix marks binary executable; Windows is a no-op)
 * - Stage / Progress data types
 * - Disk space check
 */
class ToolInstallerTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("toolinstaller-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    // ═══════ Stage enum ═══════

    @Test
    fun `Stage has DOWNLOADING EXTRACTING VERIFYING values`() {
        assertEquals(3, ToolInstaller.Stage.entries.size)
        assertTrue(ToolInstaller.Stage.entries.contains(ToolInstaller.Stage.DOWNLOADING))
        assertTrue(ToolInstaller.Stage.entries.contains(ToolInstaller.Stage.EXTRACTING))
        assertTrue(ToolInstaller.Stage.entries.contains(ToolInstaller.Stage.VERIFYING))
    }

    // ═══════ Progress data class ═══════

    @Test
    fun `Progress stores stage and percent`() {
        val progress = ToolInstaller.Progress(ToolInstaller.Stage.DOWNLOADING, 0.5f)
        assertEquals(ToolInstaller.Stage.DOWNLOADING, progress.stage)
        assertEquals(0.5f, progress.percent)
    }

    // ═══════ hasEnoughSpace ═══════

    @Test
    fun `hasEnoughSpace returns true when sufficient space`() {
        val result = ToolInstaller.hasEnoughSpace(1, tempDir)
        assertTrue(result)
    }

    @Test
    fun `hasEnoughSpace returns false when insufficient space`() {
        val result = ToolInstaller.hasEnoughSpace(Long.MAX_VALUE / (1024 * 1024), tempDir)
        assertFalse(result)
    }

    // ═══════ Download integration (network failure path) ═══════

    @Test
    fun `download returns failure for invalid URL`() = runBlocking {
        val targetDir = File(tempDir, "tools").apply { mkdirs() }
        val result = ToolInstaller.download(
            url = "http://invalid-domain-12345-nonexistent.com/tool.zip",
            targetDir = targetDir,
            sha256 = null
        )
        assertTrue(result.isFailure)
    }

    // ═══════ SHA256 — happy path (valid file) ═══════

    @Test
    fun `computeSha256 returns hash for file`() {
        val testFile = File(tempDir, "test.txt").apply { writeText("hello world") }
        val hash = ToolInstaller.computeSha256(testFile)
        // SHA256 of "hello world" — well-known value
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash)
    }

    @Test
    fun `computeSha256 returns null for non-existent file`() {
        val nonExistent = File(tempDir, "does-not-exist.txt")
        val hash = ToolInstaller.computeSha256(nonExistent)
        assertNull(hash)
    }

    // ═══════ SHA256 — verification semantics: pass for valid, fail for tampered ═══════

    @Test
    fun `computeSha256 produces matching hash for two identical files`() {
        val a = File(tempDir, "a.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val b = File(tempDir, "b.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }

        val hashA = ToolInstaller.computeSha256(a)
        val hashB = ToolInstaller.computeSha256(b)

        assertNotNull(hashA)
        assertEquals(hashA, hashB)
    }

    @Test
    fun `computeSha256 produces different hashes for tampered content`() {
        val original = File(tempDir, "original.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val tampered = File(tempDir, "tampered.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 6)) }

        val originalHash = ToolInstaller.computeSha256(original)
        val tamperedHash = ToolInstaller.computeSha256(tampered)

        assertNotNull(originalHash)
        assertNotNull(tamperedHash)
        // Tampering ANY byte must change the hash — SHA256 collision-resistance.
        assertFalse(
            originalHash == tamperedHash,
            "tampered content must produce a different SHA256 hash"
        )
    }

    @Test
    fun `computeSha256 returns 64 hex chars for non-empty file`() {
        val file = File(tempDir, "any.bin").apply { writeBytes(byteArrayOf(0x42)) }
        val hash = ToolInstaller.computeSha256(file)
        assertNotNull(hash)
        // SHA256 hex output is always 64 chars (32 bytes * 2)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' }, "hash must be lowercase hex")
    }

    // ═══════ ZIP extraction — extracts contents to target dir ═══════

    @Test
    fun `extractZip extracts a flat file to target directory`() {
        val zipFile = File(tempDir, "flat.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("hello.txt"))
            zos.write("hi there".toByteArray())
            zos.closeEntry()
        }

        val targetDir = File(tempDir, "out").apply { mkdirs() }
        ToolInstaller.extractZip(zipFile, targetDir)

        val extracted = File(targetDir, "hello.txt")
        assertTrue(extracted.exists(), "extracted file must exist at root of target dir")
        assertEquals("hi there", extracted.readText())
    }

    @Test
    fun `extractZip preserves nested directory structure`() {
        val zipFile = File(tempDir, "nested.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // Nested entry: bin/ffmpeg.exe
            zos.putNextEntry(ZipEntry("bin/ffmpeg.exe"))
            zos.write(byteArrayOf(0x4D, 0x5A)) // MZ header bytes — placeholder
            zos.closeEntry()
            // Sibling at root
            zos.putNextEntry(ZipEntry("README.txt"))
            zos.write("readme".toByteArray())
            zos.closeEntry()
        }

        val targetDir = File(tempDir, "out").apply { mkdirs() }
        ToolInstaller.extractZip(zipFile, targetDir)

        val nested = File(targetDir, "bin/ffmpeg.exe")
        val readme = File(targetDir, "README.txt")

        assertTrue(nested.exists(), "nested file under bin/ must be extracted with its parent dir")
        assertTrue(nested.parentFile.isDirectory, "parent directory must be created")
        assertTrue(readme.exists(), "sibling root file must also be extracted")
        assertEquals("readme", readme.readText())
        assertEquals(2, nested.length(), "nested file content size must be preserved")
    }

    @Test
    fun `extractZip preserves multi-level deep directory structure`() {
        val zipFile = File(tempDir, "deep.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("platform-tools/lib/x64/adb"))
            zos.write("fake-adb-bytes".toByteArray())
            zos.closeEntry()
        }

        val targetDir = File(tempDir, "out").apply { mkdirs() }
        ToolInstaller.extractZip(zipFile, targetDir)

        val deep = File(targetDir, "platform-tools/lib/x64/adb")
        assertTrue(deep.exists(), "3-level deep path must be created")
        assertEquals("fake-adb-bytes", deep.readText())
    }

    // ═══════ chmod — Unix marks executable, Windows no-op ═══════

    @Test
    fun `setExecutable marks adb binary executable on Unix`() {
        // Skip the assertion on Windows since the function is a no-op there.
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val dir = File(tempDir, "tools").apply { mkdirs() }
        val adb = File(dir, "adb").apply { writeText("#!/bin/sh\necho fake") }

        // Pre-condition: on Unix, files are NOT executable by default after writeText.
        // On Windows, canExecute() always returns true for any existing file regardless.
        ToolInstaller.setExecutable(dir)

        if (isWindows) {
            // No-op on Windows — we cannot meaningfully assert chmod state.
            // The function must not throw and must leave the file in place.
            assertTrue(adb.exists(), "file must still exist after no-op setExecutable on Windows")
        } else {
            assertTrue(adb.canExecute(), "adb binary must be marked executable on Unix")
        }
    }

    @Test
    fun `setExecutable does not throw on Windows`() {
        // Triangulation: even on Unix, calling setExecutable on a directory containing
        // unrecognized binaries must not throw — only the known names (adb/ffmpeg/ffprobe)
        // are touched.
        val dir = File(tempDir, "weird").apply { mkdirs() }
        File(dir, "unknown-tool").apply { writeText("data") }

        // Must not throw regardless of platform.
        ToolInstaller.setExecutable(dir)

        assertTrue(File(dir, "unknown-tool").exists())
    }

    @Test
    fun `setExecutable recurses into subdirectories`() {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val root = File(tempDir, "root").apply { mkdirs() }
        val nested = File(root, "bin").apply { mkdirs() }
        val ffmpeg = File(nested, "ffmpeg").apply { writeText("fake") }

        ToolInstaller.setExecutable(root)

        if (isWindows) {
            assertTrue(ffmpeg.exists(), "no-op on Windows must not delete or move the file")
        } else {
            assertTrue(ffmpeg.canExecute(), "nested ffmpeg binary must be marked executable on Unix")
        }
    }
}
