# Proposal: Shareable HTML Report (Issue #2 Block F)

## Intent

Today `ReportGenerator.kt` emits a self-contained HTML with FPS / memory / CPU / temp / battery cards. Block F of issue #2 extends it with the market-anchored KPI scoring v1 already shipped (`core/kpi/`), plus the differentiator features identified in `docs/competitive-analysis-and-kpis.md` §7.5: per-game annotation in Notebookcheck `Ø60 (59-61)` style, downloadable CSV/JSON, p1/p0.1 percentiles, Android Vitals banner warnings, comparison-vs-target tables, and inline caveats. Output remains self-contained (no CDN, no telemetry) for privacy-safe link sharing.

## Scope

### In Scope
- F.2 KPI Scoring section (per-phase, per-category, overall) driven by `KpiScoreReport`
- F.3 Comparison-vs-targets table (KPI actual vs target, deltas, color bands GREEN/AMBER/RED)
- F.4 Joint timeline overlay markers wired to KPI sections (existing chart, new anchors)
- F.5 Phase breakdown table (metrics segmented by detected `Phase` from KPI input)
- F.6 Notebookcheck annotation formatter `Ø<avg> (<min>-<max>)` for FPS/CPU/temp/FPower
- F.7 Download CSV + JSON buttons (embedded base64 data URLs, no server)
- F.8 p1 + p0.1 frame-time percentiles in stats row by default
- F.9 Android Vitals warning banner (cold-start ≥5s, ANR ≥0.47%, slow-frame >25%, frozen-frame >0.1%)
- F.10 Caveats footer (foreground-app GPU attribution, 1Hz sample rate, device tier)
- New `core/report/kpi/` package: pure HTML string-building helpers (`KpiScoreSection.kt`, `ComparisonTable.kt`, `AndroidVitalsBanners.kt`, `Notebookcheck.kt`, `PhaseBreakdown.kt`, `DataExportButtons.kt`)
- Feature flag: all new sections gated by `Settings.kpiScoringInternalEnabled` (same flag as KPI scoring v1)
- Golden HTML fixture tests per renderer (TDD red-green)

### Out of Scope
- Calibration / weight tuning (Block G)
- UI to choose which sections to include (manual gating via Settings only)
- Cloud hosting (violates local-first)
- Public/external sharing model (legal review needed — §8 decision #8)
- `SessionResult → KpiInput` adapter (separate change `kpi-session-adapter`; this change accepts `KpiScoreReport?` directly)

## Capabilities

### New Capabilities
None — extends `report` capability.

### Modified Capabilities
- `report`: ADD KPI scoring section, comparison-vs-target table, phase breakdown, Notebookcheck annotation formatter, CSV/JSON export buttons, p1/p0.1 percentile pills, Android Vitals warning banner, inline caveats footer. Gated by `Settings.kpiScoringInternalEnabled`.

## Approach

Extend `ReportGenerator.generate(...)` with three optional parameters defaulted to safe no-op values: `kpiReport: KpiScoreReport? = null`, `kpiInternalEnabled: Boolean = false`, `allFrameTimesP01: Double = 0.0`. When the flag is OFF or `kpiReport == null`, the existing HTML renders byte-equivalent (backward compat for legacy fixtures + pre-v4.6 history re-renders). When ON, six pure helper functions stitch new sections into the existing template at well-defined anchors (after `#sec-summary` for F.2/F.3, replacing/augmenting `#sec-stats` for F.5/F.6/F.8, after `#sec-events` for F.10, inside `#sec-summary` for F.9 banner, top-right FAB group for F.7 buttons). All helpers are pure `(input) -> String` for golden-fixture testing without a browser.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `src/main/kotlin/com/gameperf/desktop/report/ReportGenerator.kt` | Modified | Add 3 params, wire 6 new sections behind flag |
| `src/main/kotlin/com/gameperf/desktop/core/report/kpi/` | New | 6 pure renderers + CSS snippet |
| `src/main/kotlin/com/gameperf/desktop/core/Settings.kt` | Touched | Reuse existing `kpiScoringInternalEnabled` flag (no schema change) |
| `src/test/kotlin/com/gameperf/desktop/report/` | New tests | One test file per renderer + integration test for flag OFF byte-equivalence |
| `src/test/resources/report-fixtures/kpi/` | New | Golden HTML fragments + JSON `KpiScoreReport` inputs |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| HTML bloat blows past 5MB for 1-min capture | Low | CSV/JSON embedded as base64 data URLs; budget test `KpiReportSizeTest` asserts <5MB for 60s session |
| Legacy fixture (`ReportRenderingTest`) breaks byte-equivalence when flag OFF | Med | Integration test `KpiSectionsOffByDefaultTest` asserts identical output to v4.5 baseline when `kpiInternalEnabled=false` |
| Notebookcheck formatter drift vs `docs/competitive-analysis-and-kpis.md` §7.3 wording | Low | `NotebookcheckTest` asserts exact format `Ø60 (59-61)` |
| Android Vitals threshold drift from `KpiCatalog` | Med | `AndroidVitalsBannersTest` reads threshold constants from a shared object, no hardcoded numbers in renderer |
| New CSS classes clash with existing `.stat-pill` / `.card` | Low | All new classes prefixed `kpi-*`; reviewer checklist |
| Multiple sources of band logic (GREEN/AMBER/RED) | High | All band → color mapping delegates to `ComparisonEngine.band(score)` + a single `KpiBandColors` object — no hardcoded hex elsewhere |

## Rollback Plan

Revert the change commit. `Settings.kpiScoringInternalEnabled` already defaults to `false` so even partial revert leaves users on the legacy report unchanged. Tests `KpiSectionsOffByDefaultTest` + `ReportRenderingTest` guarantee no behavior change with flag OFF.

## Dependencies

- KPI scoring v1 (`core/kpi/`) — archived `2026-05-13-kpi-scoring-framework` ✅
- Future: `kpi-session-adapter` change to provide `KpiScoreReport` from `SessionResult` — NOT required for this change (renderers accept `KpiScoreReport?` directly, callers can pass `null` until adapter ships).

## Success Criteria

- [ ] `./gradlew check` GREEN, detekt 0 findings
- [ ] Flag OFF: report HTML byte-identical to v4.5 baseline (golden test)
- [ ] Flag ON with synthetic `KpiScoreReport`: all 6 sections render with expected anchors + content
- [ ] CSV download button produces valid CSV (header row + per-KPI rows) loadable by Excel/LibreOffice
- [ ] JSON download button produces valid `KpiScoreReport` round-trippable via kotlinx-serialization
- [ ] p1 + p0.1 pills present when `allFrameTimes.size >= 1000`
- [ ] Android Vitals banner appears when cold-start ≥5s OR ANR ≥0.47% OR slow-frame >25% OR frozen-frame >0.1%
- [ ] Output HTML ≤5MB for 60s synthetic capture
- [ ] Single source for band colors: grep `#10b981` / `#f59e0b` / `#ef4444` outside `KpiBandColors.kt` = 0 hits in `core/report/kpi/`
