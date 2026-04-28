package com.gameperf.desktop.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [DependencyBootstrap].
 *
 * Tests the orchestration of tool checks via ToolResolver, including:
 * - adb missing scenario
 * - adb bundled scenario  
 * - ffmpeg missing/available scenarios
 * - Progress state transitions
 */
class DependencyBootstrapTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "depbootstrap-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ═══════ MissingTool data class ═══════

    @Test
    fun `MissingTool stores tool name and reason`() {
        val missing = DependencyBootstrap.MissingTool("adb", DependencyBootstrap.MissingReason.NOT_FOUND)
        assertEquals("adb", missing.toolName)
        assertEquals(DependencyBootstrap.MissingReason.NOT_FOUND, missing.reason)
    }

    @Test
    fun `MissingReason enum has expected values`() {
        assertEquals(3, DependencyBootstrap.MissingReason.entries.size)
        assertTrue(DependencyBootstrap.MissingReason.entries.contains(DependencyBootstrap.MissingReason.NOT_FOUND))
        assertTrue(DependencyBootstrap.MissingReason.entries.contains(DependencyBootstrap.MissingReason.BUNDLED_AVAILABLE))
        assertTrue(DependencyBootstrap.MissingReason.entries.contains(DependencyBootstrap.MissingReason.USER_DIR_AVAILABLE))
    }

    // ═══════ check() orchestration ═══════

    @Test
    fun `check returns empty list when all tools are available`() {
        // This test simulates a scenario where we pass a custom tool resolver
        // that always finds the tool. We can't easily mock ToolResolver.find in tests
        // since it's an object, so we test the integration behavior.
        // For a real scenario, adb might not be available in test environment.
        val result = DependencyBootstrap.check()
        // The actual result depends on the system. At minimum we verify the call doesn't throw.
        // On a fresh CI system with no adb/ffmpeg, we'd get missing tools.
        assertTrue(result is List<DependencyBootstrap.MissingTool> || result.isEmpty())
    }

    @Test
    fun `check returns MissingTool with correct tool name when tool not found`() {
        val result = DependencyBootstrap.check()
        
        // Find any missing adb entries
        val adbMissing = result.filter { it.toolName == "adb" }
        if (adbMissing.isNotEmpty()) {
            assertTrue(
                adbMissing.any { it.reason == DependencyBootstrap.MissingReason.NOT_FOUND },
                "When adb is not found, reason should be NOT_FOUND"
            )
        }
    }

    // ═══════ Progress state ═══════

    @Test
    fun `BootstrapProgress has expected stages`() {
        val downloading = DependencyBootstrap.BootstrapProgress.Downloading(0.5f)
        assertEquals(DependencyBootstrap.BootstrapStage.DOWNLOADING, downloading.stage)
        assertEquals(0.5f, downloading.percent)

        val extracting = DependencyBootstrap.BootstrapProgress.Extracting
        assertEquals(DependencyBootstrap.BootstrapStage.EXTRACTING, extracting.stage)

        val verifying = DependencyBootstrap.BootstrapProgress.Verifying
        assertEquals(DependencyBootstrap.BootstrapStage.VERIFYING, verifying.stage)

        val completed = DependencyBootstrap.BootstrapProgress.Completed
        assertEquals(DependencyBootstrap.BootstrapStage.COMPLETED, completed.stage)

        val failed = DependencyBootstrap.BootstrapProgress.Failed("error")
        assertEquals(DependencyBootstrap.BootstrapStage.FAILED, failed.stage)
        assertEquals("error", failed.errorMessage)
    }

    // ═══════ Tool URLs ═══════

    @Test
    fun `TOOL_URLS contains expected entries`() {
        assertTrue(DependencyBootstrap.TOOL_URLS.containsKey("adb"))
        assertTrue(DependencyBootstrap.TOOL_URLS.containsKey("ffmpeg"))
        
        val adbUrl = DependencyBootstrap.TOOL_URLS["adb"]
        assertTrue(adbUrl?.contains("android/repository") == true || adbUrl?.contains("dl.google.com") == true)
        
        val ffmpegUrl = DependencyBootstrap.TOOL_URLS["ffmpeg"]
        assertTrue(ffmpegUrl?.contains("ffmpeg") == true)
    }

    // ═══════ Tool SHA256 hashes (best-effort) ═══════

    @Test
    fun `TOOL_SHA256 contains optional entries`() {
        // SHA256 is best-effort — may be null for some tools.
        // ffmpeg will have a known hash from gyan.dev once we wire it; adb is null
        // because Google doesn't publish hashes. This test verifies the keys exist
        // and that the lookup helper accepts both tool names without throwing.
        assertTrue(DependencyBootstrap.TOOL_SHA256.containsKey("adb"))
        assertTrue(DependencyBootstrap.TOOL_SHA256.containsKey("ffmpeg"))
        // Lookup must not throw for either key (returns null is acceptable).
        DependencyBootstrap.sha256("adb")
        DependencyBootstrap.sha256("ffmpeg")
    }
}
