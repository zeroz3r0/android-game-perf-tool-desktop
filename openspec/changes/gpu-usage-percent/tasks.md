# Tasks: GPU Usage % on Android via sysfs (Sprint 1)

Strict TDD red→green. Runner: `./gradlew test`. Each batch ends green. Each task atomic (<2min reviewer check). Spec refs `GPU-XXX`. Design refs `§N`.

## Batch 1 — Models + Catalog (foundation, zero behavior)

- [ ] 1.1 Create `core/model/GpuDiagnostic.kt` with `GpuUnavailableReason` enum — 5 cases per design §2 (`ALL_PROBES_FAILED`, `ADRENO_BLOCKED`, `ADRENO_PERFCOUNTER_DISABLED`, `POWERVR_UNSUPPORTED`, `CAPTURE_THREW`). [GPU-012] // note: spec.md lists 8 reasons but design.md §2 collapses to 5 for Sprint 1 — following DESIGN (it's the implementation contract); `NOT_PROBED_YET`/`MALI_NOT_FOUND`/`ADRENO_NOT_FOUND`/`ALL_PATHS_EMPTY`/`OUT_OF_RANGE_VALUE`/`COUNTER_WRAPAROUND` rolled into `ALL_PROBES_FAILED` or expressed via `gpuAvailable=false`+null `usagePct`.
- [ ] 1.2 Add `@Serializable data class GpuDiagnostic(probedPaths: List<String>, detectedVendor: String? = null, failedEnableCommand: String? = null, reason: GpuUnavailableReason)` to same file. Cap `probedPaths.size <= 10` enforced in factory. [GPU-011, §2]
- [ ] 1.3 Modify `core/model/Metrics.kt`: add `@Serializable data class GpuSnapshot(usagePct: Int = -1, gpuAvailable: Boolean = false, diagnostic: GpuDiagnostic? = null)` next to `ThermalSnapshot`. [GPU-010, §2]
- [ ] 1.4 Create `core/GpuVendorCatalog.kt` with enums `GpuVendor {MALI, ADRENO, POWERVR}`, `ProbeFormat {MALI_INT_0_100, ADRENO_KGSL_BUSY_TOTAL, ADRENO_GPU_BUSY_PERCENTAGE, POWERVR_UNKNOWN}`, `Confidence {HIGH, MEDIUM, LOW}`. [§2]
- [ ] 1.5 Same file: add `data class GpuProbeCandidate(vendor: GpuVendor, path: String, format: ProbeFormat, confidence: Confidence)`. [§2]
- [ ] 1.6 Same file: add `object GpuVendorCatalog { val PROBE_CANDIDATES: List<GpuProbeCandidate>; const val ADRENO_PERFCOUNTER_NODE: String }`. Order: Mali utilization (+ `utility` typo + platform-bus alt) HIGH → Adreno `gpu_busy_percentage` HIGH → Adreno `gpubusy` HIGH → PowerVR LOW placeholders. [GPU-004, GPU-005, GPU-008, GPU-009]
- [ ] 1.7 RED: create `core/GpuVendorCatalogTest.kt` — assert ordering (MALI<ADRENO, gpu_busy_percentage<gpubusy, ADRENO<POWERVR), ≥1 candidate per vendor, all confidences set, substring-uniqueness across paths (no Mali path is substring of any Adreno/PowerVR path), `ADRENO_PERFCOUNTER_NODE` non-empty + NOT in `PROBE_CANDIDATES.map { it.path }`. [GPU-024]
- [ ] 1.8 Run `./gradlew test --tests "*GpuVendorCatalogTest*"` → expect RED (catalog yet to compile or assertions fail).
- [ ] 1.9 GREEN: adjust catalog data so all assertions pass. Re-run → green.
- [ ] 1.10 Batch-end gate: `./gradlew test` full suite green. Commit: `feat(gpu): add models + vendor catalog foundation`.

