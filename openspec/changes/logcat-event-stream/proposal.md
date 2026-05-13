# Proposal — logcat-event-stream

Topic key: `sdd/logcat-event-stream/proposal`
Depends on: exploration `sdd/logcat-event-stream/explore`

## Intent

Add a **PID-scoped raw logcat event stream** to the desktop tool, persisted alongside metrics, surfaced both LIVE (HUD tail) and POST-MORTEM (report HTML with click-to-window timeline integration). Lets a QA engineer answer "what was the game emitting RIGHT when FPS crashed?" without reaching for a separate `adb logcat` terminal.

The user observed (verbatim, in Spanish): *if FPS drops sharply in the timeline, we should be able to see what events the game emitted at that moment to identify the cause — errors, warnings, or just normal events; visible and identifiable but ONLY from the game package, NOT from the phone*.

This is **complementary to** — not replacing — the existing v4.4.0 semantic auto-event detection (`DetectedEvent`: IAP, INTERSTITIAL, AD_CLOSE, etc.). The semantic detector operates on a TAG-whitelist of SDK signatures. The new stream captures the ENTIRE game-process logcat output, raw. Both coexist on disk in the same `.gameperf` file under different fields.

## Scope

### IN

- **PID-scoped capture**. Switch the underlying `adb logcat` command from `-b main,system,events <tagArgs>` to `--pid=<pid>` (or chained `--pid` for multi-process games). Resolves package → PID(s) via the existing `pidof $pkg` mechanism already used in `AdbBridge.captureProcessCpuPercent`.
- **Sliding-window in-memory ring buffer** (`LogcatStreamBuffer`). Default cap: 10 000 lines OR 5 MB, whichever first. FIFO eviction. Thread-safe.
- **Dual-emit from `LogcatCapture`**. Existing `onLine(LogLine)` callback to `EventDetectorImpl` is preserved bit-for-bit. New parallel emission to `LogcatStreamConsumer`.
- **Persistence**. New `logcatStream: List<LogLine>` field on `SessionHistory.SerializableEntry`, defaulted `emptyList()`. `LogLine` itself widened with `@Serializable` annotation and per-field defaults.
- **HUD live tail**. `LiveMetrics` gains `logcatTail: List<LogLine>` (last 20 lines). Level-chip filter (E/W/I default on, D/V off).
- **Report HTML post-mortem section**. New `sec-logcat-stream` card next to the existing `sec-events` card. Collapsible table, default expanded for E/W only.
- **Clickable timeline integration**. Every metric chart datapoint gets `data-ts="<absMs>"`. JS handler opens a modal listing `LogLine` rows where `tsMs ∈ [clickedMs - 5000, clickedMs + 5000]`. ±5s baked in for v1.
- **Multi-PID support**. `pidof <pkg>` returns all matching PIDs; capture them all (Unity/Unreal can spawn `<pkg>:gpu`, `<pkg>:ipc`).
- **PID-change marker**. If `pidof` returns a different PID mid-session (game restart), insert a synthetic `LogLine(level='I', tag='gameperf', msg='[PID CHANGED <old>→<new>]')` into the stream and rewire the capture process.
- **Backward compat**. v4.4.1 / v4.5.0 `.gameperf` files load with `logcatStream` defaulted to empty list via `Json { ignoreUnknownKeys = true }`. Pre-existing reports render identically (no logcat section if list empty).
- **Unit tests across all 5 new test files**; detekt clean.

### OUT (firm)

- **No grep / full-text search inside the report**. v1 ships level + timestamp window only; in-report search is a Sprint 4 follow-up.
- **No cross-session log correlation**. Each session's logcat stream is independent.
- **No log streaming to cloud / Slack / remote**. Local-first stance; anti-`adb logcat | curl` pattern.
- **No phone-wide / system logs**. PID-scoped only. Per the user's explicit requirement.
- **No new top-level screen**. HUD live tail lives inside the existing capture screen; report section lives inside the existing HTML report.
- **No configurable ±N window in v1 UI**. The default 5 s is baked into the JS handler. Per-game config is deferred.
- **No iOS support in v1**. The sidecar's `os_log` access is a separate workstream — `LogcatStream` here is Android-only. `SessionResult.logcatStream` is empty on iOS captures.
- **No per-tag allowlist UI**. Sprint 4 deferred.

