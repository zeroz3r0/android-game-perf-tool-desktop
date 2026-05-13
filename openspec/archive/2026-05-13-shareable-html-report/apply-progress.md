# Apply Progress — shareable-html-report (Block F) — COMPLETE

**Mode**: Strict TDD. Test runner: `./gradlew test` / `./gradlew check`.
**Status**: ✅ 15/15 tasks complete. Final `./gradlew check` GREEN. detekt clean.

## Phase 0 — Foundation ✅
- [x] T0.1 `@Serializable` on KpiScoreReport et al.
- [x] T0.2 `KpiBandColors.kt` — single source.
- [x] T0.3 `Notebookcheck.kt`.

## Phase 1 — Percentiles + CSV ✅
- [x] T1.1 `FrameTimePercentiles.kt`.
- [x] T1.2 `KpiCsvSerializer.kt`.

## Phase 2 — Static-content renderers ✅
- [x] T2.1 `KpiCaveats.kt` — 5 tests GREEN.
- [x] T2.2 `AndroidVitalsBanners.kt` — 8 tests GREEN.

## Phase 3 — Tabular renderers ✅
- [x] T3.1 `KpiScoreSection.kt` — 5 tests GREEN.
- [x] T3.2 `ComparisonTable.kt` — 5 tests GREEN.
- [x] T3.3 `PhaseBreakdown.kt` — 4 tests GREEN.

## Phase 4 — Export buttons + CSS ✅
- [x] T4.1 `DataExportButtons.kt` — 4 tests GREEN. Wrapper `<div class="kpi-export-buttons">` + base64 CSV/JSON `<a download>` anchors.
- [x] T4.2 `KpiReportCss.kt` — `val KPI_CSS: String` top-level. Band colors via `KpiBandColors.forBand` string templates. No hardcoded hex.

## Phase 5 — ReportGenerator wiring ✅
- [x] T5.1 New defaulted params (`kpiReport`, `kpiInternalEnabled`, `kpiTier: String?`). `KpiSectionsOffByDefaultTest` — 3 tests GREEN (byte-equiv + 2 no-section-ids). Strategy: normalize regex on sessionId + date forms, no fixture file.
- [x] T5.2 Wired 6 sections + CSS behind `if (kpiInternalEnabled && kpiReport != null)`. `KpiSectionsRenderedWithFlagOnTest` — 6 tests GREEN.

## Phase 6 — Size budget + final verify ✅
- [x] **T6.1** `KpiReportSizeTest` — 60s synthetic session (60 fps points, 3600 frame times, ALL 8 phases × 26 KPIs populated, ALL 5 categories) + flag ON → asserts HTML bytes ≤ 5_000_000. PASSED.
- [x] **T6.2** `KpiBandColorsSingleSourceTest` — walks `src/main/.../core/report/kpi/*.kt` (except `KpiBandColors.kt`), greps for `#(10b981|f59e0b|ef4444|22c55e|d97706|dc2626|f97316)` (case-insensitive), asserts ZERO matches. Reads with `Charsets.UTF_8` per CLAUDE.md mojibake lesson. PASSED with no offenders.
- [x] **T6.3** `./gradlew check` ✅ GREEN. detekt ✅ CLEAN. Build time: 1m 45s.

## TDD Cycle Evidence (final run, T4+T5+T6)
| Task | Test File | Layer | Safety Net | RED | GREEN | TRIANGULATE | REFACTOR |
|------|-----------|-------|------------|-----|-------|-------------|----------|
| T4.1 | `DataExportButtonsTest.kt` | Unit | N/A (new) | ✅ compile-fail | ✅ 4/4 | ✅ 4 cases | ➖ |
| T4.2 | (via T5.2 integration) | Unit | N/A (new) | ✅ via T5.2 | ✅ via T5.2 | ➖ struct only | ➖ |
| T5.1 | `KpiSectionsOffByDefaultTest.kt` | Integration | ✅ legacy report tests | ✅ compile-fail | ✅ 3/3 | ✅ 3 cases (after footer regex fix) | ➖ |
| T5.2 | `KpiSectionsRenderedWithFlagOnTest.kt` | Integration | N/A (new) | ✅ written | ✅ 6/6 | ✅ 6 cases | ➖ |
| T6.1 | `KpiReportSizeTest.kt` | Integration | N/A (new) | ✅ written | ✅ 1/1 | ➖ single budget | ➖ |
| T6.2 | `KpiBandColorsSingleSourceTest.kt` | Unit (FS walk) | N/A (new) | ✅ written | ✅ 1/1 | ➖ single regex | ➖ |

