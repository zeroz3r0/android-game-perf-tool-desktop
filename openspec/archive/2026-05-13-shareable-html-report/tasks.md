# Tasks: Shareable HTML Report Extensions (Issue #2 Block F)

**Mode**: STRICT TDD. Each task = (1) write failing test(s), (2) implement minimum code, (3) `./gradlew check` GREEN, (4) commit. detekt MUST stay clean. Phases sized <15min per apply run.

## Phase 0 — Foundation (pure helpers, no rendering)

- [x] **T0.1** Verify all `KpiScoreReport` data classes have `@Serializable` (KpiMetadata.kt + nested types)
- [x] **T0.2** Create `core/report/kpi/KpiBandColors.kt` (single source for band colors)
- [x] **T0.3** Create `core/report/kpi/Notebookcheck.kt`

## Phase 1 — Frame-time percentiles + CSV serializer

- [x] **T1.1** Create `core/report/kpi/FrameTimePercentiles.kt`
- [x] **T1.2** Create `core/report/kpi/KpiCsvSerializer.kt`

## Phase 2 — Static-content renderers (caveats + Notebookcheck wiring)

- [x] **T2.1** Create `core/report/kpi/KpiCaveats.kt` — 5 tests GREEN, section id `sec-caveats`, blank tier→"MID (default)", explicit tier verbatim
- [x] **T2.2** Create `core/report/kpi/AndroidVitalsBanners.kt` — 8 tests GREEN, COLD_START threshold via `KpiCatalog`, ANR/slow/frozen via Vitals doc §3.1 constants, section id `sec-vitals-banner` class `kpi-vitals-warn`, empty string when no breaches

## Phase 3 — Tabular renderers (KPI score section + comparison + phase breakdown)

- [x] **T3.1** Create `core/report/kpi/KpiScoreSection.kt` — 5 tests GREEN, overall+phases-table+category-cards, band via `KpiBandColors`
- [x] **T3.2** Create `core/report/kpi/ComparisonTable.kt` — 5 tests GREEN, ComparisonEngine.delta with %.2f formatting, tier fallback chain, N/D + kpi-na for null rawValue
- [x] **T3.3** Create `core/report/kpi/PhaseBreakdown.kt` — 4 tests GREEN, Phase.ordinal-sorted, empty→"" exact, drill-down by KpiScore.id

## Phase 4 — Export buttons + CSS bundle

- [x] **T4.1** Create `core/report/kpi/DataExportButtons.kt`
  - **RED**: `DataExportButtonsTest` — 4 tests
  - **GREEN**: `renderExportButtons(report, pkg)` calls `KpiCsvSerializer.toCsv` + `Json.encodeToString(report)`, base64 encodes both (`java.util.Base64.getEncoder()`), emits two `<a>` tags inside `<div class="kpi-export-buttons">`.

- [x] **T4.2** Create `core/report/kpi/KpiReportCss.kt`
  - **GREEN**: `val KPI_CSS: String` top-level with `.kpi-band-*` classes, `.kpi-vitals-warn`, `.kpi-export-buttons`, `.kpi-na`, and full kpi-* family. Colors via Kotlin String templates referencing `KpiBandColors.forBand(...)` at top of file (top-level `val`).

## Phase 5 — ReportGenerator wiring (integration)

- [x] **T5.1** Added new params to `ReportGenerator.generate(...)` + flag-OFF byte-equivalence guard
  - **RED**: `KpiSectionsOffByDefaultTest` — 3 tests
  - **GREEN**: Added params `kpiReport: KpiScoreReport? = null`, `kpiInternalEnabled: Boolean = false`, `kpiTier: String? = null` (kept as String for symmetry with existing `deviceTier: String`).
  - Strategy: byte-equivalence via `normalize()` regex stripping sessionId + date forms, no fixture file. Two calls (legacy + explicit defaults) → equal length + equal content after normalization.
  - Verified all 6 legacy tests still PASS.

- [x] **T5.2** Wired the 6 new sections behind the flag
  - **RED**: `KpiSectionsRenderedWithFlagOnTest` — 6 tests GREEN
  - **GREEN**: at each anchor in `ReportGenerator.generate`, call the helper (guarded by `if (kpiInternalEnabled && kpiReport != null)`). Append `$KPI_CSS` to `<style>` block conditionally.

## Phase 6 — Size budget + final verify

- [x] **T6.1** `KpiReportSizeTest` — 60s synthetic session (60 fps points, 3600 frame times, ALL 8 phases × 26 KPIs populated) + flag ON → assert HTML bytes ≤ 5_000_000. PASSED.

- [x] **T6.2** `KpiBandColorsSingleSourceTest` — walks `src/main/.../core/report/kpi/*.kt` (except `KpiBandColors.kt`) with `Charsets.UTF_8`, greps for `#(10b981|f59e0b|ef4444|22c55e|d97706|dc2626|f97316)`, asserts ZERO matches. PASSED with no offenders.

- [x] **T6.3** Final `./gradlew check` ✅ GREEN. detekt ✅ CLEAN. Build time 1m 45s. Apply-progress saved to engram with summary.

## Out of Scope (DO NOT do in this change)

- AppViewModel wiring to compute `KpiScoreReport` from `SessionResult` (separate change `kpi-session-adapter`)
- UI toggle for `kpiScoringInternalEnabled` in Settings screen
- Multi-session comparison view
- Cloud-hosted variant
- Ad SDK lab work
