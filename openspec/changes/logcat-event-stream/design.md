# Design — logcat-event-stream

Topic key: `sdd/logcat-event-stream/design`
Depends on: proposal `sdd/logcat-event-stream/proposal`, spec `sdd/logcat-event-stream/spec`

The v4.4.0 `auto-event-detection-and-clean-metrics` change is the closest precedent — same package (`core/events/`), same `LogcatCapture` lifecycle, same persistence pattern. This design extends that infrastructure rather than duplicating it.

---

## Component map

### 1. `core/events/LogLine.kt` (EXISTING → annotate `@Serializable`)

Today: plain `data class LogLine(tsMs, pid, tid, level, tag, msg)`, all positional, no defaults.

After this change:
```kotlin
@Serializable
data class LogLine(
    val tsMs: Long = 0L,
    val pid: Int = 0,
    val tid: Int = 0,
    val level: Char = 'I',
    val tag: String = "",
    val msg: String = "",
)
```

`Char` is `@Serializable` natively via `kotlinx.serialization.builtins.CharSerializer` (no custom serializer needed). All fields default → forward-compat with future widenings is automatic via `ignoreUnknownKeys = true`.

### 2. `core/events/LogcatStreamBuffer.kt` (NEW, `internal class`)

Thin ring buffer. Single producer (capture reader coroutine), multiple readers via snapshot.

```kotlin
internal class LogcatStreamBuffer(
    private val maxLines: Int = 10_000,
    private val maxBytes: Long = 5L * 1024 * 1024,
) {
    private val deque = ArrayDeque<LogLine>(maxLines)
    @Volatile private var currentBytes: Long = 0L
    private val lock = Any()

    fun append(line: LogLine) { ... }   // O(1) amortised, evicts if over cap
    fun snapshot(): List<LogLine> { ... } // synchronized copy
    fun clear() { ... }
    fun size(): Int { ... }
}
```

Byte-size estimation: per-line cost ≈ `40 (header) + tag.length + msg.length` bytes (rough mapping to compact JSON encoding). Recomputed on append and decremented on eviction. NOT exact JSON size — close-enough heuristic that prevents pathological cases (multi-MB stack-trace lines) from blowing the bound while keeping the math cheap.

### 3. `core/events/LogcatStreamConsumer.kt` (NEW, `internal class`)

Wires the buffer into the rest of the system. Owns the `MutableStateFlow` for HUD updates.

```kotlin
internal class LogcatStreamConsumer(
    private val buffer: LogcatStreamBuffer = LogcatStreamBuffer(),
    private val hudTailSize: Int = HUD_TAIL_SIZE,
    private val levelFilter: StateFlow<Set<Char>>,
) {
    private val _tail = MutableStateFlow<List<LogLine>>(emptyList())
    val tail: StateFlow<List<LogLine>> = _tail

    fun onRawLine(line: LogLine) {
        buffer.append(line)
        if (line.level in levelFilter.value) {
            _tail.value = (_tail.value + line).takeLast(hudTailSize)
        }
    }
    fun snapshot(): List<LogLine> = buffer.snapshot()
    fun clear() { buffer.clear(); _tail.value = emptyList() }

    companion object { const val HUD_TAIL_SIZE = 20 }
}
```

### 4. `core/events/PidWatchdog.kt` (NEW, `internal class`)

Polls `pidof <pkg>` once per second on `Dispatchers.IO`. On change, fires `onPidChange(oldPids, newPids)`.

```kotlin
internal class PidWatchdog(
    private val bridge: AdbBridgeApi,
    private val deviceId: String,
    private val pkg: String,
    private val onPidChange: (old: List<Int>, new: List<Int>) -> Unit,
    private val intervalMs: Long = 1_000L,
) {
    fun start(scope: CoroutineScope): Job { ... }
    fun stop() { ... }
}
```

### 5. `core/events/LogcatCapture.kt` (EXTEND)

Constructor gains optional `onRawLine: ((LogLine) -> Unit)? = null`. Reader coroutine becomes:

```kotlin
while (isActive) {
    val line = reader.readLine() ?: break
    val now = System.currentTimeMillis()
    val gap = now - lastReceiveMs
    if (gap > GAP_THRESHOLD_MS) onGap(gap)
    lastReceiveMs = now
    val parsed = LogcatLineParser.parse(line) ?: continue
    try { onRawLine?.invoke(parsed) } catch (_: Exception) { /* never propagate */ }
    onLine(parsed)
}
```

