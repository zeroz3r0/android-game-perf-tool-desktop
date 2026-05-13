package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [FPowerVendorCatalog]. Pure object — no mocks, no I/O.
 *
 * Asserts spec FPW-010: 5-tuple ordered vendor catalog, AOSP-canonical first,
 * OEM alternates following. Each tuple exposes a `currentPath` ending
 * `current_now` and a `voltagePath` ending `voltage_now` so the bridge can
 * read both halves of `Power = current * voltage`.
 *
 * See `sdd/fpower-metric/design` §2 + spec FPW-010 for the source-of-truth list.
 */
class FPowerVendorCatalogTest {

    // ── Shape ────────────────────────────────────────────────────────────

    @Test
    fun `ORDERED_PATHS is non-empty`() {
        assertTrue(FPowerVendorCatalog.ORDERED_PATHS.isNotEmpty())
    }

    @Test
    fun `ORDERED_PATHS contains exactly 5 tuples per spec FPW-010`() {
        assertEquals(5, FPowerVendorCatalog.ORDERED_PATHS.size)
    }

    // ── Order ────────────────────────────────────────────────────────────

    @Test
    fun `AOSP-canonical path is at index 0`() {
        val first = FPowerVendorCatalog.ORDERED_PATHS.first()
        assertEquals("/sys/class/power_supply/battery/current_now", first.currentPath)
        assertEquals("/sys/class/power_supply/battery/voltage_now", first.voltagePath)
    }

    // ── Path naming contract ─────────────────────────────────────────────

    @Test
    fun `every tuple currentPath ends with current_now`() {
        FPowerVendorCatalog.ORDERED_PATHS.forEach { tuple ->
            assertTrue(
                tuple.currentPath.endsWith("current_now") ||
                    tuple.currentPath.endsWith("current_ua_now"),
                "currentPath must end with current_now or current_ua_now: ${tuple.currentPath}",
            )
        }
    }

    @Test
    fun `every tuple voltagePath ends with voltage_now`() {
        FPowerVendorCatalog.ORDERED_PATHS.forEach { tuple ->
            assertTrue(
                tuple.voltagePath.endsWith("voltage_now"),
                "voltagePath must end with voltage_now: ${tuple.voltagePath}",
            )
        }
    }

    @Test
    fun `every path starts with sys class power_supply`() {
        FPowerVendorCatalog.ORDERED_PATHS.forEach { tuple ->
            assertTrue(
                tuple.currentPath.startsWith("/sys/class/power_supply/"),
                "currentPath must be under /sys/class/power_supply/: ${tuple.currentPath}",
            )
            assertTrue(
                tuple.voltagePath.startsWith("/sys/class/power_supply/"),
                "voltagePath must be under /sys/class/power_supply/: ${tuple.voltagePath}",
            )
        }
    }

    @Test
    fun `every path is non-blank`() {
        FPowerVendorCatalog.ORDERED_PATHS.forEach { tuple ->
            assertTrue(tuple.currentPath.isNotBlank())
            assertTrue(tuple.voltagePath.isNotBlank())
        }
    }

    // ── Uniqueness ───────────────────────────────────────────────────────

    @Test
    fun `currentPath entries are unique`() {
        val currents = FPowerVendorCatalog.ORDERED_PATHS.map { it.currentPath }
        assertEquals(currents.size, currents.toSet().size, "duplicate currentPath in catalog")
    }
}
