# Tasks — logcat-event-stream

Topic key: `sdd/logcat-event-stream/tasks`
Depends on: spec `sdd/logcat-event-stream/spec`, design `sdd/logcat-event-stream/design`.

TDD strict (red → green per item). Detekt clean at every sprint boundary. No commits / pushes / branch ops handled by this change.

Total estimated effort: **~4 days**. Sprints are DAG-ordered (later sprints depend on earlier ones). `./gradlew test` must be green at every sprint boundary.

---

## Sprint 0 — Foundation: buffer + dual-emit + PID resolver (~1 day)

Goal: data-plane layer ready, no UI yet. Detector path bit-for-bit unchanged.

- [ ] **T0.1** (RED) — Create `core/events/LogcatStreamBufferTest.kt`. Cases per LCS-004:
  (a) append below `maxLines` does not evict;
  (b) append at exact `maxLines` evicts oldest, size stays at cap;
  (c) byte cap (`maxBytes`) triggers eviction even when line count is under;
  (d) concurrent `append` from producer thread + `snapshot` from reader thread → no `ConcurrentModificationException`, snapshot is consistent;
  (e) `clear()` resets size and bytes to 0.
  Run → red (class doesn't exist).
- [ ] **T0.2** (GREEN) — Implement `core/events/LogcatStreamBuffer.kt` per design §2. `ArrayDeque<LogLine>` + `@Volatile currentBytes` + `synchronized(lock)` for append/snapshot. Byte heuristic per design.
- [ ] **T0.3** (RED) — Create `core/events/LogcatLineSerializationTest.kt`. Cases per LCS-005:
  (a) populated `LogLine` round-trips value-equal;
  (b) defaulted `LogLine()` encodes all 6 fields;
  (c) JSON without `level` field decodes with default `'I'`.
  Run → red (LogLine is not `@Serializable` yet).
- [ ] **T0.4** (GREEN) — Annotate `LogLine` with `@Serializable` and default every field (per design §1). Verify no behaviour change to `LogcatLineParser` (its tests must still pass).
- [ ] **T0.5** (RED) — Create `core/events/LogcatCaptureDualEmitTest.kt`. Cases per LCS-003 + LCS-012:
  (a) `onLine` invoked with parsed line when `onRawLine = null` (legacy path);
  (b) BOTH `onLine` AND `onRawLine` invoked with value-equal `LogLine` when both non-null;
  (c) malformed input line → NEITHER callback invoked;
  (d) `onRawLine` throws → `onLine` still invoked for the SAME line AND subsequent lines.
  Run → red.
- [ ] **T0.6** (GREEN) — Extend `LogcatCapture` per design §5: add `onRawLine: ((LogLine) -> Unit)? = null` constructor arg; invoke before `onLine`; wrap in `try/catch`.
- [ ] **T0.7** (RED) — Create `core/AdbBridgeResolvePidsTest.kt`. Cases per LCS-002:
  (a) single PID from `pidof` → list of 1;
  (b) multi-PID space-separated → list of N;
  (c) empty `pidof` output → empty list;
  (d) garbage (non-integer tokens) → only integer-parseable tokens kept.
  Use `FakeAdbBridge` with scripted `shellResponses["pidof com.example.game"]`.
  Run → red (function doesn't exist).
- [ ] **T0.8** (GREEN) — Add `AdbBridge.resolveGamePids(deviceId, pkg): List<Int>` per design §7. Refactor `captureProcessCpuPercent` to call the new function via `.firstOrNull()` (zero behaviour change). Add to `AdbBridgeApi` interface; `RealAdbBridge` passthrough; `FakeAdbBridge` scripted.
- [ ] **T0.9** (RED) — Create `core/AdbBridgeStartLogcatPidTest.kt`. Cases per LCS-001:
  (a) `pids = [12345]` → command includes `--pid 12345`, NO `-b main,system,events`, NO tag args;
  (b) `pids = [12345, 12346, 12347]` → command includes all three `--pid` flags;
  (c) `pids = emptyList(), tagArgs = ["Ads:D", "*:S"]` → command preserves v4.4.x form (`-b main,system,events ... Ads:D *:S`);
  (d) both empty → unfiltered fallback `adb logcat -v threadtime`.
  Drive via `FakeAdbBridge` capturing the `ProcessBuilder` arg list.
  Run → red.
- [ ] **T0.10** (GREEN) — Extend `AdbBridge.startLogcat` and `AdbBridgeApi` per design §6: add `pids: List<Int> = emptyList()` defaulted arg. Command-construction precedence: `pids.isNotEmpty()` ⇒ PID mode; else `tagArgs.isNotEmpty()` ⇒ tag mode; else unfiltered.
- [ ] **T0.11** (RED→GREEN) — Create `core/events/PidWatchdogTest.kt`. Cases per LCS-007:
  (a) `pidof` returns same PIDs on two ticks → callback NOT invoked;
  (b) `pidof` returns different PIDs on second tick → callback invoked once with `(old, new)`;
  (c) `pidof` returns empty (game died) → callback invoked with `(old, [])`.
  Implement `PidWatchdog` per design §4.
- [ ] **T0.12** (RED→GREEN) — Create `core/events/LogcatStreamConsumerTest.kt`. Cases:
  (a) `onRawLine` appends to buffer AND updates tail when level passes filter;
  (b) tail caps at `HUD_TAIL_SIZE = 20`, oldest dropped;
  (c) level-filter change re-applies on subsequent lines (existing tail not retroactively filtered — that's the v1 semantics; documented).
  Implement `LogcatStreamConsumer` per design §3.
- [ ] **T0.13** — Detekt local on all changed files = 0 warnings.

Done-when: all Sprint 0 tests green. `LogcatCapture` dual-emit verified. PID resolver + watchdog working. `./gradlew test` green.

---

## Sprint 1 — Persistence (~0.5 day)

Goal: `SessionHistory` round-trips the stream; legacy files load with empty default.

- [ ] **T1.1** (RED) — Create `core/SessionHistoryLogcatStreamTest.kt`. Cases per LCS-006, LCS-011:
  (a) save then load a `HistoryEntry` with `logcatStream` of 5 mixed-level lines → all 5 round-trip value-equal;
  (b) load a legacy fixture (checked in at `src/test/resources/history-v4-5-0.json`) → entries decode, `logcatStream == emptyList()`;
  (c) save a `HistoryEntry` with `logcatStream = emptyList()` → field present in JSON with `[]` (per `encodeDefaults = true`).
  Run → red.
- [ ] **T1.2** (GREEN) — Extend `SessionHistory.SerializableEntry` with `val logcatStream: List<LogLine> = emptyList()`. Extend `HistoryEntry` with the same field. Update `toSerializable` / `toHistoryEntry` converters. Bump `SCHEMA_VERSION` constant from `5` to `6`.
- [ ] **T1.3** (GREEN) — Extend `SessionResult` (`viewmodel/AppViewModel.kt:144`) with `val logcatStream: List<LogLine> = emptyList()`. Field is named-only (positional callers unaffected).
- [ ] **T1.4** — Add a checked-in `src/test/resources/history-v4-5-0.json` fixture produced by running v4.5.0 against the test harness OR hand-curated to match the v4.5.0 schema. Document its provenance in the test file.
- [ ] **T1.5** — Re-run the existing `SessionHistoryTest` suite. Verify zero regression (defaulted field preserves all positional/named-args call sites).
- [ ] **T1.6** — Detekt local clean.

Done-when: round-trip test green; legacy fixture loads; full session-history suite green.

---

## Sprint 2 — HUD live tail (~1 day)

Goal: capture screen renders the last 20 lines with auto-scroll + level-chip filter.

- [ ] **T2.1** (RED) — Create `viewmodel/AppViewModelLogcatStreamTest.kt`. Cases per LCS-008, LCS-013:
  (a) `startCapture` → `_logcatLevelFilter.value == setOf('E','W','I')`;
  (b) feeding 30 lines through the consumer → `liveMetrics.value.logcatTail.size == 20`, all in `setOf('E','W','I')`;
  (c) change filter to `setOf('E')` then feed 10 more lines → tail contains only those E lines (existing tail unchanged per v1 semantics);
  (d) on `stopCapture` → `_result.value.logcatStream` equals `consumer.snapshot()`;
  (e) on iOS-platform device → no consumer wired, `_result.value.logcatStream == emptyList()`.
  Run → red.
- [ ] **T2.2** (GREEN) — Wire `AppViewModel.startCapture` per design §9:
  - Resolve PIDs via `adb.resolveGamePids(...)`;
  - Construct `LogcatStreamConsumer(levelFilter = _logcatLevelFilter)`;
  - Pass `onRawLine = consumer::onRawLine` to `detector.start(..., onRawLine = ...)`;
  - Launch `consumer.tail.collect { _liveMetrics.value = _liveMetrics.value.copy(logcatTail = it) }`;
  - Launch `PidWatchdog` with `onPidChange` callback emitting the synthetic marker + restarting the underlying capture.
- [ ] **T2.3** (GREEN) — Extend `LiveMetrics` with `val logcatTail: List<LogLine> = emptyList()`.
- [ ] **T2.4** (GREEN) — Extend `EventDetector` interface (and `EventDetectorImpl`) to accept `onRawLine: ((LogLine) -> Unit)? = null` defaulted arg on `start(...)`. Wire it through to `LogcatCapture`.
- [ ] **T2.5** (GREEN) — UI: add a `LogcatTailPanel` Compose component on the capture screen showing `logcatTail` with row colors per level. Add level-chip toggle row (E / W / I / D / V) bound to `_logcatLevelFilter`. Auto-scroll to most recent.
- [ ] **T2.6** — Manual smoke test: launch real capture on a connected Android device, verify lines stream in real-time with correct level coloring, verify chip-toggle changes visible levels.
- [ ] **T2.7** — Detekt local clean.

Done-when: `AppViewModelLogcatStreamTest` green; HUD live tail visible in capture screen; level chips functional.

---

## Sprint 3 — Report HTML rendering + click-to-window (~1.5 day)

Goal: post-mortem section in the report HTML; clicking any metric point opens a ±5 s log modal.

- [ ] **T3.1** (RED) — Create `report/ReportGeneratorLogcatStreamTest.kt`. Cases per LCS-009, LCS-010:
  (a) `logcatStream = emptyList()` → no `id="sec-logcat-stream"` in HTML, no log CSS classes;
  (b) `logcatStream` with 1 E + 5 I → exactly one `<tr class="log-row-error">`, five `<tr class="log-row-info">`;
  (c) every log row carries `data-ts="<ms>"` attribute parsing as Long;
  (d) HTML contains the `function gpClickMetric(absMs)` JavaScript;
  (e) HTML contains `<dialog id="gp-log-window">` modal markup;
  (f) the FPS chart datapoints carry `onclick="gpClickMetric(...)" data-ts="..."` attributes.
  Run → red.
- [ ] **T3.2** (GREEN) — Add `logcatStream: List<LogLine> = emptyList()` defaulted named parameter to `ReportGenerator.generate(...)`.
- [ ] **T3.3** (GREEN) — Implement `private fun sectionLogcatStream(stream, captureStartMs)` per design §12:
  - Render `<section id="sec-logcat-stream">` only when `stream.isNotEmpty()`;
  - Group rows into two `<details>` blocks: error/warn (`open` by default), info/debug/verbose (collapsed);
  - Each row has `data-ts="${line.tsMs}"` and CSS class `.log-row-${levelClassName(level)}`;
  - Escape HTML in tag/message via existing `escapeHtml` helper.
- [ ] **T3.4** (GREEN) — Add the `gpClickMetric` JavaScript helper + `<dialog id="gp-log-window">` modal markup. Append to the existing report `<script>` and `<body>` blocks respectively. Modal closes via native `<dialog>` ESC handling.
- [ ] **T3.5** (GREEN) — Extend the FPS / CPU / FPower / Mem chart row-rendering loops to add `onclick="gpClickMetric(${absMs})" data-ts="${absMs}"` on each datapoint. Use `captureStartMs + tsRel` for absolute ms (where `tsRel` is the existing per-sample relative offset).
- [ ] **T3.6** (GREEN) — Add CSS rules for `.log-row-error / .log-row-warn / .log-row-info / .log-row-debug / .log-row-verbose` to the inline `<style>` block per design §12.
- [ ] **T3.7** (RED→GREEN) — Backward-compat test: run all existing report-rendering tests with the new parameter defaulted; verify zero output drift on legacy fixtures (positional output bytes identical to pre-change).
- [ ] **T3.8** — Manual smoke test: open a generated report in Chrome, click an FPS datapoint, verify the modal opens with logs in the ±5 s window. Click a point with no logs in range, verify the "No logs en esta ventana" placeholder appears.
- [ ] **T3.9** — Detekt local clean.

Done-when: `ReportGeneratorLogcatStreamTest` green; legacy fixtures unchanged; click-to-window verified manually.

---

## Sprint 4 — DEFERRED (out of v1 scope, ~1 day if pursued)

- [ ] **T4.1** — In-report grep/search box over the log table (client-side JS).
- [ ] **T4.2** — Per-game tag allowlist UI config in settings.
- [ ] **T4.3** — Configurable ±N window in the report (instead of hardcoded 5 s).
- [ ] **T4.4** — `LogcatStream` GZip persistence option for very large sessions.

Skip in v1. Re-evaluate after one release of v1 in production.

---

## Definition of done (v1, Sprints 0+1+2+3)

- [ ] All 25+ new test cases green; full `./gradlew test` green.
- [ ] Detekt baseline NOT expanded.
- [ ] Existing `SessionHistoryTest`, `ReportGeneratorTest`, `EventDetectorImplTest`, `LogcatCaptureTest` regression-suites all green.
- [ ] Manual smoke on at least one real Android device covering: live HUD tail visible; level chips work; report opens; click-to-window modal works.
- [ ] Legacy `.gameperf` files (v4.4.1, v4.5.0) load with `logcatStream = emptyList()`.
- [ ] No new external dependencies.
- [ ] No new sub-packages.
- [ ] No new top-level screen.
