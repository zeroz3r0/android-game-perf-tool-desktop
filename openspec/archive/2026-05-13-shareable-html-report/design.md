# Design: Shareable HTML Report Extensions (Issue #2 Block F)

## Technical Approach

Extend the existing `ReportGenerator.generate(...)` with **3 new optional parameters** defaulted so every legacy caller (and every pre-v4.6 `.gameperf` re-render) stays byte-equivalent. New rendering lives in a new pure-functional package `core/report/kpi/`, mirroring the `core/conclusions/` and `core/events/` layout already accepted in v4.4.0. All helpers are `(input) -> String`, tested via golden HTML fixtures (no headless browser needed). The feature flag is `Settings.kpiScoringInternalEnabled` — same one already gating `KpiScoringFacade.compute`, so user-facing visibility is bound to a single switch.

## Package Layout (`core/report/kpi/`)

| File | Role |
|------|------|
| `KpiBandColors.kt` | **SINGLE SOURCE** for `Band → #RRGGBB`. Mirrors `SdkSignatureCatalog.ALL` / `KpiCatalog` pattern. Object with `forBand(Band)` + `cssClassFor(Band)` (returns `kpi-band-green` / `-amber` / `-red`). KDoc warning "DO NOT define band hex values outside this file". |
| `KpiScoreSection.kt` | Pure `renderKpiScoreSection(report: KpiScoreReport): String`. Emits `<section id="sec-kpi-scoring">` with overall + per-phase + per-category cards. Delegates band class to `KpiBandColors.cssClassFor`. |
| `ComparisonTable.kt` | Pure `renderComparisonTable(report: KpiScoreReport, catalog: List<Kpi> = KpiCatalog.ALL): String`. Emits a table with cols `KPI / Actual / Target / Delta / Band`. Pulls target from `kpi.thresholds[tier].target` (tier from report context; defaults to MID). Null `rawValue` → cell `N/D` class `kpi-na`. Delegates delta sign to `ComparisonEngine.delta`. |
| `PhaseBreakdown.kt` | Pure `renderPhaseBreakdown(report: KpiScoreReport): String`. One row per `PhaseScore`, ordered by `Phase` enum declaration order. Empty list → `""`. |
| `Notebookcheck.kt` | Pure `Notebookcheck.format(avg, min, max, decimals: Int = 0): String`. Uses `fmtUS(...)` (existing util) for locale-stable decimal point. Returns exact format `Ø<avg> (<min>-<max>)` per §7.3 docs anchor. |
| `AndroidVitalsBanners.kt` | Pure `renderVitalsBanner(report: KpiScoreReport, durationSec: Int): String`. Reads thresholds from `KpiCatalog` (no hardcoded numbers) for `COLD_START_MS`, `ANR_COUNT` (computed as count/duration%), `SLOW_FRAMES`, `FROZEN_FRAMES`. Builds a list of breaches; empty list → `""`. |
| `DataExportButtons.kt` | Pure `renderExportButtons(report: KpiScoreReport, pkg: String): String`. Calls `KpiCsvSerializer.toCsv(report)` + `Json.encodeToString(report)`, base64-encodes both, emits `<a download="kpi_<pkg>.csv" href="data:text/csv;base64,...">` + same for JSON. No `<script>`, no `fetch`. |
| `KpiCsvSerializer.kt` | Pure `toCsv(report: KpiScoreReport): String`. Header `phase,kpi,raw_value,score,delta,band`. Escapes `,` and `"` in values. |
| `FrameTimePercentiles.kt` | Pure `p1(sorted: List<Double>): Double?` + `p01(sorted: List<Double>): Double?`. Returns null when `size < 100` / `< 1000` respectively. Caller renders only when non-null. |
| `KpiCaveats.kt` | Pure `renderCaveats(deviceTier: String): String`. Hardcoded Spanish-tuteo-formal caveat list. Tier fallback: `if (deviceTier.isBlank()) "MID (default)"`. |
| `KpiReportCss.kt` | `const val KPI_CSS: String = "..."`. Appended to existing `CSS` constant when flag ON. All new class names prefixed `kpi-`. |

## ReportGenerator.kt Touch Points

Three new params at the END of the existing list (preserve default-args order for backward compat):