## Batch 2 — Pure parser (no I/O)

- [ ] 2.1 RED: create `core/GpuUsageParserTest.kt`. First block: Mali int parsing — happy `"42\n"` → 42, trailing whitespace `"  73 \n\n"` → 73, out-of-range `"110"` → null, negative `"-1"` → null, non-numeric `"foo"` → null, empty `""` → null. [GPU-003]
- [ ] 2.2 GREEN: create `core/GpuUsageParser.kt` (`internal object`). Implement `parseMali(raw: String): Int?` → trim, toIntOrNull, gate `in 0..100`. Run → green.
- [ ] 2.3 RED: add Adreno gpu_busy_percentage block to test — `"55"` → 55, `"55%"` → 55, `"0"` → 0, `"100"` → 100, `""` → null, `"NaN"` → null, OOR `"150"` → null. [GPU-005]
- [ ] 2.4 GREEN: implement `parseAdrenoGpuBusyPercentage(raw: String): Int?` → strip optional `%`, parse, clamp-or-reject 0..100. Run → green.
- [ ] 2.5 RED: add Adreno gpubusy raw block — `"1234 5678"` → `Pair(1234L, 5678L)`, two-line `"1234\n5678"` → same, single token `"1234"` → null, negative `"-1 5678"` → null, non-numeric `"foo bar"` → null. [GPU-006]
- [ ] 2.6 GREEN: implement `parseAdrenoGpuBusy(raw: String): Pair<Long, Long>?`. Tokenize by whitespace, require exactly 2 longs, both ≥0. Run → green.
- [ ] 2.7 RED: add `computeAdrenoDelta` block — prev=(100,1000) curr=(200,2000) → 10 (`100*100/1000`); prev=(0,0) curr=(50,500) → 10; wraparound prev=(500,5000) curr=(100,1000) → null (deltaBusy<0); zero-delta prev=(100,1000) curr=(100,1000) → null (deltaTotal=0); OOR prev=(0,0) curr=(2000,1000) → null (busy>total). [GPU-006]
- [ ] 2.8 GREEN: implement `computeAdrenoDelta(prev: Pair<Long,Long>, curr: Pair<Long,Long>): Int?`. Return null if either delta ≤0 or deltaBusy>deltaTotal; else `((deltaBusy*100)/deltaTotal).toInt().coerceIn(0,100)`. Run → green.
- [ ] 2.9 RED: add `parseProbeOutput` block — multi-line input `"<mali_path>:42\n<adreno_busy_pct_path>:\n<adreno_gpubusy_path>:\n"` → `GpuProbeResult(vendor=MALI, path=mali_path, rawValue="42", format=MALI_INT_0_100)`; all-empty values → null; Adreno-only → ADRENO result; both Mali+Adreno populated → catalog-order wins (MALI). [GPU-001]
- [ ] 2.10 GREEN: implement `parseProbeOutput(rawOutput: String): GpuProbeResult?`. Split lines, match each line against catalog paths by exact-substring of key `"<path>:"`, return first non-empty in catalog order. Define `internal data class GpuProbeResult(vendor, path, rawValue, format)` in same file. Run → green.
- [ ] 2.11 RED: add plausibility-guard block — Mali OOR loud (parseMali returns null already covers); Adreno delta soft-clamp jitter `((150*100)/100)` → 100 not null when both deltas positive AND busy≤total but result rounds high. // note: design §10 GPU-021 says Adreno soft-clamps to 100 only when arithmetic produces 101-110 due to rounding; >110 still null. Test boundary: prev=(0,0) curr=(100,100) → 100 (busy==total). prev=(0,0) curr=(105,100) → null (busy>total, OOR).
- [ ] 2.12 GREEN: confirm `computeAdrenoDelta` already coerces 0..100 when busy≤total — no extra code. If 2.11 fails, refine.
- [ ] 2.13 Batch-end: `./gradlew test --tests "*GpuUsageParserTest*"` then full `./gradlew test` → green. Commit: `feat(gpu): pure parser with Mali + Adreno + delta math`.

