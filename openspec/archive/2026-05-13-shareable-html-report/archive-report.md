# Archive Report: shareable-html-report (Issue #2 Block F)

**Change**: `shareable-html-report`
**Archive date**: 2026-05-13
**Mode**: engram + filesystem (hybrid archival, no git touched)
**Status**: ✅ ARCHIVED

## Change Summary

Extended `ReportGenerator` with 6 KPI-driven HTML sections + base64 CSV/JSON export + size budget guard + grep-guard for single-source band colors. All new behavior is gated by `Settings.kpiScoringInternalEnabled` (default OFF) → backward-compat byte-equivalence preserved (asserted via `KpiSectionsOffByDefaultTest` using two-call normalize-and-compare strategy, no fixture file). Internal v1: no UI exposure path active yet — needs `AppViewModel` wire-up via `kpi-session-adapter` (separate follow-up change).

## Source Artifact References (Engram)

| Artifact | Topic Key | Obs ID |
|----------|-----------|--------|
| proposal | `sdd/shareable-html-report/proposal` | #383 |
| spec (delta) | `sdd/shareable-html-report/spec` | #385 |
| design | `sdd/shareable-html-report/design` | #389 |
| tasks | `sdd/shareable-html-report/tasks` | #390 |
| apply-progress | `sdd/shareable-html-report/apply-progress` | #391 |
| verify-report | `sdd/shareable-html-report/verify-report` | #397 |
| archive-report | `sdd/shareable-html-report/archive-report` | (this) |
| project context | `sdd-init/android-game-perf-tool-desktop` | #96 |

## Filesystem Outputs

| Path | Purpose |
|------|---------|
| `openspec/archive/2026-05-13-shareable-html-report/proposal.md` | Audit trail |
| `openspec/archive/2026-05-13-shareable-html-report/spec.md` | Delta spec (ADDED requirements only) |
| `openspec/archive/2026-05-13-shareable-html-report/design.md` | Audit trail |
| `openspec/archive/2026-05-13-shareable-html-report/tasks.md` | Audit trail |
| `openspec/archive/2026-05-13-shareable-html-report/apply-progress.md` | Audit trail |
| `openspec/archive/2026-05-13-shareable-html-report/verify-report.md` | Audit trail |
| `openspec/archive/2026-05-13-shareable-html-report/archive-report.md` | This file |
| **`openspec/specs/report/spec.md`** | **NEW** source-of-truth spec for `report` capability |

## Source-of-Truth Sync

No prior `openspec/specs/report/spec.md` existed → delta spec promoted to v1 main spec. All 11 ADDED requirements from the delta are now the canonical contract for the `report` capability:

1. KPI Scoring Section Rendering
2. Comparison vs Targets Table
3. Notebookcheck Annotation Formatter
4. Phase Breakdown Table
5. CSV + JSON Download Buttons
6. p1 + p0.1 Frame-Time Percentile Pills
7. Android Vitals Warning Banner
8. Caveats Footer
9. Single Source for Band Colors
10. Output Size Budget
11. Backward Compatibility

Test files implementing these requirements (cross-reference):

| Requirement | Test File(s) |
|-------------|--------------|
| KPI Scoring Section | `KpiScoreSectionTest`, `KpiSectionsRenderedWithFlagOnTest` |
| Comparison vs Targets | `ComparisonTableTest` |
| Notebookcheck | `NotebookcheckTest` |
| Phase Breakdown | `PhaseBreakdownTest` |
| CSV + JSON Buttons | `DataExportButtonsTest`, `KpiCsvSerializerTest` |
| p1 + p0.1 Pills | `FrameTimePercentilesTest`, `KpiSectionsRenderedWithFlagOnTest` |
| Android Vitals Banner | `AndroidVitalsBannersTest` |
| Caveats Footer | `KpiCaveatsTest` |
| Single Source Band Colors | `KpiBandColorsTest`, `KpiBandColorsSingleSourceTest` |
| Output Size Budget | `KpiReportSizeTest` |
| Backward Compatibility | `KpiSectionsOffByDefaultTest` |

## Files Added

### Main (11 in `core/report/kpi/`)
- `KpiBandColors.kt`, `Notebookcheck.kt`, `FrameTimePercentiles.kt`, `KpiCsvSerializer.kt`,
  `KpiCaveats.kt`, `AndroidVitalsBanners.kt`, `KpiScoreSection.kt`, `ComparisonTable.kt`,
  `PhaseBreakdown.kt`, `DataExportButtons.kt`, `KpiReportCss.kt`