```kotlin
// SDD shareable-html-report Block F (v4.6+) — all defaulted to no-op.
// When `kpiInternalEnabled == false` OR `kpiReport == null` every new
// section short-circuits to "" and the legacy template renders byte-
// equivalent (asserted by KpiSectionsOffByDefaultTest).
kpiReport: KpiScoreReport? = null,
kpiInternalEnabled: Boolean = false,
allFrameTimesP01: List<Double> = emptyList(),   // pre-sorted by caller, OR pass allFrameTimes — see ADR-3
```

Wiring (6 insertion points, each guarded by `if (kpiInternalEnabled && kpiReport != null)`):

| Anchor | Inserted HTML |
|--------|---------------|
| Inside `#sec-summary` after the grade ring | `renderVitalsBanner(...)` (F.9) |
| After `#sec-summary` close | `renderKpiScoreSection(...)` (F.2) + `renderComparisonTable(...)` (F.3) + `renderPhaseBreakdown(...)` (F.5) |
| Inside `#sec-fps` stats row after avg pill | Notebookcheck annotation `Ø<avg> (<min>-<max>)` (F.6) |
| Inside `#sec-frametime` stats row after p99 pill | p1 + p0.1 pills (F.8) |
| `.fab-group` (top-right floating buttons) | CSV + JSON download anchors (F.7) |
| After `#sec-device` close (legacy footer position) | `renderCaveats(deviceTier)` (F.10) |

The `<style>$CSS</style>` block conditionally appends `KPI_CSS` only when flag ON, so legacy output keeps the exact same CSS bytes.

## Data Flow

```
ReportGenerator.generate(... kpiReport, kpiInternalEnabled, allFrameTimesP01)
  │
  ├─ if (!kpiInternalEnabled || kpiReport == null)
  │       → return existing template (byte-equivalent path)
  │
  └─ else (flag ON + report non-null)
        ├─ KpiBandColors.cssClassFor(report.sessionBand)         → overall card
        ├─ renderKpiScoreSection(report)                          → F.2
        ├─ renderComparisonTable(report, KpiCatalog.ALL)          → F.3
        ├─ renderPhaseBreakdown(report)                           → F.5
        ├─ Notebookcheck.format(avgFps, minFps, maxFps)           → F.6
        ├─ renderVitalsBanner(report, duration)                   → F.9
        ├─ FrameTimePercentiles.p1/p01(allFrameTimes.sorted())    → F.8
        ├─ renderExportButtons(report, pkg)                       → F.7
        └─ renderCaveats(deviceTier)                              → F.10
```

## Architecture Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **Extend, not replace** `ReportGenerator.kt` (one file already 860+ lines) | Mirror v4.4.0 / v4.5.0 / v4.5.0-Sprint3 playbook: new optional params + helper extraction. Avoids touching all 6 existing test fixtures. |
| D2 | **All new code pure** in `core/report/kpi/` package | Same pattern as `core/conclusions/`, `core/events/`. Testable without JVM browser. Golden HTML fixtures (string comparison) instead of integration tests. |
| D3 | **Caller pre-sorts `allFrameTimes`** OR we sort lazily | v1: caller passes `allFrameTimes` (already exists), renderer sorts internally. Performance acceptable (1000-3000 doubles for 60s capture). Eliminates a parameter. |
| D4 | **Single source for band colors** (`KpiBandColors.kt`) | CLAUDE.md v4.2.13 / v4.4.0 anti-duplication rule. Grep guard test asserts no hex outside this file. |
| D5 | **Feature flag = same `kpiScoringInternalEnabled` field** | Already exists from KPI scoring v1. No new Settings field. User flips ONE switch to enable scoring math + UI. |
| D6 | **CSV/JSON via base64 data URL**, no Blob/fetch JS | Self-contained per §7.6 + §C.5 user decision. Works offline, works when HTML opened from email attachment, no JS dependency. Tradeoff: doubles size of embedded data (base64 overhead ~33%), still well under 5MB budget for typical session. |
| D7 | **Vitals thresholds read from `KpiCatalog`** | No new constants file. Single source already exists. `AndroidVitalsBanners.kt` looks up `KpiCatalog.byId(COLD_START_MS).thresholds[MID].floor` etc. Doc anchor test guards drift. |
| D8 | **TDD red-green per renderer** | Strict TDD mode active per project standards. Each renderer = one test file = one task. |
| D9 | **`Phase` enum order = display order** in F.5 | Same convention as `KpiId` ordering. Stable, deterministic, golden-fixture friendly. |
| D10 | **`fmtUS(...)` for all decimals** including Notebookcheck `Ø60.5 (...)` | Project convention from v4.2.4 mojibake fix; locale-stable. |

