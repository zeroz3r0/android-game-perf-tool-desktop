# Design: in-app dependency bootstrap

## Technical Approach

Implement automatic in-app installation of external dependencies (`adb`, `ffmpeg`) to eliminate manual setup via scoop/chocolatey/Homebrew. Follow the proposal's Option C + D: bundle `adb` at build-time, download `ffmpeg` on-demand when recording is attempted. Reuse `ToolResolver.find` as the single source of truth for tool detection (per the existing rule in CLAUDE.md), and extract a reusable `Downloader` from `AutoUpdater` for the download logic.

## Architecture Decisions

### Decision: User-writable tools directory as step 0 in ToolResolver

**Choice**: Add `UserToolsDir` as the first candidate in `ToolResolver.find`, before PATH lookup.
**Alternatives considered**: (1) Use only bundled resources, (2) Check UserToolsDir after PATH.
**Rationale**: User-installed tools (via the bootstrap flow) should take precedence over system PATH to ensure the app works offline with the exact version it bundled or downloaded. Bundled tools in `<installDir>/tools/` remain step 0 for fresh installs.

### Decision: Extract reusable Downloader from AutoUpdater

**Choice**: Create `Downloader` object with buffer 8192, progress callback, temp file handling — cloned pattern from `AutoUpdater.downloadUpdate`.
**Alternatives considered**: (1) Inline download logic in `ToolInstaller`, (2) Use a Kotlin HTTP client library.
**Rationale**: The existing pattern works (battle-tested in AutoUpdater), keeps dependencies low, and follows the project's "no new deps unless necessary" philosophy. The download logic is already pure enough to extract.

### Decision: SHA256 verification strategy — best-effort

**Choice**: Verify SHA256 when hash is available (ffmpeg from gyan.dev); for adb bundled in resources, verify minimum file size only.
**Alternatives considered**: (1) No verification, (2) Require SHA256 for all downloads.
**Rationale**: Google does not publish SHA256 for platform-tools, so requiring it would break the bundled adb flow. Gyan publishes hashes for ffmpeg builds, so verification is feasible there. Best-effort strikes the right balance.

### Decision: On-demand ffmpeg download triggered by recording attempt

**Choice**: Check ffmpeg availability only when user clicks "Grabar video", not at app startup.
**Alternatives considered**: (1) Check at startup alongside adb.
**Rationale**: Most users don't record video; checking at startup adds latency and prompts for a tool most users won't need. The proposal explicitly calls for on-demand.

### Decision: Banner UI following UpdateBanner pattern

**Choice**: Reuse the visual style and interaction pattern from `UpdateBanner` for `DepsBootstrapBanner`.
**Alternatives considered**: (1) Create entirely new UI component from scratch, (2) Use generic dialog.
**Rationale**: Consistency with existing UI patterns, fewer files to maintain. The banner pattern handles primary CTA + secondary action well.

## Data Flow

