# Delta Spec: event-segmentation (instrumented-event-mode)

> Supersedes `ESC-INSTR-001..003` from parent change `event-segmentation-coverage` for the four fixed sub-tags. Where this delta and parent delta both touch `INSTRUMENTED`, THIS delta wins.

## ADDED Requirements

### Requirement: IEM-001 — GamePerf opt-in signature

The `core/events/SdkSignatureCatalog.ALL` MUST include exactly ONE entry with `sdk = "GamePerf"`, `defaultType = EventType.INSTRUMENTED`, `logcatTags = listOf("GamePerf")`, `activityClasses = emptyList()`, and open/close patterns that match `{Tag}.Start` / `{Tag}.Stop` literals. The entry's open patterns SHALL resolve to `EventType.INSTRUMENTED`.

#### Scenario: Catalog exposes GamePerf entry

- GIVEN the catalog is loaded
- WHEN the test inspects `SdkSignatureCatalog.ALL`
- THEN exactly one entry MUST have `sdk == "GamePerf"`
- AND its `defaultType` MUST equal `EventType.INSTRUMENTED`
- AND `logcatTags` MUST equal `listOf("GamePerf")`

### Requirement: IEM-002 — Fixed 4-tag allowlist

The detector MUST recognize ONLY these four sub-tags as valid instrumented phases: `CINEMATIC`, `TUTORIAL`, `GAMEPLAY_DENSE`, `SPECIAL_EVENT`. Any other tag value MUST be silently rejected (no event emitted, no warning surfaced to the user).

#### Scenario: Each of the four tags opens an INSTRUMENTED event

- GIVEN a detector with foreground guard primed and the catalog loaded
- WHEN `handleLogLine(LogLine(tag="GamePerf", level='I', msg="CINEMATIC.Start"))` fires at t=1000
- THEN one event MUST be emitted with `type=INSTRUMENTED`, `sdkSource="GamePerf"`, `confidence=HIGH`, `startMs=1000`, `endMs=null`, `metadata["tag"]=="CINEMATIC"`
- AND the same MUST hold (with the corresponding tag value in metadata) for inputs `TUTORIAL.Start`, `GAMEPLAY_DENSE.Start`, `SPECIAL_EVENT.Start`

#### Scenario: Unknown tag silently rejected

- GIVEN no open events
- WHEN `handleLogLine(LogLine(tag="GamePerf", level='I', msg="MENU.Start"))` fires
- THEN NO event MUST be emitted
- AND NO warning MUST be added to the detector's `warnings` flow

### Requirement: IEM-003 — Case-sensitive matching

Tag matching MUST be case-sensitive. `CINEMATIC` is valid; `Cinematic`, `cinematic`, `CINeMATIC` MUST all be rejected as unknown tags.

#### Scenario: Lowercase variant rejected

- GIVEN no open events
- WHEN `handleLogLine(LogLine(tag="GamePerf", level='I', msg="cinematic.Start"))` fires
- THEN NO event MUST be emitted
- AND no warning MUST be added

#### Scenario: Mixed-case variant rejected

- GIVEN no open events
- WHEN `handleLogLine(LogLine(tag="GamePerf", level='I', msg="Cinematic.Start"))` fires
- THEN NO event MUST be emitted

### Requirement: IEM-004 — Per-tag-keyed lifecycle

`{Tag}.Stop` MUST close ONLY the still-open event whose `metadata["tag"]` equals that exact tag value. A Stop for tag X MUST NOT close an open event of tag Y, even when both share `sdkSource="GamePerf"`.

#### Scenario: TUTORIAL.Stop does not close CINEMATIC.Start

- GIVEN a CINEMATIC event open from t=1000 and a TUTORIAL event open from t=1500
- WHEN `handleLogLine(LogLine(tag="GamePerf", level='I', msg="TUTORIAL.Stop"))` fires at t=2000
- THEN the TUTORIAL event MUST have `endMs=2000`
- AND the CINEMATIC event MUST still have `endMs=null`

#### Scenario: Overlapping tags are independent

- GIVEN no open events
- WHEN `CINEMATIC.Start` fires at t=1000, then `TUTORIAL.Start` at t=1500, then `CINEMATIC.Stop` at t=2000, then `TUTORIAL.Stop` at t=2500
- THEN two events MUST exist
- AND the CINEMATIC event MUST have `startMs=1000, endMs=2000`
- AND the TUTORIAL event MUST have `startMs=1500, endMs=2500`

### Requirement: IEM-005 — Orphan Stop tolerated silently

A `{Tag}.Stop` with no matching open event of the same tag MUST be ignored without emitting an event and without surfacing a warning.

#### Scenario: Stop without prior Start ignored

- GIVEN no open events
- WHEN `handleLogLine(LogLine(tag="GamePerf", level='I', msg="GAMEPLAY_DENSE.Stop"))` fires
- THEN NO event MUST be emitted
- AND NO warning MUST be added

### Requirement: IEM-006 — Nested same-tag handling

If `{Tag}.Start` fires while an event with the same tag is still open, the detector MUST NOT open a second parallel event for that tag. The existing open event SHALL remain open with its original `startMs`. The redundant Start MUST be ignored (no warning).

#### Scenario: Re-entrant CINEMATIC.Start no-ops

- GIVEN a CINEMATIC event open from t=1000
- WHEN `handleLogLine(LogLine(tag="GamePerf", level='I', msg="CINEMATIC.Start"))` fires at t=1500
- THEN only ONE CINEMATIC event MUST exist
- AND its `startMs` MUST still be 1000
- AND its `endMs` MUST still be null

### Requirement: IEM-007 — Tag allowlist includes GamePerf

`SdkSignatureCatalog.logcatTagArgs()` MUST include `"GamePerf:D"` so adb logcat passes lines emitted with that tag to the detector.

#### Scenario: logcatTagArgs lists GamePerf:D

- WHEN `SdkSignatureCatalog.logcatTagArgs()` is invoked
- THEN the returned list MUST contain `"GamePerf:D"`

### Requirement: IEM-008 — Foreground-guard bypass for instrumented opens

Instrumented opens MUST NOT be rejected by the `FOREGROUND_GUARD_MS` proximity check. The game is, by definition, in foreground when emitting `GamePerf:I` lines from its own process.

#### Scenario: Instrumented event opens even when foreground stale

- GIVEN `lastGameForegroundMs` is more than `FOREGROUND_GUARD_MS` in the past
- WHEN `handleLogLine(LogLine(tag="GamePerf", level='I', msg="CINEMATIC.Start"))` fires
- THEN ONE INSTRUMENTED event MUST be emitted

## MODIFIED Requirements

(None — this delta adds new requirements; it does not modify existing ones in the main `event-segmentation` spec.)

## REMOVED Requirements

(None.)