## Interfaces / Contracts

```kotlin
package com.gameperf.desktop.core.report.kpi

object KpiBandColors {
    fun forBand(band: Band): String     // "#22c55e" / "#f59e0b" / "#dc2626"
    fun cssClassFor(band: Band): String  // "kpi-band-green" / "-amber" / "-red"
}

internal fun renderKpiScoreSection(report: KpiScoreReport): String
internal fun renderComparisonTable(report: KpiScoreReport, catalog: List<Kpi> = KpiCatalog.ALL, tier: DeviceTier = DeviceTier.MID): String
internal fun renderPhaseBreakdown(report: KpiScoreReport): String
internal fun renderVitalsBanner(report: KpiScoreReport, durationSec: Int): String
internal fun renderExportButtons(report: KpiScoreReport, pkg: String): String
internal fun renderCaveats(deviceTier: String): String

object Notebookcheck {
    fun format(avg: Number, min: Number, max: Number, decimals: Int = 0): String
}

object FrameTimePercentiles {
    fun p1(samples: List<Double>): Double?    // null if size < 100
    fun p01(samples: List<Double>): Double?   // null if size < 1000
}

object KpiCsvSerializer {
    fun toCsv(report: KpiScoreReport): String
}
```

## Golden Fixture Strategy

`src/test/resources/report-fixtures/kpi/`:

```
kpi-score-section-good.html         # Synthetic report all GREEN
kpi-score-section-mixed.html        # 1 GREEN / 1 AMBER / 1 RED
comparison-table-with-null.html     # null rawValue row shows N/D
phase-breakdown-3-phases.html       # APP_STARTUP + GAMEPLAY + INTERSTITIAL_AD
notebookcheck-int.txt               # "Ø60 (59-61)"
notebookcheck-decimal.txt           # "Ø14.3 (12.1-16.7)"
vitals-banner-coldstart.html
vitals-banner-multi.html            # 3 simultaneous breaches
vitals-banner-empty.html            # all within target (empty string)
caveats-no-tier.html                # deviceTier = "" → "MID (default)"
report-baseline-v45.html            # FULL report with flag OFF — byte-equivalence guard
```

Each test reads its fixture via `getResourceAsStream("/report-fixtures/kpi/<name>")` and asserts string equality with the renderer output.

## Testing Strategy

| Layer | Test Class | What |
|-------|-----------|------|
| Band color source | `KpiBandColorsTest` | 3 bands non-empty distinct hex; grep-style guard test scans `core/report/kpi/` source for hex hits outside KpiBandColors.kt → 0 |
| KPI score section | `KpiScoreSectionTest` | Golden fixture diff for good/mixed/empty reports; section id present; band classes correct |
| Comparison table | `ComparisonTableTest` | Null rawValue → N/D; HIGHER_IS_BETTER sign; LOWER_IS_BETTER sign; cells delegate to ComparisonEngine.delta |
| Phase breakdown | `PhaseBreakdownTest` | Phase order matches enum; empty phases → ""; per-KPI drill-down present |
| Notebookcheck | `NotebookcheckTest` | Integer format exact `"Ø60 (59-61)"`; decimal format with US locale; negative numbers (FrameTime in delta context) |
| Vitals banner | `AndroidVitalsBannersTest` | Cold-start breach; ANR rate computation; slow-frame rate; frozen-frame rate; multi-breach concatenation; no-breach → "" |
| Export buttons | `DataExportButtonsTest` | data-url prefix correct; base64 decodes; CSV header matches spec; JSON kotlinx round-trip equals input |
| CSV serializer | `KpiCsvSerializerTest` | Header line exact; row count matches phase × kpi; comma/quote escaping; band names match enum |
| Frame-time percentiles | `FrameTimePercentilesTest` | p1 with 100 samples returns 99th; p01 with 1000 returns 99.9th; below threshold → null |
| Caveats | `KpiCaveatsTest` | All 3 required substrings present; tier="" → "MID (default)" |
| Integration flag OFF | `KpiSectionsOffByDefaultTest` | Full ReportGenerator.generate with flag=false + null report → byte-equal to baseline-v45.html fixture |
| Integration flag ON | `KpiSectionsRenderedWithFlagOnTest` | Same inputs + flag=true + synthetic report → contains all 6 anchor ids |
| Size budget | `KpiReportSizeTest` | 60s synthetic session output ≤5_000_000 bytes |

