# Tasks — fpower-metric

Topic key: `sdd/fpower-metric/tasks`
Depends on: spec `sdd/fpower-metric/spec`, design `sdd/fpower-metric/design`.

TDD strict (red → green per item). Detekt clean. No commits / pushes / branch ops handled by this change.

Total estimated effort: **2.75 days**. Batches are DAG-ordered (later batches depend on earlier ones).

---

## Batch 1 — Models + Vendor Catalog (0.25d)

Goal: data layer ready, no behaviour yet.

- [ ] **T1.1** — Extend `core/model/Metrics.kt` with `@Serializable data class FPowerSnapshot(...)` (all defaulted fields per design §3). Cite spec FPW-004.
- [ ] **T1.2** — Create `core/model/FPowerDiagnostic.kt` with `@Serializable data class FPowerDiagnostic(...)` + `@Serializable enum class FPowerUnavailableReason { BATTERY_PATH_MISSING, FPS_ZERO, IMPLAUSIBLE_VALUE, OEM_LOCKED, PERMISSION_DENIED, UNKNOWN }`. Cite spec FPW-005.
- [ ] **T1.3** — Create `core/FPowerVendorCatalog.kt` with the 5-tuple ordered `ORDERED_PATHS` list per design §2 + spec FPW-010.
- [ ] **T1.4** (RED) — Add `core/FPowerVendorCatalogTest.kt` asserting: AOSP-canonical is index 0; all 5 tuples present; each tuple has a `currentPath` ending `current_now` and a `voltagePath` ending `voltage_now`. Run, observe failure (file doesn't exist yet at this RED step) → GREEN after T1.3 lands.
- [ ] **T1.5** — Detekt local on changed files = 0 warnings.

Done-when: classes compile, `FPowerVendorCatalogTest` is green.

---

## Batch 2 — Pure Parser (0.5d)

Goal: deterministic, no-I/O `FPowerParser` covered by exhaustive unit tests.

- [ ] **T2.1** (RED) — Create `core/FPowerParserTest.kt` with one test per spec scenario across FPW-002, FPW-003, FPW-004 (defaults round-trip), FPW-005 (each reason), FPW-011 (plausibility): each branch a separate `@Test`. Use literal raw strings as inputs (`"-350000"`, `"4100000"`, etc.). Run, observe all red.
- [ ] **T2.2** (GREEN) — Implement `core/FPowerParser.kt` per design §1. Match algorithm steps 1-8. Use named constants for `POWER_DIVISOR`, `POWER_W_WINDOW`, `FPOWER_MW_WINDOW`, `DIAGNOSTIC_PATHS_LIMIT`.
- [ ] **T2.3** — Verify `@Serializable` round-trip: write a tiny inline test that JSON-encodes an `FPowerSnapshot` and decodes it back to the same value. Confirms FPW-004.
- [ ] **T2.4** — Detekt local on changed files = 0 warnings; clean parser receives no suppressions.

Done-when: every `FPowerParserTest` case green; coverage hits every reason enum value.

---

## Batch 3 — Bridge Wiring (0.5d)

Goal: `AdbBridge` orchestrates the cached-path read; `AdbBridgeApi` + `RealAdbBridge` + `FakeAdbBridge` extended.

- [ ] **T3.1** (RED) — Create `core/AdbBridgeFPowerTest.kt` covering: (a) FPW-001 AOSP-first happy path via `FakeAdbBridge.shellResponses["/sys/class/power_supply/battery/current_now"]` etc.; (b) FPW-001 fallback path (Samsung tuple takes over when AOSP returns empty); (c) FPW-006 cache hit (2 ticks → 4 shell calls total because cache kicks in after tick 1, not 8); (d) FPW-006 `resetSessionState()` clears cache. Run → red.
- [ ] **T3.2** (GREEN) — Extend `AdbBridge.kt` with `private val fpowerPathCache: ConcurrentHashMap<String, FPowerVendorCatalog.PathTuple>` and `fun captureFPower(deviceId, fps): FPowerSnapshot` per design §5. Update `resetSessionState()` to call `fpowerPathCache.clear()`.
- [ ] **T3.3** (GREEN) — Extend `AdbBridgeApi.kt` interface and `RealAdbBridge` passthrough per design §6.
- [ ] **T3.4** (GREEN) — Extend `core/bridge/AndroidBridge.kt` / `CompositeBridge.kt` / `IosBridge.kt` per design §7. `IosBridge.captureFPower` returns `FPowerSnapshot()` with `fpowerAvailable = false`, `diagnostic.reason = UNKNOWN`.
- [ ] **T3.5** (GREEN) — Extend `test/testing/FakeAdbBridge.kt` with `setFPower(...)` builder + `scriptedFPower` field per design §8.
- [ ] **T3.6** — Detekt local clean.

Done-when: `AdbBridgeFPowerTest` green; FakeAdbBridge fixture works.

---

## Batch 4 — AppViewModel Integration (0.5d)

Goal: every-4-tick capture, history accumulation, LiveMetrics propagation, persisted payload.

- [ ] **T4.1** (RED) — Create `viewmodel/AppViewModelFPowerTest.kt`. Cases: (a) FPW-007 8-tick capture → exactly 2 `captureFPower` calls (assert via `FakeAdbBridge` call-counter); (b) FPW-008 persisted `SessionResult.fpowerAvg / fpowerPeak / fpowerAvailable` populated from history; (c) Unavailable snapshot from FakeAdbBridge → `LiveMetrics.fpower == 0.0` and `_result.value.fpowerAvailable == false`. Run → red.
- [ ] **T4.2** (GREEN) — Wire `AppViewModel.startCapture` per design §9a-9f. Five wiring points: initialiser, accumulators, poll-on-runThermal, history append, LiveMetrics emission, post-loop aggregates + SessionResult + HistoryEntry. Use named-args for ALL additions to be future-proof (the v4.4.1 temperature-not-shown change documented this lesson).
- [ ] **T4.3** (GREEN) — Extend `LiveMetrics` data class with `fpower`, `fpowerHistory`, `fpowerTimed` (all defaulted).
- [ ] **T4.4** (GREEN) — Extend `SessionResult` + `SessionHistory.HistoryEntry` with `fpowerHistory`, `fpowerTimed`, `fpowerAvg`, `fpowerPeak`, `fpowerAvailable`, `fpowerDiagnostic` (all `@Serializable`-friendly defaulted).
- [ ] **T4.5** (RED→GREEN) — Add a backward-compat test: deserialise a checked-in pre-this-change `.gameperf` fixture, assert it decodes with `fpowerHistory.isEmpty() && fpowerAvg == 0.0 && fpowerAvailable == true && fpowerDiagnostic == null` (spec FPW-012).
- [ ] **T4.6** — Detekt local clean.

Done-when: all `AppViewModelFPowerTest` cases green; backward-compat fixture round-trips.

---

## Batch 5 — Report HTML (0.5d)

Goal: card rendered with color-bands; diagnostic banner shown on unavailable; defaulted args keep legacy fixtures compiling.

- [ ] **T5.1** (RED) — Create `report/ReportGeneratorFPowerTest.kt`. Cases: (a) FPW-009 `fpowerAvg = 38.4, fpowerPeak = 51.2` → output HTML contains `class="fpower-green"` for avg AND `class="fpower-amber"` for peak; (b) `fpowerAvailable = false, diagnostic.reason = BATTERY_PATH_MISSING, rawPathsTried = ["/sys/.../battery/current_now"]` → output contains the Spanish-tuteo-formal banner copy AND the path string; (c) Defaulted call (no FPower args at all) → no FPower section in HTML (legacy fixture stays unchanged). Run → red.
- [ ] **T5.2** (GREEN) — Extend `ReportGenerator.generate(...)` signature with the 5 new defaulted named-args per design §10.
- [ ] **T5.3** (GREEN) — Add `private fun fpowerBand(value: Double): String` helper (`<50 green`, `<65 amber`, `else red`).
- [ ] **T5.4** (GREEN) — Render the FPower `<section>` only when `fpowerHistory.isNotEmpty() || !fpowerAvailable`. Banner only when `!fpowerAvailable && fpowerDiagnostic != null`.
- [ ] **T5.5** (GREEN) — Add CSS classes `.fpower-green / .fpower-amber / .fpower-red` to the report's inline stylesheet block.
- [ ] **T5.6** — Re-run existing `ReportRenderingTest` fixtures — confirm zero regression (defaulted args preserve old output).
- [ ] **T5.7** — Detekt local clean.

Done-when: `ReportGeneratorFPowerTest` green; existing `ReportRenderingTest` unchanged; defaulted-args path verified.

---

## Batch 6 — Detekt + Full Suite Gate (0.25d)

Goal: project-wide gate, no new warnings, no baseline growth.

- [ ] **T6.1** — Run `./gradlew test` full suite. All tests green.
- [ ] **T6.2** — Run `./gradlew detekt`. Exit code 0. Baseline file unchanged.
- [ ] **T6.3** — If any new detekt finding pops, FIX IT (NO new suppressions). Repeat T6.1 + T6.2.
- [ ] **T6.4** — Confirm spec FPW-013.

Done-when: full suite green; detekt baseline byte-identical to pre-change.

---

## Batch 7 — CHANGELOG + README (0.25d)

Goal: user-visible documentation aligned with v4.5.0 minor bump.

- [ ] **T7.1** — Add CHANGELOG entry under `v4.5.0` (unreleased) header:
  - "Added: FPower metric (mW per frame). Computed from `/sys/class/power_supply/battery/{current_now, voltage_now}` ÷ FPS. PerfDog-style anchors: <50 / 50–65 / >65 mW/frame. No root, no SDK, no cloud."
  - "Added: Vendor catalog for Samsung One UI / Huawei / Xiaomi / OnePlus battery sysfs path alternates."
  - "Added: Diagnostic banner in the report HTML when the device exposes no readable battery sysfs path."
  - Cite spec topic_key `sdd/fpower-metric/spec`.
- [ ] **T7.2** — Update `README.md` "Supported metrics" table with the new FPower row.
- [ ] **T7.3** — Update `docs/competitive-analysis-and-kpis.md` §5.1 row for FPower: change Source column from `/sys/class/power_supply/battery/{current_now,voltage_now} ÷ FPS` to mark it `✅ shipped (v4.5.0)` — small status-tag delta, no other content change.

Done-when: CHANGELOG + README + competitive doc reflect the v4.5.0 ship.

---

## Total

7 batches, **2.75 days**. Standalone — no dependency on other in-flight changes (`event-segmentation-coverage`, `kpi-scoring-framework`, `gpu-usage-percent`).

Suggested next step after `tasks.md`: `sdd-apply fpower-metric Batch 1`.
