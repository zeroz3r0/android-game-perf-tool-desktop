package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [LayerSelector.selectBestLayer] — pure ranking logic for
 * SurfaceFlinger layer candidates.
 *
 * Background — the FPS-resume-after-ad bug (v4.3.5): SurfaceFlinger keeps the
 * old (zombie) game SurfaceView around briefly after an ad SDK destroys and
 * recreates it. The two layers differ only in the trailing `#N` (or `@N`)
 * suffix — the new one always has a higher counter. The pre-fix selector
 * used `firstOrNull()` which is order-dependent on `dumpsys --list`, which
 * is not documented to be stable, so it would deterministically re-elect
 * the dead layer every poll → `--latency` returns 1 line → HUD stuck at "--".
 *
 * The fix is a pure ranking function that prefers the highest-suffix layer
 * and explicitly rejects animation-leash, dim, and backdrop layers that ad
 * SDKs leave in the layer list. Pure → fully unit-testable, no adb mocking.
 */
class LayerSelectorTest {

    // ===== Recency ranking by #N / @N suffix =====

    @Test
    fun `picks highest hash suffix among same-package candidates`() {
        // Two SurfaceViews for the same package, only the trailing #N differs.
        // After an ad close, the new layer always has a higher counter, so the
        // selector must prefer #2 over #0 — the old behavior of firstOrNull()
        // would lock onto whichever came first in dumpsys output.
        val candidates = listOf(
            "SurfaceView[com.example.game/foo]@0#0",
            "SurfaceView[com.example.game/foo]@0#2",
        )
        val result = LayerSelector.selectBestLayer(candidates)
        assertEquals("SurfaceView[com.example.game/foo]@0#2", result)
    }

    @Test
    fun `picks highest hash suffix regardless of input order`() {
        // Reversed order — selector must NOT depend on input position.
        val candidates = listOf(
            "SurfaceView[com.example.game/foo]@0#5",
            "SurfaceView[com.example.game/foo]@0#1",
        )
        val result = LayerSelector.selectBestLayer(candidates)
        assertEquals("SurfaceView[com.example.game/foo]@0#5", result)
    }

    @Test
    fun `picks highest at suffix when hash is absent`() {
        // Some Android versions only emit `@N` (no `#N`). The selector should
        // still rank by that integer.
        val candidates = listOf(
            "SurfaceView[com.example.game/foo]@0",
            "SurfaceView[com.example.game/foo]@3",
        )
        val result = LayerSelector.selectBestLayer(candidates)
        assertEquals("SurfaceView[com.example.game/foo]@3", result)
    }

    // ===== Zombie + fresh layer selection =====

    @Test
    fun `prefers fresh layer over zombie layer with same name`() {
        // The exact resume-after-ad scenario. Both layers exist; the zombie
        // is the old SurfaceView the ad SDK destroyed but SurfaceFlinger
        // hasn't garbage-collected yet. Its frame buffer is stale.
        val candidates = listOf(
            "SurfaceView[com.touch2goal.soccer/com.unity3d.player.UnityPlayerActivity]@0#0",
            "SurfaceView[com.touch2goal.soccer/com.unity3d.player.UnityPlayerActivity]@0#1",
        )
        val result = LayerSelector.selectBestLayer(candidates)
        assertEquals(
            "SurfaceView[com.touch2goal.soccer/com.unity3d.player.UnityPlayerActivity]@0#1",
            result,
        )
    }

    // ===== BLAST preference (within same recency tier) =====

    @Test
    fun `prefers BLAST SurfaceView when suffixes are equal`() {
        // Pre-fix tie-breaker (BLAST > non-BLAST) must still hold when the
        // suffix doesn't disambiguate. Both candidates carry `@0#0` so the
        // recency score is identical (0 + 0 = 0) and the BLAST tag breaks
        // the tie.
        val candidates = listOf(
            "SurfaceView[com.example.game/foo]@0#0",
            "SurfaceView[com.example.game/foo](BLAST)@0#0",
        )
        val result = LayerSelector.selectBestLayer(candidates)
        assertNotNull(result)
        assertTrue(result.contains("BLAST"), "expected BLAST layer, got: $result")
    }

