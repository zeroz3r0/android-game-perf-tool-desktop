# Exploration — logcat-event-stream

Date: 2026-05-12
Topic key: `sdd/logcat-event-stream/explore`

## Goal

User-requested feature, verbatim:

> "En la timeline, molaría que hubiera los eventos del package registrándose a tiempo real y que se guarden para verlos. Si se produce un bajón de FPS muy bruto, podemos ver qué eventos se ejecutaron en ese momento para identificar qué lo está ocasionando. Fallos, advertencias o solo el evento. Que se pueda ver e identificar pero SOLO del package del juego, NO del teléfono."

Translation of requirement into engineering terms:
- Capture **all logcat output from the game's process** (PID-scoped, NOT phone-wide).
- Stream live during capture (timeline-aligned with FPS / CPU / thermal).
- Persist into the session so post-mortem analysis is possible.
- When a hard FPS drop is observed in the timeline, the user **must** be able to identify what log events (errors, warnings, info) the game emitted around that moment.
- Hard constraint: **GAME PACKAGE ONLY**. Phone/system logs are explicitly OUT.

This is a NEW kind of timeline-aligned artifact (raw log lines), complementary to — NOT replacing — the existing semantic event detection (`DetectedEvent`: IAP, INTERSTITIAL, etc.).

---

## Phase 1: Audit of existing logcat infrastructure (v4.4.0)

The tool ALREADY runs `adb logcat` for SDK event detection. Audit findings, file-by-file:

### `core/events/LogcatCapture.kt` (`f335444`)

- Single-process owner. Spawns ONE `adb logcat` process for its lifetime via `AdbBridgeApi.startLogcat(deviceId, tagArgs)`.
- Reader coroutine on `Dispatchers.IO`. Calls `LogcatLineParser.parse(line)` for every received line; on success, fires `onLine(LogLine)` callback to whoever owns the capture (today: `EventDetectorImpl`).
- **Lines that fail to parse (regex miss) are DROPPED silently** at `LogcatCapture.kt:97`.
- Lines that parse but don't match an SDK signature are **also dropped** at the consumer layer — `EventDetectorImpl.handleLogLine()` only forwards lines that `SdkSignatureCatalog.matchOpen()` / `matchClose()` recognize.
- `LogcatCapture` has gap-detection wired in (`GAP_THRESHOLD_MS = 5_000L`) — useful: any future stream consumer can hook into the SAME `onGap` callback to mark "data missing here" in the persisted stream.
- Idempotent `start()` / `stop()` lifecycle. Already production-tested across v4.4.0 / v4.4.1.

### `core/events/LogcatLineParser.kt`

- Pure regex-based parser, `threadtime` format only.
- Compiled-once top-level `THREADTIME_REGEX` per the project's "regex hot-path" rule.
- Produces `LogLine(tsMs, pid, tid, level, tag, msg)`.
- Year inferred from desktop wall-clock at parse time (no year in threadtime output).
- Returns `null` for malformed input (caller skips).

### `core/events/LogLine.kt`

- 6 fields, simple `data class`.
- **NOT `@Serializable` today.** Sprint 1 will add `@Serializable` + a default constructor for round-trip safety.

### `core/events/EventDetectorImpl.kt`

- Composes `LogcatCapture` + `DumpsysPoller`.
- The capture is started with `SdkSignatureCatalog.logcatTagArgs()` — see `SdkSignatureCatalog.kt:230` — which produces e.g. `["Ads:D","AdActivity:D","MobileAds:D","UnityAds:D", ..., "*:S"]`. The trailing `*:S` SILENCES everything except the listed SDK tags.
- Consequence: **the existing capture is TAG-filtered, NOT PID-filtered**. It already drops 99% of device logcat by tag whitelist, but a stray ad-SDK chatter from a different process running the same tag could leak in.

### `core/AdbBridge.startLogcat(deviceId, tagArgs)` (`AdbBridge.kt:925-943`)

