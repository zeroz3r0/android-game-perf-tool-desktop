# Capability: `report`

**Source of truth**: this file integrates ADDED requirements from `2026-05-13-shareable-html-report` (Issue #2 Block F). Predates this archive there was no main `report` spec — the new requirements below ARE the v1 source of truth for the capability. Prior behavior of `ReportGenerator.kt` (FPS / memory / CPU / temp / battery cards, grading ring, events strip) is implicit and preserved by the backward-compatibility requirement.

Implementation lives in:
- `src/main/kotlin/com/gameperf/desktop/report/ReportGenerator.kt`
- `src/main/kotlin/com/gameperf/desktop/core/report/kpi/` (KPI rendering helpers)

Feature flag: `Settings.kpiScoringInternalEnabled` (defaults `false`).

## Requirements

### Requirement: KPI Scoring Section Rendering

The system SHALL render a KPI scoring section in the HTML report when `kpiScoringInternalEnabled` is `true` AND a non-null `KpiScoreReport` is provided.

#### Scenario: Flag OFF — section omitted

- **GIVEN** `Settings.kpiScoringInternalEnabled == false`
- **AND** a non-null `KpiScoreReport` is passed to `ReportGenerator.generate`
- **WHEN** the report is generated
- **THEN** the output HTML SHALL NOT contain `id="sec-kpi-scoring"`
- **AND** the output SHALL be byte-equivalent to the same call with `kpiReport = null`

#### Scenario: Flag ON, null report — section omitted

- **GIVEN** `kpiScoringInternalEnabled == true`
- **AND** `kpiReport == null`
- **WHEN** the report is generated
- **THEN** the output HTML SHALL NOT contain `id="sec-kpi-scoring"`

#### Scenario: Flag ON, report present — section rendered

- **GIVEN** `kpiScoringInternalEnabled == true`
- **AND** a `KpiScoreReport(sessionScore=72, sessionBand=AMBER, ...)` with 3 phases + 5 categories
- **WHEN** the report is generated
- **THEN** the HTML SHALL contain `id="sec-kpi-scoring"`
- **AND** the section SHALL include the overall session score `72/100` with band class `kpi-band-amber`
- **AND** the section SHALL include one row per phase with score, band, and per-KPI drill-down
- **AND** the section SHALL include one row per category with score and band

### Requirement: Comparison vs Targets Table

The system SHALL render a table comparing actual KPI values to per-tier targets with signed delta and band color.

#### Scenario: KPI rows show actual / target / delta / band

- **GIVEN** flag ON, `kpiReport` with `FPS_AVG` raw=55, target=60, direction=HIGHER_IS_BETTER, band=AMBER
- **WHEN** rendered
- **THEN** the comparison table SHALL contain a row with cells `55`, `60`, `-5` (negative because HIGHER_IS_BETTER and actual<target), CSS class `kpi-band-amber`

#### Scenario: LOWER_IS_BETTER delta sign

- **GIVEN** `FRAME_TIME_P99` raw=20.0ms, target=16.67ms, direction=LOWER_IS_BETTER
- **WHEN** rendered
- **THEN** the delta cell SHALL display `-3.33` (negative because actual is worse than target — sign convention "positive=better" per `ComparisonEngine.delta`)

#### Scenario: Null raw value rendered as N/D

- **GIVEN** a `KpiScore` with `rawValue = null`
- **WHEN** rendered
- **THEN** the actual cell SHALL display `N/D` with class `kpi-na`
- **AND** the delta cell SHALL be empty
- **AND** the row SHALL NOT carry a band color class

### Requirement: Notebookcheck Annotation Formatter

The system SHALL provide a pure formatter that produces the Notebookcheck `Ø<avg> (<min>-<max>)` shorthand per `docs/competitive-analysis-and-kpis.md` §7.3.

#### Scenario: Integer formatter

- **GIVEN** avg=60, min=59, max=61
- **WHEN** `Notebookcheck.format(60, 59, 61)` is called
- **THEN** it SHALL return the exact string `"Ø60 (59-61)"`

#### Scenario: Decimal formatter with 1-digit precision

- **GIVEN** avg=14.3, min=12.1, max=16.7
- **WHEN** `Notebookcheck.format(14.3, 12.1, 16.7, decimals=1)` is called
- **THEN** it SHALL return `"Ø14.3 (12.1-16.7)"`
- **AND** the decimal separator SHALL be `.` (US locale, mirroring `fmtUS`)

#### Scenario: Annotation appears next to FPS pill

- **GIVEN** flag ON, `avgFps=60`, `minFps=59`, `maxFps=61`
- **WHEN** rendered
- **THEN** the FPS section SHALL contain the literal substring `Ø60 (59-61)`

### Requirement: Phase Breakdown Table

The system SHALL render per-phase metrics from `KpiScoreReport.phases` in a tabular form distinct from the overall scoring section.

#### Scenario: Phase rows ordered by `Phase` enum declaration order

- **GIVEN** flag ON, phases `[GAMEPLAY, APP_STARTUP, INTERSTITIAL_AD]` present
- **WHEN** rendered
- **THEN** the phase table SHALL list them in `Phase` declaration order: `APP_STARTUP`, `INTERSTITIAL_AD`, `GAMEPLAY`

#### Scenario: Empty phase list omits the table

- **GIVEN** flag ON, `kpiReport.phases.isEmpty()`
- **WHEN** rendered
- **THEN** the output SHALL NOT contain `id="sec-phase-breakdown"`

### Requirement: CSV + JSON Download Buttons

The system SHALL embed downloadable CSV and JSON exports of the `KpiScoreReport` as base64 data URLs (no server, no fetch).

#### Scenario: CSV download attribute and filename

- **GIVEN** flag ON, `kpiReport` non-null, `pkg = "com.example.game"`
- **WHEN** rendered
- **THEN** the HTML SHALL contain a link `<a download="kpi_com.example.game.csv" href="data:text/csv;base64,...">`

#### Scenario: CSV header + row schema

- **WHEN** the embedded CSV is base64-decoded
- **THEN** the first line SHALL be exactly `phase,kpi,raw_value,score,delta,band`
- **AND** each subsequent line SHALL contain those 6 fields comma-separated

#### Scenario: JSON download is valid kotlinx-serialization round-trip

- **WHEN** the embedded JSON is base64-decoded and parsed via `Json.decodeFromString<KpiScoreReport>()`
- **THEN** the parsed report SHALL equal (`==`) the input `kpiReport`

### Requirement: p1 + p0.1 Frame-Time Percentile Pills

The system SHALL display p1 and p0.1 frame-time percentiles in the frame-time stats row by default when sufficient samples are available.

#### Scenario: Sufficient samples → pills shown

- **GIVEN** `allFrameTimes.size >= 1000`
- **WHEN** rendered
- **THEN** the `#sec-frametime` stats row SHALL contain pills labeled `p1` and `p0.1` with millisecond values

#### Scenario: Insufficient samples → pills omitted

- **GIVEN** `allFrameTimes.size < 1000`
- **WHEN** rendered
- **THEN** the `#sec-frametime` stats row SHALL NOT contain `p0.1` pill
- **AND** the `p1` pill SHALL still render if `size >= 100`

#### Scenario: p0.1 is the 99.9th percentile of frame times

- **GIVEN** a sorted `allFrameTimes` list of size 10000
- **WHEN** rendered
- **THEN** the p0.1 pill value SHALL equal `allFrameTimes.sorted()[9990]` formatted with 1-decimal precision and `ms` suffix

### Requirement: Android Vitals Warning Banner

The system SHALL display a prominent banner when any Android Vitals threshold is breached, per `docs/competitive-analysis-and-kpis.md` §3.1.

#### Scenario: Cold-start ≥5s triggers banner

- **GIVEN** flag ON, `kpiReport` with `COLD_START_MS` raw=5500
- **WHEN** rendered
- **THEN** the HTML SHALL contain `id="sec-vitals-banner"` with class `kpi-vitals-warn`
- **AND** the banner text SHALL include the literal substring `Cold start lento (≥5s)`

#### Scenario: ANR rate ≥0.47% triggers banner

- **GIVEN** flag ON, `kpiReport` with `ANR_COUNT` raw=5, session duration=1000s, ANR rate computed as 0.5%
- **WHEN** rendered
- **THEN** the banner SHALL include `ANR ≥0.47%`

#### Scenario: Slow-frame rate >25% triggers banner

- **GIVEN** `SLOW_FRAMES` rate=30%
- **WHEN** rendered
- **THEN** the banner SHALL include `Slow frames >25%`

#### Scenario: Frozen-frame rate >0.1% triggers banner

- **GIVEN** `FROZEN_FRAMES` rate=0.2%
- **WHEN** rendered
- **THEN** the banner SHALL include `Frozen frames >0.1%`

#### Scenario: No thresholds breached → banner omitted

- **GIVEN** all Vitals KPIs within target
- **WHEN** rendered
- **THEN** the HTML SHALL NOT contain `id="sec-vitals-banner"`

### Requirement: Caveats Footer

The system SHALL render a caveats footer listing measurement limitations.

#### Scenario: Caveat content

- **GIVEN** flag ON
- **WHEN** rendered
- **THEN** the HTML SHALL contain `id="sec-caveats"` with at least these caveat strings:
  - Foreground-app GPU attribution disclaimer
  - 1Hz sample-rate disclaimer
  - Device tier disclosure (TOP/MID/LOW or `MID (default)` when null)

#### Scenario: Tier label when `deviceTier` empty

- **GIVEN** `deviceTier == ""` (legacy callers)
- **WHEN** rendered
- **THEN** the tier caveat SHALL say `MID (default)`

### Requirement: Single Source for Band Colors

The system SHALL delegate band-to-color mapping to a single `KpiBandColors` object. No band hex values SHALL be hardcoded elsewhere in `core/report/kpi/` (mirror `KpiCatalog` anti-duplication pattern from CLAUDE.md v4.2.13).

#### Scenario: Grep guard

- **WHEN** `rg "#(10b981|f59e0b|ef4444|22c55e|d97706|dc2626)"` runs against `src/main/kotlin/com/gameperf/desktop/core/report/kpi/` excluding `KpiBandColors.kt`
- **THEN** it SHALL return zero matches

#### Scenario: Single source contains 3 bands

- **WHEN** `KpiBandColors.forBand(Band.GREEN)`, `forBand(Band.AMBER)`, `forBand(Band.RED)` are called
- **THEN** each SHALL return a non-empty `#RRGGBB` string
- **AND** the three values SHALL be distinct

### Requirement: Output Size Budget

The system SHALL produce HTML output ≤5MB for a 60-second capture with `kpiReport` populated.

#### Scenario: Size budget for 60s synthetic session

- **GIVEN** a synthetic session with 60 FPS samples, 1000 frame times, populated `kpiReport`
- **WHEN** rendered with flag ON
- **THEN** the resulting HTML byte size SHALL be ≤ 5_000_000 bytes

### Requirement: Backward Compatibility

The system SHALL preserve byte-equivalent output for callers that do not pass KPI parameters.

#### Scenario: Legacy fixture byte-equivalence

- **GIVEN** `ReportGenerator.generate(...)` called with all KPI params defaulted (no `kpiReport`, `kpiInternalEnabled = false`)
- **WHEN** rendered
- **THEN** the output SHALL equal byte-for-byte the v4.5 output for the same non-KPI inputs (asserted by `KpiSectionsOffByDefaultTest` against a stored golden file or via two-call normalize-and-compare)
