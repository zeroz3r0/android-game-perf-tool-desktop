package com.gameperf.desktop.core.sharing

import java.io.File
import java.util.Base64

/**
 * Pure object that converts a self-contained HTML report file into a
 * `data:text/html;base64,...` URL that the user can paste directly into the
 * address bar of any modern browser to view the report without any external
 * service.
 *
 * Pipeline:
 *  1. Validate file exists.
 *  2. Validate file size <= [MAX_SIZE_BYTES] (exclusive over the cap).
 *  3. Read the file, Base64-encode, prepend the MIME prefix.
 *
 * Failures (file missing, IO exception, file over cap) return `null` so the
 * caller can surface a UI message without crashing. The orchestrator
 * differentiates between "file missing" and "over cap" by inspecting
 * `file.exists()` + `file.length()` against [MAX_SIZE_BYTES] before showing
 * the error string.
 *
 * Memory safety: `file.readBytes()` loads the entire file in heap. The 5 MB
 * cap keeps allocation well within `-Xmx2048m`. Future bumps to the cap
 * MUST account for this — a 50 MB cap would still be safe but a 500 MB
 * cap would not.
 *
 * Cap rationale: 5 MB is the practical paste limit observed across Slack
 * (truncates above ~5 MB), Discord (warns above ~6 MB), Notion (silent
 * fail above ~10 MB). Above this, recommend the user share the HTML
 * file directly via the "Compartir reporte" button.
 *
 * Pure: no I/O outside the file read, no mutable state, no Compose context.
 * Testable without mocks via `@TempDir`.
 *
 * @since v5.0.0
 */
object DataUrlBuilder {
    /**
     * Maximum file size accepted by [build], exclusive cap: a file of
     * exactly this many bytes is accepted; a file of `MAX_SIZE_BYTES + 1`
     * is rejected and the function returns `null`.
     *
     * 5 MB picked to clear common chat client paste limits while keeping
     * the JVM heap footprint trivial.
     */
    const val MAX_SIZE_BYTES: Long = 5L * 1024L * 1024L

    private const val MIME_PREFIX: String = "data:text/html;base64,"

    /**
     * Build a `data:text/html;base64,…` URL from [file]. Returns `null` if
     * the file does not exist, exceeds [MAX_SIZE_BYTES], or any IO
     * exception occurs while reading.
     *
     * Empty files produce a valid prefix-only URL (`data:text/html;base64,`).
     */
    fun build(file: File): String? = runCatching {
        if (!file.exists()) return null
        if (file.length() > MAX_SIZE_BYTES) return null
        val bytes = file.readBytes()
        MIME_PREFIX + Base64.getEncoder().encodeToString(bytes)
    }.getOrNull()
}