The actual adb command spawned today:
```
adb -s <deviceId> logcat -b main,system,events -v threadtime <tagArgs>
```
where `<tagArgs>` = `["Ads:D", "AdActivity:D", ..., "*:S"]`.

**Critical finding for this change**:
- The command uses `-b main,system,events` (three buffers), NOT `--pid=<pid>`.
- That means the capture today is **NOT filtered by game PID at all**. Tag filtering is the only safeguard.
- For our new feature, we need EXACTLY the OPPOSITE filter — capture EVERY level/tag emitted by the game's PID, regardless of tag.

### Package → PID resolution (already in tool)

`AdbBridge.captureProcessCpuPercent` at `AdbBridge.kt:627-679` already resolves package → PID:
```kotlin
val pidOutput = shell(deviceId, "pidof $pkg", timeoutMs = 2000).trim()
val first = pidOutput.split(" ").firstOrNull()?.toIntOrNull() ?: return -1
cachedPidByPkg[pkg] = first
```
- Uses `pidof <pkg>` (busybox / toybox available on every Android ≥ 5).
- Caches per-package, invalidates on `/proc/<pid>/stat` empty (process died).
- **REUSABLE**. Sprint 0 expose this lookup so the new logcat stream can adopt the same mechanism.
- `pidof` can return MULTIPLE PIDs (Unity / Unreal multi-process). The current code takes `.firstOrNull()` — for our use-case we need ALL of them (e.g. `<pkg>:gpu`, `<pkg>:ipc`).

### `viewmodel/AppViewModel.kt` — capture orchestration

- `startCapture()` at line 914 onwards. Owns `eventDetector: EventDetector?` (line 336), which holds the existing `LogcatCapture`.
- `LiveMetrics` data class at line 83 holds the streaming HUD state.
- Tier-cadence design comment block at `:1085-1107` is the canonical place to plug new periodic emissions.
- We do NOT need a new tick — logcat is inherently stream-driven, not poll-driven.

### `core/SessionHistory.kt`

- `SerializableEntry` at line 150 — single `@Serializable` shape on disk.
- v4.4.1 schema version is `SCHEMA_VERSION = 5`.
- `Json { ignoreUnknownKeys = true }` at line 95 — forward-compat handled automatically. Adding a new optional field is a non-breaking change.
- Precedent: `events: List<DetectedEvent>` was added in v4.4.1 with default `emptyList()` (line 181), `fpowerHistory: List<Double>` was added in v4.5.0 (line 205). Same pattern works for `logcatStream`.

### `report/ReportGenerator.kt`

- Renders an HTML timeline + manual-markers section + auto-events section (`sectionEvents` at line 1208, `events-table` CSS class at line 1755).
- New section "Logcat Stream" plugs in alongside existing `sec-events` section.
- Adding a `data-ts="<ms>"` attribute to log rows + a JS handler for clickable timeline gives us the ±N second window query for free.

---

## Phase 2: Gap analysis

| Capability | Exists today? | If yes, how? | If no, needs |
|---|---|---|---|
| logcat capture process | YES | `LogcatCapture` + `AdbBridge.startLogcat` | Reuse |
| PID filtering | NO | tag-filter only (`Ads:D ... *:S`) | NEW: switch to `--pid=<pid>` OR run a second capture process scoped to PID |
| line buffer (raw, not events) | NO | parsed `LogLine` is discarded if no SDK match | NEW: ring buffer for raw `LogLine`s |
| persistence to `.gameperf` | NO | only `DetectedEvent` (semantic) persists | NEW: `logcatStream: List<LogLine>` field on `SerializableEntry`, defaulted empty |
| timeline rendering | YES (events + metrics) | `ReportGenerator.sectionEvents` + metric charts | EXTEND: new HTML section + click-handler for ±N window |
| time-window query (±Ns) | NO | report is static | NEW: client-side JS modal triggered by clicking a timeline point |
| HUD live browsing | NO | `LiveMetrics` does not carry log lines | NEW: live tail of last 20 lines in HUD; level-chip filter |
| log level filter (E/W/I/D/V) | partial | already known per-line in `LogLine.level` | NEW: HUD + report-side UI to toggle |