## Files Changed (final cumulative count)
### New source files (11 in `core/report/kpi/`)
- `KpiBandColors.kt`, `Notebookcheck.kt`, `FrameTimePercentiles.kt`, `KpiCsvSerializer.kt`,
  `KpiCaveats.kt`, `AndroidVitalsBanners.kt`, `KpiScoreSection.kt`, `ComparisonTable.kt`,
  `PhaseBreakdown.kt`, `DataExportButtons.kt`, `KpiReportCss.kt`

### New test files (12 total — 9 unit + 3 integration)
- Unit: `KpiBandColorsTest`, `KpiBandColorsSingleSourceTest`, `NotebookcheckTest`,
  `FrameTimePercentilesTest`, `KpiCsvSerializerTest`, `KpiCaveatsTest`,
  `AndroidVitalsBannersTest`, `KpiScoreSectionTest`, `ComparisonTableTest`,
  `PhaseBreakdownTest`, `DataExportButtonsTest`
- Integration: `KpiSectionsOffByDefaultTest`, `KpiSectionsRenderedWithFlagOnTest`,
  `KpiReportSizeTest`

### Modified source files
- `core/kpi/KpiMetadata.kt` — `@Serializable` on `KpiScore`/`PhaseScore`/`CategoryScore`/`KpiScoreReport`
- `report/ReportGenerator.kt` — +3 defaulted params (`kpiReport`, `kpiInternalEnabled`, `kpiTier: String?`) + 6 conditional pre-computed HTML blocks + 7 injection points (CSS bundle + 6 section anchors)

## Build Status (final)
- `./gradlew check`: ✅ GREEN (1m 45s)
- detekt: ✅ CLEAN
- New tests cumulative: 65+ across the change
- ALL legacy `ReportGenerator*` tests pass unchanged (`ReportRenderingTest`, `ReportGradingTest`, `ReportGeneratorCpuDualTest`, `ReportGeneratorFPowerTest`, `ReportGeneratorDevActionBriefTest`, `ReportThermalAvailabilityRenderingTest`)

## Discoveries / Gotchas (cumulative across all 3 runs)
- **kpi-category-card substring collision** — wrapper `kpi-category-cards` (plural) trips substring counts; use `Regex("kpi-category-card(?!s)")` negative lookahead in tests, or rename. Kept plural for semantic clarity, fixed test regex.
- **Floating-point noise in delta** — `16.67 - 20.0 = -3.3299999999999983`. Format with `fmtUS("%.2f", ...)` everywhere a numeric cell is rendered.
- **Phase enum order ≠ intuition** — declaration order is APP_STARTUP, CINEMATIC, TUTORIAL, LEVEL_LOADING, SCREEN_NAV, INTERSTITIAL_AD(5), REWARDED_AD, GAMEPLAY(7).
- **Vitals thresholds — mixed source** — COLD_START uses `KpiCatalog.floor` (5000 ms), but ANR rate / slow-frames / frozen-frames use Vitals' published "bad" rates (0.47%, 25%, 0.1%) from docs §3.1. Kept as `private const val` with citations.
- **Byte-equivalence WITHOUT fixture file**: orchestrator's hint about fragility was on-target. Two-call normalize-and-compare beats fixture file: zero filesystem coupling, regenerates automatically on any future template change. Regex must catch ALL date forms — initial regex missed `HH:mm:ss` footer form, took one re-run to fix.
- **`kpiTier: String?` vs `DeviceTier?`**: tasks.md hinted at `DeviceTier?` but existing `renderCaveats(deviceTier: String)` already takes a string AND existing `deviceTier: String` param is plain string. Stayed consistent — passed `kpiTier ?: deviceTier` to `renderCaveats`.
- **`KpiScoreReport.serializer()` explicit form**: detekt-cleaner than reified `Json.encodeToString<T>(value)`. Same for decoding in the test.
- **CSS string template with top-level `val`s**: `private val GREEN = KpiBandColors.forBand(Band.GREEN)` at file-top-level → then `val KPI_CSS = "...$GREEN..."`. Compiles to one-time init. Grep guard finds ZERO hex offenders.
- **`${'$'}{X}` Kotlin syntax pitfall**: when wanting a literal `$` in a triple-quoted string, the standard Kotlin escape is `${'$'}`. Avoid it where the variable IS the value you want — simply use `$X` directly (as in `${AMBER}15` for the alpha-suffix trick).

## Next Recommended
**sdd-verify** — all 15 tasks complete, gradle check green. Spec acceptance ready.
