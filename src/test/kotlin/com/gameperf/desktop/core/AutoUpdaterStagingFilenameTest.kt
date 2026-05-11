package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v4.4.1 — Tests for the staged-JAR filename derivation in [AutoUpdater].
 *
 * Spec scenarios N1 / N2 (auto-updater capability, MODIFIED requirement
 * "Staged JAR filename uses target version"): the staged filename MUST
 * derive from the release's target version (e.g. 4.4.1) rather than the
 * running [AppVersion.NAME] (e.g. 4.3.8), so the on-disk artifact accurately
 * names the version that will be installed.
 *
 * Pre-v4.4.1: filename used `AppVersion.NAME` so a v4.3.8 client downloading
 * v4.4.1 produced the misleading `android-game-perf-tool-desktop-4.3.8-staged.jar`.
 */
class AutoUpdaterStagingFilenameTest {

    @Test
    fun `stagedJarFilename derives from target version not from AppVersion NAME`() {
        // N1: running version (whatever AppVersion.NAME is today), target = a
        // distinct future version → filename must contain target, NOT the running
        // AppVersion.NAME. Using "9.9.9" guarantees the two strings differ
        // regardless of which release the codebase is currently bumped to.
        val futureTarget = "9.9.9"
        check(futureTarget != AppVersion.NAME) {
            "test premise broken: futureTarget must differ from AppVersion.NAME"
        }
        val filename = AutoUpdater.stagedJarFilename(targetVersion = futureTarget)
        assertTrue(
            filename.contains(futureTarget),
            "filename must reflect TARGET version $futureTarget, was: $filename"
        )
        assertFalse(
            filename.contains(AppVersion.NAME),
            "filename must NOT contain the running AppVersion.NAME, was: $filename"
        )
        assertTrue(
            filename.endsWith("-staged.jar"),
            "filename must end with -staged.jar suffix, was: $filename"
        )
    }

    @Test
    fun `stagedJarFilename is idempotent when running version equals target`() {
        // N2: running == target — no error, filename still uses target version.
        val sameVersion = AppVersion.NAME
        val filename = AutoUpdater.stagedJarFilename(targetVersion = sameVersion)
        assertTrue(
            filename.contains(sameVersion),
            "filename for running == target must include the version, was: $filename"
        )
        assertEquals(
            "android-game-perf-tool-desktop-$sameVersion-staged.jar",
            filename,
            "exact filename must match the canonical pattern"
        )
    }

    @Test
    fun `stagedJarFilename canonical shape for arbitrary version`() {
        // Triangulation: third distinct input ensures no hardcoded constant.
        val filename = AutoUpdater.stagedJarFilename(targetVersion = "5.0.0")
        assertEquals(
            "android-game-perf-tool-desktop-5.0.0-staged.jar",
            filename,
            "canonical filename pattern: <appName>-<targetVersion>-staged.jar"
        )
    }

    @Test
    fun `stagedJarFilename sanitizes filesystem-unsafe characters in version`() {
        // Defensive: a release tag like "4.4.1-beta" or "4.4.1/rc1" must not produce
        // a path-traversing filename. Slashes and backslashes get sanitized to '-'.
        val withSlash = AutoUpdater.stagedJarFilename(targetVersion = "4.4.1/rc1")
        assertFalse(withSlash.contains("/"), "forward slash must be sanitized, was: $withSlash")
        assertFalse(withSlash.contains("\\"), "backslash must be sanitized, was: $withSlash")
        assertTrue(withSlash.endsWith("-staged.jar"))

        // Hyphens (common in beta/rc tags) are allowed — only path separators are scrubbed.
        val withBeta = AutoUpdater.stagedJarFilename(targetVersion = "4.4.1-beta")
        assertEquals(
            "android-game-perf-tool-desktop-4.4.1-beta-staged.jar",
            withBeta,
            "hyphens in version are preserved (legitimate semver pre-release tags)"
        )
    }
}