The `try/catch` around `onRawLine` is the LCS-012 isolation requirement: even if the stream consumer throws, the detector path stays bit-for-bit identical to v4.4.x.

### 6. `core/AdbBridge.startLogcat` (EXTEND signature, preserve default)

```kotlin
// Old:
fun startLogcat(deviceId: String, tagArgs: List<String>): Process?

// New:
fun startLogcat(
    deviceId: String,
    tagArgs: List<String> = emptyList(),
    pids: List<Int> = emptyList(),
): Process?
```

Command construction:
- If `pids.isNotEmpty()` → `adb -s <deviceId> logcat -v threadtime` then for each pid append `--pid <pid>` (no `-b` flags, no tag args).
- If `pids.isEmpty() && tagArgs.isNotEmpty()` → preserve current behaviour: `adb -s <deviceId> logcat -b main,system,events -v threadtime <tagArgs>` (v4.4.x detector callers see zero change).
- If both empty → `adb -s <deviceId> logcat -v threadtime` (unfiltered fallback for very old devices).

The interface `AdbBridgeApi` adds a new default-parameter overload; `RealAdbBridge` passes through; `FakeAdbBridge` honours both.

### 7. `core/AdbBridge.resolveGamePids` (NEW)

Extract the existing `pidof` logic from `captureProcessCpuPercent` into a public helper:

```kotlin
fun resolveGamePids(deviceId: String, pkg: String): List<Int> {
    if (!isValidPackageName(pkg)) return emptyList()
    val out = shell(deviceId, "pidof $pkg", timeoutMs = 2000).trim()
    if (out.isEmpty()) return emptyList()
    return out.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
}
```

`captureProcessCpuPercent` reuses this — its existing `cachedPidByPkg[pkg]` becomes `resolveGamePids(...).firstOrNull()`. Zero behaviour change for the CPU path.

### 8. `core/events/EventDetectorImpl.kt` (NEW dual-emit wiring)

Today's `start(...)` constructs the `LogcatCapture` with only `onLine = ::handleLogLine` and `onGap = ::handleGap`. The change adds optional `onRawLine`:

```kotlin
override fun start(
    deviceId: String,
    gamePackage: String,
    scope: CoroutineScope,
    onRawLine: ((LogLine) -> Unit)? = null,
) {
    // ... existing setup ...
    val capture = LogcatCapture(
        bridge = bridge,
        onLine = ::handleLogLine,
        onGap = ::handleGap,
        onRawLine = onRawLine,
    )
    // ... existing setup ...
}
```

`EventDetector` interface gains the `onRawLine` default. Pre-existing callers that don't pass it are unaffected (LCS-012).

### 9. `viewmodel/AppViewModel.kt` integration

Add fields:
```kotlin
private val _logcatLevelFilter = MutableStateFlow<Set<Char>>(setOf('E','W','I'))
val logcatLevelFilter: StateFlow<Set<Char>> = _logcatLevelFilter
private var streamConsumer: LogcatStreamConsumer? = null
```

In `startCapture(...)`, after `val detector = EventDetectorImpl(bridge = adb)`:
```kotlin
val pids = adb.resolveGamePids(device.id, pkg)
val consumer = LogcatStreamConsumer(levelFilter = _logcatLevelFilter)
streamConsumer = consumer
detector.start(deviceId = device.id, gamePackage = pkg, scope = scope, onRawLine = consumer::onRawLine)
// New: also push pids hint to LogcatCapture via detector setup (see §10 LogcatCapture.start signature widening).
// Wire HUD:
scope.launch { consumer.tail.collect { _liveMetrics.value = _liveMetrics.value.copy(logcatTail = it) } }
// PID watchdog (LCS-007):
val watchdog = PidWatchdog(bridge = adb, deviceId = device.id, pkg = pkg, onPidChange = { old, new ->
    consumer.onRawLine(LogLine(
        tsMs = System.currentTimeMillis(),
        pid = 0, tid = 0, level = 'I', tag = "gameperf",
        msg = "[PID CHANGED ${old.joinToString(",")} → ${new.joinToString(",")}]",
    ))
    // Stop + restart the underlying LogcatCapture with new PIDs.
    // Implementation detail handled inside EventDetectorImpl via a new restartLogcat(pids) helper.
})
watchdog.start(scope)
```

`LiveMetrics` gains:
```kotlin
val logcatTail: List<LogLine> = emptyList(),
```

`SessionResult` gains:
```kotlin
val logcatStream: List<LogLine> = emptyList(),
```

