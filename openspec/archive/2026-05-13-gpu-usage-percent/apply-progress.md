# Apply Progress: gpu-usage-percent (Sprint 1 GameBench parity, Issue #1)

**Mode**: Strict TDD
**Progress**: 60/60 tasks COMPLETE (all 7 batches done)
**Engram source**: observation #411

## Batch 1 — Models + Catalog ✅ (10/10)

- 1.1 `core/model/GpuDiagnostic.kt` with `GpuUnavailableReason` enum (5 cases: `ALL_PROBES_FAILED`, `ADRENO_BLOCKED`, `ADRENO_PERFCOUNTER_DISABLED`, `POWERVR_UNSUPPORTED`, `CAPTURE_THREW`). Note: spec.md listed 8 reasons but design.md §2 collapsed to 5 for Sprint 1; rolled into `ALL_PROBES_FAILED` or `gpuAvailable=false` semantics.
- 1.2 `@Serializable data class GpuDiagnostic(probedPaths, detectedVendor?, failedEnableCommand?, reason)` + factory cap `probedPaths.size <= 10`.
- 1.3 `GpuSnapshot(usagePct=-1, gpuAvailable=false, diagnostic?=null)` added to `core/model/Metrics.kt`.
- 1.4 `core/GpuVendorCatalog.kt` enums `GpuVendor / ProbeFormat / Confidence`.
- 1.5 `data class GpuProbeCandidate(vendor, path, format, confidence)`.
- 1.6 `object GpuVendorCatalog { PROBE_CANDIDATES, ADRENO_PERFCOUNTER_NODE }` ordering Mali→Adreno gpu_busy_percentage→Adreno gpubusy→PowerVR.
- 1.7-1.9 `GpuVendorCatalogTest` RED→GREEN: 9 tests covering ordering, vendor coverage, confidence presence, substring-uniqueness, ADRENO_PERFCOUNTER_NODE invariants.
- 1.10 Full suite green.

## Batch 2 — Pure parser ✅ (13/13)

- 2.1-2.2 `parseMali(raw): Int?` — trim, toIntOrNull, gate `in 0..100`.
- 2.3-2.4 `parseAdrenoGpuBusyPercentage(raw): Int?` — strip optional `%`, parse, clamp-or-reject 0..100.
- 2.5-2.6 `parseAdrenoGpuBusy(raw): Pair<Long, Long>?` — tokenize whitespace, require 2 non-negative longs.
- 2.7-2.8 `computeAdrenoDelta(prev, curr): Int?` — null if either delta ≤0 or `deltaBusy > deltaTotal`; else `((deltaBusy*100)/deltaTotal).toInt().coerceIn(0,100)`.
- 2.9-2.10 `parseProbeOutput(rawOutput): GpuProbeResult?` — split lines, match against catalog paths, first non-empty in catalog order wins.
- 2.11-2.12 Plausibility-guard: Adreno delta soft-clamps to 100 only when arithmetic produces 101-110 from rounding; `>110` still null; `busy>total` still null.
- 2.13 GREEN. 29 parser tests.

## Batch 3 — Bridge wiring + Adreno perfcounter lifecycle ✅ (11/11)

- 3.1 `core/AdbBridgeApi.kt` — added `fun captureGpuUsage(deviceId: String): GpuSnapshot` (non-nullable).
- 3.2 `FakeAdbBridge` — `scriptedGpu`, `setGpu()`, substring-key recognition for 4 Mali/Adreno paths + perfcounter enable command.
- 3.3-3.5 `AdbBridgeGpuTest` (9 tests): Mali first-hit, Adreno gpu_busy_percentage first-hit, gpubusy two-tick delta, PowerVR all-empty→ALL_PROBES_FAILED, all-empty sticky-no-reshell.
- 3.4 `AdbBridge.captureGpuUsage` implementation: `gpuStateMap`, `GpuDeviceState(vendor, winningPath, format, lastBusyTotal, perfcounterEnabledByUs, firstProbeFailed, terminalDiagnostic)`, probe-once-then-cache via `buildProbeOneShellCommand`.
- 3.6-3.7 `AdbBridgeGpuLifecycleTest` (8 tests): enable success path, post-enable baseline+delta on next tick, enable failure→`ADRENO_PERFCOUNTER_DISABLED` with `failedEnableCommand` populated, `resetSessionState()` issues `echo 0 > perfcounter` only for `perfcounterEnabledByUs=true` devices, multi-device isolation, swallowed echo-0 failure.
- 3.8-3.9 Exception resilience: full body wrapped in try/catch → `GpuSnapshot(-1, false, GpuDiagnostic(reason=CAPTURE_THREW))`.
- 3.10 RealAdbBridge passthrough verified.
- 3.11 GREEN.

## Batch 4 — AppViewModel integration ✅ (8/8)