### Tests (13 test files, 66 new tests)
- Unit (10): `KpiBandColorsTest`, `KpiBandColorsSingleSourceTest`, `NotebookcheckTest`,
  `FrameTimePercentilesTest`, `KpiCsvSerializerTest`, `KpiCaveatsTest`,
  `AndroidVitalsBannersTest`, `KpiScoreSectionTest`, `ComparisonTableTest`,
  `PhaseBreakdownTest`, `DataExportButtonsTest`
- Integration (3): `KpiSectionsOffByDefaultTest`, `KpiSectionsRenderedWithFlagOnTest`,
  `KpiReportSizeTest`

### Modified
- `core/kpi/KpiMetadata.kt` — `@Serializable` on 4 data classes (T0.1)
- `report/ReportGenerator.kt` — +3 defaulted params (`kpiReport`, `kpiInternalEnabled`, `kpiTier: String?`) + 6 conditional blocks + 7 injection points (CSS bundle + 6 anchors)

## Build Status (final)
- `./gradlew check`: ✅ GREEN (1m 45s)
- detekt: ✅ CLEAN
- 66 new tests added, 0 legacy tests broken

## Lessons Learned

1. **Byte-equivalence baseline file is fragile**; the two-call normalize-and-compare pattern is more portable. Documented in engram #396 (also linked from apply-progress and verify-report). Useful any time a renderer needs a backward-compatibility guard without locking into a snapshot file format.
2. **Single-source pattern enforced by file-walking grep guard works** — `KpiBandColorsSingleSourceTest` walks `core/report/kpi/*.kt` (except the source-of-truth file) and asserts ZERO hex offenders. This is the CLAUDE.md v4.2.13 anti-duplication rule (`ToolResolver`, `SdkSignatureCatalog`) re-applied to a new subsystem from day one — the lesson held.
3. **Vitals rate thresholds are RATES, not FLOORS** — keep `ANR_RATE_THRESHOLD = 0.47%`, `SLOW_FRAME_THRESHOLD = 25%`, `FROZEN_FRAME_THRESHOLD = 0.1%` separate from `KpiCatalog`. The catalog's `floor` semantics don't match Vitals rate semantics. Mixed sourcing is documented in `AndroidVitalsBanners.kt` with `private const val` + doc citations.
4. **Renderers stay pure** — every helper is `(input) -> String`, tested with substring assertions or golden text fragments. Mirrors `core/conclusions/` and `core/events/` precedent. No headless browser, no JVM rendering harness. Fast tests, deterministic.
5. **Optional params at the END of signature** preserve backward compat without touching any of the 6 legacy report tests. Validated by running existing suites unchanged after the wiring commit.

## Follow-Ups Deferred (NOT done in this change)

- **`AppViewModel` wire-up using `kpi-session-adapter`**: currently the report sections never render in production because `AppViewModel.startCapture` does not yet compute a `KpiScoreReport`. The adapter change (`2026-05-13-kpi-session-adapter`, already archived) provides the `SessionResult → KpiInput → KpiScoreReport` plumbing; what remains is wiring it through the report-generation call site and surfacing the feature flag in Settings UI. Tracked as the next SDD change for this capability.
- **Multi-session comparison view** — out of scope for v1. Requires a different UI surface and a serialized session-pair store.
- **iOS DeviceInfo path verification** — `KpiCaveats` and `renderVitalsBanner` are platform-agnostic by design, but no integration test exercised the iOS code path. Spot-check during the next iOS-touching change.
- **UI toggle for `kpiScoringInternalEnabled`** in Settings screen — deferred per KPI v1 internal-only policy.

## Next Recommended

The next SDD change in flight is the `AppViewModel` wire-up. Open candidates already in `openspec/changes/`:

- `event-segmentation-coverage`
- `gpu-usage-percent`
- `logcat-event-stream`

Pick one, run `sdd-continue` or `sdd-ff` on it. No blockers from this archive.

## Risks Carried Forward

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `KpiSectionsOffByDefaultTest` normalize regex misses a future nondeterministic field | Low | Med | Extend regex when adding new dynamic content; pattern is centralized in the test file. |
| Vitals rate thresholds drift from upstream Google guidance | Low | Low | Constants are commented with doc anchors (§3.1) — easy to audit on Vitals doc updates. |
| `kpiTier: String?` opaque label drifts from `DeviceTier` enum | Low | Low | Renderers treat it as label only; resolution stays caller-side. |

## SDD Cycle Complete

The change has been fully planned, implemented, verified, and archived. The `report` capability now has a written source-of-truth spec at `openspec/specs/report/spec.md`. The change is ready for the next phase: a separate `report-kpi-ui-wireup` (or equivalent) change to surface the new sections in production via `AppViewModel`.
