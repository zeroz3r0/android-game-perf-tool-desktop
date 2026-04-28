package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [LayerSelector.parseSurfaceFlingerListOutput] (moved out of
 * AdbBridge in v4.3.5 — pure parser, no adb dependency).
 *
 * Background — this parser exists because the output format of `dumpsys SurfaceFlinger
 * --list` changed between Android 10/11 and Android 12+. v3.1.9 only handled the newer
 * format, which meant devices like the Pixel XL (Android 10 / SDK 29) returned raw lines
 * that SurfaceFlinger's `--latency <layer>` command didn't recognize, producing empty
 * `fpsHistory` for entire sessions. Symptom: "avgFps: 0" in the final report despite the
 * game running fine.
 *
 * These tests lock in the dual-format behavior using REAL captured output from actual
 * devices so future refactors can't regress the fix.
 */
class SurfaceFlingerListParserTest {

    // ===== Android 10 (SDK 29) — Pixel XL format =====

    @Test
    fun `android 10 plain format resolves SurfaceView layer`() {
        // Real `dumpsys SurfaceFlinger --list` output from a Pixel XL running Android 10
        // with Touch2Goal Soccer (com.touch2goal.soccer) open. Observed fields include
        // the game's SurfaceView plus system layers that also contain the package name
        // via the window manager's per-activity bookkeeping.
        val output = """
            Display 4630946409886133889 name="Built-in Screen"
            NavigationBar0#0
            StatusBar#0
            com.touch2goal.soccer/com.unity3d.player.UnityPlayerActivity#0
            SurfaceView[com.touch2goal.soccer/com.unity3d.player.UnityPlayerActivity]@0#0
            Background for -SurfaceView[com.touch2goal.soccer/com.unity3d.player.UnityPlayerActivity]@0#0
        """.trimIndent()

        val result = LayerSelector.parseSurfaceFlingerListOutput(output, "com.touch2goal.soccer")

        assertNotNull(result, "must resolve a layer for a known package")
        assertTrue(
            result.startsWith("SurfaceView[com.touch2goal.soccer"),
            "expected the SurfaceView layer, got: $result"
        )
        assertTrue(
            !result.contains("Background"),
            "must not select the Background layer: $result"
        )
    }

    @Test
    fun `android 10 preserves trailing at and hash suffixes`() {
        // SurfaceFlinger needs the exact layer name including @0#0 for --latency to work.
        // Regression guard: make sure we don't strip those characters.
        val output = "SurfaceView[com.foo.bar/com.foo.bar.MainActivity]@0#0"
        val result = LayerSelector.parseSurfaceFlingerListOutput(output, "com.foo.bar")
        assertEquals("SurfaceView[com.foo.bar/com.foo.bar.MainActivity]@0#0", result)
    }

    // ===== Android 12+ — modern format =====

    @Test
    fun `android 12 modern format extracts name from RequestedLayerState`() {
        val output = """
            Display 4630946409886133889
             RequestedLayerState{NavigationBar0#0  parentId=0 ...}
             RequestedLayerState{SurfaceView[com.example.game/com.unity3d.player.UnityPlayerActivity]@0#0  parentId=42 flags=0x0}
        """.trimIndent()

        val result = LayerSelector.parseSurfaceFlingerListOutput(output, "com.example.game")

        assertEquals(
            "SurfaceView[com.example.game/com.unity3d.player.UnityPlayerActivity]@0#0",
            result
        )
    }

    @Test
    fun `android 12 modern format prefers BLAST SurfaceView`() {
        // When both BLAST and non-BLAST SurfaceViews exist for the same package, prefer BLAST
        // because that's the one receiving actual frames on modern devices.
        // v4.3.5: with recency ranking now in effect, both candidates carry the
        // same `@0#0` suffix so the BLAST tie-breaker decides — same outcome as
        // pre-v4.3.5, just expressed via the ranking compositor.
        val output = """
             RequestedLayerState{SurfaceView[com.example.game/foo]@0#0  parentId=10 flags=0x0}
             RequestedLayerState{SurfaceView[com.example.game/foo](BLAST)@0#0  parentId=11 flags=0x0}
        """.trimIndent()

        val result = LayerSelector.parseSurfaceFlingerListOutput(output, "com.example.game")

        assertNotNull(result)
        assertTrue(result.contains("BLAST"), "expected BLAST layer, got: $result")
    }

