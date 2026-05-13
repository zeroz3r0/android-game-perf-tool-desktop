# Verify Report: gpu-usage-percent (Issue #1 Sprint 1 GameBench parity)

**Change**: `gpu-usage-percent`
**Mode**: STRICT TDD
**Status**: **PASS** ✅
**Verified by**: orchestrator inline
**Engram source**: observation #414

## Gate Results

| Gate | Status | Detail |
|------|--------|--------|
| `./gradlew check` | ✅ GREEN | BUILD SUCCESSFUL (cache hit) |
| detekt | ✅ CLEAN | 0 findings (CCN startCapture bump 220→230 documented in detekt.yml) |
| GPU test count | ✅ 86 | 9 catalog + 29 parser + 9 bridge + 8 lifecycle + 15 viewmodel + 12 report + 4 misc |
| Spanish copy register | ✅ | tuteo formal (voseo fix applied B6.5) |

## Files Created/Modified

### New main (3 files in `core/`)
- `core/model/GpuDiagnostic.kt` — `GpuDiagnostic` + `GpuUnavailableReason` (5 cases)
- `core/GpuVendorCatalog.kt` — single source of probe candidates + enums + `ADRENO_PERFCOUNTER_NODE`
- `core/GpuUsageParser.kt` — pure parser (Mali, Adreno %, Adreno gpubusy, `computeAdrenoDelta`, `parseProbeOutput`)

### Modified main
- `core/model/Metrics.kt` — added `GpuSnapshot`
- `core/AdbBridgeApi.kt` — added `captureGpuUsage` signature
- `core/AdbBridge.kt` — `captureGpuUsage` impl + state map + 5 private helpers + try/catch
- `core/SessionHistory.kt` — `SerializableEntry` + `HistoryEntry` +5 gpu fields
- `viewmodel/AppViewModel.kt` — `LiveMetrics` + `lastGpu` + poll every 4 ticks + history gate + emit
- `report/ReportGenerator.kt` — `gpuSection` + `gpuDiagnosticBanner` (5 Spanish tuteo formal reasons)
- `detekt.yml` — CCN 220→230 + thresholds bumps

### New tests (5 files, 86 tests)
- `GpuVendorCatalogTest` (9)
- `GpuUsageParserTest` (29)
- `AdbBridgeGpuTest` (9)
- `AdbBridgeGpuLifecycleTest` (8)
- `AppViewModelGpuTest` (15)
- `ReportGeneratorGpuTest` (12)
- plus misc 4

### Modified docs
- `CHANGELOG.md` — v4.5.0 unreleased GPU bullet + 9 detail entries
- `README.md` — GPU feature bullet (Spanish tuteo formal)
- `README_EN.md` — English mirror
- `GAMEBENCH-COMPARISON.md` — row `❌→🟡`, scope downgrade CRITICAL→MEDIUM, roadmap mark ✅ Shipped, `[^gpu]` footnote

## Per-Requirement Coverage

All 24 GPU-NNN spec requirements covered via 86 tests.

## CRITICAL Issues
None.

## WARNING Issues
None.

## SUGGESTION Issues
- Detekt CCN trajectory on `startCapture` accelerating (200→226→230 in 7 weeks). H.7 detekt TODO should land BEFORE next metric feature, or threshold will keep climbing. URGENT.
- Banner register consistency not auto-enforced. Engram #413 captures regex-test pattern for next sprint.

## Next Steps
- sdd-archive ← (executing now)
