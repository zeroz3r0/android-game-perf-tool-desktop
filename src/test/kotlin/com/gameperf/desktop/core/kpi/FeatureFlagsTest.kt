package com.gameperf.desktop.core.kpi

import com.gameperf.desktop.core.Settings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 6 — `FeatureFlags` invariants (KPI-008).
 *
 * Spec coverage: design D5 — `isKpiScoringInternalEnabled()` returns true when
 * EITHER the system property `gameperf.kpi.internal=true` OR
 * `Settings.kpiScoringInternalEnabled=true`. Default OFF.
 *
 * Pure-ish: the function reads a JVM system property which we set/clear per
 * test (no concurrent test interference because tests run sequentially within
 * one class by default).
 */
class FeatureFlagsTest {

    @BeforeTest
    fun clearSysprop() {
        System.clearProperty(FeatureFlags.INTERNAL_FLAG_KEY)
    }

    @AfterTest
    fun clearSyspropAfter() {
        System.clearProperty(FeatureFlags.INTERNAL_FLAG_KEY)
    }

    @Test
    fun `default off when no sysprop and settings off`() {
        assertFalse(FeatureFlags.isKpiScoringInternalEnabled(Settings()))
    }

    @Test
    fun `sysprop true enables flag regardless of settings`() {
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "true")
        assertTrue(FeatureFlags.isKpiScoringInternalEnabled(Settings()))
    }

    @Test
    fun `settings true enables flag regardless of sysprop`() {
        assertTrue(FeatureFlags.isKpiScoringInternalEnabled(Settings(kpiScoringInternalEnabled = true)))
    }

    @Test
    fun `sysprop set to non-true string does not enable flag`() {
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "yes")
        assertFalse(FeatureFlags.isKpiScoringInternalEnabled(Settings()))
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "1")
        assertFalse(FeatureFlags.isKpiScoringInternalEnabled(Settings()))
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "false")
        assertFalse(FeatureFlags.isKpiScoringInternalEnabled(Settings()))
    }

    @Test
    fun `both on returns true`() {
        System.setProperty(FeatureFlags.INTERNAL_FLAG_KEY, "true")
        assertTrue(FeatureFlags.isKpiScoringInternalEnabled(Settings(kpiScoringInternalEnabled = true)))
    }
}
