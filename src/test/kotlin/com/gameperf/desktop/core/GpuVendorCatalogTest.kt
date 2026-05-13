package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [GpuVendorCatalog]. Pure object — no mocks, no I/O.
 *
 * Asserts the structural invariants required by `sdd/gpu-usage-percent` spec:
 *  - Ordering: MALI before ADRENO before POWERVR (catalog-order-wins probe).
 *  - Inside ADRENO: `gpu_busy_percentage` before `gpubusy` (no warm-up before
 *    delta-math path).
 *  - At least one candidate per vendor (POWERVR included for Sprint 1.5
 *    readiness).
 *  - Confidence level assigned for every candidate.
 *  - Substring uniqueness across paths so the FakeAdbBridge.shellResponses
 *    substring-keyed map cannot collide between vendors (GPU-024).
 *  - `ADRENO_PERFCOUNTER_NODE` non-empty AND NOT a member of
 *    `PROBE_CANDIDATES.map { it.path }` (it is a write-only enable path).
 */
class GpuVendorCatalogTest {

    @Test
    fun `catalog has at least one candidate per vendor`() {
        val vendors = GpuVendorCatalog.PROBE_CANDIDATES.map { it.vendor }.toSet()
        assertTrue(GpuVendor.MALI in vendors, "MALI must have at least one candidate")
        assertTrue(GpuVendor.ADRENO in vendors, "ADRENO must have at least one candidate")
        assertTrue(GpuVendor.POWERVR in vendors, "POWERVR placeholder must exist for Sprint 1.5 readiness")
    }

    @Test
    fun `catalog orders MALI before ADRENO before POWERVR`() {
        val candidates = GpuVendorCatalog.PROBE_CANDIDATES
        val firstAdrenoIdx = candidates.indexOfFirst { it.vendor == GpuVendor.ADRENO }
        val lastMaliIdx = candidates.indexOfLast { it.vendor == GpuVendor.MALI }
        val firstPowervrIdx = candidates.indexOfFirst { it.vendor == GpuVendor.POWERVR }
        val lastAdrenoIdx = candidates.indexOfLast { it.vendor == GpuVendor.ADRENO }
        assertTrue(lastMaliIdx < firstAdrenoIdx, "all MALI must precede first ADRENO")
        assertTrue(lastAdrenoIdx < firstPowervrIdx, "all ADRENO must precede first POWERVR")
    }

    @Test
    fun `adreno gpu_busy_percentage precedes gpubusy`() {
        val candidates = GpuVendorCatalog.PROBE_CANDIDATES
        val pctIdx = candidates.indexOfFirst { it.path.endsWith("gpu_busy_percentage") }
        val rawIdx = candidates.indexOfFirst { it.path.endsWith("/gpubusy") }
        assertTrue(pctIdx >= 0, "catalog must include gpu_busy_percentage")
        assertTrue(rawIdx >= 0, "catalog must include gpubusy raw counter")
        assertTrue(pctIdx < rawIdx, "gpu_busy_percentage must precede gpubusy (avoids warm-up)")
    }

    @Test
    fun `every candidate has a confidence level`() {
        // Compiler-enforced (non-nullable) — but explicit assertion guards against future refactor regressions.
        GpuVendorCatalog.PROBE_CANDIDATES.forEach { candidate ->
            assertNotNull(candidate.confidence, "confidence must be set on ${candidate.path}")
        }
    }

    @Test
    fun `every candidate has a probe format consistent with its vendor`() {
        GpuVendorCatalog.PROBE_CANDIDATES.forEach { candidate ->
            when (candidate.vendor) {
                GpuVendor.MALI -> assertEquals(ProbeFormat.MALI_INT_0_100, candidate.format,
                    "MALI candidates must use MALI_INT_0_100 format: ${candidate.path}")
                GpuVendor.ADRENO -> assertTrue(
                    candidate.format == ProbeFormat.ADRENO_GPU_BUSY_PERCENTAGE ||
                        candidate.format == ProbeFormat.ADRENO_KGSL_BUSY_TOTAL,
                    "ADRENO candidate must use one of the two ADRENO formats: ${candidate.path}",
                )
                GpuVendor.POWERVR -> assertEquals(ProbeFormat.POWERVR_UNKNOWN, candidate.format,
                    "POWERVR candidates must use POWERVR_UNKNOWN format: ${candidate.path}")
            }
        }
    }

    @Test
    fun `no candidate path is a substring of any other candidate path`() {
        // Required so FakeAdbBridge.shellResponses substring-keyed lookup cannot collide
        // between vendor entries. Spec GPU-024 + design §2.7.
        val paths = GpuVendorCatalog.PROBE_CANDIDATES.map { it.path }
        for (i in paths.indices) {
            for (j in paths.indices) {
                if (i == j) continue
                assertFalse(
                    paths[j].contains(paths[i]),
                    "path '${paths[i]}' is a substring of '${paths[j]}' — would collide in FakeAdbBridge.shellResponses",
                )
            }
        }
    }

    @Test
    fun `adreno perfcounter node is non-empty and not in probe candidates`() {
        val node = GpuVendorCatalog.ADRENO_PERFCOUNTER_NODE
        assertTrue(node.isNotEmpty(), "ADRENO_PERFCOUNTER_NODE must be a non-empty path")
        assertTrue(node.startsWith("/"), "ADRENO_PERFCOUNTER_NODE must be an absolute sysfs path")
        val probePaths = GpuVendorCatalog.PROBE_CANDIDATES.map { it.path }
        assertFalse(
            node in probePaths,
            "ADRENO_PERFCOUNTER_NODE is a write-only enable target — it must NOT be a probe candidate",
        )
    }

    @Test
    fun `mali catalog contains both canonical utilization and BSP typo alternate`() {
        // Spec GPU-004 requires the typo `utility` alternate so phones with the kernel typo work.
        val maliPaths = GpuVendorCatalog.PROBE_CANDIDATES
            .filter { it.vendor == GpuVendor.MALI }
            .map { it.path }
        assertTrue(maliPaths.any { it.endsWith("/utilization") },
            "MALI catalog must include canonical /utilization path")
        assertTrue(maliPaths.any { it.endsWith("/utility") },
            "MALI catalog must include BSP-typo /utility alternate")
    }

    @Test
    fun `confidence enum exposes HIGH MEDIUM LOW`() {
        val values = Confidence.values().toSet()
        assertTrue(Confidence.HIGH in values)
        assertTrue(Confidence.MEDIUM in values)
        assertTrue(Confidence.LOW in values)
        assertEquals(3, values.size, "Confidence is a closed 3-level enum")
    }
}