**Summary**: the foundations are present and battle-tested — `LogcatCapture` lifecycle, parser, gap detection, package→PID resolution all exist. What is missing is (a) **raw-line buffering** (today's pipeline DISCARDS non-matching lines), (b) **PID scoping** (today's filter is tag-based not process-based), (c) **persistence** of raw lines, (d) **HUD + report UI**.

The cheapest implementation is **dual-emit from `LogcatCapture`**: extend it to ALSO forward every parsed line to a new `LogcatStreamConsumer` while continuing to call the existing `onLine` callback for SDK detection. The pipeline stays event-driven, no new threads, no new adb processes if we widen the EXISTING capture's filter from "tag-filtered" to "PID-scoped". A second alternative — a SEPARATE adb logcat process for the raw stream — is rejected because it would double the adb process footprint and complicate gap detection.

---

## Phase 3: Key decisions baked in (no escalation)

| # | Question | Decision |
|---|---|---|
| Q1 | PID resolution | Reuse the existing `pidof $pkg` mechanism from `AdbBridge.captureProcessCpuPercent`. Extract into a new `AdbBridge.resolveGamePids(deviceId, pkg): List<Int>` returning ALL matching PIDs (Unity multi-process). |
| Q2 | Buffer model | In-memory ring buffer with sliding window. Default cap: **10 000 lines OR 5 MB**, whichever hits first. Eviction is FIFO oldest-first. Thread-safe via single producer (the `LogcatCapture` reader coroutine). |
| Q3 | Persistence format | New `logcatStream: List<LogLine>` field on `SessionHistory.SerializableEntry`. `LogLine` becomes `@Serializable` (additive: `data class` already has all-defaultable fields). Pre-v4.4.x history files load with empty list via `ignoreUnknownKeys`. |
| Q4 | Cadence | Event-driven. The reader coroutine appends to the buffer as lines arrive. No new tick / no new timer. Aligns with `LogcatCapture`'s existing design. |
| Q5 | UI placement | Two surfaces: (a) **HUD live tail** — last 20 lines visible during capture, auto-scroll, level-chip filter; (b) **report post-mortem section** — collapsible table, level filter, **clickable timeline integration** that opens a modal showing logs in `[clickedMs - 5s, clickedMs + 5s]`. No new top-level screen. |
| Q6 | Default level filter | HUD live tail defaults to **E/W/I on, D/V off** (the noise-vs-signal balance for a QA-facing tool). Report-side renders ALL captured levels but defaults the table to "E/W expanded, I/D/V collapsed-behind-toggle". |
| Q7 | Time-window query | Report HTML adds `data-ts="<ms>"` attribute to every timeline point + JS handler. Clicking a metric point opens a modal listing `LogLine` entries with `tsMs ∈ [click − 5000, click + 5000]`. ±5s is the default; advanced per-game configuration is deferred (Sprint 4 / out of scope for v1). |
| Q8 | Where capture lives | Inside the EXISTING `LogcatCapture` — extend it to **dual-emit** (events to detector + raw to new stream consumer). Switch the underlying `startLogcat` command from `-b main,system,events -v threadtime <tagArgs>` to `-v threadtime --pid=<pid>` (or chained `--pid` flags for multi-PID games). Tag filter goes AWAY — once PID-scoped, the noise is already gone. |
| Q9 | Diff vs events | `DetectedEvent` (semantic — IAP, INTERSTITIAL) and `LogLine` (physical — raw log entry) coexist. They are persisted in SEPARATE fields on `SerializableEntry` (`events: List<DetectedEvent>` already exists at line 181; new `logcatStream: List<LogLine>` is additive). The detector continues to operate on `LogLine` emissions; the new stream consumer is a parallel branch. |

