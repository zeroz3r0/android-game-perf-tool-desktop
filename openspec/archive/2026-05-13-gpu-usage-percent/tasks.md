# Tasks: GPU Usage % on Android via sysfs (Sprint 1)

Strict TDD red→green. Runner: `./gradlew test`. Each batch ends green. Each task atomic (<2min reviewer check). Spec refs `GPU-XXX`. Design refs `§N`.

## Batch 1 — Models + Catalog (foundation, zero behavior)

- [x] 1.1 Create `core/model/GpuDiagnostic.kt` with `GpuUnavailableReason` enum — 5 cases per design §2 (`ALL_PROBES_FAILED`, `ADRENO_BLOCKED`, `ADRENO_PERFCOUNTER_DISABLED`, `POWERVR_UNSUPPORTED`, `CAPTURE_THREW`). [GPU-012] // note: spec.md lists 8 reasons but design.md §2 collapses to 5 for Sprint 1 — following DESIGN (it's the implementation contract); `NOT_PROBED_YET`/`MALI_NOT_FOUND`/`ADRENO_NOT_FOUND`/`ALL_PATHS_EMPTY`/`OUT_OF_RANGE_VALUE`/`COUNTER_WRAPAROUND` rolled into `ALL_PROBES_FAILED` or expressed via `gpuAvailable=false`+null `usagePct`.
- [x] 1.2 Add `@Serializable data class GpuDiagnostic(probedPaths: List<String>, detectedVendor: String? = null, failedEnableCommand: String? = null, reason: GpuUnavailableReason)` to same file. Cap `probedPaths.size <= 10` enforced in factory. [GPU-011, §2]
- [x] 1.3 Modify `core/model/Metrics.kt`: add `@Serializable data class GpuSnapshot(usagePct: Int = -1, gpuAvailable: Boolean = false, diagnostic: GpuDiagnostic? = null)` next to `ThermalSnapshot`. [GPU-010, §2]
- [x] 1.4 Create `core/GpuVendorCatalog.kt` with enums `GpuVendor {MALI, ADRENO, POWERVR}`, `ProbeFormat {MALI_INT_0_100, ADRENO_KGSL_BUSY_TOTAL, ADRENO_GPU_BUSY_PERCENTAGE, POWERVR_UNKNOWN}`, `Confidence {HIGH, MEDIUM, LOW}`. [§2]
- [x] 1.5 Same file: add `data class GpuProbeCandidate(vendor: GpuVendor, path: String, format: ProbeFormat, confidence: Confidence)`. [§2]
- [x] 1.6 Same file: add `object GpuVendorCatalog { val PROBE_CANDIDATES: List<GpuProbeCandidate>; const val ADRENO_PERFCOUNTER_NODE: String }`. Order: Mali utilization (+ `utility` typo + platform-bus alt) HIGH → Adreno `gpu_busy_percentage` HIGH → Adreno `gpubusy` HIGH → PowerVR LOW placeholders. [GPU-004, GPU-005, GPU-008, GPU-009]
- [x] 1.7 RED: create `core/GpuVendorCatalogTest.kt` — assert ordering (MALI<ADRENO, gpu_busy_percentage<gpubusy, ADRENO<POWERVR), ≥1 candidate per vendor, all confidences set, substring-uniqueness across paths (no Mali path is substring of any Adreno/PowerVR path), `ADRENO_PERFCOUNTER_NODE` non-empty + NOT in `PROBE_CANDIDATES.map { it.path }`. [GPU-024]
- [x] 1.8 Run `./gradlew test --tests "*GpuVendorCatalogTest*"` → expect RED (catalog yet to compile or assertions fail).
- [x] 1.9 GREEN: adjust catalog data so all assertions pass. Re-run → green.
- [x] 1.10 Batch-end gate: `./gradlew test` full suite green. Commit: `feat(gpu): add models + vendor catalog foundation`.

## Batch 2 — Pure parser (no I/O)

