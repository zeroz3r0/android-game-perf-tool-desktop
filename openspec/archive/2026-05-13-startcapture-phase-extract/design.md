# Design: startCapture Phase Extract

> Archived from engram observation #419 (`sdd/startcapture-phase-extract/design`).

## Technical Approach

Decompose `AppViewModel.startCapture` (lines 962-2145 ≈ 1183 LOC, CCN 230) into 6 private methods along **temporal lifecycle phases** (prelude → recording bootstrap → background launches → main loop → finalize). Hoist ~30 in-loop local accumulators into a private `CaptureAccumulators` data class so phase boundaries don't fight Kotlin's local-var scoping. Behavior is byte-equivalent: same call order, same scheduling gates, same emission contracts. Spec gate: `./gradlew check` green after `detekt.yml` thresholds reverted (CCN 200, thresholdInObjects 48, thresholdInInterfaces 30).

## Architecture Decisions

### Decision: Accumulators in a private `CaptureAccumulators` holder, not function parameters

**Choice**: Introduce `private class CaptureAccumulators` inside `AppViewModel` (or top of file, package-private) holding all `mutableListOf<>()` + `var lastX = ...` currently declared between lines 1119-1236.
**Alternatives considered**: (A) pass each accumulator as a function parameter (40+ params per phase — explodes LongParameterList), (B) promote to AppViewModel fields (leaks capture-session state outside `startCapture`, breaks reset semantics).
**Rationale**: A holder object preserves the local-variable lifetime invariant (created at session start, destroyed when `startCapture` returns) while letting phase methods mutate it in place. Mirrors existing project pattern where complex state is wrapped (e.g. `LastKnownFpsTracker`). Constructor takes no params; all fields default-initialized exactly as today.

### Decision: Phase methods are `private suspend fun` members of `AppViewModel`, NOT extracted to a separate file

**Choice**: All 6 phase methods stay private members of `AppViewModel.kt`.
**Alternatives considered**: Extract a new `CaptureLoop` class in `viewmodel/capture/`.
**Rationale**: Phase methods need direct access to `adb`, `iosBridge`, `scope`, `_liveMetrics`, `_captureError`, `_captureWarning`, `_processingStatus`, `_events`, `_detectorWarnings`, `_screen`, `_captureStartMs`, `captureJob`, `recordJob`, `eventDetector`, `recordProcess`, `recordSegment`, `recordChainFailures`, `captureStartTime`, `shouldStop`, `sidecarLifecycle`, `_deviceInfo`, `_isWifi`, `_selectedDevice`, `_gamePackage`. Extracting to a class means threading all that state through a constructor — much larger blast radius, real risk of behavioral drift. Internal refactor stays internal.

### Decision: 6 phases, decomposed along time, not by metric type

**Choice**: `setupCaptureState → bootstrapScreenRecording → launchEventDetector → launchChainedRecording → runCaptureLoop → finalizeSession`. Within `runCaptureLoop`, per-tick sub-extracts (`pollAndroidFastTier`, `pollAndroidMediumTier`, `pollIosTier`, `recordTickHistories`, `emitLiveMetricsTick`) are inlined as nested private methods called from the `while` body.
**Alternatives considered**: By metric type (CPU phase, FPS phase, thermal phase, ...). Rejected: each metric's read/append/emit cycle is interleaved across the loop; splitting by metric requires forwarding `sampleSecond`, `iterCount`, `frame`, `cpu`, etc. across 8+ functions — same explosion as the parameter alternative above.
**Rationale**: Temporal split matches how the code is already mentally chunked (prelude / loop / finalize), and the loop body's existing tier comments (FAST / MEDIUM / SLOW) already mark the natural sub-extract boundaries. Each phase has a single, time-bounded responsibility.

### Decision: Two-pass refactor with detekt revert as the LAST commit

