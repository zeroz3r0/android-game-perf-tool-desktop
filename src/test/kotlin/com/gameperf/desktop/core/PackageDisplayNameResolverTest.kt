package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v4.8.0 — spec `package-display-name-resolver` scenarios.
 *
 * Pure function: no I/O, no mutable state. Tests drive
 * [PackageDisplayNameResolver.displayName] directly.
 *
 * Coverage:
 *  - 3 curated entries (S1 happy path)
 *  - Unknown package → fallback (S2)
 *  - Empty / whitespace input → empty string (S3)
 *  - Dotless input → capitalized whole string (S4)
 *  - Leading / trailing dots stripped before lookup (S5)
 *  - Curated entry MUST include `com.vivastudios.pieceout` (v4.8.0 acceptance)
 *
 * @since v4.8.0
 */
class PackageDisplayNameResolverTest {

    // S1 — curated entries return the verbatim display name.
    @Test
    fun `resolves com_vivastudios_pieceout to Piece Out`() {
        assertEquals("Piece Out", PackageDisplayNameResolver.displayName("com.vivastudios.pieceout"))
    }

    @Test
    fun `resolves com_vivastudios_tower_battle to Tower Battle`() {
        assertEquals("Tower Battle", PackageDisplayNameResolver.displayName("com.vivastudios.tower_battle"))
    }

    @Test
    fun `resolves com_mafia_paradise_tycoon to Mafia Paradise Tycoon`() {
        assertEquals("Mafia Paradise Tycoon", PackageDisplayNameResolver.displayName("com.mafia.paradise_tycoon"))
    }

    // S2 — unknown package falls back to capitalized last segment.
    @Test
    fun `unknown package falls back to capitalized last segment`() {
        assertEquals("Foo", PackageDisplayNameResolver.displayName("com.unknown.foo"))
    }

    @Test
    fun `unknown package with mixed casing preserves non-leading characters`() {
        // `myGame` → `MyGame` (only first char gets touched; rest preserved).
        assertEquals("MyGame", PackageDisplayNameResolver.displayName("com.example.myGame"))
    }

    // S3 — empty / whitespace input.
    @Test
    fun `empty string returns empty`() {
        assertEquals("", PackageDisplayNameResolver.displayName(""))
    }

    @Test
    fun `whitespace-only string returns empty`() {
        assertEquals("", PackageDisplayNameResolver.displayName("   "))
    }

    // S4 — dotless input: capitalize the whole single-segment string.
    @Test
    fun `dotless input capitalizes single segment`() {
        assertEquals("Monolith", PackageDisplayNameResolver.displayName("monolith"))
    }

    // S5 — leading / trailing dots are trimmed deterministically.
    @Test
    fun `leading and trailing dots are trimmed before lookup`() {
        assertEquals("Foo", PackageDisplayNameResolver.displayName(".foo."))
    }

    @Test
    fun `trailing dots do not break curated lookup`() {
        assertEquals("Piece Out", PackageDisplayNameResolver.displayName("com.vivastudios.pieceout."))
    }
}