TDD order: KpiBandColors → Notebookcheck → FrameTimePercentiles → KpiCsvSerializer → KpiCaveats → KpiScoreSection → ComparisonTable → PhaseBreakdown → AndroidVitalsBanners → DataExportButtons → ReportGenerator wiring (integration tests last).

## Migration / Rollout

Feature flag `Settings.kpiScoringInternalEnabled` already defaults `false`. New params default to safe no-op. No DB / disk schema change. No `.gameperf` history file format change (KpiScoreReport is computed on-demand from `KpiInput`, not persisted in v1 — see KPI framework verify-report deviation D8). Rollback = revert commit; flag stays OFF; users see legacy report. Internal v1 — no UI surfacing the flag yet (deferred per KPI framework Block E decision).

## Risks

| Risk | Mitigation |
|------|------------|
| `KpiSectionsOffByDefaultTest` is fragile (any CSS change breaks the golden) | Baseline regenerated explicitly in test setup via env var `REGENERATE_BASELINE=true`. CI runs without the env var → strict. |
| Embedded base64 CSV/JSON bloats 1-min report past 5MB | Size budget test `KpiReportSizeTest` fails before merge. CSV is small (rows = phases × KPIs ≈ 8 × 27 = 216 lines, ~10KB). |
| New CSS classes clash with existing `.stat-pill` / `.card-badge` | All new classes prefixed `kpi-`; reviewer checklist enforces grep guard |
| Vitals threshold drift between renderer and `KpiCatalog` | `AndroidVitalsBannersTest` asserts banner text matches threshold reads from `KpiCatalog`; no hardcoded `5000` / `0.47` constants in renderer |
| `KpiCatalog` doesn't carry MID tier for some KPI → NPE in renderer | Renderer uses `kpi.thresholds[tier] ?: kpi.thresholds[DeviceTier.MID] ?: kpi.thresholds.values.first()` fallback chain; covered by test |
| `Json.encodeToString<KpiScoreReport>` needs `@Serializable` on all KPI data classes | Verified during apply Phase 0: if missing, add `@Serializable` (additive, no behavior change) |
| Pre-existing report tests (`ReportRenderingTest`, `ReportGradingTest`, `ReportGeneratorCpuDualTest`, etc.) break because of param-list change | New params are at END of signature, all defaulted — existing call sites unchanged. Verified by running `./gradlew check` after wiring |

## Out of Scope

- Calibration (Block G)
- `SessionResult → KpiInput` adapter (separate change)
- UI toggle for `kpiScoringInternalEnabled` (deferred per KPI v1)
- Public/external-facing report (requires legal review — §8 #8)
- Comparison across multiple sessions (separate change)
- Cloud hosting / versioned URLs (§7.6 deferred)
- Customizable section ordering / selection (use feature flag only)
- Ad SDK lab (§8 #9 — separate work)

## Open Questions

- [ ] D3: should the renderer sort `allFrameTimes` once and pass to both p1/p0.1, or expose a precomputed `SortedFrameTimes` wrapper? (v1: sort inside `FrameTimePercentiles.p01`, callers cache externally.)
- [ ] D4: should `KpiBandColors` ALSO own background-fill hex with alpha for bands? (v1: only foreground; renderer composes background via CSS `${color}20` opacity trick like existing template.)
- [ ] D7: should ANR_COUNT threshold be sourced from Vitals' 0.47% (multi-user rate) or 0.27% (perceived stutter rate)? — v1 uses 0.47% per `KpiCatalog`, configurable later.