## Batch 3 — Bridge wiring (FakeAdbBridge first, then RealAdbBridge)

- [ ] 3.1 Extend `core/AdbBridgeApi.kt` interface: `fun captureGpuUsage(deviceId: String): GpuSnapshot`. Non-nullable return. [§2]
- [ ] 3.2 Extend `testing/FakeAdbBridge.kt`: add `@Volatile private var scriptedGpu: GpuSnapshot?`, `fun setGpu(snapshot: GpuSnapshot)`, override `captureGpuUsage` to return `scriptedGpu ?: defaultUnavailable()`. Recognize 4 substrings in `shellResponses` keys: Mali path, Adreno gpu_busy_percentage path, Adreno gpubusy path, perfcounter enable command. [§2]
- [ ] 3.3 RED: create `core/AdbBridgeGpuTest.kt`. Test 1 — Mali first-hit: configure `shellResponses` with Mali path → "42", others empty → assert `captureGpuUsage("dev1")` returns `GpuSnapshot(42, true, null)`. Test 2 — Adreno gpu_busy_percentage first-hit: Mali empty, gpu_busy_percentage="55", gpubusy empty → returns `GpuSnapshot(55, true, null)`. Test 3 — Adreno gpubusy two-tick: first call returns `gpuAvailable=false ALL_PROBES_FAILED-or-baseline`, second call (after script update with new counter values) returns delta in 0..100. Test 4 — PowerVR all-empty (no probe match): returns `GpuSnapshot(-1, false, GpuDiagnostic(reason=POWERVR_UNSUPPORTED))`. // note: design §3 says all-empty AND no perfcounter-enable success path → `ALL_PROBES_FAILED`; PowerVR distinction comes from vendor inference — for Sprint 1, treat any all-empty as `ALL_PROBES_FAILED` UNLESS perfcounter enable also failed in which case `ADRENO_PERFCOUNTER_DISABLED`. PowerVR-specific reason only triggered when a PowerVR-marked candidate is in catalog AND probe returned non-empty placeholder — Sprint 1 catalog has PowerVR LOW placeholders so all-empty stays `ALL_PROBES_FAILED`. Test 5 — all-empty sticky: after first all-empty, set firstProbeFailed=true → subsequent calls return same diagnostic without re-shelling (assert `shellInvocationCount` on FakeAdbBridge). [GPU-001, GPU-002, GPU-003, GPU-005, GPU-006]
- [ ] 3.4 GREEN: implement in `core/AdbBridge.kt`:
  - Add private fields: `gpuLock = Any()`, `gpuStateMap: MutableMap<String, GpuDeviceState>`.
  - Add private `data class GpuDeviceState(vendor: GpuVendor?, winningPath: String?, format: ProbeFormat?, lastBusyTotal: Pair<Long,Long>?, perfcounterEnabledByUs: Boolean = false, firstProbeFailed: Boolean = false, terminalDiagnostic: GpuDiagnostic? = null)`.
  - Implement `captureGpuUsage` Steps 1-4 (cache lookup; if no winningPath and !firstProbeFailed → single-shell probe via `buildProbeOneShellCommand`; Mali direct cat; Adreno gpu_busy_percentage direct cat). [§4]
  - Skip Step 5 enable lifecycle for now (returns ALL_PROBES_FAILED for now).
