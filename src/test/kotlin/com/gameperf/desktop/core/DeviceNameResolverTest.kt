package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [DeviceNameResolver].
 *
 * Pinned to the user complaint that prompted v4.2.5 ("en el nombre del dispositivo
 * aparecen letras y numeros"): an SM-S911B should display as "Samsung Galaxy S23",
 * not as a cryptic codename.
 */
class DeviceNameResolverTest {

    // ═══ Exact matches ═══

    @Test
    fun `resolve returns marketing name for known Pixel`() {
        assertEquals("Google Pixel 6", DeviceNameResolver.resolve("Pixel 6", "Google"))
        assertEquals("Google Pixel 7 Pro", DeviceNameResolver.resolve("Pixel 7 Pro", "Google"))
        assertEquals("Google Pixel 8a", DeviceNameResolver.resolve("Pixel 8a", "Google"))
    }

    @Test
    fun `resolve returns marketing name for Xiaomi flagship with numeric codename`() {
        // Xiaomi 13 internal codename is "2210132G"
        assertEquals("Xiaomi 13", DeviceNameResolver.resolve("2210132G", "Xiaomi"))
        assertEquals("Xiaomi 14", DeviceNameResolver.resolve("23116PN5BG", "Xiaomi"))
    }

    @Test
    fun `resolve returns marketing name for OnePlus and Motorola`() {
        assertEquals("OnePlus 11", DeviceNameResolver.resolve("CPH2449", "OnePlus"))
        assertEquals("Motorola Edge 30", DeviceNameResolver.resolve("edge 30", "motorola"))
    }

    // ═══ Prefix matches (regional / carrier variants) ═══

    @Test
    fun `resolve handles Samsung S23 regional variants via prefix match`() {
        // Samsung uses suffixes to denote region: B (international), U (US Unlocked),
        // N (Korea), 0 (China). All are the same phone.
        assertEquals("Samsung Galaxy S23", DeviceNameResolver.resolve("SM-S911B", "samsung"))
        assertEquals("Samsung Galaxy S23", DeviceNameResolver.resolve("SM-S911U", "samsung"))
        assertEquals("Samsung Galaxy S23", DeviceNameResolver.resolve("SM-S911N", "samsung"))
        assertEquals("Samsung Galaxy S23", DeviceNameResolver.resolve("SM-S9110", "samsung"))
    }

    @Test
    fun `resolve handles Samsung S24 Ultra and S25 Ultra regional variants`() {
        assertEquals("Samsung Galaxy S24 Ultra", DeviceNameResolver.resolve("SM-S928B", "samsung"))
        assertEquals("Samsung Galaxy S24 Ultra", DeviceNameResolver.resolve("SM-S928U", "samsung"))
        assertEquals("Samsung Galaxy S25 Ultra", DeviceNameResolver.resolve("SM-S938B", "samsung"))
    }

    @Test
    fun `resolve handles Samsung Galaxy A series prefixes`() {
        assertEquals("Samsung Galaxy A54 5G", DeviceNameResolver.resolve("SM-A546B", "samsung"))
        assertEquals("Samsung Galaxy A54 5G", DeviceNameResolver.resolve("SM-A546U", "samsung"))
    }

    @Test
    fun `resolve handles Samsung Galaxy Z Fold variants`() {
        assertEquals("Samsung Galaxy Z Fold 5", DeviceNameResolver.resolve("SM-F946B", "samsung"))
        assertEquals("Samsung Galaxy Z Flip 5", DeviceNameResolver.resolve("SM-F731B", "samsung"))
    }

    // ═══ Fallback behavior ═══

    @Test
    fun `resolve falls back to Manufacturer Model for unknown codenames`() {
        // A device not in the table — the fallback should be useful, not crash.
        assertEquals(
            "Samsung SM-S999X",
            DeviceNameResolver.resolve("SM-S999X", "samsung"),
            "unknown Samsung codename must fallback to 'Samsung <code>'",
        )
    }

    @Test
    fun `resolve capitalizes lowercase manufacturer in fallback`() {
        // Android `ro.product.manufacturer` is typically lowercase ("samsung", "xiaomi",
        // "google", "oneplus"). The fallback should display them properly capitalized.
        assertEquals("Xiaomi Mi-Unknown", DeviceNameResolver.resolve("Mi-Unknown", "xiaomi"))
        assertEquals("Asus Unknown", DeviceNameResolver.resolve("Unknown", "ASUS"))
    }

    @Test
    fun `resolve does not duplicate manufacturer when model already starts with it`() {
        // If the codename already contains the manufacturer prefix, don't repeat it.
        assertEquals(
            "Samsung-XYZ",
            DeviceNameResolver.resolve("Samsung-XYZ", "samsung"),
            "should not produce 'Samsung Samsung-XYZ'",
        )
    }

    @Test
    fun `resolve handles empty model gracefully`() {
        assertEquals("Samsung", DeviceNameResolver.resolve("", "samsung"))
        assertEquals("Unknown device", DeviceNameResolver.resolve("", ""))
    }

    @Test
    fun `resolve trims surrounding whitespace`() {
        assertEquals("Samsung Galaxy S23", DeviceNameResolver.resolve("  SM-S911B  ", " samsung "))
    }

    // ═══ Underscore normalization (v4.3.3) ═══
    //
    // `adb devices -l` prints `model:SM_S911B` with an UNDERSCORE because it
    // uses space-delimited parsing and would misread a hyphen. `getprop
    // ro.product.model` returns `SM-S911B` with a hyphen. Both codepaths
    // go through resolve() and must produce the same marketing name — the
    // bug reported was that the device list view (fed by `adb devices -l`)
    // showed the raw codename while the session detail view (fed by getprop)
    // showed the correct name.

    @Test
    fun `resolve handles underscore variant from adb devices listing`() {
        // User-reported case: Samsung Galaxy S23 on `adb devices -l` shows up
        // as `SM_S911B`, not `SM-S911B`. Must resolve identically.
        assertEquals("Samsung Galaxy S23", DeviceNameResolver.resolve("SM_S911B", "samsung"))
        assertEquals("Samsung Galaxy S23", DeviceNameResolver.resolve("SM_S911U", "samsung"))
        assertEquals("Samsung Galaxy S24 Ultra", DeviceNameResolver.resolve("SM_S928B", "samsung"))
        assertEquals("Samsung Galaxy Z Fold 5", DeviceNameResolver.resolve("SM_F946B", "samsung"))
    }

    @Test
    fun `resolve underscore and hyphen forms are interchangeable`() {
        // The same phone reported via both sources should produce the same
        // output — proves normalization happens at the entry point.
        val hyphenForm = DeviceNameResolver.resolve("SM-S911B", "samsung")
        val underscoreForm = DeviceNameResolver.resolve("SM_S911B", "samsung")
        assertEquals(hyphenForm, underscoreForm)
    }

    @Test
    fun `resolve fallback uses hyphen form even when input had underscores`() {
        // An unknown codename should fall back to the HYPHEN form, not the
        // underscore form — the hyphen is the canonical vendor-documented name
        // and looks less like an internal identifier.
        assertEquals(
            "Samsung SM-S999X",
            DeviceNameResolver.resolve("SM_S999X", "samsung"),
            "unknown underscore-form codenames must fallback to hyphen-form",
        )
    }

    // ═══ Coverage smoke check ═══

    @Test
    fun `resolve table contains at least the QA team's recurring devices`() {
        // The QA team has used at minimum the S23-series and a couple of Pixels +
        // a Xiaomi mid-range as their daily-driver test fleet. This test fails if
        // somebody accidentally deletes one of those entries.
        val mustHaves = listOf(
            "SM-S911" to "Samsung Galaxy S23",
            "Pixel 6" to "Google Pixel 6",
            "Pixel 7" to "Google Pixel 7",
            "Mi 11 Lite 5G" to "Xiaomi Mi 11 Lite 5G",
        )
        for ((codename, expected) in mustHaves) {
            assertTrue(
                DeviceNameResolver.codenameToMarketing.containsKey(codename),
                "QA recurring device '$codename' MUST be in the lookup table",
            )
            assertEquals(expected, DeviceNameResolver.codenameToMarketing[codename])
        }
    }
}
