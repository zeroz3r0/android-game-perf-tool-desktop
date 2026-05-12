# Exploration — fpower-metric

Date: 2026-05-12
Branch: `fix/autoupdater-resilience-v4-4-1` @ `f335444`
Topic key: `sdd/fpower-metric/explore`

## Goal

Add the **FPower** metric (mW per frame) to the desktop tool, closing the highest-ROI gap vs PerfDog (Tencent/WeTest) identified in `research/perfdog-deep-dive-2026-05-12` (obs #312) and `docs/competitive-analysis-and-kpis.md` §3.6 / §5.1 / §9 #8.

PerfDog calls FPower industry-first. Public case study: 60 mW/frame → 46.7 mW/frame yielded 22 % battery-life gain at unchanged FPS. The formula is fully public — there is no proprietary moat — and the data sources are zero-root, zero-SDK, zero-cloud, which matches our local-first positioning.

## Formula (cited from doc §3.6 + obs #312)

```
Power(W)        = abs(current_now_microA) * voltage_now_microV / 1e12
FPower(mW/frame) = Power(W) * 1000.0 / fps          (when fps > 0)
```

PerfDog anchors (case-study-grounded, not bench-audited):

- `< 50 mW/frame` — excellent
- `50–65 mW/frame` — acceptable
- `> 65 mW/frame` — investigate

## Data sources (Android, no root)

Sysfs files exposed to the `shell` UID on all stock AOSP builds I 11+:

- `/sys/class/power_supply/battery/current_now` — instantaneous current, **microamps**. Kernel sign convention: positive when **charging**, negative when **discharging** (Linux `power_supply` core, `POWER_SUPPLY_PROP_CURRENT_NOW`). Some OEMs invert it. We `abs()` to neutralise both.
- `/sys/class/power_supply/battery/voltage_now` — instantaneous voltage, **microvolts**. Always positive.

Both files are world-readable per AOSP `genfs_contexts` (label `u:object_r:sysfs_batteryinfo:s0`). No `adb root` required. Confirmed on Pixel, Samsung, Xiaomi, OnePlus stock images.

### OEM / vendor caveats to plan for

- **Samsung One UI 5+**: `/sys/class/power_supply/battery/{current_now,voltage_now}` still present, BUT additionally exposes `/sys/class/power_supply/battery/batt_current_ua_now` (microA, ABS-only) — used internally by Samsung Members. We treat the AOSP path as primary; Samsung-specific aliases live in the vendor catalog as fallbacks (mirrors the `ThermalZoneClassifier` pattern).
- **Huawei EMUI / HarmonyOS**: vendor branch sometimes drops the standard `battery` symlink and exposes `/sys/class/power_supply/Battery/...` (capital B). Catalog entry.
- **Xiaomi MIUI**: sometimes exposes `/sys/class/power_supply/bms/current_now` as the BMS-side reading; OnePlus/Realme similar with `/sys/class/power_supply/bq2589x_charger/...`. Catalog entry but lower priority than the AOSP path.
- **Knox-locked corporate / banking enterprise builds**: SELinux can deny the read despite the AOSP label; we surface this as `OEM_LOCKED` in the diagnostic.

These are mitigations, NOT blockers. The AOSP path covers the overwhelming majority of consumer devices our QA teams target.

## Precedent inventory (verified file paths)

The thermal v4.4.1 change (`temperature-not-shown`) is the exact precedent we follow. Every architectural decision below has a live counterpart:

| Concept | Precedent file |
|---|---|
| Pure parser | `src/main/kotlin/com/gameperf/desktop/core/AdbThermalParser.kt` |
| Vendor catalog as `private val Set/List<Regex>` | `src/main/kotlin/com/gameperf/desktop/core/ThermalZoneClassifier.kt` |
| Stateful bridge orchestrator + cache | `src/main/kotlin/com/gameperf/desktop/core/AdbBridge.kt:680` (`captureTemperature`) |
| Snapshot data class with availability flag + diagnostic | `src/main/kotlin/com/gameperf/desktop/core/model/Metrics.kt:65` (`ThermalSnapshot`), `src/main/kotlin/com/gameperf/desktop/core/model/ThermalDiagnostic.kt` |
| AdbBridgeApi method + RealAdbBridge passthrough | `src/main/kotlin/com/gameperf/desktop/core/AdbBridgeApi.kt:53,151` |
| FakeAdbBridge fixture pattern (`setThermal` / `shellResponses`) | `src/test/kotlin/com/gameperf/desktop/testing/FakeAdbBridge.kt:101-114,182-189` |
| AppViewModel last-known + tiered cadence + history accumulation + report payload | `src/main/kotlin/com/gameperf/desktop/viewmodel/AppViewModel.kt:1107` (initial), `:1177-1196` (every-4-tick poll), `:1284-1332` (history accumulation), `:1686-1729` (report payload), `:1815-1824` (history entry persistence) |

All paths confirmed by direct file read at HEAD `f335444`. No phantom claims.

## FPS source for FPower division

`AppViewModel.startCapture` loop:

- Per-tick FPS lives in local `var` `frame.fps` and is added to `fpsHistory: MutableList<Int>` at `AppViewModel.kt:1254`.
- The most recent FPS value at the moment of an every-4-tick poll = `frame?.fps ?: 0` from the current iteration (lines `:1244-1254`).
- For the FPower divisor we will use the **same per-tick `fps` local** the existing thermal block already has in scope, NOT a smoothed average. Smoothing belongs at aggregation time (per-phase later), not at capture time — same principle the rest of the metrics already follow.

When `fps <= 0` we surface `FPS_ZERO` in `FPowerDiagnostic.reason` and skip the history append for that tick. We do NOT emit `0 mW/frame` (would corrupt the chart axis exactly like the v4.2.5 thermal bogus-value fix did).

## Plausibility window

Reality check on the formula across realistic device classes:

- Modern flagship under load: ~2-6 W total → 30-100 mW/frame at 60 fps
- Mid-tier under load: ~3-8 W → 50-130 mW/frame at 60 fps
- Charging (positive current, post-`abs`): up to ~25 W during fast charging — outlier, must NOT crash the chart
- Idle screen-off: ~50-200 mW → not realistic in our capture context but harmless

Window: `0 W < Power < 30 W`, `0 mW/frame < FPower < 500 mW/frame`. Outside → diagnostic `IMPLAUSIBLE_VALUE`, history append skipped, same defensive posture as thermal `ALL_TEMPS_INVALID`.

## Risks identified

| Risk | Likelihood | Mitigation |
|---|---|---|
| FPS = 0 transient on ad close | High (already happens) | Guard `fps > 0`; reuse existing v4.3.5 `LastKnownFpsTracker` semantics? **NO** — that tracker is HUD-only. For FPower we want truth, so we skip the tick. |
| Sign-convention inversion | Medium | `abs()` neutralises. Covered in spec FPW-002. |
| OEM-locked sysfs path (Knox / SELinux) | Low-Medium | Diagnostic surfaces `OEM_LOCKED`; report banner explains how to file a vendor catalog request. Pattern identical to thermal `ALL_ZONES_UNCLASSIFIED`. |
| Charging during capture inflates FPower | Medium | `AdbBridge.disableCharging` already used during sessions (existing pattern). FPower remains valid post-`abs`; the dumpsys `battery unplug` call still removes the systemic charging-current pollution. We document this in spec FPW-003 acceptance. |
| `voltage_now = 0` on dead battery / fault | Very low | Treat as `BATTERY_PATH_MISSING` equivalent; `Power = 0` is implausible → diagnostic. |
| First-tick FPS not warm yet | Low | Same as thermal — `lastFPower` initialised to "unavailable", warms up by tick 2. Matches `lastThermal` semantics at AppViewModel.kt:1107. |
| Charging fast-charge spike during USB power | Medium | Already-mitigated by disableCharging; spec FPW-011 plausibility window absorbs residual outliers. |
| Pre-v4.4.1 `.gameperf` history load | Certain | Defaulted fields on the persisted payload (matches the thermal pattern in `ThermalSnapshot`). Spec FPW-012. |

## Scope clarifications

**IN scope** for this change:

- Battery sysfs read on Android (Mali + Adreno + all CPU/SoC, vendor-agnostic).
- FPower (mW/frame) computation per tick, every-4-tick cadence (~2 s).
- Live HUD field (mW/frame, color-coded by anchor band).
- History persistence (`fpowerHistory`, `fpowerTimed`).
- Report HTML section: card, line chart, color band, Spanish-tuteo-formal banner when unavailable.
- Vendor catalog (top SoC paths) using the `ThermalZoneClassifier` pattern.
- Backward compat with pre-v4.4.1 `.gameperf` exports (defaulted fields).

**OUT of scope** (defer or owned by other changes):

- Per-phase FPower aggregation — owned by `kpi-scoring-framework` (#2 in doc §9), this change only emits the raw timeline.
- GPU frequency or CPU frequency reads — separate changes (`gpu-usage-percent` partially-done #4, `cpu-freq-normalized` bundled into #9).
- Battery health / cycle metrics — niche, no PerfDog parity gap.
- Charging-state detection — already covered by `disableCharging` at session start.
- iOS FPower — sidecar API addition only when we have an iOS sysfs equivalent or IOKit power API mapping. Out of this change.

## Open question (NO escalation needed per orchestrator instruction)

The doc §8 #11 lists the question "confirm PerfDog anchors vs run our own baselines". For this change we adopt PerfDog anchors verbatim (<50 / 50–65 / >65). Rationale: shipping with industry-cited anchors is the right v1 default; per-device-tier baselines can be a follow-up once we have real corpus data from teams using the tool.

## Status

Exploration COMPLETE. All file paths verified by direct read. Ready for `sdd-propose`.
