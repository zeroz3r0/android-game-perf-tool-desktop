package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [GpuUsageParser]. Pure object — no mocks, no I/O.
 *
 * Each block exercises one entry point (`parseMali`, `parseAdrenoGpuBusyPercentage`,
 * `parseAdrenoGpuBusy`, `computeAdrenoDelta`, `parseProbeOutput`) with
 * triangulated happy + edge + adversarial inputs from spec GPU-003 / 005 /
 * 006 / 021 + design §2.2.
 */
class GpuUsageParserTest {

    // ── parseMali ────────────────────────────────────────────────────────

    @Test
    fun `parseMali happy path returns integer percent`() {
        assertEquals(42, GpuUsageParser.parseMali("42\n"))
    }

    @Test
    fun `parseMali tolerates leading and trailing whitespace`() {
        assertEquals(73, GpuUsageParser.parseMali("  73 \n\n"))
    }

    @Test
    fun `parseMali rejects values above 100`() {
        // Spec GPU-003 / GPU-021: out-of-range loud rejection (NOT silent clamp).
        assertNull(GpuUsageParser.parseMali("110"))
    }

    @Test
    fun `parseMali rejects negative values`() {
        assertNull(GpuUsageParser.parseMali("-1"))
    }

    @Test
    fun `parseMali rejects non-numeric strings`() {
        assertNull(GpuUsageParser.parseMali("foo"))
    }

    @Test
    fun `parseMali rejects empty string`() {
        assertNull(GpuUsageParser.parseMali(""))
    }

    @Test
    fun `parseMali accepts boundary values 0 and 100`() {
        assertEquals(0, GpuUsageParser.parseMali("0"))
        assertEquals(100, GpuUsageParser.parseMali("100"))
    }

    // ── parseAdrenoGpuBusyPercentage ─────────────────────────────────────

    @Test
    fun `parseAdrenoGpuBusyPercentage parses plain integer`() {
        assertEquals(55, GpuUsageParser.parseAdrenoGpuBusyPercentage("55"))
    }

    @Test
    fun `parseAdrenoGpuBusyPercentage strips trailing percent sign`() {
        // Some Adreno kernels append the literal '%'.
        assertEquals(55, GpuUsageParser.parseAdrenoGpuBusyPercentage("55%"))
    }

    @Test
    fun `parseAdrenoGpuBusyPercentage accepts boundary 0 and 100`() {
        assertEquals(0, GpuUsageParser.parseAdrenoGpuBusyPercentage("0"))
        assertEquals(100, GpuUsageParser.parseAdrenoGpuBusyPercentage("100"))
    }

    @Test
    fun `parseAdrenoGpuBusyPercentage rejects empty`() {
        assertNull(GpuUsageParser.parseAdrenoGpuBusyPercentage(""))
    }

    @Test
    fun `parseAdrenoGpuBusyPercentage rejects non-numeric`() {
        assertNull(GpuUsageParser.parseAdrenoGpuBusyPercentage("NaN"))
    }

    @Test
    fun `parseAdrenoGpuBusyPercentage rejects out-of-range`() {
        assertNull(GpuUsageParser.parseAdrenoGpuBusyPercentage("150"))
    }

    // ── parseAdrenoGpuBusy ───────────────────────────────────────────────

    @Test
    fun `parseAdrenoGpuBusy parses two whitespace-separated longs`() {
        assertEquals(1234L to 5678L, GpuUsageParser.parseAdrenoGpuBusy("1234 5678"))
    }

    @Test
    fun `parseAdrenoGpuBusy parses two-line counter dump`() {
        assertEquals(1234L to 5678L, GpuUsageParser.parseAdrenoGpuBusy("1234\n5678"))
    }

    @Test
    fun `parseAdrenoGpuBusy rejects single token`() {
        assertNull(GpuUsageParser.parseAdrenoGpuBusy("1234"))
    }

    @Test
    fun `parseAdrenoGpuBusy rejects negative numbers`() {
        assertNull(GpuUsageParser.parseAdrenoGpuBusy("-1 5678"))
    }

    @Test
    fun `parseAdrenoGpuBusy rejects non-numeric tokens`() {
        assertNull(GpuUsageParser.parseAdrenoGpuBusy("foo bar"))
    }

    @Test
    fun `parseAdrenoGpuBusy rejects empty`() {
        assertNull(GpuUsageParser.parseAdrenoGpuBusy(""))
    }

    // ── computeAdrenoDelta ───────────────────────────────────────────────

    @Test
    fun `computeAdrenoDelta computes proportional percent`() {
        // prev=(100,1000) curr=(200,2000) → deltaBusy=100, deltaTotal=1000, 10%.
        assertEquals(10, GpuUsageParser.computeAdrenoDelta(100L to 1000L, 200L to 2000L))
    }

    @Test
    fun `computeAdrenoDelta computes from zero baseline`() {
        // First-tick baseline=(0,0) curr=(50,500) → 10%.
        assertEquals(10, GpuUsageParser.computeAdrenoDelta(0L to 0L, 50L to 500L))
    }

    @Test
    fun `computeAdrenoDelta returns null on counter wraparound`() {
        // prev=(500,5000) curr=(100,1000) → deltaBusy<0 → null (wraparound or counter reset).
        assertNull(GpuUsageParser.computeAdrenoDelta(500L to 5000L, 100L to 1000L))
    }

