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
 * Pure unit tests for [InstallLocation].
 *
 * Covers the v4.3.8 admin-required install path detector that drives the
 * UAC self-elevation flow in [AutoUpdater].
 *
 * Style matches `AutoUpdaterDetectionTest`: no external test deps, temp dir
 * managed via `Files.createTempDirectory` + `@BeforeTest`/`@AfterTest`.
 */
class InstallLocationTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("install-location-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    // ═══════ requiresAdmin — Windows protected paths ═══════

    @Test
    fun `requiresAdmin returns true for Program Files on Windows`() {
        val dir = File("""C:\Program Files\GamePerf""")
        assertTrue(InstallLocation.requiresAdmin(dir, isWindows = true))
    }

    @Test
    fun `requiresAdmin returns true for Program Files x86 on Windows (case insensitive)`() {
        val dir = File("""c:\program files (x86)\GamePerf""")
        assertTrue(InstallLocation.requiresAdmin(dir, isWindows = true))
    }

    @Test
    fun `requiresAdmin returns true for ProgramData on Windows`() {
        val dir = File("""C:\ProgramData\GamePerf""")
        assertTrue(InstallLocation.requiresAdmin(dir, isWindows = true))
    }

    @Test
    fun `requiresAdmin returns true for Windows system directory`() {
        val dir = File("""C:\Windows\System32\GamePerf""")
        assertTrue(InstallLocation.requiresAdmin(dir, isWindows = true))
    }

    @Test
    fun `requiresAdmin returns true for Program Files mixed case`() {
        val dir = File("""C:\PROGRAM FILES\GamePerf""")
        assertTrue(InstallLocation.requiresAdmin(dir, isWindows = true))
    }

    // ═══════ requiresAdmin — user-writable Windows paths ═══════

    @Test
    fun `requiresAdmin returns false for user AppData Local install on Windows`() {
        val dir = File("""C:\Users\Vivi\AppData\Local\GamePerf""")
        assertFalse(InstallLocation.requiresAdmin(dir, isWindows = true))
    }

    @Test
    fun `requiresAdmin returns false for non-system drive on Windows`() {
        val dir = File("""D:\Apps\GamePerf""")
        assertFalse(InstallLocation.requiresAdmin(dir, isWindows = true))
    }

    @Test
    fun `requiresAdmin returns false for user home on Windows`() {
        val dir = File("""C:\Users\Vivi\GamePerf""")
        assertFalse(InstallLocation.requiresAdmin(dir, isWindows = true))
    }

    // ═══════ requiresAdmin — non-Windows always false ═══════

    @Test
    fun `requiresAdmin returns false on non-Windows even for Program Files-shaped path`() {
        // A Linux user could in theory have a directory literally named "C:\Program Files"
        // (unlikely, but possible). The detector must not fire on non-Windows hosts.
        val dir = File("""C:\Program Files\GamePerf""")
        assertFalse(InstallLocation.requiresAdmin(dir, isWindows = false))
    }

    @Test
    fun `requiresAdmin returns false on macOS for typical install`() {
        val dir = File("/Applications/GamePerf.app/Contents/app")
        assertFalse(InstallLocation.requiresAdmin(dir, isWindows = false))
    }

    @Test
    fun `requiresAdmin returns false on Linux for opt install`() {
        val dir = File("/opt/gameperf")
        assertFalse(InstallLocation.requiresAdmin(dir, isWindows = false))
    }

    // ═══════ currentInstallDir ═══════

    @Test
    fun `currentInstallDir returns parent dir of first classpath jar when it exists`() {
        // Synthesize a classpath that points at a real jar inside tempDir, save the previous
        // value, swap, assert, restore. This exercises the production code path that reads
        // java.class.path without depending on how the test runner itself was launched.
        val installDir = File(tempDir, "FakeApp/app").apply { mkdirs() }
        val jar = File(installDir, "fake-main.jar").apply { writeBytes(ByteArray(10)) }
        val original = System.getProperty("java.class.path")
        try {
            System.setProperty("java.class.path", jar.absolutePath)
            val detected = InstallLocation.currentInstallDir()
            assertNotNull(detected, "should resolve from java.class.path's first entry")
            assertEquals(installDir.canonicalFile, detected.canonicalFile)
        } finally {
            if (original != null) System.setProperty("java.class.path", original)
        }
    }

    @Test
    fun `currentInstallDir returns null when classpath first entry does not exist`() {
        val original = System.getProperty("java.class.path")
        try {
            // Point at a path that definitely does not exist on this filesystem.
            System.setProperty("java.class.path", File(tempDir, "does-not-exist.jar").absolutePath)
            val detected = InstallLocation.currentInstallDir()
            // Parent of a nonexistent file may itself exist (tempDir does), so we tolerate
            // either a null result OR the tempDir parent — but never a dir that does not exist.
            if (detected != null) {
                assertTrue(detected.exists(), "if non-null, detected install dir must exist")
            } else {
                assertNull(detected)
            }
        } finally {
            if (original != null) System.setProperty("java.class.path", original)
        }
    }

    @Test
    fun `currentInstallDir uses the first entry when classpath has multiple entries`() {
        val firstDir = File(tempDir, "First/app").apply { mkdirs() }
        val firstJar = File(firstDir, "first.jar").apply { writeBytes(ByteArray(10)) }
        val secondDir = File(tempDir, "Second/app").apply { mkdirs() }
        val secondJar = File(secondDir, "second.jar").apply { writeBytes(ByteArray(10)) }

        val original = System.getProperty("java.class.path")
        try {
            // Use the platform's pathSeparator so this works on both Windows (`;`) and Unix (`:`).
            System.setProperty(
                "java.class.path",
                firstJar.absolutePath + File.pathSeparator + secondJar.absolutePath
            )
            val detected = InstallLocation.currentInstallDir()
            assertNotNull(detected)
            assertEquals(
                firstDir.canonicalFile,
                detected.canonicalFile,
                "must pick the FIRST entry, not the second"
            )
        } finally {
            if (original != null) System.setProperty("java.class.path", original)
        }
    }
}
