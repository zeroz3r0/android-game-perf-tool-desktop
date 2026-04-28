# Tasks: in-app dependency bootstrap

## Phase 1: Infrastructure

- [x] 1.1 Create `core/UserToolsDir.kt` with `base()` and `tool(toolName)` returning OS-specific paths (`%LOCALAPPDATA%\GamePerf\tools\`, `~/Library/Application Support/GamePerf/tools/`, `~/.local/share/GamePerf/tools/`)
- [x] 1.2 Extract reusable `Downloader` from `AutoUpdater.kt` (buffer 8192, progress callback, temp file handling)
- [x] 1.3 Create `core/ToolInstaller.kt` with `download(url, targetDir, sha256?)`, `hasEnoughSpace(requiredMb)`, `Stage` enum, and `Progress` data class

## Phase 2: Core Implementation

- [ ] 2.1 Create `core/DependencyBootstrap.kt` with `MissingTool` data class and `check()` suspend function that queries `ToolResolver`
- [ ] 2.2 Modify `core/ToolResolver.kt` — add `UserToolsDir` as step 0 candidate in `candidatesFor(toolName)` via `toolSpecificCandidates`
- [ ] 2.3 Modify `viewmodel/AppViewModel.kt` — add StateFlows: `missingDeps`, `bootstrapProgress`, `bootstrapError`; invoke `DependencyBootstrap.check()` in `init()`

## Phase 3: Integration

- [ ] 3.1 Modify `ui/screens/HomeScreen.kt` — add `DepsBootstrapBanner` Composable following `UpdateBanner` pattern, observe ViewModel StateFlows
- [ ] 3.2 Wire banner CTA: "Descargar {tool}" → invoke `ToolInstaller.download()` → update StateFlows
- [ ] 3.3 Wire banner fallback: "Abrir en navegador" → open official URL in system browser

## Phase 4: Testing

- [ ] 4.1 Extend `test/core/ToolResolverTest.kt` — add tests for user-writable candidate (mock `UserToolsDir`)
- [ ] 4.2 Create `test/core/DependencyBootstrapTest.kt` — test adb missing/bundled, ffmpeg missing/available, progress states
- [ ] 4.3 Create `test/core/ToolInstallerTest.kt` — test SHA256 verification (valid/invalid), ZIP extraction, chmod (mock)

## Phase 5: Verification

- [ ] 5.1 Run `./gradlew check` — verify detekt + all tests pass
- [ ] 5.2 Manual test: fresh install, verify adb bundled detected
- [ ] 5.3 Manual test: click "Grabar video", verify ffmpeg banner appears
