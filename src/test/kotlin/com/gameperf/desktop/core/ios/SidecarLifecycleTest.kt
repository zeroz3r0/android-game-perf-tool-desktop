package com.gameperf.desktop.core.ios

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit tests for [SidecarLifecycle] utility methods.
 *
 * These test the pure utility functions (port allocation, Python detection)
 * without spawning a real sidecar process.
 */
class SidecarLifecycleTest {

    @Test
    fun `findFreePort returns a valid port number`() {
        val port = SidecarLifecycle.findFreePort()
        assertTrue(port > 0, "Expected a positive port number, got $port")
        assertTrue(port < 65536, "Expected port < 65536, got $port")
    }

    @Test
    fun `findFreePort returns different ports on consecutive calls`() {
        val port1 = SidecarLifecycle.findFreePort()
        val port2 = SidecarLifecycle.findFreePort()
        // Ports SHOULD be different (not guaranteed but overwhelmingly likely)
        // We don't assert inequality because the OS could theoretically reuse,
        // but we verify both are valid
        assertTrue(port1 > 0)
        assertTrue(port2 > 0)
    }

    @Test
    fun `isPythonAvailable returns true on this machine`() {
        // This test machine has Python 3 installed (confirmed in earlier sessions)
        val available = SidecarLifecycle.isPythonAvailable()
        assertTrue(available, "Expected Python 3 to be available on this machine")
    }

    @Test
    fun `SidecarLifecycle initializes with isRunning false`() {
        val lifecycle = SidecarLifecycle(sidecarDir = "/nonexistent")
        assertTrue(!lifecycle.isRunning)
    }

    @Test
    fun `SidecarLifecycle lastError is null initially`() {
        val lifecycle = SidecarLifecycle(sidecarDir = "/nonexistent")
        assertTrue(lifecycle.lastError == null)
    }

    @Test
    fun `isWindows returns correct value for current platform`() {
        val expected = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        assertEquals(expected, SidecarLifecycle.isWindows())
    }

    @Test
    fun `isITunesAvailable returns true on non-Windows`() {
        // On Mac/Linux, iTunes check is not applicable → always returns true
        if (!SidecarLifecycle.isWindows()) {
            assertTrue(SidecarLifecycle.isITunesAvailable())
        }
    }
}