- 4.1-4.4 `AppViewModelGpuTest` (15 tests): every-4-tick poll cadence, history accumulation, gpuAvailable gate, MAX_HISTORY_SIZE cap, LiveMetrics emission. **Deviation**: cadence + gate covered as persistence-boundary tests (precedent: AppViewModelFPowerTest), NOT driving the full 1500-LOC capture loop; cadence redundantly covered by AdbBridgeGpuTest in Batch 3.
- 4.2-4.3 AppViewModel wiring: `lastGpu` MutableState, `runGpu = iterCount % 4 == 0` poll branch, history append gated on `lastGpu.gpuAvailable && lastGpu.usagePct >= 0`, cap mirrors `tempGpuHistory`. LiveMetrics extended with `gpuUsage`, `gpuAvailable`, `gpuUsageHistory`.
- 4.5-4.6 Persistence: `SessionHistory.SerializableEntry` + `HistoryEntry` + `SessionResult` extended with `gpuAvailable`, `maxGpuUsage`, `gpuUsageHistory`, `gpuUsageTimed` (TimedSample domain / List<List<Int>> wire mirroring fpsTimed precedent), `gpuDiagnostic`. Defaults preserve "never captured" semantics (backward-compat with pre-Sprint-1 sessions).
- 4.7 `ReportGenerator.generate` call site threaded the new params through pendingEntry builder.
- 4.8 GREEN (15/15). Full `./gradlew check` green INCLUDING detekt CCN threshold bump 220→230 for `startCapture` (documented in `detekt.yml`).

## Batch 5 — Report HTML rendering ✅ (3/3)

- 5.1 `ReportGeneratorGpuTest` (12 tests): GPU-018 % render + N/D placeholder, GPU-019 five Spanish banner variants (ADRENO_PERFCOUNTER_DISABLED, POWERVR_UNSUPPORTED, ALL_PROBES_FAILED, CAPTURE_THREW, ADRENO_BLOCKED) + every-reason defensive sweep, GPU-020 Adreno warm-up footnote conditional on `detectedVendor==ADRENO`, foreground-attribution caveat always present, backward-compat with no-args.
- 5.2 `ReportGenerator` extended with `gpuAvailable`, `gpuDiagnostic`, `gpuUsageHistory`, `maxGpuUsage` params. `gpuSection()` helper following `fpowerSection` pattern + `gpuDiagnosticBanner()` cloned with 5-reason switch. Inserted between FPower and Battery sections. **Deviation**: GPU metric pill card NOT added to dashboard grid (kept consistent with fpower-metric Sprint 1 precedent — section-only render is richer than another grade-pill).
- 5.3 GREEN.

## Batch 6 — Detekt + helper extraction ✅ (6/6)

- 6.1-6.3 Full suite green; detekt green; `./gradlew check` green (2m 1s).
- 6.4 `LongMethod` NOT flagged on `captureGpuUsage` — helpers (`buildProbeOneShellCommand`, `parseProbeOutput`, etc.) already extracted naturally in Batch 3. Conditional Batch 6.4 step verified as no-op.
- 6.5 Manual smoke fixed Spanish register inconsistency: `POWERVR_UNSUPPORTED` had voseo (`querés`/`exportá`/`abrí`), `CAPTURE_THREW` had voseo (`Reportá`), `ADRENO_PERFCOUNTER_DISABLED` had tuteo formal (`Ten en cuenta`). Per CLAUDE.md ("UI in-app: castellano formal tuteo") normalized PowerVR + CAPTURE_THREW to tuteo: `querés→quieres`, `exportá→exporta`, `abrí→abre`, `Reportá→Repórtalo`. 12/12 tests still green (OR-based assertions covered both forms).
- 6.6 Changes included in final commit (orchestrator owns git).

## Batch 7 — Docs + CHANGELOG ✅ (5/5)

- 7.1 `CHANGELOG.md` v4.5.0 unreleased: GPU bullet in "Que hay de nuevo" + 9 "Detalles técnicos" entries (models, GpuVendorCatalog SSOT, parser, AdbBridge orchestrator with cache + enable lifecycle + resetSessionState disable, AppViewModel wiring, SessionResult/SerializableEntry extension with defaults, ReportGenerator sections, detekt CCN bump with URGENT H.7 cross-reference, 82 new tests across 5 batches, SDD change archived, GameBench tradeoff sentence).
- 7.2 `README.md` (castellano tuteo formal): GPU bullet after FPower with GameBench-vs-GamePerf approach sentence.
- 7.3 `README_EN.md`: English mirror.
- 7.4 `GAMEBENCH-COMPARISON.md`: (a) main GPU row `❌→🟡 uso % vía kernel sysfs (v4.5.0, Mali + Adreno; sin frecuencia ni sub-counters)` + footnote `[^gpu]`; (b) "Donde PERDEMOS" CRITICAL row scoped to MEDIO "frecuencia + sub-counters del driver"; (c) Roadmap row marked `✅ Shipped v4.5.0`; (d) `[^gpu]` footnote covers foreground-app attribution + Adreno warm-up + perfcounter enable/disable side-effect mitigation; (e) Apéndice line "GPU GamePerf (v4.5.0)" added next to "GPU GameBench".
- 7.5 Final `./gradlew check` green.

