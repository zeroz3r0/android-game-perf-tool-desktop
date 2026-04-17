package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [AutoUpdater.selectFirstReleaseWithAsset], the pure function
 * that iterates releases by semver and selects the first one with a JAR asset.
 *
 * These tests exercise the bug scenario documented in CLAUDE.md: "release sin
 * JAR assets" — when a release exists but the CI workflow hasn't finished
 * uploading binaries yet. Before v4.2.13, this code path was untested.
 *
 * Test strategy: inject fake [fetchReleaseJson] and [extractJarAssetUrl] lambdas
 * to simulate various GitHub API response scenarios without network I/O.
 */
class AutoUpdaterSelectionTest {

    // ═══════ Helper: minimal release JSON builder ═══════

    /**
     * Build a minimal GitHub release JSON for testing.
     * If [jarUrl] is null, the release has no JAR assets (simulates workflow still building).
     */
    private fun buildReleaseJson(
        tag: String,
        name: String = tag,
        body: String = "Release notes for $tag",
        publishedAt: String = "2024-01-01T00:00:00Z",
        htmlUrl: String = "https://github.com/test/repo/releases/$tag",
        jarUrl: String? = null
    ): String {
        val assetsBlock = if (jarUrl != null) {
            """
            "assets": [
                {
                    "name": "GamePerf-windows-x64-${tag.removePrefix("v")}.jar",
                    "browser_download_url": "$jarUrl"
                }
            ]
            """.trimIndent()
        } else {
            "\"assets\": []"
        }

        return """
        {
            "tag_name": "$tag",
            "name": "$name",
            "body": "$body",
            "published_at": "$publishedAt",
            "html_url": "$htmlUrl",
            $assetsBlock
        }
        """.trimIndent()
    }

    // ═══════ Test: all releases lack assets → null ═══════

    @Test
    fun `selectFirstReleaseWithAsset returns null when all releases lack assets for platform`() {
        // Scenario: v4.2.12 and v4.2.11 exist but neither has JAR assets uploaded
        // (both are still building). Current version is 4.2.10.
        val tags = listOf("v4.2.12", "v4.2.11", "v4.2.10")
        val releaseJsons = mapOf(
            "v4.2.12" to buildReleaseJson("v4.2.12", jarUrl = null),
            "v4.2.11" to buildReleaseJson("v4.2.11", jarUrl = null),
            "v4.2.10" to buildReleaseJson("v4.2.10", jarUrl = "https://example.com/v4.2.10.jar")
        )

        val result = AutoUpdater.selectFirstReleaseWithAsset(
            tags = tags,
            currentVersion = "4.2.10",
            fetchReleaseJson = { tag -> releaseJsons[tag] },
            extractJarAssetUrl = { json ->
                // Simulate extractJarAssetUrl: parse the JSON and extract browser_download_url
                AutoUpdater.extractJsonString(json, "browser_download_url")
            }
        )

        assertNull(result, "Should return null when all newer releases lack JAR assets")
    }

    // ═══════ Test: skip higher semver without JAR, pick next ═══════

    @Test
    fun `selectFirstReleaseWithAsset skips higher semver when it has no JAR and picks next one`() {
        // Scenario: v4.2.12 has no assets (still building), v4.2.11 has assets.
        // Current version is 4.2.10. Should skip v4.2.12 and return v4.2.11.
        val tags = listOf("v4.2.12", "v4.2.11", "v4.2.10")
        val releaseJsons = mapOf(
            "v4.2.12" to buildReleaseJson("v4.2.12", jarUrl = null),
            "v4.2.11" to buildReleaseJson(
                "v4.2.11",
                jarUrl = "https://github.com/test/releases/download/v4.2.11/GamePerf-windows-x64-4.2.11.jar"
            ),
            "v4.2.10" to buildReleaseJson(
                "v4.2.10",
                jarUrl = "https://github.com/test/releases/download/v4.2.10/GamePerf-windows-x64-4.2.10.jar"
            )
        )

        val result = AutoUpdater.selectFirstReleaseWithAsset(
            tags = tags,
            currentVersion = "4.2.10",
            fetchReleaseJson = { tag -> releaseJsons[tag] },
            extractJarAssetUrl = { json ->
                AutoUpdater.extractJsonString(json, "browser_download_url")
            }
        )

        assertNotNull(result, "Should return v4.2.11 (skipping v4.2.12 without assets)")
        assertEquals("v4.2.11", result.tagName)
        assertEquals("4.2.11", result.version)
        assertEquals(
            "https://github.com/test/releases/download/v4.2.11/GamePerf-windows-x64-4.2.11.jar",
            result.jarUrl
        )
    }

