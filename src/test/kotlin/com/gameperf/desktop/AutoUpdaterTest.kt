package com.gameperf.desktop

import com.gameperf.desktop.core.AutoUpdater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // ===== v4.1.0 — extractJsonString (linear parser) tests =====

    @Test
    fun `extractJsonString extracts simple value`() {
        val json = """{"tag_name": "v4.0.0", "name": "Release"}"""
        assertEquals("v4.0.0", AutoUpdater.extractJsonString(json, "tag_name"))
    }

    @Test
    fun `extractJsonString returns null for missing key`() {
        assertNull(AutoUpdater.extractJsonString("""{"foo": "bar"}""", "missing"))
    }

    @Test
    fun `extractJsonString handles escaped quotes in value`() {
        val json = """{"body": "Fixed \"white-on-white\" bug"}"""
        val body = AutoUpdater.extractJsonString(json, "body")
        assertNotNull(body)
        assertTrue(body.contains("white-on-white"))
    }

    @Test
    fun `extractJsonString handles newlines in value`() {
        val json = """{"body": "Line 1\nLine 2\nLine 3"}"""
        val body = AutoUpdater.extractJsonString(json, "body")
        assertNotNull(body)
        assertEquals(3, body.lines().size)
    }

    @Test
    fun `extractJsonString handles unicode escapes`() {
        val json = """{"name": "Caf\u00e9"}"""
        val name = AutoUpdater.extractJsonString(json, "name")
        assertEquals("Café", name)
    }

    @Test
    fun `extractJsonString returns null for non-string value`() {
        // value is a number, not string — should return null
        assertNull(AutoUpdater.extractJsonString("""{"count": 42}""", "count"))
    }

    @Test
    fun `extractAllJsonStrings extracts multiple values`() {
        val json = """{"assets": [{"browser_download_url": "http://a.jar"}, {"browser_download_url": "http://b.jar"}]}"""
        val urls = AutoUpdater.extractAllJsonStrings(json, "browser_download_url")
        assertEquals(2, urls.size)
        assertEquals("http://a.jar", urls[0])
        assertEquals("http://b.jar", urls[1])
    }

    @Test
    fun `extractAllJsonStrings returns empty for no matches`() {
        val urls = AutoUpdater.extractAllJsonStrings("""{"foo": "bar"}""", "missing")
        assertTrue(urls.isEmpty())
    }

    @Test
    fun `extractJsonString handles very long body without StackOverflow`() {
        // regression test: old regex parser hit StackOverflowError on long bodies
        val longBody = "x".repeat(5000)
        val json = """{"body": "$longBody"}"""
        val result = AutoUpdater.extractJsonString(json, "body")
        assertEquals(5000, result?.length)
    }
}