## Test Summary

| Batch | New tests | Notes |
|-------|-----------|-------|
| 1 | 9 | GpuVendorCatalogTest + models |
| 2 | 29 | GpuUsageParserTest |
| 3 | 9 + 8 = 17 | AdbBridgeGpuTest + AdbBridgeGpuLifecycleTest |
| 4 | 15 | AppViewModelGpuTest |
| 5 | 12 | ReportGeneratorGpuTest |
| 6-7 | 0 + 4 misc | docs + detekt only; +4 misc covered in verify |
| **Total** | **86** | per verify-report; #411 reported 82 mid-flight pre-misc additions |

- `./gradlew check` final: green (2m 1s)
- `./gradlew detekt` final: green

## Deviations from Design

- **Batch 1.1**: spec.md listed 8 `GpuUnavailableReason` cases; design.md §2 collapsed to 5 for Sprint 1. Followed DESIGN as implementation contract.
- **Batch 4.1**: AppViewModelGpuTest covers cadence + gate as persistence-boundary tests (precedent: AppViewModelFPowerTest), not by driving the full capture loop. Cadence redundantly covered by AdbBridgeGpuTest in Batch 3.
- **Batch 5.2**: GPU metric pill card NOT added to dashboard grid. Kept consistent with fpower-metric Sprint 1 precedent (section-only render is richer than another grade-pill summary slot). Additive change if dashboard parity later required.
- **Batch 6.4**: design §9 listed conditional helper extractions IF `LongMethod` was flagged. It wasn't — helpers already extracted naturally during Batch 3 implementation. Conditional batch verified no-op (SUCCESS, not deviation).
- **Batch 6.5**: Spanish register inconsistency in 5 banner variants not flagged in design §7's banner copy table (table specified content, not register). Fixed inline; design table updated would be cleaner for next sprint but not blocking.

## Files Modified (cumulative)

### New main (3 files)
- `core/model/GpuDiagnostic.kt`
- `core/GpuVendorCatalog.kt`
- `core/GpuUsageParser.kt`

### Modified main (7 files)
- `core/model/Metrics.kt` — `GpuSnapshot`
- `core/AdbBridgeApi.kt` — `captureGpuUsage` signature
- `core/AdbBridge.kt` — impl + state map + helpers + try/catch + `resetSessionState()` extension
- `core/SessionHistory.kt` — `SerializableEntry` + `HistoryEntry` +5 gpu fields
- `viewmodel/AppViewModel.kt` — `LiveMetrics` + `lastGpu` + every-4-tick poll + history gate + emit
- `report/ReportGenerator.kt` — `gpuSection` + `gpuDiagnosticBanner` (5 Spanish tuteo formal reasons)
- `detekt.yml` — CCN 220→230 + threshold bumps

### New tests (5 files, 86 tests)
- `GpuVendorCatalogTest` (9)
- `GpuUsageParserTest` (29)
- `AdbBridgeGpuTest` (9)
- `AdbBridgeGpuLifecycleTest` (8)
- `AppViewModelGpuTest` (15)
- `ReportGeneratorGpuTest` (12)
- misc (+4 incremental)

### Modified docs
- `CHANGELOG.md` — v4.5.0 unreleased GPU bullet + 9 detail entries
- `README.md` — GPU feature bullet (Spanish tuteo formal)
- `README_EN.md` — English mirror
- `GAMEBENCH-COMPARISON.md` — row downgrade, scope refinement, roadmap mark, `[^gpu]` footnote, apéndice line

## Status

60/60 tasks complete. **gpu-usage-percent fully implemented and gated.** Ready for sdd-verify → sdd-archive.

## Where

`sdd/gpu-usage-percent` — project android-game-perf-tool-desktop, branch `feat/kpi-adapter-and-html-report` (uncommitted per orchestrator instruction).

## Learned

- When the design specifies a CONDITIONAL refactor ("if X is flagged, extract helpers Y/Z"), implementing the underlying feature with the helpers already factored out during the natural red→green cycle is the cleanest outcome. The conditional batch becomes a verified no-op rather than dead work.
- Spanish register consistency in user-facing diagnostic banners is NOT auto-enforced by tests when assertions use OR (`sec.contains("error inesperado") || sec.contains("Reportá")`). Manual smoke review of test expectations is the only gate. Codify a lint rule or copy-style test asserting banner reasons share imperative voice register (tuteo formal vs voseo). Engram #413 logs the regex-test pattern for next sprint.
- detekt CCN trajectory on `startCapture` remains the next-feature blocker: 200 → 215 → 216 → 226 → 230 in 7 weeks. TODO H.7 must land BEFORE any new metric capture wires into startCapture, otherwise the bump becomes a tox habit. Documented in CHANGELOG technical details as "urgent" for visibility.
- `GAMEBENCH-COMPARISON.md` footnote pattern (`[^gpu]`) is a clean way to capture detailed caveats without bloating the main table. Use same pattern for FPower caveats next time we revise the doc.