Post-stop, in the `_result.value = SessionResult(...)` builder, populate `logcatStream = streamConsumer?.snapshot() ?: emptyList()`.

### 10. `LogcatCapture.start(...)` widening

```kotlin
fun start(
    deviceId: String,
    tagArgs: List<String> = emptyList(),
    pids: List<Int> = emptyList(),
    scope: CoroutineScope,
): Boolean {
    if (_running.value) return false
    val proc = bridge.startLogcat(deviceId, tagArgs, pids) ?: return false
    // ... rest unchanged ...
}
```

The detector calls it with `tagArgs = SdkSignatureCatalog.logcatTagArgs()` AND `pids = resolvedPids` (both non-empty). When both are present, `AdbBridge.startLogcat` prefers PID mode (LCS-001) — PID filtering supersedes tag filtering because PID-scoped capture is a STRICT SUPERSET of what the SDK-tag-only capture would surface (the game process's logs include the SDK tag emissions). The detector's existing `matchOpen` / `matchClose` matching is tag-aware so it filters internally — no observable change.

### 11. `core/SessionHistory.kt` schema

`SerializableEntry` gains:
```kotlin
val logcatStream: List<LogLine> = emptyList(),
```

`HistoryEntry` gains the matching field. The conversion functions extend trivially (no nested data restructuring needed — `LogLine` is already a clean `@Serializable` after Sprint 0). `SCHEMA_VERSION` bumps from `5` → `6`.

### 12. `report/ReportGenerator.kt` rendering

New parameter (defaulted):
```kotlin
fun generate(
    ...,
    logcatStream: List<LogLine> = emptyList(),
    ...
): String
```

New private helper:
```kotlin
private fun sectionLogcatStream(stream: List<LogLine>, captureStartMs: Long): String {
    if (stream.isEmpty()) return ""
    val rows = stream.joinToString("\n") { line ->
        val cls = "log-row-${levelClassName(line.level)}"
        val tsRel = (line.tsMs - captureStartMs).coerceAtLeast(0L)
        """<tr class="$cls" data-ts="${line.tsMs}"><td>${formatRel(tsRel)}</td><td>${line.level}</td><td>${escapeHtml(line.tag)}</td><td>${escapeHtml(line.msg)}</td></tr>"""
    }
    // ... wrap in <details><table> per LCS-009 ...
}
private fun levelClassName(level: Char): String = when (level) {
    'E','F','A' -> "error"
    'W' -> "warn"
    'I' -> "info"
    'D' -> "debug"
    else -> "verbose"  // V or unknown
}
```

CSS additions (inline `<style>` block):
```css
.log-row-error{background:#fee;color:#900;font-weight:600}
.log-row-warn{background:#ffd;color:#860}
.log-row-info{color:#333}
.log-row-debug{color:#666;font-size:11px}
.log-row-verbose{color:#888;font-size:10px}
```

JS click handler (appended to existing report `<script>`):
```javascript
function gpClickMetric(absMs) {
  const dlg = document.getElementById('gp-log-window');
  const body = document.getElementById('gp-log-window-body');
  const min = absMs - 5000, max = absMs + 5000;
  const rows = Array.from(document.querySelectorAll('#sec-logcat-stream tr[data-ts]'))
    .filter(r => { const t = +r.dataset.ts; return t >= min && t <= max; });
  if (rows.length === 0) {
    body.innerHTML = '<p>No logs en esta ventana (±5 s).</p>';
  } else {
    body.innerHTML = '<table>' + rows.map(r => r.outerHTML).join('') + '</table>';
  }
  dlg.showModal();
}
```

Every metric chart datapoint already carries timing info in `fpsTimed`; the rendering loop adds `onclick="gpClickMetric(${absMs})" data-ts="${absMs}"` to each.

---

## Threading model

| Component | Thread | Notes |
|---|---|---|
| `LogcatCapture` reader | `Dispatchers.IO` | unchanged from v4.4.x |
| `onRawLine` callback | `Dispatchers.IO` | fires on the same thread as `onLine` |
| `LogcatStreamBuffer.append` | called from `IO` | `synchronized(lock)` guards `deque` + `currentBytes` |
| `LogcatStreamBuffer.snapshot` | callable from any thread | acquires same `lock`, copies via `ArrayList(deque)` |
| `LogcatStreamConsumer._tail` | `MutableStateFlow` | atomic updates via `.value = ...` (thread-safe by contract) |
| HUD collection | `Dispatchers.Default` | mirrors the existing `_events.collect` pattern at AppViewModel:1002-1003 |
| `PidWatchdog` | `Dispatchers.IO` | `delay(1000)` between polls |

---

## Backward compatibility matrix

| Caller | Pre-change behaviour | Post-change behaviour |
|---|---|---|
| v4.4.x event detection (`EventDetectorImpl`) | tag-filtered logcat | PID-scoped logcat covering same SDK tags (superset) — internal matching unchanged |
| v4.4.x `LogcatCapture(bridge, onLine, onGap)` constructor | works | works (new `onRawLine` defaults to null) |
| v4.4.x `AdbBridge.startLogcat(deviceId, tagArgs)` | works | works (new `pids` defaults to empty → falls back to tag-args mode) |
| v4.4.x `.gameperf` files | load | load (new `logcatStream` defaults to empty) |
| v4.4.x `SessionResult(...)` constructor (positional) | works | works (new `logcatStream` is named-only / trailing) |
| Pre-N (Android < 7.0) devices | `--pid` unsupported | bridge falls back to in-coroutine PID filter (LCS-001 scenario 3) |
| iOS captures | no logcat | no logcat (`logcatStream` stays empty) |

---

## Test architecture

| Test file | Sprint | Covers |
|---|---|---|
| `LogcatStreamBufferTest.kt` | Sprint 0 | LCS-004 (ring buffer ordering, line + byte caps, concurrent snapshot) |
| `LogcatLineSerializationTest.kt` | Sprint 0 | LCS-005 (`LogLine` `@Serializable` round-trip; default-field decoding) |
| `LogcatCaptureDualEmitTest.kt` | Sprint 0 | LCS-003 (existing `onLine` unchanged; `onRawLine` invoked; throw-isolation per LCS-012) |
| `AdbBridgeStartLogcatPidTest.kt` | Sprint 0 | LCS-001 (command construction for PID, multi-PID, fallback) |
| `AdbBridgeResolvePidsTest.kt` | Sprint 0 | LCS-002 (multi-PID, empty, garbage input) |
| `PidWatchdogTest.kt` | Sprint 0 | LCS-007 (no-change tick → no event; change tick → callback) |
| `SessionHistoryLogcatStreamTest.kt` | Sprint 1 | LCS-006, LCS-011 (round-trip, legacy load) |
| `AppViewModelLogcatStreamTest.kt` | Sprint 2 | LCS-008, LCS-013 (HUD tail mirroring, level-filter defaults) |
| `ReportGeneratorLogcatStreamTest.kt` | Sprint 3 | LCS-009, LCS-010 (section rendering, click-handler injection, `data-ts` attributes) |
| `ReportGeneratorRegressionTest.kt` | Sprint 3 | existing fixtures stay byte-identical with `logcatStream = emptyList()` (default) |

Target test count: **+25 across the SDD** (10 in Sprint 0, 3 in Sprint 1, 4 in Sprint 2, 8 in Sprint 3).

---

## Risks and mitigations

1. **Stream backpressure could starve event detection** → ring buffer is O(1) lock-only, `MutableStateFlow.value =` is atomic and non-blocking; HUD collection on `Dispatchers.Default` cannot back-pressure the `IO` capture reader. Verified by `LogcatCaptureDualEmitTest` with a slow `onRawLine` stub.
2. **5 MiB byte cap heuristic may undercount** under unicode-heavy logs → the heuristic is by-design pessimistic for the line cap and slightly optimistic for the byte cap; if real-world traces exceed the cap by a few %, the line cap still catches it. Sprint 4 can refine with exact `Json.encodeToString().length` measurement if needed.
3. **`--pid` semantics differ pre/post Android 7** → handled in LCS-001 scenario 3 with in-coroutine fallback; verified by `AdbBridgeStartLogcatPidTest`.
4. **Persistence size growth** → 10 K lines × ~100 B JSON ≈ 1 MB per session, in line with the existing `.gameperf` file sizes (~500 KB - 2 MB today). Acceptable.
5. **PID change during capture** → LCS-007 specifies the synthetic marker + restart; the watchdog interval (1 s) is the worst-case data-loss window.
6. **`pidof` may not be available** on some heavily-stripped OEM builds → fallback path: `adb shell ps -A | grep <pkg>`. Out of scope for v1; documented as a known limitation.
7. **Multi-PID `--pid` chaining** → Android logcat supports a single `--pid` arg only in some early releases. Verify on the device matrix in Sprint 0; fallback to in-coroutine filter if the chain syntax is rejected.