    // ═══════ Test: already on latest with assets → null ═══════

    @Test
    fun `selectFirstReleaseWithAsset returns null when current version is already latest with assets`() {
        // Scenario: current version is 4.2.12, which is the latest.
        // Even though the release has assets, we're already running it.
        val tags = listOf("v4.2.12", "v4.2.11", "v4.2.10")
        val releaseJsons = mapOf(
            "v4.2.12" to buildReleaseJson(
                "v4.2.12",
                jarUrl = "https://github.com/test/releases/download/v4.2.12/GamePerf-windows-x64-4.2.12.jar"
            ),
            "v4.2.11" to buildReleaseJson(
                "v4.2.11",
                jarUrl = "https://github.com/test/releases/download/v4.2.11/GamePerf-windows-x64-4.2.11.jar"
            )
        )

        val result = AutoUpdater.selectFirstReleaseWithAsset(
            tags = tags,
            currentVersion = "4.2.12",
            fetchReleaseJson = { tag -> releaseJsons[tag] },
            extractJarAssetUrl = { json ->
                AutoUpdater.extractJsonString(json, "browser_download_url")
            }
        )

        assertNull(result, "Should return null when already on the latest version")
    }

    // ═══════ Test: fetch failure → skip tag, continue ═══════

    @Test
    fun `selectFirstReleaseWithAsset handles fetch failure gracefully and continues to next tag`() {
        // Scenario: v4.2.12 fetch fails (network error), v4.2.11 succeeds.
        // Should skip v4.2.12 and return v4.2.11.
        val tags = listOf("v4.2.12", "v4.2.11", "v4.2.10")

        val result = AutoUpdater.selectFirstReleaseWithAsset(
            tags = tags,
            currentVersion = "4.2.10",
            fetchReleaseJson = { tag ->
                when (tag) {
                    "v4.2.12" -> null // Simulate network failure
                    "v4.2.11" -> buildReleaseJson(
                        "v4.2.11",
                        jarUrl = "https://github.com/test/releases/download/v4.2.11/GamePerf.jar"
                    )
                    else -> buildReleaseJson(tag, jarUrl = null)
                }
            },
            extractJarAssetUrl = { json ->
                AutoUpdater.extractJsonString(json, "browser_download_url")
            }
        )

        assertNotNull(result, "Should skip failed fetch and pick v4.2.11")
        assertEquals("v4.2.11", result.tagName)
    }

    // ═══════ Test: happy path — highest version with JAR ═══════

    @Test
    fun `selectFirstReleaseWithAsset returns the highest version with JAR assets`() {
        // Scenario: All releases have assets, current is 4.2.9.
        // Should return v4.2.12 (the highest).
        val tags = listOf("v4.2.10", "v4.2.12", "v4.2.11", "v4.2.9") // Intentionally unsorted
        val releaseJsons = mapOf(
            "v4.2.12" to buildReleaseJson(
                "v4.2.12",
                name = "Release 4.2.12",
                body = "Big update",
                jarUrl = "https://github.com/test/releases/download/v4.2.12/GamePerf-4.2.12.jar"
            ),
            "v4.2.11" to buildReleaseJson(
                "v4.2.11",
                jarUrl = "https://github.com/test/releases/download/v4.2.11/GamePerf-4.2.11.jar"
            ),
            "v4.2.10" to buildReleaseJson(
                "v4.2.10",
                jarUrl = "https://github.com/test/releases/download/v4.2.10/GamePerf-4.2.10.jar"
            ),
            "v4.2.9" to buildReleaseJson(
                "v4.2.9",
                jarUrl = "https://github.com/test/releases/download/v4.2.9/GamePerf-4.2.9.jar"
            )
        )

        val result = AutoUpdater.selectFirstReleaseWithAsset(
            tags = tags,
            currentVersion = "4.2.9",
            fetchReleaseJson = { tag -> releaseJsons[tag] },
            extractJarAssetUrl = { json ->
                AutoUpdater.extractJsonString(json, "browser_download_url")
            }
        )

        assertNotNull(result, "Should return the highest available version")
        assertEquals("v4.2.12", result.tagName)
        assertEquals("4.2.12", result.version)
        assertEquals("Release 4.2.12", result.name)
        assertEquals("Big update", result.body)
        assertEquals(
            "https://github.com/test/releases/download/v4.2.12/GamePerf-4.2.12.jar",
            result.jarUrl
        )
    }