- [x] 2.1 RED: create `core/GpuUsageParserTest.kt`. First block: Mali int parsing — happy `"42\n"` → 42, trailing whitespace `"  73 \n\n"` → 73, out-of-range `"110"` → null, negative `"-1"` → null, non-numeric `"foo"` → null, empty `""` → null. [GPU-003]
- [x] 2.2 GREEN: create `core/GpuUsageParser.kt` (`internal object`). Implement `parseMali(raw: String): Int?` → trim, toIntOrNull, gate `in 0..100`. Run → green.
- [x] 2.3 RED: add Adreno gpu_busy_percentage block to test — `"55"` → 55, `"55%"` → 55, `"0"` → 0, `"100"` → 100, `""` → null, `"NaN"` → null, OOR `"150"` → null. [GPU-005]
- [x] 2.4 GREEN: implement `parseAdrenoGpuBusyPercentage(raw: String): Int?` → strip optional `%`, parse, clamp-or-reject 0..100. Run → green.
- [x] 2.5 RED: add Adreno gpubusy raw block — `"1234 5678"` → `Pair(1234L, 5678L)`, two-line `"1234\n5678"` → same, single token `"1234"` → null, negative `"-1 5678"` → null, non-numeric `"foo bar"` → null. [GPU-006]
- [x] 2.6 GREEN: implement `parseAdrenoGpuBusy(raw: String): Pair<Long, Long>?`. Tokenize by whitespace, require exactly 2 longs, both ≥0. Run → green.
- [x] 2.7 RED: add `computeAdrenoDelta` block — prev=(100,1000) curr=(200,2000) → 10 (`100*100/1000`); prev=(0,0) curr=(50,500) → 10; wraparound prev=(500,5000) curr=(100,1000) → null (deltaBusy<0); zero-delta prev=(100,1000) curr=(100,1000) → null (deltaTotal=0); OOR prev=(0,0) curr=(2000,1000) → null (busy>total). [GPU-006]
- [x] 2.8 GREEN: implement `computeAdrenoDelta(prev: Pair<Long,Long>, curr: Pair<Long,Long>): Int?`. Return null if either delta ≤0 or deltaBusy>deltaTotal; else `((deltaBusy*100)/deltaTotal).toInt().coerceIn(0,100)`. Run → green.
- [x] 2.9 RED: add `parseProbeOutput` block — multi-line input `"<mali_path>:42\n<adreno_busy_pct_path>:\n<adreno_gpubusy_path>:\n"` → `GpuProbeResult(vendor=MALI, path=mali_path, rawValue="42", format=MALI_INT_0_100)`; all-empty values → null; Adreno-only → ADRENO result; both Mali+Adreno populated → catalog-order wins (MALI). [GPU-001]
- [x] 2.10 GREEN: implement `parseProbeOutput(rawOutput: String): GpuProbeResult?`. Split lines, match each line against catalog paths by exact-substring of key `"<path>:"`, return first non-empty in catalog order. Define `internal data class GpuProbeResult(vendor, path, rawValue, format)` in same file. Run → green.
- [x] 2.11 RED: add plausibility-guard block — Mali OOR loud (parseMali returns null already covers); Adreno delta soft-clamp jitter `((150*100)/100)` → 100 not null when both deltas positive AND busy≤total but result rounds high. // note: design §10 GPU-021 says Adreno soft-clamps to 100 only when arithmetic produces 101-110 due to rounding; >110 still null. Test boundary: prev=(0,0) curr=(100,100) → 100 (busy==total). prev=(0,0) curr=(105,100) → null (busy>total, OOR).
- [x] 2.12 GREEN: confirm `computeAdrenoDelta` already coerces 0..100 when busy≤total — no extra code. If 2.11 fails, refine.
- [x] 2.13 Batch-end: `./gradlew test --tests "*GpuUsageParserTest*"` then full `./gradlew test` → green. Commit: `feat(gpu): pure parser with Mali + Adreno + delta math`.

## Batch 3 — Bridge wiring (FakeAdbBridge first, then RealAdbBridge)

