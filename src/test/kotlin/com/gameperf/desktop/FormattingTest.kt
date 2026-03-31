package com.gameperf.desktop

import com.gameperf.desktop.ui.util.fmtUS
import com.gameperf.desktop.ui.util.formatDuration
import com.gameperf.desktop.ui.util.formatTimeMs
import kotlin.test.Test
import kotlin.test.assertEquals

class FormattingTest {

    // ===== formatTimeMs =====

    @Test
    fun `formatTimeMs zero returns 00 colon 00`() {
        assertEquals("00:00", formatTimeMs(0))
    }

    @Test
    fun `formatTimeMs 61000ms returns 01 colon 01`() {
        assertEquals("01:01", formatTimeMs(61000))
    }

    @Test
    fun `formatTimeMs 30000ms returns 00 colon 30`() {
        assertEquals("00:30", formatTimeMs(30000))
    }

    @Test
    fun `formatTimeMs 3600000ms returns 60 colon 00`() {
        assertEquals("60:00", formatTimeMs(3600000))
    }

    @Test
    fun `formatTimeMs sub-second values are truncated not rounded`() {
        assertEquals("00:00", formatTimeMs(999))
        assertEquals("00:01", formatTimeMs(1000))
    }

    // ===== formatDuration =====

    @Test
    fun `formatDuration zero seconds returns 0 colon 00`() {
        assertEquals("0:00", formatDuration(0))
    }

    @Test
    fun `formatDuration 61 seconds returns 1 colon 01`() {
        assertEquals("1:01", formatDuration(61))
    }

    @Test
    fun `formatDuration 3661 seconds returns 61 colon 01`() {
        assertEquals("61:01", formatDuration(3661))
    }

    @Test
    fun `formatDuration 30 seconds returns 0 colon 30`() {
        assertEquals("0:30", formatDuration(30))
    }

    // ===== fmtUS =====

    @Test
    fun `fmtUS formats decimal with dot not comma`() {
        assertEquals("1.5", fmtUS("%.1f", 1.5))
    }

    @Test
    fun `fmtUS formats zero correctly`() {
        assertEquals("0.0", fmtUS("%.1f", 0.0))
    }

    @Test
    fun `fmtUS formats integers`() {
        assertEquals("42", fmtUS("%d", 42))
    }

    @Test
    fun `fmtUS formats multiple args`() {
        assertEquals("FPS: 60 / 1.5ms", fmtUS("FPS: %d / %.1fms", 60, 1.5))
    }
}
