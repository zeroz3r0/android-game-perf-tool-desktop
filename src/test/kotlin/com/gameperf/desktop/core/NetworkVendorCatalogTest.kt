package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [NetworkVendorCatalog]. Pure object â€” no mocks, no I/O.
 *
 * Asserts the structural invariants required by `sdd/network-bandwidth-total-app`:
 *  - NET-003: `PROBE_CANDIDATES` is the single source of truth â€” non-empty,
 *    binder-first then dumpsys-fallback (dumpsys lives as a separate `const val`
 *    sibling, NOT inside `PROBE_CANDIDATES`).
 *  - NET-004: binder codes `[11, 12, 14, 15]` distinct, absorbing AOSP renumbering.
 *  - Catalog is locked at size 4 to guard against accidental drift (Phase 2 task 2.3).
 *  - Every candidate carries `NetworkConfidence.HINT` per design D3 (no real-device captures yet).
 *
 * Mirrors [GpuVendorCatalogTest] anti-duplication assertions. Single source = this file
 * + `NetworkVendorCatalog.kt`. Adding a binder code means appending here + updating
 * the size lock â€” no other catalog may grow elsewhere (CLAUDE.md v4.2.13 lesson).
 */
class NetworkVendorCatalogTest {

    // â”€â”€ Basic shape â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `PROBE_CANDIDATES is non-empty`() {
        assertTrue(
            NetworkVendorCatalog.PROBE_CANDIDATES.isNotEmpty(),
            "NET-003: at least one binder candidate must exist",
        )
    }

    @Test
    fun `PROBE_CANDIDATES is locked at size 4`() {
        // Task 2.3 size lock â€” guards against accidental catalog growth without test update.
        // To grow the list, also update this test, the binder-codes test, and the
        // FakeAdbBridge substring-uniqueness expectations.
        assertEquals(
            4,
            NetworkVendorCatalog.PROBE_CANDIDATES.size,
            "NET-004: binder catalog locked at 4 entries [11, 12, 14, 15]",
        )
    }

    // â”€â”€ NET-004 binder codes â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `PROBE_CANDIDATES exposes binder codes 11, 12, 14, 15 in order`() {
        val codes = NetworkVendorCatalog.PROBE_CANDIDATES.map { it.binderCode }
        assertEquals(
            listOf(11, 12, 14, 15),
            codes,
            "NET-004: binder transaction codes must be [11, 12, 14, 15] in catalog order",
        )
    }

    @Test
    fun `every binder code is distinct`() {
        val codes = NetworkVendorCatalog.PROBE_CANDIDATES.mapNotNull { it.binderCode }
        assertEquals(
            codes.toSet().size,
            codes.size,
            "NET-004: binder codes must be distinct across the catalog",
        )
    }

    // â”€â”€ NET-003 ordering invariant â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `every candidate has either a binder code or a dumpsys command`() {
        // Each entry is a probeable source â€” at least one side of (binderCode, dumpsysCommand)
        // MUST be set. Both null is a malformed entry.
        NetworkVendorCatalog.PROBE_CANDIDATES.forEach { c ->
            assertTrue(
                c.binderCode != null || c.dumpsysCommand != null,
                "candidate '${c.method}' must declare binderCode or dumpsysCommand",
            )
        }
    }

    @Test
    fun `binder candidates appear before any dumpsys candidate in PROBE_CANDIDATES`() {
        // NET-003: binder-first ordering. v1 dumpsys lives as DUMPSYS_NETSTATS_COMMAND const
        // (not in PROBE_CANDIDATES). Test still encodes the ordering invariant so future
        // growth (adding a dumpsys candidate to the list) preserves it.
        val candidates = NetworkVendorCatalog.PROBE_CANDIDATES
        val lastBinderIdx = candidates.indexOfLast { it.binderCode != null }
        val firstDumpsysIdx = candidates.indexOfFirst { it.dumpsysCommand != null }
        if (firstDumpsysIdx >= 0) {
            assertTrue(
                lastBinderIdx < firstDumpsysIdx,
                "NET-003: all BINDER entries must precede first DUMPSYS entry",
            )
        }
    }

    // â”€â”€ Anti-duplication by composite key (CLAUDE.md v4.2.13 lesson) â”€â”€â”€â”€â”€

    @Test
    fun `no two candidates share the same composite key`() {
        // Anti-dup: the (method, binderCode, dumpsysCommand) tuple is the deduplication key.
        // Two entries with the same tuple would represent a copy-paste mistake.
        val keys = NetworkVendorCatalog.PROBE_CANDIDATES.map {
            Triple(it.method, it.binderCode, it.dumpsysCommand)
        }
        assertEquals(
            keys.size,
            keys.toSet().size,
            "no two candidates may share method/binderCode/dumpsysCommand",
        )
    }

    @Test
    fun `every candidate has Confidence HINT per design D3`() {
        // Design D3: all binder candidates are HINT confidence until real-device captures land.
        // Banner copy says "estimado". This invariant prevents drift to MEDIUM/HIGH before
        // lab verification.
        NetworkVendorCatalog.PROBE_CANDIDATES.forEach { c ->
            assertEquals(
                NetworkConfidence.HINT,
                c.confidence,
                "candidate '${c.method}' must be NetworkConfidence.HINT pre-lab-verification",
            )
        }
    }

    @Test
    fun `every candidate exposes a non-blank method label`() {
        NetworkVendorCatalog.PROBE_CANDIDATES.forEach { c ->
            assertTrue(c.method.isNotBlank(), "method label must not be blank")
        }
    }

    // â”€â”€ Sibling consts (NET-003 + Task 2.2) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `DUMPSYS_NETSTATS_COMMAND is the canonical dumpsys fallback string`() {
        // Task 2.2 â€” dumpsys lives as a separate const (not probeable inside PROBE_CANDIDATES).
        // The exact string is consumed by AdbBridge in Phase 4 + report KDoc.
        val cmd = NetworkVendorCatalog.DUMPSYS_NETSTATS_COMMAND
        assertNotNull(cmd)
        assertEquals("dumpsys netstats detail --uid", cmd)
    }

    @Test
    fun `DUMPSYS_NETSTATS_COMMAND is NOT also listed inside PROBE_CANDIDATES`() {
        // Anti-dup: the const is the single source for the dumpsys command string. It MUST NOT
        // appear (verbatim) inside any candidate.dumpsysCommand to avoid divergence.
        val cmd = NetworkVendorCatalog.DUMPSYS_NETSTATS_COMMAND
        val anyMatch = NetworkVendorCatalog.PROBE_CANDIDATES.any { it.dumpsysCommand == cmd }
        assertFalse(
            anyMatch,
            "v1 dumpsys must be reached via DUMPSYS_NETSTATS_COMMAND const, not duplicated as a candidate",
        )
    }
}

