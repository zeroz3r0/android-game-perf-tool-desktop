# Spec — logcat-stream capability (logcat-event-stream change)

Topic key: `sdd/logcat-event-stream/spec`
Change: `logcat-event-stream`
Capability: `logcat-stream`
Scope: Android. iOS deferred.

Stable requirement IDs LCS-001..LCS-014. EARS keyword style + GIVEN/WHEN/THEN scenarios.

---

## LCS-001 — PID-scoped logcat capture

The system **shall** spawn a single `adb logcat` process per capture session that is scoped to the game package's PID(s) only, NEVER to phone-wide / system logs.

### Scenarios

GIVEN a capture session has started for game package `com.example.game`
AND `pidof com.example.game` returns `12345`
WHEN `LogcatCapture.start(...)` is invoked
THEN the underlying adb command **shall** be `adb -s <deviceId> logcat -v threadtime --pid=12345`
AND it **shall not** include `-b main,system,events`
AND it **shall not** include tag-filter arguments (`Ads:D`, `*:S`, etc.).

GIVEN `pidof com.example.game` returns multiple PIDs `12345 12346 12347` (Unity multi-process)
WHEN `LogcatCapture.start(...)` is invoked
THEN the underlying adb command **shall** include `--pid=12345 --pid=12346 --pid=12347` (chained).

GIVEN the device runs Android < 7.0 (no `--pid` support)
WHEN `LogcatCapture.start(...)` is invoked
THEN the bridge **shall** fall back to unfiltered `adb logcat -v threadtime` AND filter by `LogLine.pid ∈ resolvedPids` in the reader coroutine
AND lines from non-matching PIDs **shall** be dropped before reaching the consumer.

---

## LCS-002 — Multi-PID resolution

The system **shall** expose `AdbBridge.resolveGamePids(deviceId, pkg): List<Int>` which returns **all** PIDs matching the game package, not just the first.

### Scenarios

GIVEN `adb -s <deviceId> shell pidof com.example.game` outputs `12345 12346 12347`
WHEN `resolveGamePids(deviceId, "com.example.game")` is invoked
THEN it **shall** return `[12345, 12346, 12347]` in input order.

GIVEN `pidof` outputs an empty string (game not running)
WHEN `resolveGamePids(...)` is invoked
THEN it **shall** return an empty list AND **shall not** throw.

GIVEN `pidof` returns garbage (e.g. `"error: <foo>"`)
WHEN `resolveGamePids(...)` is invoked
THEN the bridge **shall** parse defensively and return only the integer-parseable tokens; non-integer tokens **shall** be discarded.

---

## LCS-003 — Dual-emit from `LogcatCapture`

The `LogcatCapture` **shall** invoke both an `onLine: (LogLine) -> Unit` callback (existing detector path) AND an optional `onRawLine: ((LogLine) -> Unit)?` callback (new stream path) for every successfully-parsed line. The new callback **shall** default to `null` to preserve constructor backward compatibility for callers that only want the existing behaviour.

### Scenarios

GIVEN a `LogcatCapture` constructed with both `onLine` and `onRawLine` non-null
WHEN a parseable threadtime line arrives
THEN `onRawLine` **shall** be invoked FIRST
AND THEN `onLine` **shall** be invoked
AND both **shall** receive the SAME `LogLine` instance (or value-equal instances) for the same input line.

GIVEN a `LogcatCapture` constructed with `onRawLine = null` (existing detector path)
WHEN a parseable line arrives
THEN only `onLine` **shall** be invoked AND there **shall** be no observable behaviour change from v4.4.x.

GIVEN a line that fails `LogcatLineParser.parse` (returns null)
WHEN it arrives
THEN NEITHER callback **shall** be invoked
AND the line **shall** be dropped silently as in v4.4.x.

---

## LCS-004 — Ring-buffer sliding window

The system **shall** maintain a `LogcatStreamBuffer` that holds at most `MAX_LINES = 10_000` lines OR `MAX_BYTES = 5 * 1024 * 1024` (5 MiB) of accumulated serialized payload, whichever bound is reached first. Oldest entries **shall** be evicted FIFO when either bound is exceeded.

### Scenarios

GIVEN a buffer with `MAX_LINES = 10_000` and 9 999 entries
WHEN one new `LogLine` is appended
THEN the buffer size **shall** equal 10 000 AND no eviction **shall** occur.

GIVEN a buffer at exactly `MAX_LINES = 10_000`
WHEN one new `LogLine` is appended
THEN the OLDEST entry **shall** be removed
AND the buffer size **shall** equal 10 000
AND the new entry **shall** occupy the most-recent position.

GIVEN a buffer at 8 000 lines but total serialized byte size = 5 242 880 (= 5 MiB)
WHEN one new line of 500 bytes is appended
THEN at least one oldest entry **shall** be evicted until total bytes ≤ 5 MiB.

