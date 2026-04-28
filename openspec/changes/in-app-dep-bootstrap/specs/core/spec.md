# Delta for Core — Dependency Bootstrap

## ADDED Requirements

### Requirement: App detects missing adb at startup

The system MUST detect when `adb` is not available in the system path or bundled resources and MUST display a bootstrap banner prompting the user to install it.

#### Scenario: adb missing on first launch

- GIVEN the user has never installed adb system-wide
- WHEN the app starts and invokes `DependencyBootstrap.check()` in `AppViewModel.init()`
- THEN `ToolResolver.find("adb")` returns `null`
- AND the UI displays the DepsBootstrapBanner with message "ADB no está instalado"
- AND the banner shows "Instalar ADB" as primary CTA

#### Scenario: adb bundled in app resources

- GIVEN the app was packaged with bundled platform-tools in `<installDir>/tools/`
- WHEN `ToolResolver.find("adb")` is called
- THEN it MUST return a path to bundled adb as step 0 (user-writable candidate)
- AND no bootstrap banner is shown

---

### Requirement: App detects missing ffmpeg when recording

The system MUST detect when `ffmpeg` is not available when the user attempts to start video recording and MUST offer on-demand download.

#### Scenario: ffmpeg missing on record attempt

- GIVEN the user has never installed ffmpeg system-wide
- WHEN the user clicks "Grabar video" and `ToolResolver.find("ffmpeg")` returns `null`
- THEN the UI displays the DepsBootstrapBanner with message "FFmpeg requerido para grabar video"
- AND the banner shows "Descargar FFmpeg" as primary CTA

#### Scenario: ffmpeg available after previous download

- GIVEN the user previously downloaded ffmpeg via the bootstrap flow
- WHEN `ToolResolver.find("ffmpeg")` is called
- THEN it MUST return the path in `UserToolsDir` (step 0)
- AND no bootstrap banner is shown

---

### Requirement: Download succeeds with progress feedback

The system MUST download and install dependencies to the user-writable tools directory with SHA256 verification when available.

#### Scenario: ffmpeg download and install succeeds

- GIVEN the user clicks "Descargar FFmpeg" on the bootstrap banner
- WHEN `ToolInstaller.download(url, targetDir, sha256?)` is invoked
- THEN it MUST download the ZIP file with progress callbacks
- AND extract the binary to `UserToolsDir`
- AND verify SHA256 hash if provided (best-effort)
- AND set executable permissions on Unix
- AND update `AppViewModel.bootstrapProgress` with download/extraction states
- AND add the path to `ToolResolver` candidates for the current session

#### Scenario: bundled adb installation succeeds

- GIVEN the bundled platform-tools exist in app resources
- WHEN `DependencyBootstrap.check()` finds adb missing but bundled available
- THEN it MUST copy/extract to `UserToolsDir`
- AND verify minimum file size for platform-tools
- AND set executable permissions

---

### Requirement: Download fails gracefully with proxy error

The system MUST handle download failures and provide actionable error messages.

#### Scenario: download fails due to corporate proxy

- GIVEN the user is behind a corporate proxy that blocks downloads
- WHEN `ToolInstaller.download()` throws a network exception (proxy/timeout)
- THEN `AppViewModel.bootstrapError` MUST show "Error al descargar. Verifica tu conexión o proxy."
- AND the banner MUST show "Abrir en navegador" as fallback CTA
- AND the fallback URL MUST open the official download page

#### Scenario: download fails due to insufficient disk space

- GIVEN the system has less than 100MB free
- WHEN `ToolInstaller.download()` is attempted
- THEN it MUST detect low disk space before downloading
- AND show error "Espacio insuficiente. Libera al menos 100 MB."
- AND NOT attempt the download

---

### Requirement: Manual fallback for download failures

The system MUST provide a manual fallback path when automatic download fails.

#### Scenario: user opens manual fallback URL

- GIVEN the download failed for any reason
- WHEN the user clicks "Abrir en navegador" or "Descargar manualmente"
- THEN the app MUST open the official download URL in the system browser
- AND provide instructions to place the extracted binary in `UserToolsDir`

#### Scenario: user manually places tool in UserToolsDir

- GIVEN the user downloaded and extracted the tool manually
- WHEN `ToolResolver.find(toolName)` is called
- THEN it MUST check `UserToolsDir` as step 0 candidate
- AND return the path if the binary exists and is executable
