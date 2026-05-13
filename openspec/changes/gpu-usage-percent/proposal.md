# Proposal: GPU Usage % on Android via sysfs (Sprint 1 — GameBench parity)

## Intent

Close highest-ROI GameBench gap (obs #289, issue #1, GAMEBENCH-COMPARISON.md): per-tick **GPU usage %** on Android, no root, Mali + Adreno + PowerVR-graceful-degradation. Mirrors v4.4.1 thermal pattern (pure parser, vendor catalog, plausibility window, diagnostic).

## Positioning vs GameBench (honest framing)

- **What we are**: host-side sysfs reader via `adb shell`. Zero on-device install. Works on any debuggable APK.
- **What we are NOT**: a reimplementation of GameBench's driver-perfcounter approach (their native `.so` reads GLES/Vulkan perfcounters in-process — requires Pro Android app, SDK link, or instrumented APK).
- **Tradeoff**: GameBench gets sub-counters (Vertex Load, Pixel Load) + driver-level accuracy at the cost of on-device footprint. We get whatever sysfs exposes for free.
- **Accuracy claim**: we report what sysfs reports — Mali kbase utilization (driver-computed int 0-100) and Adreno kgsl `gpubusy` delta (kernel-computed). Same numbers Snapdragon Profiler / ARM Streamline read for their high-level GPU view. We are NOT inventing a formula.
- **Genuine differentiator**: Sprint 1.5 crowdsource path adds PowerVR — GameBench docs literally state "hope to add PowerVR in the future" (obs #298).

## Scope

### In Scope
- `GpuSnapshot(usagePct, gpuAvailable, diagnostic?)` in `core/model/Metrics.kt`.
- `GpuVendorCatalog` SSOT: Mali `/sys/class/misc/mali0/device/utilization`; Adreno `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` then `gpubusy` delta; PowerVR placeholders `confidence=LOW`.
- Pure `GpuUsageParser` (string-in / snapshot-out, plausibility `0..100`).
- `AdbBridge.captureGpuUsage` Approach C (probe-once-then-cache, single-shell inline `for p in <paths>; do echo "$p:$(cat $p 2>/dev/null)"; done`).
- **Stateful bridge** per-device: vendor + last counter reading + **`perfcounterEnabledByUs: Boolean`** flag. Precedent: `captureCpuPercent` + `pidStateMap`. Cleared by `resetSessionState()`.
- **Adreno Android 13+ perfcounter enable** (Option B — probe-then-enable-then-retry-then-graceful-fail). See Approach.
- `AdbBridgeApi` extension. `FakeAdbBridge.scriptedGpu` + `setGpu` + `shellResponses` per-vendor fixtures + scriptable `echo`-write failure for perfcounter enable.
- `AppViewModel`: `lastGpu` MutableState, every-4-tick poll (~2 s, parity thermal), `gpuUsageHistory` + `gpuUsageTimed`, `gpuAvailable` persisted + report HTML banner + caveat tooltip (foreground-app attribution, peak-clock-drops-displayed-usage).
- `GpuDiagnostic` own file `core/model/GpuDiagnostic.kt` (mirrors `ThermalDiagnostic.kt`). Probed paths cap=10. `GpuUnavailableReason` enum: `ALL_PROBES_FAILED` / `ADRENO_BLOCKED` / `ADRENO_PERFCOUNTER_DISABLED` / `POWERVR_UNSUPPORTED` / `CAPTURE_THREW`.

### Out of Scope
- GPU freq (root post-Android-12 Mali).
- GPU memory / GPU temperature (already thermal).
- Per-process GPU.
- GPU sub-counters (Vertex Load / Pixel Load — Sprint 2+ candidate).
- StateFlow (HUD = MutableState + history arrays).
- `core/gpu/` subpackage (flat matches thermal).
- Web/cloud trends. PowerVR catalog discovery → Sprint 1.5 crowdsource.

## Capabilities

### New Capabilities
- `gpu-usage`: Android GPU usage % capture, vendor catalog, parser, plausibility window, Adreno perfcounter enable/disable lifecycle, snapshot wiring into `AppViewModel`, persistence to session payload + report.

### Modified Capabilities
- None. Purely additive — `core/spec.md` untouched; `AppViewModel` plug-in points mirror thermal poll without altering thermal behavior.

## Approach

**Approach C (probe-once-then-cache) + Option B for Adreno (probe-then-enable-then-retry-then-graceful-fail).**

### Mali read-flow (read-only, no enable)
1. `cat /sys/class/misc/mali0/device/utilization` (or alternates `utility` typo / platform path).
2. Parser plausibility window 0..100 → `GpuSnapshot`.
3. All paths empty → `gpuAvailable=false` reason `ALL_PROBES_FAILED`.

### Adreno read-flow (Option B — probe-then-enable-then-retry)
1. Probe `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` (kernel-computed single int %) → if non-empty: DONE, return % directly, no delta math, no perfcounter enable.
2. Probe `/sys/class/kgsl/kgsl-3d0/gpubusy` (cumulative counters):
   - non-empty AND prior reading exists → compute delta → DONE
   - non-empty AND no prior reading → store baseline, return UNAVAILABLE (warm-up, ~tick 8 / ~4 s)
3. **Both probes empty** → attempt enable:
   - `adb shell "echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter"`
   - success → mark session `perfcounterEnabledByUs=true` → retry step 2 next tick
   - failure (non-zero exit, SELinux denial, permission denied) → `gpuAvailable=false` reason `ADRENO_PERFCOUNTER_DISABLED` + diagnostic listing both probed paths + the failed echo command (Spanish tuteo-formal, parity v4.4.1 temperature-not-shown report style).
4. `AdbBridge.resetSessionState()` best-effort disable if `perfcounterEnabledByUs=true`:
   - `adb shell "echo 0 > /sys/class/kgsl/kgsl-3d0/perfcounter"`
   - failures swallowed silently (side-effect mitigation, NOT a hard contract — documented in test).

### PowerVR read-flow
All probes empty → `gpuAvailable=false` reason `POWERVR_UNSUPPORTED` + diagnostic listing probed paths (Sprint 1.5 crowdsource).

### Common
- First tick: ONE shell-out iterating catalog paths inline.
- Bridge caches `Map<deviceId, ProbedGpu(vendor, path, lastBusyTotal?, perfcounterEnabledByUs)>`.
- Subsequent ticks: single `cat <cachedPath>` → `parseGpuSingleRead(line, vendor, lastBusyTotal)`.
- All-probes-failed + enable-failed → never re-probe in session.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `core/GpuUsageParser.kt` | New | Pure parser. Mirrors `AdbThermalParser`. |
| `core/GpuVendorCatalog.kt` | New | SSOT vendor→path (Mali/Adreno/PowerVR) per CLAUDE.md anti-duplication. |
| `core/model/Metrics.kt` | Modified | Add `data class GpuSnapshot`. |
| `core/model/GpuDiagnostic.kt` | New | Own file (mirrors `ThermalDiagnostic.kt`). `@Serializable`. Enum incl. `ADRENO_PERFCOUNTER_DISABLED`. |
| `core/AdbBridge.kt` | Modified | `captureGpuUsage` + `gpuStateMap` (vendor + lastBusyTotal + perfcounterEnabledByUs). `resetSessionState()` clear + best-effort disable. New `private val RE_GPU_BUSY`. |
| `core/AdbBridgeApi.kt` | Modified | Add `captureGpuUsage` to interface. |
| `testing/FakeAdbBridge.kt` | Modified | `scriptedGpu` + `setGpu` builder + scriptable echo-write failure. |
| `viewmodel/AppViewModel.kt` | Modified | `lastGpu`, every-4-tick poll, histories, `gpuAvailable` persisted, caveat tooltip. |
| `core/GpuUsageParserTest.kt` | New | Inline-heredoc per-vendor fixtures. |
| `core/GpuVendorCatalogTest.kt` | New | Probe order + path uniqueness. |
| `core/AdbBridgeGpuTest.kt` | New | `FakeAdbBridge.shellResponses` end-to-end + delta accumulator + perfcounter enable/disable lifecycle + `resetSessionState` clear. |

## Test Strategy

- **TDD strict red→green** (`./gradlew test`, parity temperature-not-shown).
- **Parser tests**: inline `"""..."""` heredoc fixtures per vendor. NOT in `src/test/resources/logcat-fixtures/` (logcat-only).
- **Bridge tests**: `FakeAdbBridge.shellResponses["/sys/class/kgsl/kgsl-3d0/gpubusy"]="12345 56789"` substring-keyed.
- **Mandatory negatives**:
  - Plausibility window rejection (110% → unavailable).
  - Adreno delta wraparound (`deltaTotal <= 0` → unavailable, matches `captureCpuPercent`).
  - `total=0` post-boot idle.
  - PowerVR all-probes-empty → `POWERVR_UNSUPPORTED`.
  - **Adreno perfcounter-disabled path → `gpuAvailable=false` reason `ADRENO_PERFCOUNTER_DISABLED`** (FakeAdbBridge returns empty for both probe paths AND simulates echo-write failure).
  - **`resetSessionState()` best-effort disable** when `perfcounterEnabledByUs=true` (asserted echo `0 >` issued; failure swallowed).
- **Zero new test deps. Detekt clean.**

## Caveats (exposed in report HTML tooltip)

- System-wide GPU utilization — values "generally attributed to the foreground app" (GameBench-docs caveat, obs #298). Same caveat applies to us (sysfs is system-wide).
- Unnormalised: peak workloads may raise GPU clock, displaying lower % than expected.
- Adreno first-delta warm-up ~4 s (tick 8). Documented in HUD spinner copy.
- On Adreno Android 13+ OEMs requiring perfcounter enable: we issue the toggle. If denied (SELinux / locked OEMs), feature gracefully unavailable — report banner explains in Spanish tuteo-formal.

## Perf Budget

GameBench reports 3.8% CPU overhead (Pixel 6 Tensor GS101, full profiling, USB) as headline. Most of that is **on-device**. Ours is **host-side**: poll cost is on dev machine, not DUT. Per-tick = one `cat` (or `echo` once on Adreno A13+). Target: stay well under their 3.8% on the dev-machine side, and effectively ≈0% additional DUT load beyond the cost of an `adb shell cat`.

## Migration / Breaking

None. Additive. Nullable/default new fields preserve session payload deserialization.

## Estimated Effort

**2.5–3 days** TDD red→green (parity temperature-not-shown sprint). +0.25 day vs original estimate for Adreno enable/disable lifecycle + new negative test case.

## Roadmap / References

- Engram obs **#289** (3-sprint roadmap), **#288** (gap analysis), **#296** (exploration), **#298** (GameBench-docs research — Adreno enable + positioning intel).
- **GitHub issue #1** (GameBench parity tracking).
- **GAMEBENCH-COMPARISON.md** (working doc, repo root).

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Adreno SELinux lockdown on A14+ specific OEM builds | Medium | Probe `gpu_busy_percentage` first; `gpubusy` fallback; both empty + enable failure → `ADRENO_PERFCOUNTER_DISABLED`. |
| **Adreno `echo 1 > perfcounter` write fails on locked-down OEMs** | Medium | Graceful failure path: `gpuAvailable=false` reason `ADRENO_PERFCOUNTER_DISABLED` + diagnostic explaining SELinux limitation in Spanish tuteo-formal. |
| PowerVR sysfs paths unverified publicly | High | Ship Mali+Adreno only. PowerVR returns unavailable + diagnostic; crowdsource Sprint 1.5. |
| Adreno `gpubusy` cumulative-counter wraparound | Low | `u64` ns counter (centuries). Guard `delta < 0 → discard` per `captureCpuPercent`. |
| `gpubusy total=0` post-boot idle | Low | Guard `deltaTotal <= 0 → UNAVAILABLE`. Test mandatory. |
| Stateful bridge cache complicates `resetSessionState()` (+ best-effort disable) | Medium | Explicit `gpuStateMap.clear()` + best-effort `echo 0 >` if `perfcounterEnabledByUs`. Both asserted in `AdbBridgeGpuTest`. |
| Mali path variants | Low | Catalog includes `utility` typo + platform alternates. |
| `FakeAdbBridge.shellResponses` substring collision | Low | Paths are unique substrings; `GpuVendorCatalogTest` asserts uniqueness. |

## Rollback Plan

Single commit revert. No data migration. Disable flag: skip every-4-tick poll branch (one-line conditional). `AppViewModel` poll wrapped `try { ... } catch (t: Throwable) { lastGpu = GpuSnapshot(-1, false, GpuDiagnostic(reason=CAPTURE_THREW, ...)) }` mirroring thermal resilience.

## Dependencies

None. `AdbBridgeApi.shell(...)` (since v4.4.0) is the only primitive needed.

## Success Criteria

- [ ] `./gradlew test` green with `GpuUsageParserTest` + `GpuVendorCatalogTest` + `AdbBridgeGpuTest` (TDD red→green committed).
- [ ] Mali device: `usagePct ∈ [0,100]` within ~2 s.
- [ ] Adreno device (A12 or A13+ allowed): `usagePct ∈ [0,100]` within ~4 s (tick 8 first delta); perfcounter enable issued only when needed.
- [ ] Adreno locked-down OEM: `gpuAvailable=false` reason `ADRENO_PERFCOUNTER_DISABLED` + Spanish diagnostic.
- [ ] PowerVR device: `gpuAvailable=false` + diagnostic listing probed paths.
- [ ] Report HTML: chart when available, banner "GPU usage no disponible en este dispositivo" otherwise, caveat tooltip live.
- [ ] `.gameperf` roundtrip preserves `GpuSnapshot` + diagnostic.
- [ ] Detekt clean. Zero new test deps. `resetSessionState()` clears `gpuStateMap` AND issues best-effort `echo 0 >` when `perfcounterEnabledByUs=true` (asserted).
