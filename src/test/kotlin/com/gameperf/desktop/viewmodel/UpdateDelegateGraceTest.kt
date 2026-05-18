package com.gameperf.desktop.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v4.6.1 — Asserts the grace period that [UpdateDelegate] waits between
 * surfacing the "Cerrando GamePerf..." status message and calling
 * `exitProcess(0)` on the elevated-exit branch.
 *
 * Bug context (engram obs #474): the v4.6.0 path used `delay(1500)` inline,
 * which gave Compose/Skiko only 1.5 s to release native GL contexts + the
 * AWT EDT before the JVM died. Combined with the broad helper-side process
 * filter, the UAC helper aborted with `exit 1` because it observed a
 * `java.exe` (the bundled JVM) still alive after 30 s.
 *
 * The fix is twofold:
 *   1. helper-side timeout widened (covered in [com.gameperf.desktop.core.AutoUpdaterHelperScriptTest]).
 *   2. JVM-side grace extended to 3000 ms to give Compose/Skiko more headroom
 *      BEFORE exitProcess is called (this file's concern).
 *
 * We expose the value as a private const so this test can pin it via
 * reflection without forcing the production code to expose an internal field.
 */
class UpdateDelegateGraceTest {

    @Test
    fun `grace before exit is 3 seconds, not the legacy 1500ms`() {
        // Reflection probe of the companion-object const. We deliberately
        // assert on the EXACT value rather than ">= 3000L" — pinning the
        // literal forces a future regression (someone silently flipping it
        // back to 1.5 s) to break this test loudly.
        val grace = readGraceMs()
        assertEquals(
            3000L,
            grace,
            "GRACE_BEFORE_EXIT_MS must be 3000L (bug #474 — Compose/Skiko cleanup " +
                "needs ≥3 s on Windows bundles; legacy was 1500L)"
        )
    }

    @Test
    fun `grace is strictly greater than the legacy 1500ms (triangulation)`() {
        // Triangulation: the literal might be 3000L today but the SEMANTIC
        // contract is "must be > 1500L". This second test catches a future
        // refactor that lowers grace below the safe floor even if it tweaks
        // the exact value (e.g., someone trying 2000L "to make it snappier"
        // and re-introducing the race).
        val grace = readGraceMs()
        assertTrue(
            grace > 1500L,
            "GRACE_BEFORE_EXIT_MS must exceed legacy 1500L floor — actual: $grace"
        )
    }

    private fun readGraceMs(): Long {
        // The const lives on UpdateDelegate's companion. Kotlin private const
        // is a static field on the enclosing class; we walk the declared fields
        // and pick the one named GRACE_BEFORE_EXIT_MS so we don't depend on the
        // synthetic companion class layout.
        val clazz = UpdateDelegate::class.java
        val field = clazz.declaredFields.firstOrNull { it.name == "GRACE_BEFORE_EXIT_MS" }
            ?: error(
                "UpdateDelegate must declare a private const val GRACE_BEFORE_EXIT_MS: Long " +
                    "in its companion object (v4.6.1 — extracted from the inline 1500L literal)"
            )
        field.isAccessible = true
        // Static field → get(null). For a private const, Kotlin emits a static
        // backing field on the outer class itself.
        return field.getLong(null)
    }
}
