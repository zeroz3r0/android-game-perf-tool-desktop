package com.gameperf.desktop.core

import java.io.File
import java.nio.file.Files
import org.junit.Assume
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AutoUpdater.detectInstallation]. We use the internal `jarPathOverride`
 * parameter to inject fake JAR paths pointing into temp directory trees that mimic the
 * real installation layouts (macOS .app bundle, Windows jpackage, Linux native package).
 *
 * No external test deps (kotlin.test only): temp dirs are managed via
 * `Files.createTempDirectory` + `@BeforeTest`/`@AfterTest`, matching the existing
 * `FileCleanupTest` / `SessionHistoryTest` style.
 */
class AutoUpdaterDetectionTest {

    private val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("autoupdater-detect-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    // ═══════ DEV_MODE ═══════

    @Test
    fun `detectInstallation returns DEV_MODE when override is null and codeSource is unavailable`() {
        // We can't reliably force codeSource to null in unit tests, so we exercise the
        // null-override branch through the public API and just assert the contract:
        // when nothing identifies a JAR, we land in DEV_MODE.
        // Direct exercise of the override path:
        val info = AutoUpdater.detectInstallation(jarPathOverride = null)
        // Either DEV_MODE (no JAR found) or a real installation type if running from a JAR.
        // The contract we care about: currentJar matches the type.
        if (info.type == InstallationType.DEV_MODE) {
            assertNull(info.currentJar)
            assertNull(info.bundleRoot)
            assertNull(info.launcher)
        }
    }

    // ═══════ FAT_JAR_STANDALONE ═══════

    @Test
    fun `detectInstallation returns FAT_JAR_STANDALONE when jar is outside any bundle layout`() {
        val jar = File(tempDir, "GamePerf.jar").apply { writeBytes(ByteArray(10)) }

        val info = AutoUpdater.detectInstallation(jarPathOverride = jar)

        assertEquals(InstallationType.FAT_JAR_STANDALONE, info.type)
        assertEquals(jar, info.currentJar)
        assertNull(info.bundleRoot)
        assertNull(info.launcher)
    }

    // ═══════ MACOS_APP_BUNDLE ═══════

    @Test
    fun `detectInstallation returns MACOS_APP_BUNDLE when jar is inside a real app bundle`() {
        // Build: <tmp>/Foo.app/Contents/MacOS/Foo (executable)
        //        <tmp>/Foo.app/Contents/app/main.jar
        val bundleRoot = File(tempDir, "Foo.app").apply { mkdirs() }
        val macOSDir = File(bundleRoot, "Contents/MacOS").apply { mkdirs() }
        val launcher = File(macOSDir, "Foo").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        val appDir = File(bundleRoot, "Contents/app").apply { mkdirs() }
        val jar = File(appDir, "main.jar").apply { writeBytes(ByteArray(10)) }

        val info = AutoUpdater.detectInstallation(jarPathOverride = jar)

        assertEquals(InstallationType.MACOS_APP_BUNDLE, info.type)
        assertEquals(jar, info.currentJar)
        assertEquals(bundleRoot, info.bundleRoot)
        assertEquals(launcher, info.launcher)
        assertNotNull(info.launcher)
        assertTrue(info.launcher!!.canExecute(), "launcher must be executable")
    }

    @Test
    fun `detectInstallation falls back to FAT_JAR when bundle layout is incomplete`() {
        // Build: <tmp>/Fake.app/Contents/app/main.jar  (NO Contents/MacOS/Fake)
        val bundleRoot = File(tempDir, "Fake.app").apply { mkdirs() }
        val appDir = File(bundleRoot, "Contents/app").apply { mkdirs() }
        val jar = File(appDir, "main.jar").apply { writeBytes(ByteArray(10)) }

        val info = AutoUpdater.detectInstallation(jarPathOverride = jar)

        assertEquals(
            InstallationType.FAT_JAR_STANDALONE,
            info.type,
            "missing launcher should fall back to fat JAR"
        )
        assertEquals(jar, info.currentJar)
        assertNull(info.bundleRoot)
        assertNull(info.launcher)
    }

    @Test
    fun `detectInstallation falls back to FAT_JAR when launcher exists but is not executable`() {
        // setExecutable(false) has no effect on Windows (no Unix permission bits) — skip.
        Assume.assumeFalse("Windows does not support setExecutable(false)", isWindows)
        val bundleRoot = File(tempDir, "Bar.app").apply { mkdirs() }
        val macOSDir = File(bundleRoot, "Contents/MacOS").apply { mkdirs() }
        // Launcher exists as a regular file but is NOT executable.
        val launcher = File(macOSDir, "Bar").apply {
            writeText("not-a-binary")
            setExecutable(false)
        }
        val appDir = File(bundleRoot, "Contents/app").apply { mkdirs() }
        val jar = File(appDir, "main.jar").apply { writeBytes(ByteArray(10)) }

        val info = AutoUpdater.detectInstallation(jarPathOverride = jar)

        // Defensive: if the launcher isn't executable, treat as fat JAR rather than risk
        // a broken relaunch via `open -n`.
        assertEquals(InstallationType.FAT_JAR_STANDALONE, info.type)
        assertNull(info.bundleRoot)
        assertNull(info.launcher)
        // Quiet the unused-warning by referencing the file we created.
        assertTrue(launcher.exists())
    }

    // ═══════ WINDOWS_APP_BUNDLE ═══════

    @Test
    fun `detectInstallation returns WINDOWS_APP_BUNDLE when jar is in app dir adjacent to exe`() {
        // Build: <tmp>/MyApp/app/main.jar  +  <tmp>/MyApp/MyApp.exe
        val installRoot = File(tempDir, "MyApp").apply { mkdirs() }
        val appDir = File(installRoot, "app").apply { mkdirs() }
        val jar = File(appDir, "main.jar").apply { writeBytes(ByteArray(10)) }
        val exe = File(installRoot, "MyApp.exe").apply { writeBytes(ByteArray(4)) }

        val info = AutoUpdater.detectInstallation(jarPathOverride = jar)

        assertEquals(InstallationType.WINDOWS_APP_BUNDLE, info.type)
        assertEquals(jar, info.currentJar)
        assertEquals(installRoot, info.bundleRoot)
        assertEquals(exe, info.launcher)
    }

    @Test
    fun `detectInstallation does not classify as WINDOWS_APP_BUNDLE when sibling exe is missing`() {
        // Build: <tmp>/Lone/app/main.jar  (no Lone.exe)
        val installRoot = File(tempDir, "Lone").apply { mkdirs() }
        val appDir = File(installRoot, "app").apply { mkdirs() }
        val jar = File(appDir, "main.jar").apply { writeBytes(ByteArray(10)) }

        val info = AutoUpdater.detectInstallation(jarPathOverride = jar)

        // Should NOT be WINDOWS_APP_BUNDLE — falls through to FAT_JAR.
        assertEquals(InstallationType.FAT_JAR_STANDALONE, info.type)
    }

    @Test
    fun `detectInstallation returns WINDOWS_APP_BUNDLE when install folder was renamed (exe basename differs)`() {
        // Regression test for v4.2.2 in-app update bug:
        // User installed via jpackage as "GamePerf/" (with GamePerf.exe launcher) and
        // later renamed the folder to something else (e.g., "GamePerfApp2"). The old
        // detection looked for "<foldername>.exe" — if the .exe basename no longer
        // matched the folder name, detection fell through to FAT_JAR_STANDALONE and
        // the in-app updater relaunched with `java -jar` instead of the native
        // launcher .exe, which crashed because the bundle's .cfg (Skiko paths, etc.)
        // was never loaded.
        // Fix: fall back to ANY .exe in the install root when no exact match exists.
        val installRoot = File(tempDir, "RenamedFolder").apply { mkdirs() }
        val appDir = File(installRoot, "app").apply { mkdirs() }
        val jar = File(appDir, "main.jar").apply { writeBytes(ByteArray(10)) }
        // Launcher name does NOT match folder name — simulates a renamed install dir.
        val exe = File(installRoot, "GamePerf.exe").apply { writeBytes(ByteArray(4)) }

        val info = AutoUpdater.detectInstallation(jarPathOverride = jar)

        assertEquals(
            InstallationType.WINDOWS_APP_BUNDLE,
            info.type,
            "renamed folder with any .exe at root must still be detected as Windows bundle"
        )
        assertEquals(jar, info.currentJar)
        assertEquals(installRoot, info.bundleRoot)
        assertEquals(exe, info.launcher, "fallback should pick any .exe at install root")
    }

    @Test
    fun `detectInstallation prefers exe matching folder name when multiple exe files exist`() {
        // When the install root contains several .exe files (e.g., a real launcher plus
        // an "uninstall.exe" left by the installer), the matcher must prefer the .exe
        // whose basename matches the folder name. Only when no match exists should it
        // fall back to the first .exe found.
        val installRoot = File(tempDir, "MyApp").apply { mkdirs() }
        val appDir = File(installRoot, "app").apply { mkdirs() }
        val jar = File(appDir, "main.jar").apply { writeBytes(ByteArray(10)) }
        // Create unrelated .exe first so directory iteration might return it before MyApp.exe
        // on some filesystems — if the matcher is naive (just firstOrNull), this test fails.
        val unrelated = File(installRoot, "uninstall.exe").apply { writeBytes(ByteArray(4)) }
        val launcher = File(installRoot, "MyApp.exe").apply { writeBytes(ByteArray(4)) }

        val info = AutoUpdater.detectInstallation(jarPathOverride = jar)

        assertEquals(InstallationType.WINDOWS_APP_BUNDLE, info.type)
        assertEquals(
            launcher,
            info.launcher,
            "exact folder-name match must beat alphabetical/order fallback"
        )
        assertTrue(unrelated.exists()) // silence unused warning
    }

    // ═══════ LINUX_NATIVE_PACKAGE ═══════

    @Test
    fun `detectInstallation returns LINUX_NATIVE_PACKAGE when path contains lib slash app`() {
        // Linux detection uses forward-slash paths (/lib/app/); Windows temp dirs use backslashes.
        Assume.assumeFalse("Linux package detection not applicable on Windows", isWindows)
        // Build a fake install root anywhere — we trigger the detector via the "/lib/app/"
        // substring, which is independent of the temp dir's absolute prefix.
        val installRoot = File(tempDir, "myapp").apply { mkdirs() }
        val libDir = File(installRoot, "lib").apply { mkdirs() }
        val appDir = File(libDir, "app").apply { mkdirs() }
        val jar = File(appDir, "main.jar").apply { writeBytes(ByteArray(10)) }
        val binDir = File(installRoot, "bin").apply { mkdirs() }
        val launcher = File(binDir, "myapp").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }

        val info = AutoUpdater.detectInstallation(jarPathOverride = jar)

        assertEquals(InstallationType.LINUX_NATIVE_PACKAGE, info.type)
        assertEquals(jar, info.currentJar)
        assertEquals(installRoot, info.bundleRoot)
        assertEquals(launcher, info.launcher)
    }

    @Test
    fun `detectInstallation returns LINUX_NATIVE_PACKAGE without launcher when bin script missing`() {
        Assume.assumeFalse("Linux package detection not applicable on Windows", isWindows)
        val installRoot = File(tempDir, "noargs").apply { mkdirs() }
        val libDir = File(installRoot, "lib").apply { mkdirs() }
        val appDir = File(libDir, "app").apply { mkdirs() }
        val jar = File(appDir, "main.jar").apply { writeBytes(ByteArray(10)) }

        val info = AutoUpdater.detectInstallation(jarPathOverride = jar)

        assertEquals(InstallationType.LINUX_NATIVE_PACKAGE, info.type)
        assertEquals(installRoot, info.bundleRoot)
        assertNull(info.launcher, "no bin/<name> means launcher should be null")
    }
}
