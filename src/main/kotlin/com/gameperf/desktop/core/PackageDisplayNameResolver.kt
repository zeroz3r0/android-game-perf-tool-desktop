package com.gameperf.desktop.core

/**
 * Resolves Android package names (e.g. `com.vivastudios.pieceout`) to
 * human-readable display names (e.g. `Piece Out`) for UI surfaces such as
 * report titles, history list rows, and shareable filenames.
 *
 * Why: raw package names are noisy in user-facing strings — they expose
 * the vendor's reverse-DNS convention, hide the actual game name when the
 * last segment is an internal codename, and look bad in shared reports
 * that go to non-engineers (marketing, producers, leadership).
 *
 * Strategy:
 *   1. Exact lookup in the curated [packageToDisplay] table.
 *   2. Deterministic fallback: drop leading/trailing dots, take the last
 *      segment, capitalize the first letter (`com.unknown.foo → Foo`).
 *   3. Empty / whitespace input → empty string. No crash, no placeholder.
 *
 * Anti-duplication rule (CLAUDE.md v4.4.0): package → display-name lookup
 * lives ONLY here. Do NOT scatter `substringAfterLast` + `capitalize`
 * one-off helpers across `ReportGenerator`, `HistoryEntry`, etc. — call
 * [displayName] at the call site.
 *
 * Mirrors [DeviceNameResolver] in shape and intent. Both resolvers are
 * pure (no I/O, no mutable state) so they can be tested without
 * Compose/JVM context.
 *
 * @since v4.8.0
 */
object PackageDisplayNameResolver {

    /**
     * Curated map of `packageName → displayName`. Append entries
     * preserving alphabetical order by package so duplicates surface
     * during code review.
     *
     * Start small: only the games actively tested by the QA team get
     * an entry. Unknown packages get the fallback. This keeps the table
     * honest — no aspirational entries for games we have never seen.
     */
    internal val packageToDisplay: Map<String, String> = mapOf(
        "com.vivastudios.pieceout" to "Piece Out",
        "com.vivastudios.tower_battle" to "Tower Battle",
        "com.vivastudios.towerbattle" to "Tower Battle",
        "com.mafia.paradise_tycoon" to "Mafia Paradise Tycoon",
    )

    /**
     * Resolve a package name to a human-readable display name.
     *
     * @param pkg Raw Android package name (e.g. `com.vivastudios.pieceout`).
     *            Surrounding whitespace and leading/trailing dots are
     *            tolerated and stripped before lookup.
     * @return The curated display name if the package is in the table,
     *         otherwise a deterministic capitalized version of the last
     *         dotted segment. Empty input → empty string.
     */
    fun displayName(pkg: String): String {
        val trimmed = pkg.trim().trim('.')
        if (trimmed.isEmpty()) return ""

        packageToDisplay[trimmed]?.let { return it }

        // Fallback: last segment, first char uppercased. Handles both
        // dotted (`com.example.foo`) and dotless (`monolith`) inputs.
        val lastSegment = trimmed.substringAfterLast('.')
        return lastSegment.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
    }
}