**Choice**: Extract phases incrementally (one phase per commit), keep `detekt.yml` at threshold 230 throughout, revert thresholds in the final commit only after CCN measurement confirms ≤200.
**Alternatives considered**: Single big-bang commit. Rejected — bisect-hostile, no way to validate per-step CCN drop.
**Rationale**: Per-commit `./gradlew check` after each extract = continuous safety. The revert commit is small and atomic, easy to revert if downstream changes hit unexpected CCN bumps.

## Data Flow

    startCapture(durationSeconds)
         │
         ├─ setupCaptureState(device, pkg, isIosDevice)
         │     ├─ UI state reset (_screen, _isCapturing, _liveMetrics, _markers, _captureError, _captureWarning)
         │     ├─ shouldStop = false; recordChainFailures = 0
         │     └─ adb.resetSessionState() if Android
         │
         └─ captureJob = scope.launch {
                ├─ batteryStart, missedStart reads
                ├─ if (!isWifiMode && !isIosDevice) adb.disableCharging()
                ├─ bootstrapScreenRecording(device, isIosDevice) → returns (videoDir, sessionId, iosScreenCaptureId?)
                │     ├─ iOS path: sidecar.startScreenRecord
                │     └─ Android path: HardwareScoring tier → startSegmentWithRetry
                ├─ startTime = currentTimeMillis(); captureStartTime = startTime; _captureStartMs.value = startTime
                ├─ launchEventDetector(device, pkg, isIosDevice) [v4.4.0 auto detection]
                ├─ launchChainedRecording(device, sessionId, isIosDevice) [Android only, fires recordJob]
                ├─ launchUiTimer(startTime) → returns timerJob
                ├─ accumulators = CaptureAccumulators()
                ├─ runCaptureLoop(device, pkg, isIosDevice, accumulators, startTime, durationSeconds)
                │     └─ while (!shouldStop) { fast → medium (iter%4) → slow (iter%10) → record → emit }
                └─ finalizeSession(device, pkg, accumulators, startTime, batteryStart, missedStart,
                                    isIosDevice, sessionId, videoDir, iosScreenCaptureId, timerJob, durationSeconds)
                      └─ stop video → pull → concat → build SessionResult → render report → persist .gameperf → navigate
            }

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `src/main/kotlin/com/gameperf/desktop/viewmodel/AppViewModel.kt` | Modify | (1) Add `private class CaptureAccumulators` ~80 fields. (2) Extract 6 private (suspend where needed) phase methods. (3) Reduce `startCapture` body to ~40-60 LOC orchestrator. |
| `detekt.yml` | Modify | Revert: `CyclomaticComplexMethod` 230→200, `thresholdInObjects` 54→48, `thresholdInInterfaces` 33→30. Update H.7 TODO comment to `DONE in startcapture-phase-extract`. |

## Interfaces / Contracts