GIVEN concurrent producer (capture coroutine) and consumer (snapshot reader)
WHEN `append()` and `snapshot()` are invoked from different threads
THEN no `ConcurrentModificationException` **shall** be thrown
AND `snapshot()` **shall** return a consistent point-in-time copy.

---

## LCS-005 — `LogLine` serialization

The `LogLine` data class **shall** be annotated `@Serializable` with all fields defaulted (`tsMs = 0`, `pid = 0`, `tid = 0`, `level = 'I'`, `tag = ""`, `msg = ""`) so that pre-change `.gameperf` history files that lack a `logcatStream` field deserialize without error.

### Scenarios

GIVEN a populated `LogLine(tsMs=1234567890123L, pid=12345, tid=12346, level='E', tag='MyTag', msg='boom')`
WHEN encoded via `Json.encodeToString` and decoded back
THEN the round-tripped instance **shall** be value-equal to the original.

GIVEN a `LogLine` instance with only `tsMs` set (other fields defaulted)
WHEN serialized
THEN the resulting JSON **shall** include all 6 fields (per `encodeDefaults = true` in the project `Json` config).

---

## LCS-006 — Persistence of the stream

`SessionHistory.SerializableEntry` **shall** carry a new `logcatStream: List<LogLine> = emptyList()` field. `HistoryEntry` **shall** carry the same field. Round-trip via `SessionHistory.save` + `load` **shall** preserve the stream exactly.

### Scenarios

GIVEN a `HistoryEntry` with `logcatStream = [LogLine(tsMs=100,level='E',tag='A',msg='x'), LogLine(tsMs=200,level='W',tag='B',msg='y')]`
WHEN `SessionHistory.save(...)` then `SessionHistory.load()` are called sequentially
THEN the loaded entry **shall** have `logcatStream.size == 2`
AND `logcatStream[0]` **shall** be value-equal to the original first line
AND `logcatStream[1]` **shall** be value-equal to the original second line.

GIVEN a `history.json` file written by v4.5.0 (no `logcatStream` field)
WHEN `SessionHistory.load()` is called
THEN entries **shall** deserialize with `logcatStream = emptyList()` (default)
AND no exception **shall** be thrown.

---

## LCS-007 — PID-change synthetic marker

When the capture detects that `pidof <pkg>` returns a different PID set than the one the running `LogcatCapture` was started with, the system **shall** append a synthetic `LogLine(level='I', tag='gameperf', msg='[PID CHANGED <oldPids> → <newPids>]')` to the buffer AND restart the underlying capture process with the new PID set.

### Scenarios

GIVEN a session capturing PID `12345`
WHEN at `t = 60_000` the watchdog observes `pidof` now returns `99999` (game crashed and relaunched)
THEN a synthetic `LogLine` **shall** be appended with `tsMs = 60_000` and `msg = "[PID CHANGED 12345 → 99999]"`
AND the existing `LogcatCapture` **shall** be stopped
AND a new `LogcatCapture` **shall** be started against PID `99999`
AND subsequent lines from the new PID **shall** flow into the same buffer.

GIVEN `pidof` returns the same PID set across consecutive checks
WHEN the watchdog tick runs
THEN no synthetic marker **shall** be emitted AND no capture restart **shall** occur.

---

## LCS-008 — HUD live tail

`LiveMetrics` **shall** carry `logcatTail: List<LogLine> = emptyList()` representing the last `HUD_TAIL_SIZE = 20` lines that pass the active level filter (default `setOf('E','W','I')`).

### Scenarios

GIVEN the buffer has accumulated 100 lines, with 25 at level `'E'`, 25 at `'W'`, 25 at `'I'`, 25 at `'D'`
AND the HUD level filter is the default `setOf('E','W','I')`
WHEN `LiveMetrics.logcatTail` is sampled
THEN it **shall** contain 20 lines, all of which **shall** have `level ∈ setOf('E','W','I')`
AND it **shall** contain the 20 most recent such lines in chronological order.

GIVEN the HUD level filter is changed to `setOf('E')`
WHEN `LiveMetrics.logcatTail` is sampled
THEN it **shall** contain at most 20 lines, all with `level == 'E'`.

---

## LCS-009 — Report post-mortem section

`ReportGenerator.generate(...)` **shall** accept a new defaulted `logcatStream: List<LogLine> = emptyList()` parameter AND **shall** render a `<section id="sec-logcat-stream">` only when `logcatStream.isNotEmpty()`. The section **shall** include:
- A collapsible `<details>` wrapper, expanded by default for E/W rows, collapsed by default for I/D/V rows.
- A `<table>` of log rows with columns `[time, level, tag, message]`.
- CSS classes `.log-row-error`, `.log-row-warn`, `.log-row-info`, `.log-row-debug`, `.log-row-verbose` applied per row.
- A `data-ts="<absMs>"` attribute on each row for the click-to-window JS handler.

### Scenarios