    @Test
    fun `computeAdrenoDelta returns null on zero total delta`() {
        // prev=(100,1000) curr=(100,1000) → deltaTotal=0 → null.
        assertNull(GpuUsageParser.computeAdrenoDelta(100L to 1000L, 100L to 1000L))
    }

    @Test
    fun `computeAdrenoDelta returns null when busy delta exceeds total delta`() {
        // prev=(0,0) curr=(2000,1000) → busy(2000) > total(1000) → implausible.
        assertNull(GpuUsageParser.computeAdrenoDelta(0L to 0L, 2000L to 1000L))
    }

    @Test
    fun `computeAdrenoDelta accepts busy equal to total at 100 percent`() {
        // Plausibility-guard boundary — busy==total is a valid 100% load.
        assertEquals(100, GpuUsageParser.computeAdrenoDelta(0L to 0L, 100L to 100L))
    }

    @Test
    fun `computeAdrenoDelta returns 0 on zero busy delta`() {
        // prev=(100,1000) curr=(100,2000) → 0% load (no GPU work, only time elapsed).
        assertEquals(0, GpuUsageParser.computeAdrenoDelta(100L to 1000L, 100L to 2000L))
    }

    // ── parseProbeOutput ─────────────────────────────────────────────────

    @Test
    fun `parseProbeOutput picks Mali when only Mali path has a value`() {
        val output = """
            /sys/class/misc/mali0/device/utilization:42
            /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage:
            /sys/class/kgsl/kgsl-3d0/gpubusy:
        """.trimIndent()
        val hit = GpuUsageParser.parseProbeOutput(output)
        assertNotNull(hit)
        assertEquals(GpuVendor.MALI, hit.vendor)
        assertEquals("/sys/class/misc/mali0/device/utilization", hit.winningPath)
        assertEquals(ProbeFormat.MALI_INT_0_100, hit.format)
        assertEquals("42", hit.rawPayload)
    }

    @Test
    fun `parseProbeOutput picks Adreno gpu_busy_percentage when Mali empty`() {
        val output = """
            /sys/class/misc/mali0/device/utilization:
            /sys/class/misc/mali0/device/utility:
            /sys/class/misc/mali0/device/utilization (alt):
            /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage:55
            /sys/class/kgsl/kgsl-3d0/gpubusy:
        """.trimIndent()
        val hit = GpuUsageParser.parseProbeOutput(output)
        assertNotNull(hit)
        assertEquals(GpuVendor.ADRENO, hit.vendor)
        assertEquals("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", hit.winningPath)
        assertEquals(ProbeFormat.ADRENO_GPU_BUSY_PERCENTAGE, hit.format)
        assertEquals("55", hit.rawPayload)
    }

    @Test
    fun `parseProbeOutput returns null when every probe is empty`() {
        val output = GpuVendorCatalog.PROBE_CANDIDATES.joinToString("\n") { "${it.path}:" }
        assertNull(GpuUsageParser.parseProbeOutput(output))
    }

    @Test
    fun `parseProbeOutput catalog-order wins on multi-vendor hit`() {
        // Both Mali AND Adreno populated → Mali wins because MALI precedes ADRENO in catalog.
        val output = """
            /sys/class/misc/mali0/device/utilization:42
            /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage:55
        """.trimIndent()
        val hit = GpuUsageParser.parseProbeOutput(output)
        assertNotNull(hit)
        assertEquals(GpuVendor.MALI, hit.vendor)
        assertEquals("42", hit.rawPayload)
    }

    @Test
    fun `parseProbeOutput recognises gpubusy two-token payload`() {
        val output = """
            /sys/class/misc/mali0/device/utilization:
            /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage:
            /sys/class/kgsl/kgsl-3d0/gpubusy:1234 5678
        """.trimIndent()
        val hit = GpuUsageParser.parseProbeOutput(output)
        assertNotNull(hit)
        assertEquals(GpuVendor.ADRENO, hit.vendor)
        assertEquals(ProbeFormat.ADRENO_KGSL_BUSY_TOTAL, hit.format)
        assertEquals("1234 5678", hit.rawPayload)
    }

    @Test
    fun `parseProbeOutput recognises PowerVR placeholder hit`() {
        val output = """
            /sys/class/misc/mali0/device/utilization:
            /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage:
            /sys/class/kgsl/kgsl-3d0/gpubusy:
            /proc/mtk_mali/utilization:30
        """.trimIndent()
        val hit = GpuUsageParser.parseProbeOutput(output)
        assertNotNull(hit)
        assertEquals(GpuVendor.POWERVR, hit.vendor)
        assertEquals(ProbeFormat.POWERVR_UNKNOWN, hit.format)
    }

    @Test
    fun `parseProbeOutput ignores unrecognised lines`() {
        val output = """
            random-debug-line: something
            /sys/class/misc/mali0/device/utilization:42
            another-garbage-line
        """.trimIndent()
        val hit = GpuUsageParser.parseProbeOutput(output)
        assertNotNull(hit)
        assertEquals(GpuVendor.MALI, hit.vendor)
        assertEquals("42", hit.rawPayload)
    }
}