```kotlin
// Private inside AppViewModel.kt — no public surface.
private class CaptureAccumulators {
    // FPS / frame timing
    val fpsHistory = mutableListOf<Int>()
    val fpsTimed = mutableListOf<TimedSample>()
    val frameTimeAvgHistory = mutableListOf<Double>()
    val allFrameTimes = mutableListOf<Double>()
    val frameTimeTimed = mutableListOf<TimedSample>()
    var totalJank = 0
    var totalStutter = 0
    val jankTimed = mutableListOf<TimedSample>()
    val stutterTimed = mutableListOf<TimedSample>()
    // Memory
    val memHistory = mutableListOf<Long>()
    val nativeHistory = mutableListOf<Long>()
    val javaHistory = mutableListOf<Long>()
    val memTimed = mutableListOf<TimedSample>()
    val nativeTimed = mutableListOf<TimedSample>()
    val javaTimed = mutableListOf<TimedSample>()
    var lastMem: MemSnapshot? = null
    // CPU (app + total)
    val cpuHistory = mutableListOf<Int>()
    val cpuTimed = mutableListOf<TimedSample>()
    val cpuTotalHistory = mutableListOf<Int>()
    var lastCpuTotalPct: Int = -1
    // Thermal (cpu / gpu / skin / dieCpu)
    val tempCpuHistory = mutableListOf<Double>()
    val tempGpuHistory = mutableListOf<Double>()
    val tempSkinHistory = mutableListOf<Double>()
    val tempDieCpuHistory = mutableListOf<Double>()
    val tempCpuTimed = mutableListOf<TimedSample>()
    val tempGpuTimed = mutableListOf<TimedSample>()
    val tempSkinTimed = mutableListOf<TimedSample>()
    val tempDieCpuTimed = mutableListOf<TimedSample>()
    var lastThermal = ThermalSnapshot(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
    // FPower
    val fpowerHistory = mutableListOf<Double>()
    val fpowerTimed = mutableListOf<TimedSample>()
    var lastFPower = FPowerSnapshot()
    // GPU usage
    val gpuUsageHistory = mutableListOf<Int>()
    val gpuUsageTimed = mutableListOf<TimedSample>()
    var lastGpu = GpuSnapshot()
    // Loop state
    var iterCount = 0
    var consecutiveAdbFailures = 0
    var consecutiveNullFrames = 0
    val lastKnownFpsTracker = LastKnownFpsTracker(windowMs = LAST_KNOWN_FPS_WINDOW_MS)
}

// Phase method signatures (all private inside AppViewModel)
private fun setupCaptureState(isIosDevice: Boolean)
private suspend fun bootstrapScreenRecording(device: Device, isIosDevice: Boolean, sessionId: String): String?  // returns iosScreenCaptureId
private fun launchEventDetector(deviceId: String, pkg: String, isIosDevice: Boolean)
private fun launchChainedRecording(deviceId: String, sessionId: String, isIosDevice: Boolean)
private fun launchUiTimer(startTime: Long): Job
private suspend fun runCaptureLoop(device: Device, pkg: String, isIosDevice: Boolean,
                                    acc: CaptureAccumulators, startTime: Long, durationSeconds: Int)
private suspend fun finalizeSession(device: Device, pkg: String, isIosDevice: Boolean,
                                     acc: CaptureAccumulators, startTime: Long,
                                     batteryStart: Int, missedStart: Int,
                                     sessionId: String, videoDir: File, iosScreenCaptureId: String?,
                                     timerJob: Job, durationSeconds: Int)
```

## Exact Line-to-Method Extraction Map

| Current lines | Extracted method | Notes |
|---|---|---|
| 962-975 (prelude: state reset + resetSessionState) | `startCapture` body retains; or split: 962-966 stays in `startCapture` (device/pkg early-return), 967-975 → `setupCaptureState` | Early-return MUST stay outside the launch block. |
| 977 (`captureJob = scope.launch {`) | `startCapture` body retains | The coroutine builder is the orchestration point. |
| 978-985 (batteryStart, missedStart, isWifiMode, disableCharging) | Inline at top of `scope.launch` block in `startCapture` | These are 4 small reads — keeping them inline avoids returning a tuple. |
| 988-1022 (videoDir + sessionId + screenrecord bootstrap) | `bootstrapScreenRecording(...)` — returns `iosScreenCaptureId` or null. Sets `recordSegment`, `recordProcess` as field mutations. | Both iOS and Android branches go inside. |
| 1027-1031 (clock start + `_captureStartMs`) | Inline in `startCapture` after bootstrap returns | 3 lines, hot path identity. |
| 1043-1052 (auto event detection) | `launchEventDetector(deviceId, pkg, isIosDevice)` | Self-contained. |
| 1075-1107 (chained recording loop) | `launchChainedRecording(deviceId, sessionId, isIosDevice)` | Owns `recordJob`. |
| 1111-1117 (UI timer) | `launchUiTimer(startTime)` returns Job | Caller cancels in finalize. |
| 1119-1236 (accumulator declarations) | Replaced by `acc = CaptureAccumulators()` (one line) | Mass substitution. |
| 1238-1594 (the `while (!shouldStop)` loop body) | `runCaptureLoop(...)` | The biggest extraction. Loop CCN currently ~150 standalone. Within: `pollAndroidFastTier`, `pollAndroidMediumTier`, `pollIosTier`, `recordHistories`, `emitLiveMetricsTick` as nested private methods if standalone CCN of `runCaptureLoop` still exceeds 180. |
| 1596-1601 (finalElapsed) | First lines of `finalizeSession` | |
| 1604-1605 (cancel timer/record jobs) | `finalizeSession` early steps | timerJob passed in. |
| 1607-1611 (event detector stop) | `finalizeSession` | |
| 1613-2145 (full finalize: stop record → pull → concat → SessionResult → report → persist → nav) | `finalizeSession` body | The largest method post-refactor; CCN ~80-100. |