GIVEN `logcatStream = []`
WHEN the report is generated
THEN the output HTML **shall not** contain `id="sec-logcat-stream"`
AND no log-stream CSS classes **shall** appear in the output.

GIVEN `logcatStream` contains 1 ERROR line and 5 INFO lines
WHEN the report is generated
THEN the output **shall** contain exactly one `<tr class="log-row-error">`
AND it **shall** contain five `<tr class="log-row-info">`
AND the section **shall** have a `<details open>` containing the error
AND the `<details>` for info rows **shall not** carry the `open` attribute by default.

GIVEN `logcatStream` is non-empty
WHEN the report is generated
THEN every log row **shall** carry a `data-ts="<absMs>"` attribute whose value parses as a `Long`.

---

## LCS-010 — Click-to-window timeline integration

The report HTML **shall** include a JavaScript function `gpClickMetric(absMs)` that, when invoked, displays a modal `<dialog>` listing every `LogLine` row whose `tsMs ∈ [absMs - 5_000, absMs + 5_000]`. Every metric chart datapoint in the report **shall** carry a `data-ts="<absMs>"` attribute and an `onclick="gpClickMetric(<absMs>)"` handler.

### Scenarios

GIVEN a report with 3 log lines at `tsMs ∈ {10_000, 15_000, 25_000}` (relative to capture start = 0)
AND a click on a metric point at `absMs = 14_000`
WHEN `gpClickMetric(14_000)` is invoked
THEN the modal **shall** display lines at `tsMs = 10_000` and `tsMs = 15_000`
AND it **shall not** display the line at `tsMs = 25_000`.

GIVEN no log lines fall in the ±5s window
WHEN `gpClickMetric(...)` is invoked
THEN the modal **shall** display a "No logs in window" placeholder message
AND it **shall** remain dismissible.

---

## LCS-011 — Backward compatibility

A pre-change `.gameperf` history file (v4.4.1, v4.5.0, etc., which has no `logcatStream` field) **shall** load without exception AND the resulting `HistoryEntry.logcatStream` **shall** equal `emptyList()`.

### Scenarios

GIVEN a checked-in fixture `test/resources/history-v4-5-0.json` produced by v4.5.0
WHEN `SessionHistory.load()` runs against that fixture
THEN every entry **shall** decode successfully
AND `entry.logcatStream` **shall** equal `emptyList()` for every entry
AND `entry.events` (v4.4.1 field) **shall** decode unchanged.

---

## LCS-012 — Detector path unchanged

The existing `EventDetectorImpl` SDK detection path **shall** behave bit-for-bit identically before and after this change for any input log stream.

### Scenarios

GIVEN a synthetic input sequence of 50 `LogLine`s that pre-change produces N `DetectedEvent`s and M `warnings`
WHEN the same sequence is fed post-change (with a stream consumer also wired)
THEN the produced `DetectedEvent`s **shall** be value-equal to the pre-change baseline
AND the produced `warnings` **shall** be value-equal to the pre-change baseline.

GIVEN the dual-emit `onRawLine` callback throws an exception
WHEN a line arrives
THEN the exception **shall not** propagate into the `onLine` (detector) path
AND the detector **shall** continue to process subsequent lines.

---

## LCS-013 — Level filter defaults

The HUD level filter **shall** default to `setOf('E', 'W', 'I')`. The report-side default render **shall** expand `<details>` for sections containing E or W rows and **shall** collapse the I/D/V sections behind a toggle.

### Scenarios

GIVEN a fresh `AppViewModel.startCapture` invocation
WHEN `LiveMetrics.logcatTail` is first observed
THEN the underlying filter **shall** be `setOf('E','W','I')` AND `'D'` / `'V'` lines **shall** be excluded.

GIVEN a report rendered with `logcatStream` containing only `'D'` and `'V'` lines (no E/W/I)
WHEN the report is opened
THEN the log section's top `<details>` **shall not** be `open`
AND a "Mostrar todos los niveles" toggle **shall** be present.

---

## LCS-014 — Bounded memory and disk footprint

The system **shall** ensure total stream memory and persisted size are bounded by the constants in LCS-004 (10K lines / 5 MiB) at all times during and after capture. Persisted `.gameperf` files **shall not** exceed an additional ~1 MiB attributable to `logcatStream` for a session at the cap.

### Scenarios

GIVEN a capture session that produces 50 000 log lines over its duration
WHEN the session ends
THEN the persisted `SerializableEntry.logcatStream` **shall** contain at most 10 000 lines
AND those 10 000 **shall** be the 10 000 most recent (FIFO eviction).

GIVEN a session where lines are unusually large (e.g. multi-KB stack traces)
WHEN total accumulated serialized bytes would exceed 5 MiB
THEN older entries **shall** be evicted until the byte cap is respected
AND the persisted file size growth attributable to `logcatStream` **shall** stay below ~1 MiB after JSON encoding.