## Approach

Strict mirror of the **v4.4.0 `DetectedEvent` + v4.4.1 thermal-availability + v4.5.0 FPower** pattern: pure parser + thin stateful bridge + flat `core/` layout + every-field-defaulted `@Serializable`. Every layer has a live precedent in the repo:

1. **Reuse `LogcatCapture`, `LogcatLineParser`, `LogLine`**. The existing 3-class capture stack is correct; we add a NEW consumer (`LogcatStreamConsumer`) and a NEW buffer (`LogcatStreamBuffer`). No changes to the parser, no changes to the threadtime regex.
2. **Switch the `adb logcat` command**. `AdbBridge.startLogcat` widens its signature to accept a `pids: List<Int>` (defaulted empty for backward compat with the existing detector callers). When `pids` is non-empty, append `--pid=<n>` flags instead of tag args + `-b main,system,events`. Tag args become OPTIONAL; the detector keeps emitting them today (zero behavioural change for v1's PID path uses ALL levels which is a superset).
3. **Dual-emit**. `LogcatCapture` constructor gains an optional `onRawLine: ((LogLine) -> Unit)? = null` parameter. After parsing, before invoking the existing `onLine`, also invoke `onRawLine?.invoke(line)`. The detector's behaviour is untouched.
4. **`LogcatStreamBuffer`** is a thin ring buffer (Kotlin `ArrayDeque` + size cap + byte-cap). Single-writer (the capture reader coroutine). Reads happen on snapshot (e.g. when `stopCapture` flushes to `SessionResult`).
5. **`LogcatStreamConsumer`** wires the buffer into `AppViewModel`. Exposes a `MutableStateFlow<List<LogLine>>` for the HUD tail (windowed to last 20) and a snapshot `fun snapshot(): List<LogLine>` for the post-stop persistence path.
6. **Multi-PID**. `AdbBridge.resolveGamePids(deviceId, pkg): List<Int>` — new public function, factor out the existing `pidof` logic from `captureProcessCpuPercent`. Returns ALL matching PIDs (defaultable to first via `.first()` for the legacy CPU-% path).
7. **PID change detection**. The buffer-fill coroutine watches `pidof` once per second (cheap shell). On change, emit a synthetic marker `LogLine` and restart the underlying `LogcatCapture` with the new PID set. Sprint 0 specs this.
8. **HUD wiring**. `AppViewModel.startCapture` instantiates `LogcatStreamConsumer` next to `EventDetectorImpl`. The HUD `LiveMetrics` mirrors `consumer.tail` into `LiveMetrics.logcatTail`.
9. **Report rendering**. New `private fun sectionLogcatStream(stream: List<LogLine>, ...)` in `ReportGenerator`. CSS classes `.log-row-error / .log-row-warn / .log-row-info / .log-row-debug / .log-row-verbose`. JS handler `gpClickMetric(absMs)` opens a `<dialog>` with filtered rows.
10. **TDD strict, red → green per sprint, detekt clean**.

## Effort

**~4 days** (Sprints 0+1+2+3). HIGH ROI for the QA workflow (correlating FPS dips with log emissions is the #1 manual back-and-forth today). STANDALONE — no dependency on any in-flight change.

## Outcome

- A QA engineer hits a hard FPS dip at `t = 2:34` in the report. They click that point. A modal opens with the 12 log lines the game emitted between `2:29` and `2:39`. They see `ERROR System.OutOfMemoryError` at `2:33.421`. Root cause identified WITHOUT a second `adb logcat` terminal.
- Live during capture, the HUD shows the most recent 20 log lines of the game with E/W highlighted in red/amber. The QA engineer sees a stream of warnings during a known-bad loading scene without leaving the tool.
- The `.gameperf` file gains a new field, defaulted empty for forward compat. v4.4.1 / v4.5.0 files load unchanged; v1.6-and-up files carry the stream.
- Foundation laid for Sprint 4 (in-report search, per-game tag allowlist) without re-doing the capture layer.

## Non-goals reaffirmed

This change does NOT replace the existing `DetectedEvent` pipeline; does NOT introduce a new top-level screen; does NOT add new sub-packages under `core/events/`; does NOT touch iOS; does NOT add adb processes (it widens the existing one). It is an additive thin slice on top of the already-shipped logcat capture stack.