    // ===== Edge cases =====

    @Test
    fun `returns null when no layer matches the package`() {
        val output = """
            NavigationBar0#0
            StatusBar#0
            SurfaceView[com.other.app/MainActivity]@0#0
        """.trimIndent()

        val result = LayerSelector.parseSurfaceFlingerListOutput(output, "com.missing.pkg")
        assertNull(result)
    }

    @Test
    fun `returns null for empty output`() {
        assertNull(LayerSelector.parseSurfaceFlingerListOutput("", "com.any"))
    }

    @Test
    fun `falls back to non-SurfaceView line if no SurfaceView matches`() {
        // Edge case: package is mentioned but not in a SurfaceView (very rare, but should
        // not crash). The fallback should return the first matching line.
        val output = "com.foo.bar/MainActivity#0"
        val result = LayerSelector.parseSurfaceFlingerListOutput(output, "com.foo.bar")
        assertEquals("com.foo.bar/MainActivity#0", result)
    }

    // ===== v4.3.5 — recency ranking (zombie layer after ad close) =====

    @Test
    fun `recency picks newest SurfaceView when multiple suffixes are present`() {
        // After an ad close, SurfaceFlinger transiently exposes BOTH the old
        // (zombie) and the new game SurfaceView. The two layers differ only
        // in the trailing `#N` counter. The selector must pick the newer one
        // so that --latency returns a real frame stream, not the dead one.
        // Pre-v4.3.5 this returned the first match in dumpsys order, which
        // was deterministically the zombie on at least one Unity ad SDK and
        // caused the FPS HUD to stay stuck on `--` after the ad closed.
        val output = """
            Display 4630946409886133889
            SurfaceView[com.example.game/com.unity3d.player.UnityPlayerActivity]@0#0
            SurfaceView[com.example.game/com.unity3d.player.UnityPlayerActivity]@0#1
        """.trimIndent()

        val result = LayerSelector.parseSurfaceFlingerListOutput(output, "com.example.game")

        assertEquals(
            "SurfaceView[com.example.game/com.unity3d.player.UnityPlayerActivity]@0#1",
            result,
        )
    }

    // ===== v4.3.5 — multi-candidate API (parseSurfaceFlingerListAllCandidates) =====

    @Test
    fun `parseAllCandidates returns ranked list with newest first`() {
        // captureFrames needs to iterate candidates if the cached one returns
        // <3 lines from --latency. The pure parser exposes all of them ranked
        // by the same selector, so the caller can try each in order until
        // one succeeds.
        val output = """
            SurfaceView[com.example.game/foo]@0#0
            SurfaceView[com.example.game/foo]@0#2
            Background for -SurfaceView[com.example.game/foo]@0#0
        """.trimIndent()

        val all = LayerSelector.parseSurfaceFlingerListAllCandidates(output, "com.example.game")

        // Background must be filtered out, fresh #2 must be ranked above #0.
        assertEquals(2, all.size, "expected 2 non-noise candidates, got $all")
        assertEquals("SurfaceView[com.example.game/foo]@0#2", all[0])
        assertEquals("SurfaceView[com.example.game/foo]@0#0", all[1])
    }

    @Test
    fun `parseAllCandidates returns empty list when no layer matches`() {
        val output = "SurfaceView[com.other.app/MainActivity]@0#0"
        val all = LayerSelector.parseSurfaceFlingerListAllCandidates(output, "com.missing.pkg")
        assertTrue(all.isEmpty(), "expected empty candidates list, got $all")
    }

    @Test
    fun `skips Background layer in favor of main SurfaceView`() {
        // On pre-12 the Background layer often appears BEFORE the real SurfaceView in the
        // list. Make sure we don't pick it.
        val output = """
            Background for -SurfaceView[com.example.game/foo]@0#0
            SurfaceView[com.example.game/foo]@0#0
        """.trimIndent()

        val result = LayerSelector.parseSurfaceFlingerListOutput(output, "com.example.game")

        assertNotNull(result)
        assertTrue(
            !result.startsWith("Background"),
            "expected the real SurfaceView, got: $result"
        )
    }
}
