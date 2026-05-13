# Proposal — fpower-metric

Topic key: `sdd/fpower-metric/proposal`
Branch: `fix/autoupdater-resilience-v4-4-1` @ `f335444`
Depends on: exploration `sdd/fpower-metric/explore`
Doc anchors: `docs/competitive-analysis-and-kpis.md` §3.6 (formula) / §5.1 (KPI row) / §9 #8 (change entry) / §8 #11 (anchor decision).

## Intent

Ship **FPower** (mW per frame) as a first-class metric in the desktop tool to close the highest-ROI gap vs PerfDog (Tencent/WeTest). FPower is PerfDog's headline industry-first metric and the single biggest item on the "PerfDog has this, we don't" list. A public case study quoted in `wetest.net/blog/...#1189` shows a 60 → 46.7 mW/frame reduction yielding **22 % battery-life gain at unchanged FPS** — the kind of finding QA teams care about and Android Vitals never surfaces.

The formula is public, the inputs are zero-root sysfs files exposed to the `shell` UID, there is no SDK or cloud dependency. The change reinforces our **local-first, zero-touch, open-methodology** positioning (see doc §10).

## Scope

### IN

- Read `/sys/class/power_supply/battery/{current_now, voltage_now}` (microA, microV) via `adb shell cat`, no root.
- Compute `Power(W) = abs(current) * voltage / 1e12` and `FPower(mW/frame) = Power * 1000 / fps` (when `fps > 0`).
- New pure parser `FPowerParser` (mirrors `AdbThermalParser`).
- New vendor catalog `FPowerVendorCatalog` (mirrors `ThermalZoneClassifier`) for OEM path alternates: Samsung One UI `batt_current_ua_now`, Huawei `/sys/class/power_supply/Battery/...`, Xiaomi `bms/current_now`, OnePlus `bq2589x_charger/...` fallbacks. Primary AOSP path tried first.
- `FPowerSnapshot` + `FPowerDiagnostic` + `FPowerUnavailableReason` data classes (mirrors `ThermalSnapshot` + `ThermalDiagnostic` + `ThermalUnavailableReason`).
- `AdbBridge.captureFPower(deviceId, fps)` orchestrator (mirrors `captureTemperature(deviceId)`), stateful per-device cache of the working battery sysfs path.
- `AdbBridgeApi.captureFPower` + `RealAdbBridge` passthrough.
- `FakeAdbBridge.setFPower(...)` fixture + `shellResponses["/sys/class/power_supply/battery/"]` paths for unit tests.
- `AppViewModel.startCapture`:
  - `lastFPower` initialiser next to `lastThermal` at `:1107`.
  - Every-4-tick poll alongside thermal at `:1177-1196` (same cadence, ~2 s).
  - `fpowerHistory` + `fpowerTimed` accumulators alongside thermal histories at `:1056-1077`.
  - History append at `:1284-1332` mirroring `tempDieCpuHistory` shape.
  - `LiveMetrics.fpower` MutableState field for HUD.
- `LiveMetrics` data class: new `fpower: Double`, `fpowerHistory: List<Double>`, `fpowerTimed: List<TimedSample>` (mirrors `tempCpu*` / `tempDieCpu*` shape).
- `SessionResult` + `SessionHistory.HistoryEntry`: `fpowerAvg`, `fpowerPeak`, `fpowerAvailable`, `fpowerDiagnostic` — all `@Serializable`, defaulted for pre-v4.4.1 reads.
- `ReportGenerator`: HTML card + line-chart + color-band banner using PerfDog anchors (<50 / 50–65 / >65 mW/frame).
- Spanish-tuteo-formal "FPower no disponible" banner when `fpowerAvailable == false` (mirrors v4.4.1 thermal banner copy).
- Unit tests across all 5 new test files; full detekt clean.

### OUT

- Per-phase FPower aggregation (owned by `kpi-scoring-framework` change #2 in doc §9).
- GPU usage % or GPU frequency (owned by `gpu-usage-percent` change #4).
- CPU% freq-normalised (owned by bundled change #9 inside `kpi-scoring-framework`).
- Battery health / charge cycle count / temperature-derived health (no PerfDog parity gap, niche).
- Charging-state UI (already covered by existing `disableCharging` / `restoreCharging` flow at session start/end).
- iOS FPower (no sidecar API mapping yet — deferred until iOS power API surface decision).
- Configurable anchor thresholds (v1 ships PerfDog defaults; per-device-tier baselines are a follow-up).

## Approach

Strict mirror of the **thermal v4.4.1 (`temperature-not-shown`)** architecture. Every layer has a precedent file in the codebase at `f335444`:

1. **Flat `core/` layout.** No new sub-package. `FPowerParser.kt`, `FPowerVendorCatalog.kt`, `FPowerSnapshot`-in-`Metrics.kt`, `FPowerDiagnostic.kt` all sit next to their thermal counterparts. The doc §3.6 anchor language is the unified naming source.
2. **Pure parser, stateful bridge.** `FPowerParser.parseBatteryOutput(currentRaw, voltageRaw, fps)` takes already-fetched strings, returns `FPowerSnapshot`. No I/O, fully unit-testable. `AdbBridge.captureFPower` does the `shell` orchestration + caches the successful path per-device, identical to the thermal pattern.
3. **Every-4-tick cadence (~2 s).** Same iteration counter (`iterCount % 4 == 0`) as the existing thermal poll. No new timer, no new coroutine, no new threading concern. Falls inside the existing tiered-cadence design comment block at `AppViewModel.kt:1085-1107`.
4. **MutableState HUD.** `LiveMetrics` gains `fpower: Double` next to `tempCpu`. Same emission point at `:1357`. Same `snapshotHistories` gate.
5. **Defaulted serialization.** `FPowerSnapshot` + `FPowerDiagnostic` are `@Serializable` with defaults so pre-v4.4.1 `.gameperf` exports load cleanly (matches `ThermalSnapshot` v4.4.1 widening).
6. **`abs(current_now)`.** Single line handles the kernel-vs-OEM sign convention divergence. No conditional, no per-vendor branch.
7. **Vendor catalog as `private val Sets`.** Mirrors `ThermalZoneClassifier.SKIN_LITERAL` / `DIE_CPU_PATTERN` / etc. Strict allow-list, no fuzzy substring match (we learned this lesson in v4.3.6 thermal).
8. **TDD red → green strict** per repo convention. All tests precede implementation. Detekt baseline kept clean (no new suppressions).

## Effort

**2.75 days** (7 batches, see `tasks.md`). HIGH ROI, STANDALONE — no dependency on `kpi-scoring-framework` or any other in-flight change. Ships independently as a v4.5.0 (minor bump) on its own merge.

## Outcome

- Top PerfDog gap closed. Marketing line in §10 can extend "no FPower" claim to a positive: "FPower included, formula public, anchors documented, no SDK no cloud."
- Foundation for `kpi-scoring-framework` (FPower row in the KPI catalog at §5.1 becomes implemented data, not a placeholder).
- Detectable 22 %-class battery-life regression in CI when `cli-headless-mode` (#11) lands and uses FPower as a threshold gate.

## Non-goals reaffirmed

This change does NOT replace `tempCpuHistory`, does NOT replace `cpuHistory`, does NOT add a new poll cadence, does NOT introduce a new sub-package, does NOT touch iOS, does NOT add configuration UI. It is intentionally a one-axis surgical addition.