- [ ] 3.5 Run `./gradlew test --tests "*AdbBridgeGpuTest*"` → green for tests 1, 2, 4, 5. Test 3 baseline+delta may still fail until Step 5 wired — verify gpubusy parses + stores baseline at minimum.
- [ ] 3.6 RED: create `core/AdbBridgeGpuLifecycleTest.kt`. Test 1 — both probes empty + echo succeeds: `shellResponses` Mali/Adreno empty + perfcounter-enable command returns `"rc=0"` → first call returns `GpuSnapshot(-1, false, GpuDiagnostic(reason=ALL_PROBES_FAILED))` AND state has `perfcounterEnabledByUs=true, winningPath=null`. Test 2 — second call after enable: `shellResponses` updated so gpubusy returns counter → second call stores baseline, returns unavailable; third call returns delta. Test 3 — echo fails (`"rc=1"` or contains "Permission denied"): returns `GpuSnapshot(-1, false, GpuDiagnostic(reason=ADRENO_PERFCOUNTER_DISABLED, failedEnableCommand=<cmd>))`, state has `firstProbeFailed=true`, subsequent calls return same diagnostic without re-shelling. Test 4 — `resetSessionState()` issues `echo 0 > perfcounter` for devices with `perfcounterEnabledByUs=true`, NOT for others. Test 5 — echo-0 failure during reset is swallowed (doesn't throw). Test 6 — multi-device isolation: dev1 enabled, dev2 not → reset issues echo only for dev1. [GPU-007.1, 007.2, 007.3, 007.4, GPU-014]
- [ ] 3.7 GREEN: implement Step 5 in `captureGpuUsage`: when both Adreno probes empty AND `!firstProbeFailed` AND `!perfcounterEnabledByUs` → issue `echo 1 > <ADRENO_PERFCOUNTER_NODE> 2>&1; echo rc=$?`; parse rc + check for "denied"/"Permission" substring → success path sets `perfcounterEnabledByUs=true` + `winningPath=null` + returns NOT_PROBED_YET-equivalent (`ALL_PROBES_FAILED` with `detectedVendor="ADRENO"`); failure sets `firstProbeFailed=true` + `terminalDiagnostic=ADRENO_PERFCOUNTER_DISABLED`. Extend `resetSessionState()`: BEFORE `gpuStateMap.clear()` iterate entries with `perfcounterEnabledByUs=true` → best-effort `echo 0 > <ADRENO_PERFCOUNTER_NODE>` wrapped in try/catch (swallow). [§3, §4]
- [ ] 3.8 RED: add to `AdbBridgeGpuTest.kt` — exception resilience: `FakeAdbBridge` configured to throw on shell call → `captureGpuUsage` returns `GpuSnapshot(-1, false, GpuDiagnostic(reason=CAPTURE_THREW))`, doesn't propagate. [GPU-022]
- [ ] 3.9 GREEN: wrap entire `captureGpuUsage` body in try/catch returning `CAPTURE_THREW` snapshot. Mirror thermal pattern. Run → green.
- [ ] 3.10 RealAdbBridge passthrough: `core/RealAdbBridge.kt` (or wherever Real impl lives) — implement `captureGpuUsage` as one-line delegation to `AdbBridge.captureGpuUsage(deviceId)`. // note: if Real is a thin wrapper this is a no-op; otherwise ensure the shared captureGpuUsage path is reached.
- [ ] 3.11 Batch-end: `./gradlew test --tests "*AdbBridgeGpu*"` then full `./gradlew test` → green. Commit: `feat(gpu): bridge wiring with Adreno perfcounter enable lifecycle`.

## Batch 4 — AppViewModel integration

- [ ] 4.1 RED: extend or create `viewmodel/AppViewModelGpuTest.kt`. Test 1 — every-4-tick poll cadence: drive 12 ticks against FakeAdbBridge, assert `captureGpuUsage` invoked exactly on ticks 4, 8, 12. Test 2 — history accumulation: 8 ticks with `gpuAvailable=true usagePct=50` → `gpuUsageHistory.size == 2`, both==50. Test 3 — history GATED by `gpuAvailable`: 8 ticks alternating available/unavailable → only available entries appended. Test 4 — `gpuUsageHistory` capped at `MAX_HISTORY_SIZE` (mirror tempGpuHistory cap pattern). Test 5 — LiveMetrics emission contains `gpuUsage`, `gpuAvailable`. [GPU-015, GPU-016, §6]
- [ ] 4.2 GREEN: modify `viewmodel/AppViewModel.kt`:
  - L1107 area: add `var lastGpu = GpuSnapshot(usagePct = -1, gpuAvailable = false, diagnostic = null)`.
  - L1177-1197 area: add `val runGpu = iterCount % 4 == 0; if (runGpu) { lastGpu = adb.captureGpuUsage(device.id); if (shouldStop) break }`. iOS branch untouched.
  - L1284 area: add `val shouldRecordGpu = iterCount % 4 == 1` block appending to `gpuUsageHistory` + `gpuUsageTimed` ONLY when `lastGpu.gpuAvailable && lastGpu.usagePct >= 0`. Cap mirroring `tempGpuHistory` L1315-1320.
  - L1357 LiveMetrics emission: add `gpuUsage`, `gpuAvailable`, `gpuUsageHistory` fields.
- [ ] 4.3 Extend `LiveMetrics` data class (file `viewmodel/LiveMetrics.kt`): add `gpuUsage: Int = -1`, `gpuAvailable: Boolean = false`, `gpuUsageHistory: List<Int> = emptyList()`.
- [ ] 4.4 Run new ViewModel tests → green.
- [ ] 4.5 RED: add to `AppViewModelGpuTest.kt` — Test 6: `.gameperf` persistence round-trip — capture session with GPU available, serialize SessionResult/SerializableEntry, deserialize, assert `gpuAvailable + maxGpuUsage + gpuUsageHistory + gpuUsageTimed + gpuDiagnostic` preserved. Test 7: backward-compat — load synthetic v4.4.1 payload (JSON missing GPU fields) → deserializes with `gpuAvailable=false`, others default. [GPU-017]
- [ ] 4.6 GREEN: modify `SessionHistory.SerializableEntry` (L1824 area) AND `SessionHistory.Entry` (L193/238 area): add `gpuAvailable: Boolean = false`, `maxGpuUsage: Int = -1`, `gpuUsageHistory: List<Int> = emptyList()`, `gpuUsageTimed: List<TimedSample> = emptyList()`, `gpuDiagnostic: GpuDiagnostic? = null`. Defaults ensure backward-compat. // note: defaults must NOT be `null` for primitives — use `-1` sentinel matching ThermalSnapshot.dieCpu precedent.
- [ ] 4.7 Modify L1683-1729 `ReportGenerator.generate` call: thread `gpuUsageHistory`, `maxGpuUsage`, `gpuAvailable`, `gpuDiagnostic` through.
- [ ] 4.8 Batch-end: `./gradlew test --tests "*AppViewModelGpu*"` then full `./gradlew test` → green. Commit: `feat(gpu): AppViewModel poll + history + persistence`.

## Batch 5 — Report HTML rendering

- [ ] 5.1 RED: create or extend `core/ReportGeneratorGpuTest.kt`. Test 1 — render % when `gpuAvailable=true`: generated HTML contains `id="sec-gpu"`, chart canvas, `maxGpuUsage` value, NO banner. Test 2 — Spanish banner for `ADRENO_PERFCOUNTER_DISABLED`: HTML contains banner mentioning Adreno + lists `probedPaths` + `failedEnableCommand` + tuteo-formal copy ("ten en cuenta", NOT "tené"). Test 3 — Spanish banner for `POWERVR_UNSUPPORTED`: mentions MediaTek/Unisoc + Sprint 1.5 crowdsource invite. Test 4 — banner for `ALL_PROBES_FAILED`: generic + lists probedPaths. Test 5 — banner for `CAPTURE_THREW`: generic resilience message. Test 6 — Adreno warm-up footnote: when `detectedVendor=="ADRENO"` AND first sample timestamp ≥4000ms → footnote present. Test 7 — foreground-attribution caveat tooltip always rendered when `gpuAvailable=true`. [GPU-018, GPU-019, GPU-020]
- [ ] 5.2 GREEN: modify `core/ReportGenerator.kt`:
  - Generator signature L84-85 area: add `gpuAvailable: Boolean = false, gpuDiagnostic: GpuDiagnostic? = null, gpuUsageHistory: List<Int> = emptyList(), maxGpuUsage: Int = -1`.
  - Insert GPU metric card BETWEEN CPU (L353-357) and Temperature (L358-393): mirror thermal `metricCard` pattern. `!gpuAvailable` → "N/D" + "Sensor no disponible" neutral grade 'A'.
  - Insert `#sec-gpu` chart section AFTER `#sec-cpu` (L454-464 area): chart-container + canvas same shape as CPU.
  - Add private `gpuDiagnosticBanner(gpuAvailable, gpuDiagnostic)` cloned from `thermalDiagnosticBanner` (L1371-1400). Switch on `reason` for 5 Spanish tuteo-formal copies.
  - Caveat `<p class="hint">` tooltip under chart: foreground-app attribution + unnormalised peaks + Adreno ~4s warm-up.
- [ ] 5.3 Batch-end: `./gradlew test --tests "*ReportGeneratorGpu*"` then full `./gradlew test` → green. Commit: `feat(gpu): report HTML rendering with Spanish banners`.

## Batch 6 — Full-suite gate + detekt

- [ ] 6.1 Run `./gradlew test` — full suite green (existing ~815 + new ~40-50 tests, 0 failures).
- [ ] 6.2 Run `./gradlew detekt` — zero NEW warnings on touched files; pre-existing baseline warnings unchanged.
- [ ] 6.3 Run `./gradlew check` — full gate green (includes test + detekt + any ktlint).
- [ ] 6.4 If `LongMethod` flagged on `captureGpuUsage`: extract helpers per design §9 (`adrenoProbeFailedTryEnable`, `cachedUnavailable`, `cachedAdrenoBlocked`, `powervrUnsupported`, `fallbackToGpubusy`, `buildProbeOneShellCommand`, `catalogPaths`). Each ≤25 lines. Re-run detekt → clean. // note: if extraction proves disruptive, add detekt-baseline entry with comment justifying — Sprint 1 budget allows.
- [ ] 6.5 Manual smoke: review diagnostic messages by reading test expectations — confirm Spanish tuteo-formal copy reads naturally for Mali / Adreno / unknown-vendor / locked-OEM cases.
- [ ] 6.6 Commit: `chore(gpu): detekt cleanup + helper extraction` (only if 6.4 produced code changes).

## Batch 7 — Docs + CHANGELOG

- [ ] 7.1 Update `CHANGELOG.md` with v4.5.0 entry: "Added GPU usage % capture (Mali + Adreno) with graceful PowerVR degradation, Adreno perfcounter enable/disable lifecycle, Spanish tuteo-formal diagnostic banners."
- [ ] 7.2 Update `README.md` (Spanish) and `README_EN.md`: new "GPU Usage" metric description + one sentence comparing approach to GameBench (host-side sysfs vs on-device driver perfcounter).
- [ ] 7.3 If `docs/ARCHITECTURE.md` or similar exists: document foreground-attribution caveat + Adreno warm-up + perfcounter side-effect mitigation via `resetSessionState()`. Otherwise add a `docs/gpu-usage.md` short note. // note: if no docs dir, skip — CHANGELOG + README cover it.
- [ ] 7.4 Update `GAMEBENCH-COMPARISON.md` if present: mark "GPU usage %" row as shipped in v4.5.0 with caveats footnote.
- [ ] 7.5 Batch-end: `./gradlew test && ./gradlew detekt` → green. Commit: `docs(gpu): CHANGELOG + README + comparison update for v4.5.0`.

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
