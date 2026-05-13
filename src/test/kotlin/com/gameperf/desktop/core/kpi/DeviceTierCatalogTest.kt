package com.gameperf.desktop.core.kpi

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 2 — `DeviceTierCatalog` resolution.
 *
 * Spec coverage: `sdd/kpi-scoring-framework/spec` — Requirement: Device Tier
 * Resolution. Catalog uses exact-match (mirror `ThermalZoneClassifier` /
 * `SdkSignatureCatalog` pattern) and falls back to MID for unknown models.
 *
 * Tests are pure-Kotlin (no I/O, no mocks).
 */
class DeviceTierCatalogTest {

    @Test
    fun `Pixel 8 Pro classifies as TOP`() {
        assertEquals(DeviceTier.TOP, DeviceTierCatalog.resolve("Pixel 8 Pro"))
    }

    @Test
    fun `Pixel 6a classifies as MID`() {
        assertEquals(DeviceTier.MID, DeviceTierCatalog.resolve("Pixel 6a"))
    }

    @Test
    fun `Samsung Galaxy S23 model number SM-S911B classifies as TOP`() {
        // Note: model number variant — DeviceNameResolver (v4.3.3) handles
        // underscore→hyphen normalization, but DeviceTierCatalog accepts the
        // canonical hyphen form directly.
        assertEquals(DeviceTier.TOP, DeviceTierCatalog.resolve("SM-S911B"))
    }

    @Test
    fun `Galaxy Tab A8 classifies as LOW`() {
        assertEquals(DeviceTier.LOW, DeviceTierCatalog.resolve("Galaxy Tab A8"))
    }

    @Test
    fun `unknown device falls back to MID default`() {
        assertEquals(DeviceTier.MID, DeviceTierCatalog.resolve("Unknown XYZ"))
    }

    @Test
    fun `blank model falls back to MID default`() {
        assertEquals(DeviceTier.MID, DeviceTierCatalog.resolve(""))
    }

    @Test
    fun `null device falls back to MID default`() {
        assertEquals(DeviceTier.MID, DeviceTierCatalog.resolve(null))
    }
}
