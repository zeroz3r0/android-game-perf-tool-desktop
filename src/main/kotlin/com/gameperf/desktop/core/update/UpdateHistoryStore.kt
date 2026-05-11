package com.gameperf.desktop.core.update

import java.io.File

/**
 * Append-only persistence for [UpdateAttempt] rows.
 *
 * Backed by a jsonl file (one [UpdateAttempt] per line). Capped at
 * [DEFAULT_CAP] entries with FIFO eviction on overflow per design
 * ADR-1 / ADR-4. Reads are corrupt-tolerant: any line that fails to
 * parse via [parseJsonlLine] is silently skipped.
 *
 * Single-process / single-writer: the running JVM is the only writer
 * to `~/GamePerf Reports/updates/history.jsonl`, so no external
 * locking is required.
 */
interface UpdateHistoryStore {

    /**
     * Append a single [UpdateAttempt] row to the underlying file.
     *
     * Creates parent directories and the file itself on first write.
     * After the append, the file is truncated to at most [DEFAULT_CAP]
     * lines (oldest entries evicted).
     *
     * Errors are swallowed and logged in production-style implementations
     * (writing to the history file must never break the update flow).
     */
    fun append(attempt: UpdateAttempt)

    /**
     * Return up to [limit] most recent [UpdateAttempt] entries in
     * chronological order (oldest first within the returned slice).
     *
     * Missing file → empty list.
     * Corrupt lines → silently skipped.
     * Never throws.
     */
    fun recentAttempts(limit: Int = DEFAULT_LIMIT): List<UpdateAttempt>

    companion object {
        /** Maximum entries retained in the jsonl file (FIFO eviction). */
        const val DEFAULT_CAP: Int = 100

        /** Default `recentAttempts` slice if the caller does not specify one. */
        const val DEFAULT_LIMIT: Int = 100
    }
}

/**
 * File-backed [UpdateHistoryStore] writing one [UpdateAttempt] per
 * jsonl line to [file].
 *
 * Implementation details:
 *   - parent directory auto-created on first append
 *   - cap-100 enforced after every append by re-reading + rewriting
 *     the file with `takeLast(cap)` (file is small: \u2264100 lines × ~200 B)
 *   - corrupt lines are skipped on read via [parseJsonlLine] returning `null`
 *   - I/O errors during append are swallowed (caller path must not break)
 *
 * Suitable for single-writer usage (the running JVM owns the file).
 */
class FileUpdateHistoryStore(
    private val file: File,
    private val cap: Int = UpdateHistoryStore.DEFAULT_CAP,
) : UpdateHistoryStore {

    override fun append(attempt: UpdateAttempt) {
        runCatching {
            file.parentFile?.mkdirs()
            val existing: List<String> = if (file.exists()) {
                file.readLines().filter { it.isNotBlank() }
            } else {
                emptyList()
            }
            val next: List<String> = (existing + writeJsonlLine(attempt)).takeLast(cap)
            file.writeText(next.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    override fun recentAttempts(limit: Int): List<UpdateAttempt> {
        if (!file.exists()) return emptyList()
        val parsed: List<UpdateAttempt> = runCatching {
            file.readLines()
                .asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { parseJsonlLine(it) }
                .toList()
        }.getOrElse { emptyList() }
        return if (parsed.size <= limit) parsed else parsed.takeLast(limit)
    }
}
