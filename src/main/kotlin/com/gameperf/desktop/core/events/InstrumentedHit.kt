package com.gameperf.desktop.core.events

/**
 * A successfully-parsed instrumented logcat hit.
 *
 * Produced by [InstrumentedLineParser.parse] when a message body matches one
 * of the fixed `{Tag}.Start` / `{Tag}.Stop` literals with [tag] in the
 * allowlist. Consumed by [EventDetectorImpl]'s instrumented branch which
 * routes opens vs closes per spec IEM-004 (per-tag-keyed lifecycle).
 *
 * @property tag One of the four allowed phase tags (`CINEMATIC`, `TUTORIAL`,
 *   `GAMEPLAY_DENSE`, `SPECIAL_EVENT`). Case-sensitive — see IEM-003.
 * @property isStart `true` for `{Tag}.Start`, `false` for `{Tag}.Stop`.
 *
 * @since instrumented-event-mode change
 */
internal data class InstrumentedHit(val tag: String, val isStart: Boolean)
