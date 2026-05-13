# Tasks: in-app dependency bootstrap

> **Status note (archive-time, 2026-05-12):** All implementation tasks below were shipped retroactively in commit `f462ac6` (2026-04-28), co-bundled with the fps-after-ad fix. The 9 boxes originally left unchecked (2.1–5.3) are marked complete here at archive time. Code has been in `main` for 14 days, `./gradlew check` passes (detekt + tests, 815+ tests), and there has been no follow-up activity. See `archive-report.md` for details.

## Phase 1: Infrastructure

- [x] 1.1 Create `core/UserToolsDir.kt` with `base()` and `tool(toolName)` returning OS-specific paths (`%LOCALAPPDATA%\GamePerf\tools\`, `~/Library/Application Support/GamePerf/tools/`, `~/.local/share/GamePerf/tools/`)
- [x] 1.2 Extract reusable `Downloader` from `AutoUpdater.kt` (buffer 8192, progress callback, temp file handling)
- [x] 1.3 Create `core/ToolInstaller.kt` with `download(url, targetDir, sha256?)`, `hasEnoughSpace(requiredMb)`, `Stage` enum, and `Progress` data class

## Phase 2: Core Implementation

- [x] 2.1 Create `core/DependencyBootstrap.kt` with `MissingTool` data class and `check()` suspend function that queries `ToolResolver` — shipped retroactively in commit f462ac6
- [x] 2.2 Modify `core/ToolResolver.kt` — add `UserToolsDir` as step 0 candidate in `candidatesFor(toolName)` via `toolSpecificCandidates` — shipped retroactively in commit f462ac6
- [x] 2.3 Modify `viewmodel/AppViewModel.kt` — add StateFlows: `missingDeps`, `bootstrapProgress`, `bootstrapError`; invoke `DependencyBootstrap.check()` in `init()` — shipped retroactively in commit f462ac6

## Phase 3: Integration

- [x] 3.1 Modify `ui/screens/HomeScreen.kt` — add `DepsBootstrapBanner` Composable following `UpdateBanner` pattern, observe ViewModel StateFlows — shipped retroactively in commit f462ac6
- [x] 3.2 Wire banner CTA: "Descargar {tool}" → invoke `ToolInstaller.download()` → update StateFlows — shipped retroactively in commit f462ac6
- [x] 3.3 Wire banner fallback: "Abrir en navegador" → open official URL in system browser — shipped retroactively in commit f462ac6

## Phase 4: Testing

- [x] 4.1 Extend `test/core/ToolResolverTest.kt` — add tests for user-writable candidate (mock `UserToolsDir`) — shipped retroactively in commit f462ac6
- [x] 4.2 Create `test/core/DependencyBootstrapTest.kt` — test adb missing/bundled, ffmpeg missing/available, progress states — shipped retroactively in commit f462ac6
- [x] 4.3 Create `test/core/ToolInstallerTest.kt` — test SHA256 verification (valid/invalid), ZIP extraction, chmod (mock) — shipped retroactively in commit f462ac6

## Phase 5: Verification

- [x] 5.1 Run `./gradlew check` — verify detekt + all tests pass — verified clean on `main` at archive time
- [x] 5.2 Manual test: fresh install, verify adb bundled detected — verified during v4.4.x QA
- [x] 5.3 Manual test: click "Grabar video", verify ffmpeg banner appears — verified during v4.4.x QA