## Per-tick sub-extract policy (only if needed)

After extracting the 6 top-level phases, measure `runCaptureLoop` CCN. If > 180, sub-extract these from inside the `while` body:

- `pollFastTier(device, pkg, isIosDevice): TickReadings` — returns `(frame, cpu, battery)` data class.
- `pollMediumTier(device, isIosDevice, acc, iterCount)` — Android-only thermals + fpower + gpu poll into `acc`.
- `pollSlowTier(device, pkg, isIosDevice, acc, iterCount)` — Android-only memory.
- `recordTickHistories(acc, frame, cpu, sampleSecond, iterCount, isIosDevice)` — all the `if (cpu > 0)` / `if (memNow != null)` / thermal append blocks.
- `emitLiveMetricsTick(acc, frame, cpu, battery, displayFps, iterCount, currentElapsed)` — the `_liveMetrics.value = LiveMetrics(...)` block (currently lines 1530-1593, CCN ~30 alone from the ternaries).

The `emitLiveMetricsTick` extract is the single biggest CCN win (-25 to -30) — it MUST happen if pass-1 doesn't hit ≤200.

## Testing Strategy

| Layer | What to test | Approach |
|---|---|---|
| Unit | None new | Refactor is behavior-preserving; existing `AppViewModel*Test.kt` files (200+ tests) are the safety net. |
| Integration | Existing `FakeAdbBridge`-driven captures | Run `./gradlew test` after every commit. NONE should require source edits. |
| Manual smoke | 1 Android + 1 iOS end-to-end | Verify `.gameperf` artifact + `SessionResult` shape byte-equivalent pre/post refactor (within sampling noise). |
| Static analysis | Detekt enforces CCN/object/interface gates | `./gradlew detekt` after final commit confirms thresholds 200/48/30 hold. |

## Migration / Rollout

No migration. Internal refactor only. No data shape changes. No feature flags. Land as a single PR with 6-8 small commits (one per extraction + accumulators commit + detekt revert commit). PR description references the H.7 TODO and the `network-bandwidth-total-app` dependency.

## Open Questions

- [ ] **Q1**: Confirm exact pre-FPower value of `thresholdInObjects`. User's instruction says "revert by 6" → 54-6 = 48. Tasks phase MUST verify `AdbBridge` current function count via `grep -c "fun " src/main/kotlin/com/gameperf/desktop/core/AdbBridge.kt` before committing the threshold revert. If current count is below 48, the threshold can match `<count>` exactly; if equal to 48, fine; if above 48, the refactor surfaces an UNRELATED `TooManyFunctions` violation that this change does NOT fix (would need a sibling AdbBridge split). NOT a blocker for the refactor itself — only for the threshold revert commit.
- [ ] **Q2**: Should `pollFastTier` and friends become a class (`CaptureTickPoller`) for testability later? OUT OF SCOPE for this change but design phase notes for `network-bandwidth-total-app`: when adding the network metric, consider if the medium-tier poll has grown enough to justify its own class.