    @Test
    fun `prefers higher suffix even when other layer is BLAST`() {
        // Recency wins over BLAST tag — a fresh non-BLAST layer is more
        // important than a stale BLAST layer (the BLAST one was the OLD
        // pipeline, the fresh non-BLAST is what the game actually re-created).
        val candidates = listOf(
            "SurfaceView[com.example.game/foo](BLAST)#0",
            "SurfaceView[com.example.game/foo]#3",
        )
        val result = LayerSelector.selectBestLayer(candidates)
        assertEquals("SurfaceView[com.example.game/foo]#3", result)
    }

    // ===== Noise filtering =====

    @Test
    fun `ignores Background layer`() {
        val candidates = listOf(
            "Background for -SurfaceView[com.example.game/foo]@0#0",
            "SurfaceView[com.example.game/foo]@0#0",
        )
        val result = LayerSelector.selectBestLayer(candidates)
        assertNotNull(result)
        assertTrue(!result.startsWith("Background"), "must not pick Background: $result")
    }

    @Test
    fun `ignores animation-leash layer`() {
        // Ad SDKs and system transitions leave animation-leash entries that
        // appear with the package name in their string but never deliver real
        // frames. Picking them returns 1 line from --latency.
        val candidates = listOf(
            "Surface(name=Splash com.example.game)#0 - animation-leash",
            "SurfaceView[com.example.game/foo]@0#1",
        )
        val result = LayerSelector.selectBestLayer(candidates)
        assertEquals("SurfaceView[com.example.game/foo]@0#1", result)
    }

    @Test
    fun `ignores Dim and BackdropBlur layers`() {
        val candidates = listOf(
            "Dim for SurfaceView[com.example.game/foo]#0",
            "BackdropBlur com.example.game#0",
            "SurfaceView[com.example.game/foo]@0#2",
        )
        val result = LayerSelector.selectBestLayer(candidates)
        assertEquals("SurfaceView[com.example.game/foo]@0#2", result)
    }

    @Test
    fun `ignores Splash surface`() {
        val candidates = listOf(
            "Surface(name=Splash com.example.game)#0",
            "SurfaceView[com.example.game/foo]@0#1",
        )
        val result = LayerSelector.selectBestLayer(candidates)
        assertEquals("SurfaceView[com.example.game/foo]@0#1", result)
    }

    // ===== Empty / null fallbacks =====

    @Test
    fun `returns null for empty list`() {
        assertNull(LayerSelector.selectBestLayer(emptyList()))
    }

    @Test
    fun `returns null when every candidate is noise`() {
        val candidates = listOf(
            "Background for SurfaceView[com.example.game/foo]@0#0",
            "Dim com.example.game#0",
        )
        assertNull(LayerSelector.selectBestLayer(candidates))
    }

    // ===== Pre-Android-12 fallback (no parseable suffix) =====

    @Test
    fun `falls back to first SurfaceView when no suffix parseable`() {
        // Truly old Android (pre-10) layers don't always carry a numeric
        // suffix. The selector should still return the SurfaceView
        // candidate over the empty alternatives.
        val candidates = listOf(
            "SurfaceView[com.foo.bar/MainActivity]",
            "Background for -SurfaceView[com.foo.bar/MainActivity]",
        )
        val result = LayerSelector.selectBestLayer(candidates)
        assertEquals("SurfaceView[com.foo.bar/MainActivity]", result)
    }

    @Test
    fun `falls back to single non-noise candidate`() {
        // One layer, no decoration, no suffix — pre-Android-10 plain format.
        val candidates = listOf("SurfaceView[com.foo.bar/MainActivity]")
        val result = LayerSelector.selectBestLayer(candidates)
        assertEquals("SurfaceView[com.foo.bar/MainActivity]", result)
    }
}
