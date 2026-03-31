package com.gameperf.desktop

import com.gameperf.desktop.core.AutoUpdater
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoUpdaterTest {

    @Test
    fun `isNewer returns true when remote is higher major`() {
        assertTrue(AutoUpdater.isNewer("3.0.0", "2.9.9"))
    }

    @Test
    fun `isNewer returns true when remote is higher minor`() {
        assertTrue(AutoUpdater.isNewer("2.1.0", "2.0.0"))
    }

    @Test
    fun `isNewer returns true when remote is higher patch`() {
        assertTrue(AutoUpdater.isNewer("2.0.1", "2.0.0"))
    }

    @Test
    fun `isNewer returns false when versions are equal`() {
        assertFalse(AutoUpdater.isNewer("2.0.0", "2.0.0"))
    }

    @Test
    fun `isNewer returns false when remote is lower`() {
        assertFalse(AutoUpdater.isNewer("1.9.9", "2.0.0"))
    }

    @Test
    fun `isNewer handles v prefix`() {
        assertTrue(AutoUpdater.isNewer("v2.1.0", "v2.0.0"))
        assertTrue(AutoUpdater.isNewer("v3.0.0", "2.9.9"))
        assertFalse(AutoUpdater.isNewer("v1.0.0", "v2.0.0"))
    }

    @Test
    fun `isNewer handles V prefix (uppercase)`() {
        assertTrue(AutoUpdater.isNewer("V2.1.0", "V2.0.0"))
    }

    @Test
    fun `isNewer handles different segment lengths`() {
        assertTrue(AutoUpdater.isNewer("2.0.0.1", "2.0.0"))
        assertFalse(AutoUpdater.isNewer("2.0", "2.0.0"))
    }

    @Test
    fun `isNewer handles single digit versions`() {
        assertTrue(AutoUpdater.isNewer("3", "2"))
        assertFalse(AutoUpdater.isNewer("1", "2"))
    }
}
