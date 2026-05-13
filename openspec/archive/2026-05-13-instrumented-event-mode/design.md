# Design: instrumented-event-mode

## Technical Approach

Single-tag opt-in protocol on logcat tag `GamePerf` (level `I`). A pure parser extracts `(tag, isStart)` from the message body via two top-level case-sensitive regexes. The detector special-cases the GamePerf signature to use a **per-tag-keyed slot** in `openEvents` so each sub-tag has an independent lifecycle.

The detection itself lives in `core/events/` (CLAUDE.md anti-duplication rule — single source of truth). The catalog entry exists for tag allowlist + symmetric pattern-presence invariants enforced by `SdkSignatureCatalogTest`; actual classification and per-tag routing happens in the detector branch.

## Architecture Decisions

| Decision | Choice | Alternatives | Rationale |
|---|---|---|---|
| Tag list | Hard-coded `setOf("CINEMATIC","TUTORIAL","GAMEPLAY_DENSE","SPECIAL_EVENT")` in `InstrumentedLineParser.kt` | User config / free-text | User-spec said FIXED for deterministic grading; free-text would break the grading rubric across captures |
| Case sensitivity | Strict — no `(?i)` flag | Insensitive (parent change Sprint 3 uses `(?i)`) | User-spec explicit: `CINEMATIC ≠ Cinematic`; deterministic, avoids accidental matches in non-instrumented logs |
| Routing in detector | Special-case branch on `sig.sdk == "GamePerf"` | Generic per-pattern keying for all SDKs | Generic change would alter close-matching semantics for AdMob/Unity/etc and risk regressions in 17 existing entries; targeted branch is surgical |
| Open/close pattern lifecycle | Per-tag key `"GamePerf:instrumented:$tag"` | Single SDK key | Existing `tryClose` would close the wrong (oldest) instrumented event when `TUTORIAL.Stop` arrives if all 4 shared one key |
| Foreground guard | Bypass for INSTRUMENTED (like ANR does) | Apply guard | Game IS in foreground when emitting from own process; guard would reject early-startup cinematics; spec IEM-008 |
| Orphan Stop | Silent ignore | Emit warning | Devs may instrument incrementally; warnings would spam reports |
| Re-entrant Start | No-op, keep original | Close+reopen | User-spec edge case: nested events same tag stays as one logical segment |

## Data Flow

```
adb logcat (GamePerf:D)
       │
       ▼
LogcatLineParser ──→ LogLine(tag="GamePerf", level='I', msg="CINEMATIC.Start")
       │
       ▼
EventDetectorImpl.handleLogLine
       │
       ├── if (sig.sdk == "GamePerf")  ◄── new branch BEFORE generic openMatch path
       │      │
       │      ▼
       │   InstrumentedLineParser.parse(msg) ──→ InstrumentedHit(tag, isStart) | null
       │      │
       │      ├── isStart → openInstrumented(tag, tsMs)
       │      │     └─ key = "GamePerf:instrumented:$tag"
       │      │     └─ if (openEvents.containsKey(key)) return  // IEM-006 no-op
       │      │     └─ skip FOREGROUND_GUARD check (IEM-008)
       │      │     └─ emit DetectedEvent(type=INSTRUMENTED, metadata=mapOf("tag" to tag, "source" to "logcat"))
       │      │
       │      └── !isStart → closeInstrumented(tag, tsMs)
       │            └─ find openEvents["GamePerf:instrumented:$tag"]
       │            └─ if null → return (IEM-005 silent)
       │            └─ tryClose(open, tsMs, "instrumented-stop")
       │
       └── else → existing matchOpen / close flow (unchanged)
```

## File Changes