---

## Scope reaffirmed

### IN
- PID-scoped (game only) raw logcat capture, all levels, all tags from the game process.
- Sliding-window in-memory ring buffer (10K lines / 5 MB cap).
- Persisted into `.gameperf` JSON.
- HUD live tail with level filter chips.
- Report HTML post-mortem section with collapsible table.
- Clickable timeline → modal with ±5s log window.
- Backward compat with v4.4.x / v4.5.0 `.gameperf` files (defaults to empty stream).

### OUT (firm)
- NO grep / full-text search inside the report (Sprint 2+ deferred).
- NO cross-session log correlation.
- NO log streaming to remote / cloud / Slack / anywhere external (anti local-first).
- NO multi-PID capture beyond the game's own process tree (the user said NO phone logs — system processes OUT).
- NO new top-level screen.
- NO configurable ±N window in the UI yet (default 5s baked in).

---

## Risks identified upfront

1. **Logcat stream backpressure**: `LogcatCapture` is a long-running adb pipe. Adding a second consumer (dual-emit) must NOT cause the existing SDK detection to drop lines. Mitigation: the ring buffer's `add()` is O(1) and non-blocking; the consumer side fans out via a `MutableSharedFlow` with `BUFFER_OVERFLOW.DROP_OLDEST`.
2. **Memory growth**: 10K lines × ~200 bytes ≈ 2 MB hot path. Sliding window prevents OOM. Persisted JSON for a 1-hour session at ~10 lines/sec ≈ 36K lines — bounded by the same cap.
3. **`.gameperf` serialization size**: `LogLine` has 5 fields; compact JSON encoding ~100 bytes/line. 10K lines × 100 B ≈ 1 MB additional per session. Acceptable; can be reduced by GZip if it ever becomes a problem (out of scope here).
4. **PID changes mid-session**: game can restart (crash + auto-relaunch, or user manually). Mitigation: monitor `pidof` periodically; on change, append a synthetic marker line `LogLine(level='I', tag='gameperf', msg="[PID CHANGED <old>→<new>]")` and reconfigure the underlying adb logcat. Sprint 0 specs this.
5. **Multi-process games (Unity, Unreal)**: `pidof <pkg>` can return multiple PIDs (`<pkg>`, `<pkg>:gpu`, `<pkg>:ipc`). Capture ALL of them. `adb logcat` supports chained `--pid` flags as of Android 7+; for older devices fall back to multiple capture processes.
6. **`adb logcat --pid` availability**: `--pid` was added in Android 7.0 (Nougat, API 24). The tool's minimum supported device is Android 5.0+. For pre-N devices, fall back to PID-grep post-parse — already cheap because `LogcatLineParser` extracts `pid` into `LogLine`.

---

## Effort

- Sprint 0 — foundation (buffer, dual-emit, PID resolver): **~1 day**
- Sprint 1 — persistence (`SerializableEntry` + round-trip): **~0.5 day**
- Sprint 2 — HUD live tail (last 20 lines, level chips, auto-scroll): **~1 day**
- Sprint 3 — Report HTML (collapsible table, ±5s click modal): **~1.5 day**
- Sprint 4 — DEFERRED (in-report search, per-game tag allowlist UI): ~1 day, out of scope for v1

**Total v1 effort: ~4 days TDD red→green.** Sprints 0+1+2+3 land in a single release.

---

## Constraints

- ZERO breaking changes to existing event detection (`EventDetectorImpl` keeps working identically).
- Backward compat with v4.4.1 / v4.5.0 `.gameperf` JSON (new field defaults to empty list).
- ZERO new external dependencies.
- Detekt clean (no baseline expansion).
- TDD red → green strict per sprint. `./gradlew test` green at every sprint boundary.