    // ═══════ Test: empty tags list → null ═══════

    @Test
    fun `selectFirstReleaseWithAsset returns null when tags list is empty`() {
        val result = AutoUpdater.selectFirstReleaseWithAsset(
            tags = emptyList(),
            currentVersion = "4.2.10",
            fetchReleaseJson = { null },
            extractJarAssetUrl = { null }
        )

        assertNull(result, "Should return null for empty tags list")
    }

    // ═══════ Test: multiple fetch failures before success ═══════

    @Test
    fun `selectFirstReleaseWithAsset continues through multiple fetch failures`() {
        // Scenario: v4.2.14, v4.2.13, v4.2.12 all fail to fetch, v4.2.11 succeeds.
        val tags = listOf("v4.2.14", "v4.2.13", "v4.2.12", "v4.2.11", "v4.2.10")
        var fetchAttempts = 0

        val result = AutoUpdater.selectFirstReleaseWithAsset(
            tags = tags,
            currentVersion = "4.2.10",
            fetchReleaseJson = { tag ->
                fetchAttempts++
                when (tag) {
                    "v4.2.14", "v4.2.13", "v4.2.12" -> null // All fail
                    "v4.2.11" -> buildReleaseJson(
                        "v4.2.11",
                        jarUrl = "https://example.com/GamePerf-4.2.11.jar"
                    )
                    else -> null
                }
            },
            extractJarAssetUrl = { json ->
                AutoUpdater.extractJsonString(json, "browser_download_url")
            }
        )

        assertNotNull(result, "Should eventually find v4.2.11 after failures")
        assertEquals("v4.2.11", result.tagName)
        assertEquals(4, fetchAttempts, "Should have attempted 4 fetches (3 failures + 1 success)")
    }

    // ═══════ Test: semver ordering handles different version lengths ═══════

    @Test
    fun `selectFirstReleaseWithAsset correctly orders versions with different segment counts`() {
        // Scenario: Mix of 2-segment and 3-segment versions
        val tags = listOf("v4.3", "v4.2.15", "v4.2.9", "v4.2")
        val releaseJsons = mapOf(
            "v4.3" to buildReleaseJson(
                "v4.3",
                jarUrl = "https://example.com/GamePerf-4.3.jar"
            ),
            "v4.2.15" to buildReleaseJson(
                "v4.2.15",
                jarUrl = "https://example.com/GamePerf-4.2.15.jar"
            ),
            "v4.2.9" to buildReleaseJson(
                "v4.2.9",
                jarUrl = "https://example.com/GamePerf-4.2.9.jar"
            ),
            "v4.2" to buildReleaseJson(
                "v4.2",
                jarUrl = "https://example.com/GamePerf-4.2.jar"
            )
        )

        val result = AutoUpdater.selectFirstReleaseWithAsset(
            tags = tags,
            currentVersion = "4.2.10",
            fetchReleaseJson = { tag -> releaseJsons[tag] },
            extractJarAssetUrl = { json ->
                AutoUpdater.extractJsonString(json, "browser_download_url")
            }
        )

        // v4.3 > v4.2.15 > v4.2.10 (current), so should return v4.3
        assertNotNull(result)
        assertEquals("v4.3", result.tagName)
    }

    // ═══════ Test: extractJarAssetUrl returns null → treated as no asset ═══════

    @Test
    fun `selectFirstReleaseWithAsset treats extractJarAssetUrl null as missing asset`() {
        // Scenario: fetch succeeds but extractJarAssetUrl always returns null
        // (e.g., release has assets but none match the platform)
        val tags = listOf("v4.2.12", "v4.2.11", "v4.2.10")

        val result = AutoUpdater.selectFirstReleaseWithAsset(
            tags = tags,
            currentVersion = "4.2.9",
            fetchReleaseJson = { tag ->
                buildReleaseJson(tag, jarUrl = "https://example.com/$tag.jar")
            },
            extractJarAssetUrl = { _ -> null } // Always fails to find JAR for platform
        )

        assertNull(result, "Should return null when no release has matching platform JAR")
    }
}