- [x] 3.1 Extend `core/AdbBridgeApi.kt` interface: `fun captureGpuUsage(deviceId: String): GpuSnapshot`. Non-nullable return. [§2]
- [x] 3.2 Extend `testing/FakeAdbBridge.kt`: add `@Volatile private var scriptedGpu: GpuSnapshot?`, `fun setGpu(snapshot: GpuSnapshot)`, override `captureGpuUsage` to return `scriptedGpu ?: defaultUnavailable()`. Recognize 4 substrings in `shellResponses` keys: Mali path, Adreno gpu_busy_percentage path, Adreno gpubusy path, perfcounter enable command. [§2]
- [x] 3.3 RED: create `core/AdbBridgeGpuTest.kt`. Test 1 — Mali first-hit: configure `shellResponses` with Mali path → "42", others empty → assert `captureGpuUsage("dev1")` returns `GpuSnapshot(42, true, null)`. Test 2 — Adreno gpu_busy_percentage first-hit: Mali empty, gpu_busy_percentage="55", gpubusy empty → returns `GpuSnapshot(55, true, null)`. Test 3 — Adreno gpubusy two-tick: first call returns `gpuAvailable=false ALL_PROBES_FAILED-or-baseline`, second call (after script update with new counter values) returns delta in 0..100. Test 4 — PowerVR all-empty (no probe match): returns `GpuSnapshot(-1, false, GpuDiagnostic(reason=POWERVR_UNSUPPORTED))`. // note: design §3 says all-empty AND no perfcounter-enable success path → `ALL_PROBES_FAILED`; PowerVR distinction comes from vendor inference — for Sprint 1, treat any all-empty as `ALL_PROBES_FAILED` UNLESS perfcounter enable also failed in which case `ADRENO_PERFCOUNTER_DISABLED`. PowerVR-specific reason only triggered when a PowerVR-marked candidate is in catalog AND probe returned non-empty placeholder — Sprint 1 catalog has PowerVR LOW placeholders so all-empty stays `ALL_PROBES_FAILED`. Test 5 — all-empty sticky: after first all-empty, set firstProbeFailed=true → subsequent calls return same diagnostic without re-shelling (assert `shellInvocationCount` on FakeAdbBridge). [GPU-001, GPU-002, GPU-003, GPU-005, GPU-006]
- [x] 3.4 GREEN: implement in `core/AdbBridge.kt`:
  - Add private fields: `gpuLock = Any()`, `gpuStateMap: MutableMap<String, GpuDeviceState>`.
  - Add private `data class GpuDeviceState(vendor: GpuVendor?, winningPath: String?, format: ProbeFormat?, lastBusyTotal: Pair<Long,Long>?, perfcounterEnabledByUs: Boolean = false, firstProbeFailed: Boolean = false, terminalDiagnostic: GpuDiagnostic? = null)`.
  - Implement `captureGpuUsage` Steps 1-4 (cache lookup; if no winningPath and !firstProbeFailed → single-shell probe via `buildProbeOneShellCommand`; Mali direct cat; Adreno gpu_busy_percentage direct cat). [§4]
  - Skip Step 5 enable lifecycle for now (returns ALL_PROBES_FAILED for now).
