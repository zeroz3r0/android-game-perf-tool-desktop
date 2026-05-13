# Exploration: GPU usage percent on Android via sysfs (Sprint 1 — GameBench parity)

## Context

Sprint 1 of the 3-sprint GameBench-parity roadmap (engram obs #289, gap analysis #288). Builds an Android GPU usage % metric, no root, across the three relevant vendor families:

- **ARM Mali** (Samsung Exynos older + many MediaTek + some Google Tensor) — `/sys/class/misc/mali0/device/utilization` returns a single integer 0–100.
- **Qualcomm Adreno** (all Snapdragon) — `/sys/class/kgsl/kgsl-3d0/gpubusy` returns `"<busy> <total>"`, percent = `busy / total * 100`.
- **PowerVR / Imagination** (MediaTek Helio, some Dimensity, Unisoc) — vendor-proprietary sysfs paths, NOT confirmed publicly with the same stability as the two above.

Pattern parity target: `ThermalZoneClassifier` + `AdbThermalParser` (v4.3.6 + v4.4.1 layering). Pure parser, classifier, vendor catalog, plausibility window, and a `thermalAvailable=false`-style `gpuAvailable=false` graceful-degradation flag.

---

## Current State

### Where thermal lives today (NOT in a subpackage)

The brief assumed `core/thermal/` — the actual layout is **flat** under `core/`:

- `src/main/kotlin/com/gameperf/desktop/core/ThermalZoneClassifier.kt` — pure object, allow-list + regex sets.
- `src/main/kotlin/com/gameperf/desktop/core/AdbThermalParser.kt` — `internal object` with two pure functions (`parseThermalZonesOutput`, `mergeThermalServiceFallback`) and the plausibility-window enum.
- No separate `ThermalVendorCatalog.kt` file — the "vendor catalog" is just the named `private val` sets/regex inside `ThermalZoneClassifier` (`SKIN_LITERAL`, `DIE_CPU_PATTERN`, `TSENS_TZ_PATTERN`, `CLUSTER_THERMAL_PATTERN`, etc.). Each pattern carries a `// v4.4.1 ...` comment naming the vendor it came from.
- `ThermalSnapshot` (the data class) lives in `core/model/Metrics.kt`, NOT in `core/thermal/`.

Implication for Sprint 1: brief says "clone `core/thermal/` subpackage". The realistic clone is **flat files under `core/`** (or, optionally, introduce `core/gpu/` ONLY for GPU — but then GPU and thermal diverge architecturally without benefit). Decision item, listed below.

### How AdbBridge invokes sysfs today

`AdbBridge.captureTemperature()` is the wiring template (`AdbBridge.kt:680-692`):

```kotlin
fun captureTemperature(deviceId: String): ThermalSnapshot {
    val zones = shell(deviceId,
        "for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null):\$(cat \$z/temp 2>/dev/null)\"; done",
        timeoutMs = 3000)
    var snapshot = AdbThermalParser.parseThermalZonesOutput(zones)
    if (snapshot.dieCpu < 0 || snapshot.gpu < 0) {
        val dump = shell(deviceId, "dumpsys thermalservice", timeoutMs = 3000)
        snapshot = AdbThermalParser.mergeThermalServiceFallback(snapshot, dump, RE_THERMAL_TEMP)
    }
    return snapshot
}
```

Key facts confirmed by reading the code:

1. **`shell(deviceId, cmd, timeoutMs)` is the GENERIC sysfs read primitive** — already exposed via `AdbBridgeApi` (interface) since v4.4.0 (`AdbBridgeApi.kt:92`). GPU does NOT need a new bridge method; it reuses `shell(...)`.
2. The thermal call uses a **single shell-out with an inline shell loop** for ALL zones at once. GPU does NOT need that pattern — one path per vendor, single `cat` each. Cheaper.
3. The 2-stage strategy (sysfs first, `dumpsys thermalservice` fallback) is the established convention for "vendor support is patchy". GPU has its own equivalent fallback question (see Open Questions).
4. `shell()` already swallows timeouts / failures and returns `""`. Caller checks for empty / negative. Mirror this for GPU.

### How AppViewModel consumes thermal Snapshot

In `AppViewModel.kt`:

- **Line 1107**: `var lastThermal = ThermalSnapshot(NaN, NaN, NaN, NaN)` — sentinel-init.
- **Lines 1177–1196**: every 4th capture-loop tick (cadence ~2 s), call `bridge.captureTemperature(deviceId)`, splat fields into `lastThermal` (ADDITIVE — preserves `t.thermalAvailable` + `t.diagnostic`).
- **Lines 1284–1330**: sample-recording block reads `lastThermal.skin / dieCpu / gpu / cpu`, records into `tempCpuHistory`, `tempGpuHistory`, `tempSkinHistory`, `tempDieCpuHistory`, and their parallel `TimedSample` variants.
- **Line 1676** + 1727 + 1824: `thermalAvailable` flag propagated all the way into the persisted session payload + report HTML.

GPU plugs in identically:

- `var lastGpu = GpuSnapshot(NaN, false, null)` (or similar) at line ~1107.
- Same tier-cadence (every ~2 s — GPU usage is a noisy ~1 Hz metric and that matches GameBench's 1 Hz; faster is wasted I/O).
- New per-tick `gpuUsageHistory` + `gpuUsageTimed` arrays (mirror `tempCpuHistory` shape).
- New `gpuAvailable` + `gpuDiagnostic` persisted to the session.

NB: the brief says "expose via StateFlow". AppViewModel does NOT use raw `StateFlow` for thermal — it uses a `MutableState` HUD field + ArrayList histories that get folded into the final report. GPU should follow the **same pattern** (HUD live value + history arrays), not introduce a new StateFlow.

### Existing test conventions (verified against `AdbBridgeThermalTest.kt`)

- **Fixtures = inline Kotlin heredoc strings** (`"""..."""` with `.trimIndent()`). No resource files. The `src/test/resources/logcat-fixtures/` directory is for **logcat-line** fixtures (auto-event detection v4.4.0), NOT for sysfs samples.
- **Tests are PURE** — they call `AdbThermalParser.parseThermalZonesOutput(fixture)` directly. No `FakeAdbBridge`, no mocks, no I/O.
- For wiring tests (`AppViewModel` integration), use `FakeAdbBridge` with the new `shellResponses` substring-keyed map already used by `DumpsysPoller` tests (`FakeAdbBridge.kt:175-190`):
  ```kotlin
  fake.shellResponses["/sys/class/kgsl/kgsl-3d0/gpubusy"] = "12345 56789"
  ```
- Plausibility window for thermal is `0..60 C` (skin/battery) / `0..120 C` (die). GPU usage % has its own obvious window: `0..100` integer. Mirror the same rejection pattern.

### CLAUDE.md hard rules (verified by re-reading)

- **Tests pure, no mocks** — applies. Parser tests = inline strings. Bridge tests = `FakeAdbBridge`.
- **Regex compiled as `private val` top-level** — applies if we use regex (GPU `gpubusy` format is `(\d+)\s+(\d+)`, single regex).
- **Single-source-of-truth for catalogs** — applies. `GpuVendorCatalog.kt` MUST be the only place that knows about Mali / Adreno / PowerVR paths, same way `SdkSignatureCatalog.ALL` is the only place SDK signatures live (CLAUDE.md operative rule, v4.4.0).
- **ToolResolver for external binaries** — N/A for Sprint 1 (no new tools).
- **`AppViewModel` only speaks `core.model.*`** — applies. `GpuSnapshot` MUST live in `core/model/`, NOT in `core/gpu/`. Same place as `ThermalSnapshot`.

---

## Affected Areas

| Path | Reason |
|---|---|
| `src/main/kotlin/com/gameperf/desktop/core/GpuUsageParser.kt` | NEW. Pure parser. Mirrors `AdbThermalParser` shape. |
| `src/main/kotlin/com/gameperf/desktop/core/GpuVendorCatalog.kt` | NEW. Catalog of (vendor, sysfs path, format-kind) tuples. Single source of truth per CLAUDE.md anti-duplication rule. |
| `src/main/kotlin/com/gameperf/desktop/core/model/Metrics.kt` | EXTEND. Add `data class GpuSnapshot(usagePct: Int, gpuAvailable: Boolean, diagnostic: GpuDiagnostic?)`. **In Metrics.kt next to `ThermalSnapshot`, NOT in core/gpu/** — `AppViewModel` only speaks `core.model.*`. |
| `src/main/kotlin/com/gameperf/desktop/core/model/GpuDiagnostic.kt` | NEW (or co-located in Metrics.kt). Mirrors `ThermalDiagnostic` shape — raw probed paths + reason enum. |
| `src/main/kotlin/com/gameperf/desktop/core/AdbBridge.kt` | EXTEND. Add `fun captureGpuUsage(deviceId: String): GpuSnapshot`. Wires GpuVendorCatalog → `shell()` calls → GpuUsageParser. |
| `src/main/kotlin/com/gameperf/desktop/core/AdbBridgeApi.kt` | EXTEND. Add the new method to interface. `RealAdbBridge` delegates, `FakeAdbBridge` returns a scriptable. |
| `src/test/kotlin/com/gameperf/desktop/testing/FakeAdbBridge.kt` | EXTEND. Add `scriptedGpu: GpuSnapshot? = null` + `setGpu(...)` builder, mirror the v4.4.1 `setThermal` pattern. |
| `src/main/kotlin/com/gameperf/desktop/viewmodel/AppViewModel.kt` | EXTEND. New `lastGpu` field, new every-4-tick poll, new `gpuUsageHistory` + `gpuUsageTimed`, new `gpuAvailable` flag persisted to session payload. |
| `src/test/kotlin/com/gameperf/desktop/core/GpuUsageParserTest.kt` | NEW. Inline-fixture style. Mali / Adreno / unknown / OOR plausibility tests. |
| `src/test/kotlin/com/gameperf/desktop/core/GpuVendorCatalogTest.kt` | NEW. Order-of-probes test + uniqueness-of-paths test. |
| `src/test/kotlin/com/gameperf/desktop/core/AdbBridgeGpuTest.kt` | NEW. End-to-end via `FakeAdbBridge.shellResponses` per-vendor fixture. |

---

## Approaches

### Approach A — Vendor-detect-first, then probe (catalog-first)

`captureGpuUsage` reads `/proc/cpuinfo` or `getprop ro.hardware.gpu` once per session to identify Mali / Adreno / PowerVR, looks up the path in `GpuVendorCatalog`, single `cat` call.

- **Pros**: fewer shell-outs per poll (1 vs N). Lower DUT overhead. Matches GameBench's 3.8% overhead bar.
- **Cons**: needs a session-level detection step (extra complexity in capture-start). Cached vendor needs invalidation if device disconnects. PowerVR paths are non-uniform across SoCs — vendor-detect ≠ path-known.

### Approach B — Probe-first (try-all-paths)

Every poll, attempt `cat` on each known path in priority order. First non-empty wins. No vendor detection needed.

- **Pros**: zero state. Brutally simple. Mirrors `AdbThermalParser`'s "iterate every zone, classify each" pattern — same mental model.
- **Cons**: N shell-outs per poll on the cold (first) tick. With sysfs `2>/dev/null` redirects and adb-shell pipelining the cost is small (~1 ms each) but visible.

### Approach C — Hybrid: probe-once-then-cache (RECOMMENDED)

First tick: probe-first (Approach B). On hit, cache the vendor+path on the bridge instance for the rest of the session. Subsequent ticks: single `cat` (Approach A).

- **Pros**: zero session-start cost, single shell-out steady-state, no `getprop` round-trip. Mirrors how `ToolResolver.find()` caches its result (v4.2.13 pattern). Failure case = `gpuAvailable=false` after first probe failure, no repeated probing.
- **Cons**: slight bridge-state increase (a `MutableMap<deviceId, ProbedGpu>` or similar). Manageable.

### Approach C addendum — single shell-out for the probe

Use the SAME shell-loop trick `captureTemperature` uses: one shell command that tries every path and emits `<vendor>:<value>` lines. Parser identifies which line(s) populated.

```
for p in /sys/class/misc/mali0/device/utilization /sys/class/kgsl/kgsl-3d0/gpubusy <powervr-paths>; do
  echo "${p}:$(cat $p 2>/dev/null)"
done
```

Then `GpuUsageParser.parseGpuProbeOutput(probe)` returns a `GpuSnapshot`. Steady-state: cache the winning path, switch to per-path single-cat.

This is the **TDD-friendliest** option — the parser stays a pure string-in/snapshot-out function (mirrors `parseThermalZonesOutput`), and tests use inline heredoc fixtures.

---

## Recommendation

**Approach C with the single-shell-probe addendum.**

Rationale:

1. **Pattern parity with thermal** — single shell-out, pure parser, identical test ergonomics.
2. **No-root**: confirmed via public docs for Mali (`/sys/class/misc/mali0/device/utilization` is world-readable on Mali kbase driver). For Adreno `/sys/class/kgsl/kgsl-3d0/gpubusy` is world-readable on Android up to ~13; some OEMs lock it down via SELinux contexts post-Android-13, BUT `gpubusy` specifically has stayed readable (the locked-down ones are `gpu_clk_*` and `cur_devfreq`). Risk-rated below.
3. **Cacheable** — vendor doesn't change mid-session. Once probed, it's a 1-syscall poll.
4. **Graceful degradation** — failure path is identical to `thermalAvailable=false`: `gpuAvailable=false` + `GpuDiagnostic` with the list of paths probed and which returned empty. Report HTML can render a banner instead of "0%".
5. **Avoids a `core/gpu/` subpackage** — mirrors thermal's flat layout. **DECISION ITEM (Q1 below)**.

---

## Vendor-specific Notes

### Mali (ARM kbase driver) — CONFIRMED

- Path: `/sys/class/misc/mali0/device/utilization`
- Format: single integer 0..100
- Read mode: world-readable on standard kbase. **Confirmed** by ARM Mali documentation + linux-mali community posts.
- Naming variants: some older devices use `/sys/class/misc/mali0/device/utility` (typo in some vendor BSPs) or `/sys/devices/platform/<bus>/mali.0/utilization`. Latter requires probing — listed as low-priority candidate in the catalog.
- **No root needed**. **No SELinux issues**. Solid.
- Caveat from obs #289: **GPU frequency** (NOT usage) post-Android-12 Mali requires root. Usage % does NOT. Sprint 1 stays in scope.

### Adreno (Qualcomm kgsl driver) — CONFIRMED

- Path: `/sys/class/kgsl/kgsl-3d0/gpubusy`
- Format: two space-separated integers `"busy total"`. Percent = `(busy / total) * 100`. **Both are cumulative counters** since boot — so percent must be computed on **deltas** between two consecutive reads, NOT on the raw values. This is a critical implementation detail (see Risks).
- Read mode: world-readable up to Android 13. Android 14+ Pixel + recent Samsung lock it down via `selinux` on some build flavors but `gpubusy` specifically remains exposed because it's the user-friendly counter (the locked-down ones are `gpu_busy_percentage`, `perfcounter_*`).
- **No root needed** for read.
- Alternate paths (Adreno older): `/sys/kernel/gpu/gpu_busy` (a deprecated symlink), `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` (computed by kernel, single int — easier but absent on older Snapdragons). Catalog should probe `gpu_busy_percentage` first and fall back to `gpubusy` with delta math.

### PowerVR (Imagination / MediaTek Helio + Dimensity, Unisoc) — RISK

**Public confirmation level: WEAK.** PowerVR's DDK (Driver Development Kit) is closed-source. The sysfs paths exposed by MediaTek BSPs vary by SoC generation and by the MediaTek kernel branch. Best public references found:

- **MediaTek Helio P/G series + older Dimensity**: `/proc/mtk_mali/utilization` exists on some BSPs but it's MTK's wrapper around Mali, NOT PowerVR. Confusingly named.
- **PowerVR Rogue / Series 8XE / 9XE on MediaTek**: vendor-specific paths under `/sys/devices/platform/13000000.mfgsys-gpu/` or `/sys/module/pvrsrvkm/parameters/` have been observed in leaked MTK kernel sources, but `cat`-friendly utilization is NOT consistently exposed. The PowerVR PVRTune profiler reads this data over a privileged socket — NOT sysfs.
- **Unisoc (Tiger T-series)**: similar story — proprietary, no documented public path.

**Mitigation (FIRM)**: ship Sprint 1 with `GpuVendorCatalog` exposing Mali + Adreno paths only. Add a placeholder `POWERVR_CANDIDATES` list with one or two "best-effort" paths flagged `confidence=LOW`. When all PowerVR probes fail, `GpuSnapshot` is `gpuAvailable=false` with diagnostic reason `POWERVR_UNSUPPORTED`. The report HTML shows the banner "GPU usage no disponible en este dispositivo (PowerVR — soporte limitado)".

This mirrors the `ThermalUnavailableReason.ALL_ZONES_UNCLASSIFIED` graceful-degradation pattern (`AdbThermalParser.kt:163`). It's the SAME shape, just one bucket deep.

**Optional Sprint 1.5** (NOT Sprint 1): solicit user logs (the `GpuDiagnostic.rawZoneNames`-style probed-path list goes into the `.gameperf` export → community can crowdsource PowerVR paths). Same playbook that filled the thermal vendor catalog in v4.4.1.

---

## Open Questions (HUMAN DECISION REQUIRED before sdd-propose)

1. **Q1 — Subpackage or flat?** Brief says `core/gpu/GpuUsageParser.kt`. Thermal is FLAT under `core/`. Which wins?
   - Recommend **FLAT** (`core/GpuUsageParser.kt`, `core/GpuVendorCatalog.kt`) to match thermal's existing pattern. Diverging on Sprint 1 buys nothing.
   - Acceptable counter-argument: GPU will grow (sprint 2.x or beyond may add GPU freq, GPU memory) — pre-emptive `core/gpu/` package isolates the future. If yes, then thermal should also move to `core/thermal/` for symmetry (out of scope this sprint).
2. **Q2 — Delta accumulator location for Adreno `gpubusy`?** The "busy total" values are cumulative since boot. Two consecutive reads minus each other → instantaneous %. Options:
   - **Stateful parser** (`GpuUsageParser` holds last-read busy/total per device). Violates "pure parser" CLAUDE.md rule.
   - **Stateful bridge** (`AdbBridge` caches last reading next to the cached vendor). Matches the `pidStateMap` precedent in `captureCpuPercent(deviceId, pkg)` (`AdbBridge.kt:650-658`) — first sample returns `-1`, second+ returns delta. **Recommend this.** Parser stays pure.
   - **Caller-side accumulator** (`AppViewModel` keeps `lastAdrenoBusyTotal`). Leaks vendor specifics into the ViewModel. Reject.
3. **Q3 — Diagnostic verbosity in `.gameperf` export?** Thermal diagnostic caps `rawZoneNames` at 10 to bound export size (`AdbThermalParser.kt:33`). GPU has at most ~5 candidate paths total. Cap at 10 (consistent, room to grow) or no cap?
   - Recommend **cap at 10**, same constant style.
4. **Q4 — Capture cadence?** Brief implies same as thermal (every 4 ticks = ~2 s). GameBench samples at 1 Hz. Adreno needs at least 2 reads to compute delta — first delta lands at tick 8 (~4 s into session). Acceptable warm-up, or shorten to every 2 ticks for GPU to get first reading at ~2 s?
   - Recommend **every 4 ticks** for parity with thermal. The first-sample warm-up is documented in the report.
5. **Q5 — UI / HUD wiring?** Sprint 1 spec includes "AppViewModel: consume snapshot, expose via StateFlow". The existing thermal pattern uses HUD `MutableState` + history arrays, NOT a `StateFlow`. Confirm Sprint 1 follows the **thermal pattern**, not a new `StateFlow`.
   - Recommend **mirror thermal**: HUD-state field + history arrays. The brief's "StateFlow" wording is likely shorthand. Confirm with human before sdd-spec.

---

## Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Adreno SELinux lockdown on Android 14+ specific OEM builds | MEDIUM | `gpubusy` historically stayed exposed; catalog probes `gpu_busy_percentage` (newer, kernel-computed) first, falls back to `gpubusy`. If both 403/empty → `gpuAvailable=false` with reason `ADRENO_BLOCKED`. |
| Mali kbase path missing on older / non-Mali-G devices | LOW | Catalog includes the older `/sys/class/misc/mali0/device/utility` typo + `/sys/devices/platform/.../mali.0/utilization` alternates. |
| **PowerVR sysfs paths unverified** | HIGH | Sprint 1 ships Mali + Adreno only. PowerVR returns `gpuAvailable=false` + diagnostic. Crowdsourced path discovery in Sprint 1.5 / 2. |
| Adreno cumulative-counter wraparound on 32-bit kernels | LOW | `busy`/`total` are typically `u64` ns counters — wraparound is centuries away. Add a `delta < 0 → discard` guard anyway (matches `captureCpuPercent` precedent). |
| `gpubusy` returns `total=0` immediately after boot (idle GPU) | LOW | Plausibility guard: `if (deltaTotal <= 0) return UNAVAILABLE`. Same guard `captureCpuPercent` uses. |
| Adding `core/gpu/` subpackage diverges from thermal flat layout | LOW | Open Question #1. Defer to human. |
| `FakeAdbBridge.shellResponses` substring-first-match collision (Mali probe substring is a prefix of Adreno probe substring) | LOW | Both paths are unique substrings; no collision. Tests will assert. |
| Stateful bridge cache (vendor+last-counter) complicates `resetSessionState()` | MEDIUM | Add new `gpuCache` clear to existing `resetSessionState()` impl. One-line change. |
| Detekt complaints on the new heredoc-fixture-heavy test files | LOW | Thermal tests already pass detekt with same style. Same conventions apply. |
| Existing thermal `RE_THERMAL_TEMP` regex pattern name might suggest creating `RE_GPU_BUSY` — verify it's the right scope | LOW | `RE_THERMAL_TEMP` lives in `AdbBridge` companion. Add `RE_GPU_BUSY` (top-level `private val` per CLAUDE.md hot-path rule). |

---

## Ready for Proposal

**No — 5 Open Questions need human decision first.**

The substantive blockers (Mali + Adreno paths, parser pattern, FakeAdbBridge wiring, test fixture convention, AppViewModel plug-in points) are ALL confirmed. What's pending is **architectural taste calls** (subpackage layout, delta-accumulator location, cadence, HUD wiring style).

Recommend: human confirms Q1–Q5, then orchestrator runs `sdd-propose` with the answers baked in. Sprint 1 estimated effort: **2.5–3 days** TDD red→green (mirrors the temperature-not-shown sprint timing).