```
AppViewModel.init()
    │
    ├─► DependencyBootstrap.check()
    │       │
    │       ├─► ToolResolver.find("adb") ──► null?
    │       │                                   │
    │       │   (bundled adb exists?) ──YES──► copy to UserToolsDir
    │       │                                        │
    │       │   NO ──► missingDeps += "adb" ◄───────┘
    │       │
    │       └─► Update StateFlows: missingDeps, bootstrapProgress
    │
    ▼
HomeScreen ──► observes missingDeps ──► Shows DepsBootstrapBanner
    │
    ├─► User clicks "Descargar FFmpeg"
    │       │
    │       └─► ToolInstaller.download(url, targetDir, sha256?)
    │               │
    │               ├─► Downloader.download(url, onProgress)
    │               ├─► extract ZIP to UserToolsDir
    │               ├─► verify SHA256 (if provided)
    │               ├─► chmod Unix (if Unix)
    │               └─► Update bootstrapProgress, missingDeps
    │
    └─► User clicks "Abrir en navegador" (fallback)
            │
            └─► Open URL in system browser
```

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `core/UserToolsDir.kt` | Create | Platform-specific paths: `%LOCALAPPDATA%\GamePerf\tools\`, `~/Library/Application Support/GamePerf/tools/`, `~/.local/share/GamePerf/tools/` |
| `core/ToolInstaller.kt` | Create | Download (reusing AutoUpdater's Downloader pattern), ZIP extraction, SHA256 verification, chmod Unix |
| `core/DependencyBootstrap.kt` | Create | Orchestrates adb/ffmpeg checks, manages state, delegates to ToolInstaller |
| `core/ToolResolver.kt` | Modify | Add `UserToolsDir` as step 0 candidate via `toolSpecificCandidates` |
| `viewmodel/AppViewModel.kt` | Modify | Add StateFlows: `missingDeps`, `bootstrapProgress`, `bootstrapError`; invoke `DependencyBootstrap.check()` in `init()` |
| `ui/screens/HomeScreen.kt` | Modify | Add `DepsBootstrapBanner` Composable, observe ViewModel StateFlows |
| `core/AutoUpdater.kt` | Modify | Extract `Downloader` object (or keep download logic internal and expose a function) |
| `test/core/ToolResolverTest.kt` | Extend | Add tests for user-writable candidate (mock UserToolsDir) |
| `test/core/DependencyBootstrapTest.kt` | Create | Tests: adb missing/bundled, ffmpeg missing/available, progress states |
| `test/core/ToolInstallerTest.kt` | Create | Tests: SHA256 verification (valid/invalid), ZIP extraction, chmod (mock) |

## Interfaces / Contracts

### UserToolsDir

```kotlin
object UserToolsDir {
    /** Base directory for user-installed tools. */
    fun base(): File

    /** Path to a specific tool binary inside UserToolsDir. */
    fun tool(toolName: String): File
}
```

### ToolInstaller

```kotlin
object ToolInstaller {
    data class Progress(
        val stage: Stage,       // DOWNLOADING, EXTRACTING, VERIFYING
        val percent: Float      // 0.0 to 1.0
    )

    enum class Stage { DOWNLOADING, EXTRACTING, VERIFYING }

    /** Download tool from URL, extract to targetDir, optionally verify SHA256. */
    suspend fun download(
        url: String,
        targetDir: File,
        sha256: String? = null
    ): Result<File>

    /** Check disk space before downloading. */
    fun hasEnoughSpace(requiredMb: Long): Boolean
}
```

### DependencyBootstrap

```kotlin
object DependencyBootstrap {
    data class MissingTool(val name: String, val downloadUrl: String, val sha256: String?)

    /** Check all required tools, update AppViewModel StateFlows. */
    suspend fun check()
}
```

### AppViewModel StateFlows (additions)

```kotlin
val missingDeps: StateFlow<List<DependencyBootstrap.MissingTool>>
val bootstrapProgress: StateFlow<ToolInstaller.Progress?>
val bootstrapError: StateFlow<String?>
```

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Unit | `ToolResolver.findInCandidates` with UserToolsDir | Mock `UserToolsDir.base()` return value |
| Unit | `ToolInstaller.download` SHA256 verification | Provide known valid/invalid hashes, verify Result |
| Unit | `DependencyBootstrap.check` state transitions | Mock ToolResolver, verify StateFlow emissions |
| Unit | `UserToolsDir` path construction | Pure function, test Windows/Mac/Linux paths |
| Integration | Banner appears when ffmpeg missing + record clicked | ComposeTest, verify banner renders |
| Integration | Full download + install flow (mock HTTP) | Mock web server, verify extraction |

## Migration / Rollout

No migration required — this is a net-new feature. Existing users with `adb` in PATH or previously installed tools are unaffected. The bundled `adb` in `<installDir>/tools/` will be added in the CI workflow (out of scope for this design).

## Open Questions

- [ ] Should bundled `adb` be copied to `UserToolsDir` on first run, or should `ToolResolver` check both `<installDir>/tools/` and `UserToolsDir`? — The proposal says "copy/extract to UserToolsDir" for bundled adb, but checking both is simpler. **Decision needed**: copy vs. dual lookup.
- [ ] Should the app check for updates to bundled tools (adb version)? — Currently out of scope per proposal, but could be a future enhancement.