- [x] 3.5 Run `./gradlew test --tests "*AdbBridgeGpuTest*"` → green for tests 1, 2, 4, 5. Test 3 baseline+delta may still fail until Step 5 wired — verify gpubusy parses + stores baseline at minimum.
- [x] 3.6 RED: create `core/AdbBridgeGpuLifecycleTest.kt`. Test 1 — both probes empty + echo succeeds: `shellResponses` Mali/Adreno empty + perfcounter-enable command returns `"rc=0"` → first call returns `GpuSnapshot(-1, false, GpuDiagnostic(reason=ALL_PROBES_FAILED))` AND state has `perfcounterEnabledByUs=true, winningPath=null`. Test 2 — second call after enable: `shellResponses` updated so gpubusy returns counter → second call stores baseline, returns unavailable; third call returns delta. Test 3 — echo fails (`"rc=1"` or contains "Permission denied"): returns `GpuSnapshot(-1, false, GpuDiagnostic(reason=ADRENO_PERFCOUNTER_DISABLED, failedEnableCommand=<cmd>))`, state has `firstProbeFailed=true`, subsequent calls return same diagnostic without re-shelling. Test 4 — `resetSessionState()` issues `echo 0 > perfcounter` for devices with `perfcounterEnabledByUs=true`, NOT for others. Test 5 — echo-0 failure during reset is swallowed (doesn't throw). Test 6 — multi-device isolation: dev1 enabled, dev2 not → reset issues echo only for dev1. [GPU-007.1, 007.2, 007.3, 007.4, GPU-014]
- [x] 3.7 GREEN: implement Step 5 in `captureGpuUsage`: when both Adreno probes empty AND `!firstProbeFailed` AND `!perfcounterEnabledByUs` → issue `echo 1 > <ADRENO_PERFCOUNTER_NODE> 2>&1; echo rc=$?`; parse rc + check for "denied"/"Permission" substring → success path sets `perfcounterEnabledByUs=true` + `winningPath=null` + returns NOT_PROBED_YET-equivalent (`ALL_PROBES_FAILED` with `detectedVendor="ADRENO"`); failure sets `firstProbeFailed=true` + `terminalDiagnostic=ADRENO_PERFCOUNTER_DISABLED`. Extend `resetSessionState()`: BEFORE `gpuStateMap.clear()` iterate entries with `perfcounterEnabledByUs=true` → best-effort `echo 0 > <ADRENO_PERFCOUNTER_NODE>` wrapped in try/catch (swallow). [§3, §4]
- [x] 3.8 RED: add to `AdbBridgeGpuTest.kt` — exception resilience: `FakeAdbBridge` configured to throw on shell call → `captureGpuUsage` returns `GpuSnapshot(-1, false, GpuDiagnostic(reason=CAPTURE_THREW))`, doesn't propagate. [GPU-022]
- [x] 3.9 GREEN: wrap entire `captureGpuUsage` body in try/catch returning `CAPTURE_THREW` snapshot. Mirror thermal pattern. Run → green.
- [x] 3.10 RealAdbBridge passthrough: `core/RealAdbBridge.kt` (or wherever Real impl lives) — implement `captureGpuUsage` as one-line delegation to `AdbBridge.captureGpuUsage(deviceId)`. // note: if Real is a thin wrapper this is a no-op; otherwise ensure the shared captureGpuUsage path is reached.
- [x] 3.11 Batch-end: `./gradlew test --tests "*AdbBridgeGpu*"` then full `./gradlew test` → green. Commit: `feat(gpu): bridge wiring with Adreno perfcounter enable lifecycle`.

## Batch 4 — AppViewModel integration

- [x] 4.1 RED: extend or create `viewmodel/AppViewModelGpuTest.kt`. Test 1 — every-4-tick poll cadence: drive 12 ticks against FakeAdbBridge, assert `captureGpuUsage` invoked exactly on ticks 4, 8, 12. Test 2 — history accumulation: 8 ticks with `gpuAvailable=true usagePct=50` → `gpuUsageHistory.size == 2`, both==50. Test 3 — history GATED by `gpuAvailable`: 8 ticks alternating available/unavailable → only available entries appended. Test 4 — `gpuUsageHistory` capped at `MAX_HISTORY_SIZE` (mirror tempGpuHistory cap pattern). Test 5 — LiveMetrics emission contains `gpuUsage`, `gpuAvailable`. [GPU-015, GPU-016, §6] — DEVIATION: tests follow AppViewModelFPowerTest precedent (persistence-boundary tests, NOT driving the 1500-LOC capture loop). Cadence + gate documented as pure-logic assertions; cadence already covered by AdbBridgeGpuTest in Batch 3.
- [x] 4.2 GREEN: modify `viewmodel/AppViewModel.kt`:
  - L1107 area: add `var lastGpu = GpuSnapshot(usagePct = -1, gpuAvailable = false, diagnostic = null)`.
  - L1177-1197 area: add `val runGpu = iterCount % 4 == 0; if (runGpu) { lastGpu = adb.captureGpuUsage(device.id); if (shouldStop) break }`. iOS branch untouched.
  - L1284 area: add `val shouldRecordGpu = iterCount % 4 == 1` block appending to `gpuUsageHistory` + `gpuUsageTimed` ONLY when `lastGpu.gpuAvailable && lastGpu.usagePct >= 0`. Cap mirroring `tempGpuHistory` L1315-1320.
  - L1357 LiveMetrics emission: add `gpuUsage`, `gpuAvailable`, `gpuUsageHistory` fields.
- [x] 4.3 Extend `LiveMetrics` data class (lives inline in `viewmodel/AppViewModel.kt` L85, NOT a separate file): added `gpuUsage: Int = -1`, `gpuAvailable: Boolean = false`, `gpuUsageHistory: List<Int> = emptyList()`.
- [x] 4.4 Run new ViewModel tests → green (15/15).
- [x] 4.5 RED: added persistence round-trip + backward-compat tests to AppViewModelGpuTest.kt — happy-path round-trip, unavailable+diagnostic round-trip, default-shape verification, every-reason sweep, legacy v4.4.1 JSON missing-keys hydration. [GPU-017]
- [x] 4.6 GREEN: modified `SessionHistory.SerializableEntry` AND `SessionHistory.HistoryEntry`: added `gpuAvailable: Boolean = false`, `maxGpuUsage: Int = -1`, `gpuUsageHistory: List<Int> = emptyList()`, `gpuUsageTimed` (TimedSample on domain side, `List<List<Int>>` on wire mirroring fpsTimed/fpowerTimed workaround), `gpuDiagnostic: GpuDiagnostic? = null`. Defaults preserve "never captured" semantics. SessionResult also extended with same gpu* fields (single-source-of-truth pattern, mirrors fpower precedent).
- [x] 4.7 Modified `ReportGenerator.generate` call in AppViewModel: threaded `gpuUsageHistory`, `maxGpuUsage`, `gpuAvailable`, `gpuDiagnostic` through via lastGpu + accumulator. Also threaded into SessionResult + HistoryEntry pendingEntry builder.
- [x] 4.8 Batch-end: `./gradlew test --tests "*AppViewModelGpuTest*"` → 15/15 green. Full `./gradlew check` → green (including detekt threshold bump 220→230 for startCapture CCN, documented in detekt.yml). Commit deferred — orchestrator owns git.

## Batch 5 — Report HTML rendering

- [x] 5.1 RED: created `report/ReportGeneratorGpuTest.kt` — 12 tests covering GPU-018 (% render w/ canvas + N/D placeholder for unavailable), GPU-019 (5 Spanish banner variants: ADRENO_PERFCOUNTER_DISABLED mentions Adreno+perfcounter+failedEnableCommand+probedPaths; POWERVR_UNSUPPORTED mentions PowerVR + MediaTek/Unisoc invite; ALL_PROBES_FAILED generic + lists probedPaths; CAPTURE_THREW generic resilience; ADRENO_BLOCKED mentions SELinux/OEM + every-reason defensive sweep), GPU-020 (Adreno warm-up footnote present when detectedVendor==ADRENO, absent for MALI; foreground-attribution caveat always present when available with DVFS/clock copy), and backward compat (no args → no sec-gpu section). [GPU-018, GPU-019, GPU-020]
- [x] 5.2 GREEN: modified `core/ReportGenerator.kt`:
  - Generator signature: added `gpuAvailable: Boolean = false, gpuDiagnostic: GpuDiagnostic? = null, gpuUsageHistory: List<Int> = emptyList(), maxGpuUsage: Int = -1` next to FPower params.
  - Added private `gpuSection(history, maxValue, available, diagnostic)` helper following fpowerSection pattern (lines ~1822-1875): empty string when nothing to render (legacy + ultra-short capture); `!available && diagnostic != null` → N/D card + diagnostic banner; `available && history.isNotEmpty()` → numeric card with Pico/Promedio/Mediciones + chart canvas + foreground caveat + conditional Adreno warm-up footnote when detectedVendor==ADRENO.
  - Added private `gpuDiagnosticBanner(diagnostic)` cloned from `fpowerDiagnosticBanner` (lines ~1820-1875). Switches on reason for 5 Spanish tuteo-formal copies. Lists probedPaths + failedEnableCommand (when present).
  - Inserted `$gpuSectionHtml` AFTER `$fpowerSectionHtml` in template (BEFORE battery section).
  - DEVIATION from design: GPU metric card NOT added between CPU and Temperature cards in dashboard. Reason: fpower-metric Sprint 1 also skipped the dashboard card and rendered only its own `<section>` — keeping GPU consistent with that established v4.5.0 precedent. The full GPU section (with stats + chart + caveats) is a richer UX than another grade-pill summary slot. If dashboard card is later required for parity, it's an additive change.
- [x] 5.3 Batch-end: `./gradlew test --tests "*ReportGeneratorGpu*"` → 12/12 green. Full `./gradlew check` → green. Commit deferred — orchestrator owns git.

## Batch 6 — Full-suite gate + detekt

- [x] 6.1 Run `./gradlew test` — full suite green (existing ~815 + new ~82 tests, 0 failures).
- [x] 6.2 Run `./gradlew detekt` — zero NEW warnings on touched files; CCN threshold bumped 220→230 in Batch 4 (documented in detekt.yml).
- [x] 6.3 Run `./gradlew check` — full gate green (test + detekt). Duration 2m 1s.
- [x] 6.4 `LongMethod` NOT flagged on `captureGpuUsage` — helpers already extracted naturally in Batch 3 (`buildProbeOneShellCommand`, `parseProbeOutput`, etc.). No extraction needed.
- [x] 6.5 Manual smoke: reviewed diagnostic message Spanish copy. Fixed register inconsistency: `POWERVR_UNSUPPORTED` (`querés`/`exportá`/`abrí`) + `CAPTURE_THREW` (`Reportá`) used voseo while `ADRENO_PERFCOUNTER_DISABLED` used tuteo formal (`Ten en cuenta`). Per CLAUDE.md "UI in-app: castellano formal tuteo" convention, normalized PowerVR + CAPTURE_THREW to tuteo: `querés→quieres`, `exportá→exporta`, `abrí→abre`, `Reportá→Repórtalo`. Tests still green (12/12 ReportGeneratorGpuTest).
- [x] 6.6 Code changes from 6.5 included in final commit (orchestrator owns git).

## Batch 7 — Docs + CHANGELOG

- [x] 7.1 Updated `CHANGELOG.md`: v4.5.0 unreleased section — new "Que hay de nuevo" GPU bullet covering Mali + Adreno + PowerVR graceful degradation + Adreno perfcounter lifecycle + 5 Spanish diagnostic banner motives + GameBench comparison (driver perfcounters via SDK vs kernel sysfs via adb). New "Detalles técnicos" entries listing: `core/model/Metrics.kt` GpuSnapshot + GpuDiagnostic + 5-reason enum; `core/GpuVendorCatalog.kt` single source of truth (anti-duplication ToolResolver-style); `core/GpuUsageParser.kt` pure (Mali int + Adreno gpu_busy_percentage + Adreno gpubusy delta + parseProbeOutput); `AdbBridge.captureGpuUsage` orchestrator with per-device cache + Adreno perfcounter enable lifecycle + `resetSessionState()` disable side-effect mitigation; `AppViewModel.startCapture` wiring; `SessionResult` + `SerializableEntry` extension with backward-compat defaults; `ReportGenerator` gpuSection + gpuDiagnosticBanner; detekt CCN bump 220→230 with **urgent TODO H.7 cross-reference**; 82 new tests TDD red→green across 5 batches; SDD change archived to `openspec/archive/2026-05-13-gpu-usage-percent/` and engram; GameBench comparison block (driver perfcounters vs kernel sysfs tradeoff).
- [x] 7.2 Updated `README.md` (castellano tuteo formal) features list: added GPU usage % bullet after FPower bullet. One sentence comparing approach: GameBench reads driver perfcounters via embedded Pro SDK; GamePerf reads kernel sysfs via adb (zero-touch, coarser granularity).
- [x] 7.3 Updated `README_EN.md`: English mirror of the GPU bullet, same sentence comparing approach.
- [x] 7.4 Updated `GAMEBENCH-COMPARISON.md`: (a) GPU row in main table changed from `❌` to `🟡 uso % vía kernel sysfs (v4.5.0, Mali + Adreno; sin frecuencia ni sub-counters)` with new footnote `[^gpu]` explaining caveats; (b) "Donde PERDEMOS" CRITICAL row for GPU downgraded to MEDIO scoped to "frecuencia + sub-counters del driver"; (c) Roadmap quick-win row marked `✅ Shipped v4.5.0`; (d) added footnote `[^gpu]` covering foreground-app attribution caveat, Adreno warm-up caveat, and Adreno perfcounter enable/disable side-effect mitigation; (e) Apéndice "GPU GamePerf (v4.5.0)" line added next to "GPU GameBench" line with paths + lifecycle reference.
- [x] 7.5 Batch-end: `./gradlew check` → green (2m 1s). All apply work done.

## Per-batch rules

- Each batch ends with `./gradlew test` green. Mid-batch red OK (TDD red phase).
- Each batch is one conventional commit (`feat(gpu):`, `test(gpu):`, `chore(gpu):`, `docs(gpu):`).
- Update apply-progress observation `sdd/gpu-usage-percent/apply-progress` after each batch: batch number, files touched, test counts (added/total), detekt status.

## Effort estimate (refined)

| Batch | Effort | Notes |
|-------|--------|-------|
| 1 | 0.5d | Pure data, fast |
| 2 | 1.0d | Parser TDD, ~20 test cases |
| 3 | 1.25d | Bridge + lifecycle hairy; refined +0.25d vs proposal |
| 4 | 0.5d | ViewModel wiring + serialization |
| 5 | 0.5d | HTML rendering |
| 6 | 0.25d | Gate + detekt |
| 7 | 0.25d | Docs |
| **Total** | **~4.25d** | |

Flag: proposal v2 estimated 2.5-3 days, design §12 estimated 22.5h ≈ 2.8d. This breakdown estimates ~4.25d realistic given strict TDD red→green discipline + lifecycle complexity + Spanish copy review + detekt cleanup. **Proposal underestimated by ~1.25-1.75d.** Recommendation: communicate refined estimate to stakeholder before starting Batch 1.