| File | Action | Description |
|---|---|---|
| `src/main/kotlin/com/gameperf/desktop/core/events/InstrumentedLineParser.kt` | Create | Pure helper. Top-level `private val INSTRUMENTED_OPEN_RE = Regex("""^([A-Z_]+)\.Start$""")` + close re. Public fun `parse(msg: String): InstrumentedHit?`. Validates tag against `ALLOWED_TAGS` set. Returns null if foreign/lowercase/unknown |
| `src/main/kotlin/com/gameperf/desktop/core/events/InstrumentedHit.kt` | Create | `internal data class InstrumentedHit(val tag: String, val isStart: Boolean)` |
| `src/main/kotlin/com/gameperf/desktop/core/events/SdkSignatureCatalog.kt` | Modify | Add 18th entry `SdkSignature("GamePerf", defaultType=INSTRUMENTED, ...)`. Open pattern: `Regex("""^[A-Z_]+\.Start$""") to INSTRUMENTED`. Close pattern: `Regex("""^[A-Z_]+\.Stop$""")`. Patterns are catalog invariants; real routing in detector |
| `src/main/kotlin/com/gameperf/desktop/core/events/EventDetectorImpl.kt` | Modify | Add `handleInstrumentedLine` branch at top of `handleLogLine` checking `line.tag == "GamePerf"`. ~40 LOC. Helper methods `openInstrumented` / `closeInstrumented` |
| `src/test/resources/logcat-fixtures/instrumented-opt-in.log` | Create | 4 Start/Stop pairs in sequence + 2 negative noise lines (lowercase + unknown tag) |
| `src/test/kotlin/com/gameperf/desktop/core/events/InstrumentedLineParserTest.kt` | Create | Pure parser tests — no detector. 4 positive Start, 4 positive Stop, 4 negative case variants, foreign-tag noise |
| `src/test/kotlin/com/gameperf/desktop/core/events/EventDetectorImplInstrumentedTest.kt` | Create | Lifecycle: open/close per tag, overlapping tags, nested same-tag, orphan Stop, foreground-stale, force-close on `stop()` |
| `src/test/kotlin/com/gameperf/desktop/core/events/SdkSignatureCatalogTest.kt` | Modify | Bump catalog size 17 → 18; add GamePerf to expected SDK set; fixture-smoke test for `instrumented-opt-in.log` |
| `README.md` | Modify | New §"Modo instrumentado (opt-in)" — 10-15 lines, castellano tuteo formal, copy-paste `Log.i("GamePerf", "CINEMATIC.Start")` |
| `README_EN.md` | Modify | Mirror section in English |
| `CHANGELOG.md` | Modify | v4.5.x entry under "Que hay de nuevo" + "Detalles técnicos" |

## Interfaces / Contracts

```kotlin
// InstrumentedLineParser.kt
internal data class InstrumentedHit(val tag: String, val isStart: Boolean)

internal object InstrumentedLineParser {
    val ALLOWED_TAGS: Set<String> = setOf(
        "CINEMATIC", "TUTORIAL", "GAMEPLAY_DENSE", "SPECIAL_EVENT",
    )

    private val OPEN_RE = Regex("""^([A-Z_]+)\.Start$""")
    private val CLOSE_RE = Regex("""^([A-Z_]+)\.Stop$""")

    fun parse(msg: String): InstrumentedHit? {
        OPEN_RE.matchEntire(msg)?.let { m ->
            val tag = m.groupValues[1]
            return if (tag in ALLOWED_TAGS) InstrumentedHit(tag, true) else null
        }
        CLOSE_RE.matchEntire(msg)?.let { m ->
            val tag = m.groupValues[1]
            return if (tag in ALLOWED_TAGS) InstrumentedHit(tag, false) else null
        }
        return null
    }
}
```

## Testing Strategy

| Layer | What | Approach |
|---|---|---|
| Unit (pure) | `InstrumentedLineParser.parse` | Direct call, no detector; covers IEM-002 / IEM-003 negative paths |
| Unit (detector) | `EventDetectorImpl` lifecycle | Drive `handleLogLine` synthetically (existing pattern, no mocks); covers IEM-001/004/005/006/008 |
| Fixture smoke | `instrumented-opt-in.log` | Run through `LogcatLineParser` + `EventDetectorImpl`; assert 4 INSTRUMENTED events with correct tags + closures |
| Catalog invariant | `SdkSignatureCatalogTest` | Size + named-set + GamePerf:D in `logcatTagArgs()` |

No new test deps. Runner: `./gradlew check`. Detekt MUST stay clean.

## Migration / Rollout

No data migration. `INSTRUMENTED` enum value pre-exists since v4.4.0 Sprint 0. Existing `.gameperf` files with no INSTRUMENTED events deserialize unchanged. Devs adopt by adding `Log.i("GamePerf", "...")` calls on their side — no breaking changes to capture pipeline.

Coordination with parent `event-segmentation-coverage` change: if its Sprint 3 ESC-INSTR-001..003 specs are still active at archive time, retire them and replace with IEM-001..008. Document supersession in CHANGELOG technical details.

## Open Questions

- None. User specified protocol shape, tag list, case sensitivity, and out-of-scope items explicitly.
