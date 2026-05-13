package com.gameperf.desktop.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One row of the update attempt history (one jsonl line in `history.jsonl`).
 *
 * Pure @Serializable data class — zero I/O, zero coroutines. Used by
 * `UpdateHistoryStore` for append-only persistence (cap-100, FIFO eviction)
 * per design ADR-1 / ADR-4.
 *
 * @property timestamp     Epoch millis when the attempt terminated.
 * @property fromVersion   Running app version at attempt start (e.g. "4.4.0").
 * @property toVersion     Target release version (e.g. "4.4.1").
 * @property outcome       Terminal [UpdateOutcome] for this attempt.
 * @property durationMs    Wall-clock duration of the attempt in millis.
 * @property errorMessage  Short diagnostic; `null` for [UpdateOutcome.Success].
 * @property helperLogTail Optional trailing lines of `last-update.log` captured at terminal time.
 */
@Serializable
data class UpdateAttempt(
    val timestamp: Long,
    val fromVersion: String,
    val toVersion: String,
    val outcome: UpdateOutcome,
    val durationMs: Long,
    val errorMessage: String? = null,
    val helperLogTail: String? = null,
)

/**
 * JSON formatter shared by [writeJsonlLine] / [parseJsonlLine].
 *
 * Configured to omit nulls (compact lines) and tolerate forward-compatible
 * additions via `ignoreUnknownKeys`. The `classDiscriminator` is kept at
 * the kotlinx default `"type"` for sealed UpdateOutcome polymorphism.
 */
private val UPDATE_ATTEMPT_JSON: Json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
}

/**
 * Serializes [attempt] to a single-line JSON string suitable for jsonl persistence.
 *
 * The returned string contains no embedded newlines (kotlinx-serialization's
 * default emitter is already non-pretty) so it is safe to append followed by
 * a single `\n` separator.
 */
fun writeJsonlLine(attempt: UpdateAttempt): String =
    UPDATE_ATTEMPT_JSON.encodeToString(UpdateAttempt.serializer(), attempt)

/**
 * Parses a single jsonl line back into an [UpdateAttempt].
 *
 * Returns `null` for empty input, malformed JSON, or schema mismatches —
 * never throws. This is the contract `UpdateHistoryStore` relies on to
 * tolerate corrupt lines without aborting the read of the whole file.
 */
fun parseJsonlLine(line: String): UpdateAttempt? {
    if (line.isBlank()) return null
    return try {
        UPDATE_ATTEMPT_JSON.decodeFromString(UpdateAttempt.serializer(), line)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
